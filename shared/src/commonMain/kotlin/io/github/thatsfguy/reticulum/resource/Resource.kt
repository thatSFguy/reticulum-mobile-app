package io.github.thatsfguy.reticulum.resource

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.codec.bz2Decompress
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.TokenCrypto

/**
 * Receive-only port of `RNS.Resource` from upstream Reticulum.
 *
 * Wire summary (verified against `markqvist/Reticulum@master` `RNS/Resource.py`):
 *
 *  1. Sender emits a `CONTEXT_RESOURCE_ADV` packet whose Token-decrypted body
 *     is a msgpack dict carrying transfer metadata + the chunk hashmap.
 *  2. Sender then emits `n` (or `total_parts = ceil(t/SDU)`) `CONTEXT_RESOURCE`
 *     packets. Each chunk is independently link-encrypted; the decrypted
 *     bytes are arbitrary slices of an OUTER Token blob that wraps the
 *     full payload.
 *  3. Receiver matches each chunk to a slot via `sha256(chunk || randomHash)[:4]`
 *     against the ADV's hashmap, fills the slot.
 *  4. Once all parts are present, receiver concatenates them, runs
 *     `link.decrypt()` on the concatenation (the OUTER decrypt), strips the
 *     leading 4-byte `randomHash`, optionally bz2-decompresses, and verifies
 *     `sha256(plain || randomHash) == adv.h`.
 *  5. Receiver emits `CONTEXT_RESOURCE_PRF` (`PACKET_PROOF` to link_id) with
 *     payload `adv.h(32) || sha256(plain || adv.h)(32)` so the sender stops
 *     retransmitting.
 *  6. Reassembled bytes are delivered via the same callback shape as a
 *     single-packet RESPONSE — for `/get` round 2 that means a msgpack
 *     `[request_id(16), response_data]` envelope to the request handler.
 *
 * MVP scope:
 *  - Single-segment only (`l == 1`). Multi-segment Resources (`l > 1`) bail
 *    with [ResourceError] so the caller can surface a clear error.
 *  - HASHMAP_REQ / HASHMAP_HMU on long resources (n > HASHMAP_MAX_LEN ≈ 84) is
 *    NOT implemented — anything beyond ~42 KB total bails. Real-world
 *    propagation responses are well under that for typical message sizes.
 *  - Window/window growth/retransmit logic is out of scope; we just collect
 *    chunks until the sender stops or we see all of them.
 */
