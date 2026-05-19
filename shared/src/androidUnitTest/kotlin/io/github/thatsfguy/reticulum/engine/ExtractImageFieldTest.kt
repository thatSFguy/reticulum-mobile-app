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
 *   - The INBOUND_IMAGE_MAX_BYTES ceiling protects against a hostile
 *     peer shipping a multi-MB blob, but it must clear real interop
 *     traffic: full LXMF clients attach their own scaled images
 *     (a Sideband-downscaled camera photo was seen on the wire at
 *     ~41 KB, and higher when the sender picks better quality). The
 *     ceiling is interop-sized (512 KB), NOT sized to our own 20 KB
 *     sender ladder — the old 32 KB value silently dropped every
 *     Sideband image.
 *   - Non-ByteArray values are ignored (a future LXMF field 6 type
 *     change would otherwise crash on the persist-to-imageBytes path).
 */
class ExtractImageFieldTest {

    @Test fun `empty fields returns null with zero raw size`() {
        val (bytes, size) = extractImageField(emptyMap())
        assertNull(bytes)
        assertEquals(0, size)
    }

    @Test fun `canonical wire format - 2-element list extension+bytes - extracts cleanly`() {
        // The actual wire format Sideband + Columba emit and consume:
        // fields[6] = ["jpg", <bytes>]. See ReticulumEngine.kt
        // tryDeliverOverLink comment for the cross-reference to
        // Sideband sbapp/main.py:2192 + ui/messages.py:814.
        val payload = ByteArray(2048) { it.toByte() }
        val (bytes, size) = extractImageField(
            mapOf<Any?, Any?>(6 to listOf("jpg", payload))
        )
        assertNotNull(bytes)
        assertEquals(2048, size)
        assertTrue(bytes.contentEquals(payload))
    }

    @Test fun `Long key 6 with list value — Sideband encoder parity`() {
        // Sideband's msgpack-python sometimes packs Int keys as Long;
        // verify the integer-coercion key match holds with the canonical
        // list value too.
        val payload = ByteArray(1024) { (it * 7).toByte() }
        val (bytes, size) = extractImageField(
            mapOf<Any?, Any?>(6L to listOf("png", payload))
        )
        assertNotNull(bytes,
            "Long-encoded key 6 must still match the canonical list value")
        assertEquals(1024, size)
        assertTrue(bytes.contentEquals(payload))
    }

    @Test fun `Short key 6 with list value matches`() {
        val payload = ByteArray(512)
        val (bytes, _) = extractImageField(
            mapOf<Any?, Any?>(6.toShort() to listOf("webp", payload))
        )
        assertNotNull(bytes, "Short-encoded key 6 must still match list value")
    }

    @Test fun `bare ByteArray value still extracts — back-compat with our v1_1_15-16 senders`() {
        // Our v1.1.15-1.1.16 (broken pre-this-fix) sender packed the
        // image as bare bytes instead of [ext, bytes]. Mobile-to-mobile
        // worked because both ends agreed on the wrong format; mobile-
        // to-Sideband broke. After this fix the sender uses the
        // canonical list form, but receivers still accept bare bytes
        // so an updated phone can still display images from a peer
        // still running v1.1.15 / v1.1.16.
        val payload = ByteArray(700) { (it + 1).toByte() }
        val (bytes, _) = extractImageField(mapOf<Any?, Any?>(6 to payload))
        assertNotNull(bytes, "bare-ByteArray fallback must survive for v1.1.15-16 peers")
        assertTrue(bytes.contentEquals(payload))
    }

    @Test fun `list with wrong shape — single element — returns null`() {
        // A peer that mispacked the list (forgot the extension, or
        // packed only the extension) must not crash us. The bytes at
        // index [1] don't exist → null extraction.
        val (bytes, size) = extractImageField(
            mapOf<Any?, Any?>(6 to listOf("jpg"))
        )
        assertNull(bytes,
            "Single-element list must be rejected — Sideband indexes [1] for bytes")
        assertEquals(0, size)
    }

    @Test fun `list with non-ByteArray at index 1 returns null`() {
        // Defensive: a peer that put a string or some other type at
        // [1] should be silently dropped, not crash the unpack.
        val (bytes, _) = extractImageField(
            mapOf<Any?, Any?>(6 to listOf("jpg", "not bytes"))
        )
        assertNull(bytes)
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

    @Test fun `non-ByteArray non-List value at key 6 returns null`() {
        // A peer that misencodes the value as a plain string (not a
        // list, not a ByteArray) should not crash us — return null so
        // the row still saves (text content preserved) and the bad
        // image is silently dropped.
        val (bytes, size) = extractImageField(mapOf<Any?, Any?>(6 to "not bytes"))
        assertNull(bytes)
        assertEquals(0, size,
            "non-ByteArray / non-List value returns size=0, not the string's char count")
    }

    @Test fun `Sideband-sized image over the old 32 KB cap is accepted`() {
        // Regression pin for the "large Sideband images render as an
        // empty bubble" bug (2026-05-19). A Sideband-downscaled camera
        // photo was observed on the wire at 41590 B — over the old
        // 32 KB ceiling, so extractImageField dropped it and the
        // image-only message saved with imageBytes=null, rendering as
        // a blank bubble. The interop-realistic ceiling must accept it.
        val payload = ByteArray(41_590) { it.toByte() }
        val (bytes, size) = extractImageField(
            mapOf<Any?, Any?>(6 to listOf("jpg", payload))
        )
        assertNotNull(bytes,
            "a 41 KB Sideband image must extract — INBOUND_IMAGE_MAX_BYTES must stay interop-sized")
        assertEquals(41_590, size)
        assertTrue(bytes.contentEquals(payload))
    }

    @Test fun `payload exactly at the cap is accepted - canonical list form`() {
        val payload = ByteArray(INBOUND_IMAGE_MAX_BYTES) { 0xAB.toByte() }
        val (bytes, size) = extractImageField(
            mapOf<Any?, Any?>(6 to listOf("jpg", payload))
        )
        assertNotNull(bytes, "exactly-cap payload must pass")
        assertEquals(INBOUND_IMAGE_MAX_BYTES, size)
    }

    @Test fun `payload one byte over the cap is rejected - size leaks for log`() {
        val payload = ByteArray(INBOUND_IMAGE_MAX_BYTES + 1)
        val (bytes, size) = extractImageField(
            mapOf<Any?, Any?>(6 to listOf("jpg", payload))
        )
        assertNull(bytes, "${INBOUND_IMAGE_MAX_BYTES + 1} B must be dropped")
        assertEquals(INBOUND_IMAGE_MAX_BYTES + 1, size,
            "size leaks the full payload size for the dropped-oversize diagnostic log line")
    }

    @Test fun `pathological 10 MB blob is rejected - canonical list form`() {
        val tenMB = 10 * 1024 * 1024
        val payload = ByteArray(tenMB)
        val (bytes, size) = extractImageField(
            mapOf<Any?, Any?>(6 to listOf("png", payload))
        )
        assertNull(bytes)
        assertEquals(tenMB, size)
    }
}
