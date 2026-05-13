package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavior pins for [extractImageField] — the inbound LXMF
 * `FIELD_IMAGE` extractor wired into all three receive paths
 * (link-delivered `handleLinkLxmf`, opportunistic `handleData` LXMF
 * branch, propagation-node `PropagationClient` drain). The cases
 * matter for cross-implementation interop:
 *
 *   - Sideband's Python `msgpack` library packs the integer key 6 as
 *     a `Long` on some releases — equality against a literal `6: Int`
 *     would silently miss every Sideband-originated attachment.
 *   - The 32 KB ceiling protects against a hostile peer shipping a
 *     10 MB blob; the sender ladder caps at 20 KB so cooperating
 *     senders never trip this branch.
 *   - Non-ByteArray values are ignored (a future LXMF field 6 type
 *     change would otherwise crash on the persist-to-imageBytes path).
 */
class ExtractImageFieldTest {

    @Test fun `empty fields returns null with zero raw size`() {
        val (bytes, size) = extractImageField(emptyMap())
        assertNull(bytes)
        assertEquals(0, size)
    }

    @Test fun `Int key 6 with small payload extracts cleanly`() {
        val payload = ByteArray(2048) { it.toByte() }
        val (bytes, size) = extractImageField(mapOf<Any?, Any?>(6 to payload))
        assertNotNull(bytes)
        assertEquals(2048, size)
        assertTrue(bytes.contentEquals(payload))
    }

    @Test fun `Long key 6 matches identically — Sideband encoder parity`() {
        val payload = ByteArray(1024) { (it * 7).toByte() }
        val (bytes, size) = extractImageField(mapOf<Any?, Any?>(6L to payload))
        assertNotNull(bytes,
            "Long-encoded key 6 must match — Sideband's msgpack-python sometimes packs Int keys as Long")
        assertEquals(1024, size)
        assertTrue(bytes.contentEquals(payload))
    }

    @Test fun `Short key 6 matches identically`() {
        val payload = ByteArray(512)
        val (bytes, _) = extractImageField(mapOf<Any?, Any?>(6.toShort() to payload))
        assertNotNull(bytes, "Short-encoded key 6 must match")
    }

    @Test fun `other integer keys are ignored`() {
        // LXMF FIELD_FILE = 5, FIELD_AUDIO = 7 (per upstream lxmf-python).
        // We extract only FIELD_IMAGE — other fields ride alongside in the
        // map and must NOT be misinterpreted as the image.
        val (bytes, size) = extractImageField(mapOf<Any?, Any?>(
            5 to ByteArray(100),
            7 to ByteArray(100),
        ))
        assertNull(bytes)
        assertEquals(0, size)
    }

    @Test fun `string key '6' does not match — wire is integer keyed`() {
        // Sideband and Columba both use integer 6, not the string "6".
        // A bug we already fixed in the webclient (CLAUDE.md key bugs §5
        // family — wrong-typed key looks "right" in JSON pretty-printers
        // but fails byte-for-byte on the wire). Guard against re-adding
        // a string-keyed lookup later.
        val (bytes, _) = extractImageField(mapOf<Any?, Any?>("6" to ByteArray(50)))
        assertNull(bytes, "string-keyed '6' must not match — wire format is integer key")
    }

    @Test fun `non-ByteArray value at key 6 returns null`() {
        // A peer that misencodes the value as a list or a string
        // should not crash us — return null so the row still saves
        // (text content preserved) and the bad image is silently
        // dropped.
        val (bytes, size) = extractImageField(mapOf<Any?, Any?>(6 to "not bytes"))
        assertNull(bytes)
        assertEquals(0, size,
            "non-ByteArray value returns size=0, not the string's char count")
    }

    @Test fun `payload exactly at the 32 KB cap is accepted`() {
        // Boundary test — assertTrue(bytes.size <= INBOUND_IMAGE_MAX_BYTES)
        // not strict-less-than.
        val payload = ByteArray(INBOUND_IMAGE_MAX_BYTES) { 0xAB.toByte() }
        val (bytes, size) = extractImageField(mapOf<Any?, Any?>(6 to payload))
        assertNotNull(bytes, "exactly-cap payload must pass")
        assertEquals(INBOUND_IMAGE_MAX_BYTES, size)
    }

    @Test fun `payload one byte over the cap is rejected — size leaks for log`() {
        val payload = ByteArray(INBOUND_IMAGE_MAX_BYTES + 1)
        val (bytes, size) = extractImageField(mapOf<Any?, Any?>(6 to payload))
        assertNull(bytes, "${INBOUND_IMAGE_MAX_BYTES + 1} B must be dropped")
        // Caller uses `size > 0 && bytes == null` to fire the diagnostic.
        // 32 KB+1 must be reported so the log line "image field 32769 B
        // > 32768 B — dropped" is accurate.
        assertEquals(INBOUND_IMAGE_MAX_BYTES + 1, size)
    }

    @Test fun `pathological 10 MB blob is rejected with full size returned`() {
        // The motivating attack — a peer ships a 10 MB image hoping to
        // OOM the receiver on decode. We never persist it; size is
        // returned for the diagnostic.
        val tenMB = 10 * 1024 * 1024
        val payload = ByteArray(tenMB)
        val (bytes, size) = extractImageField(mapOf<Any?, Any?>(6 to payload))
        assertNull(bytes)
        assertEquals(tenMB, size)
    }
}
