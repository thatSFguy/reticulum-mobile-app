package io.github.thatsfguy.reticulum.protocol

import io.github.thatsfguy.reticulum.transport.toHex
// `kotlin.jvm.JvmInline` is exposed in commonMain on multiplatform
// builds but ISN'T auto-imported the way it is on a pure-JVM project.
// Without an explicit import the iOS/Native compile bails on the
// annotation — JVM was happy because `kotlin.jvm.*` happens to be
// implicitly visible to JVM commonMain.
import kotlin.jvm.JvmInline

/**
 * The 16-byte (128-bit) truncated SHA-256 hash form Reticulum uses
 * everywhere it needs to refer to a packet by hash on the wire — most
 * notably as the destination field of a PROOF packet, where the dest
 * is the original DATA packet's truncated full hash (not a destination
 * hash). Centralizing the truncation prevents the three call sites in
 * `ReticulumEngine` (outgoing-message-hash, opportunistic-proof-emit,
 * link-packet-receipt) from drifting independently.
 *
 * Stored as the lowercase-hex string because every consumer downstream
 * (message repository's packetHash column, log lines, proof-matching
 * lookups) wants it in that shape.
 */
@JvmInline
value class TruncatedHash(val hex: String) {
    init {
        require(hex.length == 32) { "TruncatedHash.hex must be 32 chars, got ${hex.length}" }
    }

    companion object {
        /**
         * Take the first 16 bytes of [fullHash] and lowercase-hex encode them.
         * [fullHash] must be at least 16 bytes; passing a shorter array is a
         * programming error rather than a runtime input concern.
         */
        fun of(fullHash: ByteArray): TruncatedHash {
            require(fullHash.size >= 16) {
                "TruncatedHash.of needs at least 16 bytes, got ${fullHash.size}"
            }
            return TruncatedHash(fullHash.copyOfRange(0, 16).toHex())
        }
    }
}
