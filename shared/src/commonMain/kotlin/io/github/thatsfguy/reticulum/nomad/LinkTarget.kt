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

    /**
     * An RRC room link — `rrc://<32hex>/<room>`
     * (`rrc-room-links.md` v2, NomadNet's grammar). [room] is already
     * run through `normalizeRrcRoom`, so it is the name `JOIN` takes.
     *
     * v1 of this format was `rrc@<32hex>:/room/<percent-encoded>`,
     * built on the reasoning that an `rrc://` scheme would be an
     * invention in an ecosystem that already had a convention. The
     * reasoning was sound and the fact was wrong: NomadNet 1.2.8 had
     * shipped `rrc://` five weeks earlier and had already claimed the
     * `rrc@` shorthand for a different payload grammar. v1 links do not
     * fail against it — they resolve the hub and come out with a room
     * named `room/<x>`, which a hub happily creates. v2 is upstream's
     * grammar; v1 is still read, because those links exist.
     */
    data class RrcRoom(val hubDestHashHex: String, val room: String) : LinkTarget()

    /** A hub with no room — `rrc://<32hex>`. Names a hub to connect to,
     *  nothing to join (§3: "A link with no path names a hub only"). */
    data class RrcHub(val hubDestHashHex: String) : LinkTarget()

    /**
     * In-document anchor jump — `#name`, or a bare `#`.
     *
     * `Browser.py:271-275`: the anchor check runs BEFORE any
     * destination parsing and before any form data is sent, so an
     * anchor link never leaves the device. [name] is empty for a bare
     * `#`, which upstream reads as "scroll to the next heading below
     * the current position" (`Browser.py:337-348`).
     */
    data class Anchor(val name: String) : LinkTarget()

    /**
     * Partial-refresh link — `p:<id>[:<id>…]` (`Browser.py:288-291`).
     * Re-fetches just the `` `{…`pid=<id>} `` placeholders named by
     * [ids], leaving the rest of the page as it is. Like [Anchor] this
     * is a local action: no navigation, no history entry.
     */
    data class PartialRefresh(val ids: List<String>) : LinkTarget()

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
 *   `rrc://<32hex>[/<room>]`                  → RrcHub / RrcRoom
 *   `rrc@<32hex>[/<room>]`                    → RrcHub / RrcRoom
 *   `rrc@<32hex>:/room/<name>`                → RrcRoom (v1, read-only)
 *   `#section-slug` / `#`                     → Anchor
 *   `p:<id>[:<id>…]`                          → PartialRefresh
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

    // In-document anchor. Checked first, exactly as upstream does
    // (Browser.py:271-275) — before destination parsing and before any
    // form data would be collected, so `#top` never opens a link.
    if (raw.startsWith("#")) {
        val name = raw.substring(1)
        if (!name.all { isAnchorNameChar(it) }) return LinkTarget.Unknown(raw)
        return LinkTarget.Anchor(name)
    }

    // RRC room link in URL form. Checked before the `@` split, exactly
    // where `Browser.py:277-280` checks it — which is what makes a room
    // name containing `@` work in this form and not in the shorthand
    // one (upstream's `link_target.split("@")` gives three components
    // and falls through to the page fetcher). Case-sensitive to match
    // upstream: a link this client accepts and NomadNet rejects is the
    // divergence v2 exists to end.
    if (raw.startsWith(io.github.thatsfguy.reticulum.rrc.RrcRoomLink.URL_SCHEME)) {
        return parseRrcLink(raw.substring(io.github.thatsfguy.reticulum.rrc.RrcRoomLink.URL_SCHEME.length))
    }

    // Partial refresh — `p:<id>[:<id>…]` (Browser.py:288-291).
    if (raw.startsWith("p:")) {
        val ids = raw.split(':').drop(1).filter { it.isNotEmpty() }
        if (ids.isEmpty() || ids.any { id -> !id.all { isAnchorNameChar(it) } }) {
            return LinkTarget.Unknown(raw)
        }
        return LinkTarget.PartialRefresh(ids)
    }

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
            // `expand_shorthands` (Browser.py:206-214) maps `rrc` to
            // the dispatch label `rrc.hub.session`; `rrc.hub` is the
            // destination aspect itself. Accept all three spellings —
            // they name one thing.
            "rrc", "rrc.hub", "rrc.hub.session" -> parseRrcLink(rest)
            else                        -> LinkTarget.Unknown(raw)
        }
    }

    // Bare hash, optionally with `:/path`.
    return parseHexAndPath(raw, isLxmf = false)
}

