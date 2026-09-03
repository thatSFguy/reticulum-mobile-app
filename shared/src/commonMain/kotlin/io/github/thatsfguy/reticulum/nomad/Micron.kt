package io.github.thatsfguy.reticulum.nomad

/**
 * Micron parser for NomadNet pages.
 *
 * Ported byte-for-byte against `nomadnet/ui/textui/MicronParser.py` (upstream
 * NomadNet master, fetched 2026-05-04). Pre-v0.1.48 we were parsing a wrong
 * `\`-based escape syntax that DID NOT EXIST in real micron — the actual
 * format escape character is **backtick** (\`).
 *
 * Per-line flow:
 *   1. Split source into lines.
 *   2. Each line starts in `text` mode. Plain chars accumulate; a `` ` ``
 *      flips to `formatting` mode for the *next* char only, then back to
 *      text after that single command is processed.
 *   3. Some commands consume extra chars (e.g. `` `F308 `` is 5 chars
 *      total: backtick + F + 3-hex). Those advance the cursor by their
 *      full length before returning to text mode.
 *
 * Block-level constructs handled at the start of a line:
 *   `>title`          h1 (also `>>` h2, `>>>` h3 — `state.depth` rises)
 *   `<`               section depth reset to 0
 *   `-`               horizontal rule (optional unicode char as `-X`)
 *   `#...`            comment line — dropped entirely
 *   `` `= ``          literal block toggle (subsequent lines are raw text
 *                     with no inline parsing until the next `` `= ``)
 *   `\` at line start escapes the line so the first char is treated as
 *                     plain text rather than a block command.
 *
 * Inline format escapes (after a `` ` `` — single-char commands unless
 * noted):
 *   `` `! ``           toggle bold
 *   `` `_ ``           toggle underline
 *   `` `* ``           toggle italic
 *   `` `F308 ``        set foreground 3-hex color  (5 chars total)
 *   `` `FT3080a0 ``    set foreground 6-hex color  (9 chars total)
 *   `` `f ``           reset foreground to default
 *   `` `B308 ``        set background 3-hex color  (5 chars total)
 *   `` `BT3080a0 ``    set background 6-hex color  (9 chars total)
 *   `` `b ``           reset background to default
 *   `` ` `` (alone)    full reset — clears bold/italic/underline + colors
 *                     + alignment back to defaults
 *   `` `c `` `` `l ``  alignment center / left / right
 *   `` `r `` `` `a ``  alignment right / reset to default
 *   `` `[url] ``       link, label = url
 *   `` `[label`url] `` link with label
 *   `` `[label`url`fields] `` link with form-field names (rare)
 *
 * Inline char escape: `\\` followed by `` ` `` produces a literal backtick
 * (does not trigger formatting mode). `\\\\` is a literal backslash.
 *
 * Block-level constructs the parser resolves fully, each with its own
 * [Block] variant: form fields, markdown tables (see [Block.Table]),
 * server-side include partials, and the `#!` page headers (lifted into
 * [MicronDocument] before block parsing).
 *
 * Every block carries the section [Block.indent] its `>`/`>>` depth
 * implies; the renderers turn that into leading space.
 */
sealed class Block {
    /**
     * Section indent, in steps, from the enclosing `>`/`>>` depth.
     * Upstream wraps every widget below a heading in
     * `urwid.Padding(left=left_indent(state))` where
     * `left_indent = (depth-1)*SECTION_INDENT` (`MicronParser.py:35`,
     * `:418-422`) — so depth 1 is flush and each further level steps in.
     * We store the step count and let each renderer pick its own metric;
     * see the divergence note on the Android/iOS renderers, which indent
     * on the left only because a phone cannot spare the width twice.
     */
    abstract val indent: Int

    data class Heading(
        val level: Int,
        val align: Align,
        val text: List<Inline>,
        override val indent: Int = 0,
    ) : Block()

