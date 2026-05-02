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

    private val _connection = MutableStateFlow(ConnectionState(TransportState.Disconnected, kind = null))
    val connection: StateFlow<ConnectionState> = _connection

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
        _connection.value = ConnectionState(transport.state.value, kind)

        stateMirrorJob = scope.launch {
            transport.state.collect { st -> _connection.value = ConnectionState(st, kind) }
        }
        pumpJob = scope.launch {
            transport.incoming.collect { incoming ->
                runCatching { handleIncoming(incoming.packet, incoming.rssi) }
                    .onFailure { _events.tryEmit(EngineEvent.Log("rx error: ${it.message}")) }
            }
        }
        reannounceJob = scope.launch {
            while (true) {
                runCatching { sendAnnounce() }.onFailure {
                    _events.tryEmit(EngineEvent.Log("announce failed: ${it.message}"))
                }
                delay(5 * 60_000L)
            }
        }
    }

    fun detach() {
        pumpJob?.cancel(); pumpJob = null
        stateMirrorJob?.cancel(); stateMirrorJob = null
        reannounceJob?.cancel(); reannounceJob = null
        transport = null
        _connection.value = ConnectionState(TransportState.Disconnected, kind = null)
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
        _events.tryEmit(EngineEvent.Log("announce sent (${destHash.toHex()})"))
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
            _events.tryEmit(EngineEvent.MessagableSeen(hashHex, displayName, rssi))
        } else {
            _events.tryEmit(EngineEvent.NodeSeen(hashHex, displayName, rssi))
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
        val savedId = messageRepo.save(StoredMessage(
            contactHash = sourceHashHex,
            direction = "incoming",
            content = msg.content,
            title = msg.title,
            timestamp = effectiveTimestamp,
            state = if (variant != null) "verified" else "unverified",
            attempts = 0,
            lastAttempt = 0,
            rssi = rssi,
        ))
        _events.tryEmit(EngineEvent.MessageReceived(
            messageId = savedId,
            contactHash = sourceHashHex,
            content = msg.content,
            verified = variant != null,
        ))
    }

    sealed class EngineEvent {
        data class Log(val line: String) : EngineEvent()
        data class MessagableSeen(val hash: String, val displayName: String, val rssi: Int?) : EngineEvent()
        data class NodeSeen(val hash: String, val displayName: String, val rssi: Int?) : EngineEvent()
        data class MessageReceived(
            val messageId: Long,
            val contactHash: String,
            val content: String,
            val verified: Boolean,
        ) : EngineEvent()
    }

    enum class TransportKind { Ble, Tcp, Usb }

    data class ConnectionState(val transport: TransportState, val kind: TransportKind?)
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
