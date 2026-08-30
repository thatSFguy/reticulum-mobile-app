package io.github.thatsfguy.reticulum.rrc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single-emoji rule for reactions (`rrc-extensions.md` §2).
 *
 * This is a security boundary, not a style rule: a reaction body is
 * attacker-chosen text, and without the rule `K_BODY` becomes an
 * unbounded second message channel that bypasses whatever the client
 * does to ordinary message rendering (§5).
 *
 * camelCase test names keep the iosTest Kotlin/Native compile happy.
 */
class RrcReactionsTest {

    @Test fun plainEmojiAreAccepted() {
        for (e in listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE4F", "\u2705")) {
            assertTrue(RrcReactions.isPlausibleReaction(e), "rejected $e")
        }
    }

    @Test fun skinTonesAndZwjSequencesAreOneEmoji() {
        // thumbs-up + medium skin tone
        assertTrue(RrcReactions.isPlausibleReaction("\uD83D\uDC4D\uD83C\uDFFD"))
        // family: man, woman, girl, boy (ZWJ sequence)
        assertTrue(
            RrcReactions.isPlausibleReaction(
                "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66",
            ),
        )
    }

    @Test fun aFlagIsAPairOfRegionalIndicators() {
        assertTrue(RrcReactions.isPlausibleReaction("\uD83C\uDDEC\uD83C\uDDE7"))
        // Three regional indicators is not a flag.
        assertFalse(RrcReactions.isPlausibleReaction("\uD83C\uDDEC\uD83C\uDDE7\uD83C\uDDFA"))
    }

    /** The case the rule exists for. */
    @Test fun aSentenceIsNotAReaction() {
        assertFalse(RrcReactions.isPlausibleReaction("this is not a reaction"))
        assertFalse(RrcReactions.isPlausibleReaction("\uD83D\uDC4D\uD83D\uDC4D"))
        assertFalse(RrcReactions.isPlausibleReaction("\uD83D\uDC4D nice"))
    }

    @Test fun emptyAndWhitespaceAreRejected() {
        assertFalse(RrcReactions.isPlausibleReaction(""))
        assertFalse(RrcReactions.isPlausibleReaction(" "))
        assertFalse(RrcReactions.isPlausibleReaction("\n"))
        assertFalse(RrcReactions.isPlausibleReaction("\t"))
    }

    @Test fun controlCharactersAreRejected() {
        assertFalse(RrcReactions.isPlausibleReaction("\u0007"))
        assertFalse(RrcReactions.isPlausibleReaction("\uD83D\uDC4D\u0000"))
    }

    @Test fun anOverlongStringIsRejectedBeforeAnalysis() {
        assertFalse(RrcReactions.isPlausibleReaction("\uD83D\uDC4D".repeat(40)))
    }

    /** A lone modifier is not a reaction — there is no base to modify. */
    @Test fun aBareModifierIsRejected() {
        assertFalse(RrcReactions.isPlausibleReaction("\uD83C\uDFFD"))
        assertFalse(RrcReactions.isPlausibleReaction("\u200D"))
    }

    /**
     * Deliberately allowed: one letter is one grapheme cluster, which
     * is all the wire rule requires. Rejecting it would mean shipping a
     * full emoji property table to gain nothing on the safety side —
     * the bound that matters is "not a second message body".
     */
    @Test fun aSingleLetterIsOneClusterAndPasses() {
        assertTrue(RrcReactions.isPlausibleReaction("x"))
    }
}
