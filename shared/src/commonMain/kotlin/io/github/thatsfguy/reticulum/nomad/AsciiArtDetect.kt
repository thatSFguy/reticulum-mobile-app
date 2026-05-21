package io.github.thatsfguy.reticulum.nomad

/**
 * Classification heuristic for Literal blocks in a micron document.
 *
 * Returns true when [lines] look like ASCII / Unicode banner art —
 * the kind of layout NomadNet pages commonly lead with that's tuned
 * for a desktop ~80-col terminal and wraps illegibly when rendered
 * at phone width. The renderer uses this hint to scale the monospace
 * font down to fit the device width instead of preserving size and
 * losing the visual.
 *
 * Three triggers, any one is enough:
 *
 *   1. **Box-drawing characters** — the `─│┌┐└┘├┤┬┴┼` family (plus
 *      double-line `═║╔╗╚╝╠╣╦╩╬` variants) is a near-certain art
 *      marker. ASCII fallbacks for boxes (`+-+`, `|---|`) aren't
 *      caught here — they fall through to the density heuristic.
 *   2. **Ruler / repeated-character lines** — `====`, `----`,
 *      `* * *`, `~~~~`, etc. Four or more repeats of the same
 *      separator char, or three or more `* ` pairs in sequence.
 *   3. **Width + non-alphanumeric density** — max line ≥ 60 chars
 *      AND > 25% of the block's content is non-alphanumeric,
 *      non-whitespace. Prose sits in low single digits; banner art
 *      with lots of `/\|_-=` regularly clears 40%.
 *
 * Empty input is not art (vacuously). Pure code-style blocks
 * (Python, shell) typically fail all three triggers and stay at
 * normal size — that's the intended bias. Detection runs once per
 * block at render time; cheap (single linear pass).
 */
fun isAsciiArtBlock(lines: List<String>): Boolean {
    if (lines.isEmpty()) return false
    if (containsBoxDrawing(lines)) return true
    if (containsRuler(lines)) return true
    val maxLen = maxLineLength(lines)
    if (maxLen < 60) return false
    return nonAlphanumericDensity(lines) > 0.25
}

/** Widest line, in chars. Zero for empty input. Used by the
 *  renderer to compute the font-shrink ratio: target font size =
 *  base * (availableWidth / (maxLen * charWidth)). */
fun maxLineLength(lines: List<String>): Int = lines.maxOfOrNull { it.length } ?: 0

private fun containsBoxDrawing(lines: List<String>): Boolean =
    lines.any { line -> line.any { it in BOX_DRAWING_CHARS } }

private fun containsRuler(lines: List<String>): Boolean =
    lines.any { line -> RULER_PATTERN.containsMatchIn(line) }

private fun nonAlphanumericDensity(lines: List<String>): Double {
    val total = lines.sumOf { it.length }
    if (total == 0) return 0.0
    val nonAlnum = lines.sumOf { line ->
        line.count { c -> !c.isLetterOrDigit() && !c.isWhitespace() }
    }
    return nonAlnum.toDouble() / total
}

private val BOX_DRAWING_CHARS: Set<Char> = setOf(
    // Single-line.
    '─', '│', '┌', '┐', '└', '┘', '├', '┤', '┬', '┴', '┼',
    // Double-line.
    '═', '║', '╔', '╗', '╚', '╝', '╠', '╣', '╦', '╩', '╬',
    // Heavy / mixed.
    '━', '┃', '┏', '┓', '┗', '┛', '┣', '┫', '┳', '┻', '╋',
    // Block elements / shading often used in banners.
    '█', '▓', '▒', '░',
)

/** Four or more repeats of a separator char, OR three or more
 *  `* ` (space-separated star) pairs in a row. Catches `====`,
 *  `----`, `~~~~`, `####`, `____`, `* * *`. */
private val RULER_PATTERN = Regex("""[-=*+~_#]{4,}|(?:\* ){3,}""")
