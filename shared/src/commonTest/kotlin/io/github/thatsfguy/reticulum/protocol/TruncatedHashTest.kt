package io.github.thatsfguy.reticulum.protocol

import io.github.thatsfguy.reticulum.transport.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Coverage for the [TruncatedHash] value type, which centralizes the
 * `.copyOfRange(0, 16).toHex()` step that appeared at three sites in
 * ReticulumEngine for proof-matching. Triplicated copy-paste was a
 * silent-skew risk if any one site drifted.
 */
class TruncatedHashTest {

    @Test fun `of takes the first 16 bytes of a 32-byte hash and lower-hex-encodes them`() {
        // Known SHA-256 digest of an empty input: e3b0c44298fc1c149afbf4c8...
        val full = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".hexToBytes()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb924", TruncatedHash.of(full).hex)
    }

    @Test fun `of preserves leading zero bytes`() {
        // First two bytes are 0x00 — a buggy encoder might drop them.
        val full = "0000aabbccddeeff00112233445566778899aabbccddeeff00112233445566ff".hexToBytes()
        assertEquals("0000aabbccddeeff0011223344556677", TruncatedHash.of(full).hex)
    }

    @Test fun `of ignores bytes after the 16th`() {
        // Bytes 16..31 are 0xaa repeated — must NOT appear in the truncation.
        val full = "112233445566778899aabbccddeeff00aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".hexToBytes()
        assertEquals("112233445566778899aabbccddeeff00", TruncatedHash.of(full).hex)
    }

    @Test fun `of accepts exactly 16 bytes (already-truncated input)`() {
        val sixteen = "0123456789abcdef0123456789abcdef".hexToBytes()
        assertEquals("0123456789abcdef0123456789abcdef", TruncatedHash.of(sixteen).hex)
    }

    @Test fun `of rejects a hash shorter than 16 bytes`() {
        // Defensive: a caller that passes the wrong-length hash should
        // get a clear error, not a silently-truncated empty string.
        assertFailsWith<IllegalArgumentException> {
            TruncatedHash.of(ByteArray(15))
        }
    }

    @Test fun `equals and hashCode use the hex string`() {
        val a = TruncatedHash("abcdef0123456789abcdef0123456789")
        val b = TruncatedHash("abcdef0123456789abcdef0123456789")
        val c = TruncatedHash("ffffffffffffffffffffffffffffffff")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
