package io.github.thatsfguy.reticulum.android.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * JPEG compression ladder for LXMF image attachments. Wire path is the
 * Reticulum Resource framing (SPEC §10) added in Phase 1; that caps at
 * HASHMAP_MAX_LEN = 84 chunks of 433 bytes ≈ 35.5 KB raw payload before
 * Token encryption + LXMF msgpack wrapping. A 20 KB JPEG ceiling keeps
 * comfortable headroom for the encryption + container overhead and
 * degrades gracefully on slow LoRa links (a 20 KB Resource at SF7 is
 * roughly 4 s of airtime — annoying but tolerable).
 *
 * The ladder mirrors Sideband's image-attachment posture: prefer
 * preserving spatial detail (512 px) over color fidelity, drop to a
 * smaller dimension only as a last resort before refusing.
 *
 *   1. max-dim 512 px, JPEG q=60  →  ≤ 20 KB? ship it.
 *   2. max-dim 512 px, JPEG q=40  →  ≤ 20 KB? ship it.
 *   3. max-dim 384 px, JPEG q=25  →  ≤ 20 KB? ship it.
 *   4. refuse — return null. Caller surfaces an "image too large" toast.
 *
 * The receive side is defensive at a different threshold (32 KB; see
 * Phase 3 in `todo.md`) so a hostile peer can't OOM us with a 10 MB
 * blob even if they bypass this sender-side ceiling.
 */
object ImageCompress {

    /** 20 KB ceiling per the LXMF Resource wire budget above. */
    const val MAX_BYTES: Int = 20 * 1024

    private val STEPS: List<Step> = listOf(
        Step(maxDim = 512, quality = 60),
        Step(maxDim = 512, quality = 40),
        Step(maxDim = 384, quality = 25),
    )

    private data class Step(val maxDim: Int, val quality: Int)

    /**
     * Decode the image at [uri] and run it through the compression
     * ladder. Returns the smallest JPEG ≤ [MAX_BYTES] across the three
     * steps, or null if the URI couldn't be decoded at all OR even
     * step 3 was still too big.
     */
    fun compressForLxmf(uri: Uri, resolver: ContentResolver): ByteArray? {
        val original = decode(uri, resolver) ?: return null
        return try {
            compressBitmap(original)
        } finally {
            original.recycle()
        }
    }

    /**
     * Run [bmp] through the ladder without the URI-decode step. Exposed
     * for unit tests that build synthetic high-frequency-noise bitmaps
     * (JPEG compresses smooth regions aggressively, so a plain
     * solid-color bitmap would fit in 20 KB at every step and miss the
     * actual decay behavior).
     */
    internal fun compressBitmap(bmp: Bitmap): ByteArray? {
        for (step in STEPS) {
            val scaled = scaleToMaxDim(bmp, step.maxDim)
            val bytes = scaled.encodeJpeg(step.quality)
            // `scaled` may be `bmp` itself when the source is already
            // ≤ maxDim — only recycle the fresh copy, otherwise we'd
            // kill `bmp` mid-iteration and the next step would crash
            // on a recycled-bitmap encode.
            if (scaled !== bmp) scaled.recycle()
            if (bytes.size <= MAX_BYTES) return bytes
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
