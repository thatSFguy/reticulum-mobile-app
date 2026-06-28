package io.github.thatsfguy.reticulum.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the Ogg-Opus container framing (RFC 7845) used for iOS voice clips.
 * The codec (libopus) only runs on iOS, but the container mux/demux is
 * pure Kotlin and is the interop-critical part — these run in normal CI.
 *
 * The CRC test cross-checks the table-driven [OggOpus] CRC against an
 * independent bitwise implementation; a wrong Ogg CRC is the single most
 * common reason a player silently rejects an otherwise-valid stream.
 */
class OggOpusTest {

    // Stand-in "Opus" packets — the container layer is codec-agnostic, so
    // arbitrary bytes exercise the framing exactly like real packets would.
    private fun fakePacket(n: Int, fill: Int) = ByteArray(n) { (fill + it).toByte() }

    @Test
    fun roundTripPreservesPacketsAndHeader() {
        val packets = listOf(fakePacket(40, 1), fakePacket(33, 2), fakePacket(57, 3))
        val ogg = OggOpus.mux(packets, channels = 1, preSkip = 312, frameSamples = 960, serial = 0x1234)
        val stream = OggOpus.demux(ogg)

        assertEquals(1, stream.channels)
        assertEquals(312, stream.preSkip)
        assertEquals(OggOpus.OPUS_DECODE_RATE, stream.inputSampleRate)
        assertEquals(packets.size, stream.packets.size)
        for (i in packets.indices) assertContentEquals(packets[i], stream.packets[i], "packet $i differs")
    }

    @Test
    fun muxProducesRfc7845Structure() {
        val ogg = OggOpus.mux(listOf(fakePacket(20, 9)), channels = 2, preSkip = 0, frameSamples = 960, serial = 7)
        // Capture pattern at offset 0.
        assertEquals("OggS", ogg.copyOfRange(0, 4).decodeToString())
        // BOS flag set on the first page (header type byte at offset 5).
        assertTrue(ogg[5].toInt() and 0x02 != 0, "first page must be BOS")
        // OpusHead magic begins at the first page body (offset 28 for a
        // single-segment page: 27 header + 1 lacing byte).
        assertEquals("OpusHead", ogg.copyOfRange(28, 36).decodeToString())
    }

    @Test
    fun lastAudioPageIsEos() {
        val ogg = OggOpus.mux(listOf(fakePacket(10, 1), fakePacket(10, 2)), 1, 0, 960, 1)
        // Walk pages, confirm exactly one EOS and it's the final page.
        var pos = 0
        var eosCount = 0
        var lastWasEos = false
        while (pos + 27 <= ogg.size) {
            assertEquals("OggS", ogg.copyOfRange(pos, pos + 4).decodeToString())
            val flags = ogg[pos + 5].toInt()
            val segCount = ogg[pos + 26].toInt() and 0xFF
            var body = 0
            for (i in 0 until segCount) body += ogg[pos + 27 + i].toInt() and 0xFF
            lastWasEos = flags and 0x04 != 0
            if (lastWasEos) eosCount++
            pos = pos + 27 + segCount + body
        }
        assertEquals(1, eosCount, "exactly one EOS page expected")
        assertTrue(lastWasEos, "EOS must be the final page")
    }

    @Test
    fun crcMatchesIndependentBitwiseImplementation() {
        val ogg = OggOpus.mux(listOf(fakePacket(50, 4)), 1, 0, 960, 0xABCD)
        // Parse the first page: header = 27 + segCount, then body.
        val segCount = ogg[26].toInt() and 0xFF
        val headerLen = 27 + segCount
        var bodyLen = 0
        for (i in 0 until segCount) bodyLen += ogg[27 + i].toInt() and 0xFF
        val pageEnd = headerLen + bodyLen

        // Stored CRC (little-endian) at offset 22.
        val stored = (ogg[22].toInt() and 0xFF) or ((ogg[23].toInt() and 0xFF) shl 8) or
            ((ogg[24].toInt() and 0xFF) shl 16) or ((ogg[25].toInt() and 0xFF) shl 24)

        // Recompute over the page with the CRC field zeroed, using a
        // from-scratch bitwise CRC (poly 0x04C11DB7, no reflection, init 0).
        val page = ogg.copyOfRange(0, pageEnd)
        for (i in 22..25) page[i] = 0
        var crc = 0
        for (byte in page) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
            }
        }
        assertEquals(crc, stored, "table CRC must match bitwise CRC")
    }

    @Test
    fun largePacketSpansMultipleLaces() {
        // > 255 bytes forces the lacing table to split it (255 + remainder);
        // demux must rejoin it byte-for-byte.
        val big = fakePacket(600, 5)
        val ogg = OggOpus.mux(listOf(big), 1, 0, 960, 1)
        val stream = OggOpus.demux(ogg)
        assertEquals(1, stream.packets.size)
        assertContentEquals(big, stream.packets[0])
    }

    @Test
    fun demuxRejectsTruncatedBody() {
        val ogg = OggOpus.mux(listOf(fakePacket(40, 1)), 1, 0, 960, 1)
        assertFailsWith<IllegalArgumentException> { OggOpus.demux(ogg.copyOfRange(0, ogg.size - 5)) }
    }

    @Test
    fun demuxRejectsBadCapturePattern() {
        val ogg = OggOpus.mux(listOf(fakePacket(40, 1)), 1, 0, 960, 1)
        ogg[1] = 'X'.code.toByte()
        assertFailsWith<IllegalArgumentException> { OggOpus.demux(ogg) }
    }

    @Test
    fun demuxRejectsNonOpusFirstPacket() {
        // Valid Ogg framing but the first packet isn't an OpusHead.
        val notOpus = "NotOpus!".encodeToByteArray() + ByteArray(11)
        // Hand-mux a single page carrying notOpus as a raw packet by reusing
        // mux with channels but swapping: simplest is to mux real packets
        // then corrupt the OpusHead magic in place.
        val ogg = OggOpus.mux(listOf(fakePacket(40, 1)), 1, 0, 960, 1)
        // OpusHead magic is at offset 28 on page 0.
        notOpus.copyInto(ogg, 28, 0, 8)
        assertFailsWith<IllegalArgumentException> { OggOpus.demux(ogg) }
    }
}
