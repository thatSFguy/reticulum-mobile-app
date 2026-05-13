package io.github.thatsfguy.reticulum.lxmf

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/**
 * LXMF stamp proof-of-work per SPEC §5.7.2. Modern Sideband (≥ 1.x)
 * treats stamp-less inbound messages as low-trust and may drop them
 * at the application layer (`LXMRouter.py:1768-1770`). Senders compute
 * a 32-byte stamp value such that
 *
 *   SHA256(workblock || stamp)  has at least  target_cost  leading zero bits
 *
 * where `workblock` is an expensive 768 KiB derivation of the message
 * id, and `target_cost` is the value the recipient advertised in their
 * announce's `app_data[1]` (see `extractStampCost` in Announce.kt).
 *
 * Mirrors `LXMF/LXStamper.py::stamp_workblock` /
 * `generate_stamp` / `stamp_valid`. Verified bytewise against upstream
 * by running [findStamp] then checking the result against a Python
 * `stamp_valid` reference.
 *
 * The workblock construction is intentionally cache-unfriendly: 3000
 * rounds of 256-byte HKDF blocks, each salted with `SHA256(material
 * || msgpack(n))`. Total memory = 768 KiB, designed to resist GPU /
 * ASIC speedup. The stamp search is the SHA256-of-768KB-loop that
 * scales as 2^target_cost on average.
 */
object LxmfStamp {

    /** SPEC §5.7.2: `WORKBLOCK_EXPAND_ROUNDS = 3000` for regular
     *  message stamps. (1000 for propagation-node stamps and 25 for
     *  peering keys — out of scope here; this implementation only
     *  serves message-stamp generation, which is the only case our
     *  outbound LXMF path needs.) */
    const val WORKBLOCK_EXPAND_ROUNDS: Int = 3000

    /** SPEC §5.7.1: `LXMessage.STAMP_SIZE = HASHLENGTH//8 = 32 bytes`. */
    const val STAMP_SIZE: Int = 32

    /** Defensive upper bound on `target_cost` we'll attempt. Beyond
     *  ~20 the expected runtime exceeds a minute on phone-class CPUs;
     *  refuse rather than block the UI indefinitely. Sideband's own
     *  defaults are typically 8-12 for personal accounts and up to
     *  ~16 for spam-sensitive setups, all comfortably under this cap. */
    const val MAX_TARGET_COST: Int = 24

    /**
     * Compute the `message_id` per upstream
     * `LXMessage.py::__update_message_id` — the input material for
     * the stamp workblock.
     *
     * `message_id = SHA256(dest_hash || src_hash || packed_payload_4_elements)`
     *
     * Note: the `packed_payload` here is the 4-element form
     * (`[timestamp, title, content, fields]`), NOT the 5-element form
     * that ALSO includes the stamp — chicken-and-egg avoidance, since
     * the stamp is what we're computing.
     */
    suspend fun computeMessageId(
        destHash: ByteArray,
        sourceHash: ByteArray,
        packedPayload4: ByteArray,
        crypto: CryptoProvider,
    ): ByteArray {
        val input = ByteArray(destHash.size + sourceHash.size + packedPayload4.size)
        destHash.copyInto(input, 0)
        sourceHash.copyInto(input, destHash.size)
        packedPayload4.copyInto(input, destHash.size + sourceHash.size)
        return crypto.sha256(input)
    }

    /**
     * Build the 768 KiB workblock per SPEC §5.7.2 step 1. Pure CPU /
     * memory work — call from `Dispatchers.Default` or equivalent
     * since on slow phones this can take a few seconds.
     *
     * Verified bytewise against upstream `LXMF/LXStamper.py:18-30`
     * via the `LxmfStampInteropTest` against a Python reference run.
     */
    suspend fun buildWorkblock(
        material: ByteArray,
        crypto: CryptoProvider,
        rounds: Int = WORKBLOCK_EXPAND_ROUNDS,
    ): ByteArray = coroutineScope {
        val chunkLen = 256
        val out = ByteArray(rounds * chunkLen)
        for (n in 0 until rounds) {
            ensureActive()  // honor cancellation between rounds
            val packedN = MessagePack.encode(n)
            // salt = SHA256(material || msgpack(n))
            val saltInput = ByteArray(material.size + packedN.size)
            material.copyInto(saltInput, 0)
            packedN.copyInto(saltInput, material.size)
            val salt = crypto.sha256(saltInput)
            // chunk = HKDF(length=256, ikm=material, salt=salt, info=∅)
            val chunk = crypto.hkdfDerive(material, salt, ByteArray(0), chunkLen)
            chunk.copyInto(out, n * chunkLen)
        }
        out
    }

