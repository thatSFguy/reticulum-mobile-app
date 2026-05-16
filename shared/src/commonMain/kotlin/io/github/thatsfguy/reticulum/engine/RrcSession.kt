package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.rrc.RrcInbound
import io.github.thatsfguy.reticulum.rrc.RrcLimits
import io.github.thatsfguy.reticulum.rrc.RrcMessages

/**
 * Driver for one Reticulum Relay Chat session — the protocol state
 * machine that sits on an established, identified RNS Link to an RRC
 * hub. Mirrors the client side of `rrcd/router.py`.
 *
 * This class is deliberately transport-agnostic: it speaks to the link
 * through [RrcLink] (one method, [RrcLink.send], carrying an encoded
 * CBOR envelope) and receives inbound frames through [onInbound]. The
 * engine wires those to a [LinkSession]'s `sendData` / `onLinkData`.
 * Keeping the engine out of this class makes the whole state machine
 * unit-testable with a fake [RrcLink].
 *
 * Lifecycle: the caller establishes + identifies the link, constructs
 * this session, then calls [start] to send HELLO. The hub replies
 * WELCOME, after which [join] / [sendMessage] / [part] are usable.
 */
class RrcSession(
    /** Our RNS identity hash (16 bytes) — the envelope K_SRC value. */
    private val ourIdentityHash: ByteArray,
    private val link: RrcLink,
    private val nowMs: () -> Long,
    private val nick: String? = null,
    /** Sink for everything the UI / storage layer needs to react to. */
    private val onEvent: (RrcEvent) -> Unit = {},
    private val logger: (String) -> Unit = {},
) {
    var state: RrcState = RrcState.CONNECTING
        private set

    /** Hub-advertised limits — defaults until WELCOME arrives. */
    var limits: RrcLimits = RrcLimits()
        private set

    /** Hub display name from WELCOME, null until then. */
    var hubName: String? = null
        private set

    private val joinedRooms = LinkedHashSet<String>()
    private val pendingJoins = LinkedHashSet<String>()

    /** Rooms we are currently a confirmed member of. */
    val rooms: Set<String> get() = joinedRooms.toSet()

    // ---- outbound -----------------------------------------------------

    /** Send the opening HELLO. Call once the link is ACTIVE + identified. */
    suspend fun start() {
        check(state == RrcState.CONNECTING) { "RRC session already started (state=$state)" }
        link.send(
            RrcMessages.hello(
                src = ourIdentityHash,
                timestampMs = nowMs(),
                nick = nick,
                clientName = CLIENT_NAME,
                resourceCapable = true,
            ).encode(),
        )
        logger("→ HELLO")
    }

    /** Request to JOIN [room]. [key] is supplied only for keyed (+k) rooms. */
    suspend fun join(room: String, key: String? = null) {
        requireWelcomed()
        pendingJoins.add(room)
        link.send(RrcMessages.join(ourIdentityHash, nowMs(), room, key, nick).encode())
        logger("→ JOIN $room")
    }

    /** Leave [room]. Membership is dropped optimistically. */
    suspend fun part(room: String) {
        requireWelcomed()
        joinedRooms.remove(room)
        pendingJoins.remove(room)
        link.send(RrcMessages.part(ourIdentityHash, nowMs(), room, nick).encode())
        logger("→ PART $room")
    }

    /**
     * Send [text] to [room]. Enforces the hub's advertised
     * max-message-body limit client-side so the user gets immediate
     * feedback instead of a round-trip ERROR.
     *
     * Returns the envelope `K_ID` (8 bytes) of the sent message. The
     * caller persists the outgoing row keyed on it: the hub echoes the
     * message back to every room member — us included — with the same
     * id, so storing it lets the persistence layer dedup that echo.
     */
    suspend fun sendMessage(room: String, text: String): ByteArray {
        requireWelcomed()
        val bytes = text.encodeToByteArray()
        require(bytes.size <= limits.maxMsgBodyBytes) {
            "message is ${bytes.size} bytes, hub limit is ${limits.maxMsgBodyBytes}"
        }
        val envelope = RrcMessages.message(ourIdentityHash, nowMs(), room, text, nick)
        link.send(envelope.encode())
        return envelope.msgId
    }

    /** Tear the session down. Idempotent. */
    fun close() {
        if (state == RrcState.CLOSED) return
        setState(RrcState.CLOSED)
        link.close()
    }

    // ---- inbound ------------------------------------------------------

    /**
     * Feed one decrypted inbound link-DATA frame (a CBOR envelope).
     * Parse failures and type/body mismatches are logged and dropped —
     * a misbehaving hub must never crash the client.
     */
    suspend fun onInbound(frame: ByteArray) {
        val msg = runCatching { RrcMessages.parse(frame) }
            .onFailure { logger("inbound RRC parse failed: ${it.message}") }
            .getOrNull() ?: return

        when (msg) {
            is RrcInbound.Welcome -> {
                hubName = msg.hubName
                limits = msg.limits
                setState(RrcState.WELCOMED)
                logger("← WELCOME from ${msg.hubName} (v${msg.hubVersion})")
                onEvent(RrcEvent.Welcomed(msg.hubName, msg.limits))
            }
            is RrcInbound.Ping -> {
                // Hub keepalive — echo the payload straight back.
                link.send(
                    RrcMessages.pong(
                        ourIdentityHash, nowMs(),
                        payload = msg.envelope.body as? ByteArray,
                    ).encode(),
                )
            }
            is RrcInbound.Message ->
                onEvent(
                    RrcEvent.RoomMessage(
                        room = msg.room,
                        senderIdHash = msg.src,
                        nick = msg.nick,
                        text = msg.text,
                        timestampMs = msg.envelope.timestampMs,
                        msgId = msg.envelope.msgId,
                    ),
                )
            is RrcInbound.Notice -> onEvent(RrcEvent.Notice(msg.room, msg.text))
            is RrcInbound.Error -> {
                logger("← ERROR ${msg.room ?: ""}: ${msg.text}")
                onEvent(RrcEvent.HubError(msg.room, msg.text))
            }
            is RrcInbound.Joined -> {
                // A JOINED for a room we asked to join is our own
                // confirmation; otherwise it announces another member.
                if (pendingJoins.remove(msg.room)) joinedRooms.add(msg.room)
                onEvent(RrcEvent.Joined(msg.room, msg.members))
            }
            is RrcInbound.Parted -> onEvent(RrcEvent.Parted(msg.room, msg.members))
            is RrcInbound.Pong -> logger("← PONG")
            is RrcInbound.ResourceEnvelope ->
                // Inbound Resource payload reassembly is Phase 3.
                logger("← RESOURCE_ENVELOPE kind=${msg.resource.kind} size=${msg.resource.size} (not yet handled)")
            is RrcInbound.Unknown ->
                logger("← unknown RRC message type ${msg.envelope.type}")
        }
    }

    private fun requireWelcomed() =
        check(state == RrcState.WELCOMED) { "RRC session not ready (state=$state)" }

    private fun setState(s: RrcState) {
        if (state == s) return
        state = s
        onEvent(RrcEvent.StateChanged(s))
    }

    private companion object {
        const val CLIENT_NAME = "reticulum-mobile"
    }
}

