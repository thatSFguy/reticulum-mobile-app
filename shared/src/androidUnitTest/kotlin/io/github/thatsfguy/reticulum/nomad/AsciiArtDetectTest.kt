package io.github.thatsfguy.reticulum.nomad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classification heuristics for the Nomad page renderer. Each
 * `\``=`-delimited Literal block in a micron document is checked: if
 * the heuristics say "this looks like ASCII art" the renderer scales
 * the monospace font down to fit the device width instead of letting
 * 80-col banner art wrap and lose its shape.
 *
 * We deliberately stay conservative — false positives (regular code
 * blocks misclassified as art) are tolerable; false NEGATIVES (real
 * banner art that wraps) are the user-visible bug we're fixing.
 */
class AsciiArtDetectTest {

    @Test
    fun `empty input is not ASCII art`() {
        assertFalse(isAsciiArtBlock(emptyList()))
    }

    @Test
    fun `short narrow lines are not ASCII art`() {
        val plainCode = listOf(
            "def hello():",
            "    return 42",
        )
        assertFalse(isAsciiArtBlock(plainCode))
    }

    @Test
    fun `box-drawing characters mark a block as ASCII art`() {
        val box = listOf(
            "┌────────────────────────────────────────────────────────────────┐",
            "│ Welcome to NomadNet — your gateway to the off-grid mesh        │",
            "└────────────────────────────────────────────────────────────────┘",
        )
        assertTrue(isAsciiArtBlock(box))
    }

    @Test
    fun `ruler lines mark a block as ASCII art`() {
        val ruler = listOf(
            "================================================================",
            " welcome to my page                                              ",
            "================================================================",
        )
        assertTrue(isAsciiArtBlock(ruler))
    }

    @Test
    fun `wide block with high non-alphanumeric density is ASCII art`() {
        // Synthetic banner — half slash / pipe / underscore.
        val banner = listOf(
            """  _____  _      _____ __  __ _____  _____ _____  ____  _   _ ____  """,
            """ |  __ \| |    |_   _|  \/  |  __ \|_   _|  __ \|  _ \| | | |  _ \ """,
            """ | |__) | |      | | | \  / | |__) | | | | |__) | |_) | | | | |_) |""",
            """ |  ___/| |      | | | |\/| |  ___/  | | |  _  /|  _ <| | | |  _ < """,
            """ | |    | |____ _| |_| |  | | |     _| |_| | \ \| |_) | |_| | |_) |""",
            """ |_|    |______|_____|_|  |_|_|    |_____|_|  \_\____/ \___/|____/ """,
        )
        assertTrue(isAsciiArtBlock(banner))
    }

    @Test
    fun `wide block of plain prose is not ASCII art`() {
        // Two long lines of natural sentences — non-alphanumeric
        // density is low; no box-drawing; no ruler.
        val prose = listOf(
            "This is a long line of plain English with words and spaces and the occasional comma, period, or similar.",
            "Second sentence continues at the same width without any banner-style formatting, just sentence structure.",
        )
        assertFalse(isAsciiArtBlock(prose))
    }

    @Test
    fun `narrow box-drawing is still ASCII art`() {
        // Width alone is NOT the gate — narrow but box-drawing-laden
        // blocks should still get the art treatment so they don't
        // get a different scale than wider art on the same page.
        val narrow = listOf(
            "┌────────┐",
            "│ small  │",
            "└────────┘",
        )
        assertTrue(isAsciiArtBlock(narrow))
    }

    @Test
    fun `maxLineLength reports the widest line`() {
        val lines = listOf("a", "ab", "abc")
        assertEquals(3, maxLineLength(lines))
    }

    @Test
    fun `maxLineLength on empty list is zero`() {
        assertEquals(0, maxLineLength(emptyList()))
    }
}
