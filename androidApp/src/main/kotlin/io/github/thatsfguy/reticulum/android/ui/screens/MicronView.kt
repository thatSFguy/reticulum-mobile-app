package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thatsfguy.reticulum.nomad.Align
import io.github.thatsfguy.reticulum.nomad.Block
import io.github.thatsfguy.reticulum.nomad.Inline
import io.github.thatsfguy.reticulum.nomad.InlineStyle
import io.github.thatsfguy.reticulum.nomad.Micron

/**
 * Compose renderer for parsed v0.1.48 micron. Headings get incremental
 * font scale; backtick-toggled bold/italic/underline + fg/bg colors map
 * to SpanStyle attributes. Block.Literal is a monospace pre block.
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
    val literalBg = MaterialTheme.colorScheme.surfaceVariant

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
                is Block.Literal        -> LiteralBlock(block, baseColor, literalBg)
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
        textAlign = block.align.toTextAlign(),
        modifier = Modifier.fillMaxWidth(),
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
    Text(
        styled,
        fontSize = 14.sp,
        color = baseColor,
        textAlign = block.align.toTextAlign(),
        modifier = Modifier.fillMaxWidth(),
    )
    HandleLinkClicks(block.runs, onLinkClick)
}

@Composable
private fun LiteralBlock(block: Block.Literal, baseColor: Color, bg: Color) {
    Text(
        block.lines.joinToString("\n"),
        fontSize = 13.sp,
        color = baseColor,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private fun Align.toTextAlign(): TextAlign = when (this) {
    Align.LEFT -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.RIGHT -> TextAlign.End
}

/**
 * Each link in the rendered runs gets a tappable "↳ label → target" row
 * underneath. Inline-clickable links would need ClickableText with span
 * annotations; this is the simple, legible interim until v0.1.49 adds
 * real navigation.
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
                    .padding(start = 4.dp),
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
            color = parseHexColor(style.fg, baseColor),
            background = parseHexColor(style.bg, Color.Transparent),
            fontWeight = if (style.bold || defaultBold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
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

/**
 * Parse a 3- or 6-digit hex colour. 3-hex is expanded by repeating each
 * nibble (e.g. "f00" → ff0000). Returns [fallback] if the code doesn't
 * parse cleanly.
 */
private fun parseHexColor(code: String?, fallback: Color): Color {
    if (code == null) return fallback
    return try {
        val (r, g, b) = when (code.length) {
            3 -> Triple(
                code.substring(0, 1).toInt(16) * 0x11,
                code.substring(1, 2).toInt(16) * 0x11,
                code.substring(2, 3).toInt(16) * 0x11,
            )
            6 -> Triple(
                code.substring(0, 2).toInt(16),
                code.substring(2, 4).toInt(16),
                code.substring(4, 6).toInt(16),
            )
            else -> return fallback
        }
        Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = 1f)
    } catch (_: Exception) { fallback }
}

/** Sample page used by the Nomad screen's "Demo page" path so users can
 *  see the renderer working before the link client is wired up. v0.1.48
 *  uses real backtick-based micron syntax. */
val DEMO_MICRON_PAGE = """
>Welcome to NomadNet

This is a sample `*Micron`* page rendered by the in-app parser. Use it
to confirm formatting works `_locally`_ before the real link-fetch
client lands.

>>Available pages

`[Home`/page/index.mu]
`[About`/page/about.mu]
`[Channels`/page/channels.mu]

>>Inline formatting demo

`!bold text`!, `_underlined text`_, `*italic`*, and `Ff00coloured`f runs
flow together in a single paragraph.

---

The renderer is read-only. Link rows below each paragraph navigate when
the link client lands.
""".trimIndent()
