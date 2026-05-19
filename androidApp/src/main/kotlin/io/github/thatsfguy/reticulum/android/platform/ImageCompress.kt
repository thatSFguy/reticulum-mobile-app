package io.github.thatsfguy.reticulum.android.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import io.github.thatsfguy.reticulum.engine.ImageResolutionTier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * JPEG compression ladders for outbound LXMF image attachments. The
 * user picks an [ImageResolutionTier] in the compose-row "+" → Photo
 * flow; each tier names a byte budget (in the shared enum) and gets a
 * dimension+quality ladder here — `compressForLxmf` ships the first
 * rung whose JPEG lands within budget, else refuses.
 *
 * Tiers run from `FULL` (≤ 4 MB, a high-quality near-full-res JPEG,
 * a TCP-path luxury) down to `MICRO` (≤ 20 KB, the original LoRa-safe
 * tier — a 20 KB Resource is ~4 s of airtime at SF7). Every rung
 * bakes EXIF orientation into the pixels and emits no EXIF, so a
 * camera capture isn't shown rotated and no GPS tag leaks.
 *
 * The receive side caps independently at `INBOUND_ATTACHMENT_MAX_BYTES`
 * (4 MB) so a hostile peer can't bypass this sender-side ceiling.
 */
object ImageCompress {

    private data class Step(val maxDim: Int, val quality: Int)

    /** Dimension/quality ladder per tier — walked top-to-bottom; the
     *  first rung within [ImageResolutionTier.byteBudget] is shipped. */
    private fun tierSteps(tier: ImageResolutionTier): List<Step> = when (tier) {
        ImageResolutionTier.FULL -> listOf(
            Step(2560, 92), Step(2560, 80), Step(2048, 75), Step(1600, 70), Step(1280, 60),
        )
        ImageResolutionTier.MEDIUM -> listOf(
            Step(1600, 80), Step(1280, 70), Step(1024, 60), Step(1024, 45),
        )
        ImageResolutionTier.SMALL -> listOf(
            Step(1024, 70), Step(768, 55), Step(640, 45), Step(512, 35),
        )
        ImageResolutionTier.MICRO -> listOf(
            Step(512, 60), Step(512, 40), Step(384, 25),
        )
    }

    /**
     * Decode the image at [uri] and run it through the [tier]'s
     * compression ladder. Returns the first JPEG within the tier's
     * byte budget, or null if the URI couldn't be decoded OR even the
     * smallest rung was still too big.
     *
     * A camera capture is frequently stored as a sideways pixel buffer
     * plus an EXIF Orientation tag. `Bitmap.compress` writes no EXIF,
     * so the rotation is baked into the pixels here — otherwise the
     * recipient (and our own outgoing bubble) would show it rotated.
     */
    fun compressForLxmf(
        uri: Uri,
        resolver: ContentResolver,
        tier: ImageResolutionTier,
    ): ByteArray? {
        val original = decode(uri, resolver) ?: return null
        val upright = applyExifOrientation(original, readOrientation(uri, resolver))
        return try {
            compressBitmap(upright, tier)
        } finally {
            if (upright !== original) upright.recycle()
            original.recycle()
        }
    }

    /**
     * Decode a stored JPEG attachment to a Bitmap with its EXIF
     * orientation applied. Our own outgoing attachments are baked
     * upright by [compressForLxmf] and carry no EXIF, so this is a
     * no-op for them; it corrects images from peers that ship an
     * EXIF-tagged JPEG. Returns null if [bytes] isn't decodable.
     */
    fun decodeOriented(bytes: ByteArray): Bitmap? {
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull() ?: return null
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val out = applyExifOrientation(bmp, orientation)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /**
     * Decode the JPEG/PNG file at [path] **downsampled** so its longer
     * edge is roughly [maxDimPx] pixels — a two-pass `BitmapFactory`
     * decode (`inJustDecodeBounds` to read dimensions, then a
     * power-of-two `inSampleSize`).
     *
     * A 4 MB camera JPEG full-decodes to a ~40–50 MB `ARGB_8888`
     * bitmap; a conversation timeline of those OOMs. Decoding straight
     * from the attachment-store file path at the bubble (or screen)
     * size keeps heap bounded — the pixels never fully materialise.
     * EXIF orientation is applied so a portrait shot renders upright.
     * Returns null when the file is missing or undecodable.
     *
     * docs/ATTACHMENT-STORE.md §3.6.
     */
    fun decodeDownsampledFile(path: String, maxDimPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(srcW, srcH, maxDimPx)
        }
        val bmp = runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull() ?: return null
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val out = applyExifOrientation(bmp, orientation)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Largest power-of-two `inSampleSize` that keeps both source
     *  dimensions at or above [maxDimPx] — the standard
     *  `BitmapFactory` downsample rule. Visible for unit tests. */
    internal fun computeSampleSize(srcW: Int, srcH: Int, maxDimPx: Int): Int {
        if (maxDimPx <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= maxDimPx || srcH / (sample * 2) >= maxDimPx) {
            sample *= 2
        }
        return sample
    }

    /**
     * Return [bmp] with the rotation / flip described by an EXIF
     * [orientation] tag baked into its pixels. ORIENTATION_NORMAL and
     * any unrecognized value return [bmp] unchanged (the same
     * instance, so the no-transform path allocates nothing). Visible
     * for unit tests.
     */
    internal fun applyExifOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE  -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** EXIF Orientation tag of the image at [uri], or
     *  [ExifInterface.ORIENTATION_NORMAL] when absent / unreadable. */
    private fun readOrientation(uri: Uri, resolver: ContentResolver): Int =
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /**
     * Run [bmp] through the [tier]'s ladder without the URI-decode
     * step. Exposed for unit tests that build synthetic
     * high-frequency-noise bitmaps (JPEG compresses smooth regions
     * aggressively, so a plain solid-color bitmap would fit in any
     * budget at every step and miss the actual decay behavior).
     */
    internal fun compressBitmap(bmp: Bitmap, tier: ImageResolutionTier): ByteArray? {
        val budget = tier.byteBudget
        for (step in tierSteps(tier)) {
            val scaled = scaleToMaxDim(bmp, step.maxDim)
            val bytes = scaled.encodeJpeg(step.quality)
            // `scaled` may be `bmp` itself when the source is already
            // ≤ maxDim — only recycle the fresh copy, otherwise we'd
            // kill `bmp` mid-iteration and the next step would crash
            // on a recycled-bitmap encode.
            if (scaled !== bmp) scaled.recycle()
            if (bytes.size <= budget) return bytes
        }
        return null
    }

    private fun decode(uri: Uri, resolver: ContentResolver): Bitmap? =
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

    /**
     * Scale [bmp] so its longer edge is at most [maxDim] pixels,
     * preserving aspect ratio. Returns [bmp] itself when no scaling
     * is needed.
     */
    private fun scaleToMaxDim(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxDim && h <= maxDim) return bmp
        val ratio = maxDim.toFloat() / maxOf(w, h)
        val tw = (w * ratio).toInt().coerceAtLeast(1)
        val th = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, tw, th, true)
    }

    private fun Bitmap.encodeJpeg(quality: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return baos.toByteArray()
    }
}
