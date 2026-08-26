package io.github.thatsfguy.reticulum.lxmf

import io.github.thatsfguy.reticulum.TestVectors
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC §5.7 stamp coverage. Three layers:
 *
 *   1. [LxmfStamp.leadingZeroBits] — byte-level helper that decides
 *      whether a SHA256 digest meets the cost. Pure arithmetic; pin
 *      the corner cases (all-zero, single-bit-set, full bytes).
 *
 *   2. [LxmfStamp.buildWorkblock] / [LxmfStamp.stampValid] /
 *      [LxmfStamp.findStamp] — the actual PoW loop. The "find then
 *      validate" round-trip proves the algorithm converges; explicit
 *      targetCost cap rejection guards against the engine accidentally
 *      asking for impossible PoW.
 *
 *   3. [LxmfStamp.computeMessageId] — pins the message_id input to
 *      SHA256(destHash || sourceHash || packed4) so a Sideband-style
 *      receiver re-computing message_id from the same wire bytes
 *      lands on the same workblock and validates our stamp.
 *
 * Out of scope: byte-level interop against an upstream Python
 * `LXStamper.py::generate_stamp` reference fixture. Would need a
 * pinned test vector file; deferred to a follow-up that wires the
 * Python-interop harness around stamp generation.
 */
class LxmfStampTest {

    private val crypto = TestVectors.crypto

    // ---- leadingZeroBits ---------------------------------------------

    @Test fun `leadingZeroBits counts 256 on an all-zero digest`() {
        assertEquals(256, LxmfStamp.leadingZeroBits(ByteArray(32)))
    }

    @Test fun `leadingZeroBits counts the leading zero byte`() {
        val bytes = ByteArray(32).also { it[1] = 0x80.toByte() }
        // byte 0 = 0x00 → 8 zeros; byte 1's top bit is 1 → 0 more.
        assertEquals(8, LxmfStamp.leadingZeroBits(bytes))
    }

    @Test fun `leadingZeroBits zero in middle of byte 0`() {
        // byte 0 = 0b00001000 = 0x08 → 4 leading zeros before the 1 bit.
        val bytes = ByteArray(32).also { it[0] = 0x08 }
        assertEquals(4, LxmfStamp.leadingZeroBits(bytes))
    }

    @Test fun `leadingZeroBits with top bit set returns 0`() {
        val bytes = ByteArray(32).also { it[0] = 0x80.toByte() }
        assertEquals(0, LxmfStamp.leadingZeroBits(bytes))
    }

    @Test fun `leadingZeroBits counts across multiple zero bytes`() {
        // bytes 0..2 all zero, byte 3 = 0x40 (1 zero bit before the high
        // 1 bit in byte 3): 3*8 + 1 = 25.
        val bytes = ByteArray(32).also { it[3] = 0x40 }
        assertEquals(25, LxmfStamp.leadingZeroBits(bytes))
    }

    // ---- buildWorkblock determinism ----------------------------------

    @Test fun `buildWorkblock is deterministic for the same material`() = runTest {
        val material = ByteArray(32) { (it + 1).toByte() }
        // Use a much smaller round count so the test finishes quickly —
        // determinism doesn't depend on the round count.
        val w1 = LxmfStamp.buildWorkblock(material, crypto, rounds = 4)
        val w2 = LxmfStamp.buildWorkblock(material, crypto, rounds = 4)
        assertEquals(4 * 256, w1.size, "workblock length = rounds × 256")
        assertTrue(w1.contentEquals(w2), "same material must produce identical workblocks")
    }

    @Test fun `buildWorkblock varies with material`() = runTest {
        val m1 = ByteArray(32) { 1 }
        val m2 = ByteArray(32) { 2 }
        val w1 = LxmfStamp.buildWorkblock(m1, crypto, rounds = 2)
        val w2 = LxmfStamp.buildWorkblock(m2, crypto, rounds = 2)
        assertTrue(!w1.contentEquals(w2), "different material must produce different workblocks")
    }

    // ---- findStamp + stampValid round-trip ---------------------------

    @Test fun `findStamp converges at low target_cost and stampValid accepts it`() = runTest {
        val workblock = LxmfStamp.buildWorkblock(
            material = ByteArray(32) { (it * 7).toByte() },
            crypto = crypto,
            rounds = 4,
        )
        val cost = 8  // 256 expected iters; fast in test runtime
        val stamp = LxmfStamp.findStamp(workblock, cost, crypto)
        assertEquals(LxmfStamp.STAMP_SIZE, stamp.size, "stamp must be 32 bytes")
        assertTrue(LxmfStamp.stampValid(stamp, workblock, cost, crypto),
            "stampValid must accept the stamp findStamp just returned")
    }

    @Test fun `stampValid rejects an all-zero stamp at non-trivial cost`() = runTest {
        val workblock = LxmfStamp.buildWorkblock(
            material = ByteArray(32) { it.toByte() },
            crypto = crypto,
            rounds = 2,
        )
        val zeroStamp = ByteArray(32)
        // The probability that all-zero stamp happens to pass cost=20
        // by coincidence is 2^-20 ≈ 10^-6 — vanishingly unlikely.
        val result = LxmfStamp.stampValid(zeroStamp, workblock, targetCost = 20, crypto)
        assertTrue(!result, "all-zero stamp should not pass cost=20 by chance")
    }

