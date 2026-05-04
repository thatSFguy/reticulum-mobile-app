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
 * What this parser still doesn't fully render (parsed but treated as
 * plain-text fallback for now — render layer can promote later):
 *   - Form fields: `` `<flags|name`value> `` (text input, checkbox, radio)
 *   - Tables: `` `t[align][maxwidth] ... cells ... `t ``
 *   - Partials: `` `{path} `` (server-side include — we never have the
 *     content client-side anyway)
 *   - Cache header `#!c=N` (browser-level concern, not parser)
 */
sealed class Block {
    data class Heading(val level: Int, val align: Align, val text: List<Inline>) : Block()
    data class Paragraph(val align: Align, val runs: List<Inline>) : Block()
    object HorizontalRule : Block()
    /** Literal pre-formatted block. Each element is one verbatim source line. */
    data class Literal(val lines: List<String>) : Block()
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

object Micron {

    /**
     * Parse a complete micron document into block-level elements. Strips
     * the optional `#!c=N` cache header on the first line if present —
     * that's a browser hint, not content.
     */
    fun parse(source: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = source.lines().let { all ->
            // Drop a leading cache-control header — `#!c=N` has to be on
            // the FIRST line per upstream Browser.py.
            if (all.isNotEmpty() && all[0].startsWith("#!c=")) all.drop(1) else all
        }

        var i = 0
        var literalMode = false
        val literalBuf = mutableListOf<String>()
        var align: Align = Align.LEFT
        var depth = 0

        fun flushLiteral() {
            if (literalBuf.isNotEmpty()) {
                blocks += Block.Literal(literalBuf.toList())
                literalBuf.clear()
            }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()

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

                // Comment line — drop entirely.
                trimmed.startsWith("#") -> { i++; continue }

                // Section depth reset.
                trimmed.startsWith("<") -> {
                    depth = 0
                    // Treat the rest as a normal line.
                    val body = trimmed.substring(1)
                    if (body.isNotEmpty()) {
                        blocks += parseLineToBlock(body, align, depth)
                    }
                    i++
                }

                // Heading: count leading `>` for depth.
                trimmed.startsWith(">") -> {
                    var level = 0
                    var pos = 0
                    while (pos < trimmed.length && trimmed[pos] == '>') { level++; pos++ }
                    val body = trimmed.substring(pos).trimStart()
                    val (runs, headingAlign) = parseInline(body, align)
                    blocks += Block.Heading(level.coerceAtMost(3), headingAlign, runs)
                    i++
                }

                // Horizontal rule: `---` / `===` / `\=` (legacy) or `-X`
                // where X is a single char (the divider rune).
                trimmed == "---" || trimmed == "===" || trimmed == "\\=" -> {
                    blocks += Block.HorizontalRule
                    i++
                }

                trimmed.startsWith("-") && trimmed.length <= 2 -> {
                    blocks += Block.HorizontalRule
                    i++
                }

                else -> {
                    // Gather consecutive non-empty, non-block-special lines
                    // into one paragraph. Wrapping a paragraph with extra
                    // spaces between source lines is upstream behavior —
                    // soft wrapping is the renderer's job.
                    val buf = StringBuilder()
                    while (i < lines.size) {
                        val cur = lines[i].trimEnd()
                        if (cur.isEmpty()) break
                        if (cur.startsWith(">") || cur.startsWith("<") || cur.startsWith("#")) break
                        if (cur == "---" || cur == "===" || cur == "\\=" || cur == "`=") break
                        if (cur.startsWith("-") && cur.length <= 2) break
                        if (buf.isNotEmpty()) buf.append(' ')
                        buf.append(cur)
                        i++
                    }
                    val (runs, paraAlign) = parseInline(buf.toString(), align)
                    blocks += Block.Paragraph(paraAlign, runs)
                }
            }
        }

        if (literalMode) flushLiteral()
        return blocks
    }

    /**
     * Heading-line shortcut: parse inline + wrap in a Heading. (Used by
     * the `<` reset path after stripping the leading `<`.)
     */
    private fun parseLineToBlock(body: String, defaultAlign: Align, depth: Int): Block {
        if (body.startsWith(">")) {
            var level = 0
            var pos = 0
            while (pos < body.length && body[pos] == '>') { level++; pos++ }
            val rest = body.substring(pos).trimStart()
            val (runs, hAlign) = parseInline(rest, defaultAlign)
            return Block.Heading(level.coerceAtMost(3), hAlign, runs)
        }
        val (runs, pAlign) = parseInline(body, defaultAlign)
        return Block.Paragraph(pAlign, runs)
    }

    /**
     * Inline parser. Returns the list of styled runs + the line-level
     * alignment (an `` `c `` / `` `l `` / `` `r `` / `` `a `` command
     * mid-line affects subsequent runs but it's effectively a paragraph
     * property in upstream — last-wins is fine for our renderer).
     */
    internal fun parseInline(text: String, defaultAlign: Align = Align.LEFT): Pair<List<Inline>, Align> {
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
                            out += Inline.Field(
                                name = name, type = fieldType, width = width, masked = masked,
                                value = value, label = label, prechecked = prechecked, style = style,
                            )
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
