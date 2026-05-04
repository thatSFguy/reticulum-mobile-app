package io.github.thatsfguy.reticulum.codec

/**
 * Decompress a single bz2 stream. Used by [io.github.thatsfguy.reticulum.resource.Resource]
 * when the propagation node (or any LXMF resource sender) compressed
 * the payload before chunking.
 *
 * [maxBytes] caps the cumulative decompressed output. The decompressor
 * MUST abort + throw [IllegalStateException] if the running total
 * exceeds the cap. This is a defense-in-depth bound against bz2
 * decompression bombs — a small (~tens of KB) compressed input can
 * legitimately expand to gigabytes, which on a phone is OOM. Callers
 * size the cap from the resource advertisement's `dataSize` plus a
 * small tolerance for the prefixed `randomHash`.
 *
 * Implementations should throw on malformed input rather than return
 * partial data — the caller verifies the full-data SHA-256 against
 * the resource hash and a partial decompress would mask corruption.
 */
expect fun bz2Decompress(input: ByteArray, maxBytes: Int): ByteArray
