package io.github.thatsfguy.reticulum.resource

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Outbound Resource sender coverage (§10.2 step 1-3). Validates that the
 * bytes [Resource.buildOutbound] emits are accepted by the existing
 * receive-side [Resource] state machine + [ResourceAdvertisement.parse].
 *
 * Strategy: black-box round-trip. The sender produces ADV body cipher +
 * already-Token-encrypted chunks. We decrypt the ADV body, parse it, feed
 * chunks back through the receiver, and assert the assembled bytes
 * byte-equal the original payload. This is the same "external oracle"
 * shape the rest of the wire-format tests follow — see
 * `feedback_self_roundtrip_insufficient_wire.md` for why self-roundtrip
 * alone isn't enough, but our receive code is also being exercised
 * against upstream test fixtures and live fwdsvc interop, so passing
 * through it gives confidence the wire format matches.
 */
class ResourceSenderTest {

    private val crypto = TestVectors.crypto
    private val tokenCrypto = TokenCrypto(crypto)
    private val linkKey = ByteArray(64) { (it + 0xa0).toByte() }
    private val linkId = ByteArray(16) { (it + 0x10).toByte() }

    @Test fun `buildOutbound singleChunk smallPayload roundTrip via receiver`() = runTest {
        val payload = "small payload that fits in one chunk".encodeToByteArray()
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        )
        assertEquals(1, outbound.advertisement.totalParts)
        assertContentEquals(linkId, outbound.advertisement.linkId)
        assertEquals(1, outbound.chunks.size)

        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val adv = ResourceAdvertisement.parse(advPlain, linkId)
        assertEquals(outbound.advertisement.totalParts, adv.totalParts)
        assertContentEquals(outbound.advertisement.hash, adv.hash)
        assertContentEquals(outbound.advertisement.randomHash, adv.randomHash)
        assertEquals(true, adv.encrypted)
        assertEquals(false, adv.compressed)

