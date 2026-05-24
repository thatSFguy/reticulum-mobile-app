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

    // ---- Byte-perfect round-trip fuzz tests ----
    //
    // Observed 2026-05-24: image sends over RNode/BtClassic SPP get most
    // chunks across cleanly but ONE specific chunk repeatedly fails the
    // receiver's `SHA-256(chunk || random_hash)[:4]` lookup against the
    // advertised hashmap. Same chunk fails on every retransmit, so the
    // corruption (if it's KISS-side) must be deterministic on input bytes.
    // These tests exercise byte patterns that historically trip KISS
    // implementations: dense FEND/FESC runs, escape-at-buffer-boundary,
    // every possible byte at every position, etc.

    @Test fun roundTripAllByteValues() {
        // Single payload containing every byte value 0..255 exactly once.
        // If any byte gets dropped, mis-escaped, or mis-unescaped, this
        // test catches it because the output won't equal the input.
        val payloads = mutableListOf<ByteArray>()
        val parser = KissParser { _, payload -> payloads += payload }
        val original = ByteArray(256) { it.toByte() }
        parser.feed(buildKissFrame(CMD_DATA, original))
        assertEquals(1, payloads.size)
        assertContentEquals(original, payloads[0])
    }

    @Test fun roundTripDensePathologicalBytes() {
        // All-FEND, all-FESC, alternating FEND/FESC. Each pattern is the
        // kind of degenerate input a binary protocol can produce when
        // ciphertext happens to align that way. A bug in the unescape
        // state machine usually surfaces here first.
        val payloads = mutableListOf<ByteArray>()
        val parser = KissParser { _, payload -> payloads += payload }
        val patterns = listOf(
            ByteArray(64) { 0xC0.toByte() },           // all FEND
            ByteArray(64) { 0xDB.toByte() },           // all FESC
            ByteArray(64) { if (it % 2 == 0) 0xC0.toByte() else 0xDB.toByte() },
            ByteArray(64) { if (it % 2 == 0) 0xDB.toByte() else 0xC0.toByte() },
            ByteArray(64) { if (it % 2 == 0) 0xDB.toByte() else 0xDC.toByte() }, // FESC,TFEND repeating
            ByteArray(64) { if (it % 2 == 0) 0xDB.toByte() else 0xDD.toByte() }, // FESC,TFESC repeating
        )
        for (p in patterns) {
            payloads.clear()
            parser.reset()
            parser.feed(buildKissFrame(CMD_DATA, p))
            assertEquals(1, payloads.size, "missing emit for pattern ${p.toHex().take(20)}…")
            assertContentEquals(p, payloads[0],
                "round-trip mismatch for pattern ${p.toHex().take(20)}…")
        }
    }

    @Test fun roundTripBoundaryByEveryByte() {
        // Same payload, fed to the parser with a split between every pair
        // of consecutive bytes. Catches any "escape state leaks across
        // feed() calls" or "in-frame state leaks" bug — the kind of bug
        // BLE-NUS or SPP can hide when bytes arrive in clean bursts but
        // surface on a different transport that chunks differently.
        val original = byteArrayOf(
            0x00, 0x01, 0xC0.toByte(), 0xDB.toByte(), 0xDC.toByte(), 0xDD.toByte(),
            0xFF.toByte(), 0xC0.toByte(), 0x42, 0xDB.toByte(), 0xC0.toByte(),
            0xDB.toByte(), 0xDB.toByte(), 0xDC.toByte(), 0xDC.toByte(),
        )
        val frame = buildKissFrame(CMD_DATA, original)
        for (split in 1 until frame.size) {
            val payloads = mutableListOf<ByteArray>()
            val parser = KissParser { _, payload -> payloads += payload }
            parser.feed(frame.copyOfRange(0, split))
            parser.feed(frame.copyOfRange(split, frame.size))
            assertEquals(1, payloads.size,
                "no emit when split at $split / ${frame.size}")
            assertContentEquals(original, payloads[0],
                "round-trip mismatch when split at $split / ${frame.size}")
        }
    }

    @Test fun roundTripRandomLargeChunkSizesMatchingResource() {
        // Resource chunks ride DEFAULT_SDU = 464-byte payloads (§10.6).
        // Validate byte-perfect round-trip at that exact size with a
        // pseudo-random byte distribution. Mirrors what comes off the wire
        // when an LXMF image attachment is delivered — outer-Token-
        // encrypted ciphertext, statistically uniform bytes, ~2% FEND and
        // ~2% FESC by chance.
        val sizes = listOf(1, 7, 63, 64, 65, 127, 128, 463, 464, 465, 1023, 1024)
        var prng = 0xC0FFEEL
        fun nextByte(): Byte {
            prng = (prng * 6364136223846793005L + 1442695040888963407L)
            return ((prng ushr 33) and 0xFF).toByte()
        }
        for (size in sizes) {
            val original = ByteArray(size) { nextByte() }
            val payloads = mutableListOf<ByteArray>()
            val parser = KissParser { _, payload -> payloads += payload }
            parser.feed(buildKissFrame(CMD_DATA, original))
            assertEquals(1, payloads.size, "no emit at size $size")
            assertContentEquals(original, payloads[0],
                "round-trip mismatch at size $size")
        }
    }

    @Test fun escapeStatePersistsAcrossFeedCalls() {
        // Feed FESC alone, then TFEND alone. The escape state must
        // persist across feed() calls so the 0xDB 0xDC pair decodes to
        // a single 0xC0 byte, not "two unknown bytes."
        val payloads = mutableListOf<ByteArray>()
        val parser = KissParser { _, payload -> payloads += payload }
        // FEND, then cmd byte, then half-escape, then completion + FEND
        parser.feed(byteArrayOf(0xC0.toByte()))          // open frame
        parser.feed(byteArrayOf(0x00))                    // cmd = CMD_DATA
        parser.feed(byteArrayOf(0xDB.toByte()))          // FESC (escape start)
        parser.feed(byteArrayOf(0xDC.toByte()))          // TFEND (escape end → 0xC0)
        parser.feed(byteArrayOf(0xC0.toByte()))          // close frame
        assertEquals(1, payloads.size)
        assertContentEquals(byteArrayOf(0xC0.toByte()), payloads[0],
            "FESC TFEND split across feed() must decode to a single 0xC0")
    }
}