    data class Paragraph(
        val align: Align,
        val runs: List<Inline>,
        override val indent: Int = 0,
    ) : Block()
    /** Horizontal rule. [rune] is the character used to draw the line —
     *  default U+2500 (─); a `-X` line at start sets it to X (control
     *  chars fall back to U+2500). Per MicronParser.py:266-273. */
    data class HorizontalRule(val rune: Char = '─', override val indent: Int = 0) : Block()
    /** Literal pre-formatted block. Each element is one verbatim source line. */
    data class Literal(val lines: List<String>, override val indent: Int = 0) : Block()
    /**
     * Table. `` `t[lcr][N] `` on its own line toggles table mode
     * (`MicronParser.py:248-275`); the buffered lines are then read as a
     * MARKDOWN table by `MarkdownToMicron.format_table_raw`
     * (`RNS/Utilities/rngit/util.py:530-631`) and re-emitted as micron,
     * which is why cell contents are markup and not plain text.
     *
     * [align] and [maxWidth] come from the toggle's flags and describe
     * the table as a whole: where the box sits on the page, and its
     * width budget in characters. Per-COLUMN alignment is a different
     * thing entirely — it comes from the `:---:` spec row and lives in
     * [columnAligns].
     */
    data class Table(
        /** Placement of the whole table, from `` `t[lcr] ``. */
        val align: Align? = null,
        /** Width budget in characters, from `` `tN ``. Upstream defaults
         *  to `MAX_TABLE_WIDTH = 100` and shrinks the widest columns to
         *  fit it. */
        val maxWidth: Int? = null,
        /** Row 0 — always present; a table without one does not parse. */
        val header: List<List<Inline>> = emptyList(),
        /** Per-column alignment from the row-1 spec, one entry per header
         *  cell (`:---:` centre, `---:` right, anything else left). */
        val columnAligns: List<Align> = emptyList(),
        /** Rows 2+, each padded/truncated to [header]'s column count. */
        val rows: List<List<List<Inline>>> = emptyList(),
        override val indent: Int = 0,
    ) : Block()
    /** Server-side include placeholder per MicronParser.py:95-141.
     *  The renderer asynchronously fetches [url] on the current node
     *  and substitutes the response in place; if [refreshSeconds] is
     *  set, repeats on a timer (refresh < 1s is dropped per upstream).
     *  [fields] carries optional `key=value` parameters; entries
     *  starting with `pid=` identify the partial for sender-side
     *  swap. */
    data class Partial(
        val url: String,
        val refreshSeconds: Double? = null,
        val fields: List<String> = emptyList(),
        /** The `pid=<id>` entry's value, if the page declared one
         *  (`MicronParser.py:178-183`). A `p:<id>` link refreshes the
         *  partials carrying these ids without reloading the page
         *  (`Browser.py:288-291`). */
        val partialId: String? = null,
        override val indent: Int = 0,
    ) : Block()
}

sealed class Inline {
    data class Text(val text: String, val style: InlineStyle = InlineStyle()) : Inline()
    data class Link(val label: String, val target: String, val fields: List<String> = emptyList(), val style: InlineStyle = InlineStyle()) : Inline()

    /**
     * Form input declared by `` `<flags|name`value> `` micron syntax.
     *
     * Wire shape per `nomadnet/ui/textui/MicronParser.py:600-680` (upstream
     * fetched 2026-05-04):
     *   Text input:     `` `<24|message`Initial text> ``  (24 = max width)
     *   Masked input:   `` `<!16|password`> ``
     *   Checkbox:       `` `<?|opt_in`agree> `` value defaults to label, prechecked with `*` 4th comp
     *   Radio button:   `` `<^|color`red`label_text>``     value=red, label=label_text
     *
     * `value` for text inputs is the initial text; for radio/checkbox it's
     * the value sent on submit when checked. `label` is the human-visible
     * caption for radio/checkbox (text inputs render the field empty).
     */
    data class Field(
        val name: String,
        val type: FieldType,
        val width: Int = 24,
        val masked: Boolean = false,
        val value: String = "",     // initial text OR submit-value depending on type
        val label: String = "",     // checkbox/radio caption
        val prechecked: Boolean = false,
        val style: InlineStyle = InlineStyle(),
    ) : Inline()
}

enum class FieldType { TEXT, CHECKBOX, RADIO }

/**
 * Visible width of a run of inline content, in characters — markup
 * excluded.
 *
 * The equivalent of upstream's `_visible_width`
 * (`RNS/Utilities/rngit/util.py:667-674`), which strips the colour and
 * format escapes before measuring so a heavily-styled cell doesn't claim
 * a column's whole width. Ours is exact rather than regex-based: by this
 * point the escapes are already gone and only rendered characters are
 * left. A field counts as its declared width, which is the space its
 * widget will take.
 */
fun List<Inline>.visibleWidth(): Int = sumOf { run ->
    when (run) {
        is Inline.Text -> run.text.length
        is Inline.Link -> run.label.length
        is Inline.Field -> run.width
    }
}

enum class Align { LEFT, CENTER, RIGHT }

data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** 3- or 6-hex color string (no leading #). null = default fg. */
    val fg: String? = null,
    /** 3- or 6-hex color string. null = default bg. */
    val bg: String? = null,
)

/**
 * A full micron document — page-level headers + body blocks. Headers
 * (per Browser.py:1282-1335) are stripped from the body before block-
 * level parsing.
 */
