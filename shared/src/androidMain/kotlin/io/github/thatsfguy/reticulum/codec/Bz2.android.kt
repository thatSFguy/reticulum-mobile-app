package io.github.thatsfguy.reticulum.codec

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

actual fun bz2Decompress(input: ByteArray, maxBytes: Int): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    require(maxBytes > 0) { "bz2Decompress maxBytes must be positive, got $maxBytes" }
    BZip2CompressorInputStream(ByteArrayInputStream(input)).use { bis ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = bis.read(buf)
            if (n < 0) break
            // Cap on the running output total: a bz2 bomb can expand a few-KB
            // input to gigabytes. We refuse to allocate beyond the caller's
            // budget instead of OOM'ing the device. Cap is exclusive on
            // overflow — exactly maxBytes is allowed.
            check(out.size() + n <= maxBytes) {
                "bz2Decompress output exceeds maxBytes=$maxBytes (would-be ${out.size() + n}); aborting"
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
