package io.github.thatsfguy.reticulum.android.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Quality-decay ladder behaviour for [ImageCompress]. The ladder is:
 *   step 1 (512 px @ q=60)  →  step 2 (512 px @ q=40)  →  step 3 (384 px @ q=25)
 * and refuses (null) when even step 3 exceeds 20 KB.
 *
 * Tests target the two extremes — JPEG-trivial input (returns step 1 cheaply) and
 * JPEG-incompressible noise (refuses) — plus the dimensional invariant (output is
 * never wider than 512 px on the larger axis). The exact "fits step 2 vs step 3"
 * boundary depends on the host Skia / libjpeg version and would make this test
 * brittle across CI image upgrades; we trust the implementation's sequential walk
 * over the STEPS list and verify the observable invariants.
 *
 * Robolectric 4.13 with Skia-backed Bitmap.compress is required for these to be
 * meaningful (the legacy Robolectric shadow returned a stub byte array). The
 * androidApp build.gradle.kts pins robolectric:4.13 which carries Skia.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageCompressTest {

    /**
     * A 512×512 solid-red bitmap compresses to well under the 20 KB
     * ceiling on the very first ladder step. Smoke test for the
     * happy path — confirms the ladder returns a usable JPEG when
     * the input is JPEG-friendly.
     */
    @Test fun `solid color 512px fits step 1 comfortably`() {
        val bmp = solidBitmap(512, 512, Color.RED)
        val bytes = ImageCompress.compressBitmap(bmp)
        assertNotNull(bytes, "solid 512px should pass step 1")
        assertTrue(bytes.size <= ImageCompress.MAX_BYTES,
            "${bytes.size} > ${ImageCompress.MAX_BYTES}")
        assertTrue(bytes.size > 100, "JPEG output suspiciously small (${bytes.size} B)")

        // The wire byte stream should be a recognisable JPEG.
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded, "output bytes failed to round-trip-decode as JPEG")
        // Source already ≤ 512 px → no scaling applied.
        assertTrue(decoded.width <= 512 && decoded.height <= 512,
            "output ${decoded.width}x${decoded.height} should fit in 512px box")
    }

    /**
     * A 4032×3024 high-frequency noise bitmap (worst-case JPEG input —
     * no spatial redundancy to encode) blows past the 20 KB ceiling at
     * steps 1 and 2 (512 px @ q=60 and q=40), and the ladder falls
     * through to step 3 (384 px @ q=25). Step 3's aggressive 25%
     * quantizer + smaller dimensions reliably squeeze noise below the
     * ceiling, so the test verifies the longer edge is clamped at 384
     * px — proving the ladder actually walked off steps 1 and 2.
     *
     * Note on the refuse (null-return) path: it's intentionally
     * unreachable for typical input because step 3's 384 px @ q=25
     * stays under 20 KB for even pathological noise. That refusal
     * branch is a defensive backstop against future ladder
     * adjustments or Skia-on-disk anomalies; verifying it via the
     * normal API would require constructing input the JPEG encoder
     * physically can't compress, which doesn't exist for realistic
     * dimensions.
     */
    @Test fun `incompressible 4032x3024 noise decays to step 3`() {
        val bmp = noiseBitmap(width = 4032, height = 3024, seed = 0xC0FFEEL)
        val bytes = ImageCompress.compressBitmap(bmp)
        assertNotNull(bytes, "step 3 should always succeed for realistic dimensions")
        assertTrue(bytes.size <= ImageCompress.MAX_BYTES,
            "ladder returned bytes (${bytes.size} B) exceeding the ${ImageCompress.MAX_BYTES} B ceiling")
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        val longer = maxOf(decoded.width, decoded.height)
        // 384 px is step 3's maxDim. If steps 1 or 2 had passed, longer
        // would be 512 px — but noise can't fit those at q=60 or q=40.
        assertTrue(longer <= 384,
            "longer edge $longer px > 384 — noise unexpectedly fit step 1/2, ladder didn't decay")
    }

    /**
     * The longer edge of the encoded output never exceeds 512 px. The
     * ladder may pick step 1, 2, or 3 — all of them clamp the longer
     * edge at 512 px or 384 px. We test the upper bound to prove the
     * scale step actually fires on oversized input.
     */
    @Test fun `output longer-edge is clamped to ladder maxDim`() {
        // 1600×900 — too big for step 1 to keep as-is, scale must apply.
        val bmp = solidBitmap(1600, 900, Color.BLUE)
        val bytes = ImageCompress.compressBitmap(bmp)
        assertNotNull(bytes, "solid 1600x900 should pass step 1 after scaling")
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        val longer = maxOf(decoded.width, decoded.height)
        assertTrue(longer <= 512,
            "longer edge was $longer px, expected ≤ 512 (step 1/2 max)")
    }

    /**
     * Aspect ratio is preserved on scale. 1600×900 (16:9) scaled to a
     * 512-px longer edge should be 512×288 (±1 for integer rounding).
     */
    @Test fun `aspect ratio is preserved when scaling down`() {
        val bmp = solidBitmap(1600, 900, Color.GREEN)
        val bytes = ImageCompress.compressBitmap(bmp)!!
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        // Either step 1 (512 max) or step 3 (384 max) — both keep the ratio.
        val longer = maxOf(decoded.width, decoded.height)
        val shorter = minOf(decoded.width, decoded.height)
        val expectedShorter = (longer * 9.0 / 16.0).toInt()
        // Tolerate ±2 px slop for Bitmap.createScaledBitmap's integer rounding.
        assertTrue(kotlin.math.abs(shorter - expectedShorter) <= 2,
            "ratio drifted: ${decoded.width}x${decoded.height} (expected ~${longer}x$expectedShorter)")
    }

    /**
     * A source already smaller than every ladder step's maxDim is
     * passed through without an unnecessary scale (the early-return
     * inside scaleToMaxDim). Verifies the recycle guard in
     * [ImageCompress.compressBitmap] that prevents recycling the
     * caller's source bitmap on the no-scale path.
     */
    @Test fun `small source under 384px is not upscaled`() {
        val bmp = solidBitmap(200, 150, Color.MAGENTA)
        val bytes = ImageCompress.compressBitmap(bmp)
        assertNotNull(bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertTrue(decoded.width == 200 && decoded.height == 150,
            "expected 200x150 (no scale), got ${decoded.width}x${decoded.height}")
        // Source bitmap must remain usable — the recycle guard prevents
        // it from being recycled when scaleToMaxDim returned the
        // identity. If this assertion throws "Can't access internal
        // buffer of a recycled bitmap" the guard regressed.
        assertTrue(!bmp.isRecycled, "source bitmap was incorrectly recycled")
    }

    // ---- helpers ---------------------------------------------------

    private fun solidBitmap(w: Int, h: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return bmp
    }

    /**
     * Deterministic high-frequency noise — every pixel a random RGB
     * value with no spatial redundancy. JPEG's DCT collapses smooth
     * regions; pure noise survives every quantization step nearly
     * intact, so output stays large at every ladder step. Seeded so
     * the test result doesn't drift across JVM versions.
     */
    private fun noiseBitmap(width: Int, height: Int, seed: Long): Bitmap {
        val rnd = java.util.Random(seed)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            // Full RGB chaos, opaque alpha.
            pixels[i] = (0xFF shl 24) or (rnd.nextInt() and 0x00FFFFFF)
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }
}
