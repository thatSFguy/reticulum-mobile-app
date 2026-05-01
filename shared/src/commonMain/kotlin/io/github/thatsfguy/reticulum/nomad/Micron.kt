package io.github.thatsfguy.reticulum.nomad

/**
 * Best-effort micron parser for NomadNet pages.
 *
 * This handles the subset that real-world NomadNet pages use heavily:
 *
 *   Headings:        `>title` (h1), `>>title` (h2), `>>>title` (h3)
 *   Horizontal rule: `\=` or `---` on its own line
 *   Bold:            `\B...\b`     (color variants `\Bxxx` are stripped to plain bold)
 *   Underline:       `\!u...\!U`
 *   Foreground:      `\Fxxx...\f`  (xxx is 3-digit hex; rendered via attribute, ignored if unsupported)
 *   Links:           `[label]:/page/path` or `[label]:url` — emitted as a Link node
 *   Literal escape:  `\!` followed by any control character is treated as that character
 *   Paragraph break: blank line
 *
 * What is NOT supported (silently dropped or treated as plain text):
 *   - Centring/alignment (`^text`, `<text`, `<>`)
 *   - Background colours `\Bxxx...\b` (the `\B` heuristic only marks bold)
 *   - Section anchors / form inputs / dynamic fields
 *
 * The output is a list of [Block]s (heading / paragraph / hrule). Each
 * paragraph is a list of [Inline] runs the renderer can apply styles to.
 *
 * Tradeoff: keep this parser deliberately permissive — micron pages from
 * the wild are inconsistently formatted, and a strict parser drops
 * legitimate content. Unrecognised escapes are emitted as plain text so
 * nothing is silently lost.
 */
sealed class Block {
    data class Heading(val level: Int, val text: List<Inline>) : Block()
    data class Paragraph(val runs: List<Inline>) : Block()
    object HorizontalRule : Block()
}

sealed class Inline {
    data class Text(val text: String, val style: InlineStyle = InlineStyle()) : Inline()
    data class Link(val label: String, val target: String, val style: InlineStyle = InlineStyle()) : Inline()
}

data class InlineStyle(
    val bold: Boolean = false,
    val underline: Boolean = false,
    val color: String? = null,   // 3-digit hex like "f00", or null
)

object Micron {

    fun parse(source: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = source.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()

            when {
                trimmed.isEmpty() -> { i++; continue }

                trimmed.startsWith(">") -> {
                    var level = 0
                    var pos = 0
                    while (pos < trimmed.length && trimmed[pos] == '>') { level++; pos++ }
                    val body = trimmed.substring(pos).trimStart()
                    blocks += Block.Heading(level.coerceAtMost(3), parseInline(body))
                    i++
                }

                trimmed == "\\=" || trimmed == "---" || trimmed == "===" -> {
                    blocks += Block.HorizontalRule
                    i++
                }

                else -> {
                    // Gather consecutive non-empty, non-special lines into one paragraph.
                    val buf = StringBuilder()
                    while (i < lines.size) {
                        val cur = lines[i].trimEnd()
                        if (cur.isEmpty()) break
                        if (cur.startsWith(">") || cur == "\\=" || cur == "---") break
                        if (buf.isNotEmpty()) buf.append(' ')
                        buf.append(cur)
                        i++
                    }
                    blocks += Block.Paragraph(parseInline(buf.toString()))
                }
            }
        }
        return blocks
    }

    /**
     * Parse a single line (or paragraph body) into styled inline runs.
     * State machine over format escapes; emits a new run whenever the
     * style changes.
     */
    internal fun parseInline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        var style = InlineStyle()
        val buf = StringBuilder()

        fun flushText() {
            if (buf.isNotEmpty()) {
                out += Inline.Text(buf.toString(), style)
                buf.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    when (val next = text[i + 1]) {
                        '!' -> {
                            // Escape: \! followed by next char is literal-or-style
                            if (i + 2 < text.length) {
                                when (text[i + 2]) {
                                    'u' -> { flushText(); style = style.copy(underline = true); i += 3 }
                                    'U' -> { flushText(); style = style.copy(underline = false); i += 3 }
                                    else -> { buf.append(text[i + 2]); i += 3 }
                                }
                            } else { i += 2 }
                        }
                        'B' -> {
                            // \Bxxx is bold-with-color; \B alone is just bold start.
                            flushText()
                            // peek for 3 hex chars
                            if (i + 4 < text.length && text.substring(i + 2, i + 5).all { it.isHex() }) {
                                style = style.copy(bold = true, color = text.substring(i + 2, i + 5))
                                i += 5
                            } else {
                                style = style.copy(bold = true)
                                i += 2
                            }
                        }
                        'b' -> {
                            flushText()
                            style = style.copy(bold = false, color = null)
                            i += 2
                        }
                        'F' -> {
                            flushText()
                            if (i + 4 < text.length && text.substring(i + 2, i + 5).all { it.isHex() }) {
                                style = style.copy(color = text.substring(i + 2, i + 5))
                                i += 5
                            } else { i += 2 }
                        }
                        'f' -> {
                            flushText()
                            style = style.copy(color = null)
                            i += 2
                        }
                        '\\' -> { buf.append('\\'); i += 2 }
                        else -> {
                            // Unknown escape: include as-is so nothing is silently dropped.
                            buf.append(c); buf.append(next); i += 2
                        }
                    }
                }

                c == '[' -> {
                    // Link form: [label]:target — target keeps its leading ':' and
                    // ends at whitespace or end of string.
                    val close = text.indexOf(']', i + 1)
                    if (close > 0 && close + 1 < text.length && text[close + 1] == ':') {
                        flushText()
                        val label = text.substring(i + 1, close)
                        var end = close + 1
                        while (end < text.length && !text[end].isWhitespace()) end++
                        val target = text.substring(close + 1, end)
                        out += Inline.Link(label, target, style)
                        i = end
                    } else {
                        buf.append(c); i++
                    }
                }

                else -> { buf.append(c); i++ }
            }
        }
        flushText()
        return out
    }
}

private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
