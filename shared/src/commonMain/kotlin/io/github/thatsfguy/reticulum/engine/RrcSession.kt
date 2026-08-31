package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.rrc.Rrc
import io.github.thatsfguy.reticulum.rrc.RrcInbound
import io.github.thatsfguy.reticulum.rrc.RrcMember
import io.github.thatsfguy.reticulum.rrc.RrcMentions
import io.github.thatsfguy.reticulum.rrc.RrcLimits
import io.github.thatsfguy.reticulum.rrc.RrcMessages
import io.github.thatsfguy.reticulum.rrc.RrcNotice
import io.github.thatsfguy.reticulum.rrc.RrcNotices
import io.github.thatsfguy.reticulum.rrc.RrcReactions
import io.github.thatsfguy.reticulum.rrc.RrcResourceMeta
import io.github.thatsfguy.reticulum.rrc.RrcRoomListing
import io.github.thatsfguy.reticulum.transport.toHex

/**
 * Normalise an RRC room name to the form the hub will use.
 *
 * This MUST match the hub's own normalisation, because the hub applies
 * it to our JOIN and then fans messages out under the *normalised*
 * name — while we store the room row and our outgoing messages under
 * whatever we sent. Any disagreement splits a room in two: the hub
 * happily delivers, and the room the user is looking at stays empty.
 *
 * The rule is `reticulum-relay-chat` `internal/hub/helpers.go`
 * `normalizeRoomName`: trim, strip leading `#`, trim again, lower-case.
 *
 * The `#` is the part that bites. Room names carry no sigil — it is
 * display decoration a client adds (`rrc-room-links.md` §2.2) — but
 * every RRC UI renders rooms as `#general`, so `#general` is exactly
 * what a user types into a join field. Before this stripped it we
 * JOINed `"#general"`, the hub joined us to `general`, and every
 * message it sent back was filed under a room name no row in the
 * database had: **connected, "Joined", and silent**. Lower-casing has
 * the same shape of consequence and the same fix — rrcd lower-cases in
 * `service.py:_norm_room`, and the Go hub does too.
 */
