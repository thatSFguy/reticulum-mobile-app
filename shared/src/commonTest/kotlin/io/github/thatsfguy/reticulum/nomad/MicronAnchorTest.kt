package io.github.thatsfguy.reticulum.nomad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Coverage for the in-document anchor support ported from upstream
 * NomadNet (`MicronParser.py` / `Browser.py`, fetched 2026-09-01):
 * heading auto-slugs (`MicronParser.py:308-311`), explicit `` `:name ``
 * declarations (`:657-668`), the `header_rows` list a bare `#` walks
 * (`Browser.py:337-348`), and the anchor index binding rule
 * (`markup_to_attrmaps:124-131`, first declaration wins).
 *
 * Before this, a page's own table of contents — the `` `[Rules`#rules] ``
 * pattern every long NomadNet page uses — rendered as a link that showed
 * "Unrecognized link: #rules" when tapped.
 */
class MicronAnchorTest {

    @Test fun `heading gets an auto slug anchor`() {
        val doc = Micron.parseDocument(
            """
            >Board Rules

            Be nice.
            """.trimIndent()
        )
        assertEquals(mapOf("board-rules" to 0), doc.anchors)
        assertEquals(listOf(0), doc.headingBlocks)
    }

    @Test fun `slug strips micron formatting the way upstream does`() {
        // `F0af / `! / `f inside the heading must not leak into the slug,
        // or the page's own #links miss.
        assertEquals("bulletin-board", slugifyMicron("`c`!`F0afBulletin Board`!`f"))
        assertEquals("rules", slugifyMicron("Rules"))
        assertEquals("two-words", slugifyMicron("  Two   Words!  "))
        assertEquals("", slugifyMicron("`!`f"))
    }

    @Test fun `explicit anchor declaration is zero width`() {
        val doc = Micron.parseDocument("`:top Back to the top")
        assertEquals(mapOf("top" to 0), doc.anchors)
        val para = doc.blocks[0] as Block.Paragraph
        // The declaration renders nothing; the space after the name is
        // ordinary text, as upstream's terminate-on-first-non-name-char
        // scan leaves it.
        assertEquals(" Back to the top", (para.runs[0] as Inline.Text).text)
    }

    @Test fun `first declaration of a name wins`() {
        val doc = Micron.parseDocument(
            """
            >Setup

            >>Setup
            """.trimIndent()
        )
        assertEquals(0, doc.anchors["setup"])
        assertEquals(listOf(0, 1), doc.headingBlocks)
    }

    @Test fun `anchors index the block their line produced`() {
        val doc = Micron.parseDocument(
            """
            Intro paragraph.

            >Rules

            `:fine-print Small print here.
            """.trimIndent()
        )
        assertEquals(1, doc.anchors["rules"])
        assertEquals(2, doc.anchors["fine-print"])
    }

    @Test fun `anchor link parses ahead of destination parsing`() {
        assertEquals(LinkTarget.Anchor("rules"), parseLinkTarget("#rules"))
        // Bare `#` = "next heading below the current position".
        assertEquals(LinkTarget.Anchor(""), parseLinkTarget("#"))
    }

    @Test fun `anchor link with an illegal name is rejected`() {
        // Never silently treat a malformed anchor as a page path.
        assertTrue(parseLinkTarget("#../etc/passwd") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("#with space") is LinkTarget.Unknown)
    }

    @Test fun `partial refresh link parses its ids`() {
        assertEquals(LinkTarget.PartialRefresh(listOf("chat")), parseLinkTarget("p:chat"))
        assertEquals(
            LinkTarget.PartialRefresh(listOf("chat", "status")),
            parseLinkTarget("p:chat:status"),
        )
        assertTrue(parseLinkTarget("p:") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("p:bad id") is LinkTarget.Unknown)
    }

    @Test fun `partial declares its pid`() {
        val doc = Micron.parseDocument("`{/page/chat.mu`5`pid=chat|room=lobby}")
        val partial = doc.blocks[0] as Block.Partial
        assertEquals("/page/chat.mu", partial.url)
        assertEquals(5.0, partial.refreshSeconds)
        assertEquals("chat", partial.partialId)
        assertEquals(listOf("pid=chat", "room=lobby"), partial.fields)
    }

    @Test fun `partial without a pid has none`() {
        val doc = Micron.parseDocument("`{/page/chat.mu}")
        assertNull((doc.blocks[0] as Block.Partial).partialId)
    }

    // ---- checkbox multi-select (Browser.py:255-266) ----

    @Test fun `checkboxes sharing a name comma join`() {
        var v: String? = null
        v = toggleCheckboxValue(v, "weather", checked = true)
        v = toggleCheckboxValue(v, "radio", checked = true)
        assertEquals("weather,radio", v)
        assertTrue(isCheckboxChecked(v, "weather"))
        assertTrue(isCheckboxChecked(v, "radio"))
        assertFalse(isCheckboxChecked(v, "power"))
        assertEquals(mapOf("field_topics" to "weather,radio"), buildFormSubmitData(listOf("*"), mapOf("topics" to v!!)))
    }

    @Test fun `unticking the last box removes the key`() {
        var v: String? = toggleCheckboxValue(null, "weather", checked = true)
        v = toggleCheckboxValue(v, "radio", checked = true)
        v = toggleCheckboxValue(v, "weather", checked = false)
        assertEquals("radio", v)
        v = toggleCheckboxValue(v, "radio", checked = false)
        assertNull(v)  // key is dropped, never submitted as ""
    }

    @Test fun `ticking twice does not duplicate`() {
        var v: String? = toggleCheckboxValue(null, "radio", checked = true)
        v = toggleCheckboxValue(v, "radio", checked = true)
        assertEquals("radio", v)
    }
}
