package io.github.thatsfguy.reticulum.codec

import io.github.thatsfguy.reticulum.codec.cinterop.bz2.BZ2_bzBuffToBuffDecompress
import io.github.thatsfguy.reticulum.codec.cinterop.bz2.BZ_OK
import io.github.thatsfguy.reticulum.codec.cinterop.bz2.BZ_OUTBUFF_FULL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * iOS bzip2 decompressor — bridges to the system `libbz2` via the
 * cinterop binding wired in `shared/build.gradle.kts`. Mirrors the
 * Android implementation's contract:
 *
 *   - Empty input → empty output (no error).
 *   - `maxBytes` is a hard ceiling on the decompressed size; if the
 *     payload would exceed it, we abort instead of allocating
 *     unbounded memory. Matches the bz2-bomb defense the Android
 *     side gets from `BZip2CompressorInputStream` + a running counter.
 *   - On success the output is sized to exactly the bytes produced.
 *
 * Hit only on Resource transfers carrying bzip2-compressed payloads
 * (LXMF propagation `/get` round-2 responses, large NomadNet pages
 * over the Resource protocol). Opportunistic LXMF and single-packet
 * pages don't go through bz2.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun bz2Decompress(input: ByteArray, maxBytes: Int): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    require(maxBytes > 0) { "bz2Decompress maxBytes must be positive, got $maxBytes" }

    return memScoped {
        // `BZ2_bzBuffToBuffDecompress` is one-shot: it expects a destination
        // buffer big enough for the entire decompressed payload. We size it
        // to the cap; if the payload would have exceeded the cap, the call
        // returns BZ_OUTBUFF_FULL — exactly the "would-be > maxBytes" abort
        // the Android implementation enforces by tracking a running counter.
        val dest = ByteArray(maxBytes)
        val destLenVar = alloc<UIntVar>().apply { value = maxBytes.toUInt() }

        val rc = input.usePinned { srcPin ->
            dest.usePinned { destPin ->
                BZ2_bzBuffToBuffDecompress(
                    destPin.addressOf(0).reinterpret(),
                    destLenVar.ptr,
                    srcPin.addressOf(0).reinterpret(),
                    input.size.toUInt(),
                    /* small = */ 0,
                    /* verbosity = */ 0,
                )
            }
        }

        when (rc) {
            BZ_OK -> dest.copyOf(destLenVar.value.toInt())
            BZ_OUTBUFF_FULL -> error(
                "bz2Decompress output exceeds maxBytes=$maxBytes; aborting"
            )
            else -> error("bz2Decompress failed with libbz2 code $rc")
        }
    }
}
