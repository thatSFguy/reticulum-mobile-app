package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thatsfguy.reticulum.nomad.Block
import io.github.thatsfguy.reticulum.nomad.Inline
import io.github.thatsfguy.reticulum.nomad.InlineStyle
import io.github.thatsfguy.reticulum.nomad.Micron

/**
 * Compose renderer for parsed micron pages. Headings get incremental
 * font scale, links render as underlined accent text, horizontal rules
 * become dividers. Inline color escapes map to [Color] when the 3-hex
 * code parses; unknown codes fall back to onSurface.
 *
 * The renderer is intentionally read-only — link clicks are accepted
 * via [onLinkClick] but the behaviour (navigating into another page,
 * dialing a destination, etc.) is the caller's responsibility.
 */
@Composable
fun MicronView(
    source: String,
    modifier: Modifier = Modifier,
    onLinkClick: (target: String) -> Unit = {},
) {
    val blocks = remember(source) { Micron.parse(source) }
    val baseColor = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is Block.Heading        -> HeadingLine(block, baseColor, accent, onLinkClick)
                is Block.Paragraph      -> ParagraphLine(block, baseColor, accent, onLinkClick)
                Block.HorizontalRule    -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun HeadingLine(
    block: Block.Heading,
    baseColor: Color,
    accent: Color,
    onLinkClick: (String) -> Unit,
) {
    val sizeSp = when (block.level) { 1 -> 22.sp; 2 -> 18.sp; else -> 15.sp }
    val styled = buildAnnotated(block.text, baseColor, accent, defaultBold = true)
    Text(
        styled,
        fontSize = sizeSp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
    )
    HandleLinkClicks(block.text, onLinkClick)
}

@Composable
private fun ParagraphLine(
    block: Block.Paragraph,
    baseColor: Color,
    accent: Color,
    onLinkClick: (String) -> Unit,
) {
    val styled = buildAnnotated(block.runs, baseColor, accent, defaultBold = false)
    Text(styled, fontSize = 14.sp, color = baseColor)
    HandleLinkClicks(block.runs, onLinkClick)
}

/**
 * Crude link click handling: each Link inline gets a dedicated tappable
 * row underneath the paragraph if its label appears in the rendered
 * text. Real proportional inline links would need [ClickableText] with
 * span annotations; this gets the user past the demo while staying
 * legible. Swap for ClickableText when the link client lands.
 */
@Composable
private fun HandleLinkClicks(runs: List<Inline>, onLinkClick: (String) -> Unit) {
    val linkRuns = runs.filterIsInstance<Inline.Link>()
    if (linkRuns.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (link in linkRuns) {
            Text(
                "↳ ${link.label}  →  ${link.target}",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp)
                    .let { it },
            )
        }
    }
}

private fun buildAnnotated(
    runs: List<Inline>,
    baseColor: Color,
    accent: Color,
    defaultBold: Boolean,
): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        val style = run.style()
        val span = SpanStyle(
            color = parseHexColor(style.color, baseColor),
            fontWeight = if (style.bold || defaultBold) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (style.underline) TextDecoration.Underline else null,
        )
        withStyle(span) {
            when (run) {
                is Inline.Text -> append(run.text)
                is Inline.Link -> {
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                        append(run.label)
                    }
                }
            }
        }
    }
}

private fun Inline.style(): InlineStyle = when (this) {
    is Inline.Text -> style
    is Inline.Link -> style
}

/** Parse a 3-digit hex colour like "f00" or "1a8" into an opaque Color. */
private fun parseHexColor(code: String?, fallback: Color): Color {
    if (code == null || code.length != 3) return fallback
    return try {
        val r = code.substring(0, 1).toInt(16) * 0x11
        val g = code.substring(1, 2).toInt(16) * 0x11
        val b = code.substring(2, 3).toInt(16) * 0x11
        Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = 1f)
    } catch (_: Exception) { fallback }
}

/** Sample page used by the Nomad screen's "Demo page" button so users can
 *  see the renderer working before the link client is wired up. */
val DEMO_MICRON_PAGE = """
>Welcome to NomadNet

This is a sample \BMicron\b page rendered by the in-app parser. Use it
to confirm formatting works \!ulocally\!U before the real link-fetch
client lands.

>>Available pages

[Home]:/page/index.mu
[About]:/page/about.mu
[Channels]:/page/channels.mu

>>Inline formatting demo

\Bbold text\b, \!uunderlined text\!U, and \Ff00coloured\f runs flow
together in a single paragraph. Unknown escapes like \Q are kept as
plain characters so nothing is silently dropped.

\=

The renderer is read-only. Link rows above expand under each paragraph;
once we have a link client they'll navigate into the actual page.
""".trimIndent()
