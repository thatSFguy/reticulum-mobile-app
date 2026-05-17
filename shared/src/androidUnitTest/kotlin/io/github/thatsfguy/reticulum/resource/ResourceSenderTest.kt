package io.github.thatsfguy.reticulum.resource

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Outbound Resource sender coverage (§10.2 + §10.4 + §10.11). Validates
 * that the bytes [Resource.buildOutbound] emits are accepted by the
 * receive-side [Resource] state machine + [ResourceAdvertisement.parse].
 *
 * Strategy: black-box round-trip. The sender produces ADV body cipher +
 * already-Token-encrypted chunks. We decrypt the ADV body, parse it, feed
 * chunks back through the receiver, and assert the assembled bytes
 * byte-equal the original payload. This is the same "external oracle"
 * shape the rest of the wire-format tests follow — see
 * `feedback_self_roundtrip_insufficient_wire.md` for why self-roundtrip
 * alone isn't enough; the single-segment path is also exercised against
 * upstream fixtures + live fwdsvc interop.
 */
class ResourceSenderTest {

    private val crypto = TestVectors.crypto
    private val tokenCrypto = TokenCrypto(crypto)
    private val linkKey = ByteArray(64) { (it + 0xa0).toByte() }
    private val linkId = ByteArray(16) { (it + 0x10).toByte() }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test fun `buildOutbound singleChunk smallPayload roundTrip via receiver`() = runTest {
        val payload = "small payload that fits in one chunk".encodeToByteArray()
        val segments = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        )
        assertEquals(1, segments.size, "a small payload is one segment")
        val outbound = segments.single()
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
        // ~22 chunks at 464-byte SDU, under the HASHMAP_MAX_LEN=74 cap so
        // the whole hashmap still fits one ADV (no HMU needed).
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        ).single()
        assertTrue(
            outbound.advertisement.totalParts in 20..24,
            "10 KB / 464-byte SDU ≈ 22 chunks; got ${outbound.advertisement.totalParts}",
        )
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
        ).single()
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
        ).single()
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
        ).single()
        assertEquals(null, outbound.advertisement.requestId, "no requestId → no q field")
    }

    @Test fun `buildOutbound windows the ADV hashmap for large single-segment payloads`() = runTest {
        // §10.4 — a payload past HASHMAP_MAX_LEN×SDU no longer throws. The
        // ADV carries only the first window; the rest rides RESOURCE_HMU.
        val payload = ByteArray(80_000) { (it % 251).toByte() }
        val segments = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        )
        assertEquals(1, segments.size, "80 KB stays one segment")
        val outbound = segments.single()
        assertTrue(
            outbound.advertisement.totalParts > Resource.HASHMAP_MAX_LEN,
            "test setup — need n > HASHMAP_MAX_LEN (got ${outbound.advertisement.totalParts})",
        )
        assertEquals(
            Resource.HASHMAP_MAX_LEN, outbound.advertisement.partsInAd,
            "ADV must carry exactly the first HASHMAP_MAX_LEN window",
        )
        assertEquals(Resource.HASHMAP_MAX_LEN, outbound.advertisement.hashmap.size)
        assertEquals(
            outbound.advertisement.totalParts, outbound.fullHashmap.size,
            "fullHashmap must hold every map_hash",
        )
    }

    @Test fun `buildOutbound splits payloads past MAX_EFFICIENT_SIZE into segments`() = runTest {
        // §10.11 — just over one segment exercises the split.
        val payload = ByteArray(Resource.MAX_EFFICIENT_SIZE + 5_000) { (it % 251).toByte() }
        val segments = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        )
        assertEquals(2, segments.size)
        assertEquals(1, segments[0].advertisement.segmentIndex)
        assertEquals(2, segments[1].advertisement.segmentIndex)
        assertEquals(2, segments[0].advertisement.totalSegments)
        assertTrue(segments[0].advertisement.split, "split flag must be set on a multi-segment ADV")
        // `o` (originalHash) ties every segment to segment 1's integrity hash.
        assertContentEquals(segments[0].advertisement.hash, segments[0].advertisement.originalHash)
        assertContentEquals(segments[0].advertisement.hash, segments[1].advertisement.originalHash)
    }

    @Test fun `HMU - buildOutbound large payload round-trips through the receiver`() = runTest {
        // End-to-end: a >HASHMAP_MAX_LEN payload from buildOutbound, driven
        // through the receiver simulating the RESOURCE_REQ / RESOURCE_HMU
        // pull loop the LinkSession / LinkResourceReceiver run on the wire.
        val payload = ByteArray(120_000) { (it * 13 % 251).toByte() }
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        ).single()
        assertTrue(
            outbound.advertisement.totalParts > Resource.HASHMAP_MAX_LEN,
            "test setup — need an HMU-requiring payload",
        )

        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val adv = ResourceAdvertisement.parse(advPlain, linkId)
        val res = Resource(adv, tokenCrypto, linkKey)

        val chunkByHash = HashMap<String, ByteArray>(outbound.chunks.size)
        outbound.fullHashmap.forEachIndexed { i, h -> chunkByHash[hex(h)] = outbound.chunks[i] }
        val full = outbound.fullHashmap
        val windowLen = Resource.HASHMAP_MAX_LEN

        var guard = 0
        while (!res.isComplete && guard++ < 10_000) {
            val batch = res.nextRequestBatch() ?: break
            for (mh in batch.mapHashes) {
                val chunk = chunkByHash[hex(mh)] ?: error("sender has no chunk for a requested hash")
                res.receivePart(chunk, crypto)
            }
            if (batch.exhausted) {
                // The sender locates last_map_hash at a window boundary and
                // emits the next RESOURCE_HMU window.
                val lh = hex(batch.lastMapHash!!)
                var boundary = windowLen - 1
                while (boundary < full.size && hex(full[boundary]) != lh) boundary += windowLen
                val nextStart = boundary + 1
                if (nextStart < full.size) {
                    val end = (nextStart + windowLen).coerceAtMost(full.size)
                    val window = full.subList(nextStart, end).fold(ByteArray(0)) { a, h -> a + h }
                    res.hashmapUpdate(nextStart / windowLen, window)
                }
            }
        }
        assertTrue(res.isComplete, "all parts received after the HMU windows")
        assertContentEquals(payload, res.assemble(crypto))
    }

    @Test fun `buildOutbound advertisement transferSize matches sum of chunk sizes`() = runTest {
        val payload = ByteArray(3_000) { (it % 251).toByte() }
        val outbound = Resource.buildOutbound(
            plain = payload,
            link = tokenCrypto,
            linkKey = linkKey,
            linkId = linkId,
            crypto = crypto,
        ).single()
        val sumOfChunks = outbound.chunks.sumOf { it.size.toLong() }
        assertEquals(
            sumOfChunks,
            outbound.advertisement.transferSize,
            "transferSize must equal total wire bytes across chunks",
        )
    }

    /**
     * v1.1.19 regression pin. Upstream RNS `Resource.py:1373` unpacks
     * every ADV field with bare `dictionary["q"]` (no KeyError guard) —
     * so OUR ADV must include all 11 keys (t, d, n, h, r, o, m, f, i, l,
     * q) on every emit. Pre-v1.1.19 we omitted "q" when requestId was
     * null, which broke mobile→Sideband image attachments.
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
        ).single()
        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val decoded = io.github.thatsfguy.reticulum.codec.MessagePack.decode(advPlain)
        assertTrue(decoded is Map<*, *>, "ADV body must decode to a msgpack map")
        val map = decoded as Map<*, *>

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
     * Companion to the no-request pin above — when requestId IS set the
     * "q" key must carry the 16-byte hash. Round-trip via our own parse.
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
        ).single()
        val advPlain = tokenCrypto.decryptWithDerivedKey(outbound.advBodyCipher, linkKey)
        val map = io.github.thatsfguy.reticulum.codec.MessagePack.decode(advPlain) as Map<*, *>
        val qBytes = map["q"] as? ByteArray
        assertNotNull(qBytes, "q must be present and ByteArray-typed when requestId provided")
        assertContentEquals(requestId, qBytes, "q must echo the supplied requestId byte-for-byte")
    }
}
