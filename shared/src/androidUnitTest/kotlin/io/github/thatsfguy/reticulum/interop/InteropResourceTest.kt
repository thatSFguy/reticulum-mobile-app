package io.github.thatsfguy.reticulum.interop

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.resource.Resource
import io.github.thatsfguy.reticulum.resource.ResourceAdvertisement
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live interop for SPEC §10 Resource against upstream Python RNS.
 *
 * ## Why this file exists
 *
 * `validate_vectors.py` cross-checks announces, opportunistic LXMF,
 * LRPROOF and the link handshake against upstream — and nothing about
 * Resource. That gap is why a RESOURCE_PRF bug survived from
 * 2026-05-19 to 2026-09-02: every Resource test we had was a
 * self-round-trip, and `playbook.md` §5 is explicit that those cannot
 * catch a wire-format divergence, because both ends drift identically.
 *
 * The specific divergence: when a resource carries metadata, what
 * bytes does the proof cover? Our `assemble()` strips the §10.2 step 1
 * metadata prefix before returning, so "hash what assemble returned"
 * and "hash what the sender hashed" are different answers, and only
 * one of them is right. A self-round-trip agrees with itself either
 * way; upstream does not.
 *
 * These tests take the answer from RNS itself rather than from our
 * reading of the spec — `python_peer.py`'s `build_resource` op drives
 * `RNS.Resource.__init__` and reports the `expected_proof` it derived.
 *
 * Skips cleanly when python / rns aren't installed, like the other
 * interop tests, so CI on a bare machine stays green.
 */
class InteropResourceTest {

    private val crypto = TestVectors.crypto
    private val tokenCrypto = TokenCrypto(crypto)
    private val linkKey = ByteArray(64) { (it + 0xa0).toByte() }
    private val linkId = ByteArray(16) { (it + 0x10).toByte() }

    private fun String.hex(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Build the receive side from hash material upstream produced, so
     * the only thing under test is what OUR code does with it.
     *
     * [body] is the full plaintext RNS hashed — the metadata prefix and
     * the payload together. [rnsRandomHash] and [rnsHash] are its `r`
     * and `h`, so our integrity check verifies against upstream values
     * rather than ones we invented.
     */
    private fun receiveSideFrom(
        body: ByteArray,
        rnsRandomHash: ByteArray,
        rnsHash: ByteArray,
        hasMetadata: Boolean,
    ): Pair<ResourceAdvertisement, List<ByteArray>> {
        // The 4-byte wire prefix is a FRESH random value upstream, not
        // `r` (§10.8 step 3 / the callout under it) — the receiver
        // strips it without comparing, so any 4 bytes stand in.
        val wirePrefix = ByteArray(Resource.RANDOM_HASH_SIZE) { 0x5a }
        val inner = wirePrefix + body
        val outerToken = runBlocking { tokenCrypto.encryptWithDerivedKey(inner, linkKey) }

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < outerToken.size) {
            val end = (offset + Resource.DEFAULT_SDU).coerceAtMost(outerToken.size)
            chunks.add(outerToken.copyOfRange(offset, end))
            offset = end
        }
        if (chunks.isEmpty()) chunks.add(ByteArray(0))

        val hashmap = chunks.map { runBlocking { Resource.chunkHash(it, rnsRandomHash, crypto) } }

        val adv = ResourceAdvertisement(
            linkId = linkId,
            transferSize = outerToken.size.toLong(),
            dataSize = inner.size.toLong(),
            partsInAd = chunks.size,
            totalParts = chunks.size,
            hash = rnsHash,
            randomHash = rnsRandomHash,
            originalHash = rnsHash,
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
        return adv to chunks
    }

    /**
     * The one that matters: with metadata present, our RESOURCE_PRF must
     * equal the `expected_proof` upstream pre-computed, bytewise. The
     * initiator's `validate_proof` compares `proof_data[32:]` to it
     * directly (§10.8), so anything else is rejected and the sender
     * retransmits to its watchdog.
     */
    @Test fun rnsProofCoversTheMetadataPrefix() = runBlocking {
        val peer = PythonPeer.startOrSkip()
        Assume.assumeTrue("python/rns not available — skipping", peer != null)

        peer!!.use { p ->
            val payload = "hello, this is the actual file content".encodeToByteArray()
            val r = p.call(
                "build_resource",
                mapOf("payload_hex" to payload.hex(), "metadata_name" to "test.txt"),
            )

            assertEquals(true, r["has_metadata"], "peer must have built a metadata-bearing resource")
            val metadataPrefix = (r["metadata_prefix_hex"] as String).hex()
            val rnsRandomHash = (r["random_hash_hex"] as String).hex()
            val rnsHash = (r["hash_hex"] as String).hex()
            val expectedProof = (r["expected_proof_hex"] as String).hex()

            assertTrue(metadataPrefix.isNotEmpty(), "metadata prefix must be present")

            // Exactly what RNS hashed: `data = self.metadata + resource_data`.
            val body = metadataPrefix + payload
            val (adv, chunks) = receiveSideFrom(body, rnsRandomHash, rnsHash, hasMetadata = true)

            val res = Resource(adv, tokenCrypto, linkKey)
            for (chunk in chunks) assertTrue(res.receivePart(chunk, crypto))

            // Integrity verifies against upstream's own `h`, which is
            // itself a check that we hash the same bytes it does.
            val assembled = res.assemble(crypto)

            // assemble() returns the payload WITHOUT the prefix...
            assertContentEquals(payload, assembled, "assemble must strip the metadata prefix")
            assertFalse(body.contentEquals(assembled), "the two bodies must actually differ here")

            // ...and the proof must nonetheless cover the body WITH it.
            val proof = res.buildProofPayload(crypto)
            assertEquals(64, proof.size)
            assertContentEquals(adv.hash, proof.copyOfRange(0, 32), "proof carries resource_hash first")
            assertContentEquals(
                expectedProof, proof.copyOfRange(32, 64),
                "our RESOURCE_PRF must equal upstream's expected_proof; hashing " +
                    "assemble()'s stripped return value makes RNS reject the proof",
            )
        }
    }

    /** The no-metadata path, where the two candidate bodies coincide.
     *  Pinned so the common case is also checked against upstream and
     *  not merely against ourselves. */
    @Test fun rnsProofMatchesWithoutMetadata() = runBlocking {
        val peer = PythonPeer.startOrSkip()
        Assume.assumeTrue("python/rns not available — skipping", peer != null)

        peer!!.use { p ->
            val payload = "no metadata on this one".encodeToByteArray()
            val r = p.call("build_resource", mapOf("payload_hex" to payload.hex()))

            assertEquals(false, r["has_metadata"])
            val rnsRandomHash = (r["random_hash_hex"] as String).hex()
            val rnsHash = (r["hash_hex"] as String).hex()
            val expectedProof = (r["expected_proof_hex"] as String).hex()

            val (adv, chunks) = receiveSideFrom(payload, rnsRandomHash, rnsHash, hasMetadata = false)
            val res = Resource(adv, tokenCrypto, linkKey)
            for (chunk in chunks) assertTrue(res.receivePart(chunk, crypto))

            assertContentEquals(payload, res.assemble(crypto))
            assertContentEquals(
                expectedProof, res.buildProofPayload(crypto).copyOfRange(32, 64),
            )
        }
    }
}