data class MicronDocument(
    /** Server's cache TTL hint from `#!c=N`. null = no header, use
     *  default (12h); 0 = explicit "do not cache". */
    val cacheTtlSeconds: Int? = null,
    /** 3- or 6-hex page background color from `#!bg=` (no leading #).
     *  null = theme default. Malformed values are dropped, not stored. */
    val pageBg: String? = null,
    /** 3- or 6-hex page foreground color from `#!fg=`. */
    val pageFg: String? = null,
    val blocks: List<Block>,
    /**
     * Anchor name → index into [blocks], for `#name` link jumps.
     *
     * Two sources, both per `MicronParser.py` (upstream fetched
     * 2026-09-01):
     *   - an explicit `` `:name `` inline declaration (`:657-668`),
     *     which binds to the block the declaring line produced;
     *   - every heading's auto-slug (`:308-311` →
     *     `slugify_micron(line)`), so a page's table of contents can
     *     link `#section-title` without the author declaring anchors.
     *
     * First declaration of a name wins (`markup_to_attrmaps:124-131`).
     */
    val anchors: Map<String, Int> = emptyMap(),
    /**
     * Indices into [blocks] of every [Block.Heading], in document
     * order — upstream's `header_rows`. A bare `#` link (no name)
     * scrolls to the next heading below the current position
     * (`Browser.py:337-348`).
     */
    val headingBlocks: List<Int> = emptyList(),
)

object Micron {

    /**
     * Parse just the body blocks. Headers are stripped without being
     * surfaced. Backward-compat shim — new callers should use
     * [parseDocument].
     */
    fun parse(source: String): List<Block> = parseDocument(source).blocks

    /**
     * Parse a complete micron document. Strips any leading `#!`
     * headers per Browser.py:1282-1335 — multiple consecutive header
     * lines are accepted; first non-header line ends the header
     * region.
     */
    fun parseDocument(source: String): MicronDocument {
        var cacheTtl: Int? = null
        var pageBg: String? = null
        var pageFg: String? = null
        val sourceLines = source.lines()
        // Header region: consume consecutive lines starting with `#!`.
        var headerEnd = 0
        while (headerEnd < sourceLines.size) {
            val l = sourceLines[headerEnd]
            if (!l.startsWith("#!")) break
            when {
                l.startsWith("#!c=") -> {
                    cacheTtl = l.substring(4).trim().toIntOrNull()
                }
                l.startsWith("#!bg=") -> {
                    val v = l.substring(5).trim()
                    if (isValidHexColor(v)) pageBg = v
                }
                l.startsWith("#!fg=") -> {
                    val v = l.substring(5).trim()
                    if (isValidHexColor(v)) pageFg = v
                }
                // Unknown #! header — silently drop (forward-compat).
            }
            headerEnd++
        }
        val body = parseBlocks(sourceLines.drop(headerEnd))
        return MicronDocument(
            cacheTtlSeconds = cacheTtl,
            pageBg = pageBg,
            pageFg = pageFg,
            blocks = body.blocks,
            anchors = body.anchors,
            headingBlocks = body.headingBlocks,
        )
    }

    /** Body of a parsed page: the blocks plus the anchor index built
     *  alongside them. Internal shape only — callers see
     *  [MicronDocument]. */
    private class ParsedBody(
        val blocks: List<Block>,
        val anchors: Map<String, Int>,
        val headingBlocks: List<Int>,
    )

