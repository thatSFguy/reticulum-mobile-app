package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.PassphraseStrength
import io.github.thatsfguy.reticulum.crypto.assessPassphrase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [assessPassphrase]. The policy is:
 *   - len ≥ 20 (any classes)          → Strong / acceptable
 *   - len ≥ 12 AND ≥ 3 classes        → Strong / acceptable
 *   - len ≥ 12 AND ≥ 2 classes        → Acceptable / acceptable
 *   - everything else                 → TooWeak / NOT acceptable
 *
 * Audit reference: 2026-05-13 HIGH-3.
 */
class PassphraseStrengthTest {

    @Test fun empty_isTooWeak() {
        val a = assessPassphrase("")
        assertEquals(PassphraseStrength.TooWeak, a.strength)
        assertFalse(a.acceptable)
        assertNotNull(a.reason)
    }

    @Test fun shortLowercase_isTooWeak() {
        val a = assessPassphrase("short")
        assertEquals(PassphraseStrength.TooWeak, a.strength)
        assertFalse(a.acceptable)
    }

    @Test fun twelveCharsOneClass_isTooWeak() {
        // 12 chars, single class (lowercase only) — narrow alphabet
        // means brute-force is still cheap.
        val a = assessPassphrase("alllowercase")
        assertEquals(PassphraseStrength.TooWeak, a.strength)
        assertFalse(a.acceptable)
    }

    @Test fun twelveCharsTwoClasses_isAcceptable() {
        // 12 chars, exactly 2 classes (lower + digit). Passes the
        // minimum bar but the UI nudges toward 3+ classes / longer.
        val a = assessPassphrase("abcdef123456")
        assertEquals(PassphraseStrength.Acceptable, a.strength)
        assertTrue(a.acceptable)
        assertNotNull(a.reason)  // "OK but could be stronger" hint
    }

    @Test fun twelveCharsThreeClasses_isStrong() {
        val a = assessPassphrase("Mixed1234!ab")
        assertEquals(PassphraseStrength.Strong, a.strength)
        assertTrue(a.acceptable)
        assertNull(a.reason)
    }

    @Test fun twentyCharsAllLowercase_isStrong() {
        // Length-only route — diceware / sentence style. Single class
        // (lowercase + space, but space counts as symbol so this is
        // actually 2 classes; let's pick a strictly-lowercase variant).
        val a = assessPassphrase("aaaaaaaaaaaaaaaaaaaa")
        assertEquals(PassphraseStrength.Strong, a.strength)
        assertTrue(a.acceptable)
    }

    @Test fun fourSpaceSeparatedWords_isStrong() {
        // The xkcd-936 example: humans memorise long phrases easily;
        // entropy comes from length.
        val a = assessPassphrase("correct horse battery staple")
        assertEquals(PassphraseStrength.Strong, a.strength)
        assertTrue(a.acceptable)
    }

    @Test fun symbolCountsAsClass() {
        // Spaces, hyphens, and other non-alphanumerics all count as
        // "symbol" — important so passphrases like "round-trip-test"
        // get credit for the hyphens.
        val a = assessPassphrase("Round-Trip-1!")
        assertEquals(PassphraseStrength.Strong, a.strength)
        assertTrue(a.acceptable)
    }
}
