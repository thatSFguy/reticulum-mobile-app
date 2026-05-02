package io.github.thatsfguy.reticulum.codec

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

actual fun bz2Decompress(input: ByteArray): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    BZip2CompressorInputStream(ByteArrayInputStream(input)).use { bis ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = bis.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