class Resource internal constructor(
    val advertisement: ResourceAdvertisement,
    private val link: TokenCrypto,
    private val linkKey: ByteArray,
) {
    /** Receive slot per chunk index. null = not yet seen. */
    private val parts: Array<ByteArray?> = arrayOfNulls(advertisement.totalParts)
    private var partsReceived = 0

    val isComplete: Boolean get() = partsReceived == advertisement.totalParts
    val linkId: ByteArray get() = advertisement.linkId

    /**
     * Feed a CONTEXT_RESOURCE chunk's plaintext into the receive buffer.
     * The chunk body is matched against the hashmap to figure out which
     * slot it fills — the wire has no per-chunk index or sequence number.
     *
     * Returns true if the chunk was new (caller should expect more), false
     * if it was a duplicate or didn't match any slot.
     */
    suspend fun receivePart(chunkPlaintext: ByteArray, crypto: CryptoProvider): Boolean {
        val hash = chunkHash(chunkPlaintext, advertisement.randomHash, crypto)
        // Match against any unfilled slot in the advertised hashmap. The
        // upstream implementation uses a window over `consecutive_index` to
        // bound the linear search — our MVP just walks the whole map since
        // resources we accept are bounded to HASHMAP_MAX_LEN parts anyway.
        val map = advertisement.hashmap
        for (i in map.indices) {
            if (parts[i] != null) continue
            if (hash.contentEquals(map[i])) {
                parts[i] = chunkPlaintext
                partsReceived++
                return true
            }
        }
        return false
    }

    /**
     * After all parts are present, reassemble + verify. Returns the inner
     * payload bytes (post-decompress, post-randomHash-strip, integrity-
     * verified). Throws [ResourceError] on any failure.
     */
    suspend fun assemble(crypto: CryptoProvider): ByteArray {
        if (!isComplete) {
            throw ResourceError("incomplete resource: $partsReceived/${advertisement.totalParts} parts")
        }
        // Single-pass concat: pre-size + copy, instead of repeated +-allocation
        // (O(n²) on big resources).
        val totalSize = parts.sumOf { requireNotNull(it) { "internal: null part after isComplete" }.size }
        val concatenated = ByteArray(totalSize)
        var off = 0
        for (p in parts) {
            val pp = p!!
            pp.copyInto(concatenated, off)
            off += pp.size
        }
        val outerPlaintext = if (advertisement.encrypted) {
            runCatching { link.decryptWithDerivedKey(concatenated, linkKey) }
                .getOrElse { throw ResourceError("outer decrypt failed: ${it.message}") }
        } else {
            concatenated
        }
        if (outerPlaintext.size < RANDOM_HASH_SIZE) {
            throw ResourceError("outer plaintext too short: ${outerPlaintext.size} bytes")
        }
        // v0.1.75: per upstream Resource.py:931-933, the leading 4-byte
        // random_hash prefix is just STRIPPED — not compared to
        // advertisement.r. Sender-side `RNS.Identity.get_random_hash()[:4]`
        // (line 567) is a fresh random call, distinct from `self.random_hash`
        // which the advertisement's `r` field carries. Integrity is
        // proven by the SHA256(data || random_hash) == adv.h check
        // below; the prefix bytes don't need to match anything.
        val maybeCompressed = outerPlaintext.copyOfRange(RANDOM_HASH_SIZE, outerPlaintext.size)
        val data = if (advertisement.compressed) {
            // Cap decompressed output at the advertised dataSize plus a
            // small tolerance for the leading randomHash slice — the
            // caller verifies the integrity hash on the raw inner bytes
            // afterward, so any genuine decompression that fits the cap
            // by ≤256 B is safe to accept. A bz2 bomb that lies about
            // dataSize is already rejected at parse time (S2 cap on
            // dataSize); this is defense in depth.
            val cap = (advertisement.dataSize + 256).coerceAtMost(MAX_RESOURCE_BYTES).toInt()
            runCatching { bz2Decompress(maybeCompressed, maxBytes = cap) }
                .getOrElse { throw ResourceError("bz2 decompress failed: ${it.message}") }
        } else {
            maybeCompressed
        }
        // Verify integrity: sha256(data || randomHash) == adv.h.
        val verifyInput = ByteArray(data.size + advertisement.randomHash.size).also {
            data.copyInto(it, 0)
            advertisement.randomHash.copyInto(it, data.size)
        }
        val computed = crypto.sha256(verifyInput)
        if (!computed.contentEquals(advertisement.hash)) {
            throw ResourceError("integrity hash mismatch")
        }
        return data
    }

    /**
     * Build the CONTEXT_RESOURCE_PRF payload to stop the sender's retry
     * loop. Caller wraps in a PACKET_PROOF flags=0x0F, dest=link_id,
     * context=0x05 packet and sends.
     *
     * Layout: adv.h(32) || sha256(plain || adv.h)(32) = 64 bytes.
     */
    suspend fun buildProofPayload(plain: ByteArray, crypto: CryptoProvider): ByteArray {
        val proofInput = ByteArray(plain.size + advertisement.hash.size).also {
            plain.copyInto(it, 0)
            advertisement.hash.copyInto(it, plain.size)
        }
        val proofHash = crypto.sha256(proofInput)
        return ByteArray(advertisement.hash.size + proofHash.size).also {
            advertisement.hash.copyInto(it, 0)
            proofHash.copyInto(it, advertisement.hash.size)
        }
    }

    /**
     * Sender-side bundle returned by [buildOutbound]. Both fields are
     * ready-to-wire:
     *
     *   - [advBodyCipher] goes into a CTX_RESOURCE_ADV DATA packet body.
     *   - Each [chunks] entry goes into a CTX_RESOURCE DATA packet body.
     *
     * [advertisement] is exposed for inspection / sanity assertions and
     * mirrors what the receiver will parse out of [advBodyCipher].
     */
    data class OutboundResource(
        val advertisement: ResourceAdvertisement,
        val advBodyCipher: ByteArray,
        val chunks: List<ByteArray>,
    )

    companion object {
        const val RANDOM_HASH_SIZE = 4
        const val MAPHASH_LEN = 4
        /** Cap on n_in_adv for MVP receive — matches HASHMAP_MAX_LEN-style
         *  ceiling in upstream and avoids implementing REQ/HMU. */
        const val HASHMAP_MAX_LEN = 84
        /** Hard cap on advertised transferSize / dataSize (security S2,
         *  v0.1.55). 2 MiB covers any real NomadNet page (typical 5-50 KB)
         *  plus headroom for `/file/` downloads, but well under
         *  small-device OOM thresholds. A misbehaving / hostile node can
         *  advertise gigabyte-scale resources and our pre-allocated
         *  buffers in [assemble] would happily try to satisfy them.
         *  Pre-v0.1.55 the only effective bound was HASHMAP_MAX_LEN
         *  (~33 KB raw), but post-decompression had no cap at all — a
         *  small bz2 bomb bypassed the chunk-count limit entirely. */
        const val MAX_RESOURCE_BYTES = 2 * 1024 * 1024L
        /** Per-chunk SDU (matches Link.MDU on the sender side: MTU minus
         *  header (~19) minus Token overhead (~48). Used to compute
         *  total_parts when ADV doesn't pin it explicitly. */
        const val DEFAULT_SDU = 433

        /**
         * Compute the 4-byte chunk identity hash that the sender's hashmap
         * stores: SHA-256(chunkPlaintext || randomHash) truncated to 4 bytes.
         */
        suspend fun chunkHash(chunk: ByteArray, randomHash: ByteArray, crypto: CryptoProvider): ByteArray {
            val input = ByteArray(chunk.size + randomHash.size).also {
                chunk.copyInto(it, 0)
                randomHash.copyInto(it, chunk.size)
            }
            return crypto.sha256(input).copyOfRange(0, MAPHASH_LEN)
        }

        /**
         * Build the outbound bytes for a single-segment Resource carrying [plain]
         * over the active link. Mirror of upstream `RNS.Resource.advertise()` /
         * the wire algorithm in §10.2 step 1-3:
         *
         *  1. Prepend a 4-byte random_hash to the payload.
         *  2. Token-encrypt the whole (`random_hash || payload`) blob with the
         *     link's derived key — this is the OUTER encrypt; chunks are slices
         *     of the resulting ciphertext, not individually encrypted.
         *  3. Split the outer ciphertext into ≤[sdu]-byte chunks.
         *  4. Compute the per-chunk hashmap: `SHA-256(chunk || random_hash)[:4]`.
         *  5. Compute integrity: `SHA-256(payload || random_hash)` (over the
         *     uncompressed inner payload — same input the receiver verifies
         *     against in [Resource.assemble]).
         *  6. Pack the advertisement msgpack dict (single-segment, encrypted,
         *     uncompressed for MVP — bz2 outbound deferred since JPEGs and
         *     other typical attachments don't compress further).
         *
         * Reject payloads that would require more than [HASHMAP_MAX_LEN] chunks
         * — REQ/HMU isn't implemented, and pushing past the cap would silently
         * fail at the receiver's ADV-parse step anyway.
         */
        suspend fun buildOutbound(
            plain: ByteArray,
            link: TokenCrypto,
            linkKey: ByteArray,
            linkId: ByteArray,
            crypto: CryptoProvider,
            requestId: ByteArray? = null,
            sdu: Int = DEFAULT_SDU,
        ): OutboundResource {
            require(plain.size.toLong() <= MAX_RESOURCE_BYTES) {
                "payload ${plain.size}B exceeds MAX_RESOURCE_BYTES=$MAX_RESOURCE_BYTES"
            }
            if (requestId != null) {
                require(requestId.size == 16) { "requestId must be 16 bytes, got ${requestId.size}" }
            }

            val randomHash = crypto.randomBytes(RANDOM_HASH_SIZE)

            // §10.2 step 1-2: prepend the 4-byte random_hash and outer-encrypt
            // the whole blob with the link's derived key. The receiver strips
            // these 4 bytes after the outer-decrypt; their job is to defend
            // against ciphertext correlation when the payload has a predictable
            // prefix. We reuse `randomHash` as the prefix — upstream uses a
            // fresh distinct `RNS.Identity.get_random_hash()[:4]`, but the
            // receiver doesn't compare the prefix to anything, so either is
            // wire-correct.
            val outerPlain = ByteArray(randomHash.size + plain.size).also {
                randomHash.copyInto(it, 0)
                plain.copyInto(it, randomHash.size)
            }
            val outerCipher = link.encryptWithDerivedKey(outerPlain, linkKey)

            // §10.2 step 3: split into SDU-sized chunks.
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < outerCipher.size) {
                val end = (offset + sdu).coerceAtMost(outerCipher.size)
                chunks.add(outerCipher.copyOfRange(offset, end))
                offset = end
            }
            if (chunks.isEmpty()) chunks.add(ByteArray(0))
            check(chunks.size <= HASHMAP_MAX_LEN) {
                "resource too large (${chunks.size} chunks > MVP cap $HASHMAP_MAX_LEN); REQ/HMU not implemented"
            }

            // §10.2 step 4: hashmap[i] = SHA-256(chunk_i || random_hash)[:4]
            val hashmap = chunks.map { chunkHash(it, randomHash, crypto) }
            val hashmapBytes = ByteArray(hashmap.sumOf { it.size }).also {
                var off = 0
                for (h in hashmap) {
                    h.copyInto(it, off)
                    off += h.size
                }
            }

            // §10.2 step 5: integrity hash over the uncompressed payload.
            // Receiver verifies the post-assemble bytes against this.
            val integrityInput = ByteArray(plain.size + randomHash.size).also {
                plain.copyInto(it, 0)
                randomHash.copyInto(it, plain.size)
            }
            val integrityHash = crypto.sha256(integrityInput)

            // §10.2 step 6: pack the advertisement. Flags:
            //   0x01 encrypted (always on — outer-token-encrypted)
            //   0x02 compressed (off — MVP doesn't bz2 outbound)
            //   0x04 split      (off — single-segment only)
            //   0x08 isRequest  (off — we never originate requests via Resource)
            //   0x10 isResponse (set when requestId present, matches /get reply shape)
            //   0x20 hasMetadata(off)
            val flags = 0x01 or (if (requestId != null) 0x10 else 0)
            val transferSize = outerCipher.size.toLong()
            val dataSize = plain.size.toLong()

            // CRITICAL: upstream RNS Resource.py:1373 `adv.q =
            // dictionary["q"]` indexes without a KeyError guard. Every
            // ADV must include all 11 keys (t, d, n, h, r, o, m, f, i,
            // l, q); omitting "q" when there's no requestId causes
            // upstream RNS unpack to throw KeyError, which prevents the
            // receiver from registering the inbound Resource — Sideband
            // never sends a CTX_RESOURCE_REQ in response, and our
            // sender times out waiting for one. Encode null as msgpack
            // nil (0xC0) when no request, matching upstream's `self.q =
            // None` shape (Resource.py:1294).
            //
            // Pre-v1.1.19 emitted "q" conditionally, which worked
            // mobile↔mobile because our own receiver only reads "q"
            // when it's present, but silently broke mobile→Sideband.
            // Discovered 2026-05-13 by diff'ing our pack() against
            // upstream Resource.py.
            val advDict = LinkedHashMap<Any?, Any?>().apply {
                put("f", flags)
                put("m", hashmapBytes)
                put("n", chunks.size)
                put("t", transferSize)
                put("d", dataSize)
                put("h", integrityHash)
                put("r", randomHash)
                put("o", integrityHash)  // single-segment: originalHash == integrityHash
                put("i", 1)              // segment index (1-based)
                put("l", 1)              // total segments
                put("q", requestId)      // null = msgpack nil (0xC0), required by upstream RNS
            }
            val advBody = MessagePack.encode(advDict)
            val advBodyCipher = link.encryptWithDerivedKey(advBody, linkKey)

            val advertisement = ResourceAdvertisement(
                linkId        = linkId,
                transferSize  = transferSize,
                dataSize      = dataSize,
                partsInAd     = chunks.size,
                totalParts    = chunks.size,
                hash          = integrityHash,
                randomHash    = randomHash,
                originalHash  = integrityHash,
                segmentIndex  = 1,
                totalSegments = 1,
                requestId     = requestId,
                encrypted     = true,
                compressed    = false,
                split         = false,
                isRequest     = false,
                isResponse    = requestId != null,
                hasMetadata   = false,
                hashmap       = hashmap,
            )

            return OutboundResource(advertisement, advBodyCipher, chunks)
        }
    }
}

