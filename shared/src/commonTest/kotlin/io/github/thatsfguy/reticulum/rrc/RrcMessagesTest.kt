package io.github.thatsfguy.reticulum.rrc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RRC typed message builders + parser.
 *
 * Hub-side fixtures (WELCOME/JOINED/NOTICE/ERROR) are verbatim
 * `cbor2.dumps` of the dict `rrcd` would build — the external oracle.
 */
class RrcMessagesTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hexOf(b: ByteArray): String =
        b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val hub = ByteArray(16) { 0xAA.toByte() }
    private val src = ByteArray(16) { it.toByte() }

    // ---- builders ------------------------------------------------------

    @Test fun helloBuilderShapesBodyAndType() {
        val env = RrcMessages.hello(
            src = src, timestampMs = 1L, nick = "alice", resourceCapable = true,
            msgId = ByteArray(8),
        )
        assertEquals(Rrc.T_HELLO, env.type)
        assertEquals("alice", env.nick)
        val body = env.body
        assertTrue(body is Map<*, *>)
        val caps = body[Rrc.B_HELLO_CAPS]
        assertTrue(caps is Map<*, *>)
        assertTrue(caps.containsKey(Rrc.CAP_RESOURCE_ENVELOPE))
    }

    @Test fun messageBuilderMatchesEnvelopeFixture() {
        // Same inputs as RrcEnvelopeTest.msgHex.
        val env = RrcMessages.message(
            src = ByteArray(16) { it.toByte() },
            timestampMs = 1700000000000L,
            room = "#general",
            text = "hi",
            nick = "bob",
            msgId = ByteArray(8) { 1 },
        )
        assertEquals(
            "a80001011402480101010101010101031b0000018bcfe56800045000010203" +
                "0405060708090a0b0c0d0e0f05682367656e6572616c066268690763626f62",
            hexOf(env.encode()),
        )
    }

    @Test fun joinBuilderCarriesRoomAndKey() {
        val env = RrcMessages.join(src, 1L, room = "#secret", key = "swordfish", msgId = ByteArray(8))
        assertEquals(Rrc.T_JOIN, env.type)
        assertEquals("#secret", env.room)
        assertEquals("swordfish", env.body)
    }

    @Test fun pingBuilderRoundTripsThroughParse() {
        val env = RrcMessages.ping(src, 1L, payload = byteArrayOf(1, 2, 3), msgId = ByteArray(8))
        val parsed = RrcMessages.parse(env.encode())
        assertTrue(parsed is RrcInbound.Ping)
    }

    @Test fun actionBuilderShapesTypeAndBody() {
        val env = RrcMessages.action(
            src, 1L, room = "#general", text = "/me waves", nick = "bob", msgId = ByteArray(8),
        )
        assertEquals(Rrc.T_ACTION, env.type)
        assertEquals("#general", env.room)
        assertEquals("/me waves", env.body, "ACTION body carries the /me text verbatim")
        assertEquals("bob", env.nick)
    }

    @Test fun parseActionProjectsToMessageLikeMsg() {
        // §10 parity — inbound type-22 ACTION must render like a MSG.
        val env = RrcMessages.action(
            src, 1L, room = "#general", text = "/me waves", nick = "bob", msgId = ByteArray(8),
        )
        val parsed = RrcMessages.parse(env.encode())
        assertTrue(parsed is RrcInbound.Message, "type-22 must project to RrcInbound.Message")
        assertEquals("#general", parsed.room)
        assertEquals("/me waves", parsed.text)
        assertEquals("bob", parsed.nick)
    }

    // ---- parser: hub → client -----------------------------------------

    @Test fun parseWelcomeExtractsHubAndLimits() {
        // {0:1,1:2,2:..,3:..,4:hub,6:{0:"testhub",1:"1.2.3",
        //  3:{0:32,1:64,2:4096,3:16,4:30}}}
        val welcome = hex(
            "a60001010202480909090909090909031b0000018bcfe568000450" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa06a30067746573746875620165" +
                "312e322e3303a500182001184002191000031004181e",
        )
        val parsed = RrcMessages.parse(welcome)
        assertTrue(parsed is RrcInbound.Welcome)
        assertEquals("testhub", parsed.hubName)
        assertEquals("1.2.3", parsed.hubVersion)
        assertEquals(32, parsed.limits.maxNickBytes)
        assertEquals(4096, parsed.limits.maxMsgBodyBytes)
        assertEquals(30, parsed.limits.rateLimitMsgsPerMinute)
    }

    @Test fun parseJoinedExtractsRoomAndMembers() {
        // {..,1:11,..,5:"#general",6:[bytes(0..15), 0x11*16]}
        val joined = hex(
            "a70001010b02480a0a0a0a0a0a0a0a031b0000018bcfe568000450" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa05682367656e6572616c068250" +
                "000102030405060708090a0b0c0d0e0f5011111111111111111111111111111111",
        )
        val parsed = RrcMessages.parse(joined)
        assertTrue(parsed is RrcInbound.Joined)
        assertEquals("#general", parsed.room)
        assertEquals(2, parsed.members.size)
        assertContentEquals(ByteArray(16) { it.toByte() }, parsed.members[0])
        assertContentEquals(ByteArray(16) { 0x11 }, parsed.members[1])
    }

    @Test fun parseNoticeExtractsText() {
        val notice = hex(
            "a60001011502480b0b0b0b0b0b0b0b031b0000018bcfe568000450" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa067277656c636f6d6520746f20" +
                "74686520687562",
        )
        val parsed = RrcMessages.parse(notice)
        assertTrue(parsed is RrcInbound.Notice)
        assertEquals("welcome to the hub", parsed.text)
        assertEquals(null, parsed.room)
    }

    @Test fun parseErrorExtractsText() {
        val err = hex(
            "a6000101182802480c0c0c0c0c0c0c0c031b0000018bcfe568000450" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa066c72617465206c696d69746564",
        )
        val parsed = RrcMessages.parse(err)
        assertTrue(parsed is RrcInbound.Error)
        assertEquals("rate limited", parsed.text)
    }
}