    @Test fun `findStamp rejects targetCost above MAX_TARGET_COST`() = runTest {
        val workblock = LxmfStamp.buildWorkblock(ByteArray(32), crypto, rounds = 2)
        assertFailsWith<IllegalArgumentException>(
            "MAX_TARGET_COST cap protects the user from minutes-long PoW",
        ) {
            LxmfStamp.findStamp(workblock, LxmfStamp.MAX_TARGET_COST + 1, crypto)
        }
    }

    @Test fun `findStamp rejects targetCost of 0`() = runTest {
        val workblock = LxmfStamp.buildWorkblock(ByteArray(32), crypto, rounds = 2)
        assertFailsWith<IllegalArgumentException>(
            "cost=0 is meaningless (every stamp would pass)",
        ) {
            LxmfStamp.findStamp(workblock, 0, crypto)
        }
    }

    // ---- stampValue + validateInboundStamp (receive side) ------------

    @Test fun `stampValue reports the leading-zero count of a found stamp`() = runTest {
        val workblock = LxmfStamp.buildWorkblock(
            material = ByteArray(32) { (it * 3).toByte() },
            crypto = crypto,
            rounds = 4,
        )
        val cost = 8
        val stamp = LxmfStamp.findStamp(workblock, cost, crypto)
        val value = LxmfStamp.stampValue(stamp, workblock, crypto)
        // SPEC §5.7.2 step 3: the value can EXCEED the required cost
        // (the search stops at the first hit, which may overshoot) —
        // the receiver's test is >=, never ==.
        assertTrue(value >= cost, "stamp value $value must meet the cost it was found for")
    }

    @Test fun `stampValue returns 0 for a wrong-length stamp`() = runTest {
        val workblock = LxmfStamp.buildWorkblock(ByteArray(32), crypto, rounds = 2)
        // Inbound stamps are attacker-controlled: a malformed one is
        // worthless, not exceptional. Must not throw.
        assertEquals(0, LxmfStamp.stampValue(ByteArray(16), workblock, crypto))
        assertEquals(0, LxmfStamp.stampValue(ByteArray(0), workblock, crypto))
    }

    @Test fun `validateInboundStamp accepts a stamp generated for the same message_id`() = runTest {
        // Full send-then-receive round-trip at the default 3000-round
        // workblock: the sender derives the workblock from message_id
        // and searches; the receiver re-derives it from the SAME
        // message_id and scores the stamp. If the two workblock
        // derivations ever diverge this test fails, which is the whole
        // point — that divergence is invisible in a self-consistent
        // sender-only test.
        val messageId = ByteArray(32) { (it + 11).toByte() }
        val cost = 8
        val workblock = LxmfStamp.buildWorkblock(messageId, crypto)
        val stamp = LxmfStamp.findStamp(workblock, cost, crypto)

        val value = LxmfStamp.validateInboundStamp(stamp, messageId, crypto)
        assertTrue(value >= cost, "receiver scored $value, sender targeted $cost")
    }

    @Test fun `validateInboundStamp scores 0 for a missing stamp`() = runTest {
        val messageId = ByteArray(32) { it.toByte() }
        assertEquals(0, LxmfStamp.validateInboundStamp(null, messageId, crypto),
            "no stamp means no proof of work — score 0, don't throw")
    }

    @Test fun `validateInboundStamp scores a stamp bound to a different message_id below cost`() = runTest {
        // Stamp replay across messages: a stamp is only valid for the
        // message_id whose workblock it was mined against. Re-using it
        // on another message must not pass a non-trivial cost.
        val minedFor = ByteArray(32) { (it + 5).toByte() }
        val otherMessage = ByteArray(32) { (it + 6).toByte() }
        val stamp = LxmfStamp.findStamp(
            LxmfStamp.buildWorkblock(minedFor, crypto), 8, crypto,
        )
        val value = LxmfStamp.validateInboundStamp(stamp, otherMessage, crypto)
        assertTrue(value < 8, "a stamp mined for another message scored $value against cost 8")
    }

    @Test fun `MAX_ADVERTISED_COST never exceeds what our own sender will compute`() {
        // A cost above MAX_TARGET_COST would be self-isolating: peers
        // running this client refuse the PoW and send unstamped, and
        // we then drop them when enforcing.
        assertTrue(LxmfStamp.MAX_ADVERTISED_COST <= LxmfStamp.MAX_TARGET_COST)
    }

    // ---- computeMessageId --------------------------------------------

    @Test fun `computeMessageId is SHA256(destHash + sourceHash + packed4)`() = runTest {
        val dest = ByteArray(16) { (it * 3).toByte() }
        val src = ByteArray(16) { (it * 5).toByte() }
        val packed = "hello".encodeToByteArray()
        val out = LxmfStamp.computeMessageId(dest, src, packed, crypto)
        assertEquals(32, out.size, "message_id is SHA256, always 32 bytes")

        // Independent recomputation: SHA256 of the same concatenation
        // must equal what computeMessageId returned.
        val expected = crypto.sha256(dest + src + packed)
        assertTrue(out.contentEquals(expected),
            "computeMessageId must equal SHA256(destHash || sourceHash || packedPayload)")
    }

    @Test fun `computeMessageId varies with any input change`() = runTest {
        val dest = ByteArray(16) { 1 }
        val src = ByteArray(16) { 2 }
        val packedA = "msgA".encodeToByteArray()
        val packedB = "msgB".encodeToByteArray()
        val idA = LxmfStamp.computeMessageId(dest, src, packedA, crypto)
        val idB = LxmfStamp.computeMessageId(dest, src, packedB, crypto)
        assertTrue(!idA.contentEquals(idB),
            "changing the packed payload must change the message_id (anchors the workblock)")
    }
}