class ResourceError(message: String) : RuntimeException(message)

/**
 * Decoded form of a CONTEXT_RESOURCE_ADV packet body. The wire body is
 * a msgpack dict with single-letter keys (see `Resource.py
 * ResourceAdvertisement.pack`).
 */
data class ResourceAdvertisement(
    /** link_id this resource is being delivered on. */
    val linkId: ByteArray,
    /** transfer size — total bytes that go on the wire across all chunks. */
    val transferSize: Long,
    /** decompressed data size, before the leading randomHash is stripped. */
    val dataSize: Long,
    /** count of chunks in THIS ad's hashmap (==total_parts when ≤ HASHMAP_MAX_LEN). */
    val partsInAd: Int,
    /** total number of chunks the receiver expects in the stream. */
    val totalParts: Int,
    /** 32-byte SHA-256 over `data || randomHash` — integrity tag. */
    val hash: ByteArray,
    /** 4-byte salt mixed into chunk identity hashes and integrity tag. */
    val randomHash: ByteArray,
    /** 32-byte SHA-256 — same as `hash` for single-segment, distinct for split. */
    val originalHash: ByteArray,
    /** 1-based segment index. */
    val segmentIndex: Int,
    /** total number of segments (1 for typical /get response). */
    val totalSegments: Int,
    /** 16-byte request_id when this resource is a request/response (matches the
     *  request_id our LinkSession sent with the original REQUEST). null otherwise. */
    val requestId: ByteArray?,
    val encrypted: Boolean,
    val compressed: Boolean,
    val split: Boolean,
    val isRequest: Boolean,
    val isResponse: Boolean,
    val hasMetadata: Boolean,
    /** List of 4-byte chunk hashes in chunk-index order. */
    val hashmap: List<ByteArray>,
) {
    companion object {
        /**
         * Parse a Token-decrypted CONTEXT_RESOURCE_ADV payload.
         *
         * @param plaintext the link.decrypt()'d body of the ADV packet
         * @param linkId the link this ADV arrived on (for the returned object)
         */
        fun parse(plaintext: ByteArray, linkId: ByteArray): ResourceAdvertisement {
            val dict = MessagePack.decode(plaintext)
            require(dict is Map<*, *>) { "ADV body is not a map: ${dict?.let { it::class.simpleName }}" }
            fun reqLong(key: String): Long {
                val v = dict[key] ?: error("ADV missing key '$key'")
                return when (v) {
                    is Number -> v.toLong()
                    else -> error("ADV key '$key' is ${v::class.simpleName}, expected Number")
                }
            }
            fun reqInt(key: String): Int = reqLong(key).toInt()
            fun reqBytes(key: String): ByteArray {
                val v = dict[key] ?: error("ADV missing key '$key'")
                return when (v) {
                    is ByteArray -> v
                    is String -> v.encodeToByteArray()
                    else -> error("ADV key '$key' is ${v::class.simpleName}, expected ByteArray")
                }
            }
            val flags = reqInt("f")
            val encrypted   = (flags and 0x01) != 0
            val compressed  = (flags and 0x02) != 0
            val split       = (flags and 0x04) != 0
            val isRequest   = (flags and 0x08) != 0
            val isResponse  = (flags and 0x10) != 0
            val hasMetadata = (flags and 0x20) != 0

            val hashmapBytes = reqBytes("m")
            require(hashmapBytes.size % Resource.MAPHASH_LEN == 0) {
                "ADV hashmap length ${hashmapBytes.size} not divisible by ${Resource.MAPHASH_LEN}"
            }
            val hashmap = List(hashmapBytes.size / Resource.MAPHASH_LEN) { i ->
                hashmapBytes.copyOfRange(i * Resource.MAPHASH_LEN, (i + 1) * Resource.MAPHASH_LEN)
            }

            val partsInAd = reqInt("n")
            val transferSize = reqLong("t")
            val dataSize = reqLong("d")
            // Security S2 (v0.1.55): refuse advertisements that claim to
            // ship more bytes than we'll ever willingly allocate. Both
            // transferSize (wire bytes incl. compression) AND dataSize
            // (post-decompress bytes) must be under the cap — the latter
            // is the actual buffer-allocation driver in assemble(), and
            // the former blocks the chunk-receive loop from running for
            // an unwinnable transfer.
            check(transferSize in 0..Resource.MAX_RESOURCE_BYTES) {
                "resource ad transferSize=${transferSize}B exceeds MAX_RESOURCE_BYTES=${Resource.MAX_RESOURCE_BYTES}B"
            }
            check(dataSize in 0..Resource.MAX_RESOURCE_BYTES) {
                "resource ad dataSize=${dataSize}B exceeds MAX_RESOURCE_BYTES=${Resource.MAX_RESOURCE_BYTES}B"
            }
            val totalParts = if (split) {
                error("multi-segment resources not yet supported (l > 1)")
            } else {
                // For single-segment responses partsInAd == total_parts when
                // the response fits HASHMAP_MAX_LEN. Otherwise upstream uses
                // REQ/HMU which we don't implement — bail with a clear msg.
                if (partsInAd > Resource.HASHMAP_MAX_LEN) {
                    error("resource too large (${partsInAd} chunks > MVP cap ${Resource.HASHMAP_MAX_LEN}); REQ/HMU not implemented")
                }
                partsInAd
            }

            val requestId = (dict["q"] as? ByteArray)?.takeIf { it.size == 16 }

            return ResourceAdvertisement(
                linkId = linkId,
                transferSize = transferSize,
                dataSize = dataSize,
                partsInAd = partsInAd,
                totalParts = totalParts,
                hash = reqBytes("h"),
                randomHash = reqBytes("r"),
                originalHash = reqBytes("o"),
                segmentIndex = reqInt("i"),
                totalSegments = reqInt("l"),
                requestId = requestId,
                encrypted = encrypted,
                compressed = compressed,
                split = split,
                isRequest = isRequest,
                isResponse = isResponse,
                hasMetadata = hasMetadata,
                hashmap = hashmap,
            )
        }
    }
}
