package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.rrc.Rrc
import io.github.thatsfguy.reticulum.rrc.RrcInbound
import io.github.thatsfguy.reticulum.rrc.RrcLimits
import io.github.thatsfguy.reticulum.rrc.RrcMessages
import io.github.thatsfguy.reticulum.rrc.RrcResourceMeta

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
    /** Hashes a Resource payload for the optional §6 SHA-256 check; null
     *  skips it (the RNS Resource layer already integrity-checks the
     *  bytes, so the envelope SHA-256 is a redundant end-to-end guard). */
    private val sha256: (suspend (ByteArray) -> ByteArray)? = null,
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

    /** Metadata of a RESOURCE_ENVELOPE whose payload hasn't arrived yet
     *  (§6). The hub sends the envelope, then the payload as an RNS
     *  Resource on the link; [onResourcePayload] correlates them. */
    private var pendingResource: RrcResourceMeta? = null
    private var pendingResourceRoom: String? = null

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
        // `/me …` goes out as ACTION (type 22): the hub routes it like a
        // MSG but does NOT consume it as a hub command, so the leading
        // `/` survives. Every other `/command` stays a MSG, which the hub
        // then intercepts as a hub-local command (§2). Plain text → MSG.
        val isAction = text.startsWith("/me ") || text == "/me"
        val envelope =
            if (isAction) RrcMessages.action(ourIdentityHash, nowMs(), room, text, nick)
            else RrcMessages.message(ourIdentityHash, nowMs(), room, text, nick)
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
            is RrcInbound.ResourceEnvelope -> {
                // §6: the hub announces a large payload, then streams it
                // as an RNS Resource on the link. Stash the metadata; the
                // assembled bytes arrive later via onResourcePayload().
                pendingResource = msg.resource
                pendingResourceRoom = msg.envelope.room
                logger(
                    "← RESOURCE_ENVELOPE kind=${msg.resource.kind} " +
                        "size=${msg.resource.size} — awaiting payload",
                )
            }
            is RrcInbound.Unknown ->
                logger("← unknown RRC message type ${msg.envelope.type}")
        }
    }

    /**
     * Feed the bytes of a fully-assembled inbound RNS Resource — the
     * payload that follows a RESOURCE_ENVELOPE (§6). The engine wires
     * this to the RRC link's resource-receive callback.
     *
     * The payload is correlated to the most recent envelope by size (and
     * SHA-256 when the envelope carried one and a hasher was supplied).
     * `notice` / `motd` kinds surface as a [RrcEvent.Notice]; `blob` is
     * opaque and has no chat rendering, so it is logged and dropped.
     */
    suspend fun onResourcePayload(bytes: ByteArray) {
        val meta = pendingResource ?: run {
            logger("resource payload (${bytes.size}B) with no RESOURCE_ENVELOPE — dropping")
            return
        }
        if (meta.size != 0L && bytes.size.toLong() != meta.size) {
            logger("resource payload size ${bytes.size} ≠ envelope ${meta.size} — dropping")
            return
        }
        val expectedSha = meta.sha256
        val hasher = sha256
        if (expectedSha != null && hasher != null) {
            if (!hasher(bytes).contentEquals(expectedSha)) {
                logger("resource payload SHA-256 mismatch — dropping")
                return
            }
        }
        val room = pendingResourceRoom
        pendingResource = null
        pendingResourceRoom = null
        when (meta.kind) {
            Rrc.RES_KIND_NOTICE, Rrc.RES_KIND_MOTD -> {
                onEvent(RrcEvent.Notice(room, bytes.decodeToString()))
                logger("← resource ${meta.kind} (${bytes.size}B) delivered as NOTICE")
            }
            else ->
                logger("← resource ${meta.kind} (${bytes.size}B) — no handler for this kind")
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
