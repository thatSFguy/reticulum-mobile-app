package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-exact tests for the agnostic-LoRa tunnel envelope. These pin the
 * wire format to what the node firmware (`src/main.cpp`, `mesh_types.h`)
 * and the reference `AgnosticLoraInterface.py` actually emit — NOT to a
 * self-consistent round-trip (per the repo's playbook §5, a self-round-trip
 * can't catch a spec divergence because both ends drift together).
 *
 * Since firmware v2 a node id is a 16-byte blake2b hash written in
 * **canonical byte order — no endianness**: `nid_write`/`nid_read` are a
 * plain `memcpy`, and `nid_hex` writes `b[0]` as the first hex pair. So the
 * display hex maps straight to the wire bytes (no reversal — that was the
 * pre-v2 `struct.pack("<I")` 4-byte id, now gone).
 */
class AgnosticLoraTunnelTest {

    private companion object {
        // Real v2 id from the firmware note. Natural order: byte[0] = 0xb0.
        const val ID_HEX = "b0459c8072face9964867b39d8ed4e3e"
        val ID_BYTES = byteArrayOf(
            0xB0.toByte(), 0x45, 0x9C.toByte(), 0x80.toByte(),
            0x72, 0xFA.toByte(), 0xCE.toByte(), 0x99.toByte(),
            0x64, 0x86.toByte(), 0x7B, 0x39,
            0xD8.toByte(), 0xED.toByte(), 0x4E, 0x3E,
        )
    }

    @Test
    fun locatorFromHexIsNaturalOrder() {
        // No endianness: display b0459c80…4e3e ⇄ wire b0 45 9c 80 … 4e 3e.
        assertContentEquals(ID_BYTES, AgnosticLoraTunnel.locatorFromHex(ID_HEX))
    }

    @Test
    fun locatorFromHexAcceptsPrefixAndCase() {
        val a = AgnosticLoraTunnel.locatorFromHex("0x$ID_HEX")
        val b = AgnosticLoraTunnel.locatorFromHex(ID_HEX.uppercase())
        assertContentEquals(a, b)
        assertContentEquals(ID_BYTES, a)
    }

    @Test
    fun locatorFromHexRejectsWrongWidth() {
        assertNull(AgnosticLoraTunnel.locatorFromHex(ID_HEX.dropLast(2)))  // too short
        assertNull(AgnosticLoraTunnel.locatorFromHex(ID_HEX + "00"))       // too long
        assertNull(AgnosticLoraTunnel.locatorFromHex("Z".repeat(32)))      // 32 chars, not hex
        assertNull(AgnosticLoraTunnel.locatorFromHex("9828F51B"))          // old 4-byte width
        assertNull(AgnosticLoraTunnel.locatorFromHex(""))
    }

    @Test
    fun encodeProducesTypedLengthPrefixedFrame() {
        val loc = AgnosticLoraTunnel.locatorFromHex(ID_HEX)!!
        val payload = byteArrayOf(0x00, 0x11, 0x22)
        val frame = AgnosticLoraTunnel.encodeLocatorFrame(loc, payload)
        // [0x01][0x10][16 id bytes][00 11 22]
        assertContentEquals(
            byteArrayOf(0x01, 0x10) + ID_BYTES + byteArrayOf(0x00, 0x11, 0x22),
            frame,
        )
    }

    @Test
    fun decodeStripsEnvelopeAndReturnsPayload() {
        val frame = byteArrayOf(0x01, 0x10) + ID_BYTES +
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertContentEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            AgnosticLoraTunnel.decodeFrame(frame),
        )
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val loc = AgnosticLoraTunnel.locatorFromHex("deadbeefdeadbeefdeadbeefdeadbeef")!!
        val payload = ByteArray(200) { (it * 7).toByte() }
        val decoded = AgnosticLoraTunnel.decodeFrame(
            AgnosticLoraTunnel.encodeLocatorFrame(loc, payload),
        )
        assertContentEquals(payload, decoded)
    }

    @Test
    fun decodeIgnoresIdentityAndUnknownTypes() {
        // IDENTITY (0x02) is reserved and must be dropped, like the firmware.
        // Type is rejected before the length is consulted.
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf(0x02, 0x10) + ID_BYTES + byteArrayOf(9, 9)))
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf(0x7F, 0x01, 1, 2)))
    }

    @Test
    fun decodeRejectsTruncatedFrames() {
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf()))
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf(0x01)))             // no addr_len
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf(0x01, 0x10, 1, 2))) // addr_len=16 but 2 addr bytes
    }

    @Test
    fun decodeAllowsZeroLengthPayload() {
        // A bare envelope with no payload decodes to an empty packet, not null.
        val frame = byteArrayOf(0x01, 0x10) + ID_BYTES
        assertContentEquals(byteArrayOf(), AgnosticLoraTunnel.decodeFrame(frame))
    }

    @Test
    fun labelFromAdvertisedNameStripsCurrentAndLegacyPrefix() {
        // Current ALN- prefix: label is a friendly name or first-8-hex.
        assertEquals("kitchen", AgnosticLoraTunnel.labelFromAdvertisedName("ALN-kitchen"))
        assertEquals("b0459c80", AgnosticLoraTunnel.labelFromAdvertisedName("ALN-b0459c80"))
        assertEquals("kitchen", AgnosticLoraTunnel.labelFromAdvertisedName("aln-kitchen"))
        // Legacy AgnLoRa- prefix still parsed.
        assertEquals("b0459c80", AgnosticLoraTunnel.labelFromAdvertisedName("AgnLoRa-b0459c80"))
        assertNull(AgnosticLoraTunnel.labelFromAdvertisedName("RNode 1234"))
        assertNull(AgnosticLoraTunnel.labelFromAdvertisedName("ALN-"))
        assertNull(AgnosticLoraTunnel.labelFromAdvertisedName(null))
    }

    @Test
    fun isAdvertisedNameMatchesBothPrefixes() {
        assertTrue(AgnosticLoraTunnel.isAdvertisedName("ALN-kitchen"))
        assertTrue(AgnosticLoraTunnel.isAdvertisedName("aln-b0459c80"))
        assertTrue(AgnosticLoraTunnel.isAdvertisedName("AgnLoRa-b0459c80")) // legacy
        assertFalse(AgnosticLoraTunnel.isAdvertisedName("RNode 1234"))
        assertFalse(AgnosticLoraTunnel.isAdvertisedName(null))
    }

    @Test
    fun isValidNodeIdHexMatchesParser() {
        assertTrue(AgnosticLoraTunnel.isValidNodeIdHex(ID_HEX))
        assertFalse(AgnosticLoraTunnel.isValidNodeIdHex("9828F51B")) // old 4-byte width
        assertFalse(AgnosticLoraTunnel.isValidNodeIdHex("nope"))
    }

    @Test
    fun sourceFromFrameIsNaturalOrder() {
        // Wire bytes b0 45 9c 80 … 4e 3e → id B0459C80…4E3E (uppercase hex),
        // the inverse of locatorFromHex with no byte reversal.
        val frame = byteArrayOf(0x01, 0x10) + ID_BYTES + byteArrayOf(0x42)
        assertEquals(ID_HEX.uppercase(), AgnosticLoraTunnel.sourceFromFrame(frame))
        // Round-trip with the encoder.
        val loc = AgnosticLoraTunnel.locatorFromHex("deadbeefcafebabedeadbeefcafebabe")!!
        assertEquals(
            "DEADBEEFCAFEBABEDEADBEEFCAFEBABE",
            AgnosticLoraTunnel.sourceFromFrame(
                AgnosticLoraTunnel.encodeLocatorFrame(loc, byteArrayOf(1, 2, 3)),
            ),
        )
        // Rejected shapes mirror decodeFrame.
        assertNull(AgnosticLoraTunnel.sourceFromFrame(byteArrayOf(0x02, 0x10) + ID_BYTES))
        assertNull(AgnosticLoraTunnel.sourceFromFrame(byteArrayOf(0x01, 0x10, 1, 2)))
    }
}
