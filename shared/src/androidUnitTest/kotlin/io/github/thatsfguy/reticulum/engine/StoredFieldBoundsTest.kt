package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Audit 2026-07-28 L1: bounds on attacker-controlled text/rawPacket before
 * they reach the `messages` table (an unbounded multi-MB value overflows
 * Android's 2 MB CursorWindow and the table has no eviction).
 */
class StoredFieldBoundsTest {

    @Test fun `normal-length text passes through unchanged`() {
        val s = "hello world"
        assertSame(s, boundStoredText(s), "small text must not be copied/truncated")
    }

    @Test fun `oversized text is truncated to the cap`() {
        val s = "x".repeat(MAX_STORED_TEXT_CHARS + 5_000)
        assertEquals(MAX_STORED_TEXT_CHARS, boundStoredText(s).length)
    }

    @Test fun `text exactly at the cap is kept`() {
        val s = "y".repeat(MAX_STORED_TEXT_CHARS)
        assertEquals(MAX_STORED_TEXT_CHARS, boundStoredText(s).length)
    }

    @Test fun `null and small rawPacket pass through`() {
        assertNull(boundStoredRawPacket(null))
        val b = ByteArray(1024)
        assertSame(b, boundStoredRawPacket(b))
    }

    @Test fun `oversized rawPacket is dropped to null`() {
        val big = ByteArray(MAX_STORED_RAWPACKET_BYTES + 1)
        assertNull(
            boundStoredRawPacket(big),
            "an oversized re-verification blob is dropped, not stored — the row stays unverified",
        )
    }

    @Test fun `rawPacket exactly at the cap is kept`() {
        val b = ByteArray(MAX_STORED_RAWPACKET_BYTES)
        assertSame(b, boundStoredRawPacket(b))
    }
}
