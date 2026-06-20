package io.github.thatsfguy.reticulum.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RRC wire codec — CBOR (RFC 8949).
 *
 * Every `expected` hex below is the verbatim output of Python
 * `cbor2.dumps` — the encoder the reference hub `rrcd` uses
 * (`rrcd/codec.py`). cbor2 is the EXTERNAL ORACLE: a self-round-trip
 * (encode→decode→compare) would pass even if our encoding drifted from
 * canonical CBOR, because both halves drift together. See memory
 * feedback_self_roundtrip_insufficient_wire.
 *
 * Test names are camelCase, not backticked — backticked names with
 * `,`/`(`/`)` break the Kotlin/Native iosTest compile (memory
 * feedback_kn_test_name_chars).
 */
class CborTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hexOf(b: ByteArray): String =
        b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---- encode + decode: scalars match cbor2 --------------------------

    @Test fun unsignedIntWidths() {
        val cases = listOf(
            0L to "00", 23L to "17", 24L to "1818", 255L to "18ff",
            256L to "190100", 65535L to "19ffff", 65536L to "1a00010000",
            4294967295L to "1affffffff", 4294967296L to "1b0000000100000000",
            1700000000000L to "1b0000018bcfe56800",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, hexOf(Cbor.encode(value)), "encode $value")
            assertEquals(value, Cbor.decode(hex(expected)), "decode $expected")
        }
    }

    @Test fun negativeInt() {
        assertEquals("20", hexOf(Cbor.encode(-1L)))
        assertEquals(-1L, Cbor.decode(hex("20")))
    }

    @Test fun simpleValues() {
        assertEquals("f4", hexOf(Cbor.encode(false)))
        assertEquals("f5", hexOf(Cbor.encode(true)))
        assertEquals("f6", hexOf(Cbor.encode(null)))
        assertEquals(false, Cbor.decode(hex("f4")))
        assertEquals(true, Cbor.decode(hex("f5")))
        assertEquals(null, Cbor.decode(hex("f6")))
    }

    @Test fun byteStrings() {
        assertEquals("40", hexOf(Cbor.encode(ByteArray(0))))
        assertEquals("43616263", hexOf(Cbor.encode("abc".encodeToByteArray())))
        // 24 bytes forces the 1-byte length prefix (0x58).
        assertEquals(
            "5818" + "78".repeat(24),
            hexOf(Cbor.encode(ByteArray(24) { 'x'.code.toByte() })),
        )
        val decoded = Cbor.decode(hex("43616263"))
        assertTrue(decoded is ByteArray)
        assertContentEquals("abc".encodeToByteArray(), decoded)
    }

    @Test fun textStrings() {
        assertEquals("60", hexOf(Cbor.encode("")))
        assertEquals("6568656c6c6f", hexOf(Cbor.encode("hello")))
        assertEquals("682367656e6572616c", hexOf(Cbor.encode("#general")))
        assertEquals("hello", Cbor.decode(hex("6568656c6c6f")))
    }

    @Test fun arrays() {
        assertEquals("80", hexOf(Cbor.encode(emptyList<Any?>())))
        assertEquals("83010203", hexOf(Cbor.encode(listOf(1L, 2L, 3L))))
        assertEquals(
            "82426161426262",
            hexOf(Cbor.encode(listOf("aa".encodeToByteArray(), "bb".encodeToByteArray()))),
        )
        assertEquals(listOf(1L, 2L, 3L), Cbor.decode(hex("83010203")))
    }

    @Test fun emptyMap() {
        assertEquals("a0", hexOf(Cbor.encode(emptyMap<Any?, Any?>())))
        assertEquals(emptyMap<Any?, Any?>(), Cbor.decode(hex("a0")))
    }

    // ---- encode: a full RRC envelope is byte-identical to cbor2 --------

    @Test fun helloEnvelopeMatchesCbor2() {
        // {0:1, 1:20, 2:bytes(0..7), 3:1700000000000, 4:b"peer",
        //  5:"#general", 6:"hello"} — built with ascending integer keys,
        // so iteration order already equals CBOR's canonical key order
        // and the bytes match cbor2 exactly.
        val env = LinkedHashMap<Any?, Any?>()
        env[0L] = 1L
        env[1L] = 20L
        env[2L] = ByteArray(8) { it.toByte() }
        env[3L] = 1700000000000L
        env[4L] = "peer".encodeToByteArray()
        env[5L] = "#general"
        env[6L] = "hello"
        assertEquals(
            "a70001011402480001020304050607031b0000018bcfe568000444" +
                "7065657205682367656e6572616c066568656c6c6f",
            hexOf(Cbor.encode(env)),
        )
    }

    @Test fun nestedCapsMapMatchesCbor2() {
        // HELLO body shape: {0:name, 1:ver, 2:caps-map}.
        val m = LinkedHashMap<Any?, Any?>()
        m[0L] = 1L
        m[1L] = "1.0"
        m[2L] = linkedMapOf<Any?, Any?>(0L to 1L)
        assertEquals("a300010163312e3002a10001", hexOf(Cbor.encode(m)))
    }

    @Test fun envelopeRoundTrip() {
        val bytes = hex(
            "a70001011402480001020304050607031b0000018bcfe568000444" +
                "7065657205682367656e6572616c066568656c6c6f",
        )
        val decoded = Cbor.decode(bytes)
        assertTrue(decoded is Map<*, *>)
        assertEquals(1L, decoded[0L])
        assertEquals(20L, decoded[1L])
        assertEquals(1700000000000L, decoded[3L])
        assertEquals("#general", decoded[5L])
        assertEquals("hello", decoded[6L])
        // Decoding then re-encoding must reproduce the wire bytes.
        assertContentEquals(bytes, Cbor.encode(decoded))
    }

    // ---- decode rejects what RRC never uses (fail loud, never guess) ---

    @Test fun decodeRejectsFloat() {
        // 0xfb = float64 — RRC carries no floats; reject, don't guess.
        assertFailsWith<IllegalArgumentException> {
            Cbor.decode(hex("fb3ff0000000000000"))
        }
    }

    @Test fun decodeRejectsIndefiniteLength() {
        // 0x9f = indefinite-length array — not valid canonical CBOR.
        assertFailsWith<IllegalArgumentException> {
            Cbor.decode(hex("9f00ff"))
        }
    }

    @Test fun decodeRejectsTag() {
        // 0xc0 = tag 0 (date/time string) — major type 6, unsupported.
        assertFailsWith<IllegalArgumentException> {
            Cbor.decode(hex("c0746474"))
        }
    }

    // ---- security: malformed input must fail loud, never crash/OOM ------

    @Test fun stringLengthBeyondInputIsRejected() {
        // A byte/text-string header declaring more bytes than the input
        // holds must be rejected (no over-read, no over-allocation). The
        // bound is overflow-safe Long math so a near-2GB declared length
        // can't wrap past the check.
        // 0x58 ff      = byte-string, 1-byte length 255, 0 bytes follow
        assertFailsWith<IllegalArgumentException> { Cbor.decode(hex("58ff")) }
        // 0x78 ff      = text-string, 1-byte length 255, 0 bytes follow
        assertFailsWith<IllegalArgumentException> { Cbor.decode(hex("78ff")) }
        // 0x5b 0000_0000_7fff_ffff = byte-string, 8-byte length ~2GB
        assertFailsWith<IllegalArgumentException> { Cbor.decode(hex("5b000000007fffffff")) }
    }

    @Test fun deeplyNestedArrayIsRejectedNotStackOverflow() {
        // 70 array-of-1 head bytes (0x81) — exceeds the depth cap, and
        // must throw a plain IllegalArgumentException, not StackOverflow.
        val blob = ByteArray(70) { 0x81.toByte() } + byteArrayOf(0xF6.toByte())
        assertFailsWith<IllegalArgumentException> { Cbor.decode(blob) }
    }

    @Test fun arrayLengthBeyondInputIsRejected() {
        // 0x99 0xFF 0xFF = array of 65535 elements in a 3-byte frame —
        // the n <= bytes-remaining check rejects the pre-allocation bomb.
        assertFailsWith<IllegalArgumentException> { Cbor.decode(hex("99ffff")) }
    }

    @Test fun mapLengthBeyondInputIsRejected() {
        // 0xB9 0xFF 0xFF = map of 65535 pairs in a 3-byte frame.
        assertFailsWith<IllegalArgumentException> { Cbor.decode(hex("b9ffff")) }
    }
}