    private fun parseBlocks(lines: List<String>): ParsedBody {
        val blocks = mutableListOf<Block>()
        // Anchor name → block index. Upstream binds each line's pending
        // anchors to the row index of the widget that line produced
        // (markup_to_attrmaps:124-131); our blocks are the analogue of
        // its rows. First declaration of a name wins, as upstream's
        // `if name not in anchors` guard does.
        val anchors = LinkedHashMap<String, Int>()
        val headingBlocks = mutableListOf<Int>()
        // Anchor names harvested from the line currently being parsed,
        // bound once the block that line produced has been appended.
        val lineAnchors = mutableListOf<String>()

        fun bindAnchors() {
            val index = blocks.lastIndex
            if (index >= 0) {
                for (name in lineAnchors) if (name !in anchors) anchors[name] = index
            }
            lineAnchors.clear()
        }

        var i = 0
        var literalMode = false
        val literalBuf = mutableListOf<String>()
        var align: Align = Align.LEFT
        var depth = 0

        // Table mode per MicronParser.py:248-275. Toggled by `\`t[lcr][N]`
        // on its own line; inside, lines are buffered verbatim and read
        // as a markdown table when the closing `\`t arrives — see
        // [buildTable].
        var tableMode = false
        var tableAlign: Align? = null
        var tableMaxWidth: Int? = null
        val tableBuf = mutableListOf<String>()

        fun flushLiteral() {
            if (literalBuf.isNotEmpty()) {
                blocks += Block.Literal(literalBuf.toList(), indentOf(depth))
                literalBuf.clear()
            }
        }

        fun flushTable() {
            val built = buildTable(
                lines = tableBuf.toList(),
                align = tableAlign,
                maxWidth = tableMaxWidth,
                indent = indentOf(depth),
                anchorsOut = lineAnchors,
            )
            if (built != null) {
                blocks += built
                bindAnchors()
            }
            tableBuf.clear()
            tableAlign = null
            tableMaxWidth = null
        }

        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()

            // Table toggle takes precedence over literal mode (upstream
            // `parse_line` evaluates the table check before the literal
            // mode check at line 194 vs 172). NOT inside `\`= literal:
            // table syntax inside a literal block is preserved verbatim.
            if (!literalMode && trimmed.startsWith("`t") && (trimmed.length == 2 || trimmed.substring(2).all { it.isAsciiAlphaNum() })) {
                if (tableMode) {
                    flushTable()
                    tableMode = false
                } else {
                    tableMode = true
                    val flagsPart = trimmed.substring(2)
                    var rest = flagsPart
                    tableAlign = when (rest.firstOrNull()) {
                        'l' -> { rest = rest.drop(1); Align.LEFT }
                        'c' -> { rest = rest.drop(1); Align.CENTER }
                        'r' -> { rest = rest.drop(1); Align.RIGHT }
                        else -> null
                    }
                    tableMaxWidth = rest.toIntOrNull()
                }
                i++; continue
            }

            if (tableMode) {
                // Buffer verbatim. Empty lines never reach the buffer
                // upstream either — `parse_line` returns before the
                // table-mode append for a zero-length line.
                if (trimmed.isNotEmpty()) tableBuf += trimmed
                i++; continue
            }

            // `` `= `` on its own toggles literal mode.
            if (trimmed == "`=") {
                if (literalMode) flushLiteral()
                literalMode = !literalMode
                i++; continue
            }

            if (literalMode) {
                literalBuf += raw
                i++; continue
            }

            when {
                trimmed.isEmpty() -> { i++; continue }

                // Per-line escape per MicronParser.py:185-187 — a line
                // starting with `\` strips the backslash and the rest
                // is parsed as text, bypassing block-level dispatch
                // (so `\>not a heading` renders `>not a heading` and
                // `\#` survives instead of being dropped).
                trimmed.startsWith("\\") -> {
                    val rest = trimmed.substring(1)
                    if (rest.isNotEmpty()) {
                        val (runs, paraAlign) = parseInline(rest, align, lineAnchors)
                        blocks += Block.Paragraph(paraAlign, runs, indentOf(depth))
                        bindAnchors()
                    }
                    i++
                }

                // Comment line — drop entirely.
                trimmed.startsWith("#") -> { i++; continue }

                // Section depth reset.
                trimmed.startsWith("<") -> {
                    depth = 0
                    // Treat the rest as a normal line.
                    val body = trimmed.substring(1)
                    if (body.isNotEmpty()) {
                        blocks += parseLineToBlock(body, align, depth, lineAnchors)
                        (blocks.last() as? Block.Heading)?.let { depth = it.level }
                        if (blocks.last() is Block.Heading) headingBlocks += blocks.lastIndex
                        bindAnchors()
                    }
                    i++
                }

                // Heading: count leading `>` for depth.
                // Per MicronParser.py:179-182 — a `>`-line containing
                // `` `< `` (a form field) is demoted to a normal line
                // because urwid's Text-with-edit-widget composition
                // doesn't fit the heading widget. Strip the leading
                // `>`s and parse as paragraph.
                trimmed.startsWith(">") -> {
                    if ("`<" in trimmed) {
                        val body = trimmed.trimStart('>')
                        val (runs, paraAlign) = parseInline(body, align, lineAnchors)
                        blocks += Block.Paragraph(paraAlign, runs, indentOf(depth))
                        bindAnchors()
                        i++
                    } else {
                        var level = 0
                        var pos = 0
                        while (pos < trimmed.length && trimmed[pos] == '>') { level++; pos++ }
                        // The heading SETS the section depth, and does so
                        // before its own line is laid out: upstream
                        // assigns `state["depth"] = i` while counting the
                        // `>`s and only then calls `left_indent(state)`
                        // for the heading itself (MicronParser.py:285-318).
                        // Everything after the heading inherits it, until
                        // the next heading or a `<` reset.
                        depth = level
                        val body = trimmed.substring(pos)
                        val (runs, headingAlign) = parseInline(body.trimStart(), align, lineAnchors)
                        blocks += Block.Heading(level.coerceAtMost(3), headingAlign, runs, indentOf(depth))
                        headingBlocks += blocks.lastIndex
                        // Auto-anchor from the heading's own text, per
                        // MicronParser.py:308-311. Declared anchors on
                        // the same line were harvested first and keep
                        // priority (first-wins in bindAnchors).
                        lineAnchors += slugifyMicron(body)
                        bindAnchors()
                        i++
                    }
                }

                // Partial per MicronParser.py:95-141 + 224-225. A
                // line starting with `\`{` is a server-side include
                // placeholder; renderer fetches the inner URL async
                // and substitutes the result. If parse fails (no
                // closing `}`, empty url), emit nothing — matches
                // upstream's `return None` fallthrough.
                trimmed.startsWith("`{") -> {
                    val parsed = parsePartial(trimmed.substring(2))
                    if (parsed != null) blocks += parsed.copy(indent = indentOf(depth))
                    i++
                }

                // Horizontal rule per MicronParser.py:266-273:
                //   `-`  → HR with default rune U+2500
                //   `-X` → HR with rune X (control chars fall back to U+2500)
                // 3+ chars starting with `-` are upstream-literal text.
                trimmed == "-" -> {
                    blocks += Block.HorizontalRule('─', indentOf(depth))
                    i++
                }

                trimmed.length == 2 && trimmed[0] == '-' -> {
                    val rune = trimmed[1]
                    val safeRune = if (rune.code < 32) '─' else rune
                    blocks += Block.HorizontalRule(safeRune, indentOf(depth))
                    i++
                }

                else -> {
                    // Gather consecutive non-empty, non-block-special lines
                    // into one paragraph. Per MicronParser.py:82-93 each
                    // source line of a paragraph becomes its own urwid.Text
                    // widget — i.e. line breaks are HARD. We concatenate
                    // with `\n` so renderers honor the author's wrap
                    // (Compose Text auto-soft-wraps inside each segment).
                    val buf = StringBuilder()
                    while (i < lines.size) {
                        val cur = lines[i].trimEnd()
                        if (cur.isEmpty()) break
                        if (cur.startsWith(">") || cur.startsWith("<") || cur.startsWith("#")) break
                        if (cur == "`=") break
                        if (cur.startsWith("\\")) break  // per-line escape lines stand alone
                        // HR: only the upstream forms (`-` and `-X`).
                        // `---` / `===` / `\=` are upstream-literal text
                        // and stay in the paragraph.
                        if (cur == "-") break
                        if (cur.length == 2 && cur[0] == '-') break
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(cur)
                        i++
                    }
                    val (runs, paraAlign) = parseInline(buf.toString(), align, lineAnchors)
                    blocks += Block.Paragraph(paraAlign, runs, indentOf(depth))
                    bindAnchors()
                }
            }
        }

