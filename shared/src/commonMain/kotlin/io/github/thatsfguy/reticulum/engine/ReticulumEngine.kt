package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.announce.KnownDestinations
import io.github.thatsfguy.reticulum.announce.extractCoordinates
import io.github.thatsfguy.reticulum.announce.extractDisplayName
import io.github.thatsfguy.reticulum.announce.parseAnnounce
import io.github.thatsfguy.reticulum.announce.parseTelemetryBytes
import io.github.thatsfguy.reticulum.announce.validateAnnounce
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.computePacketFullHash
import io.github.thatsfguy.reticulum.lxmf.SignatureVariant
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.lxmf.unpackMessage
import io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.MSG_BACKOFF_MS
import io.github.thatsfguy.reticulum.protocol.MSG_MAX_ATTEMPTS
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.HEADER_2
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_BROADCAST
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_TRANSPORT
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.store.DestinationRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Glue between the protocol stack, the active [Transport], and the
 * persistent repositories. One instance per app lifetime; held by the
 * Android foreground service.
 */
class ReticulumEngine(
    private val crypto: CryptoProvider,
    private val identityRepo: IdentityRepository,
    private val destinationRepo: DestinationRepository,
    private val messageRepo: MessageRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { 0L },
    private val displayNameProvider: () -> String = { "Reticulum Mobile" },
    /** Optional NomadNet page cache. When provided, [fetchNomadPage]
     *  writes successful fetches here so the UI can render the previous
     *  version on next visit while a fresh fetch runs in the background.
     *  Null in tests or when the caller doesn't want caching. */
    private val nomadPageCache: io.github.thatsfguy.reticulum.store.NomadPageCacheRepository? = null,
) {
    private val tokenCrypto = TokenCrypto(crypto)

    private var identity: Identity? = null

    private val _connection = MutableStateFlow(ConnectionState(TransportState.Disconnected, kind = null, changedAtMs = nowMs()))
    val connection: StateFlow<ConnectionState> = _connection

    private fun emitConnection(state: TransportState, kind: TransportKind?) {
        val prev = _connection.value
        if (prev.transport != state || prev.kind != kind) {
            _connection.value = ConnectionState(state, kind, nowMs())
        }
    }

    private val _events = MutableSharedFlow<EngineEvent>(replay = 0, extraBufferCapacity = 64)
    val events: Flow<EngineEvent> = _events.asSharedFlow()

    private var transport: Transport? = null
    private var pumpJob: Job? = null
    private var stateMirrorJob: Job? = null
    private var reannounceJob: Job? = null

    /** Active outbound Link sessions, keyed by link_id hex. The pump routes
     *  inbound packets to a session when destHash matches a key here. */
    private val activeSessions: MutableMap<String, LinkPump> = mutableMapOf()
    private val sessionsLock = kotlinx.coroutines.sync.Mutex()

    /**
     * v0.1.66 NomadNet link reuse cache. Keyed by destHash hex, holds
     * the [LinkSession] established for the most recent fetchNomadPage
     * to that destination plus whether we already sent LINKIDENTIFY on
     * it. A subsequent fetch reuses the session if it's still ACTIVE
     * AND the identify state matches — saves the LRPROOF round-trip
     * (~1.5-6s per hop) and is a prerequisite for partials.
     *
     * Mismatched identify state (cached anonymous → request identified
     * or vice versa) drops the cached session and re-establishes;
     * upstream Browser.py:1245-1250 makes the identify decision once
     * per Link, and we keep the same invariant.
     *
     * Locked under [sessionsLock] alongside activeSessions because
     * removals are coordinated when a link closes.
     */
    private data class NomadLink(val session: LinkSession, val identified: Boolean, val linkIdHex: String)
    private val nomadLinks: MutableMap<String, NomadLink> = mutableMapOf()

    /** Hashes we've already issued a path request for in this session,
     *  to keep the "unverified message → request identity" loop from
     *  spamming the mesh if the same unknown sender keeps writing. */
    private val pathRequestsSent = mutableSetOf<String>()

    /** Truncated packet hashes (16 bytes, hex) of opportunistic DATA
     *  packets we've already accepted in this session. Senders like
     *  Sideband retransmit until a proof arrives, so until our proof
     *  propagates back we may see the same packet multiple times.
     *  We always reply with a fresh proof but only store the message
     *  once. Bounded LRU; resets on engine restart. */
    private val seenIncomingDataHashes = LinkedHashSet<String>()
    private val seenIncomingDataCap = 256

    /** Truncated full packet hashes (16 bytes hex) of announces we've
     *  already ingested in this session. The same announce typically
     *  arrives via several redundant relay paths through a single rnsd
     *  attachment — without dedup, every announce shows up 5-6× in the
     *  log and the destination row gets pointlessly upserted each time. */
    private val seenAnnounceHashes = LinkedHashSet<String>()
    private val seenAnnounceCap = 512

    /** Wall-clock millis of our most recent announce. Used by
     *  [sendAnnounceIfDue] to coalesce burst announces — without this,
     *  reconnect storms + per-link announces could trip rnsd's
     *  default-on ingress-control throttle and leave our destination
     *  silently held on the server. */
    private var lastAnnounceMs: Long = 0L

    /** Wall-clock ms of the last ratchet rotation. 0 = never rotated.
     *  Time-gated by [DEFAULT_RATCHET_INTERVAL_MS] so peers don't race
     *  in-flight DATA against rotation. */
    private var lastRatchetRotationMs: Long = 0L

    /** Minimum gap between throttled announces. Explicit user actions
     *  (Send-announce button, identity reset) still bypass this. */
    private val announceMinIntervalMs: Long = 15 * 60_000L

    /** Load existing identity from storage, or generate a fresh one. */
    suspend fun ensureIdentity(): Identity {
        identity?.let { return it }
        val id = Identity(crypto)
        val stored = identityRepo.load()
        if (stored != null) {
            id.loadFromPrivateKeys(stored.encPrivKey, stored.sigPrivKey, stored.ratchetPrivKey)
        } else {
            id.generate()
            identityRepo.save(StoredIdentity(
                encPrivKey = id.encPrivKey!!,
                sigPrivKey = id.sigPrivKey!!,
                ratchetPrivKey = id.ratchetPrivKey,
            ))
        }
        identity = id
        // One-time cleanup: hide any self-row left behind by a prior
        // session that ingested our own looped-back announce. The
        // ingest filter prevents this happening again going forward.
        runCatching {
            val selfHex = id.hash!!.toHex()
            val existing = destinationRepo.get(selfHex)
            if (existing != null && !existing.hidden) {
                destinationRepo.delete(selfHex)
                _events.tryEmit(EngineEvent.Log("hid stale self-row $selfHex"))
            }
        }
        return id
    }

    /** Compute our `lxmf.delivery` destination hash. */
    suspend fun ourDestHash(): ByteArray {
        val id = ensureIdentity()
        return computeDestinationHash(crypto, "lxmf.delivery", id.hash!!)
    }

    /** Build the JSON payload that another app's QR scanner consumes. */
    suspend fun myIdentityCard(): IdentityCard.Payload {
        val id = ensureIdentity()
        val dest = ourDestHash()
        return IdentityCard.Payload(
            destHash = dest.toHex(),
            publicKey = id.publicKey.toHex(),
            ratchetPub = id.ratchetPubKey?.toHex(),
            displayName = displayNameProvider().ifBlank { "Reticulum Mobile" },
        )
    }

    /**
     * Apply an [IdentityCard] payload (manually pasted hex hash, scanned QR,
     * etc.) as a destination in the local store, marking it favorite by
     * default so it surfaces in the Messages tab. Re-uses any existing row
     * (preserving lastSeen / rssi / telemetry from prior announces).
     */
    suspend fun applyIdentityCard(card: IdentityCard.Payload): StoredDestination {
        val publicKey = card.publicKey.hexBytesOrThrow("publicKey", expectedLen = 64)
        val destBytes = card.destHash.hexBytesOrThrow("destHash", expectedLen = 16)
        val ratchet = card.ratchetPub?.hexBytesOrThrow("ratchetPub", expectedLen = 32)
        val identityHash = crypto.truncatedHash(publicKey, 16)

        val existing = destinationRepo.get(card.destHash)
        val merged = existing?.copy(
            identityHash = identityHash.toHex(),
            publicKey = publicKey,
            destHash = destBytes,
            ratchetPub = ratchet,
            displayName = card.displayName.ifBlank { existing.displayName },
            appName = "lxmf.delivery",
            appLabel = "LXMF delivery",
            favorite = true,
            source = if (existing.source == "announce") existing.source else "qr",
        ) ?: StoredDestination(
            hash = card.destHash,
            identityHash = identityHash.toHex(),
            publicKey = publicKey,
            destHash = destBytes,
            nameHash = ByteArray(0),  // filled in when an announce arrives
            ratchetPub = ratchet,
            displayName = card.displayName.ifBlank { "(QR import)" },
            appName = "lxmf.delivery",
            appLabel = "LXMF delivery",
            telemetry = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = nowMs(),
            rssi = null,
            favorite = true,
            source = "qr",
        )
        destinationRepo.upsertFromAnnounce(merged)
        _events.tryEmit(EngineEvent.Log("destination from QR: ${card.destHash}"))
        return merged
    }

    /**
     * Add a destination from a manually-typed hash. We don't have the public
     * key yet, so the row is a stub: visible in Nodes but not messagable
     * until an announce arrives and fills in the missing fields.
     */
    suspend fun addManualDestination(hashHex: String, label: String): StoredDestination {
        val normalized = hashHex.lowercase().filter { it != ':' && it != ' ' && it != '-' }
        require(normalized.length == 32 && normalized.all { it.isHexDigit() }) {
            "destination hash must be 32 hex chars (got ${normalized.length})"
        }
        val destBytes = normalized.hexBytesOrThrow("destHash", expectedLen = 16)
        val existing = destinationRepo.get(normalized)
        val merged = existing?.copy(
            displayName = label.ifBlank { existing.displayName },
            favorite = true,
        ) ?: StoredDestination(
            hash = normalized,
            identityHash = "",
            publicKey = ByteArray(0),
            destHash = destBytes,
            nameHash = ByteArray(0),
            ratchetPub = null,
            displayName = label.ifBlank { "(manual)" },
            appName = null,
            appLabel = null,
            telemetry = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = nowMs(),
            rssi = null,
            favorite = true,
            source = "manual",
        )
        destinationRepo.upsertManualStub(merged)
        _events.tryEmit(EngineEvent.Log("manual destination: $normalized"))
        return merged
    }

    suspend fun setFavorite(hashHex: String, favorite: Boolean) {
        destinationRepo.setFavorite(hashHex, favorite)
    }

    /**
     * Delete a destination and every message associated with it. Used
     * by the Messages tab's "Delete conversation" action. Idempotent —
     * fine to call when one or the other is already gone. Does NOT
     * affect the local identity, transports, or other destinations.
     *
     * If a future announce from the same destHash arrives, it will be
     * re-added (just without prior message history).
     */
    suspend fun deleteDestinationAndMessages(hashHex: String) {
        runCatching { messageRepo.deleteForContact(hashHex) }
            .onFailure { _events.tryEmit(EngineEvent.Log("delete messages failed: ${it.message}")) }
        runCatching { destinationRepo.delete(hashHex) }
            .onFailure { _events.tryEmit(EngineEvent.Log("delete destination failed: ${it.message}")) }
        _events.tryEmit(EngineEvent.Log("deleted destination + messages: $hashHex"))
    }

    /** Clear conversation history for a destination but keep the
     *  destination row (favorite, public key, last-seen). The user can
     *  trigger this from inside a conversation when they want to wipe
     *  history without losing the contact. */
    suspend fun deleteMessagesForDestination(hashHex: String) {
        runCatching { messageRepo.deleteForContact(hashHex) }
            .onFailure { _events.tryEmit(EngineEvent.Log("clear messages failed: ${it.message}")) }
        _events.tryEmit(EngineEvent.Log("cleared messages for: $hashHex"))
    }

    /** Public hook for transport-layer code to emit lines into the
     *  diagnostics log alongside engine-originated entries. */
    fun logExternal(line: String) {
        _events.tryEmit(EngineEvent.Log(line))
    }

    /**
     * Issue a Reticulum path request for [targetDestHash]. Mirrors
     * `RNS.Transport.request_path()` from Python RNS. Other peers on the
     * mesh that know the path respond by re-announcing the destination,
     * which refreshes our local transport node's routing table so a
     * subsequent LINKREQUEST has a definite forward path AND a return
     * path for the LRPROOF.
     *
     * Wire format (matches Python RNS Transport.py:2541-2588):
     *   - Packet: HEADER_1, DATA, PLAIN destination, BROADCAST transport, ctx=NONE
     *   - destHash: SHA-256(SHA-256("rnstransport.path.request")[:10])[:16]
     *   - payload:  target_dest_hash(16) + random_tag(16) = 32 bytes
     *     (only non-transport-enabled clients; transport instances also
     *      include their own identity hash, which we are not.)
     */
    suspend fun requestPath(targetDestHash: ByteArray) {
        require(targetDestHash.size == 16) { "targetDestHash must be 16 bytes" }
        val tx = transport ?: return
        val pathReqDest = computeDestinationHash(crypto, "rnstransport.path.request", ByteArray(0))
        val tag = crypto.randomBytes(16)
        val payload = targetDestHash + tag
        val packet = buildPacket(
            headerType = HEADER_1,
            transportType = TRANSPORT_BROADCAST,
            destType = io.github.thatsfguy.reticulum.protocol.DEST_PLAIN,
            packetType = PACKET_DATA,
            destHash = pathReqDest,
            context = CTX_NONE,
            payload = payload,
        )
        tx.send(packet)
        _events.tryEmit(EngineEvent.Log("path? ${targetDestHash.toHex()}"))
    }

    /**
     * Open a Reticulum Link to [destinationHash] and fetch a NomadNet page
     * via REQUEST/RESPONSE.
     *
     * Limitation: only single-packet pages work right now. Pages whose
     * encrypted plaintext exceeds the link MTU (~383 bytes after Token
     * overhead) need Reticulum Resource fragmentation, which is on the
     * follow-up list. Errors surface in the returned [Result].
     */
    suspend fun fetchNomadPage(
        destinationHash: String,
        path: String = "/page/index.mu",
        proofTimeoutMs: Long? = null,
        responseTimeoutMs: Long? = null,
        /** Optional request data sent as the third element of the
         *  envelope `[time, path_hash, data]`. For NomadNet form posts
         *  this is the dict `{ "field_<name>": "<value>", ... }`
         *  upstream Node.py:109-111 expects. `null` = simple GET (msgpack
         *  nil). The engine msgpack-encodes the whole envelope once;
         *  callers must NOT pre-encode `data` themselves. */
        data: Any? = null,
        /** v0.1.64: when true, send a CTX_LINKIDENTIFY packet
         *  (`LINKIDENTIFY = 0xFB`) right after LRPROOF lands, BEFORE
         *  the REQUEST. Required for ALLOW_LIST pages — server-side
         *  Node.py:152-154 keys auth on `remote_identity.hash`, which
         *  is None unless the client identifies. Default off because
         *  identifying on every link reveals the user's long-term
         *  identity hash to the page operator (see SPEC.md §11.6.6
         *  privacy note). UI surfaces this as an opt-in toggle. */
        identify: Boolean = false,
    ): Result<String> = runCatching {
        val dest = destinationRepo.get(destinationHash) ?: error("Unknown destination $destinationHash")
        require(dest.publicKey.size == 64) {
            "No public key for $destinationHash yet — wait for an announce"
        }
        val tx = transport ?: error("No transport attached — connect on the Settings tab first")
        // Adaptive timeout: scale with hop count. The flat 45 s used
        // pre-v0.1.47 timed out cleanly for 4-hop ALAYA and 6-hop
        // Cryptid_Node 2026-05-03 even when the path was known. Caller
        // can still pass an explicit override for tests / power users.
        val proofTimeout = proofTimeoutMs ?: proofTimeoutForHops(dest.hopCount)
        val responseTimeout = responseTimeoutMs ?: proofTimeoutForHops(dest.hopCount)

        // v0.1.66: try to reuse an existing ACTIVE link for this dest.
        // Saves LRPROOF round-trip on intra-node nav (index → about →
        // help). Mirrors Browser.py:1167-1213 — reuse if dest matches
        // and link is still ACTIVE; identify state must also match
        // because Browser fires identify once at link-up.
        val reused = sessionsLock.withLock {
            nomadLinks[destinationHash]?.takeIf {
                it.session.link.state == io.github.thatsfguy.reticulum.link.LinkState.ACTIVE &&
                    it.identified == identify
            }
        }
        if (reused != null) {
            _events.tryEmit(EngineEvent.Log("[${reused.linkIdHex}] reusing active link for $destinationHash$path"))
            val pathHash = crypto.sha256(path.encodeToByteArray()).copyOfRange(0, 16)
            val responseBytes = reused.session.request(pathHash, data, responseTimeout)
            if (responseBytes != null) {
                _events.tryEmit(EngineEvent.Log("page received (reused link): ${responseBytes.size} bytes"))
                return@runCatching cachePageAndReturn(
                    decoded = responseBytes.decodeToString(),
                    destinationHash = destinationHash,
                    path = path,
                    sizeBytes = responseBytes.size,
                    isPost = data != null,
                )
            }
            // Reused link's request timed out — fall through to fresh
            // establishment. Drop the cached session first.
            sessionsLock.withLock { nomadLinks.remove(destinationHash) }
            _events.tryEmit(EngineEvent.Log("reused link timed out — reconnecting"))
        }

        val targetSigPub = dest.publicKey.copyOfRange(32, 64)
        val (link, requestData) = Link.createInitiator(
            peerLongTermSigPub = targetSigPub,
            peerDestHash = dest.destHash,
            crypto = crypto,
            nowMs = nowMs(),
        )
        // §2.3 LINKREQ conversion: same rule as DATA. Upstream RNS Transport
        // silently drops a HEADER_1 LINKREQ addressed to a destination that
        // isn't locally attached — without a transport_id the transport node
        // has nothing to forward against and the LINKREQ never reaches the
        // responder. Reproduced 2026-05-03 against tools/test_nomadnet_node.py
        // via local transport node — LRPROOF never came back.
        val useHeader2Lr = dest.hopCount > 1 && dest.nextHop != null
        val linkReqPacket = buildPacket(
            headerType = if (useHeader2Lr) HEADER_2 else HEADER_1,
            transportType = if (useHeader2Lr) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_LINKREQ,
            destHash = dest.destHash,
            transportId = if (useHeader2Lr) dest.nextHop else null,
            payload = requestData,
        )
        // The link_id is computed from the LINKREQUEST as it was packed,
        // so we have to parse our own outbound packet to derive it.
        val parsed = parsePacket(linkReqPacket) ?: error("self-parse failed")
        link.setLinkIdFromPacket(parsed)

        val linkIdHex = link.linkId!!.toHex()
        val session = LinkSession(
            link = link,
            crypto = crypto,
            sender = { tx.send(it) },
            nowMs = nowMs,
            logger = { line -> _events.tryEmit(EngineEvent.Log("[$linkIdHex] $line")) },
        )
        if (useHeader2Lr) {
            _events.tryEmit(EngineEvent.Log(
                "[$linkIdHex] LINKREQ → using HEADER_2 (hops=${dest.hopCount}, transport_id=${dest.nextHop?.toHex()})"
            ))
        }
        sessionsLock.withLock { activeSessions[linkIdHex] = session }
        try {
            // Issue a path request for the target so each relay along
            // the way refreshes its forward path before we send the
            // LINKREQUEST. (We deliberately do NOT announce here — see
            // the throttle on sendAnnounceIfDue. Per-link announce
            // bursts are what trip rnsd's default-on ingress control
            // and silently park our destination on remote transports.)
            primePath(
                destHash = dest.destHash,
                requestPath = { hash -> requestPath(hash) },
                delayMs = { ms -> delay(ms) },
                onPathFailure = { _events.tryEmit(EngineEvent.Log("path? failed: ${it.message}")) },
            )

            tx.send(linkReqPacket)
            _events.tryEmit(EngineEvent.Log("link → $destinationHash (link_id=$linkIdHex)"))

            // Snapshot transport state at fetch start so we can detect a
            // mid-fetch disconnect on timeout — that's much more
            // diagnostic than the generic "no LRPROOF" message.
            val transportAtStart: Transport? = transport

            when (val proof = session.awaitProof(proofTimeout)) {
                is LinkSession.ProofResult.Validated -> {
                    _events.tryEmit(EngineEvent.Log("link active, requesting $path"))
                }
                is LinkSession.ProofResult.Invalid -> {
                    error("LRPROOF rejected: ${proof.reason}. ${session.diagnosticSummary()}")
                }
                LinkSession.ProofResult.Timeout -> {
                    val diag = classifyLinkFailure(dest.hopCount, dest.lastSeen, nowMs())
                    val rxDetail = session.diagnosticSummary().ifEmpty { "no inbound packets on link_id during wait" }
                    val transportNote = if (transport == null && transportAtStart != null) {
                        " Transport disconnected during the wait."
                    } else if (transport != null && transportAtStart != null && transport !== transportAtStart) {
                        " Transport reattached mid-fetch."
                    } else ""
                    error("No LRPROOF received within ${proofTimeout / 1000}s. $diag$transportNote\n  $rxDetail")
                }
            }

            // v0.1.64: optional LINKIDENTIFY before the REQUEST. Required
            // for ALLOW_LIST pages (server-side Node.py:152-154 reads
            // `remote_identity.hash` for the auth check). UI surfaces this
            // as an opt-in toggle so the user's long-term identity hash
            // isn't pinned to every public node they browse.
            if (identify) {
                val ourIdentity = ensureIdentity()
                val identifyCipher = link.buildIdentifyPayload(ourIdentity)
                val identifyPacket = buildPacket(
                    destType = io.github.thatsfguy.reticulum.protocol.DEST_LINK,
                    packetType = PACKET_DATA,
                    destHash = link.linkId!!,
                    context = io.github.thatsfguy.reticulum.protocol.CTX_LINKIDENTIFY,
                    payload = identifyCipher,
                )
                tx.send(identifyPacket)
                _events.tryEmit(EngineEvent.Log("[$linkIdHex] → LINKIDENTIFY (auth)"))
            }

            // Spec §11.1: request_path_hash is the 16-byte truncation of
            // SHA-256(path). Upstream Destination.register_request_handler
            // keys its handler dict on this 16-byte form, so a 32-byte
            // hash never matches a registered handler.
            val pathHash = crypto.sha256(path.encodeToByteArray()).copyOfRange(0, 16)
            val responseBytes = session.request(pathHash, data, responseTimeout) ?: run {
                // Link came up (we got the LRPROOF) but no body arrived.
                // The session diagnostic now distinguishes the cases:
                //  - silence after LRPROOF → server didn't run the handler
                //  - RESOURCE_ADV but partial RESOURCE → mid-stream drop
                //  - RESOURCE_ADV + complete but no PRF emit → handshake glitch
                val rxDetail = session.diagnosticSummary().ifEmpty { "no further packets after LRPROOF" }
                val transportNote = if (transport == null && transportAtStart != null)
                    " Transport disconnected during fetch." else ""
                error("No RESPONSE within ${responseTimeout / 1000}s — link came up but no body.${transportNote}\n  $rxDetail")
            }

            _events.tryEmit(EngineEvent.Log("page received: ${responseBytes.size} bytes"))
            // v0.1.66: cache the just-established session in nomadLinks
            // so the next fetch to this destHash can reuse it. Keep it
            // in activeSessions too (the engine pump dispatches inbound
            // packets through that map).
            sessionsLock.withLock {
                nomadLinks[destinationHash] = NomadLink(session, identified = identify, linkIdHex = linkIdHex)
            }
            return@runCatching cachePageAndReturn(
                decoded = responseBytes.decodeToString(),
                destinationHash = destinationHash,
                path = path,
                sizeBytes = responseBytes.size,
                isPost = data != null,
            )
        } finally {
            // v0.1.66: do NOT remove from activeSessions here — the
            // session is now cached in nomadLinks for reuse and still
            // needs to receive inbound packets via the engine pump.
            // Removal happens on KEEPALIVE timeout, link close, or
            // engine detach (clearNomadLinks).
        }
    }

    /**
     * Apply per-page cache rules and return the decoded page source.
     * Used by both the fresh-link and reused-link branches of
     * fetchNomadPage so caching behavior stays identical.
     */
    private suspend fun cachePageAndReturn(
        decoded: String,
        destinationHash: String,
        path: String,
        sizeBytes: Int,
        isPost: Boolean,
    ): String {
        // Cache only for plain GETs — form-post responses are body-
        // dependent and pollute the cache for subsequent GETs.
        if (!isPost) {
            // v0.1.62: respect server's `#!c=N` cache-TTL hint per
            // Browser.py:1315-1335. 0 = "do not cache".
            val ttl = io.github.thatsfguy.reticulum.nomad.Micron
                .parseDocument(decoded).cacheTtlSeconds
            if (ttl == 0) {
                _events.tryEmit(EngineEvent.Log("page cache: skipped — server set #!c=0"))
            } else {
                nomadPageCache?.let { cache ->
                    runCatching {
                        cache.put(io.github.thatsfguy.reticulum.store.StoredNomadPage(
                            destHash  = destinationHash,
                            path      = path,
                            source    = decoded,
                            fetchedAt = nowMs(),
                            byteSize  = sizeBytes,
                        ))
                    }.onFailure { _events.tryEmit(EngineEvent.Log("page cache write failed: ${it.message}")) }
                }
            }
        }
        return decoded
    }

    /**
     * Result of a propagation /get poll. [tidsAdvertised] = how many
     * messages the propagation node says it has queued for us.
     * [messagesStored] = how many we actually decoded and saved.
     * [resourceDeferred] = true when round 2 timed out, which usually
     * means the response went out as a multi-packet Resource that we
     * can't yet receive.
     */
    data class PropagationSyncResult(
        val tidsAdvertised: Int,
        val messagesStored: Int,
        val resourceDeferred: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Try [syncPropagation] against the best-ranked candidates from our
     * destinations table until one returns successfully or the candidate
     * list is exhausted. Ranking is `(hopCount asc, lastSeen desc)`:
     * closest first, freshest tie-break. We bail after [maxAttempts]
     * candidates so a network of 200+ propagation nodes doesn't pin the
     * UI for an hour.
     */
    suspend fun syncPropagationAuto(maxAttempts: Int = 5): PropagationSyncResult {
        val candidates = destinationRepo.getAll()
            .filter { it.appName == "lxmf.propagation" && it.publicKey.size == 64 && !it.hidden }
            .sortedWith(compareBy({ it.hopCount }, { -it.lastSeen }))
            .take(maxAttempts)

        if (candidates.isEmpty()) {
            return PropagationSyncResult(0, 0, false, "no propagation nodes seen yet")
        }
        _events.tryEmit(EngineEvent.Log(
            "propagation: ${candidates.size} candidate(s) ranked by hops; best=${candidates.first().hash} (${candidates.first().hopCount} hops)"
        ))
        for ((i, node) in candidates.withIndex()) {
            _events.tryEmit(EngineEvent.Log(
                "propagation: trying ${node.hash} (${node.hopCount} hops, ${(nowMs() - node.lastSeen) / 60_000}m ago) [${i+1}/${candidates.size}]"
            ))
            // Short timeouts so a dead node doesn't block the cascade.
            // First-hit wins; the loop exits as soon as one node returns
            // a non-error sync result with at least the round-1 list.
            val result = syncPropagation(
                propagationNodeHash = node.hash,
                proofTimeoutMs = 20_000L,
                roundTimeoutMs = 15_000L,
            )
            if (result.errorMessage == null) {
                return result
            }
            _events.tryEmit(EngineEvent.Log("propagation: ${node.hash} → ${result.errorMessage}"))
        }
        return PropagationSyncResult(0, 0, false, "all ${candidates.size} candidate(s) failed")
    }

    /**
     * Open a Reticulum Link to a propagation node and run the 3-phase
     * /get fetch. Each delivered LXMF blob is unpacked + saved via the
     * same code path as opportunistic delivery, so notifications fire
     * exactly the same way. Caller (UI / service) is responsible for
     * the polling cadence.
     */
    suspend fun syncPropagation(
        propagationNodeHash: String,
        proofTimeoutMs: Long = 45_000L,
        roundTimeoutMs: Long = 30_000L,
    ): PropagationSyncResult = try {
        val dest = destinationRepo.get(propagationNodeHash)
            ?: return PropagationSyncResult(0, 0, false, "Unknown propagation node $propagationNodeHash")
        require(dest.publicKey.size == 64) { "No public key for $propagationNodeHash yet — wait for its announce" }
        val tx = transport ?: error("No transport attached — connect on the Settings tab first")
        val id = ensureIdentity()

        val targetSigPub = dest.publicKey.copyOfRange(32, 64)
        val (link, requestData) = Link.createInitiator(
            peerLongTermSigPub = targetSigPub,
            peerDestHash = dest.destHash,
            crypto = crypto,
            nowMs = nowMs(),
        )
        // §2.3 LINKREQ conversion — see fetchNomadPage for the full why.
        val useHeader2Lr = dest.hopCount > 1 && dest.nextHop != null
        val linkReqPacket = buildPacket(
            headerType = if (useHeader2Lr) HEADER_2 else HEADER_1,
            transportType = if (useHeader2Lr) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_LINKREQ,
            destHash = dest.destHash,
            transportId = if (useHeader2Lr) dest.nextHop else null,
            payload = requestData,
        )
        val parsed = parsePacket(linkReqPacket) ?: error("self-parse failed")
        link.setLinkIdFromPacket(parsed)

        val linkIdHex = link.linkId!!.toHex()
        val session = LinkSession(
            link = link,
            crypto = crypto,
            sender = { tx.send(it) },
            nowMs = nowMs,
            logger = { line -> _events.tryEmit(EngineEvent.Log("[prop $linkIdHex] $line")) },
        )
        if (useHeader2Lr) {
            _events.tryEmit(EngineEvent.Log(
                "[prop $linkIdHex] LINKREQ → using HEADER_2 (hops=${dest.hopCount}, transport_id=${dest.nextHop?.toHex()})"
            ))
        }
        sessionsLock.withLock { activeSessions[linkIdHex] = session }
        try {
            primePath(
                destHash = dest.destHash,
                requestPath = { hash -> requestPath(hash) },
                delayMs = { ms -> delay(ms) },
                onPathFailure = { _events.tryEmit(EngineEvent.Log("[prop $linkIdHex] path? failed: ${it.message}")) },
            )
            tx.send(linkReqPacket)
            _events.tryEmit(EngineEvent.Log("propagation link → $propagationNodeHash"))

            when (val proof = session.awaitProof(proofTimeoutMs)) {
                is LinkSession.ProofResult.Validated -> Unit
                is LinkSession.ProofResult.Invalid -> error("LRPROOF rejected: ${proof.reason}")
                LinkSession.ProofResult.Timeout ->
                    error("no LRPROOF within ${proofTimeoutMs / 1000}s — propagation node may be down")
            }

            val client = PropagationClient(
                session = session,
                identity = id,
                crypto = crypto,
                sender = { tx.send(it) },
                logger = { line -> _events.tryEmit(EngineEvent.Log("[prop $linkIdHex] $line")) },
            )
            client.identify()
            // Tiny settle so the node processes our identify before /get.
            delay(250L)

            val result = client.pollAll(roundTimeoutMs = roundTimeoutMs)

            // Save each delivered LXMF blob via the opportunistic path.
            // Format on the wire is: source_hash(16) + sig(64) + msgpack
            // — same as a token-decrypted opportunistic packet plaintext.
            var stored = 0
            val ourDest = ourDestHash()
            for (blob in result.messagesReceived) {
                runCatching {
                    val msg = io.github.thatsfguy.reticulum.lxmf.unpackMessage(blob, ourDest, crypto)
                    val sourceHashHex = msg.sourceHash.toHex()
                    val senderRow = destinationRepo.get(sourceHashHex)
                    val variant = senderRow?.takeIf { it.publicKey.size == 64 }?.let {
                        val senderId = Identity(crypto)
                        senderId.loadFromPublicKey(it.publicKey)
                        io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature(msg, senderId, crypto)
                    }
                    val isUnverified = variant == null
                    val savedId = messageRepo.save(StoredMessage(
                        contactHash = sourceHashHex,
                        direction = "incoming",
                        content = msg.content,
                        title = msg.title,
                        timestamp = correctClocklessTimestamp(msg.timestamp, nowMs()),
                        state = if (!isUnverified) "verified" else "unverified",
                        rawPacket = if (isUnverified) blob else null,
                    ))
                    _events.tryEmit(EngineEvent.MessageReceived(
                        messageId = savedId,
                        contactHash = sourceHashHex,
                        content = msg.content,
                        verified = !isUnverified,
                    ))
                    if (isUnverified && pathRequestsSent.add(sourceHashHex)) {
                        runCatching { requestPath(msg.sourceHash) }
                    }
                    stored++
                }.onFailure {
                    _events.tryEmit(EngineEvent.Log("propagation msg unpack failed: ${it.message}"))
                }
            }

            PropagationSyncResult(
                tidsAdvertised = result.tidsAdvertised.size,
                messagesStored = stored,
                resourceDeferred = result.multiPacketDeferred,
            )
        } finally {
            sessionsLock.withLock { activeSessions.remove(linkIdHex) }
        }
    } catch (t: Throwable) {
        PropagationSyncResult(0, 0, false, t.message ?: t::class.simpleName)
    }

    /** Discard current identity, generate fresh keys, immediately re-announce. */
    suspend fun resetIdentity(): Identity {
        identity = null
        val id = Identity(crypto)
        id.generate()
        identityRepo.save(StoredIdentity(
            encPrivKey = id.encPrivKey!!,
            sigPrivKey = id.sigPrivKey!!,
            ratchetPrivKey = id.ratchetPrivKey,
        ))
        identity = id
        _events.tryEmit(EngineEvent.Log("identity reset (dest=${id.hash!!.toHex()})"))
        runCatching { sendAnnounce() }.onFailure {
            _events.tryEmit(EngineEvent.Log("re-announce after reset failed: ${it.message}"))
        }
        return id
    }

    fun attach(transport: Transport, kind: TransportKind) {
        detach()
        this.transport = transport
        emitConnection(transport.state.value, kind)

        // Reset announce throttle on every (re)attach. Different rnsd
        // = no return path for our destination yet, so we must announce
        // immediately so the new transport's routing table learns where
        // to forward inbound DATA + PROOFs addressed to us. Without
        // this, switching MichMesh → ChicagoNomadNet leaves the new
        // rnsd with no return path until the throttle window expires
        // (up to 15 min), and any inbound proofs fail to reach us.
        lastAnnounceMs = 0L

        stateMirrorJob = scope.launch {
            transport.state.collect { st -> emitConnection(st, kind) }
        }
        pumpJob = scope.launch {
            transport.incoming.collect { incoming ->
                runCatching { handleIncoming(incoming.packet, incoming.rssi) }
                    .onFailure { _events.tryEmit(EngineEvent.Log("rx error: ${it.message}")) }
            }
        }
        reannounceJob = scope.launch {
            while (true) {
                runCatching { sendAnnounceIfDue() }.onFailure {
                    _events.tryEmit(EngineEvent.Log("announce failed: ${it.message}"))
                }
                delay(announceMinIntervalMs)
            }
        }
    }

    fun detach() {
        pumpJob?.cancel(); pumpJob = null
        stateMirrorJob?.cancel(); stateMirrorJob = null
        reannounceJob?.cancel(); reannounceJob = null
        transport = null
        // v0.1.66: drop reused-link cache. Without the transport,
        // existing LinkSessions can't send / receive — reusing one
        // after re-attach would silently fail since the underlying
        // Reticulum Link state is no longer valid for the new
        // transport. Force fresh handshakes after re-attach.
        scope.launch {
            sessionsLock.withLock {
                nomadLinks.clear()
                activeSessions.clear()
            }
        }
        emitConnection(TransportState.Disconnected, kind = null)
    }

    suspend fun sendAnnounce(asPathResponse: Boolean = false) {
        val id = ensureIdentity()
        // Rotate the ratchet on a slow schedule (default 30 min, per
        // upstream RNS RATCHET_INTERVAL). Two competing requirements:
        //  - rotate often enough that transit nodes that dedupe on
        //    (destHash, ratchet) keep propagating our re-announces
        //    (the v0.1.33 fix that broke pre-fix invisible)
        //  - DON'T rotate so often that peers' in-flight DATA arrives
        //    encrypted to a ratchet pub we've already discarded
        //    (mobile-to-mobile silent failure observed 2026-05-03)
        //
        // The path-request handler in v0.1.35 calls sendAnnounce on
        // every inbound path?, which can fire many times per minute.
        // Time-gating rotation here decouples announce frequency from
        // ratchet rotation cadence — the same ratchet pub may appear
        // on many announces within a 30-min window.
        if (shouldRotateRatchet(nowMs(), lastRatchetRotationMs)) {
            id.rotateRatchet()
            identityRepo.save(StoredIdentity(
                encPrivKey = id.encPrivKey!!,
                sigPrivKey = id.sigPrivKey!!,
                ratchetPrivKey = id.ratchetPrivKey,
            ))
            lastRatchetRotationMs = nowMs()
            _events.tryEmit(EngineEvent.Log("ratchet rotated"))
        }
        val name = displayNameProvider().ifBlank { "Reticulum Mobile" }
        val (destHash, payload, hasRatchet) = io.github.thatsfguy.reticulum.announce.buildAnnounce(
            identity = id,
            crypto = crypto,
            appName = "lxmf.delivery",
            appData = io.github.thatsfguy.reticulum.codec.MessagePack.encode(
                listOf(name.encodeToByteArray(), 0)
            ),
            ratchetPub = id.ratchetPubKey,
            nowSeconds = nowMs() / 1000L,
        )
        // PATH_RESPONSE context (0x0B) when this announce is a reply
        // to a path? request, NONE (0x00) otherwise. Receivers
        // (RNS/Transport.py:1632-1639) bypass ingress rate-limiting
        // for path-response context — without it, our re-announce in
        // response to a flood of path? requests gets rate-limited at
        // transit nodes and the requester never gets the path it
        // needs to advance from path? to actual DATA delivery.
        // See reticulum-specifications/flows/path-discovery.md §6.
        val ctx = if (asPathResponse) {
            io.github.thatsfguy.reticulum.protocol.CTX_PATH_RESPONSE
        } else CTX_NONE
        val packet = buildPacket(
            headerType = HEADER_1,
            contextFlag = if (hasRatchet) 1 else 0,
            transportType = TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_ANNOUNCE,
            destHash = destHash,
            context = ctx,
            payload = payload,
        )
        transport?.send(packet)
        lastAnnounceMs = nowMs()
        val tag = if (asPathResponse) " [path-response]" else ""
        _events.tryEmit(EngineEvent.Log("announce sent (${destHash.toHex()})$tag"))
    }

    /** Throttled wrapper around [sendAnnounce]. Skips the send if our
     *  last announce went out less than [announceMinIntervalMs] ago.
     *  Used by the on-connect path and the periodic re-announce loop;
     *  explicit user actions (Settings → Send announce, identity
     *  reset, display-name change) call [sendAnnounce] directly. */
    suspend fun sendAnnounceIfDue() {
        val now = nowMs()
        val sinceLast = now - lastAnnounceMs
        if (lastAnnounceMs != 0L && sinceLast < announceMinIntervalMs) {
            val ageS = sinceLast / 1000
            val gateS = announceMinIntervalMs / 1000
            _events.tryEmit(EngineEvent.Log("announce throttled (last sent ${ageS}s ago, gate ${gateS}s)"))
            return
        }
        sendAnnounce()
    }

    /** Send an opportunistic LXMF message to a known messagable destination. */
    suspend fun sendMessage(destinationHash: String, content: String, title: String = ""): Long {
        val dest = destinationRepo.get(destinationHash) ?: error("Unknown destination $destinationHash")
        require(dest.publicKey.size == 64) {
            "No public key for $destinationHash yet — wait for an announce or rescan QR"
        }
        require(dest.identityHash.isNotEmpty()) { "No identity hash for $destinationHash" }

        val id = ensureIdentity()
        val ourDest = ourDestHash()

        val plaintext = packMessage(
            sourceIdentity = id,
            destHash = dest.destHash,
            sourceHash = ourDest,
            title = title,
            content = content,
            timestampSeconds = (nowMs() / 1000.0),
            crypto = crypto,
        )
        val recipientEncPub = dest.ratchetPub ?: dest.publicKey.copyOfRange(0, 32)
        val recipientIdHash = dest.identityHash.hexBytesOrThrow("identityHash", expectedLen = 16)
        val keyKind = if (dest.ratchetPub != null) "ratchet" else "long-term"
        val seenAgeMin = (nowMs() - dest.lastSeen) / 60_000
        _events.tryEmit(EngineEvent.Log(
            "→ encrypting to ${destinationHash.take(16)}… via $keyKind key (peer last seen ${seenAgeMin}m ago, ${dest.hopCount} hops)"
        ))
        val token = tokenCrypto.encrypt(plaintext, recipientEncPub, recipientIdHash)
        // §2.3 originator HEADER_1→HEADER_2 conversion. When the path
        // to the destination is via a transit relay (hopCount > 1) and
        // we know that relay's transport_id, we MUST emit HEADER_2 with
        // the relay as transport_id. Upstream RNS/Transport.py:1497
        // forwards inbound DATA only when transport_id != None; HEADER_1
        // DATA addressed to a non-locally-attached destination is added
        // to the dedup hashlist and silently dropped. Verified 2026-05-03
        // by reading RNS 1.2.0 source after offline replay-decrypt
        // confirmed our outbound crypto was correct end-to-end.
        val useHeader2 = dest.hopCount > 1 && dest.nextHop != null
        val packet = buildPacket(
            headerType = if (useHeader2) HEADER_2 else HEADER_1,
            transportType = if (useHeader2) TRANSPORT_TRANSPORT else TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = dest.destHash,
            transportId = if (useHeader2) dest.nextHop else null,
            context = CTX_NONE,
            payload = token,
        )
        if (useHeader2) {
            _events.tryEmit(EngineEvent.Log(
                "  → using HEADER_2 (hops=${dest.hopCount}, transport_id=${dest.nextHop?.toHex()})"
            ))
        }
        // Pre-compute the truncated packet hash so we can match an
        // incoming Reticulum PROOF (whose dest_hash field IS the
        // truncated full hash of the original packet) back to this
        // outgoing message and flip its state to "delivered".
        val outgoingTruncHashHex = runCatching {
            val self = parsePacket(packet) ?: error("self-parse failed")
            io.github.thatsfguy.reticulum.protocol.TruncatedHash
                .of(computePacketFullHash(self, crypto)).hex
        }.getOrNull()

        val msgId = messageRepo.save(StoredMessage(
            contactHash = destinationHash,
            direction = "outgoing",
            content = content,
            title = title,
            timestamp = nowMs(),
            state = "pending",
            attempts = 0,
            lastAttempt = nowMs(),
            rawPacket = packet,
            packetHash = outgoingTruncHashHex,
        ))
        // Sideband-style progressive states so the user can see WHERE a
        // send is in flight. The path-request step matters: without it,
        // opportunistic DATA sent to a destination whose path has aged
        // out on the local rnsd silently fails — the rnsd has nowhere
        // to forward it.
        _events.tryEmit(EngineEvent.Log("msg #$msgId: requesting path to $destinationHash"))
        primePath(
            destHash = dest.destHash,
            requestPath = { hash -> requestPath(hash) },
            delayMs = { ms -> delay(ms) },
            onPathFailure = { _events.tryEmit(EngineEvent.Log("msg #$msgId: path? failed: ${it.message}")) },
        )

        _events.tryEmit(EngineEvent.Log("msg #$msgId: sending (${packet.size}B)"))
        val txAtSend = transport
        if (txAtSend == null) {
            // Silent-no-op bug: transport?.send(packet) drops the
            // packet without any indication if transport went null
            // between save and send (e.g. user just tapped Disconnect,
            // or the supervisor is mid-reconnect). Surface it so the
            // diagnostics log shows the real failure.
            _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ no transport attached — bytes never hit the wire"))
            messageRepo.updateState(msgId, state = "failed", lastAttempt = nowMs(), lastError = "no transport at send time")
            return msgId
        }
        runCatching { txAtSend.send(packet) }.onFailure {
            _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ send threw: ${it::class.simpleName}: ${it.message}"))
            messageRepo.updateState(msgId, state = "failed", lastAttempt = nowMs(), lastError = it.message ?: "send error")
            return msgId
        }
        messageRepo.updateState(msgId, state = "sent", attempts = 1, lastAttempt = nowMs())
        outgoingTruncHashHex?.let { _events.tryEmit(EngineEvent.Log("→ data $it (msg #$msgId)")) }
        _events.tryEmit(EngineEvent.Log("msg #$msgId: awaiting proof (up to ${MSG_MAX_ATTEMPTS} attempts)"))

        scope.launch {
            try {
                for (attempt in 2..MSG_MAX_ATTEMPTS) {
                    delay(MSG_BACKOFF_MS[attempt - 2])
                    val current = messageRepo.getById(msgId) ?: return@launch
                    if (current.state == "delivered") {
                        _events.tryEmit(EngineEvent.Log("msg #$msgId: ✓ delivered"))
                        return@launch
                    }
                    if (current.state == "failed") return@launch
                    _events.tryEmit(EngineEvent.Log("msg #$msgId: retry $attempt/$MSG_MAX_ATTEMPTS (no proof yet)"))
                    val txAtRetry = transport
                    if (txAtRetry == null) {
                        // Same silent-no-op bug shape as the primary path
                        // had pre-v0.1.31. The transport detached between
                        // the initial send and this retry attempt (user
                        // tapped Disconnect, supervisor mid-reconnect, or
                        // a transport switch hasn't completed). Without
                        // this branch the retry would silently no-op via
                        // `?.send()` and the message would stay on "sent"
                        // forever instead of flipping to failed.
                        _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ transport detached at retry $attempt"))
                        messageRepo.updateState(
                            msgId,
                            state = "failed",
                            lastAttempt = nowMs(),
                            lastError = "no transport at retry $attempt",
                        )
                        return@launch
                    }
                    runCatching { txAtRetry.send(packet) }
                        .onSuccess { messageRepo.updateState(msgId, attempts = attempt, lastAttempt = nowMs()) }
                        .onFailure {
                            _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ retry $attempt threw: ${it::class.simpleName}: ${it.message}"))
                            messageRepo.updateState(msgId, lastError = it.message ?: "send error")
                        }
                }
                // Final attempt complete — give the proof one more grace
                // window before declaring failed. If a proof arrives in
                // that window the PROOF handler flips state to delivered;
                // we re-check before marking failed.
                delay(MSG_BACKOFF_MS.last())
                val finalState = messageRepo.getById(msgId)?.state
                if (finalState != "delivered" && finalState != "failed") {
                    messageRepo.updateState(msgId, state = "failed", lastError = "no proof after $MSG_MAX_ATTEMPTS attempts")
                    _events.tryEmit(EngineEvent.Log("msg #$msgId: ✗ failed — no proof after $MSG_MAX_ATTEMPTS attempts"))
                }
            } catch (_: kotlinx.coroutines.CancellationException) {}
        }
        return msgId
    }

    private suspend fun handleIncoming(rawPacket: ByteArray, rssi: Int?) {
        val pkt = parsePacket(rawPacket) ?: run {
            // Symmetric to TcpInterface's tx-log: surface unparseable
            // bytes so we can wire-trace BLE/TCP issues. Truncate to
            // 32B so a single corrupt frame doesn't flood logcat.
            val n = minOf(rawPacket.size, 32)
            val hex = (0 until n).joinToString("") { (rawPacket[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
            _events.tryEmit(EngineEvent.Log("rx ${rawPacket.size}B unparseable: $hex${if (rawPacket.size > n) "..." else ""}"))
            return
        }

        // One-line wire trace of every inbound packet, BEFORE any
        // filtering. Without this, packets dropped by a destHash mismatch
        // or a context-byte we don't handle disappear silently — the
        // BLE inbound-from-Ratdeck investigation got stuck for hours
        // because we couldn't tell whether replies were arriving at the
        // engine or being lost upstream.
        val ptName = when (pkt.packetType) {
            PACKET_DATA     -> "DATA"
            PACKET_ANNOUNCE -> "ANNC"
            PACKET_LINKREQ  -> "LREQ"
            io.github.thatsfguy.reticulum.protocol.PACKET_PROOF -> "PROOF"
            else            -> "PT${pkt.packetType}"
        }
        _events.tryEmit(EngineEvent.Log(
            "rx ${rawPacket.size}B H${pkt.headerType + 1} $ptName dest=${pkt.destHash.toHex()} ctx=0x${pkt.context.toString(16).padStart(2,'0')} hops=${pkt.hops}"
        ))

        // Active link routing: if destHash matches a session's link_id,
        // hand the packet to that session and stop. LRPROOF / RESPONSE
        // packets all flow through this path during a NomadNet fetch.
        val sessionKey = pkt.destHash.toHex()
        val session = sessionsLock.withLock { activeSessions[sessionKey] }
        if (session != null) {
            session.handlePacket(pkt)
            return
        }

        // PROOF packets that don't match an active link session are
        // (almost always) opportunistic-DATA delivery proofs from the
        // recipient of one of our outgoing messages. The dest_hash
        // field of an opportunistic proof IS the truncated full hash
        // of the original DATA packet — so we just look up by it.
        if (pkt.packetType == io.github.thatsfguy.reticulum.protocol.PACKET_PROOF) {
            val msg = messageRepo.getOutgoingByPacketHash(sessionKey)
            if (msg != null && msg.state != "delivered") {
                messageRepo.updateState(msg.id, state = "delivered", lastAttempt = nowMs())
                _events.tryEmit(EngineEvent.Log("✓ delivered msg #${msg.id} (proof for $sessionKey)"))
            } else {
                val activeKeys = sessionsLock.withLock { activeSessions.keys.toList() }
                _events.tryEmit(EngineEvent.Log(
                    "rx PROOF dest=$sessionKey ctx=0x${pkt.context.toString(16).padStart(2,'0')} (no match; active=$activeKeys)"
                ))
            }
            return
        }

        when (pkt.packetType) {
            PACKET_ANNOUNCE -> handleAnnounce(pkt, rssi)
            PACKET_DATA     -> handleData(pkt, rssi)
            PACKET_LINKREQ  -> handleLinkRequest(pkt)
            else            -> _events.tryEmit(EngineEvent.Log("rx pkt type ${pkt.packetType} ctx=${pkt.context}"))
        }
    }

    /**
     * Inbound peer-initiated link. Validates the request, sends an
     * LRPROOF, and registers a [ResponderLinkSession] so subsequent DATA
     * packets to link_id route into our LXMF receiver.
     *
     * Wire layout of the LRPROOF (from upstream Link.prove):
     *   flags=0x0F (HEADER_1 | DEST_LINK | PACKET_PROOF), hops=0,
     *   destHash=link_id, context=0xFF (CTX_LRPROOF),
     *   payload = signature(64) || ourEphemeralX25519Pub(32) || signalling(3)
     */
    private suspend fun handleLinkRequest(pkt: io.github.thatsfguy.reticulum.protocol.Packet) {
        val ourDest = ourDestHash()
        if (!pkt.destHash.contentEquals(ourDest)) {
            // Not for us — relay layer would normally forward; we don't
            // play that role yet.
            return
        }
        val id = ensureIdentity()
        val tx = transport ?: run {
            _events.tryEmit(EngineEvent.Log("LINKREQUEST received but no transport attached"))
            return
        }

        val (link, proofPayload) = runCatching {
            io.github.thatsfguy.reticulum.link.Link.validateRequest(pkt, id, crypto)
        }.onFailure { _events.tryEmit(EngineEvent.Log("LINKREQUEST rejected: ${it.message}")) }
            .getOrNull() ?: return

        val linkIdHex = link.linkId!!.toHex()
        val proofPacket = buildPacket(
            headerType = HEADER_1,
            destType = io.github.thatsfguy.reticulum.protocol.DEST_LINK,
            packetType = io.github.thatsfguy.reticulum.protocol.PACKET_PROOF,
            destHash = link.linkId!!,
            context = io.github.thatsfguy.reticulum.protocol.CTX_LRPROOF,
            payload = proofPayload,
        )
        tx.send(proofPacket)
        _events.tryEmit(EngineEvent.Log("→ LRPROOF for $linkIdHex (responder)"))

        val session = ResponderLinkSession(
            link = link,
            identity = id,
            crypto = crypto,
            sender = { tx.send(it) },
            nowMs = nowMs,
            onLxmfReceived = { plaintext, senderHash, _ ->
                handleLinkLxmf(plaintext, senderHash)
            },
            onClose = { closedHex, reason ->
                sessionsLock.withLock { activeSessions.remove(closedHex) }
                _events.tryEmit(EngineEvent.Log("link $closedHex closed: $reason"))
            },
            logger = { line -> _events.tryEmit(EngineEvent.Log("[$linkIdHex] $line")) },
        )
        sessionsLock.withLock { activeSessions[linkIdHex] = session }
    }

    /**
     * Persist a link-delivered LXMF message. Mirrors [handleData]'s
     * opportunistic path: try to verify against the sender's cached
     * identity, save with state="verified" or "unverified", trigger a
     * path request if the sender is unknown so a future announce can
     * retroactively re-verify.
     */
    private suspend fun handleLinkLxmf(linkPlaintext: ByteArray, senderDestHashHex: String) {
        val msg = io.github.thatsfguy.reticulum.lxmf.unpackLinkMessage(linkPlaintext, crypto)
        val dest = destinationRepo.get(senderDestHashHex)
        val variant = dest?.takeIf { it.publicKey.size == 64 }?.let {
            val senderId = Identity(crypto)
            senderId.loadFromPublicKey(it.publicKey)
            io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature(msg, senderId, crypto)
        }
        val effectiveTimestamp = correctClocklessTimestamp(msg.timestamp, nowMs())
        val isUnverified = variant == null
        val savedId = messageRepo.save(StoredMessage(
            contactHash = senderDestHashHex,
            direction = "incoming",
            content = msg.content,
            title = msg.title,
            timestamp = effectiveTimestamp,
            state = if (!isUnverified) "verified" else "unverified",
            attempts = 0,
            lastAttempt = 0,
            rawPacket = if (isUnverified) linkPlaintext else null,
            rssi = null,
        ))
        _events.tryEmit(EngineEvent.MessageReceived(
            messageId = savedId,
            contactHash = senderDestHashHex,
            content = msg.content,
            verified = !isUnverified,
        ))

        if (isUnverified && pathRequestsSent.add(senderDestHashHex)) {
            runCatching { requestPath(msg.sourceHash) }
                .onSuccess { _events.tryEmit(EngineEvent.Log("path? for unverified link sender $senderDestHashHex")) }
                .onFailure { _events.tryEmit(EngineEvent.Log("path? failed for $senderDestHashHex: ${it.message}")) }
        }
    }

    private suspend fun handleAnnounce(pkt: io.github.thatsfguy.reticulum.protocol.Packet, rssi: Int?) {
        // Drop announces that came back to us — typically our own
        // packet looped via a relay (RNode in repeater mode, or an
        // upstream rnsd retransmitting). Ingesting these would
        // populate the contact list with a row pointing at ourselves.
        val ours = runCatching { ourDestHash() }.getOrNull()
        if (ours != null && pkt.destHash.contentEquals(ours)) {
            _events.tryEmit(EngineEvent.Log("self-announce echo dropped (${pkt.destHash.toHex()})"))
            return
        }
        // Dedup against the in-session set. Each announce has a unique
        // random_hash (10 bytes baked into the payload), so the full
        // packet hash uniquely identifies one emission across the 5-6
        // relay paths an rnsd typically forwards through.
        val truncHashHex = runCatching {
            io.github.thatsfguy.reticulum.protocol.TruncatedHash
                .of(computePacketFullHash(pkt, crypto)).hex
        }.getOrNull()
        if (truncHashHex != null && !rememberAnnounce(truncHashHex)) {
            // Silent — duplicates would otherwise spam the diagnostics log.
            return
        }
        val parsed = parseAnnounce(pkt.payload, pkt.contextFlag, pkt.destHash, crypto) ?: return
        if (!validateAnnounce(parsed, crypto)) {
            _events.tryEmit(EngineEvent.Log("announce sig fail ${pkt.destHash.toHex()}"))
            return
        }
        val nameHashHex = parsed.nameHash.toHex()
        val knownService = KnownDestinations.byNameHashHex(nameHashHex)
        val extractedName = extractDisplayName(parsed.appData)

        // Telemetry parse — only meaningful for non-LXMF announces.
        // parseTelemetryBytes tries both legacy `key=value;` text AND
        // msgpack-encoded `[int_key: value]` (used by `rnstransport.broadcasts`
        // BackboneInterface / RNodeInterface / TCPInterface gossip). v0.1.51.
        val telemetry = if (knownService?.name != "lxmf.delivery") {
            runCatching { parseTelemetryBytes(parsed.appData) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } else null
        val coords = telemetry?.let { extractCoordinates(it) }

        val hashHex = pkt.destHash.toHex()
        val existing = destinationRepo.get(hashHex)
        val merged = StoredDestination(
            hash = hashHex,
            identityHash = parsed.identityHash.toHex(),
            publicKey = parsed.publicKey,
            destHash = pkt.destHash,
            nameHash = parsed.nameHash,
            ratchetPub = parsed.ratchet,
            displayName = io.github.thatsfguy.reticulum.announce.resolveDisplayName(
                extracted = extractedName,
                existing = existing?.displayName,
                knownLabel = knownService?.label,
            ),
            appName = knownService?.name,
            appLabel = knownService?.label,
            telemetry = telemetry ?: existing?.telemetry,
            lat = coords?.first ?: existing?.lat,
            lon = coords?.second ?: existing?.lon,
            appDataHex = parsed.appData.toHex(),
            lastSeen = nowMs(),
            rssi = rssi ?: existing?.rssi,
            favorite = existing?.favorite ?: false,
            source = existing?.source ?: "announce",
            // Match upstream RNS Transport.inbound (RNS/Transport.py:1395)
            // which increments hops on receive before storing in path_table.
            // Without the +1 our local hopCount is one less than upstream's
            // path-table HOPS, and the §2.3 originator conversion threshold
            // (hops > 1) misfires. Concrete failure: a 1-wire-hop announce
            // (received via a transit transport) records hopCount=1 here,
            // we don't convert to HEADER_2, and the transport drops our
            // outbound DATA at RNS/Transport.py:1497.
            hopCount = pkt.hops + 1,
            // Capture the transport_id of the relay that delivered this
            // announce. Persist as nextHop for §2.3 outbound conversion.
            // Falls back to the existing nextHop if this announce arrived
            // direct (HEADER_1, no transport_id) — a leaf-attached peer
            // that re-announces directly to us shouldn't lose its previously
            // known relay path. transportId is null for HEADER_1.
            nextHop = pkt.transportId ?: existing?.nextHop,
        )
        destinationRepo.upsertFromAnnounce(merged)

        if (knownService?.name == "lxmf.delivery") {
            _events.tryEmit(EngineEvent.MessagableSeen(hashHex, merged.displayName, rssi, knownService.name))
            // Retroactive re-verify: if this announce gives us the identity
            // of a sender whose previous messages we couldn't verify, walk
            // those rows and try again with the now-known sig pub. Each
            // row that succeeds flips "unverified" → "verified" and the
            // stored LXMF plaintext is no longer needed.
            scope.launch { reverifyMessagesFrom(hashHex, parsed.publicKey) }
        } else {
            _events.tryEmit(EngineEvent.NodeSeen(hashHex, merged.displayName, rssi, knownService?.name))
        }
    }

    private suspend fun reverifyMessagesFrom(senderHashHex: String, publicKey: ByteArray) {
        val candidates = messageRepo.getForContact(senderHashHex)
            .filter { it.state == "unverified" && it.rawPacket != null }
        if (candidates.isEmpty()) return

        val senderId = Identity(crypto)
        senderId.loadFromPublicKey(publicKey)
        val ourDest = ourDestHash()
        var verifiedCount = 0
        for (row in candidates) {
            val plaintext = row.rawPacket ?: continue
            val msg = runCatching { unpackMessage(plaintext, ourDest, crypto) }.getOrNull() ?: continue
            val variant = verifyMessageSignature(msg, senderId, crypto) ?: continue
            messageRepo.updateState(row.id, state = "verified")
            verifiedCount++
        }
        if (verifiedCount > 0) {
            _events.tryEmit(EngineEvent.Log(
                "re-verified $verifiedCount prior message${if (verifiedCount == 1) "" else "s"} from $senderHashHex"
            ))
        }
    }

    private suspend fun handleData(pkt: io.github.thatsfguy.reticulum.protocol.Packet, rssi: Int?) {
        val ourDest = ourDestHash()

        // Path-request handling. Peers send a path? before sending us
        // LXMF DATA — without us responding (by re-announcing) the
        // sender's path? times out and the message never goes out.
        // Symptom that surfaced this: 13 inbound 51B DATA packets to
        // 6b9f66... (the rnstransport.path.request destination) on
        // BLE, all silently dropped by the destHash mismatch below.
        val pathReqDest = computeDestinationHash(crypto, "rnstransport.path.request", ByteArray(0))
        if (pkt.destHash.contentEquals(pathReqDest)) {
            val target = parsePathRequestTarget(pkt.payload)
            if (target == null) {
                _events.tryEmit(EngineEvent.Log("path? rx malformed (${pkt.payload.size}B)"))
                return
            }
            if (target.contentEquals(ourDest)) {
                _events.tryEmit(EngineEvent.Log("path? rx for us — re-announcing as PATH_RESPONSE"))
                runCatching { sendAnnounce(asPathResponse = true) }.onFailure {
                    _events.tryEmit(EngineEvent.Log("re-announce on path? failed: ${it.message}"))
                }
            } else {
                // We're not a transport node, so we can't fulfill
                // path? for other destinations. Just trace it.
                _events.tryEmit(EngineEvent.Log("path? rx for ${target.toHex()} (not us; not transport)"))
            }
            return
        }

        if (!pkt.destHash.contentEquals(ourDest)) return

        val id = ensureIdentity()
        // Candidate decrypt keys, tried in order of likelihood:
        //   1. current ratchet  — most recent peer announces will use this
        //   2. previous ratchet — for in-flight messages encrypted just before
        //                         our last rotation (peers may not have seen
        //                         the new announce yet); see Identity.rotateRatchet
        //   3. long-term enc    — fallback for peers that don't track our
        //                         ratchet at all (Sideband sometimes does this)
        val candidates = listOfNotNull(
            id.ratchetPrivKey,
            id.previousRatchetPrivKey,
            id.encPrivKey,
        )
        val plaintext = runCatching { tokenCrypto.decrypt(pkt.payload, candidates, id.hash!!) }
            .onFailure { _events.tryEmit(EngineEvent.Log("decrypt fail: ${it.message}")) }
            .getOrNull() ?: return

        // Send a delivery proof on every successful decrypt — Sideband and
        // MeshChatX keep retransmitting opportunistic LXMF until they get
        // back a proof, even if we're already storing the message. We
        // always re-emit the proof on duplicates so a re-tx that crossed
        // our first proof in flight still gets acked.
        val fullHash = runCatching { computePacketFullHash(pkt, crypto) }.getOrNull()
        if (fullHash != null) {
            runCatching { sendDeliveryProof(fullHash) }
                .onFailure { _events.tryEmit(EngineEvent.Log("proof send failed: ${it.message}")) }
        }

        // Dedup against the in-session set. Duplicates get re-acked above
        // but skip storage so the inbox doesn't fill with copies.
        val truncHashHex = fullHash?.let { io.github.thatsfguy.reticulum.protocol.TruncatedHash.of(it).hex }
        if (truncHashHex != null && !rememberIncomingData(truncHashHex)) {
            _events.tryEmit(EngineEvent.Log("dup data $truncHashHex (re-acked)"))
            return
        }

        val msg = unpackMessage(plaintext, ourDest, crypto)
        val sourceHashHex = msg.sourceHash.toHex()
        val dest = destinationRepo.get(sourceHashHex)

        val variant = dest?.takeIf { it.publicKey.size == 64 }?.let {
            val senderId = Identity(crypto)
            senderId.loadFromPublicKey(it.publicKey)
            verifyMessageSignature(msg, senderId, crypto)
        }

        val effectiveTimestamp = correctClocklessTimestamp(msg.timestamp, nowMs())
        val isUnverified = variant == null
        val savedId = messageRepo.save(StoredMessage(
            contactHash = sourceHashHex,
            direction = "incoming",
            content = msg.content,
            title = msg.title,
            timestamp = effectiveTimestamp,
            state = if (!isUnverified) "verified" else "unverified",
            attempts = 0,
            lastAttempt = 0,
            // Stash the LXMF plaintext on unverified rows so we can
            // re-run verifyMessageSignature once the sender's announce
            // (and identity) shows up, and flip "unverified" → "verified"
            // retroactively. The plaintext is already decrypted local
            // bytes — no extra secret storage.
            rawPacket = if (isUnverified) plaintext else null,
            rssi = rssi,
        ))
        _events.tryEmit(EngineEvent.MessageReceived(
            messageId = savedId,
            contactHash = sourceHashHex,
            content = msg.content,
            verified = !isUnverified,
        ))

        // Active credential fetch: ask the network for this sender's
        // identity so the next message verifies, and so the announce
        // also drives the retroactive re-verify path on this message.
        // Dedup per session — one path request per unknown sender.
        if (isUnverified && pathRequestsSent.add(sourceHashHex)) {
            runCatching { requestPath(msg.sourceHash) }
                .onSuccess { _events.tryEmit(EngineEvent.Log("path? for unverified sender $sourceHashHex")) }
                .onFailure { _events.tryEmit(EngineEvent.Log("path? failed for $sourceHashHex: ${it.message}")) }
        }
    }

    /**
     * Emit a Reticulum delivery proof for an opportunistic DATA packet
     * we just decrypted. Wire format (implicit-proof default):
     *
     *   byte 0 : flags = 0x03 (HEADER_1 | DEST_SINGLE | PACKET_PROOF)
     *   byte 1 : hops = 0
     *   2..17  : truncHash[:16]   — the original DATA packet's full hash
     *   byte 18: context = 0x00 (NONE)
     *   19..82 : Ed25519 signature over the FULL 32-byte packet hash
     *
     * The dest_hash field is the original packet's truncated hash (NOT a
     * destination hash), which is how relays match the proof back to the
     * sender via Transport.reverse_table[truncHash]. Total: 83 bytes.
     */
    private suspend fun sendDeliveryProof(fullPacketHash: ByteArray) {
        val tx = transport ?: return
        val id = ensureIdentity()
        val truncHash = fullPacketHash.copyOfRange(0, 16)
        val signature = id.sign(fullPacketHash)
        val proofPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_PROOF,
            destHash = truncHash,
            context = CTX_NONE,
            payload = signature,
        )
        tx.send(proofPacket)
        _events.tryEmit(EngineEvent.Log("→ proof for ${truncHash.toHex()}"))
    }

    /** Returns true if [hashHex] was newly added (caller should treat
     *  the packet as fresh), false if we'd already seen it. Bounded
     *  LRU. */
    private fun rememberIncomingData(hashHex: String): Boolean {
        if (hashHex in seenIncomingDataHashes) return false
        seenIncomingDataHashes.add(hashHex)
        if (seenIncomingDataHashes.size > seenIncomingDataCap) {
            val it = seenIncomingDataHashes.iterator()
            it.next(); it.remove()
        }
        return true
    }

    /** Same shape as [rememberIncomingData] but for announce packets. */
    private fun rememberAnnounce(hashHex: String): Boolean {
        if (hashHex in seenAnnounceHashes) return false
        seenAnnounceHashes.add(hashHex)
        if (seenAnnounceHashes.size > seenAnnounceCap) {
            val it = seenAnnounceHashes.iterator()
            it.next(); it.remove()
        }
        return true
    }

    sealed class EngineEvent {
        data class Log(val line: String) : EngineEvent()
        data class MessagableSeen(val hash: String, val displayName: String, val rssi: Int?, val appName: String?) : EngineEvent()
        data class NodeSeen(val hash: String, val displayName: String, val rssi: Int?, val appName: String?) : EngineEvent()
        data class MessageReceived(
            val messageId: Long,
            val contactHash: String,
            val content: String,
            val verified: Boolean,
        ) : EngineEvent()
    }

    enum class TransportKind { Ble, Tcp, Usb }

    /** Connection state plus the wall-clock millis when [transport]
     *  last changed. UI uses changedAtMs to show "Connecting (12s)…"
     *  so the user can tell a slow-but-working connection apart from
     *  a wedged one. */
    data class ConnectionState(
        val transport: TransportState,
        val kind: TransportKind?,
        val changedAtMs: Long = 0L,
    )
}

/** Clockless RNode timestamps (small seconds-since-boot) → local receive time. CLAUDE.md gotcha #4. */
internal fun correctClocklessTimestamp(senderSeconds: Double, nowMs: Long): Long {
    val senderMs = (senderSeconds * 1000.0).toLong()
    return if (senderMs < 1_577_836_800_000L) nowMs else senderMs
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.hexBytesOrThrow(label: String, expectedLen: Int): ByteArray {
    require(length == expectedLen * 2) { "$label must be $expectedLen bytes (${expectedLen * 2} hex chars), got $length" }
    val s = lowercase()
    return ByteArray(expectedLen) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
