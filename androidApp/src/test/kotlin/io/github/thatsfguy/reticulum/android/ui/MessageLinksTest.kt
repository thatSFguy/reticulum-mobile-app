package io.github.thatsfguy.reticulum.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit 2026-09-02 L2: the message-body linkifier must not carry its own
 * weaker copy of the link grammar.
 *
 * `parseNomadShareLink` used to re-derive `(hash, path)` from the regex
 * match by hand, checking the hash and the leading `/` and nothing else.
 * That skipped `isPathSafe` (security gate S4) entirely — the match
 * pattern excludes whitespace but permits every other control character,
 * and permits `..` segments freely. So a link in a DM or a room line
 * could render as tappable and dispatch a path that the shared parser,
 * which every other surface uses, would have rejected.
 *
 * These tests pin the delegation rather than re-testing the gate:
 * `LinkTargetTest` owns the gate's own cases. What matters here is that
 * this function stays a thin pass-through, so the two cannot drift apart
 * again. Mutation check: restoring the hand-rolled decomposition turns
 * every "refused" case below red.
 */
class MessageLinksTest {

    private val hex = "deadbeef0123456789abcdef01234567"

    @Test fun `a well-formed cross-node link still parses`() {
        assertEquals(hex to "/page/help.mu", parseNomadShareLink("$hex:/page/help.mu"))
        assertEquals(hex to "/page/index.mu", parseNomadShareLink("nnn@$hex"))
        assertEquals(hex to "/page/a.mu", parseNomadShareLink("nnn@$hex:/page/a.mu"))
    }

    @Test fun `the hash is normalised to lower case`() {
        assertEquals(hex to "/page/a.mu", parseNomadShareLink("${hex.uppercase()}:/page/a.mu"))
    }

    // ---- the gate this used to skip -------------------------------------

    @Test fun `a traversal path is refused`() {
        assertNull(parseNomadShareLink("$hex:/page/../../etc/passwd"))
        assertNull(parseNomadShareLink("$hex:/.."))
        assertNull(parseNomadShareLink("$hex:/page/sub/../../etc"))
    }

    @Test fun `a path carrying control characters is refused`() {
        // NUL is a string-terminator smuggle; CR / LF are injection into
        // anything that logs or frames the path as text; DEL has no
        // business in a path at all. Written as escapes, never as raw
        // literals, so the source stays readable and greppable.
        assertNull(parseNomadShareLink("$hex:/page/a\u0000.mu"), "NUL")
        assertNull(parseNomadShareLink("$hex:/page/a\r\n.mu"), "CRLF")
        assertNull(parseNomadShareLink("$hex:/page/a\u0007.mu"), "BEL")
        assertNull(parseNomadShareLink("$hex:/page/a\u007F.mu"), "DEL")
    }

    /** A space is 0x20, not a control character, and the shared gate
     *  allows it deliberately. Pinned so that tightening it later is a
     *  decision rather than an accident. */
    @Test fun `a space in a path is allowed`() {
        assertEquals(hex to "/page/a b.mu", parseNomadShareLink("$hex:/page/a b.mu"))
    }

    @Test fun `an over-long path is refused`() {
        assertNull(parseNomadShareLink("$hex:/" + "a".repeat(300)))
    }

    @Test fun `a malformed hash is refused`() {
        assertNull(parseNomadShareLink("nnn@${hex.dropLast(1)}"))
        assertNull(parseNomadShareLink("nnn@${hex}ff"))
        assertNull(parseNomadShareLink("zz${hex.drop(2)}:/page/a.mu"))
    }

    @Test fun `an unanchored path is refused`() {
        assertNull(parseNomadShareLink("$hex:page/a.mu"))
    }

    // ---- display bounding for the confirmation dialogs -------------------

    /**
     * The confirmation dialog is where the user decides whether to leave
     * the mesh, so the string it shows them is the one thing that must
     * not be able to lie. A bidi override reorders the rendered text and
     * can make a URL read as a host it does not point at.
     */
    @Test fun `format characters are stripped from a displayed url`() {
        val spoofed = "https://example.com\u202E/gnp.evil"
        assertTrue('\u202E' !in displayableExternalUrl(spoofed))
        assertEquals("https://example.com/gnp.evil", displayableExternalUrl(spoofed))
    }

    @Test fun `a short url is shown whole`() {
        val url = "https://example.com/a/b?c=d"
        assertEquals(url, displayableExternalUrl(url))
    }

    /** An unbounded URL pushes the dialog's buttons off screen. Keep the
     *  head — scheme and host, the part that decides the answer. */
    @Test fun `an absurd url is elided in the middle`() {
        val url = "https://example.com/" + "a".repeat(5_000)
        val shown = displayableExternalUrl(url)

        assertTrue(shown.length <= 120, "was ${shown.length}")
        assertTrue(shown.startsWith("https://example.com/"), "host must survive: $shown")
        assertTrue('…' in shown, "elision must be visible")
    }
}
