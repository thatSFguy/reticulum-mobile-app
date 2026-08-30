package io.github.thatsfguy.reticulum.rrc

/**
 * The single-emoji rule for RRC reactions (`rrc-extensions.md` §2).
 *
 * > `K_BODY` **MUST** be a single user-perceived emoji (one grapheme
 * > cluster), normalised to **NFC**. […] Receivers **SHOULD** compare
 * > reaction strings by exact byte equality after NFC, and **MAY**
 * > decline to display anything that is not a single grapheme cluster —
 * > this keeps a "reaction" from becoming an unbounded second message
 * > body.
 *
 * That last sentence is the security property, not a style preference:
 * a reaction body is attacker-chosen text that bypasses whatever a
 * client does to ordinary message rendering, so it is bounded here on
 * both the send and receive paths.
 *
 * **What this is not.** Kotlin common has no UAX-29 grapheme segmenter
 * and no NFC normaliser, so this is a deliberate approximation:
 *
 *  - *Clustering* is approximated by counting code points that can
 *    START a cluster. Everything that only ever extends one (combining
 *    marks, variation selectors, skin-tone modifiers, keycaps, tag
 *    characters) is not counted, and a zero-width joiner welds the base
 *    that follows it onto the current cluster rather than starting a
 *    new one — which is what makes a four-person family emoji one
 *    reaction and not four. One base, or exactly two regional
 *    indicators (a flag), is a single cluster. This accepts every real
 *    emoji including ZWJ sequences and skin tones, and rejects the
 *    thing that matters — a sentence.
 *  - *Normalisation* is not performed; comparison is exact string
 *    equality on what arrived. Emoji are overwhelmingly already NFC and
 *    the spec tells senders to emit NFC, so the residual cost is that
 *    two spellings of the same reaction would aggregate as two entries
 *    rather than being merged — cosmetic, not a correctness or safety
 *    problem.
 */
object RrcReactions {

    /** Hard ceiling on UTF-16 units, before any cluster analysis. The
     *  longest plausible real emoji (a four-person ZWJ family with skin
     *  tones) is well under this. */
    private const val MAX_UNITS = 32

    private const val ZWJ = 0x200D
    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F
    private const val KEYCAP = 0x20E3
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF
    private val TAG_CHARS = 0xE0020..0xE007F

    /**
     * True when [s] is plausibly one user-perceived emoji, and so may be
     * sent or displayed as a reaction.
     */
    fun isPlausibleReaction(s: String): Boolean {
        if (s.isEmpty() || s.length > MAX_UNITS) return false

        var bases = 0
        var regionals = 0
        // A zero-width joiner welds the NEXT base onto the current
        // cluster instead of starting a new one — that is what makes
        // 👨‍👩‍👧‍👦 one emoji rather than four.
        var joined = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAtCompat(i)
            i += if (cp > 0xFFFF) 2 else 1

            // Control characters and whitespace are never part of an
            // emoji, and a reaction "body" containing them is text.
            if (cp < 0x20 || cp == 0x7F || cp == 0x20 || cp == 0x09 ||
                cp == 0x0A || cp == 0x0D
            ) {
                return false
            }
            when {
                cp == ZWJ -> {
                    // A joiner with nothing before it to join is malformed.
                    if (bases == 0 && regionals == 0) return false
                    joined = true
                }
                // Extenders — they modify the preceding base rather
                // than starting a new cluster.
                cp == VS15 || cp == VS16 || cp == KEYCAP -> Unit
                cp in SKIN_TONES -> Unit
                cp in TAG_CHARS -> Unit
                isCombiningMark(cp) -> Unit
                // A flag is exactly two regional indicators.
                cp in REGIONAL_INDICATORS -> {
                    regionals++
                    if (regionals > 2) return false
                }
                else -> {
                    // Welded to the previous base by a ZWJ, so it
                    // continues the cluster rather than starting one.
                    if (joined) joined = false else bases++
                    if (bases > 1) return false
                }
            }
        }
        // Either one ordinary base, or a regional-indicator pair — not
        // both, and not nothing (a lone modifier is not a reaction).
        // A trailing joiner is an unterminated cluster, not an emoji.
        if (joined) return false
        return when {
            regionals > 0 -> bases == 0 && regionals == 2
            else -> bases == 1
        }
    }

    /** The combining ranges an emoji sequence can legitimately contain.
     *  Not exhaustive Unicode — enough to avoid miscounting the marks
     *  that actually appear in emoji and in text a peer might send. */
    private fun isCombiningMark(cp: Int): Boolean =
        cp in 0x0300..0x036F ||   // combining diacriticals
            cp in 0x1AB0..0x1AFF ||
            cp in 0x1DC0..0x1DFF ||
            cp in 0x20D0..0x20FF ||   // combining marks for symbols
            cp in 0xFE00..0xFE0F ||   // variation selectors
            cp in 0xFE20..0xFE2F

    /** `String.codePointAt` is JVM-only; this is the common equivalent. */
    private fun String.codePointAtCompat(index: Int): Int {
        val high = this[index]
        if (high.isHighSurrogate() && index + 1 < length) {
            val low = this[index + 1]
            if (low.isLowSurrogate()) {
                return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
            }
        }
        return high.code
    }
}
