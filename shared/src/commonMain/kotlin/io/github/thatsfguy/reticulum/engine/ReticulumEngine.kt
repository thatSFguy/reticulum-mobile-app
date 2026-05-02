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
    private val activeSessions: MutableMap<String, LinkSession> = mutableMapOf()
    private val sessionsLock = kotlinx.coroutines.sync.Mutex()

    /** Hashes we've already issued a path request for in this session,
     *  to keep the "unverified message → request identity" loop from
     *  spamming the mesh if the same unknown sender keeps writing. */
    private val pathRequestsSent = mutableSetOf<String>()

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
            runCatching { requestPath(dest.destHash) }.onFailure {
                _events.tryEmit(EngineEvent.Log("path? failed: ${it.message}"))
            }
            delay(1_500L)

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
        val token = tokenCrypto.encrypt(plaintext, recipientEncPub, recipientIdHash)
        val packet = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = dest.destHash,
            context = CTX_NONE,
            payload = token,
        )
        val msgId = messageRepo.save(StoredMessage(
            contactHash = destinationHash,
            direction = "outgoing",
            content = content,
            title = title,
            timestamp = nowMs(),
            state = "sending",
            attempts = 1,
            lastAttempt = nowMs(),
            rawPacket = packet,
        ))
        transport?.send(packet)
        messageRepo.updateState(msgId, state = "sent")
        scope.launch {
            try {
                for (attempt in 2..MSG_MAX_ATTEMPTS) {
                    delay(MSG_BACKOFF_MS[attempt - 2])
                    val current = messageRepo.getById(msgId) ?: return@launch
                    if (current.state == "delivered" || current.state == "failed") return@launch
                    runCatching { transport?.send(packet) }
                        .onSuccess { messageRepo.updateState(msgId, attempts = attempt, lastAttempt = nowMs()) }
                        .onFailure { messageRepo.updateState(msgId, lastError = it.message ?: "send error") }
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

        // Diagnostic: PROOF packets are rare and high-signal during link
        // debugging. Log every one so we can see whether any reach our
        // pump (vs. being dropped by the transport node).
        if (pkt.packetType == io.github.thatsfguy.reticulum.protocol.PACKET_PROOF) {
            val activeKeys = sessionsLock.withLock { activeSessions.keys.toList() }
            _events.tryEmit(EngineEvent.Log(
                "rx PROOF dest=$sessionKey ctx=0x${pkt.context.toString(16).padStart(2,'0')} (no session match; active=$activeKeys)"
            ))
            return
        }

        when (pkt.packetType) {
            PACKET_ANNOUNCE -> handleAnnounce(pkt, rssi)
            PACKET_DATA     -> handleData(pkt, rssi)
            else            -> _events.tryEmit(EngineEvent.Log("rx pkt type ${pkt.packetType} ctx=${pkt.context}"))
        }
    }

    private suspend fun handleAnnounce(pkt: io.github.thatsfguy.reticulum.protocol.Packet, rssi: Int?) {
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
