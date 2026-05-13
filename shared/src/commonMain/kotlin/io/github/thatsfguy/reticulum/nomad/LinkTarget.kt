package io.github.thatsfguy.reticulum.nomad

/**
 * Routing target for a micron `[label`url]` link click. Built from the
 * raw `url` string by [parseLinkTarget] — the UI then decides what to
 * do based on the variant.
 *
 * Cases mirror upstream NomadNet `Browser.py` (master fetched 2026-05-04):
 *   - `expand_shorthands()` at lines 184-189 maps `nnn` → `nomadnetwork.node`,
 *     `lxmf` → `lxmf.delivery`.
 *   - The `@` separator at lines 248-253 splits the destination type
 *     prefix from the hash + path.
 *   - Bare-hash default at lines 255-259: a hex hash with no type
 *     prefix is interpreted as `nomadnetwork.node`.
 *   - Path defaulting: a `nomadnetwork.node` link with no `:/path`
 *     suffix uses `/page/index.mu` (Browser.py:67 DEFAULT_PATH).
 */
sealed class LinkTarget {
    /** Same-node navigation: tap moves to a different page on the
     *  currently-selected NomadNet node. The path is taken verbatim. */
    data class SameNode(val path: String) : LinkTarget()

    /** Cross-node navigation: swap the selected destination to
     *  [destHashHex] (32 lower-case hex chars = 16 bytes truncated
     *  identity hash) and load [path] on it. If the destination is
     *  not yet in the local repo, the UI triggers `addManualDestination`
     *  + a path request and waits for the announce. */
    data class CrossNode(val destHashHex: String, val path: String) : LinkTarget()

    /** LXMF link: opens a conversation, not a page fetch. Phase 1.4
     *  surfaces these as a distinct case so the UI can route to the
     *  Messages tab; out of scope for this phase to actually wire up. */
    data class Lxmf(val destHashHex: String) : LinkTarget()

    /** Anything we couldn't parse — empty input, garbage, malformed
     *  hash, unknown shorthand. The UI shows an error rather than
     *  silently no-op'ing (security: never trust input upstream
     *  would reject). */
    data class Unknown(val raw: String) : LinkTarget()
}

private const val DEFAULT_NOMAD_PATH = "/page/index.mu"
private const val HEX_HASH_LEN = 32  // 16 bytes truncated identity hash, hex-encoded
private const val MAX_PATH_LEN = 256  // generous; longest real upstream path is ~30 chars

/**
 * Parse a micron link `target` string into a [LinkTarget].
 *
 * Accepts (case-insensitive on hex):
 *   `/page/index.mu`                          → SameNode
 *   `<32hex>`                                 → CrossNode (default path)
 *   `<32hex>:/page/help.mu`                   → CrossNode
 *   `nnn@<32hex>[:<path>]`                    → CrossNode
 *   `lxmf@<32hex>` / `lxmf.delivery@<32hex>`  → Lxmf
 *
 * Anything else returns [LinkTarget.Unknown]. The hash is normalized
 * to lower case so cache keys / repo lookups don't miss on case.
 *
 * Defense (security S4, v0.1.60): paths are validated against
 * MAX_PATH_LEN, control characters (NUL / CR / LF / TAB / anything
 * < 0x20 / DEL), and `..` traversal segments. A path that fails any
 * check returns Unknown so the UI shows an error instead of silently
 * dispatching.
 */
