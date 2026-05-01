package io.github.thatsfguy.reticulum.nomad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MicronTest {

    @Test fun headingsAndParagraphs() {
        val blocks = Micron.parse("""
            >Welcome to the node

            This is a body paragraph
            spanning two lines.

            >>Subheading

            \=

            Another paragraph.
        """.trimIndent())

        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is Block.Heading)
        assertEquals(1, (blocks[0] as Block.Heading).level)
        assertTrue(blocks[1] is Block.Paragraph)
        // Two trimmed lines collapse to one paragraph with a single space joiner.
        val paraText = (blocks[1] as Block.Paragraph).runs.joinToString("") {
            (it as? Inline.Text)?.text ?: ""
        }
        assertEquals("This is a body paragraph spanning two lines.", paraText)
        assertEquals(2, (blocks[2] as Block.Heading).level)
        assertTrue(blocks[3] is Block.HorizontalRule)
        assertTrue(blocks[4] is Block.Paragraph)
    }

    @Test fun boldAndUnderlineInline() {
        val runs = Micron.parseInline("hello \\Bworld\\b and \\!ufoo\\!U bar")
        assertEquals(5, runs.size)
        assertEquals("hello ",  (runs[0] as Inline.Text).text)
        assertEquals("world",   (runs[1] as Inline.Text).text)
        assertEquals(true,      (runs[1] as Inline.Text).style.bold)
        assertEquals(" and ",   (runs[2] as Inline.Text).text)
        assertEquals("foo",     (runs[3] as Inline.Text).text)
        assertEquals(true,      (runs[3] as Inline.Text).style.underline)
        assertEquals(" bar",    (runs[4] as Inline.Text).text)
    }

    @Test fun linksAreEmittedAsLinkInline() {
        val runs = Micron.parseInline("see [the page]:/page/help.mu for more")
        assertEquals(3, runs.size)
        val link = runs[1] as Inline.Link
        assertEquals("the page", link.label)
        assertEquals(":/page/help.mu", link.target)
    }

    @Test fun colorEscapesAreCaptured() {
        val runs = Micron.parseInline("\\Ff00bright\\f normal")
        val first = runs[0] as Inline.Text
        assertEquals("bright", first.text)
        assertEquals("f00", first.style.color)
        val second = runs[1] as Inline.Text
        assertEquals(" normal", second.text)
        assertEquals(null, second.style.color)
    }

    @Test fun unknownEscapesAreNotDropped() {
        val runs = Micron.parseInline("plain \\Q text")
        // The \Q is unrecognised — survive as plain text rather than disappear silently.
        val combined = runs.joinToString("") { (it as Inline.Text).text }
        assertTrue(combined.contains("Q"))
    }
}
