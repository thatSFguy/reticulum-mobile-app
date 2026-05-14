package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin the Columba/Sideband wire-format dispatch for LXMF field 16.
 * The decoder accepts the two mutually-exclusive shapes (reaction
 * vs reply) and tolerates both String and ByteArray for string-
 * typed values (different msgpack encoders pick differently — same
 * dual-variant precedent as SPEC.md §5.6).
 *
 * Audit reference: 2026-05-13 reactions + replies feature.
 */
class Field16Test {

    @Test fun reactionStringFields() {
        val payload = extractField16(
            mapOf(
                16 to mapOf<Any?, Any?>(
                    "reaction_to" to "abcd1234",
                    "emoji" to "👍",
                    "sender" to "deadbeef",
                ),
            ),
        )
        assertEquals(
            Field16Payload.Reaction(
                reactionTo = "abcd1234",
                emoji = "👍",
                sender = "deadbeef",
            ),
            payload,
        )
    }

    @Test fun reactionByteArrayFields() {
        // Sideband's msgpack-python sometimes ships strings as bin8 —
        // the decoder must accept ByteArray values and UTF-8 them.
        val payload = extractField16(
            mapOf(
                16 to mapOf<Any?, Any?>(
                    "reaction_to" to "abcd1234".encodeToByteArray(),
                    "emoji" to "👍".encodeToByteArray(),
                    "sender" to "deadbeef".encodeToByteArray(),
                ),
            ),
        )
        assertEquals(
            Field16Payload.Reaction(
                reactionTo = "abcd1234",
                emoji = "👍",
                sender = "deadbeef",
            ),
            payload,
        )
    }

    @Test fun reactionFieldKeyAsLong() {
        // umsgpack on some Python releases emits the integer key as
        // a Long. Our extractor's `(k as? Number).toInt() == 16`
        // catches this; pin so the upcast can't regress.
        val payload = extractField16(
            mapOf(
                16L to mapOf<Any?, Any?>(
                    "reaction_to" to "abcd1234",
                    "emoji" to "👍",
                    "sender" to "deadbeef",
                ),
            ),
        )
        assertTrue(payload is Field16Payload.Reaction)
    }

    @Test fun replyShape() {
        val payload = extractField16(
            mapOf(
                16 to mapOf<Any?, Any?>(
                    "reply_to" to "abcd1234",
                ),
            ),
        )
        assertEquals(Field16Payload.Reply(replyTo = "abcd1234"), payload)
    }

    @Test fun absentReturnsNull() {
        assertNull(extractField16(mapOf(1 to "hello")))
        assertNull(extractField16(emptyMap()))
    }

    @Test fun malformedReturnsNull() {
        // No recognised sub-keys
        assertNull(extractField16(mapOf(16 to mapOf("foo" to "bar"))))
        // Not a map value
        assertNull(extractField16(mapOf(16 to "raw string")))
        // Wrong types for the recognised sub-keys
        assertNull(extractField16(mapOf(16 to mapOf("reaction_to" to 42))))
    }

    @Test fun reactionWinsWhenBothPresent() {
        // Columba's convention is mutually exclusive but if a future
        // peer ships both, prefer the reaction (matches Columba's
        // dispatch order).
        val payload = extractField16(
            mapOf(
                16 to mapOf<Any?, Any?>(
                    "reaction_to" to "aaa",
                    "emoji" to "👍",
                    "sender" to "bbb",
                    "reply_to" to "ccc",
                ),
            ),
        )
        assertTrue(payload is Field16Payload.Reaction)
    }

    // ---- MeshChatX shape (fields[0x30] + optional [0x31]) ---------

    @Test fun meshChatXReplyShape_rawBytes() {
        // 0x30 holds the target's hash as RAW BYTES, not hex. The
        // extractor hex-encodes them so the rest of the codebase
        // (StoredMessage.replyToMessageId, getByMessageId, etc.)
        // sees consistent hex strings regardless of which wire
        // shape arrived.
        val hashBytes = ByteArray(32) { (it + 1).toByte() }
        val expectedHex = hashBytes.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        val payload = extractField16(mapOf(0x30 to hashBytes))
        assertEquals(
            Field16Payload.Reply(replyTo = expectedHex, quotedContent = null),
            payload,
        )
    }

    @Test fun meshChatXReplyShape_withQuotedContent() {
        // 0x31 ships the quoted message text as UTF-8 bytes per
        // MeshChatX lxmf_utils.py:343-349. Extractor decodes to
        // String so the reply-preview render can use it as a
        // fallback when the target row isn't found locally.
        val hashBytes = ByteArray(32) { (it + 1).toByte() }
        val quoted = "Hello mesh world".encodeToByteArray()
        val payload = extractField16(mapOf(
            0x30 to hashBytes,
            0x31 to quoted,
        ))
        val expectedHex = hashBytes.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        assertEquals(
            Field16Payload.Reply(
                replyTo = expectedHex,
                quotedContent = "Hello mesh world",
            ),
            payload,
        )
    }

    @Test fun columbaReplyTakesPrecedenceWhenBothShapesPresent() {
        // If a future bridge ships BOTH the Columba and MeshChatX
        // shapes on the same LXMF (weird but possible), prefer
        // field 16's value — it's the older convention. Doesn't
        // matter much since they point at the same target_id
        // either way, but tie-break is deterministic.
        val payload = extractField16(mapOf(
            16 to mapOf<Any?, Any?>("reply_to" to "abcd1234"),
            0x30 to ByteArray(32) { 0x42 },
        ))
        assertEquals("abcd1234", (payload as? Field16Payload.Reply)?.replyTo)
    }

    @Test fun meshChatXReplyShape_emptyBytesIsAbsent() {
        // 0x30 with empty bytes is treated as no-reply. Defensive
        // against bridges that include the key with no payload.
        val payload = extractField16(mapOf(0x30 to ByteArray(0)))
        assertNull(payload)
    }
}