        val resource = Resource(adv, tokenCrypto, linkKey)
        for (chunk in outbound.chunks) {
            assertTrue(resource.receivePart(chunk, crypto), "chunk must match a hashmap slot")
        }
        assertTrue(resource.isComplete)
        assertContentEquals(payload, resource.assemble(crypto))
    }

    @Test fun `buildOutbound multiChunk 10kB roundTrip via receiver`() = runTest {
        // 10 KB payload — covers the realistic image-attachment use case.
        // ~24 chunks at 433-byte SDU, well under the HASHMAP_MAX_LEN=84 cap.
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        )
        // 10000 bytes + 4 random_prefix + token overhead ≈ ~10100 bytes
        // → ceil(10100 / 433) = 24 chunks
        assertTrue(
            outbound.advertisement.totalParts in 22..26,
            "10 KB / 433-byte SDU ≈ 24 chunks; got ${outbound.advertisement.totalParts}",
        )
        // Round-trip via the existing receiver
        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val adv = ResourceAdvertisement.parse(advPlain, linkId)
        val resource = Resource(adv, tokenCrypto, linkKey)
        for (chunk in outbound.chunks) {
            assertTrue(resource.receivePart(chunk, crypto), "chunk must match a hashmap slot")
        }
        assertTrue(resource.isComplete)
        assertContentEquals(payload, resource.assemble(crypto))
    }

    @Test fun `buildOutbound outOfOrder delivery still assembles via receiver`() = runTest {
        val payload = ByteArray(5_000) { (it % 251).toByte() }
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        )
        assertTrue(outbound.advertisement.totalParts > 1, "test setup: need multi-chunk")

        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val adv = ResourceAdvertisement.parse(advPlain, linkId)
        val resource = Resource(adv, tokenCrypto, linkKey)
        // Reverse-order delivery — exercises hashmap-based slot matching.
        for (chunk in outbound.chunks.reversed()) {
            assertTrue(resource.receivePart(chunk, crypto), "out-of-order chunk should still slot-match")
        }
        assertContentEquals(payload, resource.assemble(crypto))
    }

    @Test fun `buildOutbound carries requestId in q field when provided`() = runTest {
        val payload = "tiny".encodeToByteArray()
        val requestId = ByteArray(16) { (it + 0x55).toByte() }
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
            requestId = requestId,
        )
        assertNotNull(outbound.advertisement.requestId, "requestId must round-trip into advertisement")
        assertContentEquals(requestId, outbound.advertisement.requestId)

        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val adv = ResourceAdvertisement.parse(advPlain, linkId)
        assertContentEquals(requestId, adv.requestId, "parsed ADV must carry the same q field")
    }

    @Test fun `buildOutbound omitsRequestId by default`() = runTest {
        val payload = "tiny".encodeToByteArray()
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        )
        assertEquals(null, outbound.advertisement.requestId, "no requestId → no q field")
    }

    @Test fun `buildOutbound rejects payload exceeding HASHMAP_MAX_LEN chunks`() = runTest {
        // HASHMAP_MAX_LEN = 84 chunks × 433 SDU ≈ 36 KB. Push past that.
        val payload = ByteArray(40_000) { (it % 251).toByte() }
        assertFailsWith<IllegalStateException>(
            "resources beyond HASHMAP_MAX_LEN must be rejected at build time (REQ/HMU not implemented)",
        ) {
            Resource.buildOutbound(
                plain = payload,
                link = tokenCrypto,
                linkKey = linkKey,
                linkId = linkId,
                crypto = crypto,
            )
        }
    }

    @Test fun `buildOutbound advertisement transferSize matches sum of chunk sizes`() = runTest {
        val payload = ByteArray(3_000) { (it % 251).toByte() }
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        )
        val sumOfChunks = outbound.chunks.sumOf { it.size.toLong() }
        assertEquals(
            sumOfChunks,
            outbound.advertisement.transferSize,
            "transferSize must equal total wire bytes across chunks",
        )
    }

    /**
     * v1.1.19 regression pin. Upstream RNS `Resource.py:1373`
     * unpacks every ADV field with bare `dictionary["q"]` (no
     * KeyError guard) — so OUR ADV must include all 11 keys
     * (t, d, n, h, r, o, m, f, i, l, q) on every emit. Pre-v1.1.19
     * we omitted "q" when requestId was null, which broke
     * mobile→Sideband image attachments: upstream RNS threw KeyError
     * on unpack, the receiver never registered the Resource, no
     * CTX_RESOURCE_REQ went out, our sender timed out at ~2 min and
     * silently dropped the image. Confirmed 2026-05-13 by reading
     * upstream pack/unpack against our buildOutbound.
     *
     * Pin the full key set so a future refactor of buildOutbound
     * can't silently drop any of them and re-break Sideband.
     */
    @Test fun `buildOutbound ADV body carries all 11 upstream-RNS keys with null q for no-request case`() = runTest {
        val payload = "hello world".encodeToByteArray()
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
            requestId = null,
        )
        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val decoded = io.github.thatsfguy.reticulum.codec.MessagePack.decode(advPlain)
        assertTrue(decoded is Map<*, *>, "ADV body must decode to a msgpack map")
        val map = decoded as Map<*, *>

        // The 11 keys upstream RNS Resource.py:1342-1352 packs and
        // 1363-1373 unpacks unconditionally:
        val required = listOf("t", "d", "n", "h", "r", "o", "m", "f", "i", "l", "q")
        for (key in required) {
            assertTrue(map.containsKey(key),
                "ADV missing required key \"$key\" — upstream RNS unpack would KeyError here " +
                    "(see Resource.py:1363-1373). All 11 keys present in map: ${map.keys}")
        }
        assertEquals(null, map["q"],
            "no-request case: q must encode as msgpack nil (decoded as null), " +
                "matching upstream's `self.q = None` (Resource.py:1294)")
    }

    /**
     * Companion to the no-request pin above — when requestId IS set
     * (request/response Resource path, not image attachment path),
     * the "q" key must carry the 16-byte hash. Round-trip via our
     * own parse to verify the byte-for-byte fidelity.
     */
    @Test fun `buildOutbound ADV carries 16-byte requestId in q when provided`() = runTest {
        val payload = "request body".encodeToByteArray()
        val requestId = ByteArray(16) { (it * 11 + 3).toByte() }
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
            requestId = requestId,
        )
        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val map = io.github.thatsfguy.reticulum.codec.MessagePack.decode(advPlain) as Map<*, *>
        val qBytes = map["q"] as? ByteArray
        assertNotNull(qBytes, "q must be present and ByteArray-typed when requestId provided")
        assertContentEquals(requestId, qBytes, "q must echo the supplied requestId byte-for-byte")
    }
}