    /**
     * SPEC §5.7.2 step 2: does this 32-byte `stamp` satisfy the
     * `target_cost` over `workblock`?
     *
     *   target  = 1 << (256 - target_cost)
     *   valid   = int.from_bytes(SHA256(workblock || stamp), "big") <= target
     *
     * Equivalent (and faster than constructing a 256-bit big-int):
     * the SHA256 result has ≥ `target_cost` leading zero BITS.
     */
    suspend fun stampValid(
        stamp: ByteArray,
        workblock: ByteArray,
        targetCost: Int,
        crypto: CryptoProvider,
    ): Boolean {
        require(stamp.size == STAMP_SIZE) {
            "stamp must be ${STAMP_SIZE}B (got ${stamp.size})"
        }
        val input = ByteArray(workblock.size + stamp.size)
        workblock.copyInto(input, 0)
        stamp.copyInto(input, workblock.size)
        val digest = crypto.sha256(input)
        return leadingZeroBits(digest) >= targetCost
    }

    /**
     * SPEC §5.7.2 step 2: brute-force search for a 32-byte `stamp`
     * value that satisfies [stampValid]. Counter-based — increments a
     * 64-bit big-endian counter in the trailing 8 bytes of the stamp,
     * keeping the leading 24 bytes zero. Matches upstream's "iterate
     * and test" approach (`LXStamper.py::generate_stamp`) without
     * specifying exact byte layout; receivers don't care HOW the
     * stamp was found, only that it validates.
     *
     * Expected iterations ≈ 2^target_cost. CPU work is dominated by
     * the inner SHA256 over `workblock(768 KiB) || stamp(32B)`. On a
     * mid-range Android device that's ~100 μs per try, so:
     *
     *   target_cost=8  ≈ 256 tries × 100 μs   ≈ 25 ms
     *   target_cost=12 ≈ 4096 tries × 100 μs  ≈ 0.4 s
     *   target_cost=16 ≈ 65k tries × 100 μs   ≈ 7 s
     *   target_cost=20 ≈ 1M tries × 100 μs    ≈ 100 s
     *
     * Defensive cap at [MAX_TARGET_COST] = 24; rejects with
     * IllegalArgumentException before starting so the UI can degrade
     * gracefully (caller catches and surfaces "stamp too expensive,
     * not sending").
     *
     * Cancellable: checks the coroutine scope every 1024 iterations
     * so the user can abort an in-progress send before the search
     * completes.
     */
    suspend fun findStamp(
        workblock: ByteArray,
        targetCost: Int,
        crypto: CryptoProvider,
    ): ByteArray = coroutineScope {
        require(targetCost in 1..MAX_TARGET_COST) {
            "targetCost $targetCost outside [1, $MAX_TARGET_COST]"
        }
        val stamp = ByteArray(STAMP_SIZE)
        val combined = ByteArray(workblock.size + STAMP_SIZE)
        workblock.copyInto(combined, 0)

        var counter = 0L
        while (true) {
            if (counter and 0x3FFL == 0L) ensureActive()  // every 1024 tries
            // Write the counter as big-endian uint64 in stamp[24..32].
            stamp[24] = (counter ushr 56).toByte()
            stamp[25] = (counter ushr 48).toByte()
            stamp[26] = (counter ushr 40).toByte()
            stamp[27] = (counter ushr 32).toByte()
            stamp[28] = (counter ushr 24).toByte()
            stamp[29] = (counter ushr 16).toByte()
            stamp[30] = (counter ushr 8).toByte()
            stamp[31] = counter.toByte()
            stamp.copyInto(combined, workblock.size)
            val digest = crypto.sha256(combined)
            if (leadingZeroBits(digest) >= targetCost) {
                return@coroutineScope stamp.copyOf()
            }
            counter++
        }
        @Suppress("UNREACHABLE_CODE")  // KT compiler may not see the early return
        stamp
    }

    /**
     * Count leading zero bits across the byte array, MSB-first. Used
     * by [stampValid] and [findStamp] to test the target_cost without
     * constructing a 256-bit BigInteger comparison.
     */
    internal fun leadingZeroBits(bytes: ByteArray): Int {
        var count = 0
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            if (unsigned == 0) {
                count += 8
                continue
            }
            // count leading zeros in the byte: 0..7
            var bit = 7
            while (bit >= 0) {
                if ((unsigned ushr bit) and 1 == 1) return count
                count++
                bit--
            }
            return count  // unreachable; loop above would have returned
        }
        return count
    }
}
