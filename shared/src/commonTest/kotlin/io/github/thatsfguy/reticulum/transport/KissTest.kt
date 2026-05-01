package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KissTest {

    @Test fun roundTripEmptyData() {
        val frame = buildKissFrame(CMD_DATA)
        // FEND + cmd + FEND
        assertContentEquals(byteArrayOf(0xC0.toByte(), 0x00, 0xC0.toByte()), frame)
    }

    @Test fun escapesFendAndFesc() {
        val data = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x42)
        val frame = buildKissFrame(CMD_DATA, data)
        // FEND 00 [DB DC] [DB DD] 42 FEND
        val expected = byteArrayOf(
            0xC0.toByte(), 0x00,
            0xDB.toByte(), 0xDC.toByte(),
            0xDB.toByte(), 0xDD.toByte(),
            0x42,
            0xC0.toByte(),
        )
        assertContentEquals(expected, frame)
    }

    @Test fun parserRoundTrip() {
        val payloads = mutableListOf<Pair<Int, ByteArray>>()
        val parser = KissParser { cmd, payload -> payloads += cmd to payload }
        val original = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x01, 0x02, 0xC0.toByte())
        parser.feed(buildKissFrame(CMD_DATA, original))
        assertEquals(1, payloads.size)
        assertEquals(CMD_DATA, payloads[0].first)
        assertContentEquals(original, payloads[0].second)
    }

    @Test fun parserHandlesSplitChunks() {
        val payloads = mutableListOf<Pair<Int, ByteArray>>()
        val parser = KissParser { cmd, payload -> payloads += cmd to payload }
        val frame = buildKissFrame(CMD_DATA, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        // Split at every byte boundary
        for (b in frame) parser.feed(byteArrayOf(b))
        assertEquals(1, payloads.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), payloads[0].second)
    }

    @Test fun parserDropsEmptyFrames() {
        val payloads = mutableListOf<Pair<Int, ByteArray>>()
        val parser = KissParser { cmd, payload -> payloads += cmd to payload }
        // FEND FEND followed by a real frame — empty frame should be ignored
        parser.feed(byteArrayOf(0xC0.toByte(), 0xC0.toByte()))
        parser.feed(buildKissFrame(CMD_DATA, byteArrayOf(0xAA.toByte())))
        assertEquals(1, payloads.size)
    }

    @Test fun rssiAndSnrDecoding() {
        // RSSI byte 100 means -57 dBm
        assertEquals(-57, decodeRssi(100.toByte()))
        // SNR 0x10 = 16 / 4 = 4.0
        assertEquals(4.0, decodeSnr(0x10.toByte()))
        // SNR negative
        assertEquals(-2.0, decodeSnr((-8).toByte()))
    }

    @Test fun hexHelpers() {
        val bytes = byteArrayOf(0x12, 0x34, 0xAB.toByte())
        assertEquals("1234ab", bytes.toHex())
        assertContentEquals(bytes, "1234AB".hexToBytes())
    }
}
