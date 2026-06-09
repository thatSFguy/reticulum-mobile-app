package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-exact tests for the agnostic-LoRa tunnel envelope. These pin the
 * wire format to what the node firmware (`src/main.cpp`) and the reference
 * `AgnosticLoraInterface.py` actually emit — NOT to a self-consistent
 * round-trip (per the repo's playbook §5, a self-round-trip can't catch a
 * spec divergence because both ends drift together). The locator bytes
 * below are the exact output of the reference's `struct.pack("<I", peer)`.
 */
class AgnosticLoraTunnelTest {

    @Test
    fun locatorFromHexIsLittleEndian() {
        // struct.pack("<I", 0x9828F51B) == b"\x1b\xf5\x28\x98"
        val loc = AgnosticLoraTunnel.locatorFromHex("9828F51B")
        assertContentEquals(
            byteArrayOf(0x1B, 0xF5.toByte(), 0x28, 0x98.toByte()),
            loc,
        )
    }

    @Test
    fun locatorFromHexAcceptsPrefixAndCase() {
        val a = AgnosticLoraTunnel.locatorFromHex("0x9828f51b")
        val b = AgnosticLoraTunnel.locatorFromHex("9828F51B")
        assertContentEquals(a, b)
    }

    @Test
    fun locatorFromHexRejectsWrongWidth() {
        assertNull(AgnosticLoraTunnel.locatorFromHex("9828F5"))      // too short
        assertNull(AgnosticLoraTunnel.locatorFromHex("9828F51B00"))  // too long
        assertNull(AgnosticLoraTunnel.locatorFromHex("ZZZZZZZZ"))    // not hex
        assertNull(AgnosticLoraTunnel.locatorFromHex(""))
    }

    @Test
    fun encodeProducesTypedLengthPrefixedFrame() {
        val loc = AgnosticLoraTunnel.locatorFromHex("9828F51B")!!
        val payload = byteArrayOf(0x00, 0x11, 0x22)
        val frame = AgnosticLoraTunnel.encodeLocatorFrame(loc, payload)
        // [0x01][0x04][1b f5 28 98][00 11 22]
        assertContentEquals(
            byteArrayOf(
                0x01, 0x04,
                0x1B, 0xF5.toByte(), 0x28, 0x98.toByte(),
                0x00, 0x11, 0x22,
            ),
            frame,
        )
    }

    @Test
    fun decodeStripsEnvelopeAndReturnsPayload() {
        val frame = byteArrayOf(
            0x01, 0x04,
            0x1B, 0xF5.toByte(), 0x28, 0x98.toByte(),
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )
        assertContentEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            AgnosticLoraTunnel.decodeFrame(frame),
        )
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val loc = AgnosticLoraTunnel.locatorFromHex("DEADBEEF")!!
        val payload = ByteArray(200) { (it * 7).toByte() }
        val decoded = AgnosticLoraTunnel.decodeFrame(
            AgnosticLoraTunnel.encodeLocatorFrame(loc, payload),
        )
        assertContentEquals(payload, decoded)
    }

    @Test
    fun decodeIgnoresIdentityAndUnknownTypes() {
        // IDENTITY (0x02) is reserved and must be dropped, like the firmware.
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf(0x02, 0x04, 1, 2, 3, 4, 9, 9)))
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf(0x7F, 0x01, 1, 2)))
    }

    @Test
    fun decodeRejectsTruncatedFrames() {
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf()))
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf(0x01)))            // no addr_len
        assertNull(AgnosticLoraTunnel.decodeFrame(byteArrayOf(0x01, 0x04, 1, 2))) // addr_len=4 but only 2 addr bytes
    }

    @Test
    fun decodeAllowsZeroLengthPayload() {
        // A bare envelope with no payload decodes to an empty packet, not null.
        val frame = byteArrayOf(0x01, 0x04, 0x1B, 0xF5.toByte(), 0x28, 0x98.toByte())
        assertContentEquals(byteArrayOf(), AgnosticLoraTunnel.decodeFrame(frame))
    }

    @Test
    fun nodeIdFromAdvertisedNameParsesPrefix() {
        assertEquals("9828F51B", AgnosticLoraTunnel.nodeIdFromAdvertisedName("AgnLoRa-9828F51B"))
        assertEquals("9828F51B", AgnosticLoraTunnel.nodeIdFromAdvertisedName("agnlora-9828F51B"))
        assertNull(AgnosticLoraTunnel.nodeIdFromAdvertisedName("RNode 1234"))
        assertNull(AgnosticLoraTunnel.nodeIdFromAdvertisedName("AgnLoRa-"))
        assertNull(AgnosticLoraTunnel.nodeIdFromAdvertisedName(null))
    }

    @Test
    fun isValidNodeIdHexMatchesParser() {
        assertTrue(AgnosticLoraTunnel.isValidNodeIdHex("9828F51B"))
        assertFalse(AgnosticLoraTunnel.isValidNodeIdHex("nope"))
    }
}
