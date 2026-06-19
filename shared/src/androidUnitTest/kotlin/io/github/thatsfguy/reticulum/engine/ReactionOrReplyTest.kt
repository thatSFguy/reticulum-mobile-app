package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin the inbound LXMF reaction/reply wire-format dispatch against the
 * official upstream allocation (LXMF 1.0.0):
 *   - FIELD_REACTION    (0x40) — SPEC §5.9.8
 *   - FIELD_REPLY_TO    (0x30) + optional FIELD_REPLY_QUOTE (0x31) — §5.9.9
 *
 * The pre-1.0.0 legacy `fields[16]` app-extension shapes are NOT
 * parsed (removed from SPEC §5.9 on 2026-06-19 — the spec is the
 * authority and we don't track other clients' shapes). The tests at the
 * bottom pin that they return null so a regression can't silently
 * re-introduce legacy parsing. We never emit them either.
 *
 * Bytes/str + integer-key (Int vs Long) tolerance is verified per
 * SPEC §5.6 / §5.9.8 — different msgpack encoders pick differently.
 */
class ReactionOrReplyTest {

    private fun hex(bytes: ByteArray) =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---- FIELD_REACTION (0x40) ------------------------------------

    @Test fun reaction0x40_rawBytesAndEmojiBytes() {
        // Canonical wire form: int-keyed inner dict, raw 32-byte
        // message_id at 0x00, UTF-8 emoji bytes at 0x01, no sender.
        val msgId = ByteArray(32) { (it + 1).toByte() }
        val payload = extractReactionOrReply(
            mapOf(
                0x40 to mapOf<Any?, Any?>(
                    0x00 to msgId,
                    0x01 to "👍".encodeToByteArray(),
                ),
            ),
        )
        assertEquals(
            ReactionOrReply.Reaction(reactionTo = hex(msgId), emoji = "👍"),
            payload,
        )
    }

    @Test fun reaction0x40_emojiAsString() {
        // Some encoders surface the UTF-8 content as a msgpack `str`.
        val msgId = ByteArray(32) { 0x11 }
        val payload = extractReactionOrReply(
            mapOf(
                0x40 to mapOf<Any?, Any?>(
                    0x00 to msgId,
                    0x01 to "🎉",
                ),
            ),
        )
        assertEquals(
            ReactionOrReply.Reaction(reactionTo = hex(msgId), emoji = "🎉"),
            payload,
        )
    }

    @Test fun reaction0x40_keysAsLong() {
        // umsgpack emits integer keys as Long on some releases — for
        // BOTH the outer fields map and the inner 0x40 dict. byIntKey's
        // numeric cast must catch this (SPEC §5.9.8 inner-map tolerance).
        val msgId = ByteArray(32) { (it + 5).toByte() }
        val payload = extractReactionOrReply(
            mapOf(
                0x40L to mapOf<Any?, Any?>(
                    0x00L to msgId,
                    0x01L to "👍".encodeToByteArray(),
                ),
            ),
        )
        assertTrue(payload is ReactionOrReply.Reaction)
        assertEquals(hex(msgId), (payload as ReactionOrReply.Reaction).reactionTo)
    }

    @Test fun reaction0x40_missingSubKeysReturnsNull() {
        val msgId = ByteArray(32) { 0x22 }
        // No emoji (0x01)
        assertNull(extractReactionOrReply(mapOf(0x40 to mapOf<Any?, Any?>(0x00 to msgId))))
        // No message_id (0x00)
        assertNull(
            extractReactionOrReply(
                mapOf(0x40 to mapOf<Any?, Any?>(0x01 to "👍".encodeToByteArray())),
            ),
        )
        // Empty message_id bytes
        assertNull(
            extractReactionOrReply(
                mapOf(0x40 to mapOf<Any?, Any?>(0x00 to ByteArray(0), 0x01 to "👍")),
            ),
        )
    }

    // ---- FIELD_REPLY_TO (0x30) + FIELD_REPLY_QUOTE (0x31) ---------

    @Test fun reply0x30_rawBytes() {
        // 0x30 holds the target hash as RAW BYTES; the extractor
        // hex-encodes so the rest of the codebase sees consistent hex.
        val hashBytes = ByteArray(32) { (it + 1).toByte() }
        val payload = extractReactionOrReply(mapOf(0x30 to hashBytes))
        assertEquals(
            ReactionOrReply.Reply(replyTo = hex(hashBytes), quotedContent = null),
            payload,
        )
    }

    @Test fun reply0x30_withQuotedContent() {
        // 0x31 ships the quoted message text as UTF-8 bytes; decoded
        // to String so the reply-preview render can use it as a
        // fallback when the target row isn't found locally.
        val hashBytes = ByteArray(32) { (it + 1).toByte() }
        val payload = extractReactionOrReply(
            mapOf(
                0x30 to hashBytes,
                0x31 to "Hello mesh world".encodeToByteArray(),
            ),
        )
        assertEquals(
            ReactionOrReply.Reply(replyTo = hex(hashBytes), quotedContent = "Hello mesh world"),
            payload,
        )
    }

    @Test fun reply0x30_emptyBytesIsAbsent() {
        // 0x30 with empty bytes is treated as no-reply. Defensive
        // against bridges that include the key with no payload.
        assertNull(extractReactionOrReply(mapOf(0x30 to ByteArray(0))))
    }

    // ---- precedence + absence -------------------------------------

    @Test fun reactionWinsWhenBothReactionAndReplyPresent() {
        // A message carrying both fields (weird but possible) is
        // dispatched as a reaction — matches the parse order.
        val msgId = ByteArray(32) { 0x33 }
        val payload = extractReactionOrReply(
            mapOf(
                0x40 to mapOf<Any?, Any?>(0x00 to msgId, 0x01 to "👍"),
                0x30 to ByteArray(32) { 0x44 },
            ),
        )
        assertTrue(payload is ReactionOrReply.Reaction)
    }

    @Test fun absentReturnsNull() {
        assertNull(extractReactionOrReply(mapOf(1 to "hello")))
        assertNull(extractReactionOrReply(emptyMap()))
    }

    // ---- legacy fields[16] shapes are NOT parsed ------------------
    // Removed from SPEC §5.9 on 2026-06-19. We neither emit nor parse
    // them — these pin the drop so it can't silently regress.

    @Test fun legacyField16Reaction_returnsNull() {
        assertNull(
            extractReactionOrReply(
                mapOf(
                    16 to mapOf<Any?, Any?>(
                        "reaction_to" to "abcd1234",
                        "emoji" to "👍",
                        "sender" to "deadbeef",
                    ),
                ),
            ),
        )
    }

    @Test fun legacyField16Reply_returnsNull() {
        assertNull(
            extractReactionOrReply(mapOf(16 to mapOf<Any?, Any?>("reply_to" to "abcd1234"))),
        )
    }
}
