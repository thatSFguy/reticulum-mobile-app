package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.rrc.Rrc
import io.github.thatsfguy.reticulum.rrc.RrcEnvelope
import io.github.thatsfguy.reticulum.rrc.RrcMessages
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
        val msg = RrcMessages.message(hub, 1L, room = "#general", text = "hello", nick = "bob").encode()
        session.onInbound(msg)

        val m = events.filterIsInstance<RrcEvent.RoomMessage>().single()
        assertEquals("#general", m.room)
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
        session.join("#general")
        assertTrue(session.rooms.isEmpty(), "membership unconfirmed until JOINED arrives")

        session.onInbound(joinedFrame("#general"))
        assertTrue(session.rooms.contains("#general"))
    }

    @Test fun sendMessageRejectsOversizeText() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame(maxBody = 8))
        assertFailsWith<IllegalArgumentException> {
            session.sendMessage("#general", "this text is definitely longer than eight bytes")
        }
    }

    @Test fun sendMessageBeforeWelcomeThrows() = runTest {
        val session = newSession(FakeLink())
        assertFailsWith<IllegalStateException> { session.sendMessage("#general", "hi") }
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
        session.sendMessage("#general", "/me waves")
        val env = RrcEnvelope.decode(link.sent.single())
        assertEquals(Rrc.T_ACTION, env.type, "/me text must go out as ACTION, not MSG")
        assertEquals("/me waves", env.body)
    }

    @Test fun slashCommandSendsAsMsg() = runTest {
        // /list, /who, … stay a MSG so the hub command-dispatches them
        // (§2); only /me is special-cased to ACTION.
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.sendMessage("#general", "/list")
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
        session.onInbound(resourceEnvelopeFrame(Rrc.RES_KIND_NOTICE, payload.size, "#r"))
        session.onResourcePayload(payload)
        val notice = events.filterIsInstance<RrcEvent.Notice>().last()
        assertEquals("#r", notice.room)
        assertEquals("a large notice body", notice.text)
    }

    @Test fun topicNoticeEmitsRoomTopicEvent() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        val notice = RrcEnvelope(
            Rrc.T_NOTICE, ByteArray(8), 1L, hub,
            body = "topic for #general is now: hello there",
        ).encode()
        session.onInbound(notice)
        val topic = events.filterIsInstance<RrcEvent.RoomTopic>().single()
        assertEquals("#general", topic.room)
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
        session.onInbound(resourceEnvelopeFrame(Rrc.RES_KIND_NOTICE, 999, "#r"))
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
        session.sendCommand("#general", "/who")
        // The command goes out as a MSG so the hub command-dispatches it.
        assertEquals(Rrc.T_MSG, RrcEnvelope.decode(link.sent.single()).type)
        // …and is echoed inline as a system line in the room it ran from
        // — NOT stored as a normal outgoing chat message.
        val echo = events.filterIsInstance<RrcEvent.RoomSystemMessage>().single()
        assertEquals("#general", echo.room)
        assertTrue(echo.text.contains("/who"))
    }

    @Test fun commandReplyNoticeLandsInRoom() = runTest {
        val link = FakeLink()
        val events = mutableListOf<RrcEvent>()
        val session = newSession(link, onEvent = { events.add(it) })
        session.start()
        session.onInbound(welcomeFrame())
        session.sendCommand("#general", "/who")
        // The hub answers /who with a roomless NOTICE (emit_notice room=None).
        session.onInbound(
            RrcEnvelope(Rrc.T_NOTICE, ByteArray(8), 1L, hub, body = "members in #general: alice").encode(),
        )
        assertTrue(
            events.filterIsInstance<RrcEvent.RoomSystemMessage>().any {
                it.room == "#general" && it.text.contains("members in #general")
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
        session.sendCommand("#general", "/help")
        session.onInbound(
            RrcEnvelope(Rrc.T_ERROR, ByteArray(8), 1L, hub, body = "unrecognized command").encode(),
        )
        assertTrue(
            events.filterIsInstance<RrcEvent.RoomSystemMessage>().any {
                it.room == "#general" && it.text.contains("unrecognized command")
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
        session.join("#General")
        val env = RrcEnvelope.decode(link.sent.single())
        assertEquals(Rrc.T_JOIN, env.type)
        assertEquals("#general", env.room)
    }

    @Test fun joinedReplyConfirmsLowercasedRoom() = runTest {
        // User typed mixed case → we sent lowercase → the hub's JOINED
        // reply is lowercase → membership must confirm.
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        session.join("#General")
        session.onInbound(joinedFrame("#general"))
        assertTrue(session.rooms.contains("#general"))
    }

    @Test fun sendMessageLowercasesRoom() = runTest {
        val link = FakeLink()
        val session = newSession(link)
        session.start()
        session.onInbound(welcomeFrame())
        link.sent.clear()
        session.sendMessage("#General", "hi")
        assertEquals("#general", RrcEnvelope.decode(link.sent.single()).room)
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
}
