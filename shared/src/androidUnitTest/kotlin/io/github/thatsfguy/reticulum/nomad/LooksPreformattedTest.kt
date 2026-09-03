package io.github.thatsfguy.reticulum.nomad

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #58. A NomadNet forum page frames its post titles in a
 * double-line box; rendered as ordinary paragraph text on a phone the
 * border runs wrapped onto second rows and the verticals were left
 * stranded, turning the frame into a staircase.
 */
class LooksPreformattedTest {

    @Test fun `a box border is preformatted`() {
        assertTrue(looksPreformatted(listOf("╔══════════════════════╗")))
        assertTrue(looksPreformatted(listOf("┌────────────┐")))
        assertTrue(looksPreformatted(listOf("└──────┴──────┘")))
    }

    @Test fun `a whole frame is preformatted on the strength of any one line`() {
        val frame = listOf(
            "╔════════════════════════════╗",
            "║   The Slopware Scrapers    ║",
            "╚════════════════════════════╝",
        )
        assertTrue(looksPreformatted(frame))
    }

    @Test fun `prose mentioning a box character is still prose`() {
        // The whole reason the trigger is a RUN and not a presence test:
        // losing wrapping on a paragraph of prose is worse than leaving
        // one stray glyph unaligned.
        assertFalse(looksPreformatted(listOf("Use the ─ character to draw a rule.")))
        assertFalse(looksPreformatted(listOf("A ═ here and a ║ there, but no border.")))
    }

    @Test fun `three consecutive box characters are not enough`() {
        assertFalse(looksPreformatted(listOf("a ─── b")))
        assertTrue(looksPreformatted(listOf("a ──── b")))
    }

    @Test fun `ascii fallback frames are deliberately not caught`() {
        // `-` and `|` are ordinary punctuation; treating them as borders
        // would reformat far too much prose.
        assertFalse(looksPreformatted(listOf("+--------+", "| hello  |", "+--------+")))
    }

    @Test fun `empty input is not preformatted`() {
        assertFalse(looksPreformatted(emptyList()))
        assertFalse(looksPreformatted(listOf("", "   ")))
    }

    @Test fun `a run split across lines does not count`() {
        assertFalse(looksPreformatted(listOf("──", "──")))
    }
}
