package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.rrc.Rrc
import io.github.thatsfguy.reticulum.rrc.RrcEnvelope
import io.github.thatsfguy.reticulum.rrc.RrcMessages
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RrcSession protocol state machine — driven through a fake [RrcLink]
 * so the whole thing is exercised without an engine or a real link.
 * camelCase test names keep the iosTest K/N compile happy.
 */
class RrcSessionTest {

    private val me = ByteArray(16) { it.toByte() }
    private val hub = ByteArray(16) { 0xAA.toByte() }

    private class FakeLink : RrcLink {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        override suspend fun send(frame: ByteArray) { sent.add(frame) }
        override fun close() { closed = true }
    }

    private fun newSession(
        link: RrcLink,
        onEvent: (RrcEvent) -> Unit = {},
    ) = RrcSession(me, link, nowMs = { 1_700_000_000_000L }, nick = "alice", onEvent = onEvent)

    /** A hub WELCOME frame with a configurable max-message-body limit. */
    private fun welcomeFrame(maxBody: Int = 4096): ByteArray {
        val limits = linkedMapOf<Any?, Any?>(
            Rrc.B_LIMIT_MAX_NICK_BYTES to 32,
            Rrc.B_LIMIT_MAX_ROOM_NAME_BYTES to 64,
            Rrc.B_LIMIT_MAX_MSG_BODY_BYTES to maxBody,
            Rrc.B_LIMIT_MAX_ROOMS_PER_SESSION to 16,
            Rrc.B_LIMIT_RATE_LIMIT_MSGS_PER_MINUTE to 30,
        )
        val body = linkedMapOf<Any?, Any?>(
            Rrc.B_WELCOME_HUB to "testhub",
            Rrc.B_WELCOME_VER to "1.0",
            Rrc.B_WELCOME_LIMITS to limits,
        )
        return RrcEnvelope(Rrc.T_WELCOME, ByteArray(8), 1L, hub, body = body).encode()
    }

    private fun joinedFrame(room: String): ByteArray =
        RrcEnvelope(Rrc.T_JOINED, ByteArray(8), 1L, hub, room = room, body = listOf(ByteArray(16)))
            .encode()

    /** A hub RESOURCE_ENVELOPE (§6) announcing a payload of [size] bytes. */
    private fun resourceEnvelopeFrame(kind: String, size: Int, room: String): ByteArray {
        val body = linkedMapOf<Any?, Any?>(
            Rrc.B_RES_ID to ByteArray(8),
            Rrc.B_RES_KIND to kind,
            Rrc.B_RES_SIZE to size,
        )
        return RrcEnvelope(Rrc.T_RESOURCE_ENVELOPE, ByteArray(8), 1L, hub, room = room, body = body)
            .encode()
    }

    @Test fun startSendsHello() = runTest {
        val link = FakeLink()
        newSession(link).start()
        assertEquals(1, link.sent.size)
        assertEquals(Rrc.T_HELLO, RrcEnvelope.decode(link.sent[0]).type)
    }

