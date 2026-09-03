package io.github.thatsfguy.reticulum.nomad

/**
 * Does this run of lines draw a box?
 *
 * Micron pages are written for an 80-column terminal, where everything
 * is monospace and a `╔══╗ ║ ╚══╝` frame lines up for free. Rendered in
 * a proportional body font at phone width, the same frame does two
 * things at once: the border runs wrap onto a second row, and the
 * columns cannot align even where they fit. What the reader gets is a
 * staircase of stray bars.
 *
 * A paragraph that trips this is therefore treated as preformatted:
 * monospace, no wrapping, clipped at the viewport with its own
 * horizontal scroller, so the frame keeps its geometry and the long
 * lines simply end at the screen edge (#58).
 *
 * The trigger is a **run** of [MIN_BOX_RUN] or more consecutive
 * box-drawing characters, not their mere presence. Prose that mentions
 * a single `─` stays prose; a border does not.
 *
 * ASCII-fallback frames (`+---+`, `|...|`) are deliberately NOT caught:
 * `-` and `|` are ordinary punctuation, and a false positive costs a
 * paragraph of readable prose its wrapping — a worse trade than leaving
 * an ASCII frame to wrap.
 */
fun looksPreformatted(lines: List<String>): Boolean = lines.any { line ->
    var run = 0
    for (ch in line) {
        if (ch in BOX_DRAWING_CHARS) {
            run++
            if (run >= MIN_BOX_RUN) return@any true
        } else {
            run = 0
        }
    }
    false
}

/** Consecutive box-drawing characters needed to call a line a border. */
private const val MIN_BOX_RUN = 4

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
