package io.github.thatsfguy.reticulum.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import io.github.thatsfguy.reticulum.nomad.LinkTarget
import io.github.thatsfguy.reticulum.nomad.parseLinkTarget

/**
 * Turning link-shaped substrings of a message body into tappable spans.
 *
 * Lives here rather than inside one screen because BOTH message
 * surfaces need it and they must behave identically: an LXMF direct
 * message and an RRC room line are both text somebody else wrote, and a
 * link should not be tappable in one and inert in the other. (It was:
 * Rooms rendered a bare `Text(msg.text)` until 2026-08-31.)
 *
 * Three kinds are recognised, and the difference between them is the
 * whole point:
 *
 *  - **`rrc@<32hex>:/room/<name>`** — an RRC room link
 *    (`rrc-room-links.md`). Stays inside the mesh; tapping joins.
 *  - **`nnn@<32hex>` / `<32hex>:/path`** — a NomadNet page. Also stays
 *    inside the mesh.
 *  - **`http(s)://…`** — the one kind that LEAVES the mesh, and the
 *    only one routed through a confirmation (see [linkify]).
 */

// Conservative URL regex — requires an explicit http:// or https://
// scheme so we don't auto-link bare domain text the user typed
// without intent. Trailing punctuation that's almost never part of a
// URL is trimmed by the caller (sentence-end period, comma, paren).
private val URL_PATTERN = Regex(
    """https?://[^\s<>"'\]]+""",
    RegexOption.IGNORE_CASE,
)

/** Matches NomadNet cross-node links — `nnn@<32hex>(:/path)?` or
 *  `<32hex>:/path`. Bare `<32hex>` alone is deliberately excluded
 *  to avoid auto-linking LXMF contact hashes; the explicit `:/` or
 *  `nnn@` prefix is what marks a substring as a Nomad page link. */
private val NOMAD_LINK_PATTERN = Regex(
    """nnn@[0-9a-f]{32}(?::/[^\s<>"'\]]+)?|[0-9a-f]{32}:/[^\s<>"'\]]+""",
    RegexOption.IGNORE_CASE,
)

/**
 * Matches an RRC room link — `rrc@<32hex>` or
 * `rrc.hub@<32hex>:/room/<name>` (`rrc-room-links.md` §2).
 *
 * Note this necessarily OVERLAPS [NOMAD_LINK_PATTERN], whose
 * `<32hex>:/path` arm also matches the tail of an RRC link. That is
 * handled by ordering, not by making the regexes disjoint: matches are
 * sorted by start offset and an RRC match starts four characters
 * earlier (at `rrc@`), so it wins and the overlap guard in [linkify]
 * drops the Nomad match sitting inside it.
 */
private val RRC_LINK_PATTERN = Regex(
    """rrc(?:\.hub)?@[0-9a-f]{32}(?::/room/[^\s<>"'\]]*)?""",
    RegexOption.IGNORE_CASE,
)

private const val NOMAD_DEFAULT_PATH = "/page/index.mu"

/** Trim trailing punctuation that almost certainly isn't part of the
 *  URL ("see https://example.com." → URL ends before the period). */
internal fun trimTrailingPunctuation(url: String): String {
    var end = url.length
    while (end > 0 && url[end - 1] in ".,;:!?)]}>") end--
    return url.substring(0, end)
}

/** Decompose a matched NomadNet cross-node link into (hash, path).
 *  Returns null on malformed input — the regex should prevent that,
 *  but defensive guards keep a bad match from crashing render. */