/** The link transport an [RrcSession] sends over. */
interface RrcLink {
    /** Send one encoded RRC envelope as encrypted CTX_NONE link DATA. */
    suspend fun send(frame: ByteArray)

    /** Tear the underlying RNS Link down. */
    fun close()
}

/** Connection lifecycle of an [RrcSession]. */
enum class RrcState { CONNECTING, WELCOMED, CLOSED }

/** Everything the UI / storage layer reacts to. */
sealed interface RrcEvent {
    data class StateChanged(val state: RrcState) : RrcEvent
    data class Welcomed(val hubName: String?, val limits: RrcLimits) : RrcEvent
    data class RoomMessage(
        val room: String,
        val senderIdHash: ByteArray,
        val nick: String?,
        val text: String,
        val timestampMs: Long,
        /** Envelope `K_ID` (8 bytes) — lets the persistence layer
         *  dedup a hub echo or a replayed fan-out before saving. */
        val msgId: ByteArray,
    ) : RrcEvent
    data class Notice(val room: String?, val text: String) : RrcEvent
    data class HubError(val room: String?, val text: String) : RrcEvent
    data class Joined(val room: String, val members: List<ByteArray>) : RrcEvent
    data class Parted(val room: String, val members: List<ByteArray>) : RrcEvent
}
