package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thatsfguy.reticulum.nomad.Align
import io.github.thatsfguy.reticulum.nomad.Block
import io.github.thatsfguy.reticulum.nomad.buildFormSubmitData
import io.github.thatsfguy.reticulum.nomad.LinkTarget
import io.github.thatsfguy.reticulum.nomad.parseLinkTarget
import io.github.thatsfguy.reticulum.nomad.isCheckboxChecked
import io.github.thatsfguy.reticulum.nomad.toggleCheckboxValue
import io.github.thatsfguy.reticulum.nomad.FieldType
import io.github.thatsfguy.reticulum.nomad.Inline
import io.github.thatsfguy.reticulum.nomad.InlineStyle
import io.github.thatsfguy.reticulum.nomad.looksPreformatted
import io.github.thatsfguy.reticulum.nomad.visibleWidth
import io.github.thatsfguy.reticulum.nomad.Micron
import io.github.thatsfguy.reticulum.nomad.MicronDocument
import kotlinx.coroutines.launch

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
    /** [label] is the link's own visible text — what the page author
     *  called the thing. Carried alongside the target so a cross-node
     *  hop can name the destination it just created something better
     *  than a hash. Empty for a bare `\`[url]` link, where label and
     *  target are the same string. */
    onLinkClick: (target: String, label: String) -> Unit = { _, _ -> },
    onLinkClickWithFields: (target: String, fields: Map<String, String>) -> Unit = { t, _ -> onLinkClick(t, "") },
    /** v0.1.67: fetcher for partial-page placeholders (`\`{url}`).
     *  The renderer calls this asynchronously when it encounters a
     *  Block.Partial; the returned string is itself micron and is
     *  parsed + rendered inline. Default returns null so partials
     *  inside partials just show "loading" forever (rare in practice).
     *
     *  v1.2.114: takes the built request dict, not the raw field list.
     *  A partial's field list obeys the same rules as a form-submit
     *  link's (`Browser.py:766-811` is a copy of `:216-266`), `*`
     *  included — so it is built here, where the live widget values
     *  are, by the same shared helper. */
    fetchPartial: suspend (url: String, data: Map<String, String>) -> String? = { _, _ -> null },
) {
    val document = remember(source) { Micron.parseDocument(source) }
    val blocks = document.blocks
    // v0.1.62: respect page-level `#!fg=` / `#!bg=` headers per
    // Browser.py:1282-1302. Fall back to theme colors when the page
    // doesn't set them.
    val baseColor = parseHexColor(document.pageFg, MaterialTheme.colorScheme.onSurface)
    val accent = MaterialTheme.colorScheme.primary
    val literalBg = MaterialTheme.colorScheme.surfaceVariant
    val pageBg = parseHexColor(document.pageBg, Color.Unspecified)

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
                if (run !is Inline.Field) continue
                // Checkboxes accumulate (multi-select), so they must not
                // be skipped once the name is present; text inputs and
                // radios keep the first-seen / last-prechecked rules.
                if (run.type != FieldType.CHECKBOX && run.name in fieldValues) continue
                when (run.type) {
                    FieldType.TEXT -> {
                        // Initial value is always present (may be empty).
                        fieldValues[run.name] = run.value
                    }
                    FieldType.RADIO -> {
                        // urwid re-points the group when a later button
                        // is constructed with state=True, so the LAST
                        // prechecked radio in a group wins upstream.
                        if (run.prechecked) fieldValues[run.name] = run.value
                    }
                    FieldType.CHECKBOX -> {
                        // v0.1.61: only seed when prechecked; unchecked
                        // boxes stay absent so submit omits them per
                        // Browser.py:226-241. Two prechecked boxes
                        // sharing a name accumulate, they don't
                        // overwrite each other.
                        if (run.prechecked) {
                            toggleCheckboxValue(fieldValues[run.name], run.value, true)
                                ?.let { fieldValues[run.name] = it }
                        }
                    }
                }
            }
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    // v1.2.114: a `p:<id>` link bumps the tick for each named partial,
    // which re-keys that PartialBlock's fetch loop and re-runs it.
    val partialTicks = remember(source) { mutableStateMapOf<String, Int>() }

    // Link targets that never leave the device. Upstream checks both
    // BEFORE any destination parsing and before a form POST is sent
    // (Browser.py:271-291), so `#top` and `p:chat` are page-local
    // actions even on a link that carries form fields. Returns false
    // when the target is real navigation for the caller to handle —
    // including an anchor this page doesn't declare, so the caller can
    // say so rather than the tap silently doing nothing.
    fun handledLocally(target: String): Boolean = when (val parsed = parseLinkTarget(target)) {
        is LinkTarget.Anchor -> {
            val index = if (parsed.name.isEmpty()) {
                nextHeadingAfter(document, listState.firstVisibleItemIndex)
            } else {
                document.anchors[parsed.name]
            }
            if (index != null) {
                scope.launch { listState.animateScrollToItem(index) }
                true
            } else {
                false
            }
        }
        is LinkTarget.PartialRefresh -> {
            for (id in parsed.ids) partialTicks[id] = (partialTicks[id] ?: 0) + 1
            true
        }
        else -> false
    }

    val dispatchLink: (String, String) -> Unit = { target, label ->
        if (!handledLocally(target)) onLinkClick(target, label)
    }
    val dispatchLinkWithFields: (String, Map<String, String>) -> Unit = { target, data ->
        if (!handledLocally(target)) onLinkClickWithFields(target, data)
    }

    // v0.1.86: SelectionContainer wraps the whole rendered page so
    // long-press → text selection → copy works the same as on a
    // browser. Compose's tap-vs-long-press disambiguation keeps
    // ClickableText link handlers and OutlinedTextField inputs working
    // — they get short taps before the selection gesture engages, and
    // the inputs' own selection state takes precedence when the user
    // is editing them.
    //
    // v0.1.65: LazyColumn instead of verticalScroll(Column) so a
    // multi-thousand-block page only measures the visible window.
    // Without this a hostile page can OOM the renderer just by being
    // long (security S6); with it, scrolling stays smooth on
    // arbitrarily-long pages.
    androidx.compose.foundation.text.selection.SelectionContainer {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier
            .fillMaxWidth()
            .background(pageBg)
            .padding(16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(blocks.size, key = { it }) { idx ->
            val block = blocks[idx]
            // Section indent (SPEC-less rendering detail, upstream
            // MicronParser.py:418-422). Deliberate divergence: upstream
            // pads BOTH sides by the same amount, which on a phone
            // spends 24dp of line width per level to say something one
            // side already says. Left only, and capped, so a page that
            // nests six deep is still readable.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = MICRON_INDENT_STEP * block.indent.coerceAtMost(MICRON_MAX_INDENT_STEPS)),
            ) {
            when (block) {
                is Block.Heading        -> HeadingLine(block, baseColor, accent, fieldValues, dispatchLink, dispatchLinkWithFields)
                is Block.Paragraph      -> ParagraphLine(block, baseColor, accent, fieldValues, dispatchLink, dispatchLinkWithFields)
                is Block.Literal        -> LiteralBlock(block, baseColor, literalBg)
                is Block.Table          -> TableBlock(block, baseColor, accent, fieldValues, dispatchLink, dispatchLinkWithFields)
                is Block.Partial        -> PartialBlock(
                    block = block,
                    fetchPartial = fetchPartial,
                    fieldValues = fieldValues,
                    refreshTick = block.partialId?.let { partialTicks[it] } ?: 0,
                    baseColor = baseColor,
                    bg = literalBg,
                )
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
                            // A fixed 48 runes overflows a narrow phone
                            // and used to wrap onto a second row, which
                            // reads as two dividers. Clip it instead.
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            }
        }
    }
    }
}

