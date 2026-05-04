package io.github.thatsfguy.reticulum.codec

import java.io.ByteArrayOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Decompression-bomb defense for bz2.
 *
 * Pre-v0.1.55 [bz2Decompress] read the input stream to EOF without any
 * upper bound on output size — a small (~tens of KB) bz2 payload can
 * decompress to gigabytes (a "bz2 bomb"). Combined with our Resource
 * size cap that's defense in depth: even if the advertisement claims a
 * small `dataSize` but the actual compressed payload expands beyond it,
 * the decompressor aborts before consuming all of phone memory.
 *
 * Tests construct legitimate bz2 streams + verify the cap fires when
 * the *output* exceeds the cap — bz2 input size is irrelevant.
 */
class Bz2Test {

    @Test fun `decompress round-trips small payloads when under cap`() {
        val payload = "the quick brown fox jumps over the lazy dog".repeat(50).encodeToByteArray()
        val compressed = bz2Compress(payload)
        val out = bz2Decompress(compressed, maxBytes = 100_000)
        assertContentEquals(payload, out, "small payload under cap must round-trip")
    }

    @Test fun `decompress aborts when output exceeds maxBytes`() {
        // 200 KB of decompressed data (compresses well, ~few KB on the wire).
        val payload = ByteArray(200_000) { (it % 31).toByte() }
        val compressed = bz2Compress(payload)
        assertTrue(compressed.size < payload.size,
            "test setup: bz2 must actually compress repetitive data")

        // Cap to 50 KB — must throw before fully decompressing.
        assertFailsWith<IllegalStateException>(
            "bz2Decompress must abort when running output total exceeds maxBytes",
        ) {
            bz2Decompress(compressed, maxBytes = 50_000)
        }
    }

    @Test fun `decompress allows exactly maxBytes`() {
        // Exact-size boundary: decompressing exactly maxBytes worth of
        // output must succeed (cap is exclusive on overflow, not on equal).
        val payload = ByteArray(8_000) { (it % 251).toByte() }
        val compressed = bz2Compress(payload)
        val out = bz2Decompress(compressed, maxBytes = 8_000)
        assertEquals(8_000, out.size)
    }

    private fun bz2Compress(input: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        BZip2CompressorOutputStream(baos).use { it.write(input) }
        return baos.toByteArray()
    }
}
