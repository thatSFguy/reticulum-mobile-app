package io.github.thatsfguy.reticulum.nomad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage for the v0.1.48 backtick-based micron parser. Each test ports
 * directly from real upstream NomadNet examples (Guide.py + Node.py
 * fetched from github.com/markqvist/NomadNet master, 2026-05-04).
 */
class MicronTest {

    @Test fun headingsAndParagraphs() {
        val blocks = Micron.parse("""
            >Welcome to the node

            This is a body paragraph
            spanning two lines.

            >>Subheading

            ---

            Another paragraph.
        """.trimIndent())

        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is Block.Heading)
        assertEquals(1, (blocks[0] as Block.Heading).level)
        assertTrue(blocks[1] is Block.Paragraph)
        val paraText = (blocks[1] as Block.Paragraph).runs.joinToString("") {
            (it as? Inline.Text)?.text ?: ""
        }
        assertEquals("This is a body paragraph spanning two lines.", paraText)
        assertEquals(2, (blocks[2] as Block.Heading).level)
        assertTrue(blocks[3] is Block.HorizontalRule)
        assertTrue(blocks[4] is Block.Paragraph)
    }

    @Test fun boldUnderlineItalicAreBacktickToggles() {
        // Real upstream syntax — `! bold, `_ underline, `* italic.
        val (runs, _) = Micron.parseInline("hello `!world`! and `_foo`_ bar `*baz`* end")
        // Expect: "hello ", "world"(bold), " and ", "foo"(underline),
        // " bar ", "baz"(italic), " end"
        assertEquals(7, runs.size)
        assertEquals("hello ", (runs[0] as Inline.Text).text)
        assertEquals("world",  (runs[1] as Inline.Text).text)
        assertTrue((runs[1] as Inline.Text).style.bold)
        assertEquals(" and ",  (runs[2] as Inline.Text).text)
        assertEquals("foo",    (runs[3] as Inline.Text).text)
        assertTrue((runs[3] as Inline.Text).style.underline)
        assertEquals("baz",    (runs[5] as Inline.Text).text)
        assertTrue((runs[5] as Inline.Text).style.italic)
    }

    @Test fun linkWithLabelUsesBacktickSeparator() {
        // Per Guide.py:1285 — `[labeled link`72914442...:/page/index.mu]
        val (runs, _) = Micron.parseInline("see `[the page`/page/help.mu] for more")
        // Expect: "see ", Link(label="the page", target="/page/help.mu"), " for more"
        assertEquals(3, runs.size)
        val link = runs[1] as Inline.Link
        assertEquals("the page", link.label)
        assertEquals("/page/help.mu", link.target)
    }

    @Test fun linkWithoutLabelLabelsItselfAsTarget() {
        // Per Guide.py:1283 — `[72914442a...:/page/index.mu]
        val (runs, _) = Micron.parseInline("`[/page/about.mu]")
        assertEquals(1, runs.size)
        val link = runs[0] as Inline.Link
        assertEquals("/page/about.mu", link.label)
        assertEquals("/page/about.mu", link.target)
    }

    @Test fun linkWithFormFieldsThirdComponent() {
        // `[Submit`/page/post.mu`username|message]
        val (runs, _) = Micron.parseInline("`[Submit`/page/post.mu`username|message]")
        val link = runs[0] as Inline.Link
        assertEquals("Submit", link.label)
        assertEquals("/page/post.mu", link.target)
        assertEquals(listOf("username", "message"), link.fields)
    }

    @Test fun fgColorEscape3HexAndReset() {
        val (runs, _) = Micron.parseInline("`Ff00bright`f normal")
        val first = runs[0] as Inline.Text
        assertEquals("bright", first.text)
        assertEquals("f00", first.style.fg)
        val second = runs[1] as Inline.Text
        assertEquals(" normal", second.text)
        assertEquals(null, second.style.fg)
    }

    @Test fun fgColorEscape6HexTrueColor() {
        val (runs, _) = Micron.parseInline("`FT3080a0deepblue`f rest")
        val first = runs[0] as Inline.Text
        assertEquals("deepblue", first.text)
        assertEquals("3080a0", first.style.fg)
    }

    @Test fun bgColorEscape() {
        val (runs, _) = Micron.parseInline("`B888shaded`b plain")
        val first = runs[0] as Inline.Text
        assertEquals("shaded", first.text)
        assertEquals("888", first.style.bg)
    }

    @Test fun fullResetClearsAllFormatting() {
        // Two-backtick reset clears bold + italic + colors.
        val (runs, _) = Micron.parseInline("`!`*`Ff00mixed`` plain")
        val first = runs[0] as Inline.Text
        assertEquals("mixed", first.text)
        assertTrue(first.style.bold)
        assertTrue(first.style.italic)
        assertEquals("f00", first.style.fg)
        val second = runs[1] as Inline.Text
        assertEquals(" plain", second.text)
        assertEquals(InlineStyle(), second.style)
    }

    @Test fun alignmentEscapeSetsLineAlign() {
        val (_, align) = Micron.parseInline("`cCenter me")
        assertEquals(Align.CENTER, align)
        val (_, alignR) = Micron.parseInline("`rRight me")
        assertEquals(Align.RIGHT, alignR)
    }

    @Test fun literalBlockPreservesLinesVerbatim() {
        val source = "before\n`=\n  /usr/bin/python3 \\!stuff `[notalink]\n`=\nafter"
        val blocks = Micron.parse(source)
        // Expect: Paragraph "before", Literal with one line, Paragraph "after"
        assertEquals(3, blocks.size)
        val literal = blocks[1] as Block.Literal
        assertEquals(1, literal.lines.size)
        assertEquals("  /usr/bin/python3 \\!stuff `[notalink]", literal.lines[0])
    }

    @Test fun commentLinesAreDropped() {
        val blocks = Micron.parse("""
            >Title

            # this is a comment, should not appear

            Body
        """.trimIndent())
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is Block.Heading)
        val paraText = (blocks[1] as Block.Paragraph).runs.joinToString("") {
            (it as? Inline.Text)?.text ?: ""
        }
        assertEquals("Body", paraText)
    }

    @Test fun cacheHeaderOnFirstLineIsStripped() {
        // Per Guide.py:329 — `#!c=N` on the FIRST line is a browser hint,
        // not content. Only-strip if it's literally the first line.
        val blocks = Micron.parse("#!c=300\n>Hello")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is Block.Heading)
    }

    @Test fun unknownBacktickCommandIsSilentlyDropped() {
        // Upstream behavior: unknown format command consumes both bytes
        // (the backtick + the unknown char) and continues. Don't emit
        // the literal `` `Q `` in output.
        val (runs, _) = Micron.parseInline("plain `Q text")
        val combined = runs.joinToString("") { (it as Inline.Text).text }
        assertEquals("plain  text", combined)
    }

    @Test fun escapedBacktickIsLiteral() {
        // \` produces a literal backtick (does not flip to formatting mode).
        val (runs, _) = Micron.parseInline("price: \\`50/hr")
        val combined = runs.joinToString("") { (it as Inline.Text).text }
        assertEquals("price: `50/hr", combined)
    }

    @Test fun headingsCaptureInlineFormatting() {
        // Per Guide.py:202 — `>>``!Conversations Window`!`
        val blocks = Micron.parse(">>`!Conversations Window`!")
        assertEquals(1, blocks.size)
        val h = blocks[0] as Block.Heading
        assertEquals(2, h.level)
        val title = h.text.first { it is Inline.Text } as Inline.Text
        assertEquals("Conversations Window", title.text)
        assertTrue(title.style.bold)
    }

    // v0.1.50 form fields. Cases ported from upstream MicronParser.py:600-680
    // and the chatroom .mu pages at github.com/fr33n0w/thechatroom .

    @Test fun textFieldEmitsField() {
        val (runs, _) = Micron.parseInline("`<24|message`hello>")
        val field = runs[0] as Inline.Field
        assertEquals("message", field.name)
        assertEquals(FieldType.TEXT, field.type)
        assertEquals(24, field.width)
        assertEquals("hello", field.value)
        assertEquals(false, field.masked)
    }

    @Test fun maskedTextField() {
        val (runs, _) = Micron.parseInline("`<!16|password`>")
        val field = runs[0] as Inline.Field
        assertEquals("password", field.name)
        assertEquals(FieldType.TEXT, field.type)
        assertEquals(16, field.width)
        assertEquals(true, field.masked)
    }

    @Test fun checkboxField() {
        // `<?|opt_in`agree`*>  — value defaults to label "agree", prechecked
        val (runs, _) = Micron.parseInline("`<?|opt_in`agree`yes`*>")
        val field = runs[0] as Inline.Field
        assertEquals("opt_in", field.name)
        assertEquals(FieldType.CHECKBOX, field.type)
        assertEquals("agree", field.label)
        assertEquals("yes", field.value)
        assertEquals(true, field.prechecked)
    }

    @Test fun radioField() {
        val (runs, _) = Micron.parseInline("`<^|color`red`Red`*>")
        val field = runs[0] as Inline.Field
        assertEquals("color", field.name)
        assertEquals(FieldType.RADIO, field.type)
        assertEquals("red", field.value)
        assertEquals("Red", field.label)
        assertEquals(true, field.prechecked)
    }

    @Test fun fieldFollowedByLinkWithSameName() {
        // The chatroom uses this pattern: a text field then a link whose
        // `fields` list names that field.
        val (runs, _) = Micron.parseInline("`<24|msg`> `[Send`/page/post.mu`msg]")
        assertEquals(3, runs.size)
        assertNotNull(runs[0] as? Inline.Field)
        assertEquals("msg", (runs[0] as Inline.Field).name)
        val link = runs[2] as Inline.Link
        assertEquals("/page/post.mu", link.target)
        assertEquals(listOf("msg"), link.fields)
    }

    @Test fun horizontalRuleVariants() {
        assertEquals(Block.HorizontalRule, Micron.parse("---")[0])
        assertEquals(Block.HorizontalRule, Micron.parse("===")[0])
        assertEquals(Block.HorizontalRule, Micron.parse("\\=")[0])  // legacy
        assertEquals(Block.HorizontalRule, Micron.parse("-")[0])
    }
}
