package io.github.thatsfguy.reticulum.codec

/**
 * iOS Phase 1 stub. The framework links, but any code path that actually
 * needs bz2 decompression throws at runtime.
 *
 * Phase 2 will replace this with a Kotlin/Native cinterop binding to
 * `/usr/lib/libbz2.dylib` (BZ2_bzBuffToBuffDecompress). libbz2 is
 * bundled with iOS; no SPM dependency, no extra binary to ship.
 *
 * Hit only on Resource transfers carrying bzip2-compressed payloads
 * (LXMF propagation `/get` round-2 responses, large NomadNet pages
 * over the Resource protocol). Opportunistic LXMF and single-packet
 * pages don't go through bz2.
 */
actual fun bz2Decompress(input: ByteArray, maxBytes: Int): ByteArray {
    throw NotImplementedError(
        "bz2Decompress is an iOS Phase 2 task — see todo.md. " +
            "Will bind to /usr/lib/libbz2.dylib via cinterop."
    )
}