internal fun normalizeRrcRoom(room: String): String =
    room.trim().trimStart('#').trim().lowercase()

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
    nick: String? = null,
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

    /**
     * The nick stamped on every envelope we send (`K_NICK`, key 7 —
     * `client-parity.md` §4). Mutable because `/nick` has to take
     * effect on the *next message*, not the next connect: RRC carries
     * the nick per-envelope and the hub re-stamps what it is given, so
     * there is nothing to renegotiate.
     */
    var nick: String? = nick
        private set

    /** Our identity hash as lower-case hex — for mention matching. */
    private val ourIdentityHex: String = ourIdentityHash.toHex()

    /** Hub-advertised limits — defaults until WELCOME arrives. */
    var limits: RrcLimits = RrcLimits()
        private set

    /** Hub display name from WELCOME, null until then. */
    var hubName: String? = null
        private set

    private val joinedRooms = LinkedHashSet<String>()
    private val pendingJoins = LinkedHashSet<String>()

    /** Rooms currently receiving a history replay, and when that
     *  assumption expires. Between the `--- N messages from earlier ---`
     *  and `--- end of history ---` brackets (§7) the hub re-sends the
     *  *original* envelopes, which must not ring a notification or bump
     *  an unread count. The deadline is the belt-and-braces half: a
     *  replay whose closing bracket never arrives can't silence a room
     *  forever. */
    private val historyReplayUntil = LinkedHashMap<String, Long>()

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
     *  user ran it in instead of the hub-wide banner. The slot stays
     *  claimed for [COMMAND_REPLY_WINDOW_MS] rather than being consumed
     *  by the first reply: a long answer (`/help`, `/stats`) that did
     *  not fit one frame arrives as *several* NOTICEs, and the tail
     *  belongs inline with its head. */
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

    /**
     * Change the nick stamped on everything we send from now on
     * (`/nick`). Takes effect on the next envelope — the nick rides
     * `K_NICK` per message, so no reconnect and no re-HELLO.
     */
    fun setNick(value: String?) {
        nick = value?.trim()?.ifBlank { null }
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
    suspend fun sendMessage(room: String, text: String): ByteArray =
        // Any body with a leading `/` is sent as an ACTION, because the
        // hub scans MSG bodies for one and consumes what it finds (§2).
        // The caller has already decided this is chat and not a command
        // (see RrcCommands.parse), so the text must survive intact —
        // ACTION is routed and fanned out identically and is explicitly
        // not command-dispatched.
        send(room, text, action = text.trimStart().startsWith("/"))

    /**
     * Send [text] to [room] as an ACTION (type 22) — a `/me`, or chat
     * that has to keep a leading slash. Same contract as [sendMessage].
     */
    suspend fun sendAction(room: String, text: String): ByteArray =
        send(room, text, action = true)

    private suspend fun send(room: String, text: String, action: Boolean): ByteArray {
        requireWelcomed()
        val bytes = text.encodeToByteArray()
        require(bytes.size <= limits.maxMsgBodyBytes) {
            "message is ${bytes.size} bytes, hub limit is ${limits.maxMsgBodyBytes}"
        }
        val r = normalizeRrcRoom(room)
        val envelope =
            if (action) RrcMessages.action(ourIdentityHash, nowMs(), r, text, nick)
            else RrcMessages.message(ourIdentityHash, nowMs(), r, text, nick)
        link.send(envelope.encode())
        return envelope.msgId
    }

    /**
     * Send [text] to [room] as a reply to the message with `K_ID`
     * [replyToId] (`rrc-extensions.md` key 64). Same limits and return
     * value as [sendMessage].
     */
    suspend fun sendReply(room: String, text: String, replyToId: ByteArray): ByteArray {
        requireWelcomed()
        val bytes = text.encodeToByteArray()
        require(bytes.size <= limits.maxMsgBodyBytes) {
            "message is ${bytes.size} bytes, hub limit is ${limits.maxMsgBodyBytes}"
        }
        val r = normalizeRrcRoom(room)
        val envelope =
            RrcMessages.reply(ourIdentityHash, nowMs(), r, text, replyToId, nick)
        link.send(envelope.encode())
        return envelope.msgId
    }

    /**
     * React to the message with `K_ID` [targetId] in [room], or remove
     * that reaction when [retract] is set (key 65 / 66).
     *
     * [emoji] must be a single emoji: the spec's single-grapheme rule is
     * what stops a "reaction" becoming an unbounded second message body
     * that bypasses ordinary message rendering, so it is enforced here
     * on the way out rather than trusted.
     */
    suspend fun sendReaction(
        room: String,
        targetId: ByteArray,
        emoji: String,
        retract: Boolean = false,
    ) {
        requireWelcomed()
        require(RrcReactions.isPlausibleReaction(emoji)) { "not a single reaction emoji" }
        val r = normalizeRrcRoom(room)
        link.send(
            RrcMessages.reaction(
                src = ourIdentityHash,
                timestampMs = nowMs(),
                room = r,
                emoji = emoji,
                reactToId = targetId,
                retract = retract,
                nick = nick,
            ).encode(),
        )
        logger("→ ${if (retract) "un-react" else "react"} in $r")
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
        // Log only the command verb, not its arguments (audit 2026-07-28
        // L12): the full command text carries user-typed nicknames / room
        // names / args and flows to the diagnostic stream (→ logcat in debug).
        logger("→ command ${text.substringBefore(' ')} in $r")
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
                // SECURITY (audit 2026-08-31 F7): WELCOME limits were
                // adopted verbatim. They are the bound we apply to our
                // OWN sends, so a hub advertising a negative or absurd
                // maxMsgBodyBytes either blocks every send or invites us
                // to put a megabyte on a LoRa link. Clamp to a sane
                // range; an out-of-range value falls back to the
                // conservative default rather than being trusted.
                limits = sanitizeLimits(msg.limits)
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
            is RrcInbound.Message -> {
                val fromUs = msg.src.contentEquals(ourIdentityHash)
                onEvent(
                    RrcEvent.RoomMessage(
                        room = msg.room,
                        senderIdHash = msg.src,
                        nick = msg.nick,
                        text = msg.text,
                        timestampMs = msg.envelope.timestampMs,
                        msgId = msg.envelope.msgId,
                        isHistory = isReplaying(msg.room),
                        // Our own words coming back off the fan-out can
                        // never be a mention of us.
                        isMention = !fromUs &&
                            RrcMentions.namesUs(msg.text, nick, ourIdentityHex),
                        isOwn = fromUs,
                        replyToMsgId = msg.replyTo?.toHex(),
                    ),
                )
            }
            is RrcInbound.Reaction -> {
                // SECURITY (audit 2026-08-31 F2): the single-emoji rule
                // is a RECEIVE-side check, not just a composer nicety.
                // `rrc-extensions.md` §2 states the reason outright —
                // it "keeps a 'reaction' from becoming an unbounded
                // second message body". A reaction body is rendered as
                // a chip, a surface that bypasses everything ordinary
                // message rendering does, so a hub that puts 64
                // characters of text (bidi overrides, zero-width
                // joiners, homoglyphs) in K_BODY must not reach it.
                // Sending was already gated on this; receiving was not,
                // despite RrcReactions' own kdoc claiming both paths.
                if (!RrcReactions.isPlausibleReaction(msg.emoji)) {
                    logger(
                        "dropped RRC reaction in ${msg.room}: body is not a " +
                            "single emoji (${msg.emoji.length} UTF-16 units)",
                    )
                    return
                }
                // Aggregated onto its target by the persistence layer,
                // and never rendered as a line of its own. The target is
                // resolved ONLY within the room the reaction arrived in
                // (`rrc-extensions.md` §5) — a K_ID is 8 sender-chosen
                // random bytes with no uniqueness guarantee, so a
                // cross-room lookup would let a reaction be steered onto
                // an unrelated message.
                onEvent(
                    RrcEvent.RoomReaction(
                        room = msg.room,
                        targetMsgId = msg.reactTo.toHex(),
                        emoji = msg.emoji,
                        senderIdHash = msg.src.toHex(),
                        retract = msg.retract,
                    ),
                )
            }
            is RrcInbound.Notice -> handleNotice(msg.room, msg.text)
            is RrcInbound.Error -> {
                logger("← ERROR ${msg.room ?: ""}: ${msg.text}")
                handleError(msg.room, msg.text)
            }
            is RrcInbound.Joined -> {
                val members = msg.members.map { it.toHex() }
                // Whose join is this? A JOINED is fanned out to the whole
                // room, so "we are in the member list" is true for every
                // one of them. Ours is either the confirmation of a JOIN
                // we sent, or — for a hub-side add, e.g. an invite — the
                // first time a roster has put us in a room we did not
                // think we were in.
                val self = pendingJoins.remove(msg.room) ||
                    (msg.room !in joinedRooms && ourIdentityHex in members)
                if (self) joinedRooms.add(msg.room)
                onEvent(RrcEvent.Joined(msg.room, msg.members, isSelf = self))
                if (members.isNotEmpty()) onEvent(RrcEvent.RoomMembers(msg.room, members))
            }
            is RrcInbound.Parted -> {
                val members = msg.members.map { it.toHex() }
                // PARTED carries the *remaining* members. Being absent
                // from a roster for a room we still believe we are in
                // means we were removed by the hub (a /kick or a ban) —
                // our own PART already dropped the room optimistically.
                val self = msg.room in joinedRooms &&
                    members.isNotEmpty() && ourIdentityHex !in members
                if (self) joinedRooms.remove(msg.room)
                onEvent(RrcEvent.Parted(msg.room, msg.members, isSelf = self))
                if (members.isNotEmpty()) onEvent(RrcEvent.RoomMembers(msg.room, members))
            }
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
     * Clamp hub-advertised limits into a range we are willing to act
     * on. Each field falls back to [RrcLimits]'s conservative default
     * when the hub's value is absent, non-positive, or beyond what any
     * real hub would set. Audit reference: 2026-08-31 F7.
     */
    private fun sanitizeLimits(l: RrcLimits): RrcLimits {
        val d = RrcLimits()
        fun clamp(v: Int, max: Int, fallback: Int) =
            if (v in 1..max) v else fallback
        return RrcLimits(
            maxNickBytes = clamp(l.maxNickBytes, MAX_ADVERTISABLE_NICK_BYTES, d.maxNickBytes),
            maxRoomNameBytes =
                clamp(l.maxRoomNameBytes, MAX_ADVERTISABLE_ROOM_NAME_BYTES, d.maxRoomNameBytes),
            maxMsgBodyBytes =
                clamp(l.maxMsgBodyBytes, MAX_ADVERTISABLE_MSG_BODY_BYTES, d.maxMsgBodyBytes),
            maxRoomsPerSession =
                clamp(l.maxRoomsPerSession, MAX_ADVERTISABLE_ROOMS, d.maxRoomsPerSession),
            rateLimitMsgsPerMinute =
                clamp(l.rateLimitMsgsPerMinute, MAX_ADVERTISABLE_RATE, d.rateLimitMsgsPerMinute),
        )
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
                // Same routing as a framed NOTICE — a long `/help` or
                // `/stats` answer arrives this way precisely because it
                // was too big for a frame, and it must still land in the
                // room the command was run from.
                handleNotice(room, bytes.decodeToString())
                logger("← resource ${meta.kind} (${bytes.size}B) delivered as NOTICE")
            }
            else ->
                logger("← resource ${meta.kind} (${bytes.size}B) — no handler for this kind")
        }
    }

    /**
     * Route one hub NOTICE.
     *
     * Where it lands is the whole point: a NOTICE that names a room is
     * *part of that room's conversation* — the room-info line after a
     * JOIN, a topic or mode change, a `/who` answer, a mention alert —
     * and belongs in the timeline, not in a banner over the top of the
     * app. Only a genuinely hub-wide, unattributable NOTICE (the
     * greeting / MOTD) has nowhere better to go.
     *
     * Attribution order: the NOTICE's own room, then the room a
     * mention alert names, then the room a `/`-command was just run
     * from (the hub answers most commands roomlessly), then the banner.
     */
    private fun handleNotice(room: String?, text: String) {
        val n = RrcNotices.classify(text)
        when (n) {
            is RrcNotice.Topic -> onEvent(RrcEvent.RoomTopic(n.room, n.topic))
            is RrcNotice.Mode -> onEvent(RrcEvent.RoomModes(n.room, n.modes))
            is RrcNotice.RoomInfo -> {
                onEvent(RrcEvent.RoomTopic(n.room, n.topic))
                onEvent(RrcEvent.RoomModes(n.room, n.modes))
            }
            is RrcNotice.RoomList -> onEvent(RrcEvent.RoomList(n.rooms))
            // The replay brackets (§7) are structure, not conversation:
            // they mark the envelopes between them as re-sent originals
            // so those stay out of unread counts and notifications, and
            // they are not themselves rendered or stored. Roomless ones
            // fall through and render like any other notice — a bracket
            // we cannot attribute is not one we can act on either.
            is RrcNotice.HistoryStart -> if (room != null) {
                historyReplayUntil[room] = nowMs() + HISTORY_REPLAY_TTL_MS
                logger("← history replay of ${n.count} message(s) in $room")
                return
            }
            RrcNotice.HistoryEnd -> if (room != null) {
                historyReplayUntil.remove(room)
                return
            }
            // The /who answer doubles as the room's nick roster, which
            // is what @-completion needs — a JOINED member list carries
            // identity hashes only. Still rendered inline as well: the
            // user asked a question and deserves to see the answer.
            is RrcNotice.Who -> onEvent(RrcEvent.RoomRoster(n.room, n.members))
            is RrcNotice.Mentioned, is RrcNotice.HeldMentions, RrcNotice.Plain -> Unit
        }
        // A mention alert is the hub telling us we were named somewhere
        // we were not looking — exactly what a notification is for.
        val mention = n is RrcNotice.Mentioned || n is RrcNotice.HeldMentions
        val target = room ?: (n as? RrcNotice.Mentioned)?.room ?: commandReplyRoom()
        if (target != null) {
            onEvent(RrcEvent.RoomSystemMessage(target, text, isMention = mention))
            return
        }
        // A `/list` reply nobody asked for inline is the Browse-rooms
        // dialog's data, already delivered as RoomList above. Putting
        // the multi-line dump in the banner as well would be noise.
        if (n is RrcNotice.RoomList) return
        onEvent(RrcEvent.Notice(null, text))
    }

    /**
     * Route one hub ERROR — same attribution as [handleNotice], but
     * rendered as an error. A roomless one that answers no command is
     * session-wide ("banned", "rate limited") and goes to the banner.
     */
    private fun handleError(room: String?, text: String) {
        // §5 `kicked from <room>`: the hub removed us. The PARTED
        // fan-out says so too, but only when the hub is configured to
        // include member lists — this arrives either way.
        if (room != null && text.startsWith(KICKED_PREFIX)) {
            joinedRooms.remove(room)
            onEvent(RrcEvent.Parted(room, emptyList(), isSelf = true))
        }
        val target = room ?: commandReplyRoom()
        if (target != null) {
            onEvent(RrcEvent.RoomSystemMessage(target, text, isError = true))
        } else {
            onEvent(RrcEvent.HubError(null, text))
        }
    }

    /**
     * The room a `/`-command was run from, while its reply window is
     * open. Unlike a one-shot slot this keeps answering for the whole
     * window: a reply too long for one frame arrives as several
     * NOTICEs and all of them belong in the same place.
     */
    private fun commandReplyRoom(): String? {
        val room = pendingCommandRoom ?: return null
        if (nowMs() - pendingCommandAtMs > COMMAND_REPLY_WINDOW_MS) {
            pendingCommandRoom = null
            return null
        }
        return room
    }

    /** True while [room] is inside a history-replay bracket (§7). */
    private fun isReplaying(room: String): Boolean {
        val until = historyReplayUntil[room] ?: return false
        if (nowMs() >= until) {
            historyReplayUntil.remove(room)
            return false
        }
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

        /**
         * Ceilings on what a hub may advertise in its WELCOME limits
         * (audit 2026-08-31 F7). Each is far above the reference hub's
         * default (32 / 64 / 4096 / 16 / 30) and far below anything
         * that would hurt: the body ceiling in particular is what stops
         * a hub inviting us to put an outsized message on a LoRa link.
         */
        const val MAX_ADVERTISABLE_NICK_BYTES = 256
        const val MAX_ADVERTISABLE_ROOM_NAME_BYTES = 256
        const val MAX_ADVERTISABLE_MSG_BODY_BYTES = 64 * 1024
        const val MAX_ADVERTISABLE_ROOMS = 256
        const val MAX_ADVERTISABLE_RATE = 6_000

        /** A RESOURCE payload arriving more than this after its envelope
         *  is treated as stale (audit F5). Generous — a real transfer
         *  over a slow link still completes well inside it. */
        const val RESOURCE_ENVELOPE_TTL_MS = 60_000L

        /** Minimum interval between PONGs — bounds a hub PING flood
         *  (audit F6). Far below any real keepalive cadence. */
        const val MIN_PONG_INTERVAL_MS = 500L

        /** §5 error prefix for "the hub removed you from this room". */
        const val KICKED_PREFIX = "kicked from "

        /** How long a history-replay bracket (§7) silences a room's
         *  messages when the closing bracket never arrives. Long enough
         *  for a slow replay over LoRa, short enough that a hub that
         *  drops the bracket costs one quiet window and not the
         *  session. */
        const val HISTORY_REPLAY_TTL_MS = 120_000L

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
        /** True for a message re-sent inside a history-replay bracket
         *  (§7): store it, but never notify or count it as unread —
         *  the user has already been told about it once, or was not
         *  there to care. */
        val isHistory: Boolean = false,
        /** True when the text names us by nick or hash prefix (§8). */
        val isMention: Boolean = false,
        /** Our own message coming back off the hub's fan-out — the hub
         *  delivers to every member including the sender. Stored rows
         *  dedup it on `K_ID`; consumers of the *event* (notifications)
         *  need to know too. */
        val isOwn: Boolean = false,
        /** `K_ID` (hex) of the message this replies to, or null. */
        val replyToMsgId: String? = null,
    ) : RrcEvent

    /**
     * Somebody reacted to a message in [room] (`rrc-extensions.md`).
     * Aggregated onto the target row by the persistence layer and never
     * shown as a line of its own; dropped silently when the target is
     * not held, since a stray emoji in the transcript is worse than
     * nothing.
     */
    data class RoomReaction(
        val room: String,
        /** `K_ID` (hex) of the message being reacted to. */
        val targetMsgId: String,
        val emoji: String,
        /** Reactor's link-verified identity hash, hex. */
        val senderIdHash: String,
        /** True to remove the reaction rather than add it. Apply and
         *  retract are both idempotent — see [RrcMessages.reaction]. */
        val retract: Boolean = false,
    ) : RrcEvent

    /** A hub-wide NOTICE with no room to attribute it to — the greeting
     *  / MOTD. Anything a room can claim arrives as [RoomSystemMessage]
     *  instead, so it renders in the conversation rather than a banner. */
    data class Notice(val room: String?, val text: String) : RrcEvent

    /** A session-wide hub ERROR ("banned", "rate limited", link loss). */
    data class HubError(val room: String?, val text: String) : RrcEvent

    /** A system line rendered inline in [room]: a `/`-command the user
     *  ran, the hub's reply to one, or any NOTICE / ERROR the hub
     *  attributed to a room. Persisted like a chat message but with no
     *  sender (see RrcPersistence). */
    data class RoomSystemMessage(
        val room: String,
        val text: String,
        /** The hub said no — render it as an error, not information. */
        val isError: Boolean = false,
        /** A mention alert the hub sent because we were somewhere else. */
        val isMention: Boolean = false,
    ) : RrcEvent

    /** [isSelf] distinguishes "we are now in this room" from "somebody
     *  else joined it" — the hub fans one JOINED out to every member,
     *  so the two are the same message with a different meaning. */
    data class Joined(
        val room: String,
        val members: List<ByteArray>,
        val isSelf: Boolean = false,
    ) : RrcEvent

    /** [isSelf] means *we* left — a `/kick`, a ban, or a hub-side
     *  removal, since our own PART drops membership optimistically. */
    data class Parted(
        val room: String,
        val members: List<ByteArray>,
        val isSelf: Boolean = false,
    ) : RrcEvent

    /** The room's member roster as lower-case hex identity hashes, from
     *  the JOINED / PARTED member list. Only emitted when the hub sends
     *  one (`IncludeJoinedMemberList`); silence means "not told". */
    data class RoomMembers(val room: String, val members: List<String>) : RrcEvent

    /** The room's roster WITH nicknames, parsed from a `/who` reply.
     *  The JOINED member list carries identity hashes only, so this is
     *  the only source of the nicks `@`-completion offers. */
    data class RoomRoster(val room: String, val members: List<RrcMember>) : RrcEvent

    /** A room's topic changed, parsed from the hub's topic / room-info
     *  NOTICE (§3 / §4). [topic] is null when the topic was cleared. */
    data class RoomTopic(val room: String, val topic: String?) : RrcEvent

    /** A room's mode string changed, parsed from the hub's mode /
     *  room-info NOTICE. [modes] is "" when the room has no modes set. */
    data class RoomModes(val room: String, val modes: String) : RrcEvent

    /** Reply to a `/list` request — the hub's registered public rooms. */
    data class RoomList(val rooms: List<RrcRoomListing>) : RrcEvent
}
