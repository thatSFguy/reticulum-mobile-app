package io.github.thatsfguy.reticulum.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [ReactionsJson]. The encoded shape matches
 * what Columba's MessageMapper.kt parses on the receive side, so
 * round-trip + idempotent dedupe + cross-emoji accumulation are
 * the three invariants we pin. Audit reference: 2026-05-13
 * reactions + replies feature.
 */
class ReactionsJsonTest {

    @Test fun encodeEmpty() {
        assertEquals("{}", ReactionsJson.encode(emptyMap()))
    }

    @Test fun roundTripSingleEmojiSingleSender() {
        val input = mapOf("👍" to listOf("a4383b4658729ab8e204e89724e2b383"))
        val encoded = ReactionsJson.encode(input)
        val decoded = ReactionsJson.decode(encoded)
        assertEquals(input, decoded)
    }

    @Test fun roundTripMultipleEmojiMultipleSenders() {
        val input = mapOf(
            "👍" to listOf("aaaa11", "bbbb22"),
            "❤️" to listOf("cccc33"),
        )
        val encoded = ReactionsJson.encode(input)
        val decoded = ReactionsJson.decode(encoded)
        assertEquals(input, decoded)
    }

    @Test fun decodeNullOrBlankYieldsEmpty() {
        assertEquals(emptyMap(), ReactionsJson.decode(null))
        assertEquals(emptyMap(), ReactionsJson.decode(""))
        assertEquals(emptyMap(), ReactionsJson.decode("   "))
        assertEquals(emptyMap(), ReactionsJson.decode("{}"))
    }

    @Test fun applyReactionAddsNewSender() {
        val (json, changed) = ReactionsJson.applyReaction(null, "👍", "aaaa11")
        assertTrue(changed)
        assertEquals(mapOf("👍" to listOf("aaaa11")), ReactionsJson.decode(json))
    }

    @Test fun applyReactionAppendsSecondSender() {
        val (j1, _) = ReactionsJson.applyReaction(null, "👍", "aaaa11")
        val (j2, changed) = ReactionsJson.applyReaction(j1, "👍", "bbbb22")
        assertTrue(changed)
        assertEquals(
            mapOf("👍" to listOf("aaaa11", "bbbb22")),
            ReactionsJson.decode(j2),
        )
    }

    @Test fun applyReactionIsIdempotentOnSameSender() {
        val (j1, _) = ReactionsJson.applyReaction(null, "👍", "aaaa11")
        val (j2, changed) = ReactionsJson.applyReaction(j1, "👍", "aaaa11")
        assertFalse(changed, "second apply of same (emoji, sender) must be a no-op")
        // JSON should round-trip equivalently.
        assertEquals(ReactionsJson.decode(j1), ReactionsJson.decode(j2))
    }

    @Test fun applyReactionAccumulatesAcrossEmojis() {
        val (j1, _) = ReactionsJson.applyReaction(null, "👍", "aaaa11")
        val (j2, _) = ReactionsJson.applyReaction(j1, "❤️", "bbbb22")
        val (j3, _) = ReactionsJson.applyReaction(j2, "👍", "cccc33")
        val decoded = ReactionsJson.decode(j3)
        assertEquals(listOf("aaaa11", "cccc33"), decoded["👍"])
        assertEquals(listOf("bbbb22"), decoded["❤️"])
    }

    // ---- Audit 2026-07-28 L4: reject implausible attacker emojis ---------

    @Test fun applyReactionRejectsControlCharEmoji() {
        // A raw control char would, pre-fix, produce JSON that fails to
        // re-parse and wipes every reaction on the message.
        val (json, changed) = ReactionsJson.applyReaction(null, "bad", "aaaa11")
        assertFalse(changed, "control-char emoji must be rejected as a no-op")
        assertEquals(emptyMap(), ReactionsJson.decode(json))
    }

    @Test fun applyReactionRejectsEmptyAndOverlongEmoji() {
        assertFalse(ReactionsJson.applyReaction(null, "", "aaaa11").second)
        val overlong = "a".repeat(65)
        assertFalse(
            ReactionsJson.applyReaction(null, overlong, "aaaa11").second,
            "emoji longer than the grapheme cap must be rejected",
        )
    }

