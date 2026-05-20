package io.github.thatsfguy.reticulum.util

/**
 * Hash-derived avatar background + text-contrast pair, used by the
 * Messages / Nodes / detail-sheet Avatar composables on both
 * platforms so each destination gets a distinguishable colour
 * instead of every row sharing the theme's primary container.
 *
 * Ported byte-for-byte from Meshtastic-Android's `Node.colors`
 * (`core/model/.../Node.kt`):
 *
 * ```kotlin
 * val r = (num and 0xFF0000) shr 16
 * val g = (num and 0x00FF00) shr 8
 * val b = num and 0x0000FF
 * val brightness = ((r * 0.299) + (g * 0.587) + (b * 0.114)) / 255
 * val foreground = if (brightness > 0.5) BLACK else WHITE
 * val background = ARGB_OPAQUE or rgb
 * ```
 *
 * Meshtastic seeds from a 32-bit node-num (≈ last 4 bytes of the
 * device MAC). Reticulum identities don't have a node-num; we seed
 * from the first 3 bytes of the 16-byte destination hash hex
 * instead. SHA-256-truncated destination hashes are uniformly
 * distributed so the resulting RGB cube coverage is comparable.
 *
 * Luminance threshold + rec.601 weights are unchanged from Meshtastic
 * so users moving between the two apps see consistent text-contrast
 * behaviour. Non-hex input falls back to `String.hashCode()` so the
 * function never throws — the Avatar API takes any `String` seed.
 */
data class AvatarColors(
    /** Opaque ARGB int — `0xFF rrggbb`. */
    val backgroundArgb: Int,
    /**
     * `true` when the background is light enough for black text /
     * icons to remain legible; `false` for white text. Driven by
     * Meshtastic's `brightness > 0.5` cutoff.
     */
    val useDarkText: Boolean,
)

fun avatarColors(seed: String): AvatarColors {
    val rgb = rgbFromSeed(seed)
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    val brightness = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0
    val backgroundArgb = (0xFF shl 24) or rgb
    return AvatarColors(
        backgroundArgb = backgroundArgb,
        useDarkText = brightness > 0.5,
    )
}

/**
 * Extract a 24-bit RGB seed from the first 6 hex chars of [seed]. If
 * the string is non-hex (no leading 6 hex chars), fall back to
 * `String.hashCode()` so we always produce a deterministic colour —
 * matches Meshtastic's "every Node has a colour" guarantee.
 */
private fun rgbFromSeed(seed: String): Int {
    val cleaned = seed.lowercase()
    var rgb = 0
    var consumed = 0
    for (c in cleaned) {
        if (consumed >= 6) break
        val nibble = when (c) {
            in '0'..'9' -> c.code - '0'.code
            in 'a'..'f' -> c.code - 'a'.code + 10
            else -> continue
        }
        rgb = (rgb shl 4) or nibble
        consumed++
    }
    // Zero-pad on the right when the cleaned hex was shorter than 6.
    if (consumed in 1 until 6) {
        rgb = rgb shl (4 * (6 - consumed))
        return rgb and 0xFFFFFF
    }
    if (consumed == 0) {
        // No hex digits at all — derive from the string's hashCode so
        // every distinct label still gets a distinct stable colour.
        return seed.hashCode() and 0xFFFFFF
    }
    return rgb and 0xFFFFFF
}
