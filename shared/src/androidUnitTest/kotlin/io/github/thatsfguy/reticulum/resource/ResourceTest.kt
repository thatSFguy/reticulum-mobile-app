package io.github.thatsfguy.reticulum.resource

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Receive-only Resource coverage. v0.1.24 added Resource so propagation
 * /get round 2 (a real-world multi-packet response) could actually
 * deliver. Without these tests, any chunk-matching, two-layer-decrypt,
 * or integrity-check regression would surface as "page hangs forever"
 * — exactly the silent-loss class of bug Tier 2 is targeting.
 */
class ResourceTest {

    private val crypto = TestVectors.crypto
    private val tokenCrypto = TokenCrypto(crypto)
    private val linkKey = ByteArray(64) { (it + 0xa0).toByte() }
    private val linkId = ByteArray(16) { (it + 0x10).toByte() }

    @Test fun `single-chunk happy path: chunks in order assemble + verify`() = runTest {
        val payload = "hello, this is a small payload that fits in one chunk".encodeToByteArray()
        val (advertisement, chunks) = senderSideBuild(payload)

        val resource = Resource(advertisement, tokenCrypto, linkKey)
        for (chunk in chunks) {
            assertTrue(resource.receivePart(chunk, crypto), "every chunk must match a slot")
        }
        assertTrue(resource.isComplete, "resource should be complete after all chunks fed")

        val reassembled = resource.assemble(crypto)
        assertContentEquals(payload, reassembled, "reassembled payload must equal original")
    }

    @Test fun `multi-chunk happy path: out-of-order delivery still assembles`() = runTest {
        // Build a payload large enough to require multiple chunks.
        val payload = ByteArray(900) { (it % 251).toByte() }
        val (advertisement, chunks) = senderSideBuild(payload)
        assertTrue(advertisement.totalParts > 1, "test setup error — need multiple chunks")

        val resource = Resource(advertisement, tokenCrypto, linkKey)
        // Reverse delivery order — exercises hashmap-based slot matching.
        for (chunk in chunks.reversed()) {
            assertTrue(resource.receivePart(chunk, crypto), "out-of-order chunk should still slot-match")
        }
        val reassembled = resource.assemble(crypto)
        assertContentEquals(payload, reassembled)
    }

    @Test fun `duplicate chunk delivery is idempotent`() = runTest {
        val payload = ByteArray(900) { (it % 251).toByte() }
        val (advertisement, chunks) = senderSideBuild(payload)
        val resource = Resource(advertisement, tokenCrypto, linkKey)

        // Feed first chunk, then feed it again.
        assertTrue(resource.receivePart(chunks[0], crypto))
        assertFalse(resource.receivePart(chunks[0], crypto), "duplicate must return false (slot already filled)")

        // Resource should still complete normally with the rest of the chunks.
        for (chunk in chunks.drop(1)) {
            assertTrue(resource.receivePart(chunk, crypto))
        }
        assertContentEquals(payload, resource.assemble(crypto))
    }

    @Test fun `chunk that hashes to no slot returns false and does not corrupt state`() = runTest {
        val payload = "small payload".encodeToByteArray()
        val (advertisement, _) = senderSideBuild(payload)
        val resource = Resource(advertisement, tokenCrypto, linkKey)

        // Feed completely unrelated bytes; their hash matches no hashmap entry.
        val bogus = ByteArray(64) { 0xff.toByte() }
        assertFalse(resource.receivePart(bogus, crypto), "unrelated chunk must return false")
        assertFalse(resource.isComplete, "resource must not appear complete after a no-match")
    }

    @Test fun `assemble throws ResourceError when integrity hash mismatches`() = runTest {
        val payload = "small payload".encodeToByteArray()
        val (advertisement, chunks) = senderSideBuild(payload)
        // Tamper: replace the advertisement's integrity hash with garbage so
        // the post-assemble verification fails. We can't tamper a chunk
        // (it'd hash to no slot — covered above); we tamper the expected.
        val tampered = advertisement.copy(hash = ByteArray(32) { 0xee.toByte() })
        val resource = Resource(tampered, tokenCrypto, linkKey)
        for (c in chunks) resource.receivePart(c, crypto)
        assertFailsWith<ResourceError>(
            "integrity-hash mismatch must throw ResourceError, not silently return wrong bytes",
        ) {
            resource.assemble(crypto)
        }
    }

    @Test fun `assemble throws when called before all chunks present`() = runTest {
        val payload = ByteArray(900) { (it % 251).toByte() }
        val (advertisement, chunks) = senderSideBuild(payload)
        assertTrue(chunks.size > 1)
        val resource = Resource(advertisement, tokenCrypto, linkKey)
        resource.receivePart(chunks[0], crypto)
        // Don't feed the rest.
        assertFailsWith<ResourceError>("incomplete resource must error on assemble") {
            resource.assemble(crypto)
        }
    }