fun parseLinkTarget(raw: String): LinkTarget {
    if (raw.isEmpty()) return LinkTarget.Unknown(raw)

    // v0.1.77: legacy NomadNet pages write same-node links as `:/path`
    // — a leading `:` carried over from the older `[label]:target`
    // micron syntax (when `:` was the label-target separator, some
    // authors put a stray one in the target itself; the upstream
    // browser silently tolerates it). Strip the leading colon and
    // treat the rest as a same-node path. Without this, every real
    // chatroom / wiki / community-page link in older `.mu` content
    // returns "Unrecognized link" because parseHexAndPath sees an
    // empty hash before the colon.
    if (raw.startsWith(":/")) {
        val stripped = raw.substring(1)
        if (!isPathSafe(stripped)) return LinkTarget.Unknown(raw)
        return LinkTarget.SameNode(stripped)
    }

    // Same-node: leading slash means "path on current destination".
    if (raw.startsWith("/")) {
        if (!isPathSafe(raw)) return LinkTarget.Unknown(raw)
        return LinkTarget.SameNode(raw)
    }

    // Shorthand: `nnn@…` / `lxmf@…` / `lxmf.delivery@…`.
    val atIdx = raw.indexOf('@')
    if (atIdx > 0) {
        val type = raw.substring(0, atIdx)
        val rest = raw.substring(atIdx + 1)
        return when (type) {
            "nnn", "nomadnetwork.node" -> parseHexAndPath(rest, isLxmf = false)
            "lxmf", "lxmf.delivery"    -> parseHexAndPath(rest, isLxmf = true)
            else                        -> LinkTarget.Unknown(raw)
        }
    }

    // Bare hash, optionally with `:/path`.
    return parseHexAndPath(raw, isLxmf = false)
}

private fun parseHexAndPath(rest: String, isLxmf: Boolean): LinkTarget {
    if (rest.isEmpty()) return LinkTarget.Unknown(rest)

    // Split on the FIRST `:` — anything after is the path.
    val colon = rest.indexOf(':')
    val hashPart = if (colon < 0) rest else rest.substring(0, colon)
    val pathPart = if (colon < 0) DEFAULT_NOMAD_PATH else rest.substring(colon + 1)

    if (!isValidHashHex(hashPart)) return LinkTarget.Unknown(rest)
    val normalized = hashPart.lowercase()

    if (isLxmf) {
        // LXMF links don't carry a path — even if upstream had one,
        // it'd be ignored by the conversation handler.
        return LinkTarget.Lxmf(normalized)
    }
    if (pathPart.isEmpty() || !pathPart.startsWith("/")) {
        // `<hex>:nopath` — upstream Browser.py would treat as no path,
        // i.e. fall back to /page/index.mu. We're stricter: a colon
        // followed by an unanchored path is malformed input.
        return LinkTarget.Unknown(rest)
    }
    if (!isPathSafe(pathPart)) return LinkTarget.Unknown(rest)
    return LinkTarget.CrossNode(normalized, pathPart)
}

/**
 * Path-safety gate (security S4). Reject:
 *   - over MAX_PATH_LEN chars (longest real upstream path is ~30)
 *   - any control char (< 0x20) or DEL (0x7F) — NUL is a
 *     string-terminator-confusion smuggle, CR / LF would be
 *     CRLF-injection in logs or could fake response framing on any
 *     text pass-through, TAB / VT / FF have no place in a path
 *   - `..` as its own segment, or `/../` anywhere — defense in depth
 *     against a misconfigured server that fails to constrain to
 *     pages/.
 */
private fun isPathSafe(path: String): Boolean {
    if (path.length > MAX_PATH_LEN) return false
    if (path.any { it.code < 0x20 || it.code == 0x7F }) return false
    val segments = path.split('/')
    if (segments.any { it == ".." }) return false
    return true
}

private fun isValidHashHex(s: String): Boolean {
    if (s.length != HEX_HASH_LEN) return false
    return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

/**
 * Resolve the page path a form-submit link should land on, given the
 * page's [currentPath] and the link's raw [target].
 *
 * Form-submit links share micron's link syntax — they can use absolute
 * `/page/x.mu`, the legacy `:/page/x.mu`, or be empty/`:` meaning
 * "submit to the current page" (upstream Browser.py:198-241 treats an
 * empty/colon-only target as a self-submit). Without normalizing here,
 * the NomadScreen form handler used to only honor `/path` and silently
 * dropped `:/path`, which broke every same-node POST on real
 * NomadNet pages (e.g. 0chan's `[Open`:/page/board/t.mu`tid=N]`
 * thread-open links: the POST went to the current board page instead
 * of the thread page).
 *
 * Returns [currentPath] when the target parses to anything but a
 * same-node path (Unknown / Lxmf / CrossNode are all out of scope for
 * the in-screen form-submit flow — cross-node POSTs would need the
 * full re-resolve path and aren't observed on real pages).
 */
fun resolveSubmitPath(currentPath: String, target: String): String {
    val parsed = parseLinkTarget(target)
    return if (parsed is LinkTarget.SameNode) parsed.path else currentPath
}