    @Test fun welcomeMovesToWelcomedAndSetsLimits() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame(maxBody = 1234))

        assertEquals(RrcState.WELCOMED, session.state)
        assertEquals("testhub", session.hubName)
        assertEquals(1234, session.limits.maxMsgBodyBytes)
        assertTrue(events.any { it is RrcEvent.Welcomed })
    }

    @Test fun hubPingIsAnsweredWithPong() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        // A hub PING is a T_PING envelope; reuse the builder to shape one.
        val ping = RrcMessages.ping(hub, 1L, payload = byteArrayOf(7, 7)).encode()
        session.onInbound(ping)

        assertEquals(1, link.sent.size, "PING must be answered")
        assertEquals(Rrc.T_PONG, RrcEnvelope.decode(link.sent[0]).type)
    }

    @Test fun roomMessageSurfacesAsEvent() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        val msg = RrcMessages.message(hub, 1L, room = "general", text = "hello", nick = "bob").encode()
        session.onInbound(msg)

        val m = events.filterIsInstance<RrcEvent.RoomMessage>().single()
        assertEquals("general", m.room)
        assertEquals("hello", m.text)
        assertEquals("bob", m.nick)
    }

    @Test fun messageBeforeWelcomeIsIgnored() = runTest {
        // SECURITY (audit M5): a hostile hub injecting a MSG before the
        // HELLO/WELCOME handshake must not reach the UI / persistence.
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.onInbound(
            RrcMessages.message(hub, 1L, room = "#x", text = "injected", nick = "evil").encode(),
        )
        assertTrue(
            events.none { it is RrcEvent.RoomMessage },
            "a pre-WELCOME MSG must be dropped, not surfaced",
        )
    }

    @Test fun joinThenJoinedConfirmsMembership() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        session.join("general")
        assertTrue(session.rooms.isEmpty(), "membership unconfirmed until JOINED arrives")

        session.onInbound(joinedFrame("general"))
        assertTrue(session.rooms.contains("general"))
    }

    @Test fun sendMessageRejectsOversizeText() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame(maxBody = 8))
        assertFailsWith<IllegalArgumentException> {
            session.sendMessage("general", "this text is definitely longer than eight bytes")
        }
    }

    @Test fun sendMessageBeforeWelcomeThrows() = runTest {
        val session = newSession(FakeLink())
        assertFailsWith<IllegalStateException> { session.sendMessage("general", "hi") }
    }

    @Test fun closeTearsDownLink() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.close()
        assertTrue(link.closed)
        assertEquals(RrcState.CLOSED, session.state)
    }

    @Test fun meTextSendsAsAction() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.sendMessage("general", "/me waves")
        val env = RrcEnvelope.decode(link.sent.single())
        assertEquals(Rrc.T_ACTION, env.type, "/me text must go out as ACTION, not MSG")
        assertEquals("/me waves", env.body)
    }

    @Test fun chatStartingWithSlashSendsAsAction() = runTest {
        // A command reaches the hub through sendCommand, as a MSG (see
        // sendCommandEchoesAsRoomSystemMessage). Anything routed through
        // sendMessage has already been judged to be *chat*, so a leading
        // `/` must survive — and only ACTION (type 22) survives, because
        // the hub consumes MSG bodies that start with one (§2).
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.sendMessage("general", "/not-a-command after all")
        assertEquals(Rrc.T_ACTION, RrcEnvelope.decode(link.sent.single()).type)
    }

    @Test fun plainChatSendsAsMsg() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.sendMessage("general", "hello room")
        assertEquals(Rrc.T_MSG, RrcEnvelope.decode(link.sent.single()).type)
    }

    @Test fun resourcePayloadAfterEnvelopeSurfacesAsNotice() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        val payload = "a large notice body".encodeToByteArray()
        // Hub announces the payload, then delivers it as an RNS Resource.
        session.onInbound(resourceEnvelopeFrame(Rrc.RES_KIND_NOTICE, payload.size, "r"))
        session.onResourcePayload(payload)
        // Routed exactly like a framed NOTICE: it names a room, so it
        // belongs in that room's timeline, not in the hub-wide banner.
        val line = events.filterIsInstance<RrcEvent.RoomSystemMessage>().last()
        assertEquals("r", line.room)
        assertEquals("a large notice body", line.text)
    }

    @Test fun topicNoticeEmitsRoomTopicEvent() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        val notice = RrcEnvelope(
            Rrc.T_NOTICE, ByteArray(8), 1L, hub,
            body = "topic for general is now: hello there",
        ).encode()
        session.onInbound(notice)
        val topic = events.filterIsInstance<RrcEvent.RoomTopic>().single()
        assertEquals("general", topic.room)
        assertEquals("hello there", topic.topic)
        // The raw NOTICE is still surfaced — structured parsing is lossless.
        assertTrue(events.any { it is RrcEvent.Notice })
    }

    @Test fun roomInfoNoticeEmitsTopicAndModes() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        val notice = RrcEnvelope(
            Rrc.T_NOTICE, ByteArray(8), 1L, hub,
            body = "room #general: registered; mode=+int; topic=be nice",
        ).encode()
        session.onInbound(notice)
        assertEquals("be nice", events.filterIsInstance<RrcEvent.RoomTopic>().single().topic)
        assertEquals("+int", events.filterIsInstance<RrcEvent.RoomModes>().single().modes)
    }

    @Test fun requestRoomListSendsRoomlessListCommand() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.requestRoomList()
        val env = RrcEnvelope.decode(link.sent.single())
        assertEquals(Rrc.T_MSG, env.type)
        assertEquals("/list", env.body)
        assertEquals(null, env.room, "/list goes out as a roomless command MSG")
    }

    @Test fun roomListNoticeEmitsRoomListEvent() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        val notice = RrcEnvelope(
            Rrc.T_NOTICE, ByteArray(8), 1L, hub,
            body = "Registered public rooms:\n  lobby\n  dev - hacking",
        ).encode()
        session.onInbound(notice)
        val list = events.filterIsInstance<RrcEvent.RoomList>().single()
        assertEquals(2, list.rooms.size)
        assertEquals("lobby", list.rooms[0].name)
        // a /list reply must NOT also surface as a raw NOTICE banner
        assertTrue(events.none { it is RrcEvent.Notice && it.text.startsWith("Registered") })
    }

    @Test fun resourcePayloadWrongSizeIsDropped() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(resourceEnvelopeFrame(Rrc.RES_KIND_NOTICE, 999, "r"))
        session.onResourcePayload("short".encodeToByteArray()) // 5 bytes ≠ declared 999
        assertTrue(events.none { it is RrcEvent.Notice && it.text == "short" })
    }

    @Test fun sendCommandEchoesAsRoomSystemMessage() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.sendCommand("general", "/who")
        // The command goes out as a MSG so the hub command-dispatches it.
        assertEquals(Rrc.T_MSG, RrcEnvelope.decode(link.sent.single()).type)
        // …and is echoed inline as a system line in the room it ran from
        // — NOT stored as a normal outgoing chat message.
        val echo = events.filterIsInstance<RrcEvent.RoomSystemMessage>().single()
        assertEquals("general", echo.room)
        assertTrue(echo.text.contains("/who"))
    }

    @Test fun commandReplyNoticeLandsInRoom() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.sendCommand("general", "/who")
        // The hub answers /who with a roomless NOTICE (emit_notice room=None).
        session.onInbound(
            RrcEnvelope(Rrc.T_NOTICE, ByteArray(8), 1L, hub, body = "members in #general: alice").encode(),
        )
        assertTrue(
            events.filterIsInstance<RrcEvent.RoomSystemMessage>().any {
                it.room == "general" && it.text.contains("members in #general")
            },
            "a command reply must surface inline in the room it was run from",
        )
        assertTrue(
            events.none { it is RrcEvent.Notice },
            "a consumed command reply must NOT also hit the hub-wide banner",
        )
    }

    @Test fun commandErrorReplyLandsInRoom() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.sendCommand("general", "/help")
        session.onInbound(
            RrcEnvelope(Rrc.T_ERROR, ByteArray(8), 1L, hub, body = "unrecognized command").encode(),
        )
        assertTrue(
            events.filterIsInstance<RrcEvent.RoomSystemMessage>().any {
                it.room == "general" && it.text.contains("unrecognized command")
            },
            "an ERROR reply to a command must surface in the room, not the banner",
        )
        assertTrue(events.none { it is RrcEvent.HubError })
    }

    @Test fun joinLowercasesRoomName() = runTest {
        // The Python rrcd hub normalises room names to lowercase; the
        // Go hub is case-sensitive. The client lowercases on the way
        // out so a room created with any uppercase resolves the same
        // against either hub.
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.join("General")
        val env = RrcEnvelope.decode(link.sent.single())
        assertEquals(Rrc.T_JOIN, env.type)
        assertEquals("general", env.room)
    }

    @Test fun joinedReplyConfirmsLowercasedRoom() = runTest {
        // User typed mixed case → we sent lowercase → the hub's JOINED
        // reply is lowercase → membership must confirm.
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        session.join("General")
        session.onInbound(joinedFrame("general"))
        assertTrue(session.rooms.contains("general"))
    }

    @Test fun sendMessageLowercasesRoom() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.sendMessage("General", "hi")
        assertEquals("general", RrcEnvelope.decode(link.sent.single()).room)
    }

    @Test fun unsolicitedRoomlessNoticeStillHitsBanner() = runTest {
        // With no command pending, a roomless hub NOTICE (MOTD etc.) must
        // still surface as a banner Notice — never misfiled into a room.
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(
            RrcEnvelope(Rrc.T_NOTICE, ByteArray(8), 1L, hub, body = "welcome to the hub").encode(),
        )
        assertTrue(events.any { it is RrcEvent.Notice })
        assertTrue(events.none { it is RrcEvent.RoomSystemMessage })
    }

    // ---- room-name normalisation -------------------------------------
    //
    // This MUST match the hub's `normalizeRoomName` (trim, strip leading
    // `#`, trim, lower-case). When it doesn't, the hub joins us to the
    // normalised name and fans messages out under it while we file the
    // room row and our own outgoing messages under what we sent — the
    // room reads "Joined" and stays empty while messages arrive.

    @Test fun roomNameNormalisationMatchesTheHub() {
        assertEquals("general", normalizeRrcRoom("general"))
        assertEquals("general", normalizeRrcRoom("General"))
        assertEquals("general", normalizeRrcRoom("  general  "))
        // The sigil is display decoration a client adds; room names
        // carry none, and `#general` is exactly what a user types.
        assertEquals("general", normalizeRrcRoom("general"))
        assertEquals("general", normalizeRrcRoom("General"))
        assertEquals("general", normalizeRrcRoom("  #general "))
        assertEquals("general", normalizeRrcRoom("# general"))
        // TrimLeft strips a run, matching Go's TrimLeft(name, "#").
        assertEquals("general", normalizeRrcRoom("##general"))
    }

    /** The JOIN we send and the room the hub answers about have to be
     *  the same string, or the room is silently split in two. */
    @Test fun joinSendsTheNormalisedRoomName() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.join("General")
        assertEquals("general", RrcEnvelope.decode(link.sent.single()).room)
    }

    @Test fun sendingToASigiledRoomUsesTheNormalisedName() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.sendMessage("General", "hi")
        assertEquals("general", RrcEnvelope.decode(link.sent.single()).room)
    }

    // ---- inline routing of hub replies (the "commands land in a
    //      banner instead of the conversation" fix) -------------------

    private fun noticeFrame(room: String?, text: String): ByteArray =
        RrcEnvelope(Rrc.T_NOTICE, ByteArray(8), 1L, hub, room = room, body = text).encode()

    private fun errorFrame(room: String?, text: String): ByteArray =
        RrcEnvelope(Rrc.T_ERROR, ByteArray(8), 1L, hub, room = room, body = text).encode()

    private fun msgFrame(
        room: String,
        text: String,
        src: ByteArray = ByteArray(16) { 0x33 },
        nick: String? = "bob",
        msgId: ByteArray = ByteArray(8) { 0x55 },
    ): ByteArray =
        RrcEnvelope(Rrc.T_MSG, msgId, 1L, src, room = room, body = text, nick = nick).encode()

    /** A NOTICE that names a room is part of that room's conversation —
     *  the `/who` answer, a topic change, the room-info line — and must
     *  render there, not over the top of the app. */
    @Test fun roomedNoticeRendersInlineNotInTheBanner() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(noticeFrame("general", "members in general: alice, bob"))
        val line = events.filterIsInstance<RrcEvent.RoomSystemMessage>().single()
        assertEquals("general", line.room)
        assertTrue(events.none { it is RrcEvent.Notice })
    }

    @Test fun roomedErrorRendersInlineAsAnError() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(errorFrame("general", "room is moderated (+m)"))
        val line = events.filterIsInstance<RrcEvent.RoomSystemMessage>().single()
        assertTrue(line.isError)
        assertTrue(events.none { it is RrcEvent.HubError })
    }

    /** A session-wide refusal has no room to belong to. */
    @Test fun roomlessErrorStillHitsTheBanner() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(errorFrame(null, "rate limited"))
        assertTrue(events.any { it is RrcEvent.HubError })
    }

    /** A long answer (`/help`, `/stats`) does not fit one frame and
     *  arrives as several roomless NOTICEs — the tail belongs inline
     *  with the head, not scattered into the banner. */
    @Test fun everyReplyInTheWindowLandsInTheCommandsRoom() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.sendCommand("general", "/help")
        session.onInbound(noticeFrame(null, "Commands on this hub:"))
        session.onInbound(noticeFrame(null, "  /who [room]   who is in a room"))
        val lines = events.filterIsInstance<RrcEvent.RoomSystemMessage>()
        // The echo of the command plus both halves of the reply.
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.room == "general" })
        assertTrue(events.none { it is RrcEvent.Notice })
    }

    @Test fun kickedErrorDropsMembership() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.join("general")
        session.onInbound(joinedFrame("general"))
        assertTrue(session.rooms.contains("general"))
        session.onInbound(errorFrame("general", "kicked from #general"))
        assertTrue(session.rooms.isEmpty())
        assertTrue(events.filterIsInstance<RrcEvent.Parted>().any { it.isSelf })
    }

    // ---- history replay (§7) -----------------------------------------

    /** Between the replay brackets the hub re-sends the ORIGINAL
     *  envelopes. Those must be stored but never notified on. */
    @Test fun messagesInsideAHistoryBracketAreFlaggedAsHistory() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())

        session.onInbound(noticeFrame("general", "--- 2 messages from earlier ---"))
        session.onInbound(msgFrame("general", "old one", msgId = ByteArray(8) { 1 }))
        session.onInbound(noticeFrame("general", "--- end of history ---"))
        session.onInbound(msgFrame("general", "live one", msgId = ByteArray(8) { 2 }))

        val messages = events.filterIsInstance<RrcEvent.RoomMessage>()
        assertEquals(2, messages.size)
        assertTrue(messages[0].isHistory, "replayed message must be flagged")
        assertTrue(!messages[1].isHistory, "live message must not be")
        // The brackets themselves are structure, not conversation.
        assertTrue(events.filterIsInstance<RrcEvent.RoomSystemMessage>().isEmpty())
    }

    // ---- mentions (§8) -----------------------------------------------

    @Test fun aMessageNamingOurNickIsAMention() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(msgFrame("general", "@alice can you look?"))
        assertTrue(events.filterIsInstance<RrcEvent.RoomMessage>().single().isMention)
    }

    /** Our own words coming back off the fan-out are neither a mention
     *  nor something to notify about. */
    @Test fun ourOwnEchoIsNeitherMentionNorNew() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(msgFrame("general", "@alice hello", src = me, nick = "alice"))
        val msg = events.filterIsInstance<RrcEvent.RoomMessage>().single()
        assertTrue(msg.isOwn)
        assertTrue(!msg.isMention)
    }

    @Test fun hubMentionAlertIsFiledInTheRoomItNames() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(
            noticeFrame(null, "you were mentioned in #ops by bob: can you look at this"),
        )
        val line = events.filterIsInstance<RrcEvent.RoomSystemMessage>().single()
        assertEquals("ops", line.room)
        assertTrue(line.isMention)
    }

    // ---- membership + nick -------------------------------------------

    /** A JOINED is fanned out to the whole room, so "we are in the
     *  member list" cannot mean "we just joined" — only an outstanding
     *  JOIN, or a roster for a room we did not think we were in, can. */
    @Test fun ourOwnJoinIsDistinguishedFromSomebodyElses() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.join("general")
        session.onInbound(
            RrcEnvelope(
                Rrc.T_JOINED, ByteArray(8), 1L, hub, room = "general",
                body = listOf(me),
            ).encode(),
        )
        session.onInbound(
            RrcEnvelope(
                Rrc.T_JOINED, ByteArray(8), 1L, hub, room = "general",
                body = listOf(me, ByteArray(16) { 0x33 }),
            ).encode(),
        )
        val joins = events.filterIsInstance<RrcEvent.Joined>()
        assertEquals(2, joins.size)
        assertTrue(joins[0].isSelf, "our own JOIN confirmation")
        assertTrue(!joins[1].isSelf, "somebody else joining the same room")
        assertEquals(2, events.filterIsInstance<RrcEvent.RoomMembers>().last().members.size)
    }

    // ---- replies + reactions (rrc-extensions.md) ---------------------

    @Test fun anInboundReactionIsNeverAChatLine() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        val target = ByteArray(8) { 0x5A }
        session.onInbound(
            RrcEnvelope(
                Rrc.T_MSG, ByteArray(8) { 1 }, 1L, ByteArray(16) { 0x33 },
                room = "general", body = "\uD83D\uDC4D", nick = "bob",
                reactTo = target,
            ).encode(),
        )
        val reaction = events.filterIsInstance<RrcEvent.RoomReaction>().single()
        assertEquals("general", reaction.room)
        assertEquals("\uD83D\uDC4D", reaction.emoji)
        assertTrue(!reaction.retract)
        // The whole point: it must not also surface as a message.
        assertTrue(events.filterIsInstance<RrcEvent.RoomMessage>().isEmpty())
    }

    @Test fun anInboundRetractionIsFlagged() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(
            RrcEnvelope(
                Rrc.T_MSG, ByteArray(8) { 1 }, 1L, ByteArray(16) { 0x33 },
                room = "general", body = "\uD83D\uDC4D",
                reactTo = ByteArray(8) { 0x5A }, reactOp = Rrc.REACT_OP_RETRACT,
            ).encode(),
        )
        assertTrue(events.filterIsInstance<RrcEvent.RoomReaction>().single().retract)
    }

    @Test fun anInboundReplyCarriesItsAnchor() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        val target = ByteArray(8) { 0x5A }
        session.onInbound(
            RrcEnvelope(
                Rrc.T_MSG, ByteArray(8) { 2 }, 1L, ByteArray(16) { 0x33 },
                room = "general", body = "yes, exactly that", nick = "bob",
                replyTo = target,
            ).encode(),
        )
        val msg = events.filterIsInstance<RrcEvent.RoomMessage>().single()
        assertEquals(target.toHex(), msg.replyToMsgId)
        assertEquals("yes, exactly that", msg.text)
    }

    @Test fun sendingAReplyCarriesTheAnchorOnTheWire() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        val target = ByteArray(8) { 0x5A }
        session.sendReply("general", "yes, exactly that", target)
        val env = RrcEnvelope.decode(link.sent.single())
        assertEquals(Rrc.T_MSG, env.type)
        assertEquals(target.toHex(), env.replyTo?.toHex())
    }

    @Test fun sendingAReactionCarriesTheAnchorAndOp() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        val target = ByteArray(8) { 0x5A }
        session.sendReaction("general", target, "\uD83D\uDC4D")
        session.sendReaction("general", target, "\uD83D\uDC4D", retract = true)
        val applied = RrcEnvelope.decode(link.sent[0])
        val retracted = RrcEnvelope.decode(link.sent[1])
        assertEquals(target.toHex(), applied.reactTo?.toHex())
        // Omitted when applying — 0 is the default and the §7 vector
        // leaves it out.
        assertEquals(null, applied.reactOp)
        assertEquals(Rrc.REACT_OP_RETRACT, retracted.reactOp)
    }

    /** The single-emoji rule is enforced on the way OUT too, so this
     *  client can never be the one that turns a reaction body into a
     *  second message channel. */
    @Test fun sendingANonEmojiReactionIsRefused() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        assertFailsWith<IllegalArgumentException> {
            session.sendReaction("general", ByteArray(8), "not an emoji")
        }
    }

    /** `/who` is the only source of nicknames — a JOINED member list
     *  carries identity hashes only. */
    @Test fun aWhoReplyBecomesTheRoomRoster() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.onInbound(
            noticeFrame("general", "members in general: alice (6b621001912f), bob (aa11bb22cc33)"),
        )
        val roster = events.filterIsInstance<RrcEvent.RoomRoster>().single()
        assertEquals(listOf("alice", "bob"), roster.members.map { it.nick })
        // Still rendered inline — the user asked and deserves the answer.
        assertTrue(events.filterIsInstance<RrcEvent.RoomSystemMessage>().isNotEmpty())
    }

    /** `/nick` has to take effect on the next message: the nick rides
     *  K_NICK per envelope, so there is nothing to renegotiate. */
    @Test fun setNickAppliesToTheNextMessage() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        session.setNick("carol")
        link.sent.clear()
        session.sendMessage("general", "hi")
        assertEquals("carol", RrcEnvelope.decode(link.sent.single()).nick)
    }
}
