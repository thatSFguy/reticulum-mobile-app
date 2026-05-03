package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.announce.KnownDestinations
import io.github.thatsfguy.reticulum.announce.extractCoordinates
import io.github.thatsfguy.reticulum.announce.extractDisplayName
import io.github.thatsfguy.reticulum.announce.parseAnnounce
import io.github.thatsfguy.reticulum.announce.parseTelemetry
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
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_BROADCAST
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
        path: String = ":/page/index.mu",
        proofTimeoutMs: Long = 45_000L,
        responseTimeoutMs: Long = 45_000L,
    ): Result<String> = runCatching {
        val dest = destinationRepo.get(destinationHash) ?: error("Unknown destination $destinationHash")
        require(dest.publicKey.size == 64) {
            "No public key for $destinationHash yet — wait for an announce"
        }
        val tx = transport ?: error("No transport attached — connect on the Settings tab first")

        val targetSigPub = dest.publicKey.copyOfRange(32, 64)
        val (link, requestData) = Link.createInitiator(
            peerLongTermSigPub = targetSigPub,
            peerDestHash = dest.destHash,
            crypto = crypto,
            nowMs = nowMs(),
        )
        val linkReqPacket = buildPacket(
            packetType = PACKET_LINKREQ,
            destHash = dest.destHash,
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

            when (val proof = session.awaitProof(proofTimeoutMs)) {
                is LinkSession.ProofResult.Validated -> {
                    _events.tryEmit(EngineEvent.Log("link active, requesting $path"))
                }
                is LinkSession.ProofResult.Invalid -> {
                    error("LRPROOF rejected: ${proof.reason}")
                }
                LinkSession.ProofResult.Timeout -> {
                    error("no LRPROOF received within ${proofTimeoutMs / 1000}s — node may be unreachable, slow, or refusing initiator-side links")
                }
            }

            val pathHash = crypto.sha256(path.encodeToByteArray())
            val responseBytes = session.request(pathHash, ByteArray(0), responseTimeoutMs)
                ?: error("no RESPONSE within ${responseTimeoutMs / 1000}s — node accepted the link but didn't reply (page might be larger than one MTU, or the request frame format isn't what this node expects)")

            _events.tryEmit(EngineEvent.Log("page received: ${responseBytes.size} bytes"))
            return@runCatching responseBytes.decodeToString()
        } finally {
            sessionsLock.withLock { activeSessions.remove(linkIdHex) }
        }
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
        val linkReqPacket = buildPacket(
            packetType = PACKET_LINKREQ,
            destHash = dest.destHash,
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
        emitConnection(TransportState.Disconnected, kind = null)
    }

    suspend fun sendAnnounce() {
        val id = ensureIdentity()
        val name = displayNameProvider().ifBlank { "Reticulum Mobile" }
        val (destHash, payload, hasRatchet) = io.github.thatsfguy.reticulum.announce.buildAnnounce(
            identity = id,
            crypto = crypto,
            appName = "lxmf.delivery",
            appData = io.github.thatsfguy.reticulum.codec.MessagePack.encode(
                listOf(name.encodeToByteArray(), 0)
            ),
            ratchetPub = id.ratchetPubKey,
        )
        val packet = buildPacket(
            headerType = HEADER_1,
            contextFlag = if (hasRatchet) 1 else 0,
            transportType = TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_ANNOUNCE,
            destHash = destHash,
            payload = payload,
        )
        transport?.send(packet)
        lastAnnounceMs = nowMs()
        _events.tryEmit(EngineEvent.Log("announce sent (${destHash.toHex()})"))
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
        val packet = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = dest.destHash,
            context = CTX_NONE,
            payload = token,
        )
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
        val pkt = parsePacket(rawPacket) ?: return

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
        val displayName = extractDisplayName(parsed.appData) ?: knownService?.label ?: ""

        // Telemetry parse — only meaningful for non-LXMF announces.
        val telemetry = if (knownService?.name != "lxmf.delivery") {
            runCatching { parsed.appData.decodeToString() }
                .map { parseTelemetry(it) }
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
            displayName = displayName.ifBlank { existing?.displayName ?: "" },
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
            hopCount = pkt.hops,
        )
        destinationRepo.upsertFromAnnounce(merged)

        if (knownService?.name == "lxmf.delivery") {
            _events.tryEmit(EngineEvent.MessagableSeen(hashHex, displayName, rssi, knownService.name))
            // Retroactive re-verify: if this announce gives us the identity
            // of a sender whose previous messages we couldn't verify, walk
            // those rows and try again with the now-known sig pub. Each
            // row that succeeds flips "unverified" → "verified" and the
            // stored LXMF plaintext is no longer needed.
            scope.launch { reverifyMessagesFrom(hashHex, parsed.publicKey) }
        } else {
            _events.tryEmit(EngineEvent.NodeSeen(hashHex, displayName, rssi, knownService?.name))
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
        if (!pkt.destHash.contentEquals(ourDest)) return

        val id = ensureIdentity()
        val candidates = listOfNotNull(id.ratchetPrivKey, id.encPrivKey)
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
