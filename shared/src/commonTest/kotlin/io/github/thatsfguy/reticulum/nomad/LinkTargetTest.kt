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

    // v0.1.77 — legacy `:/path` form used by older NomadNet `.mu`
    // pages (carried over from when `:` was the [label]:target
    // separator). Real-world chatroom and wiki pages still emit this
    // form; without the strip every link on those pages failed as
    // Unrecognized.
    @Test fun `legacy leading-colon target is treated as same-node`() {
        assertEquals(
            LinkTarget.SameNode("/page/help.mu"),
            parseLinkTarget(":/page/help.mu"),
        )
        assertEquals(
            LinkTarget.SameNode("/page/index.mu"),
            parseLinkTarget(":/page/index.mu"),
        )
    }

    @Test fun `leading colon without slash-path is still Unknown`() {
        assertTrue(parseLinkTarget(":foo") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget(":") is LinkTarget.Unknown)
    }

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

    // v0.1.60 — security S4: sanitize link targets. The UI passes
    // these straight to fetchNomadPage; a malformed target with NUL /
    // CR / LF / path traversal sequences could create cache aliases
    // for the same destination, confuse the server, or smuggle bytes
    // through naive logging. Reject defensively.

    @Test fun `link target with NUL byte is rejected`() {
        // NUL is a classic "string terminator confusion" smuggle
        // (some path-handling code stops at NUL, the byte after gets
        // ignored or interpreted differently). Always reject.
        assertTrue(parseLinkTarget("/page/foo\u0000.mu") is LinkTarget.Unknown)
    }

    @Test fun `link target with embedded CR or LF is rejected`() {
        // CR/LF in a path is never legitimate. They'd get passed to
        // logs and could fake log lines (CRLF injection).
        assertTrue(parseLinkTarget("/page/foo\rbar.mu") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("/page/foo\nbar.mu") is LinkTarget.Unknown)
    }

    @Test fun `link target with parent-directory traversal is rejected`() {
        // Server-side `Node.py` is supposed to constrain to the pages/
        // directory, but defense in depth: don't even send something
        // with `..` segments. A misconfigured server might honor it.
        assertTrue(parseLinkTarget("/page/../../etc/passwd") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("/..") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("/page/sub/../../etc") is LinkTarget.Unknown)
    }

    @Test fun `link target longer than 256 chars is rejected`() {
        // NomadNet paths are short by convention (`/page/index.mu`).
        // 256 chars is generous (the longest real path in upstream
        // examples is ~30). Anything bigger is either malicious or a
        // copy-paste accident; better to refuse than silently drive a
        // huge path-hash request through the link.
        val longPath = "/" + "a".repeat(300)
        assertTrue(parseLinkTarget(longPath) is LinkTarget.Unknown)
    }

    @Test fun `cross-node link with bad path component is rejected`() {
        val target = "deadbeef0123456789abcdef01234567:/page/../sneaky"
        assertTrue(parseLinkTarget(target) is LinkTarget.Unknown,
            "cross-node target with .. in path must be rejected")
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
