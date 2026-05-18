package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.rrc.Rrc
import io.github.thatsfguy.reticulum.rrc.RrcInbound
import io.github.thatsfguy.reticulum.rrc.RrcLimits
import io.github.thatsfguy.reticulum.rrc.RrcMessages
import io.github.thatsfguy.reticulum.rrc.RrcNotice
import io.github.thatsfguy.reticulum.rrc.RrcNotices
import io.github.thatsfguy.reticulum.rrc.RrcResourceMeta
import io.github.thatsfguy.reticulum.rrc.RrcRoomListing

/**
 * Normalise an RRC room name for the wire — trimmed and lower-cased.
 *
 * The Python `rrcd` hub (the reference implementation) lower-cases
 * room names server-side in `_norm_room`, so a room created with any
 * uppercase is only ever reachable as its lowercase form there. The
 * Go hub is case-sensitive and does not. Lower-casing client-side
 * means a room resolves identically against either hub — and is
 * harmless on the Go hub as long as it is applied consistently to
 * every JOIN / PART / message. See `reticulum-relay-chat` `validRoom`
 * vs. rrcd `service.py:_norm_room`.
 */
internal fun normalizeRrcRoom(room: String): String = room.trim().lowercase()

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

    /** Arrival time of [pendingResource]'s envelope — a payload that
     *  shows up long after the envelope is stale and dropped (audit F5). */
    private var pendingResourceAtMs: Long = 0L

    /** Wall-clock of the last PONG sent — bounds a hub PING flood (F6). */
    private var lastPongAtMs: Long = 0L

    /** Room a `/`-command was last issued from via [sendCommand], and
     *  when. The hub answers a command with a *roomless* NOTICE / ERROR;
     *  this lets [onInbound] attribute that reply back to the room the
     *  user ran it in instead of the hub-wide banner. One command → one
     *  reply: the slot is cleared as soon as a reply is consumed. */
    private var pendingCommandRoom: String? = null
    private var pendingCommandAtMs: Long = 0L

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
        val r = normalizeRrcRoom(room)
        pendingJoins.add(r)
        link.send(RrcMessages.join(ourIdentityHash, nowMs(), r, key, nick).encode())
        logger("→ JOIN $r")
    }

    /** Leave [room]. Membership is dropped optimistically. */
    suspend fun part(room: String) {
        requireWelcomed()
        val r = normalizeRrcRoom(room)
        joinedRooms.remove(r)
        pendingJoins.remove(r)
        link.send(RrcMessages.part(ourIdentityHash, nowMs(), r, nick).encode())
        logger("→ PART $r")
    }

    /** Ask the hub for its registered public rooms (`/list`, §2). The
     *  reply arrives as a NOTICE and surfaces via [RrcEvent.RoomList]. */
    suspend fun requestRoomList() {
        requireWelcomed()
        link.send(RrcMessages.command(ourIdentityHash, nowMs(), "/list", nick).encode())
        logger("→ /list")
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
        val r = normalizeRrcRoom(room)
        val envelope =
            if (isAction) RrcMessages.action(ourIdentityHash, nowMs(), r, text, nick)
            else RrcMessages.message(ourIdentityHash, nowMs(), r, text, nick)
        link.send(envelope.encode())
        return envelope.msgId
    }

    /**
     * Send a `/`-command (`/who`, `/list`, `/topic`, …) issued from
     * [room]. The command goes out as a MSG so the hub command-dispatches
     * it (§2); [room] rides along as K_ROOM so a room-scoped command
     * (`/who`, `/topic`) defaults to the current room.
     *
     * Unlike [sendMessage] this is NOT chat: it is echoed as a
     * [RrcEvent.RoomSystemMessage] in [room] rather than a normal
     * outgoing message, and the hub's roomless reply NOTICE / ERROR is
     * attributed back to [room] by [onInbound] — see [consumeAsCommandReply].
     */
    suspend fun sendCommand(room: String, text: String) {
        requireWelcomed()
        val bytes = text.encodeToByteArray()
        require(bytes.size <= limits.maxMsgBodyBytes) {
            "command is ${bytes.size} bytes, hub limit is ${limits.maxMsgBodyBytes}"
        }
        val r = normalizeRrcRoom(room)
        pendingCommandRoom = r
        pendingCommandAtMs = nowMs()
        link.send(RrcMessages.message(ourIdentityHash, nowMs(), r, text, nick).encode())
        onEvent(RrcEvent.RoomSystemMessage(r, text))
        logger("→ command $text in $r")
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

        // SECURITY (audit M5): until WELCOME lands, only a WELCOME (or a
        // hub ERROR — e.g. a rejected HELLO) is meaningful. Drop the rest
        // so a hostile hub cannot inject room messages / state / resource
        // envelopes before the handshake completes.
        if (state != RrcState.WELCOMED &&
            msg !is RrcInbound.Welcome &&
            msg !is RrcInbound.Error
        ) {
            logger("ignoring ${msg::class.simpleName} before WELCOME")
            return
        }

        when (msg) {
            is RrcInbound.Welcome -> {
                hubName = msg.hubName
                limits = msg.limits
                setState(RrcState.WELCOMED)
                logger("← WELCOME from ${msg.hubName} (v${msg.hubVersion})")
                onEvent(RrcEvent.Welcomed(msg.hubName, msg.limits))
            }
            is RrcInbound.Ping -> {
                // Hub keepalive — echo the payload back, but rate-limit so
                // a PING flood can't drain CPU / battery (audit F6). A
                // legitimate keepalive cadence is far slower than this.
                val now = nowMs()
                if (now - lastPongAtMs >= MIN_PONG_INTERVAL_MS) {
                    lastPongAtMs = now
                    link.send(
                        RrcMessages.pong(
                            ourIdentityHash, now,
                            payload = msg.envelope.body as? ByteArray,
                        ).encode(),
                    )
                }
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
            is RrcInbound.Notice -> {
                // A roomless NOTICE arriving right after a /command is
                // that command's reply — render it inline in the room it
                // was run from rather than the hub-wide banner.
                if (!consumeAsCommandReply(msg.room, msg.text)) {
                    val n = RrcNotices.classify(msg.text)
                    // Surface the raw text for the banner (lossless) —
                    // except a /list reply, a multi-line dump best shown
                    // only via the structured RoomList event.
                    if (n !is RrcNotice.RoomList) {
                        onEvent(RrcEvent.Notice(msg.room, msg.text))
                    }
                    when (n) {
                        is RrcNotice.Topic -> onEvent(RrcEvent.RoomTopic(n.room, n.topic))
                        is RrcNotice.Mode -> onEvent(RrcEvent.RoomModes(n.room, n.modes))
                        is RrcNotice.RoomInfo -> {
                            onEvent(RrcEvent.RoomTopic(n.room, n.topic))
                            onEvent(RrcEvent.RoomModes(n.room, n.modes))
                        }
                        is RrcNotice.RoomList -> onEvent(RrcEvent.RoomList(n.rooms))
                        RrcNotice.Plain -> Unit
                    }
                }
            }
            is RrcInbound.Error -> {
                logger("← ERROR ${msg.room ?: ""}: ${msg.text}")
                // An ERROR reply to a /command (e.g. "unrecognized
                // command") belongs in the room the command ran from.
                if (!consumeAsCommandReply(msg.room, msg.text)) {
                    onEvent(RrcEvent.HubError(msg.room, msg.text))
                }
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
                // SECURITY (audit M1): refuse an envelope that already
                // declares a payload past the cap — never start the
                // Resource transfer for it.
                if (msg.resource.size > RRC_MAX_RESOURCE_BYTES) {
                    logger(
                        "RESOURCE_ENVELOPE declares ${msg.resource.size}B " +
                            "> cap $RRC_MAX_RESOURCE_BYTES — ignoring",
                    )
                    return
                }
                // §6: the hub announces a large payload, then streams it
                // as an RNS Resource on the link. Stash the metadata; the
                // assembled bytes arrive later via onResourcePayload().
                pendingResource = msg.resource
                pendingResourceRoom = msg.envelope.room
                pendingResourceAtMs = nowMs()
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
        // SECURITY (audit F5): a payload that arrives long after its
        // envelope is stale — drop it rather than attributing it to a
        // room the user may have since navigated away from.
        if (nowMs() - pendingResourceAtMs > RESOURCE_ENVELOPE_TTL_MS) {
            logger("resource payload arrived stale (>${RESOURCE_ENVELOPE_TTL_MS}ms) — dropping")
            pendingResource = null
            pendingResourceRoom = null
            return
        }
        // SECURITY (audit M1): a hard ceiling independent of the envelope's
        // (attacker-controlled) declared size — the size-equality check
        // below is self-consistent for a hostile hub and not a bound.
        if (bytes.size.toLong() > RRC_MAX_RESOURCE_BYTES) {
            logger("resource payload ${bytes.size}B exceeds cap $RRC_MAX_RESOURCE_BYTES — dropping")
            pendingResource = null
            pendingResourceRoom = null
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

    /**
     * If a `/`-command sent via [sendCommand] is awaiting its reply and
     * [noticeRoom] is roomless (the hub answers commands with a roomless
     * NOTICE / ERROR) and the reply arrived inside [COMMAND_REPLY_WINDOW_MS],
     * attribute [text] to the room the command ran from as a
     * [RrcEvent.RoomSystemMessage] and return true. Otherwise return false
     * — the caller surfaces the NOTICE / ERROR normally (banner / topic).
     *
     * A *roomed* notice never consumes the slot: a `/topic` update can
     * arrive between the command and its reply.
     */
    private fun consumeAsCommandReply(noticeRoom: String?, text: String): Boolean {
        if (noticeRoom != null) return false
        val room = pendingCommandRoom ?: return false
        pendingCommandRoom = null
        if (nowMs() - pendingCommandAtMs > COMMAND_REPLY_WINDOW_MS) return false
        onEvent(RrcEvent.RoomSystemMessage(room, text))
        return true
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

        /** Hard ceiling on an inbound RRC Resource payload (§6). The hub
         *  uses these for large NOTICE / MOTD text and opaque blobs;
         *  256 KiB is far above any real chat notice and bounds what a
         *  hostile hub can push into UI / storage. */
        const val RRC_MAX_RESOURCE_BYTES = 256L * 1024

        /** A RESOURCE payload arriving more than this after its envelope
         *  is treated as stale (audit F5). Generous — a real transfer
         *  over a slow link still completes well inside it. */
        const val RESOURCE_ENVELOPE_TTL_MS = 60_000L

        /** Minimum interval between PONGs — bounds a hub PING flood
         *  (audit F6). Far below any real keepalive cadence. */
        const val MIN_PONG_INTERVAL_MS = 500L

        /** A roomless NOTICE / ERROR arriving within this long after a
         *  [sendCommand] is treated as that command's reply and rendered
         *  in the room it ran from; past it, the reply (if any) falls
         *  back to the hub-wide banner. Generous for a slow LoRa link. */
        const val COMMAND_REPLY_WINDOW_MS = 20_000L
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
    /** A system line rendered inline in [room] — a `/`-command the user
     *  ran, or the hub's reply to one. Persisted like a chat message
     *  but with no sender (see RrcPersistence). */
    data class RoomSystemMessage(val room: String, val text: String) : RrcEvent
    data class Joined(val room: String, val members: List<ByteArray>) : RrcEvent
    data class Parted(val room: String, val members: List<ByteArray>) : RrcEvent

    /** A room's topic changed, parsed from the hub's topic / room-info
     *  NOTICE (§3 / §4). [topic] is null when the topic was cleared. */
    data class RoomTopic(val room: String, val topic: String?) : RrcEvent

    /** A room's mode string changed, parsed from the hub's mode /
     *  room-info NOTICE. [modes] is "" when the room has no modes set. */
    data class RoomModes(val room: String, val modes: String) : RrcEvent

    /** Reply to a `/list` request — the hub's registered public rooms. */
    data class RoomList(val rooms: List<RrcRoomListing>) : RrcEvent
}
