package io.github.thatsfguy.reticulum.nomad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conformance pass over [NOMADNET_MARKUP_GUIDE] — NomadNet's own micron
 * documentation, written in micron.
 *
 * The value of this fixture is that nobody wrote it for us. Our other
 * micron tests assert the behaviour we set out to implement, which makes
 * them blind in exactly the place a port goes wrong: a command we never
 * knew existed produces no failing test, it just leaks into the page as
 * literal backticks. This document uses the whole language, so anything
 * we do not handle shows up here as garbage in the rendered text.
 */
class MicronGuideConformanceTest {

    private val doc = Micron.parseDocument(NOMADNET_MARKUP_GUIDE)

    private fun runsOf(block: Block): List<Inline> = when (block) {
        is Block.Heading -> block.text
        is Block.Paragraph -> block.runs
        is Block.Table -> (listOf(block.header) + block.rows).flatten().flatten()
        else -> emptyList()
    }

    private fun renderedText(block: Block): String =
        runsOf(block).joinToString("") {
            when (it) {
                is Inline.Text -> it.text
                is Inline.Link -> it.label
                is Inline.Field -> ""
            }
        }

    @Test fun parsesIntoAPlausibleDocument() {
        assertTrue(doc.blocks.size > 100, "guide parsed to only ${doc.blocks.size} blocks")
        assertTrue(doc.headingBlocks.size > 5, "expected the guide's section headings")
    }

    /**
     * The catch-all: a backtick that survives into rendered text is
     * either one the author escaped, or a format command we failed to
     * recognise and passed through as text. So count them.
     *
     * Every `` \` `` in the source is one rendered backtick; every
     * rendered backtick should have such an escape behind it. If those
     * two numbers disagree, some command is leaking. Literal blocks are
     * exempt on both sides — showing markup verbatim is what they are
     * for, and a document about micron is full of them.
     *
     * A regex over the rendered text cannot do this job, because this
     * particular document writes things like "use the tag \`c to center
     * text" in its prose, and that IS supposed to render as "`c".
     */
    @Test fun noFormatCommandLeaksIntoRenderedText() {
        val literalLines = doc.blocks.filterIsInstance<Block.Literal>().flatMap { it.lines }
        fun escapes(text: String) = Regex("""\\`""").findAll(text).count()
        val expected = escapes(NOMADNET_MARKUP_GUIDE) - literalLines.sumOf { escapes(it) }
        val rendered = doc.blocks
            .filter { it !is Block.Literal }
            .sumOf { block -> renderedText(block).count { it == '`' } }
        assertEquals(
            expected, rendered,
            "backticks in rendered text should be exactly the escaped ones — " +
                "a mismatch means an unrecognised command leaked through as text",
        )
    }

    @Test fun sectionDepthProducesIndentedBlocks() {
        // The guide nests sections; if depth tracking regressed to flat,
        // every block would be at indent 0.
        assertTrue(doc.blocks.any { it.indent > 0 }, "no block inherited a section indent")
    }

    /**
     * Every anchor link in the guide's table of contents should resolve
     * to an anchor in the document — by an explicit `` `: `` declaration
     * or a heading's auto-slug. This is the test that would catch
     * `slugifyMicron` drifting from upstream's `slugify_micron`, since
     * the guide links its own headings by slug.
     *
     * [UPSTREAM_DANGLING_ANCHORS] is upstream's bug, not ours: the
     * contents list links `#closing-remarks` and the section it points
     * at is not in the document. NomadNet renders that tap as "Unknown
     * anchor", exactly as we do.
     */
    @Test fun anchorsAndTheirLinksAgree() {
        val anchorTargets = doc.blocks
            .flatMap { runsOf(it) }
            .filterIsInstance<Inline.Link>()
            .map { it.target }
            .filter { it.startsWith("#") && it.length > 1 }
            .map { it.substring(1) }
            .toSet()
        assertTrue(anchorTargets.isNotEmpty(), "guide's table of contents produced no anchor links")
        val missing = anchorTargets - doc.anchors.keys
        assertEquals(
            UPSTREAM_DANGLING_ANCHORS, missing,
            "anchor links with no matching anchor",
        )
    }

    private companion object {
        /** See [anchorsAndTheirLinksAgree]. */
        val UPSTREAM_DANGLING_ANCHORS = setOf("closing-remarks")
    }

    @Test fun theLanguageFeaturesTheGuideDemonstratesAllSurvive() {
        val runs = doc.blocks.flatMap { runsOf(it) }
        assertTrue(runs.filterIsInstance<Inline.Text>().any { it.style.bold }, "no bold")
        assertTrue(runs.filterIsInstance<Inline.Text>().any { it.style.italic }, "no italic")
        assertTrue(runs.filterIsInstance<Inline.Text>().any { it.style.underline }, "no underline")
        assertTrue(runs.filterIsInstance<Inline.Text>().any { it.style.fg != null }, "no foreground colour")
        assertTrue(runs.filterIsInstance<Inline.Text>().any { it.style.bg != null }, "no background colour")
        assertTrue(runs.filterIsInstance<Inline.Link>().isNotEmpty(), "no links")
        assertTrue(doc.blocks.any { it is Block.Literal }, "no literal blocks")
        assertTrue(doc.blocks.any { it is Block.HorizontalRule }, "no dividers")
        assertTrue(doc.blocks.any { it is Block.Heading }, "no headings")
        assertTrue(
            doc.blocks.any { b -> b is Block.HorizontalRule && b.rune != '─' },
            "no custom divider rune — the guide draws one with ∿",
        )
        assertTrue(
            doc.blocks.any { b -> runsOf(b).any { it is Inline.Field } },
            "no form fields",
        )
        assertTrue(
            doc.blocks.any { b -> b is Block.Heading && b.align == Align.CENTER } ||
                doc.blocks.any { b -> b is Block.Paragraph && b.align == Align.CENTER },
            "no centred content — the guide centres its banner",
        )
    }
}