    @Test fun `ResourceAdvertisement parse rejects multi-segment (l greater than 1)`() = runTest {
        // Construct an ADV body manually that signals split=true (l > 1).
        val advBody = io.github.thatsfguy.reticulum.codec.MessagePack.encode(mapOf<Any?, Any?>(
            "t" to 1024L, "d" to 1000L, "n" to 8, "h" to ByteArray(32),
            "r" to ByteArray(4), "o" to ByteArray(32), "i" to 1, "l" to 2,
            "f" to 0x05, // 0x01 encrypted | 0x04 split
            "m" to ByteArray(32),
        ))
        assertFailsWith<IllegalStateException>(
            "multi-segment resources must be rejected at parse time",
        ) {
            ResourceAdvertisement.parse(advBody, linkId)
        }
    }

    @Test fun `ResourceAdvertisement parse rejects oversized partsInAd`() = runTest {
        val tooManyParts = Resource.HASHMAP_MAX_LEN + 1
        val advBody = io.github.thatsfguy.reticulum.codec.MessagePack.encode(mapOf<Any?, Any?>(
            "t" to (tooManyParts * Resource.DEFAULT_SDU).toLong(),
            "d" to (tooManyParts * Resource.DEFAULT_SDU).toLong(),
            "n" to tooManyParts,
            "h" to ByteArray(32),
            "r" to ByteArray(4),
            "o" to ByteArray(32),
            "i" to 1,
            "l" to 1,
            "f" to 0x01,
            "m" to ByteArray(tooManyParts * Resource.MAPHASH_LEN),
        ))
        assertFailsWith<IllegalStateException>(
            "resources beyond HASHMAP_MAX_LEN must be rejected (REQ/HMU not implemented)",
        ) {
            ResourceAdvertisement.parse(advBody, linkId)
        }
    }

    // ---- Sender-side helper ------------------------------------------------

    /**
     * Build the sender-side bytes for a single-segment resource carrying
     * [payload]. Returns the advertisement + chunk plaintexts in
     * chunk-index order. Uses fixed pseudo-random_hash so tests are
     * reproducible.
     */
    private fun senderSideBuild(payload: ByteArray): Pair<ResourceAdvertisement, List<ByteArray>> {
        val randomHash = ByteArray(Resource.RANDOM_HASH_SIZE) { (it + 0x42).toByte() }

        // Inner stream: random_hash || payload (no compression in tests).
        val inner = ByteArray(randomHash.size + payload.size).also {
            randomHash.copyInto(it, 0)
            payload.copyInto(it, randomHash.size)
        }

        // Outer Token-encrypt with the link key. assemble() will run this
        // in reverse over the concatenated chunk stream.
        val outerToken: ByteArray = runBlocking {
            tokenCrypto.encryptWithDerivedKey(inner, linkKey)
        }

        // Slice into SDU-sized chunks. Last chunk is whatever's left.
        val sdu = Resource.DEFAULT_SDU
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < outerToken.size) {
            val end = (offset + sdu).coerceAtMost(outerToken.size)
            chunks.add(outerToken.copyOfRange(offset, end))
            offset = end
        }
        if (chunks.isEmpty()) chunks.add(ByteArray(0))

        // Build hashmap = SHA256(chunk || randomHash)[:4] for each chunk.
        val hashmap = chunks.map { chunk ->
            runBlocking { Resource.chunkHash(chunk, randomHash, crypto) }
        }

        // Integrity hash: SHA256(payload || randomHash) — payload is the
        // post-decompress, random-hash-stripped data.
        val verifyInput = ByteArray(payload.size + randomHash.size).also {
            payload.copyInto(it, 0)
            randomHash.copyInto(it, payload.size)
        }
        val integrityHash = runBlocking { crypto.sha256(verifyInput) }

        val advertisement = ResourceAdvertisement(
            linkId = linkId,
            transferSize = outerToken.size.toLong(),
            dataSize = inner.size.toLong(),
            partsInAd = chunks.size,
            totalParts = chunks.size,
            hash = integrityHash,
            randomHash = randomHash,
            originalHash = integrityHash,
            segmentIndex = 1,
            totalSegments = 1,
            requestId = null,
            encrypted = true,
            compressed = false,
            split = false,
            isRequest = false,
            isResponse = true,
            hasMetadata = false,
            hashmap = hashmap,
        )
        return advertisement to chunks
    }
}
