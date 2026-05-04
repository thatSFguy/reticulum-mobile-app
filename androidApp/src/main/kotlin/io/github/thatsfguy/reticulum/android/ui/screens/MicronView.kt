package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import io.github.thatsfguy.reticulum.nomad.FieldType
import io.github.thatsfguy.reticulum.nomad.Inline
import io.github.thatsfguy.reticulum.nomad.InlineStyle
import io.github.thatsfguy.reticulum.nomad.Micron

/**
 * Compose renderer for parsed v0.1.48 micron + v0.1.50 form fields.
 *
 * Form-field interaction (per upstream Browser.py):
 *   - `Inline.Field` runs render as Compose inputs (text, checkbox, radio).
 *   - The user types/checks; values land in [fieldValues] (a state map
 *     keyed by field name).
 *   - When the user taps an `Inline.Link` whose `fields` list is
 *     non-empty, [onLinkClickWithFields] fires with the link target +
 *     the subset of [fieldValues] for those names. Caller (NomadScreen)
 *     msgpack-encodes them as `{ "field_<name>": "<value>" }` and
 *     forwards as the request body — which upstream Node.py:170 reads
 *     into env vars for executable page scripts.
 *   - Links without a `fields` list use [onLinkClick] (plain GET).
 */
@Composable
fun MicronView(
    source: String,
    modifier: Modifier = Modifier,
    onLinkClick: (target: String) -> Unit = {},
    onLinkClickWithFields: (target: String, fields: Map<String, String>) -> Unit = { t, _ -> onLinkClick(t) },
) {
    val blocks = remember(source) { Micron.parse(source) }
    val baseColor = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val literalBg = MaterialTheme.colorScheme.surfaceVariant

    // Field state survives recompositions but resets when the page source
    // changes (a fresh fetch). Initial values come from the parsed
    // Inline.Field nodes (text default, checkbox prechecked, etc.).
    val fieldValues = remember(source) { mutableStateMapOf<String, String>() }
    LaunchedEffect(source) {
        for (block in blocks) {
            val runs = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.runs
                else -> emptyList()
            }
            for (run in runs) {
                if (run is Inline.Field && run.name !in fieldValues) {
                    fieldValues[run.name] = when (run.type) {
                        FieldType.TEXT     -> run.value
                        FieldType.CHECKBOX -> if (run.prechecked) run.value else ""
                        FieldType.RADIO    -> if (run.prechecked) run.value else ""
                    }
                }
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is Block.Heading        -> HeadingLine(block, baseColor, accent, fieldValues, onLinkClick, onLinkClickWithFields)
                is Block.Paragraph      -> ParagraphLine(block, baseColor, accent, fieldValues, onLinkClick, onLinkClickWithFields)
                is Block.Literal        -> LiteralBlock(block, baseColor, literalBg)
                is Block.HorizontalRule -> {
                    // Upstream uses the rune to draw the line. For the
                    // default U+2500 we just emit Material's
                    // HorizontalDivider — a clean 1dp line is closer to
                    // what most users expect than a row of `─`. For
                    // custom runes we render the rune repeated so
                    // intentional `-═` / `-•` dividers look distinct.
                    if (block.rune == '─') {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        Text(
                            block.rune.toString().repeat(48),
                            color = MaterialTheme.colorScheme.outlineVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadingLine(
    block: Block.Heading,
    baseColor: Color,
    accent: Color,
    fieldValues: SnapshotStateMap<String, String>,
    onLinkClick: (String) -> Unit,
    onLinkClickWithFields: (String, Map<String, String>) -> Unit,
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
    RenderFields(block.text, fieldValues)
    HandleLinkClicks(block.text, fieldValues, onLinkClick, onLinkClickWithFields)
}

@Composable
private fun ParagraphLine(
    block: Block.Paragraph,
    baseColor: Color,
    accent: Color,
    fieldValues: SnapshotStateMap<String, String>,
    onLinkClick: (String) -> Unit,
    onLinkClickWithFields: (String, Map<String, String>) -> Unit,
) {
    val styled = buildAnnotated(block.runs, baseColor, accent, defaultBold = false)
    Text(
        styled,
        fontSize = 14.sp,
        color = baseColor,
        textAlign = block.align.toTextAlign(),
        modifier = Modifier.fillMaxWidth(),
    )
    RenderFields(block.runs, fieldValues)
    HandleLinkClicks(block.runs, fieldValues, onLinkClick, onLinkClickWithFields)
}

/**
 * Render every `Inline.Field` in [runs] under the paragraph's text. Text
 * inputs become OutlinedTextField, checkboxes a labelled Checkbox row,
 * radios a labelled RadioButton row. Field state reads from / writes to
 * [fieldValues] so a Send link below can collect the values.
 */
@Composable
private fun RenderFields(runs: List<Inline>, fieldValues: SnapshotStateMap<String, String>) {
    val fields = runs.filterIsInstance<Inline.Field>()
    if (fields.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (field in fields) {
            when (field.type) {
                FieldType.TEXT -> {
                    // Security S7 (v0.1.60): enforce field.width as a
                    // maxLength on user input. Compose's OutlinedTextField
                    // doesn't honor any inherent width limit, so without
                    // this a user could paste 10 MB into a "max 24"
                    // field and we'd ship the whole thing as the
                    // form value. Cap at width × 4 so multi-byte UTF-8
                    // doesn't squeeze legitimate input below the
                    // declared character count, but still bounds.
                    val maxBytes = (field.width * 4).coerceIn(64, 4096)
                    OutlinedTextField(
                        value = fieldValues[field.name] ?: field.value,
                        onValueChange = { incoming ->
                            // Reject paste-bombs at write time.
                            if (incoming.encodeToByteArray().size <= maxBytes) {
                                fieldValues[field.name] = incoming
                            }
                        },
                        label = { Text(field.name) },
                        singleLine = true,
                        visualTransformation = if (field.masked)
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                        else androidx.compose.ui.text.input.VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FieldType.CHECKBOX -> {
                    val checked = (fieldValues[field.name] ?: "") == field.value
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { now ->
                                fieldValues[field.name] = if (now) field.value else ""
                            },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(field.label.ifBlank { field.name })
                    }
                }
                FieldType.RADIO -> {
                    val selected = (fieldValues[field.name] ?: "") == field.value
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected,
                            onClick = { fieldValues[field.name] = field.value },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(field.label.ifBlank { field.value })
                    }
                }
            }
        }
    }
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
 * Render each link as a tappable row. Links with form fields (e.g. a
 * chatroom Send button declared `[Send`/page/post.mu`message]`)
 * collect their named fields from [fieldValues] and route through
 * [onLinkClickWithFields]; plain links route through [onLinkClick].
 */
@Composable
private fun HandleLinkClicks(
    runs: List<Inline>,
    fieldValues: SnapshotStateMap<String, String>,
    onLinkClick: (String) -> Unit,
    onLinkClickWithFields: (String, Map<String, String>) -> Unit,
) {
    val linkRuns = runs.filterIsInstance<Inline.Link>()
    if (linkRuns.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (link in linkRuns) {
            val isPost = link.fields.isNotEmpty()
            val labelText = if (isPost) {
                "↳ ${link.label}  →  ${link.target}  (POST: ${link.fields.joinToString(",")})"
            } else {
                "↳ ${link.label}  →  ${link.target}"
            }
            Text(
                labelText,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp)
                    .clickable {
                        if (isPost) {
                            val values = link.fields
                                .associateWith { name -> fieldValues[name] ?: "" }
                            onLinkClickWithFields(link.target, values)
                        } else {
                            onLinkClick(link.target)
                        }
                    },
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
                is Inline.Field -> {
                    // Form fields render as Compose inputs by RenderFields,
                    // not as inline text — leave a small placeholder so
                    // wrap-around stays visually correct.
                    append("[ ${run.name} ]")
                }
            }
        }
    }
}

private fun Inline.style(): InlineStyle = when (this) {
    is Inline.Text  -> style
    is Inline.Link  -> style
    is Inline.Field -> style
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