private fun parseNomadShareLink(raw: String): Pair<String, String>? {
    val lower = raw.lowercase()
    val stripped = if (lower.startsWith("nnn@")) lower.removePrefix("nnn@") else lower
    val colon = stripped.indexOf(':')
    return if (colon < 0) {
        if (stripped.length != 32 || !stripped.all { it.isHexDigit() }) null
        else stripped to NOMAD_DEFAULT_PATH
    } else {
        val hash = stripped.substring(0, colon)
        if (hash.length != 32 || !hash.all { it.isHexDigit() }) return null
        val path = stripped.substring(colon + 1)
        if (!path.startsWith("/")) null else hash to path
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private sealed interface LinkKind {
    val raw: String
    data class Http(override val raw: String) : LinkKind
    data class Nomad(override val raw: String) : LinkKind
    data class Rrc(override val raw: String) : LinkKind
}

/**
 * Build an [AnnotatedString] with the link-shaped substrings of
 * [content] made tappable, styled with [fg] plus an underline.
 *
 * The callbacks are deliberately asymmetric, and that asymmetry is a
 * security property rather than an oversight:
 *
 *  - [onRrcRoom] and [onNomadLink] act immediately. Both stay on the
 *    mesh, so a tap costs nothing a peer could observe.
 *  - [onHttpLink] must NOT open a browser directly. It is handed to a
 *    confirmation dialog by the caller. SECURITY (audit 2026-07-28 L8):
 *    this is an off-grid, zero-HTTP app, and a tapped peer-supplied
 *    link is the one channel that leaves the mesh and reveals the
 *    user's real IP to a server the *sender* chose. Compose's
 *    `LinkAnnotation.Url` would hand it straight to the system browser,
 *    which is exactly what must not happen.
 *
 * A link that fails to parse renders as plain text rather than as a
 * broken tappable span.
 */
internal fun linkify(
    content: String,
    fg: Color,
    // No defaults, deliberately. These started as defaulted no-ops and
    // that silently shipped a dead link: room links were wired in
    // RoomsScreen and NOT in MessagesScreen, so a shared room link
    // rendered underlined and tappable in a DM -- the one surface people
    // share rooms into -- and did nothing at all when tapped. A defaulted
    // callback turns "forgot to wire it" into a link that looks alive and
    // isn't, which is worse than a compile error and invisible in review.
    // Requiring them makes the compiler ask at every call site, the same
    // way the sealed LinkTarget forced NomadScreen to answer.
    onNomadLink: (hash: String, path: String) -> Unit,
    onHttpLink: (url: String) -> Unit,
    onRrcRoom: (hubHash: String, room: String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    val matches = (
        URL_PATTERN.findAll(content).map { it.range to (LinkKind.Http(it.value) as LinkKind) } +
            NOMAD_LINK_PATTERN.findAll(content).map { it.range to (LinkKind.Nomad(it.value) as LinkKind) } +
            RRC_LINK_PATTERN.findAll(content).map { it.range to (LinkKind.Rrc(it.value) as LinkKind) }
        ).sortedBy { it.first.first }

    fun styles() = TextLinkStyles(
        style = SpanStyle(color = fg, textDecoration = TextDecoration.Underline),
    )

    var cursor = 0
    for ((range, kind) in matches) {
        if (range.first < cursor) continue  // overlap guard — see RRC_LINK_PATTERN
        if (range.first > cursor) append(content.substring(cursor, range.first))

        val clean = trimTrailingPunctuation(kind.raw)
        val tail = if (clean.length < kind.raw.length) kind.raw.substring(clean.length) else ""

        when (kind) {
            is LinkKind.Http -> {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "http:$clean",
                        styles = styles(),
                        linkInteractionListener = LinkInteractionListener { onHttpLink(clean) },
                    ),
                ) { append(clean) }
                append(tail)
            }
            is LinkKind.Rrc -> {
                // Parsed through the shared grammar, not re-derived
                // here: §2.2 requires decode-then-normalise and §2.3
                // requires rejecting an unknown path, and both live in
                // parseLinkTarget.
                when (val t = parseLinkTarget(clean)) {
                    is LinkTarget.RrcRoom -> {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "rrc:${t.hubDestHashHex}:${t.room}",
                                styles = styles(),
                                linkInteractionListener = LinkInteractionListener {
                                    onRrcRoom(t.hubDestHashHex, t.room)
                                },
                            ),
                        ) { append(clean) }
                        append(tail)
                    }
                    // A hub with no room names a place, not a
                    // destination to open — render it as text.
                    else -> append(kind.raw)
                }
            }
            is LinkKind.Nomad -> {
                val parsed = parseNomadShareLink(clean)
                if (parsed != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "nomad:${parsed.first}:${parsed.second}",
                            styles = styles(),
                            linkInteractionListener = LinkInteractionListener {
                                onNomadLink(parsed.first, parsed.second)
                            },
                        ),
                    ) { append(clean) }
                    append(tail)
                } else {
                    append(kind.raw)
                }
            }
        }
        cursor = range.last + 1
    }
    if (cursor < content.length) append(content.substring(cursor))
}
