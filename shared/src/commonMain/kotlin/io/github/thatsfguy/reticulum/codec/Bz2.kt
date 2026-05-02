package io.github.thatsfguy.reticulum.codec

/**
 * Decompress a single bz2 stream. Used by [Resource] when the
 * propagation node (or any LXMF resource sender) compressed the
 * payload before chunking.
 *
 * Implementations should throw on malformed input rather than return
 * partial data — the caller verifies the full-data SHA-256 against
 * the resource hash and a partial decompress would mask corruption.
 */
expect fun bz2Decompress(input: ByteArray): ByteArray