        if (literalMode) flushLiteral()
        if (tableMode) flushTable()  // unclosed `\`t — emit what we have
        return ParsedBody(blocks, anchors, headingBlocks)
    }

    /**
     * Section indent in steps for a block at section [depth], per
     * `left_indent()` (`MicronParser.py:418-422`): `(depth-1)*SECTION_INDENT`,
     * so depth 0 and depth 1 are both flush and each further level steps
     * in once.
     */
    private fun indentOf(depth: Int): Int = (depth - 1).coerceAtLeast(0)

    /**
     * Read the lines buffered between a `` `t ``/`` `t `` pair as a
     * markdown table.
     *
     * This is markdown, not micron: upstream hands the buffer to
     * `MarkdownToMicron.format_table_raw`
     * (`RNS/Utilities/rngit/util.py:530-631`), which takes row 0 as the
     * header, row 1 as the alignment spec, and rows 2+ as data, then
     * re-emits the whole thing as micron box-drawing lines that
     * `render_table` feeds back through `parse_line`
     * (`MicronParser.py:197-218`). Two consequences we have to match:
     *
     *  - **Row 1 is consumed whatever it contains.** It is read for
     *    `:---:` alignment markers and never rendered, so a table
     *    without a separator row silently loses its first data row
     *    upstream as well.
     *  - **Cells are micron.** Because the re-emitted lines go back
     *    through the inline parser, formatting and links inside a cell
     *    are live — which is why cells here are `List<Inline>` and not
     *    `String`.
     *
     * Fewer than two buffered lines renders nothing at all
     * (`MicronParser.py:198` returns `None`), so we return null and the
     * caller emits no block.
     */
    private fun buildTable(
        lines: List<String>,
        align: Align?,
        maxWidth: Int?,
        indent: Int,
        anchorsOut: MutableList<String>,
    ): Block.Table? {
        if (lines.size < 2) return null
        val headerCells = splitCells(lines[0])
        if (headerCells.isEmpty()) return null
        val specs = splitCells(lines[1])
        val columnAligns = List(headerCells.size) { col ->
            val spec = specs.getOrNull(col)?.trim().orEmpty()
            when {
                spec.length >= 2 && spec.startsWith(":") && spec.endsWith(":") -> Align.CENTER
                spec.endsWith(":") -> Align.RIGHT
                else -> Align.LEFT
            }
        }
        fun parseRow(cells: List<String>): List<List<Inline>> =
            List(headerCells.size) { col ->
                parseInline(cells.getOrElse(col) { "" }, Align.LEFT, anchorsOut).first
            }
        return Block.Table(
            align = align,
            maxWidth = maxWidth,
            header = parseRow(headerCells),
            columnAligns = columnAligns,
            rows = lines.drop(2).map { parseRow(splitCells(it)) },
            indent = indent,
        )
    }

    /**
     * Split one table row into raw cell strings per `_parse_table_row`
     * (`RNS/Utilities/rngit/util.py:633-654`): a leading and trailing
     * `|` are dropped, a backslash escapes the next character (so `\|`
     * is a literal pipe inside a cell), and each cell is trimmed.
     *
     * The backslash is consumed HERE, before the inline parser sees the
     * cell — the same order upstream runs them in.
     */
    private fun splitCells(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|")) body = body.dropLast(1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var escaped = false
        for (ch in body) {
            when {
                escaped -> { cur.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == '|' -> { cells += cur.toString().trim(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        cells += cur.toString().trim()
        return cells
    }

    /**
     * Parse a partial body (the part inside `\`{ }`) per
     * MicronParser.py:95-141. Returns null if malformed (no closing
     * `}`, empty url, parse error) so the caller can drop the line.
     */
    private fun parsePartial(body: String): Block.Partial? {
        val close = body.indexOf('}')
        if (close < 0) return null
        val data = body.substring(0, close)
        val parts = data.split('`')
        val url: String
        var refresh: Double? = null
        var fields: List<String> = emptyList()
        when (parts.size) {
            1 -> { url = parts[0] }
            2 -> {
                url = parts[0]
                refresh = parts[1].toDoubleOrNull()
            }
            3 -> {
                url = parts[0]
                refresh = parts[1].toDoubleOrNull()
                fields = parts[2].split('|').filter { it.isNotEmpty() }
            }
            else -> return null  // upstream zeroes everything; we drop the line
        }
        // Per MicronParser.py:121: refresh < 1s is dropped to defend
        // against partials configured to spam the link.
        if (refresh != null && refresh < 1.0) refresh = null
        if (url.isEmpty()) return null
        // `pid=<id>` names this partial so a `p:<id>` link can refresh
        // just this placeholder (MicronParser.py:178-183).
        val partialId = fields.firstOrNull { it.startsWith("pid=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
        return Block.Partial(url, refresh, fields, partialId)
    }

    /**
     * Heading-line shortcut: parse inline + wrap in a Heading. (Used by
     * the `<` reset path after stripping the leading `<`.)
     */
    private fun parseLineToBlock(
        body: String,
        defaultAlign: Align,
        depth: Int,
        anchorsOut: MutableList<String>,
    ): Block {
        if (body.startsWith(">")) {
            var level = 0
            var pos = 0
            while (pos < body.length && body[pos] == '>') { level++; pos++ }
            val rest = body.substring(pos)
            val (runs, hAlign) = parseInline(rest.trimStart(), defaultAlign, anchorsOut)
            anchorsOut += slugifyMicron(rest)
            return Block.Heading(level.coerceAtMost(3), hAlign, runs, indentOf(level))
        }
        val (runs, pAlign) = parseInline(body, defaultAlign, anchorsOut)
        return Block.Paragraph(pAlign, runs, indentOf(depth))
    }

    /**
     * Inline parser. Returns the list of styled runs + the line-level
     * alignment (an `` `c `` / `` `l `` / `` `r `` / `` `a `` command
     * mid-line affects subsequent runs but it's effectively a paragraph
     * property in upstream — last-wins is fine for our renderer).
     */
    internal fun parseInline(
        text: String,
        defaultAlign: Align = Align.LEFT,
        /** Anchor names declared inline with `` `:name ``, appended in
         *  document order. `null` when the caller doesn't collect them
         *  (the declaration is still consumed, never rendered). */
        anchorsOut: MutableList<String>? = null,
    ): Pair<List<Inline>, Align> {
        val out = mutableListOf<Inline>()
        var style = InlineStyle()
        var align = defaultAlign
        val buf = StringBuilder()

        fun flushText() {
            if (buf.isNotEmpty()) {
                out += Inline.Text(buf.toString(), style)
                buf.clear()
            }
        }

        var i = 0
        var escape = false
        while (i < text.length) {
            val c = text[i]
            when {
                // Backslash-escape: `\\` is literal backslash, `\` `` is
                // literal backtick, otherwise pass through.
                c == '\\' && !escape -> {
                    escape = true
                    i++
                }

                // Format mode flip — only when not escaped.
                c == '`' && !escape -> {
                    if (i + 1 >= text.length) { i++; continue }
                    val cmd = text[i + 1]
                    when (cmd) {
                        '!' -> { flushText(); style = style.copy(bold = !style.bold); i += 2 }
                        '_' -> { flushText(); style = style.copy(underline = !style.underline); i += 2 }
                        '*' -> { flushText(); style = style.copy(italic = !style.italic); i += 2 }
                        'f' -> { flushText(); style = style.copy(fg = null); i += 2 }
                        'b' -> { flushText(); style = style.copy(bg = null); i += 2 }
                        'F' -> {
                            flushText()
                            // `FT followed by 6 hex = true-color
                            if (i + 8 < text.length && text[i + 2] == 'T' &&
                                text.substring(i + 3, i + 9).all { it.isHex() }) {
                                style = style.copy(fg = text.substring(i + 3, i + 9))
                                i += 9
                            } else if (i + 4 < text.length && text.substring(i + 2, i + 5).all { it.isHex() }) {
                                style = style.copy(fg = text.substring(i + 2, i + 5))
                                i += 5
                            } else { i += 2 }
                        }
                        'B' -> {
                            flushText()
                            if (i + 8 < text.length && text[i + 2] == 'T' &&
                                text.substring(i + 3, i + 9).all { it.isHex() }) {
                                style = style.copy(bg = text.substring(i + 3, i + 9))
                                i += 9
                            } else if (i + 4 < text.length && text.substring(i + 2, i + 5).all { it.isHex() }) {
                                style = style.copy(bg = text.substring(i + 2, i + 5))
                                i += 5
                            } else { i += 2 }
                        }
                        'c' -> { flushText(); align = Align.CENTER; i += 2 }
                        'l' -> { flushText(); align = Align.LEFT; i += 2 }
                        'r' -> { flushText(); align = Align.RIGHT; i += 2 }
                        'a' -> { flushText(); align = defaultAlign; i += 2 }
                        '`' -> {
                            // Full reset.
                            flushText()
                            style = InlineStyle()
                            align = defaultAlign
                            i += 2
                        }
                        ':' -> {
                            // Anchor declaration `` `:anchor-name ``,
                            // per MicronParser.py:657-668. Zero-width:
                            // the name is consumed and reported to the
                            // caller, nothing is rendered. Terminated by
                            // the first char outside `[A-Za-z0-9_-]`,
                            // exactly as upstream's scan does — so
                            // `` `:top Back to top `` declares `top` and
                            // renders " Back to top".
                            var end = i + 2
                            while (end < text.length && isAnchorNameChar(text[end])) end++
                            val name = text.substring(i + 2, end)
                            if (name.isNotEmpty()) anchorsOut?.add(name)
                            i = end
                        }
                        '[' -> {
                            // Link: `[label`url] / `[label`url`fields] / `[url]
                            val close = text.indexOf(']', i + 2)
                            if (close < 0) { buf.append(c); i++; continue }
                            flushText()
                            val inside = text.substring(i + 2, close)
                            val parts = inside.split('`')
                            val label: String
                            val target: String
                            val fields: List<String>
                            when (parts.size) {
                                1 -> { label = parts[0]; target = parts[0]; fields = emptyList() }
                                2 -> { label = parts[0]; target = parts[1]; fields = emptyList() }
                                else -> {
                                    label = parts[0]
                                    target = parts[1]
                                    fields = parts[2].split('|').filter { it.isNotEmpty() }
                                }
                            }
                            out += Inline.Link(label = label.ifEmpty { target }, target = target, fields = fields, style = style)
                            i = close + 1
                        }
                        '<' -> {
                            // Form field per upstream MicronParser.py:598-687
                            // (master fetched 2026-05-04). Wire form:
                            //
                            //   `<flags|name|value|*`label>
                            //
                            // Left of the backtick: pipe-separated
                            //   flags|name|value|*  (4 comps for checkbox/radio)
                            //   flags|name          (2 comps for text input)
                            //   name                (1 comp = no flags)
                            // Right of the backtick: the label (checkbox/radio)
                            //   OR the initial value (text input).
                            //
                            // flags is optional digits (max width) + a single
                            // prefix flag char: `!` = masked, `?` = checkbox,
                            // `^` = radio.
                            val backtick = text.indexOf('`', i + 2)
                            val end = if (backtick > 0) text.indexOf('>', backtick) else -1
                            if (end <= 0) { i += 2; continue }
                            flushText()
                            val flagsAndName = text.substring(i + 2, backtick)
                            val afterTick = text.substring(backtick + 1, end)

                            // Split the LEFT half on `|`. Up to 4 components
                            // for checkbox/radio: [flags, name, value, *].
                            val pipeParts = flagsAndName.split('|')
                            var rawFlags = pipeParts.getOrNull(0) ?: ""
                            val name = if (pipeParts.size >= 2) pipeParts[1] else (pipeParts[0].also { rawFlags = "" })

                            var fieldType = FieldType.TEXT
                            var masked = false
                            when {
                                rawFlags.contains('^') -> { fieldType = FieldType.RADIO;    rawFlags = rawFlags.replace("^", "") }
                                rawFlags.contains('?') -> { fieldType = FieldType.CHECKBOX; rawFlags = rawFlags.replace("?", "") }
                                rawFlags.contains('!') -> { masked = true;                  rawFlags = rawFlags.replace("!", "") }
                            }
                            val width = rawFlags.toIntOrNull()?.coerceAtMost(256) ?: 24

                            val leftValue = pipeParts.getOrNull(2) ?: ""
                            val leftPrechecked = pipeParts.getOrNull(3) == "*"

                            // Decode based on type. afterTick is the label
                            // for checkbox/radio, the initial value for text.
                            val value: String
                            val label: String
                            val prechecked: Boolean
                            when (fieldType) {
                                FieldType.TEXT -> {
                                    value = afterTick
                                    label = ""
                                    prechecked = false
                                }
                                FieldType.CHECKBOX, FieldType.RADIO -> {
                                    label = afterTick
                                    // Per MicronParser.py:672 —
                                    //   value = field_value if field_value else field_data
                                    // i.e. when the left-side value is empty,
                                    // the label doubles as the submit value.
                                    value = leftValue.ifEmpty { afterTick }
                                    prechecked = leftPrechecked
                                }
                            }
                            // Security S3 (v0.1.60): server-side these
                            // names become env-var keys (Node.py:109-111
                            // does `field_<name>=<value>`). A malicious
                            // page declaring `\nLD_PRELOAD=x.so` would
                            // smuggle bytes through handlers that mishandle
                            // keys. Drop any field whose name has chars
                            // outside `[A-Za-z0-9_-]` or is empty —
                            // valid upstream field names are well within
                            // this set.
                            if (isValidFieldName(name)) {
                                out += Inline.Field(
                                    name = name, type = fieldType, width = width, masked = masked,
                                    value = value, label = label, prechecked = prechecked, style = style,
                                )
                            }
                            i = end + 1
                        }
                        else -> {
                            // Unknown command — drop the backtick + cmd
                            // byte (matches upstream silent-drop behavior).
                            i += 2
                        }
                    }
                }

                // `\` followed by something — keep the literal char.
                escape -> {
                    buf.append(c)
                    escape = false
                    i++
                }

                else -> { buf.append(c); i++ }
            }
        }
        flushText()
        return out to align
    }
}

private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal fun isAnchorNameChar(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

/**
 * Micron slug for a heading line, byte-compatible with upstream
 * `MicronParser.slugify_micron` (`MicronParser.py:69-81`): strip the
 * format escapes a heading can carry, collapse every remaining run of
 * non-alphanumerics to a single `-`, trim `-` off both ends, lower-case.
 *
 * This has to match upstream exactly or a page's own table of contents
 * (`` `[Rules`#rules] `` against a `>Rules` heading) misses.
 */
internal fun slugifyMicron(text: String): String {
    val stripped = MICRON_STRIP_RE.replace(text, "")
    return NON_ALNUM_RE.replace(stripped, "-").trim('-').lowercase()
}

private val MICRON_STRIP_RE = Regex(
    "`[FB]T[0-9a-fA-F]{6}" +
        "|`[FB][0-9a-fA-F]{3}" +
        "|`:[A-Za-z0-9_-]*" +
        "|`[!*_=fbacrl`<>{]"
)

private val NON_ALNUM_RE = Regex("[^A-Za-z0-9]+")

/**
 * Field-name gate (security S3, v0.1.60).
 *
 * Server-side these names become env-var keys — `Node.py:109-111` does
 * `env_map["field_" + name] = value` with no validation of its own — so
 * a page declaring a name containing a newline, an `=`, or a shell
 * metacharacter could smuggle a second variable through any handler
 * that serialises the map into a `KEY=VALUE` file or shell fragment.
 *
 * Upstream imposes no charset at all; we keep the allowlist. It is
 * stricter than every real field name observed in the wild
 * (`username`, `password`, `action`, `message`, `opt-in`), and a page
 * that steps outside it renders no input rather than a silently
 * unsubmittable one. Revisit only with a real page that needs a wider
 * set — this was reconsidered 2026-09-01 during the anchor/forms work
 * and deliberately left as it is.
 */
private fun isValidFieldName(s: String): Boolean {
    if (s.isEmpty()) return false
    return s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
}

private fun isValidHexColor(s: String): Boolean =
    (s.length == 3 || s.length == 6) && s.all { it.isHex() }

private fun Char.isAsciiAlphaNum(): Boolean =
    this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'
