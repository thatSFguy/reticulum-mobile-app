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
}
