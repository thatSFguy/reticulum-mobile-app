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
        val msg = RrcMessages.message(hub, 1L, room = "#general", text = "hello", nick = "bob").encode()
        session.onInbound(msg)

        val m = events.filterIsInstance<RrcEvent.RoomMessage>().single()
        assertEquals("#general", m.room)
        assertEquals("hello", m.text)
        assertEquals("bob", m.nick)
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
}
