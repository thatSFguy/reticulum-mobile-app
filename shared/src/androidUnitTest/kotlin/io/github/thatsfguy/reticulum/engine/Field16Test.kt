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
}
