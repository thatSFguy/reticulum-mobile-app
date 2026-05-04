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

            -

            Another paragraph.
        """.trimIndent())

        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is Block.Heading)
        assertEquals(1, (blocks[0] as Block.Heading).level)
        assertTrue(blocks[1] is Block.Paragraph)
        val paraText = (blocks[1] as Block.Paragraph).runs.joinToString("") {
            (it as? Inline.Text)?.text ?: ""
        }
        assertEquals("This is a body paragraph\nspanning two lines.", paraText)
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

    // v0.1.57 — checkbox/radio layout fixed to match upstream
    // (audit BLOCKER F2). Real syntax per MicronParser.py:617-687
    // (master fetched 2026-05-04):
    //
    //   `<flags|name|value|*`label>
    //
    // Left of the backtick: pipe-separated flags|name|value|*
    //   (4 components for checkbox/radio; * is the prechecked marker).
    // Right of the backtick: the label.
    //
    // Pre-v0.1.57 we had value/label/prechecked on the right of the
    // backtick (backtick-separated) which doesn't match upstream and
    // would break on every real chatroom .mu page. Tests are updated
    // to the correct upstream syntax; the parser changes accordingly.
    @Test fun checkboxField() {
        val (runs, _) = Micron.parseInline("`<?|opt_in|yes|*`Agree to terms>")
        val field = runs[0] as Inline.Field
        assertEquals("opt_in", field.name)
        assertEquals(FieldType.CHECKBOX, field.type)
        assertEquals("Agree to terms", field.label)
        assertEquals("yes", field.value)
        assertEquals(true, field.prechecked)
    }

    @Test fun checkboxFieldUncheckedDefaultsValueToLabel() {
        // Per MicronParser.py:672 — `field_value if field_value else field_data`.
        // If the left-side value is empty, the label doubles as the
        // submit value. Common shorthand for booleans: `<?|subscribe||`Subscribe>
        // means name=subscribe, value="Subscribe" (the label), prechecked=false.
        val (runs, _) = Micron.parseInline("`<?|subscribe`Subscribe>")
        val field = runs[0] as Inline.Field
        assertEquals("subscribe", field.name)
        assertEquals(FieldType.CHECKBOX, field.type)
        assertEquals("Subscribe", field.label)
        assertEquals("Subscribe", field.value)
        assertEquals(false, field.prechecked)
    }

    @Test fun radioField() {
        val (runs, _) = Micron.parseInline("`<^|color|red|*`Red>")
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

    // v0.1.58 — HR detection cleaned up to match upstream
    // (MicronParser.py:266-273, master fetched 2026-05-04). Upstream
    // accepts ONLY:
    //   `-`     → HR with default rune U+2500 ─
    //   `-X`    → HR with rune X (any printable char; control chars
    //             fall back to U+2500)
    // Pre-v0.1.58 we also matched `---`, `===`, and `\=` as HRs —
    // those are upstream-literal text. Author who writes `===` as a
    // section divider intended that text, not a thin line.
    @Test fun horizontalRuleSingleDashIsHr() {
        val block = Micron.parse("-")[0]
        assertTrue(block is Block.HorizontalRule)
        assertEquals('─', (block as Block.HorizontalRule).rune)
    }

    @Test fun horizontalRuleWithCustomRune() {
        // Per MicronParser.py:268-271 — second char is the divider rune.
        val block = Micron.parse("-═")[0]
        assertTrue(block is Block.HorizontalRule)
        assertEquals('═', (block as Block.HorizontalRule).rune)
    }

    @Test fun horizontalRuleControlCharRuneFallsBackToDefault() {
        // Per MicronParser.py:269-270 — control chars (< 32) fall back to U+2500.
        val block = Micron.parse("-")[0]  // BEL
        assertTrue(block is Block.HorizontalRule)
        assertEquals('─', (block as Block.HorizontalRule).rune)
    }

    // v0.1.59 — block-level parser matches MicronParser.py more faithfully.

    // v0.1.60 — security S3: validate form-field names. Server-side
    // these become env-var keys for the page handler, prefixed with
    // `field_` / `var_`. A malicious page declaring a field named
    // `\nLD_PRELOAD=x.so` or `; rm -rf` would smuggle bytes through
    // any handler that mishandles env-var keys (or that builds
    // shell-style strings from the dict). Reject any name containing
    // chars outside `[A-Za-z0-9_-]` so only well-formed keys reach
    // the wire.
    @Test fun fieldWithInvalidNameCharsIsDropped() {
        // Newline in name → silently drop the field (no Inline.Field
        // emitted; the surrounding text continues unchanged).
        val (runs, _) = Micron.parseInline("before `<24|bad\nname`hi> after")
        assertTrue(
            runs.none { it is Inline.Field },
            "field with newline in name must be dropped, not emitted",
        )
    }

    @Test fun fieldWithSpecialCharsInNameIsDropped() {
        // Semicolons / spaces / quotes — anything that'd be unsafe as
        // an env-var key.
        for (badName in listOf("name with space", "a;b", "a\"b", "a/b", "a\$b")) {
            val (runs, _) = Micron.parseInline("`<24|$badName`v>")
            assertTrue(runs.none { it is Inline.Field }, "field name '$badName' must be rejected")
        }
    }

    @Test fun fieldWithValidNameIsAccepted() {
        // Letters, digits, underscore, hyphen all allowed — covers
        // every real field name in upstream NomadNet examples.
        for (goodName in listOf("message", "user_id", "opt-in", "field42")) {
            val (runs, _) = Micron.parseInline("`<24|$goodName`v>")
            val field = runs.firstOrNull { it is Inline.Field } as? Inline.Field
            assertNotNull(field, "field name '$goodName' should be accepted")
            assertEquals(goodName, field.name)
        }
    }

    @Test fun fieldWithEmptyNameIsDropped() {
        val (runs, _) = Micron.parseInline("`<24|`v>")
        assertTrue(runs.none { it is Inline.Field }, "empty field name must be rejected")
    }

    @Test fun perLineBackslashEscapeStripsLeadingBackslash() {
        // Per MicronParser.py:185-187 — a line starting with `\` strips
        // the backslash and treats the rest as text. Without this, an
        // author who writes `\>not a heading` ends up with a real
        // heading; `\#literal hash` gets dropped as a comment.
        val blocks = Micron.parse("\\>not a heading")
        assertEquals(1, blocks.size)
        val para = blocks[0] as Block.Paragraph
        assertEquals(">not a heading", para.runs.joinToString("") { (it as Inline.Text).text })

        val blocks2 = Micron.parse("\\#actually visible")
        assertEquals(1, blocks2.size)
        assertTrue(blocks2[0] is Block.Paragraph,
            "leading-backslash + # should NOT be dropped as a comment")
    }

    @Test fun headingWithFormFieldIsDemotedToParagraph() {
        // Per MicronParser.py:179-182 — a `>`-line containing `` `< ``
        // (a form field) gets demoted to a normal line so the field
        // widget doesn't render with heading typography. Upstream
        // strips the leading `>`s and re-parses.
        val blocks = Micron.parse(">`<24|name`>")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is Block.Paragraph,
            "heading line with form field should be demoted; got ${blocks[0]::class.simpleName}")
    }

    @Test fun commentLineInsideLiteralBlockIsPreserved() {
        // Pre-v0.1.59 we dropped any `#`-prefixed line unconditionally,
        // even inside `\`= ... `\`= literal blocks. That ate authors'
        // shell snippets. Per MicronParser.py:177 the comment check is
        // gated on `not state["literal"]`.
        val src = "before\n`=\n#!/usr/bin/env bash\necho hi\n`=\nafter"
        val blocks = Micron.parse(src)
        val literal = blocks.first { it is Block.Literal } as Block.Literal
        assertEquals(2, literal.lines.size,
            "literal block should preserve both shell lines including the # one")
        assertEquals("#!/usr/bin/env bash", literal.lines[0])
    }

    @Test fun paragraphSourceLineBreaksArePreserved() {
        // Per MicronParser.py:82-93 each source line of a paragraph
        // becomes its own urwid.Text widget — i.e. line breaks are
        // hard. Pre-v0.1.59 we joined with spaces and lost the
        // author's wrap. Concatenate with `\n` so renderers (Compose
        // Text auto-soft-wraps inside each segment) honor the breaks.
        val blocks = Micron.parse("first line\nsecond line")
        val para = blocks[0] as Block.Paragraph
        val joined = para.runs.joinToString("") { (it as Inline.Text).text }
        assertEquals("first line\nsecond line", joined,
            "paragraph source line breaks must be preserved as `\\n`, not collapsed to spaces")
    }

    @Test fun multipleDashesAreLiteralText() {
        // Pre-v0.1.58 we mis-classified --- and === as HRs; upstream
        // does not. They render as plain paragraph text.
        val blocks = Micron.parse("---")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is Block.Paragraph,
            "`---` is upstream-literal text, not an HR; got ${blocks[0]::class.simpleName}")
    }

    @Test fun equalsLineIsLiteralText() {
        val blocks = Micron.parse("===")
        assertTrue(blocks[0] is Block.Paragraph)
    }
}