/**
 * Parse an RRC link payload — everything after `rrc://` or after the
 * `rrc@` / `rrc.hub@` / `rrc.hub.session@` shorthand.
 *
 * The grammar and its two narrowings live in [RrcRoomLink.parsePayload];
 * this function only turns the result into the right [LinkTarget] case.
 * A payload with no room names a hub and nothing to join, which is a
 * legitimate link, not a malformed one.
 */
private fun parseRrcLink(rest: String): LinkTarget {
    val parsed = io.github.thatsfguy.reticulum.rrc.RrcRoomLink.parsePayload(rest)
        ?: return LinkTarget.Unknown(rest)
    return if (parsed.room.isEmpty()) {
        LinkTarget.RrcHub(parsed.hubDestHashHex)
    } else {
        LinkTarget.RrcRoom(parsed.hubDestHashHex, parsed.room)
    }
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
 * Resolved destination for a form-submit link. Form targets share
 * micron's link syntax (parsed by [parseLinkTarget]) but the
 * NomadScreen form handler has to dispatch on the *kind* of target,
 * not just the path: a same-node target only needs a path swap, a
 * cross-node target needs a full dest swap + POST against the new
 * link.
 *
 * - [SameNode] — submit POST to [path] on the currently-selected
 *   destination. Covers absolute `/page/x.mu` and the legacy
 *   `:/page/x.mu` form.
 * - [CrossNode] — submit POST to [path] on a *different*
 *   destination identified by [destHashHex]. MeshChat's
 *   `<32hex>:/page/q.mu` form targets, and NomadSearch's
 *   self-referential `<own-hex>:/page/q.mu` Run-search links
 *   (which our pre-v1.2.17 code treated as a no-op same-page
 *   refresh). Handler must resolve / path-discover the dest,
 *   swap `selected`, update history, then POST.
 * - [Self] — empty / `:` / unparseable / `lxmf@…` target: treat
 *   as a self-submit (POST to [currentPath] on the current dest,
 *   no nav change). Browser.py:198-241 self-submit semantics.
 *
 * Replaces the prior `resolveSubmitPath` which returned a path
 * string and silently coerced cross-node submits into self-submits.
 */
sealed class FormSubmitTarget {
    data class SameNode(val path: String) : FormSubmitTarget()
    data class CrossNode(val destHashHex: String, val path: String) : FormSubmitTarget()
    object Self : FormSubmitTarget()
}

/**
 * Dispatch a form-submit link [target] (relative to a page on
 * [currentPath]) into a [FormSubmitTarget]. Cross-node targets are
 * promoted out of the silent-drop fallback the prior
 * `resolveSubmitPath` had — MeshChat and the NomadSearch reference
 * service emit them, and our pre-v1.2.17 code silently re-submitted
 * against the current page instead of following the cross-node hop.
 *
 * Self-submit (the [FormSubmitTarget.Self] case) is used for an
 * empty target, a bare `:` (legacy upstream convention), and for
 * anything that doesn't parse to a same-node / cross-node link.
 */
fun parseFormSubmitTarget(currentPath: String, target: String): FormSubmitTarget {
    return when (val parsed = parseLinkTarget(target)) {
        is LinkTarget.SameNode -> FormSubmitTarget.SameNode(parsed.path)
        is LinkTarget.CrossNode -> FormSubmitTarget.CrossNode(parsed.destHashHex, parsed.path)
        else -> FormSubmitTarget.Self
    }
}

/**
 * Build the `data` dict a form-submit link ships as REQUEST envelope
 * element [2] (SPEC §11.6.2). [linkFields] is the third backtick
 * component of the micron link — `` `[Login`:/page/login.mu`action=submit|*] ``
 * parses to `["action=submit", "*"]`. [fieldValues] is the renderer's
 * live widget state, keyed by field name; per `Browser.py:246-266` a
 * checkbox / radio that isn't selected must be ABSENT from that map
 * (and therefore from the result) rather than present as `""`, because
 * server handlers test `if "field_x" in env`.
 *
 * Each entry of [linkFields] is one of:
 *
 *   `*`           → **all fields**: every widget on the page is
 *                   included, not just the named ones. `Browser.py:222`
 *                   → `all_fields = True if "*" in link_data else False`,
 *                   then `:246` `if hasattr(w, "field_name") and
 *                   (all_fields or w.field_name in link_fields)`.
 *   `key=value`   → URL-query-style param, becomes `var_<key>`
 *                   (`Browser.py:223-228`; `Node.py:109-111` exports it
 *                   as an env var).
 *   `<name>`      → widget reference, becomes `field_<name>` with the
 *                   value from [fieldValues]; omitted when absent.
 *
 * The `*` case is why real NomadNet login / post forms did nothing in
 * this client through 1.2.113: `comboard`'s login page submits with
 * `` `action=submit|* `` and names no widget explicitly, so we posted
 * `var_action=submit` and not one character of what the user typed.
 * Reported against 1.2.113 ("can't login using reticulum mobile", works
 * in Columba) and reproduced against the live page
 * `11fe815b744fb97fd47ffc3fe6b4c703:/page/comboard/login.mu`.
 */
fun buildFormSubmitData(
    linkFields: List<String>,
    fieldValues: Map<String, String>,
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val allFields = linkFields.any { it == "*" }
    if (allFields) {
        for ((name, value) in fieldValues) out["field_$name"] = value
    }
    for (entry in linkFields) {
        if (entry == "*") continue
        val eq = entry.indexOf('=')
        if (eq > 0) {
            out["var_" + entry.substring(0, eq)] = entry.substring(eq + 1)
        } else {
            val v = fieldValues[entry] ?: continue
            out["field_$entry"] = v
        }
    }
    return out
}

/**
 * Is the checkbox carrying [value] currently selected, given the field's
 * accumulated map entry [current]?
 *
 * Several checkboxes may share one field name — that is how micron
 * expresses a multi-select — so the map entry holds a comma-joined list
 * of the selected values rather than a single one. See
 * [toggleCheckboxValue] for why the joined form is what we store.
 */
fun isCheckboxChecked(current: String?, value: String): Boolean {
    if (current == null) return false
    return current.split(',').any { it == value }
}

/**
 * Apply a checkbox tap to the field's map entry. Returns the new entry,
 * or `null` when the key must be REMOVED from the map entirely.
 *
 * `Browser.py:255-266`:
 *
 * ```python
 * user_data = getattr(w, "field_value", "1")
 * if w.state:
 *     existing_value = request_data.get(field_key, '')
 *     if existing_value: request_data[field_key] = existing_value + ',' + user_data
 *     else:              request_data[field_key] = user_data
 * else:
 *     pass  # do nothing if checkbox is not check
 * ```
 *
 * Two behaviours fall out of that and both matter on the wire:
 *
 *  - **Multi-select comma-joins.** Three boxes named `topics` with
 *    values `weather`, `radio`, `power`, two of them ticked, submit as
 *    `{"field_topics": "weather,radio"}` — one key, not three, and not
 *    last-write-wins. Storing the joined string directly (rather than a
 *    per-widget map) is what lets [buildFormSubmitData] stay a plain
 *    `Map<String, String>` and still produce the upstream wire value.
 *  - **Unticking removes the key** once nothing is left selected, never
 *    leaves `""` behind, because server handlers test
 *    `if "field_topics" in env`.
 *
 * A value containing a literal comma is ambiguous under this encoding —
 * it is equally ambiguous upstream, which joins just as blindly, so a
 * page that wants distinguishable values must not put commas in them.
 */
fun toggleCheckboxValue(current: String?, value: String, checked: Boolean): String? {
    val selected = current?.split(',')?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
    if (checked) {
        if (value !in selected) selected += value
    } else {
        selected.remove(value)
    }
    return if (selected.isEmpty()) null else selected.joinToString(",")
}
