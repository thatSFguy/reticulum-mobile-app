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
 * Defense: separators in the hash (e.g. `dead:beef:…`) are NOT
 * accepted — upstream wire encoding is plain bytes, accepting
 * forgiving variants here would let malformed pages create cache /
 * routing aliases for the same destination.
 */
fun parseLinkTarget(raw: String): LinkTarget {
    if (raw.isEmpty()) return LinkTarget.Unknown(raw)

    // Same-node: leading slash means "path on current destination".
    if (raw.startsWith("/")) return LinkTarget.SameNode(raw)

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
    return LinkTarget.CrossNode(normalized, pathPart)
}

private fun isValidHashHex(s: String): Boolean {
    if (s.length != HEX_HASH_LEN) return false
    return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
