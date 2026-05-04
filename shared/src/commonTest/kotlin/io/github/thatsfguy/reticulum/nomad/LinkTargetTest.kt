package io.github.thatsfguy.reticulum.nomad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [parseLinkTarget] — the dispatcher that turns a micron
 * link's `target` string into a [LinkTarget] the UI can route on.
 *
 * Cases mirror upstream NomadNet `Browser.py` (master, fetched
 * 2026-05-04):
 *   - `expand_shorthands()` at lines 184-189: `nnn` → `nomadnetwork.node`,
 *     `lxmf` → `lxmf.delivery`, anything else stays.
 *   - The `@` separator at lines 248-253 splits the destination type
 *     prefix from the hash + path.
 *   - Bare-hash default at lines 255-259: a hex hash with no type
 *     prefix is interpreted as `nomadnetwork.node` at that hash.
 *   - Path defaulting: a `nomadnetwork.node` link with no `:/path`
 *     suffix uses the node default `/page/index.mu`.
 */
class LinkTargetTest {

    private val hex = "deadbeef0123456789abcdef01234567"  // 32 hex chars (16 bytes)

    @Test fun `same-node absolute path`() {
        assertEquals(LinkTarget.SameNode("/page/index.mu"), parseLinkTarget("/page/index.mu"))
        assertEquals(LinkTarget.SameNode("/page/help.mu"), parseLinkTarget("/page/help.mu"))
        assertEquals(LinkTarget.SameNode("/file/foo.txt"), parseLinkTarget("/file/foo.txt"))
    }

    @Test fun `cross-node hash colon path`() {
        // Browser.py lines 255-259: `<hex>:<path>` is a nomadnetwork.node
        // link with an explicit path.
        assertEquals(
            LinkTarget.CrossNode(hex, "/page/help.mu"),
            parseLinkTarget("$hex:/page/help.mu"),
        )
    }

    @Test fun `cross-node bare hash defaults to nomadnet index path`() {
        // Browser.py:256-259 — bare hash, no path. expand_shorthands defaults
        // to nomadnetwork.node and Browser uses DEFAULT_PATH (/page/index.mu).
        assertEquals(
            LinkTarget.CrossNode(hex, "/page/index.mu"),
            parseLinkTarget(hex),
        )
    }

    @Test fun `nnn shorthand expands to nomadnetwork node link`() {
        // Browser.py:184-186: nnn → nomadnetwork.node.
        assertEquals(
            LinkTarget.CrossNode(hex, "/page/index.mu"),
            parseLinkTarget("nnn@$hex"),
        )
        assertEquals(
            LinkTarget.CrossNode(hex, "/page/about.mu"),
            parseLinkTarget("nnn@$hex:/page/about.mu"),
        )
    }

    @Test fun `lxmf shorthand routes to LXMF dest`() {
        // Browser.py:184-189 + 266-322 — lxmf (or lxmf.delivery) goes to
        // the conversation handler, not a nomadnet page fetch. Phase 1.4
        // surfaces these as a distinct LinkTarget so the UI can show a
        // "open from Messages tab" hint instead of attempting a fetch.
        assertEquals(LinkTarget.Lxmf(hex), parseLinkTarget("lxmf@$hex"))
        assertEquals(LinkTarget.Lxmf(hex), parseLinkTarget("lxmf.delivery@$hex"))
    }

    @Test fun `unknown garbage falls into Unknown bucket`() {
        // Empty target.
        assertTrue(parseLinkTarget("") is LinkTarget.Unknown)
        // Pure relative path with no leading slash — upstream rejects too.
        assertTrue(parseLinkTarget("page/index.mu") is LinkTarget.Unknown)
        // Random text.
        assertTrue(parseLinkTarget("hello world") is LinkTarget.Unknown)
        // Hash with non-hex chars.
        assertTrue(parseLinkTarget("xyzzy0123456789abcdef0123456789ab") is LinkTarget.Unknown)
        // Hash of wrong length (31 chars).
        assertTrue(parseLinkTarget("deadbeef0123456789abcdef0123456") is LinkTarget.Unknown)
        // Unknown shorthand (e.g. someone wrote `web@…` thinking it'd work).
        assertTrue(parseLinkTarget("web@$hex") is LinkTarget.Unknown)
        // Missing hash after shorthand.
        assertTrue(parseLinkTarget("nnn@") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("lxmf@") is LinkTarget.Unknown)
    }

    @Test fun `hash hex case is normalized to lower`() {
        // Real micron pages mix upper / lower hex. Normalize so cache
        // keys and destination-repo lookups don't miss on case.
        val mixed = "DeadBEEF0123456789AbCdEf01234567"
        val target = parseLinkTarget(mixed) as LinkTarget.CrossNode
        assertEquals(hex, target.destHashHex, "destHashHex must be lower-case normalized")
    }

    @Test fun `hash hex with embedded separators is rejected`() {
        // Defense: a target like `dead:beef:0123:…` (32 hex chars but with
        // colons inserted) might look right to a casual eye. Upstream
        // requires plain hex; we don't try to forgive separators because
        // the hash field has well-defined wire encoding (16 raw bytes).
        // Per security findings — never accept input upstream rejects.
        val withSeparators = "dead:beef:0123:4567:89ab:cdef:0123:4567"
        assertTrue(parseLinkTarget(withSeparators) is LinkTarget.Unknown)
    }
}