    @Test fun applyReactionRejectsNewlineEmojiWithoutCorruptingExisting() {
        val (j1, _) = ReactionsJson.applyReaction(null, "👍", "aaaa11")
        val (j2, changed) = ReactionsJson.applyReaction(j1, "x\ny", "bbbb22")
        assertFalse(changed)
        // The pre-existing, valid reaction survives intact.
        assertEquals(mapOf("👍" to listOf("aaaa11")), ReactionsJson.decode(j2))
    }

    // ---- Audit 2026-08-31 F6: escapes must survive a round trip ---------

    /**
     * The encoder writes `\uXXXX` for C0 controls; the decoder used to
     * read the `u` as the character itself and hand back `u000a`. Dead
     * today because the C0 filter runs first, which is exactly why it
     * has to be tested — the guard is the only thing hiding it.
     */
    @Test fun theDecoderUnderstandsTheEscapesTheEncoderWrites() {
        val encoded = ReactionsJson.encode(mapOf("a\nb" to listOf("aaaa11")))
        assertEquals(mapOf("a\nb" to listOf("aaaa11")), ReactionsJson.decode(encoded))
    }

    /** Quote and backslash still round-trip. */
    @Test fun quotesAndBackslashesRoundTrip() {
        val tricky = mapOf("\"\\" to listOf("aaaa11"))
        assertEquals(tricky, ReactionsJson.decode(ReactionsJson.encode(tricky)))
    }

    // ---- Audit 2026-08-31 F2: bound how MANY reactions a row holds ------

    /**
     * Per-emoji validation bounds one reaction; nothing bounded the
     * count. On the RRC path K_SRC is whatever the hub says, so a
     * hostile hub can mint unlimited distinct reactors AND unlimited
     * distinct emoji and grow this one column without limit.
     */
    @Test fun applyReactionCapsDistinctEmojiPerMessage() {
        var json: String? = null
        // 40 distinct, individually-valid emoji from one sender.
        var accepted = 0
        for (cp in 0x1F600 until 0x1F600 + 40) {
            val (next, changed) = ReactionsJson.applyReaction(
                json, String(Character.toChars(cp)), "aaaa11",
            )
            json = next
            if (changed) accepted++
        }
        assertEquals(16, accepted, "distinct emoji per message must be capped")
        assertEquals(16, ReactionsJson.decode(json).size)
    }

    /** Reactors per emoji are capped the same way. */
    @Test fun applyReactionCapsReactorsPerEmoji() {
        var json: String? = null
        var accepted = 0
        for (i in 0 until 100) {
            val (next, changed) = ReactionsJson.applyReaction(json, "👍", "%032x".format(i))
            json = next
            if (changed) accepted++
        }
        assertEquals(64, accepted, "reactors per emoji must be capped")
        assertEquals(64, ReactionsJson.decode(json)["👍"]?.size)
    }

    /** Hitting the cap is a no-op, not corruption — everything already
     *  aggregated on the row survives. */
    @Test fun reachingTheCapLeavesExistingReactionsIntact() {
        var json: String? = null
        for (cp in 0x1F600 until 0x1F600 + 16) {
            json = ReactionsJson.applyReaction(json, String(Character.toChars(cp)), "aaaa11").first
        }
        val before = ReactionsJson.decode(json)
        val (after, changed) = ReactionsJson.applyReaction(json, "❤️", "bbbb22")
        assertFalse(changed)
        assertEquals(before, ReactionsJson.decode(after))
    }

    /** A capped row still accepts a second reactor on an emoji it
     *  already holds — the cap is on new slots, not on participation. */
    @Test fun aFullEmojiSetStillAcceptsAnotherReactorOnAHeldEmoji() {
        var json: String? = null
        for (cp in 0x1F600 until 0x1F600 + 16) {
            json = ReactionsJson.applyReaction(json, String(Character.toChars(cp)), "aaaa11").first
        }
        val held = String(Character.toChars(0x1F600))
        val (after, changed) = ReactionsJson.applyReaction(json, held, "bbbb22")
        assertTrue(changed, "an emoji already on the row must still accept new reactors")
        assertEquals(listOf("aaaa11", "bbbb22"), ReactionsJson.decode(after)[held])
    }
}
