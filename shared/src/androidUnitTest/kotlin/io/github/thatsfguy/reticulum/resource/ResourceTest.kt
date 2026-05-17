package io.github.thatsfguy.reticulum.resource

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ---- §10.2 step 1 metadata prefix ---------------------------------
    //
    // Upstream NomadNet `/file/` handler (Node.py:128-141) returns
    //   [open(file, "rb"), {"name": filename_bytes}]
    // which upstream RNS Link.py:895 wraps as a Resource with
    // `metadata = {"name": ...}` and `data = file_handle`. The §10.2
    // step 1 packer prepends `length(3, big-endian uint24) || msgpack
    // (metadata)` to the body before the random_hash prefix +
    // compression + encrypt. has_metadata (flag bit 5) signals
    // presence; receivers strip the prefix during assemble.
    //
    // The integrity hash is computed over the post-decompression body
    // which INCLUDES the metadata prefix (`(compressed?) plaintext`
    // in §10.2 step 5). So the metadata is part of the integrity
    // input — corrupting metadata fails verify.

    @Test fun `assemble strips metadata prefix when hasMetadata flag is set`() = runTest {
        val metadata = mapOf<Any?, Any?>("name" to "test.txt".encodeToByteArray())
        val fileBytes = "hello, this is the actual file content".encodeToByteArray()
        val packedMetadata = MessagePack.encode(metadata)
        // length(3, big-endian uint24) prefix per §10.2 step 1.
        val lengthPrefix = ByteArray(3).also {
            it[0] = ((packedMetadata.size shr 16) and 0xFF).toByte()
            it[1] = ((packedMetadata.size shr 8) and 0xFF).toByte()
            it[2] = (packedMetadata.size and 0xFF).toByte()
        }
        val bodyWithMetadata = lengthPrefix + packedMetadata + fileBytes

        val (advertisement, chunks) = senderSideBuild(bodyWithMetadata, hasMetadata = true)

        val resource = Resource(advertisement, tokenCrypto, linkKey)
        for (chunk in chunks) {
            assertTrue(resource.receivePart(chunk, crypto))
        }
        val reassembled = resource.assemble(crypto)

        // assemble() must return the file bytes ONLY — metadata prefix
        // stripped. This is the v1.1.24 fix.
        assertContentEquals(fileBytes, reassembled,
            "assemble must strip the §10.2 step 1 metadata prefix and return only the body bytes")

        // Parsed metadata must be exposed for the engine to surface
        // filename to the UI.
        val parsed = resource.parsedMetadata
        assertNotNull(parsed,
            "parsedMetadata must be populated when adv.hasMetadata=true")
        val nameRaw = parsed["name"]
        assertTrue(nameRaw is ByteArray, "name must be msgpack bin")
        assertContentEquals("test.txt".encodeToByteArray(), nameRaw as ByteArray)
    }

    @Test fun `assemble leaves parsedMetadata null when hasMetadata flag is unset`() = runTest {
        // Regression pin — the existing non-file Resource path (NomadNet
        // pages, propagation /get) must not be affected by the v1.1.24
        // metadata extraction logic.
        val payload = "page body bytes".encodeToByteArray()
        val (advertisement, chunks) = senderSideBuild(payload, hasMetadata = false)

        val resource = Resource(advertisement, tokenCrypto, linkKey)
        for (chunk in chunks) assertTrue(resource.receivePart(chunk, crypto))
        val reassembled = resource.assemble(crypto)
        assertContentEquals(payload, reassembled,
            "non-metadata Resource: bytes pass through unchanged")
        assertNull(resource.parsedMetadata,
            "no metadata flag → parsedMetadata stays null")
    }

    @Test fun `assemble rejects metadata prefix with declared length exceeding body`() = runTest {
        // A peer that lies about the metadata-prefix length (claims
        // 1 MB of metadata in a 100-byte body) must not crash the
        // receiver. Surface the malformed prefix as a ResourceError
        // rather than throwing IndexOutOfBoundsException or returning
        // garbage bytes.
        val fakeLength = ByteArray(3).also {
            // 0xFFFFFF = 16 MB — far more than the 50-byte body
            it[0] = 0xFF.toByte()
            it[1] = 0xFF.toByte()
            it[2] = 0xFF.toByte()
        }
        val bogusBody = fakeLength + ByteArray(47)
        val (advertisement, chunks) = senderSideBuild(bogusBody, hasMetadata = true)

        val resource = Resource(advertisement, tokenCrypto, linkKey)
        for (chunk in chunks) resource.receivePart(chunk, crypto)
        assertFailsWith<ResourceError>(
            "malformed metadata length must surface as ResourceError, not a runtime crash",
        ) {
            resource.assemble(crypto)
        }
    }

    @Test fun `single-chunk happy path - chunks in order assemble + verify`() = runTest {
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

    @Test fun `multi-chunk happy path - out-of-order delivery still assembles`() = runTest {
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

    @Test fun `ResourceAdvertisement parse accepts multi-segment (l greater than 1)`() = runTest {
        // §10.11 — multi-segment Resources are now supported; the receiver
        // sequences one segment per advertisement.
        val advBody = MessagePack.encode(mapOf<Any?, Any?>(
            "t" to 1024L, "d" to 1000L, "n" to 8, "h" to ByteArray(32),
            "r" to ByteArray(4), "o" to ByteArray(32), "i" to 1, "l" to 2,
            "f" to 0x05, // 0x01 encrypted | 0x04 split
            "m" to ByteArray(8 * Resource.MAPHASH_LEN),
        ))
        val adv = ResourceAdvertisement.parse(advBody, linkId)
        assertEquals(1, adv.segmentIndex)
        assertEquals(2, adv.totalSegments)
        assertEquals(8, adv.totalParts)
    }

    @Test fun `ResourceAdvertisement parse rejects more segments than the cap`() = runTest {
        val advBody = MessagePack.encode(mapOf<Any?, Any?>(
            "t" to 1024L, "d" to 1000L, "n" to 8, "h" to ByteArray(32),
            "r" to ByteArray(4), "o" to ByteArray(32),
            "i" to 1, "l" to Resource.MAX_RESOURCE_SEGMENTS + 1,
            "f" to 0x05,
            "m" to ByteArray(8 * Resource.MAPHASH_LEN),
        ))
        assertFailsWith<IllegalStateException>(
            "l beyond MAX_RESOURCE_SEGMENTS must be rejected",
        ) {
            ResourceAdvertisement.parse(advBody, linkId)
        }
    }

    @Test fun `ResourceAdvertisement parse accepts a hashmap fragment shorter than n`() = runTest {
        // §10.4 / §10.7 — when n > HASHMAP_MAX_LEN the ADV carries only the
        // first window of map_hashes; the rest arrive via RESOURCE_HMU.
        val n = 200
        val fragment = 84
        val advBody = MessagePack.encode(mapOf<Any?, Any?>(
            "t" to (n * 64).toLong(),
            "d" to (n * 64).toLong(),
            "n" to n,
            "h" to ByteArray(32),
            "r" to ByteArray(4),
            "o" to ByteArray(32),
            "i" to 1, "l" to 1,
            "f" to 0x01,
            "m" to ByteArray(fragment * Resource.MAPHASH_LEN),
        ))
        val adv = ResourceAdvertisement.parse(advBody, linkId)
        assertEquals(n, adv.totalParts)
        assertEquals(fragment, adv.partsInAd)
        assertEquals(fragment, adv.hashmap.size)
    }

    @Test fun `ResourceAdvertisement parse rejects a hashmap fragment longer than n`() = runTest {
        // `m` must never advertise more map_hashes than the part count `n`.
        val advBody = MessagePack.encode(mapOf<Any?, Any?>(
            "t" to 1024L, "d" to 1024L, "n" to 4,
            "h" to ByteArray(32), "r" to ByteArray(4), "o" to ByteArray(32),
            "i" to 1, "l" to 1, "f" to 0x01,
            "m" to ByteArray(8 * Resource.MAPHASH_LEN), // 8 entries > n=4
        ))
        assertFailsWith<IllegalStateException>(
            "a hashmap fragment longer than n must be rejected",
        ) {
            ResourceAdvertisement.parse(advBody, linkId)
        }
    }

    @Test fun `ResourceAdvertisement parse rejects an absurd part count`() = runTest {
        // `n` drives pre-allocation of the parts/hashmap arrays — cap it
        // independently of transferSize so a hostile peer can't OOM us.
        val advBody = MessagePack.encode(mapOf<Any?, Any?>(
            "t" to 1024L, "d" to 1024L,
            "n" to Resource.MAX_RESOURCE_PARTS + 1,
            "h" to ByteArray(32), "r" to ByteArray(4), "o" to ByteArray(32),
            "i" to 1, "l" to 1, "f" to 0x01,
            "m" to ByteArray(4 * Resource.MAPHASH_LEN),
        ))
        assertFailsWith<IllegalStateException>(
            "n beyond MAX_RESOURCE_PARTS must be rejected",
        ) {
            ResourceAdvertisement.parse(advBody, linkId)
        }
    }

    // v0.1.55 — Resource size cap (security S2):
    //
    // Pre-v0.1.55 ResourceAdvertisement.parse trusted whatever transferSize /
    // dataSize the responder advertised. The receiver was already capped at
    // HASHMAP_MAX_LEN=84 chunks (~33 KB raw), but a node could declare
    // dataSize=2GB and the post-decompression buffer in Resource.assemble
    // would happily allocate that. A small bz2-compressed payload that
    // expands to gigabytes (compression bomb) bypasses the chunk-count cap
    // entirely. Cap both at parse time so we never even start receiving
    // the chunks for an obviously-oversized resource.

    @Test fun `ResourceAdvertisement parse rejects oversized transferSize`() = runTest {
        // Cap is 2 MiB (Resource.MAX_RESOURCE_BYTES). Declare 100 MB.
        val advBody = io.github.thatsfguy.reticulum.codec.MessagePack.encode(mapOf<Any?, Any?>(
            "t" to 100L * 1024 * 1024,
            "d" to 1024L,                  // dataSize ok
            "n" to 8,
            "h" to ByteArray(32),
            "r" to ByteArray(4),
            "o" to ByteArray(32),
            "i" to 1, "l" to 1,
            "f" to 0x01,
            "m" to ByteArray(8 * Resource.MAPHASH_LEN),
        ))
        assertFailsWith<IllegalStateException>(
            "transferSize > MAX_RESOURCE_BYTES must be rejected at parse time",
        ) {
            ResourceAdvertisement.parse(advBody, linkId)
        }
    }

    @Test fun `ResourceAdvertisement parse rejects oversized dataSize`() = runTest {
        val advBody = io.github.thatsfguy.reticulum.codec.MessagePack.encode(mapOf<Any?, Any?>(
            "t" to 1024L,                  // transferSize ok
            "d" to 100L * 1024 * 1024,     // dataSize over cap
            "n" to 8,
            "h" to ByteArray(32),
            "r" to ByteArray(4),
            "o" to ByteArray(32),
            "i" to 1, "l" to 1,
            "f" to 0x01,
            "m" to ByteArray(8 * Resource.MAPHASH_LEN),
        ))
        assertFailsWith<IllegalStateException>(
            "dataSize > MAX_RESOURCE_BYTES must be rejected at parse time",
        ) {
            ResourceAdvertisement.parse(advBody, linkId)
        }
    }

    @Test fun `ResourceAdvertisement parse accepts a normal-sized advertisement`() = runTest {
        // Sanity: a real-world-sized payload (10 KB) still parses cleanly.
        val advBody = io.github.thatsfguy.reticulum.codec.MessagePack.encode(mapOf<Any?, Any?>(
            "t" to 12_000L,
            "d" to 10_000L,
            "n" to 32,
            "h" to ByteArray(32),
            "r" to ByteArray(4),
            "o" to ByteArray(32),
            "i" to 1, "l" to 1,
            "f" to 0x01,
            "m" to ByteArray(32 * Resource.MAPHASH_LEN),
        ))
        ResourceAdvertisement.parse(advBody, linkId)  // must not throw
    }

    // ---- §10.7 RESOURCE_HMU — hashmap continuation -------------------------
    //
    // A resource whose part count exceeds HASHMAP_MAX_LEN can't fit its
    // whole hashmap in one advertisement. The ADV carries the first window;
    // the receiver learns the rest from RESOURCE_HMU packets, each pulled
    // by a RESOURCE_REQ with the exhausted flag set. These tests drive the
    // receive algorithm directly: hashmapUpdate applies the windows,
    // nextRequestBatch produces the windowed requests.

    @Test fun `HMU - large resource assembles after hashmap continuation windows`() = runTest {
        val payload = ByteArray(46_000) { (it * 7 % 251).toByte() }
        val build = senderSideBuildLarge(payload, advFragmentLen = 84)
        assertTrue(
            build.chunks.size > 84,
            "test setup error — need a hashmap that doesn't fit one ADV (got ${build.chunks.size})",
        )

        val res = Resource(build.adv, tokenCrypto, linkKey)
        assertFalse(res.hashmapComplete, "only the first window is known after the ADV")

        // Apply the HMU windows the sender would emit for the remainder.
        var segment = 1
        var off = 84
        while (off < build.fullHashmap.size) {
            val end = (off + 84).coerceAtMost(build.fullHashmap.size)
            val windowBytes = build.fullHashmap.subList(off, end)
                .fold(ByteArray(0)) { acc, h -> acc + h }
            res.hashmapUpdate(segment, windowBytes)
            segment++
            off = end
        }
        assertTrue(res.hashmapComplete, "every map_hash known after the HMU windows")

        for (chunk in build.chunks) {
            assertTrue(res.receivePart(chunk, crypto), "every chunk must slot-match")
        }
        assertTrue(res.isComplete)
        assertContentEquals(payload, res.assemble(crypto))
    }

    @Test fun `HMU - request batches window correctly and flag exhaustion`() = runTest {
        val payload = ByteArray(46_000) { it.toByte() }
        val build = senderSideBuildLarge(payload, advFragmentLen = 84)
        val res = Resource(build.adv, tokenCrypto, linkKey)

        // First known window = 84 parts. With an 80-hash cap that's two
        // batches; the second drains the window so it carries the
        // exhausted flag + the last known map_hash.
        val b1 = res.nextRequestBatch(80) ?: error("expected a first batch")
        assertEquals(80, b1.mapHashes.size)
        assertFalse(b1.exhausted, "parts 80..83 still pending — not exhausted yet")

        val b2 = res.nextRequestBatch(80) ?: error("expected a second batch")
        assertEquals(4, b2.mapHashes.size)
        assertTrue(b2.exhausted, "draining the known window with parts left → exhausted")
        assertContentEquals(
            build.fullHashmap[83], b2.lastMapHash,
            "an exhausted REQ must carry the last known map_hash",
        )

        assertNull(
            res.nextRequestBatch(80),
            "nothing more to request until an HMU extends the hashmap",
        )

        // Sender answers the exhausted REQ with the next window.
        val window1 = build.fullHashmap
            .subList(84, (84 + 84).coerceAtMost(build.fullHashmap.size))
            .fold(ByteArray(0)) { acc, h -> acc + h }
        res.hashmapUpdate(1, window1)
        val b3 = res.nextRequestBatch(80) ?: error("expected a batch for the new window")
        assertContentEquals(
            build.fullHashmap[84], b3.mapHashes[0],
            "the new batch must start at the first freshly-revealed part",
        )
    }

    @Test fun `HMU - out-of-boundary continuation window is rejected`() = runTest {
        // §10.7 — an HMU whose segment doesn't land on a HASHMAP_MAX_LEN
        // boundary is a sequencing error.
        val payload = ByteArray(46_000) { it.toByte() }
        val build = senderSideBuildLarge(payload, advFragmentLen = 84)
        val res = Resource(build.adv, tokenCrypto, linkKey)
        // hashmapUpdate rejects on the segment boundary before touching the
        // window content, so a placeholder window is fine here.
        val window = ByteArray(84 * Resource.MAPHASH_LEN)
        assertFailsWith<ResourceError>("segment 2 would skip segment 1") {
            res.hashmapUpdate(2, window)
        }
    }

    /** Sender-side bundle for a resource whose hashmap spans HMU windows. */
    private class LargeBuild(
        val adv: ResourceAdvertisement,
        val chunks: List<ByteArray>,
        val fullHashmap: List<ByteArray>,
        val advFragmentLen: Int,
    )

    /**
     * Build a single-segment resource carrying [payload] whose
     * advertisement exposes only the first [advFragmentLen] map_hashes —
     * the rest are delivered to the receiver via [Resource.hashmapUpdate].
     */
    private fun senderSideBuildLarge(payload: ByteArray, advFragmentLen: Int): LargeBuild {
        val randomHash = ByteArray(Resource.RANDOM_HASH_SIZE) { (it + 0x42).toByte() }
        val inner = randomHash + payload
        val outerToken = runBlocking { tokenCrypto.encryptWithDerivedKey(inner, linkKey) }

        val sdu = Resource.DEFAULT_SDU
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < outerToken.size) {
            val end = (offset + sdu).coerceAtMost(outerToken.size)
            chunks.add(outerToken.copyOfRange(offset, end))
            offset = end
        }
        if (chunks.isEmpty()) chunks.add(ByteArray(0))

        val fullHashmap = chunks.map { chunk ->
            runBlocking { Resource.chunkHash(chunk, randomHash, crypto) }
        }
        val integrityHash = runBlocking { crypto.sha256(payload + randomHash) }

        val adv = ResourceAdvertisement(
            linkId = linkId,
            transferSize = outerToken.size.toLong(),
            dataSize = inner.size.toLong(),
            partsInAd = advFragmentLen,
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
            hashmap = fullHashmap.take(advFragmentLen),
        )
        return LargeBuild(adv, chunks, fullHashmap, advFragmentLen)
    }

    // ---- Sender-side helper ------------------------------------------------

    /**
     * Build the sender-side bytes for a single-segment resource carrying
     * [payload]. Returns the advertisement + chunk plaintexts in
     * chunk-index order. Uses fixed pseudo-random_hash so tests are
     * reproducible.
     */
    private fun senderSideBuild(payload: ByteArray, hasMetadata: Boolean = false): Pair<ResourceAdvertisement, List<ByteArray>> {
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
            hasMetadata = hasMetadata,
            hashmap = hashmap,
        )
        return advertisement to chunks
    }
}
