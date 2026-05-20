package io.github.thatsfguy.reticulum.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `avatarColors` is the Reticulum port of Meshtastic-Android's
 * `Node.colors` (core/model Node.kt). The contract:
 *
 *   1. Treat the first 3 bytes of the hex hash as RGB.
 *   2. Compute perceptual luminance via the standard rec.601 weights
 *      (0.299 R + 0.587 G + 0.114 B).
 *   3. If luminance > 0.5 the background is light enough for black
 *      text, otherwise use white text.
 *
 * Stability: identical input → identical output (lookup is pure).
 */
class AvatarColorsTest {

    @Test
    fun `bright hash takes black text`() {
        // ffff00 → bright yellow. Luminance ≈ (0.299 + 0.587) = 0.886.
        val c = avatarColors("ffff00112233445566778899aabbccdd")
        assertEquals(0xFFFFFF00.toInt(), c.backgroundArgb)
        assertTrue(c.useDarkText, "bright yellow background → dark text")
    }

    @Test
    fun `dark hash takes white text`() {
        // 100020 → near-black. Luminance ≈ (0.299*0.0625 + 0.114*0.125) ≈ 0.033.
        val c = avatarColors("100020112233445566778899aabbccdd")
        assertEquals(0xFF100020.toInt(), c.backgroundArgb)
        assertTrue(!c.useDarkText, "near-black background → light text")
    }

    @Test
    fun `mid-luminance threshold is exactly 0_5`() {
        // 808080 → r=g=b=128, luminance = 128/255 ≈ 0.502 → just over → dark text.
        val c = avatarColors("808080112233445566778899aabbccdd")
        assertTrue(c.useDarkText)
    }

    @Test
    fun `is deterministic across calls`() {
        val a = avatarColors("a09874d26f3e037ab6ea70473d3d8c95")
        val b = avatarColors("a09874d26f3e037ab6ea70473d3d8c95")
        assertEquals(a, b)
    }

    @Test
    fun `accepts upper case hex`() {
        val lower = avatarColors("a1b2c3" + "00".repeat(13))
        val upper = avatarColors("A1B2C3" + "00".repeat(13))
        assertEquals(lower, upper)
    }

    @Test
    fun `falls back to string hashCode when not hex`() {
        // Non-hex input shouldn't throw — we still want a stable color
        // for any string the caller passes (e.g. an unrecognised label
        // surfaced through the Avatar API before the hash is known).
        val c = avatarColors("not-hex-at-all")
        // ARGB always has alpha=0xFF
        assertEquals(0xFF, (c.backgroundArgb ushr 24) and 0xFF)
    }

    @Test
    fun `accepts short hex by padding with zeros`() {
        // <6 hex chars — still produces a valid color. "abc" → 0xabc000?
        // We define the contract as "use whatever bytes you have, zero-
        // pad if needed". Test that no crash happens.
        val c = avatarColors("abc")
        assertEquals(0xFF, (c.backgroundArgb ushr 24) and 0xFF)
    }
}