/** One step of section indent. Upstream's `SECTION_INDENT = 2` is two
 *  terminal columns; 12.dp is the same visual step at our body size. */
private val MICRON_INDENT_STEP = 12.dp

/** Indent stops deepening after this many steps — 36dp is already a
 *  tenth of a phone's width, and micron pages nest further than they
 *  mean to. */
private const val MICRON_MAX_INDENT_STEPS = 3

@Composable
private fun HeadingLine(
    block: Block.Heading,
    baseColor: Color,
    accent: Color,
    fieldValues: SnapshotStateMap<String, String>,
    onLinkClick: (String, String) -> Unit,
    onLinkClickWithFields: (String, Map<String, String>) -> Unit,
) {
    val sizeSp = when (block.level) { 1 -> 22.sp; 2 -> 18.sp; else -> 15.sp }
    val styled = buildAnnotated(
        block.text, baseColor, accent, defaultBold = true,
        fieldValues = fieldValues,
        onLinkClick = onLinkClick,
        onLinkClickWithFields = onLinkClickWithFields,
    )
    Text(
        styled,
        fontSize = sizeSp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
        textAlign = block.align.toTextAlign(),
        modifier = Modifier.fillMaxWidth(),
    )
    RenderFields(block.text, fieldValues)
}

@Composable
private fun ParagraphLine(
    block: Block.Paragraph,
    baseColor: Color,
    accent: Color,
    fieldValues: SnapshotStateMap<String, String>,
    onLinkClick: (String, String) -> Unit,
    onLinkClickWithFields: (String, Map<String, String>) -> Unit,
) {
    val styled = buildAnnotated(
        block.runs, baseColor, accent, defaultBold = false,
        fieldValues = fieldValues,
        onLinkClick = onLinkClick,
        onLinkClickWithFields = onLinkClickWithFields,
    )
    // A paragraph that draws a box is preformatted, whatever the markup
    // says. Micron is written for a monospace 80-column terminal, so a
    // frame rendered as ordinary body text does two bad things at once:
    // the border runs wrap onto a second row, and the columns can't line
    // up in a proportional font even where they fit. Rendering it
    // monospace, unwrapped and clipped keeps the frame's geometry and
    // ends the long lines at the screen edge, with a scroller for the
    // rest (#58). Inline styling and links survive — it is the same
    // AnnotatedString either way.
    val preformatted = remember(block.runs) {
        looksPreformatted(plainLinesOf(block.runs))
    }
    if (preformatted) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                styled,
                fontSize = 13.sp,
                color = baseColor,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    } else {
        Text(
            styled,
            fontSize = 14.sp,
            color = baseColor,
            textAlign = block.align.toTextAlign(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    RenderFields(block.runs, fieldValues)
}

/**
 * The rendered text of [runs], split back into source lines — what the
 * preformatted check needs to look at. Link labels count as text
 * (they occupy columns in the frame); fields do not.
 */
private fun plainLinesOf(runs: List<Inline>): List<String> =
    runs.joinToString("") { run ->
        when (run) {
            is Inline.Text -> run.text
            is Inline.Link -> run.label
            is Inline.Field -> ""
        }
    }.split('\n')

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
                    // Several checkboxes may share one field name — that
                    // is micron's multi-select — so the map entry holds
                    // the comma-joined list of selected values that
                    // Browser.py:255-266 builds, and this box is checked
                    // iff its own value is in that list. Pre-fix the
                    // entry held a single value, so two boxes named
                    // `topics` toggled each other and only ever one
                    // value reached the server.
                    val checked = isCheckboxChecked(fieldValues[field.name], field.value)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { now ->
                                // v0.1.61: per upstream Browser.py:226-241,
                                // an unchecked checkbox is OMITTED from
                                // the submitted dict (NOT sent as "") —
                                // toggleCheckboxValue returns null once
                                // nothing is left selected, and we drop
                                // the key on null.
                                val next = toggleCheckboxValue(fieldValues[field.name], field.value, now)
                                if (next == null) fieldValues.remove(field.name)
                                else fieldValues[field.name] = next
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

/**
 * Render a `Block.Table` as a real grid.
 *
 * Upstream draws tables with box-drawing characters into a fixed-width
 * terminal (`MarkdownToMicron.format_table_raw`), and we used to imitate
 * that with one monospace `Text`. Two things were wrong with it beyond
 * looks: markup inside a cell was printed literally, so a link in a
 * table was dead text, and column widths were measured on the markup, so
 * any styled cell skewed the whole box.
 *
 * Here each cell goes through the same [buildAnnotated] path as a
 * paragraph — formatting and links live, links dispatching through the
 * same handlers — and column widths come from [visibleWidth].
 *
 * Divergences from upstream, both in the reader's favour on a phone:
 * cells WRAP where upstream truncates to fit its width budget, and the
 * budget itself (`` `tN ``) becomes an upper bound rather than a target,
 * because the screen is nearly always the binding constraint.
 */
@Composable
private fun TableBlock(
    block: Block.Table,
    baseColor: Color,
    accent: Color,
    fieldValues: SnapshotStateMap<String, String>,
    onLinkClick: (String, String) -> Unit,
    onLinkClickWithFields: (String, Map<String, String>) -> Unit,
) {
    val columns = block.header.size
    if (columns == 0) return
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant

    // Weights are the widest VISIBLE cell per column, floored at the
    // same 3 characters upstream floors at (TABLE_MIN_COL_WIDTH) so a
    // column of empty cells doesn't collapse to nothing.
    val weights = remember(block) {
        val rows = listOf(block.header) + block.rows
        FloatArray(columns) { col ->
            rows.maxOf { row -> row.getOrNull(col)?.visibleWidth() ?: 0 }
                .coerceAtLeast(MIN_TABLE_COLUMN_CHARS)
                .toFloat()
        }
    }
    val placement = when (block.align) {
        Align.CENTER -> Alignment.CenterHorizontally
        Align.RIGHT -> Alignment.End
        else -> Alignment.Start
    }
    // ``tN` is a character budget for a terminal. Approximate it at our
    // body size and treat it as a ceiling: on a phone the available
    // width wins, on a tablet the author's intent does.
    val maxDp = block.maxWidth?.let { (it * APPROX_CHAR_DP).dp } ?: Dp.Infinity

    Column(
        Modifier
            .fillMaxWidth()
            .wrapContentWidth(placement)
            .widthIn(max = maxDp)
            .border(1.dp, outline, RoundedCornerShape(6.dp)),
    ) {
        TableRow(
            cells = block.header,
            weights = weights,
            aligns = block.columnAligns,
            outline = outline,
            background = headerBg,
            bold = true,
            baseColor = baseColor,
            accent = accent,
            fieldValues = fieldValues,
            onLinkClick = onLinkClick,
            onLinkClickWithFields = onLinkClickWithFields,
        )
        for (row in block.rows) {
            HorizontalDivider(color = outline)
            TableRow(
                cells = row,
                weights = weights,
                aligns = block.columnAligns,
                outline = outline,
                background = Color.Transparent,
                bold = false,
                baseColor = baseColor,
                accent = accent,
                fieldValues = fieldValues,
                onLinkClick = onLinkClick,
                onLinkClickWithFields = onLinkClickWithFields,
            )
        }
    }
}

/** One row of a [TableBlock]. `height(IntrinsicSize.Min)` is what lets
 *  the column dividers run the full height of the tallest wrapped cell
 *  rather than stopping at the shortest. */
@Composable
private fun TableRow(
    cells: List<List<Inline>>,
    weights: FloatArray,
    aligns: List<Align>,
    outline: Color,
    background: Color,
    bold: Boolean,
    baseColor: Color,
    accent: Color,
    fieldValues: SnapshotStateMap<String, String>,
    onLinkClick: (String, String) -> Unit,
    onLinkClickWithFields: (String, Map<String, String>) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(background),
    ) {
        for (col in weights.indices) {
            if (col > 0) VerticalDivider(color = outline)
            Box(
                Modifier
                    .weight(weights[col])
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    buildAnnotated(
                        cells.getOrNull(col).orEmpty(), baseColor, accent, defaultBold = bold,
                        fieldValues = fieldValues,
                        onLinkClick = onLinkClick,
                        onLinkClickWithFields = onLinkClickWithFields,
                    ),
                    fontSize = 13.sp,
                    color = baseColor,
                    textAlign = (aligns.getOrNull(col) ?: Align.LEFT).toTextAlign(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Upstream `TABLE_MIN_COL_WIDTH` (`rngit/util.py:128`). */
private const val MIN_TABLE_COLUMN_CHARS = 3

/** Rough advance width of one character at the 13sp table body size.
 *  Only used to turn ``tN`'s terminal-column budget into a dp ceiling. */
private const val APPROX_CHAR_DP = 7

/**
 * Render a `Block.Partial` server-side include. LaunchedEffect kicks
 * the [fetchPartial] callback against the partial's URL; while the
 * fetch is in flight a "⧖ Loading…" placeholder is shown. On success
 * the response (itself micron) is parsed and rendered inline via a
 * recursive MicronView call. If [Block.Partial.refreshSeconds] is
 * set, the loop re-fetches on a timer (refresh < 1s is dropped at
 * parse time per upstream).
 *
 * Recursive partials use the default no-op fetchPartial (a partial
 * inside a partial just shows "loading" forever — rare in practice
 * and matches the upstream behavior of dropping refresh < 1).
 */
@Composable
private fun PartialBlock(
    block: Block.Partial,
    fetchPartial: suspend (String, Map<String, String>) -> String?,
    fieldValues: SnapshotStateMap<String, String>,
    /** Bumped by a `p:<id>` link naming this partial's `pid`. Part of
     *  the LaunchedEffect key, so a bump re-runs the fetch. */
    refreshTick: Int,
    baseColor: Color,
    bg: Color,
) {
    var content by remember(block) { mutableStateOf<String?>(null) }
    var failed by remember(block) { mutableStateOf<String?>(null) }
    LaunchedEffect(block.url, block.refreshSeconds, block.fields, refreshTick) {
        while (true) {
            // Partial field lists follow the form-submit rules, `*`
            // included (Browser.py:766-811). Built at fetch time so a
            // refreshing partial sees the widget values as they are now.
            val res = runCatching { fetchPartial(block.url, buildFormSubmitData(block.fields, fieldValues.toMap())) }
            content = res.getOrNull()
            failed = if (content == null) (res.exceptionOrNull()?.message ?: "no content") else null
            val refresh = block.refreshSeconds ?: break
            kotlinx.coroutines.delay((refresh * 1000).toLong())
        }
    }
    val sub = content
    when {
        sub != null -> {
            // Recursive render — but pass a no-op fetchPartial so a
            // partial inside a partial doesn't infinite-loop fetch.
            MicronView(source = sub)
        }
        failed != null -> {
            Text(
                "⚠ partial failed: $failed (${block.url})",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        else -> {
            Text(
                "⧖ Loading ${block.url}…",
                color = baseColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LiteralBlock(block: Block.Literal, baseColor: Color, bg: Color) {
    // Micron is written for an 80-column terminal, so a literal block is
    // routinely wider than a phone. It gets clipped at the viewport and
    // scrolls sideways on its own: the lines LOOK shortened, the block
    // keeps its shape, the type stays readable, and nothing is lost.
    //
    // This replaces an ASCII-art autoshrink that scaled the font down
    // until the widest line fit, with a 6sp floor. Two things were wrong
    // with it: past that floor the text wrapped anyway (which is what
    // destroys a box), and it only ran on blocks a heuristic recognised
    // as art — a literal block of anything else got no treatment at all
    // and simply wrapped. One rule now covers every literal block (#58).
    val joined = remember(block.lines) { block.lines.joinToString("\n") }
    Box(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            joined,
            fontSize = 13.sp,
            color = baseColor,
            fontFamily = FontFamily.Monospace,
            // The whole point: no reflow. Overflow clips at the
            // viewport edge and the scroller reaches the rest.
            softWrap = false,
        )
    }
}

/**
 * Index of the first heading block strictly below [current], or null
 * when there is none. Backs the bare `#` link — upstream's
 * `Browser.py:337-348` walks `header_rows` for the first one past the
 * current scroll position.
 */
private fun nextHeadingAfter(document: MicronDocument, current: Int): Int? =
    document.headingBlocks.firstOrNull { it > current }

private fun Align.toTextAlign(): TextAlign = when (this) {
    Align.LEFT -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.RIGHT -> TextAlign.End
}

/**
 * Build the `data` dict the engine ships as REQUEST envelope element [2].
 *
 * Thin adapter over the shared [buildFormSubmitData] (SPEC §11.6.2) —
 * the rules (`*` = all fields, `key=value` → `var_`, widget name →
 * `field_`, unchecked widgets omitted) live in commonMain so the iOS
 * renderer submits byte-identical dicts.
 */
private fun buildSubmitData(
    fields: List<String>,
    fieldValues: SnapshotStateMap<String, String>,
): Map<String, String> = buildFormSubmitData(fields, fieldValues.toMap())

/**
 * Build an [AnnotatedString] with inline-clickable `Inline.Link` runs.
 *
 * Pre-v1.2.7 each link rendered twice: once inline as styled-but-inert
 * text inside the paragraph and again as a tappable "↳ label → target"
 * row below the paragraph. Users tapped the underlined inline text,
 * nothing happened, and the actual control was a few lines down — a
 * UX bug surfaced by the user.
 *
 * Fix: wrap each link span in `LinkAnnotation.Clickable` so Compose's
 * `Text` natively dispatches the tap to the link callback. The
 * `SelectionContainer` wrapping the page (MicronView) still allows
 * long-press → select-and-copy; short tap routes to the listener,
 * long press starts text selection.
 */
private fun buildAnnotated(
    runs: List<Inline>,
    baseColor: Color,
    accent: Color,
    defaultBold: Boolean,
    fieldValues: SnapshotStateMap<String, String>,
    onLinkClick: (String, String) -> Unit,
    onLinkClickWithFields: (String, Map<String, String>) -> Unit,
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
                    val isPost = run.fields.isNotEmpty()
                    val target = run.target
                    val linkFields = run.fields
                    val linkAnnotation = LinkAnnotation.Clickable(
                        tag = target,
                        styles = TextLinkStyles(
                            style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                        ),
                        linkInteractionListener = LinkInteractionListener {
                            if (isPost) {
                                onLinkClickWithFields(target, buildSubmitData(linkFields, fieldValues))
                            } else {
                                onLinkClick(target, run.label)
                            }
                        },
                    )
                    withLink(linkAnnotation) {
                        append(run.label)
                    }
                }
                is Inline.Field -> {
                    // v0.1.76: render NOTHING inline. Pre-fix we appended
                    // "[ name ]" so paragraph layout would account for the
                    // field's space, but RenderFields draws the actual
                    // input widget BELOW the paragraph anyway — so the
                    // [ name ] text was always a redundant artifact above
                    // every form input. Showcase capture confirmed this
                    // looked broken to users.
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
