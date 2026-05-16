package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.codec.Cbor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RRC envelope encode/decode/validate.
 *
 * The `expected` hex blobs are verbatim Python `cbor2.dumps` output of
 * the dict `rrcd/envelope.py::make_envelope` would build for the same
 * inputs — the external oracle (memory feedback_self_roundtrip_insufficient_wire).
 * camelCase test names keep the iosTest K/N compile happy.
 */
class RrcEnvelopeTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hexOf(b: ByteArray): String =
        b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // {0:1, 1:20, 2:b"\x01"*8, 3:1700000000000, 4:bytes(0..15),
    //  5:"#general", 6:"hi", 7:"bob"}
    private val msgHex =
        "a80001011402480101010101010101031b0000018bcfe56800045000010203" +
            "0405060708090a0b0c0d0e0f05682367656e6572616c066268690763626f62"

    // {0:1, 1:1, 2:b"\x02"*8, 3:1700000000000, 4:bytes(0..15),
    //  6:{2:{0:1}}, 7:"alice"}  — HELLO, no room, caps-map body
    private val helloHex =
        "a70001010102480202020202020202031b0000018bcfe5680004500001020304" +
            "05060708090a0b0c0d0e0f06a102a100010765616c696365"

    // {0:1, 1:30, 2:b"\x03"*8, 3:1700000000000, 4:bytes(0..15), 6:b"\xaa\xbb"}
    private val pingHex =
        "a6000101181e02480303030303030303031b0000018bcfe568000450000102" +
            "0304050607 08090a0b0c0d0e0f0642aabb".replace(" ", "")

    @Test fun encodeMsgEnvelopeMatchesCbor2() {
        val env = RrcEnvelope(
            type = Rrc.T_MSG,
            msgId = ByteArray(8) { 1 },
            timestampMs = 1700000000000L,
            src = ByteArray(16) { it.toByte() },
            room = "#general",
            body = "hi",
            nick = "bob",
        )
        assertEquals(msgHex, hexOf(env.encode()))
    }

    @Test fun decodeMsgEnvelope() {
        val env = RrcEnvelope.decode(hex(msgHex))
        assertEquals(Rrc.T_MSG, env.type)
        assertEquals(1700000000000L, env.timestampMs)
        assertEquals("#general", env.room)
        assertEquals("hi", env.body)
        assertEquals("bob", env.nick)
        assertEquals(Rrc.VERSION, env.version)
        assertContentEquals(ByteArray(8) { 1 }, env.msgId)
        assertContentEquals(ByteArray(16) { it.toByte() }, env.src)
    }

    @Test fun encodeHelloEnvelopeMatchesCbor2() {
        // HELLO body is the caps map {B_HELLO_CAPS: {CAP_RESOURCE_ENVELOPE: 1}}.
        val body = linkedMapOf<Any?, Any?>(
            Rrc.B_HELLO_CAPS to linkedMapOf<Any?, Any?>(Rrc.CAP_RESOURCE_ENVELOPE to 1),
        )
        val env = RrcEnvelope(
            type = Rrc.T_HELLO,
            msgId = ByteArray(8) { 2 },
            timestampMs = 1700000000000L,
            src = ByteArray(16) { it.toByte() },
            body = body,
            nick = "alice",
        )
        assertEquals(helloHex, hexOf(env.encode()))
    }

    @Test fun decodeHelloEnvelopeBodyIsMap() {
        val env = RrcEnvelope.decode(hex(helloHex))
        assertEquals(Rrc.T_HELLO, env.type)
        assertEquals(null, env.room)
        assertEquals("alice", env.nick)
        val body = env.body
        assertTrue(body is Map<*, *>)
        // Caps map decodes with Long keys; CAP_RESOURCE_ENVELOPE present.
        val caps = body[Rrc.B_HELLO_CAPS.toLong()]
        assertTrue(caps is Map<*, *>)
        assertEquals(1L, caps[Rrc.CAP_RESOURCE_ENVELOPE.toLong()])
    }

    @Test fun pingEnvelopeBodyIsBytes() {
        val env = RrcEnvelope.decode(hex(pingHex))
        assertEquals(Rrc.T_PING, env.type)
        val body = env.body
        assertTrue(body is ByteArray)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), body)
    }

    @Test fun decodeThenEncodeRebuildsBytes() {
        for (h in listOf(msgHex, helloHex, pingHex)) {
            val bytes = hex(h)
            assertContentEquals(bytes, RrcEnvelope.decode(bytes).encode())
        }
    }

    // ---- validation rejects malformed envelopes ------------------------

    @Test fun validateRejectsMissingType() {
        val bad = Cbor.encode(
            linkedMapOf<Any?, Any?>(
                Rrc.K_V to 1, Rrc.K_ID to ByteArray(8),
                Rrc.K_TS to 0L, Rrc.K_SRC to ByteArray(16),
            ),
        )
        assertFailsWith<IllegalArgumentException> { RrcEnvelope.decode(bad) }
    }

    @Test fun validateRejectsWrongVersion() {
        val bad = Cbor.encode(
            linkedMapOf<Any?, Any?>(
                Rrc.K_V to 2, Rrc.K_T to Rrc.T_MSG, Rrc.K_ID to ByteArray(8),
                Rrc.K_TS to 0L, Rrc.K_SRC to ByteArray(16),
            ),
        )
        assertFailsWith<IllegalArgumentException> { RrcEnvelope.decode(bad) }
    }

    @Test fun validateRejectsNonIntegerKey() {
        val bad = Cbor.encode(linkedMapOf<Any?, Any?>("v" to 1))
        assertFailsWith<IllegalArgumentException> { RrcEnvelope.decode(bad) }
    }

    @Test fun validateRejectsNonBytesSource() {
        val bad = Cbor.encode(
            linkedMapOf<Any?, Any?>(
                Rrc.K_V to 1, Rrc.K_T to Rrc.T_MSG, Rrc.K_ID to ByteArray(8),
                Rrc.K_TS to 0L, Rrc.K_SRC to "not-bytes",
            ),
        )
        assertFailsWith<IllegalArgumentException> { RrcEnvelope.decode(bad) }
    }
}
