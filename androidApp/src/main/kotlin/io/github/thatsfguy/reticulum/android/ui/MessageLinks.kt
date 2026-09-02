package io.github.thatsfguy.reticulum.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import io.github.thatsfguy.reticulum.announce.isBidiControl
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
 *  - **`rrc://<32hex>/<room>`** — an RRC room link
 *    (`rrc-room-links.md` v2). Stays inside the mesh; tapping joins.
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
 * Matches an RRC link in any form the v2 grammar reads:
 *
 *   `rrc://<32hex>[:<dest_name>][/<room>]`   the canonical URL form
 *   `rrc@<32hex>[…]`                         shorthand, also
 *   `rrc.hub@…` / `rrc.hub.session@…`        the aspect spellings
 *   `rrc@<32hex>:/room/<name>`               v1, still in the wild
 *
 * Deliberately permissive: a match only decides what to hand to
 * [parseLinkTarget], which is the single place the grammar lives, and a
 * string that matches here but does not parse falls through to plain
 * text. That is why an upper-case `RRC://` can match — it parses to
 * `Unknown` (upstream matches the scheme case-sensitively) and renders
 * as inert text rather than a tap that goes nowhere.
 *
 * Note this necessarily OVERLAPS [NOMAD_LINK_PATTERN], whose
 * `<32hex>:/path` arm also matches the tail of a v1 RRC link. That is
 * handled by ordering, not by making the regexes disjoint: matches are
 * sorted by start offset and an RRC match starts earlier (at `rrc`), so
 * it wins and the overlap guard in [linkify] drops the Nomad match
 * sitting inside it.
 */
private val RRC_LINK_PATTERN = Regex(
    """rrc(?:://|(?:\.hub(?:\.session)?)?@)[0-9a-f]{32}(?:[:/][^\s<>"'\]]*)?""",
    RegexOption.IGNORE_CASE,
)

/** Trim trailing punctuation that almost certainly isn't part of the
 *  URL ("see https://example.com." → URL ends before the period). */
internal fun trimTrailingPunctuation(url: String): String {
    var end = url.length
    while (end > 0 && url[end - 1] in ".,;:!?)]}>") end--
    return url.substring(0, end)
}

/**
 * Confirmation for a tapped room link naming a hub this device has
 * never connected to (audit 2026-09-02 M3).
 *
 * Rendered from every screen that shows message text, driven by
 * [ReticulumViewModel.pendingRoomLink] — one gate in the ViewModel, one
 * dialog here, so a new message surface cannot quietly skip it.
 *
 * The wording names the durable part, which is what makes this
 * different from opening a page: accepting does not just connect once,
 * it records the room as joined and the app reconnects to that hub at
 * every launch from then on.
 */
@Composable
internal fun RoomLinkConfirmDialog(viewModel: ReticulumViewModel) {
    val pending by viewModel.pendingRoomLink.collectAsState()
    pending?.let { link ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingRoomLink() },
            title = { Text("Join room on a new hub?") },
            text = {
                Text(
                    "This link joins \"${boundLinkText(link.room)}\" on a Relay Chat hub you " +
                        "have never connected to:\n\n${link.hubHash}\n\n" +
                        "The hub operator — chosen by whoever sent the link, not you — will " +
                        "see your device connect. Your app will also reconnect to this hub " +
                        "every time it starts, until you remove it from the Rooms tab.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingRoomLink() }) { Text("Join") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingRoomLink() }) { Text("Cancel") }
            },
        )
    }
}

/** Bound a room name for display in the confirmation dialog. It comes
 *  off an attacker-authored link, so it gets the same treatment the URL
 *  does: format characters stripped, length capped. */
private fun boundLinkText(s: String, limit: Int = 48): String {
    val cleaned = s.filter { !isBidiControl(it) }
    return if (cleaned.length <= limit) cleaned else cleaned.take(limit) + "…"
}

/**
 * Render an untrusted URL for DISPLAY in the leave-the-mesh
 * confirmation dialog.
 *
 * Two hardenings, both because this string is attacker-authored and is
 * being shown to a user who is about to make a security decision about
 * it (audit 2026-09-02, dialog observation). Mirrored on iOS in
 * `ExternalLinkConfirm.swift`:
 *
 *  - **Bidi controls are stripped.** An override (U+202E and friends)
 *    reverses the display order of everything after it, so a URL can be
 *    made to read as a host it does not point at — in the very dialog
 *    whose job is to say where the tap goes. Shares `isBidiControl`
 *    with the display-name sanitizer, which strips the directional set
 *    only: U+200D ZWJ is also a format character and is load-bearing in
 *    emoji, which people put in names.
 *  - **The length is bounded, eliding the middle.** A multi-kilobyte
 *    URL pushes the dialog's confirm/cancel buttons off screen; keeping
 *    the head (scheme + host, the part that decides the answer) and the
 *    tail beats a plain truncation.
 *
 * Display only — the URL actually opened is always the unmodified one.
 */
internal fun displayableExternalUrl(url: String, limit: Int = 120): String {
    val cleaned = url.filter { !isBidiControl(it) }
    if (cleaned.length <= limit) return cleaned
    return cleaned.take(limit - 24) + "…" + cleaned.takeLast(20)
}

/**
 * Decompose a matched NomadNet cross-node link into (hash, path), or
 * null when it isn't one this client will act on.
 *
 * Routed through the shared [parseLinkTarget] rather than re-derived
 * here — the same reason the RRC arm below is, and it was a real gap
 * until 2026-09-02 (audit L2). The hand-rolled version this replaces
 * validated the hash and the leading `/` and nothing else, so it
 * skipped `isPathSafe` (security gate S4) entirely: the match pattern
 * `[^\s<>"'\]]+` excludes whitespace but permits every other control
 * character and permits `..` segments freely, so a peer-supplied DM
 * could render a tappable link dispatching a path the shared parser
 * would have rejected. A link is only ever as correct as the one place
 * that reads it.
 */
internal fun parseNomadShareLink(raw: String): Pair<String, String>? =
    when (val parsed = parseLinkTarget(raw)) {
        is LinkTarget.CrossNode -> parsed.destHashHex to parsed.path
        else -> null
    }

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
                // here: the dest_name check, the v1 percent-decoding
                // shim and the room normalisation all live in
                // parseLinkTarget, and a link is only ever as correct
                // as the one place that reads it.
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
