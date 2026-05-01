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
import io.github.thatsfguy.reticulum.lxmf.LxmfMessage
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
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_BROADCAST
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.store.ContactRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.NodeRepository
import io.github.thatsfguy.reticulum.store.StoredContact
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.store.StoredNode
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

/**
 * Glue between the protocol stack, the active [Transport], and the
 * persistent repositories. One instance per app lifetime; held by the
 * Android foreground service.
 *
 * The Engine does not OWN a transport — callers attach one via [attach]
 * and detach with [detach]. This lets the Activity / Service swap between
 * BLE and TCP transports without rebuilding all of the protocol state.
 */
class ReticulumEngine(
    private val crypto: CryptoProvider,
    private val identityRepo: IdentityRepository,
    private val contactRepo: ContactRepository,
    private val messageRepo: MessageRepository,
    private val nodeRepo: NodeRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { 0L },
    private val displayNameProvider: () -> String = { "Reticulum Mobile" },
) {
    private val tokenCrypto = TokenCrypto(crypto)

    // Loaded identity. Null until [ensureIdentity] runs.
    private var identity: Identity? = null

    private val _connection = MutableStateFlow(ConnectionState(TransportState.Disconnected, kind = null))
    val connection: StateFlow<ConnectionState> = _connection

    private val _events = MutableSharedFlow<EngineEvent>(replay = 0, extraBufferCapacity = 64)
    val events: Flow<EngineEvent> = _events.asSharedFlow()

    private var transport: Transport? = null
    private var pumpJob: Job? = null
    private var stateMirrorJob: Job? = null
    private var reannounceJob: Job? = null

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

    /**
     * Discard the current identity (in memory and on disk), generate a
     * fresh keypair, and immediately re-announce on the active transport
     * if one is attached. Contacts and messages stay — they belong to
     * other people's identities and aren't tied to ours.
     */
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

    /** Attach a [Transport] and start processing. The transport must already be connected. */
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
            // CLAUDE.md gotcha #7: relays drop our identity cache after ~5 minutes.
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

    /** Send an opportunistic LXMF message to a known contact. */
    suspend fun sendMessage(contactHash: String, content: String, title: String = ""): Long {
        val contact = contactRepo.get(contactHash) ?: error("Unknown contact $contactHash")
        val id = ensureIdentity()
        val ourDest = ourDestHash()

        val plaintext = packMessage(
            sourceIdentity = id,
            destHash = contact.destHash,
            sourceHash = ourDest,
            title = title,
            content = content,
            timestampSeconds = (nowMs() / 1000.0),
            crypto = crypto,
        )
        // Encrypt to recipient's ratchet pub if known, falling back to identity enc pub
        val recipientEncPub = contact.ratchetPub ?: contact.publicKey.copyOfRange(0, 32)
        val recipientIdHash = contact.identityHash.hexToBytesOrNull()
            ?: error("contact ${contact.hash} has no parseable identity hash")
        val token = tokenCrypto.encrypt(plaintext, recipientEncPub, recipientIdHash)
        val packet = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = contact.destHash,
            context = CTX_NONE,
            payload = token,
        )
        val msgId = messageRepo.save(StoredMessage(
            contactHash = contactHash,
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
        // Opportunistic LXMF has no application-layer receipt, so retries are
        // best-effort redundant transmissions: send again at MSG_BACKOFF_MS
        // intervals up to MSG_MAX_ATTEMPTS, in case the first transmission was
        // lost. CLAUDE.md MSG_BACKOFF_MS = [5000, 15000, 60000].
        scope.launch {
            try {
                for (attempt in 2..MSG_MAX_ATTEMPTS) {
                    delay(MSG_BACKOFF_MS[attempt - 2])
                    val current = messageRepo.getById(msgId) ?: return@launch
                    if (current.state == "delivered" || current.state == "failed") return@launch
                    runCatching { transport?.send(packet) }
                        .onSuccess {
                            messageRepo.updateState(msgId, attempts = attempt, lastAttempt = nowMs())
                        }
                        .onFailure {
                            messageRepo.updateState(msgId, lastError = it.message ?: "send error")
                        }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {}
        }
        return msgId
    }

    private suspend fun handleIncoming(rawPacket: ByteArray, rssi: Int?) {
        val pkt = parsePacket(rawPacket) ?: return
        when (pkt.packetType) {
            PACKET_ANNOUNCE -> handleAnnounce(pkt, rssi)
            PACKET_DATA     -> handleData(pkt, rssi)
            // PACKET_LINKREQ + PACKET_PROOF are Phase F (link-delivered messages,
            // proof receipts). We log them for visibility but don't act yet.
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

        if (knownService?.name == "lxmf.delivery") {
            // Treat as a contact
            contactRepo.save(StoredContact(
                hash = pkt.destHash.toHex(),
                identityHash = parsed.identityHash.toHex(),
                publicKey = parsed.publicKey,
                destHash = pkt.destHash,
                nameHash = parsed.nameHash,
                ratchetPub = parsed.ratchet,
                displayName = displayName,
                lastSeen = nowMs(),
                rssi = rssi,
            ))
            _events.tryEmit(EngineEvent.ContactSeen(pkt.destHash.toHex(), displayName, rssi))
        } else {
            // Treat as a node. Try plain UTF-8 telemetry parse on the app_data;
            // if it produces any key=value pairs, store them and pull lat/lon.
            val telemetry = runCatching { parsed.appData.decodeToString() }
                .map { parseTelemetry(it) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            val coords = telemetry?.let { extractCoordinates(it) }
            nodeRepo.save(StoredNode(
                hash = pkt.destHash.toHex(),
                identityHash = parsed.identityHash.toHex(),
                nameHash = parsed.nameHash,
                appName = knownService?.name,
                appLabel = knownService?.label,
                displayName = displayName,
                telemetry = telemetry,
                lat = coords?.first,
                lon = coords?.second,
                appDataHex = parsed.appData.toHex(),
                lastSeen = nowMs(),
                rssi = rssi,
            ))
            _events.tryEmit(EngineEvent.NodeSeen(pkt.destHash.toHex(), displayName, rssi))
        }
    }

    private suspend fun handleData(pkt: io.github.thatsfguy.reticulum.protocol.Packet, rssi: Int?) {
        val ourDest = ourDestHash()
        if (!pkt.destHash.contentEquals(ourDest)) return  // not for us

        val id = ensureIdentity()
        val candidates = listOfNotNull(id.ratchetPrivKey, id.encPrivKey)
        val plaintext = runCatching { tokenCrypto.decrypt(pkt.payload, candidates, id.hash!!) }
            .onFailure { _events.tryEmit(EngineEvent.Log("decrypt fail: ${it.message}")) }
            .getOrNull() ?: return

        val msg = unpackMessage(plaintext, ourDest, crypto)
        val sourceHashHex = msg.sourceHash.toHex()
        val contact = contactRepo.get(sourceHashHex)

        // Verify signature if we know the sender
        val variant = contact?.let {
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
        data class ContactSeen(val hash: String, val displayName: String, val rssi: Int?) : EngineEvent()
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

/**
 * Convert a clockless RNode timestamp (small seconds-since-boot value) into
 * the local receive time. CLAUDE.md gotcha #4.
 */
internal fun correctClocklessTimestamp(senderSeconds: Double, nowMs: Long): Long {
    // Anything before 2020-01-01 is treated as "no clock". 1577836800 = 2020-01-01 UTC.
    val senderMs = (senderSeconds * 1000.0).toLong()
    return if (senderMs < 1_577_836_800_000L) nowMs else senderMs
}

private fun String.hexToBytesOrNull(): ByteArray? = runCatching {
    val s = lowercase()
    if (s.length % 2 != 0) return null
    ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}.getOrNull()
