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
 * HMU + multi-segment scope:
 *  - One [Resource] models a single transfer segment. The hashmap may be
 *    larger than one ADV can carry (`n > HASHMAP_MAX_LEN`): the ADV's `m`
 *    fragment seeds the first window and [hashmapUpdate] applies later
 *    RESOURCE_HMU windows (§10.7).
 *  - Multi-segment transfers (`l > 1`, §10.11) are sequenced one [Resource]
 *    per segment by [LinkResourceReceiver], which concatenates the segment
 *    payloads in order.
 *  - The receiver requests parts in bounded batches ([nextRequestBatch]); it
 *    does not implement retransmit — a permanently lost part stalls the
 *    transfer until the sender's advertisement watchdog cancels it.
 */
class Resource internal constructor(
    val advertisement: ResourceAdvertisement,
    private val link: TokenCrypto,
    private val linkKey: ByteArray,
) {
    /** Receive slot per chunk index. null = not yet seen. */
    private val parts: Array<ByteArray?> = arrayOfNulls(advertisement.totalParts)
    private var partsReceived = 0

    /** Running total of accepted chunk-plaintext bytes. Capped against
     *  the advertised (and parse-capped) transferSize so [assemble]'s
     *  reassembly buffer can never be driven past it by a hostile peer
     *  sending oversized chunks. */
    private var receivedBytes = 0L

    /**
     * Sparse map-hash table, one 4-byte entry per part. Indices
     * `[0, hashmapHeight)` are known; the rest fill in as RESOURCE_HMU
     * packets arrive (§10.7). The advertisement's `m` fragment seeds the
     * first window.
     */
    private val hashmap: Array<ByteArray?> = arrayOfNulls(advertisement.totalParts)
    private var hashmapHeight = 0

    /** Highest index h such that every part in `[0, h)` has been received. */
    private var consecutiveHeight = 0

    /** True once a RESOURCE_REQ has been issued for this part index. */
    private val requested = BooleanArray(advertisement.totalParts)

    /** Part requests issued but not yet filled (`requested[i] && parts[i]==null`).
     *  Drives [needsRequestRefill] so the request window slides forward in
     *  bursts rather than one map_hash per inbound chunk. */
    private var outstanding = 0

    /**
     * True once a part-less exhausted RESOURCE_REQ has been emitted for the
     * current hashmap boundary, and not yet answered. Cleared by
     * [hashmapUpdate] when the RESOURCE_HMU that answers it extends
     * [hashmapHeight]. Guards [nextRequestBatch] against re-emitting the
     * HMU pull on every call while we wait for the continuation window.
     */
    private var awaitingHmu = false

    /**
     * Per-segment hashmap window length — how many map_hashes the sender
     * packs per ADV/HMU window. When the advertised part count exceeds the
     * ADV fragment (`partsInAd < totalParts`) the fragment length IS the
     * sender's `HASHMAP_MAX_LEN`, so we adopt it rather than hardcoding a
     * value that might disagree with the peer. When the whole hashmap fit
     * in the ADV there is no HMU and this is unused. Coerced to ≥1 so the
     * `segment * segLen` arithmetic in [hashmapUpdate] never divides by 0.
     */
    private val segLen: Int =
        if (advertisement.partsInAd in 1 until advertisement.totalParts) advertisement.partsInAd
        else advertisement.totalParts.coerceAtLeast(1)

    init {
        val frag = advertisement.hashmap
        for (i in frag.indices) hashmap[i] = frag[i]
        hashmapHeight = frag.size
    }

    val isComplete: Boolean get() = partsReceived == advertisement.totalParts
    val linkId: ByteArray get() = advertisement.linkId

    /** True once every map_hash is known — no further RESOURCE_HMU needed. */
    val hashmapComplete: Boolean get() = hashmapHeight >= advertisement.totalParts

    /**
     * Populated by [assemble] when [ResourceAdvertisement.hasMetadata]
     * is true (§10.2 step 1). Holds the msgpack-decoded metadata map
     * that preceded the actual payload. The canonical key for
     * NomadNet `/file/` responses (upstream `Node.py:128-141`) is
     *
     *   `metadata["name"] = filename_bytes`
     *
     * but the dict is open-ended; future spec or app-defined fields
     * (mime type, size, etc.) ride alongside. Null when the flag is
     * not set or `assemble` hasn't run yet.
     */
    var parsedMetadata: Map<Any?, Any?>? = null
        private set

    /**
     * Feed a CONTEXT_RESOURCE chunk's plaintext into the receive buffer.
     * The chunk body is matched against the hashmap to figure out which
     * slot it fills — the wire has no per-chunk index or sequence number.
     *
     * Returns true if the chunk was new (caller should expect more), false
     * if it was a duplicate or didn't match any known slot.
     */
    suspend fun receivePart(chunkPlaintext: ByteArray, crypto: CryptoProvider): Boolean {
        val hash = chunkHash(chunkPlaintext, advertisement.randomHash, crypto)
        // §10.6 interop trap: 4-byte map_hashes are only unique within
        // COLLISION_GUARD_SIZE of a sliding-window position. Searching the
        // whole hashmap risks mis-placing a part on a distant collision, so
        // bound the search to a guard window above the consecutive height,
        // and never past hashmapHeight (the rest isn't known yet).
        val end = minOf(hashmapHeight, consecutiveHeight + COLLISION_GUARD_SIZE)
        for (i in consecutiveHeight until end) {
            if (parts[i] != null) continue
            if (hash.contentEquals(hashmap[i])) {
                // The chunks are slices of a transferSize-byte blob, so the
                // sum of accepted chunk bytes can never legitimately exceed
                // the advertised (parse-capped) transferSize. Refuse a chunk
                // that would — keeps assemble()'s buffer bounded against a
                // peer streaming oversized chunks.
                if (receivedBytes + chunkPlaintext.size > advertisement.transferSize) {
                    return false
                }
                parts[i] = chunkPlaintext
                receivedBytes += chunkPlaintext.size
                partsReceived++
                if (requested[i]) outstanding--
                while (consecutiveHeight < parts.size && parts[consecutiveHeight] != null) {
                    consecutiveHeight++
                }
                return true
            }
        }
        return false
    }

    /**
     * Diagnostic for a chunk that [receivePart] just rejected. Walks the
     * ENTIRE known hashmap (not just the search window) and returns a
     * one-line classification of why the chunk didn't match. Used by the
     * link-layer receiver to log actionable detail instead of the generic
     * "chunk did not match" — distinguishing wire corruption (no slot
     * anywhere) from duplicate (slot already filled) from out-of-window
     * (slot would match but is below `consecutiveHeight`).
     */
    suspend fun classifyFailedChunk(chunkPlaintext: ByteArray, crypto: CryptoProvider): String {
        val hash = chunkHash(chunkPlaintext, advertisement.randomHash, crypto)
        val hashHex = hash.toHexLower()
        var matchedSlot = -1
        for (i in 0 until hashmapHeight) {
            if (hash.contentEquals(hashmap[i])) { matchedSlot = i; break }
        }
        val sizeInfo = "size=${chunkPlaintext.size}B"
        val stateInfo = "consecutive=$consecutiveHeight/$hashmapHeight totalParts=${advertisement.totalParts} " +
            "received=$partsReceived bytes=$receivedBytes/${advertisement.transferSize}"
        if (matchedSlot < 0) {
            return "WIRE-CORRUPT (no slot anywhere matches hash=$hashHex) — $sizeInfo $stateInfo"
        }
        val filled = parts[matchedSlot] != null
        val inWindow = matchedSlot >= consecutiveHeight &&
            matchedSlot < minOf(hashmapHeight, consecutiveHeight + COLLISION_GUARD_SIZE)
        return when {
            filled -> "DUPLICATE (slot $matchedSlot already filled, hash=$hashHex) — $sizeInfo $stateInfo"
            !inWindow -> "OUT-OF-WINDOW (slot $matchedSlot below consecutive=$consecutiveHeight, hash=$hashHex) — $sizeInfo $stateInfo"
            else -> "UNKNOWN (slot $matchedSlot in window, parts[$matchedSlot] null, hash=$hashHex — should have matched!) — $sizeInfo $stateInfo"
        }
    }

    /** Hex of the first/last few bytes of a chunk for forensic logging. */
    private fun ByteArray.toHexLower(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /**
     * Apply a RESOURCE_HMU continuation (§10.7). [segment] is the hashmap
     * window index (`part_index // HASHMAP_MAX_LEN` on the sender) and
     * [hashmapBytes] is `hashes × 4` raw map_hashes for that window.
     * Throws [ResourceError] if the window doesn't continue contiguously
     * from the current height — a sequencing error per §10.7.
     */
    fun hashmapUpdate(segment: Int, hashmapBytes: ByteArray) {
        if (hashmapBytes.size % MAPHASH_LEN != 0) {
            throw ResourceError("HMU hashmap length ${hashmapBytes.size} not 4-aligned")
        }
        val base = segment.toLong() * segLen
        if (base != hashmapHeight.toLong()) {
            throw ResourceError(
                "HMU segment $segment (base $base) does not continue height $hashmapHeight"
            )
        }
        val hashes = hashmapBytes.size / MAPHASH_LEN
        // SECURITY (audit): a single HMU window can never legitimately
        // carry more map_hashes than the whole segment has parts.
        if (hashes > advertisement.totalParts) {
            throw ResourceError(
                "HMU window of $hashes hashes exceeds total part count ${advertisement.totalParts}",
            )
        }
        for (i in 0 until hashes) {
            val idx = base.toInt() + i
            if (idx >= hashmap.size) break
            if (hashmap[idx] == null) hashmapHeight++
            hashmap[idx] = hashmapBytes.copyOfRange(i * MAPHASH_LEN, (i + 1) * MAPHASH_LEN)
        }
        // This window answers the pending HMU pull (§10.7) — clear the
        // guard so nextRequestBatch can request the freshly-revealed parts
        // and, when they are all in flight, pull the window after this one.
        awaitingHmu = false
    }

    /**
     * Build the next RESOURCE_REQ (§10.5) for this resource, or null when
     * nothing can be requested right now (transfer complete, or every
     * known part is in flight and we are waiting on chunks / an HMU).
     *
     * A batch is one of two distinct shapes — **never both**:
     *
     *  - **Part request** — up to [maxHashes] not-yet-requested map_hashes
     *    from the known hashmap, `exhausted = false`.
     *  - **HMU pull** — an empty part list with `exhausted = true`, emitted
     *    once the known hashmap has no unrequested parts left and more
     *    hashmap remains.
     *
     * Keeping these separate is load-bearing for interop, not a style
     * choice: upstream RNS and the fwdsvc Go port answer an exhausted
     * RESOURCE_REQ with ONLY a RESOURCE_HMU and **discard the request's
     * map_hash list entirely** (`resource_sender.go`:
     * `if req.Exhausted { serveHmu(req); continue }`, SPEC §10.7). A batch
     * that bundled parts with `exhausted = true` would have every one of
     * those parts silently dropped — which stalled every >74-part inbound
     * image relayed through the Fwd service (2026-05-19). Mobile↔mobile
     * self-roundtrip masked it: our own sender leniently honoured the
     * bundled parts, so only a spec-strict peer exposed the divergence.
     */
    fun nextRequestBatch(maxHashes: Int = REQ_MAX_HASHES): RequestBatch? {
        // Sliding request window (§10.6): never request a part more than
        // REQUEST_WINDOW indices ahead of the consecutive-received height.
        // A sender only serves a requested part within COLLISION_GUARD_SIZE
        // of that height — request too far ahead and those parts are
        // silently skipped, never delivered, and (flagged `requested`)
        // never re-asked, stalling the transfer. The window slides as
        // consecutiveHeight advances; LinkResourceReceiver.handleChunk
        // pumps a refill once enough parts are in (see [needsRequestRefill]).
        val windowEnd = consecutiveHeight + REQUEST_WINDOW
        val collectLimit = minOf(hashmapHeight, windowEnd)
        val out = ArrayList<ByteArray>(maxHashes)
        var i = 0
        while (i < collectLimit && out.size < maxHashes) {
            if (parts[i] == null && !requested[i]) {
                requested[i] = true
                outstanding++
                out.add(hashmap[i]!!)
            }
            i++
        }
        // A batch carrying parts is always non-exhausted (see kdoc).
        if (out.isNotEmpty()) {
            return RequestBatch(out, exhausted = false, lastMapHash = null)
        }
        // No part is requestable right now. Pull the next hashmap window
        // with a part-less exhausted REQ only when the window has reached
        // the end of the *known* hashmap (windowEnd >= hashmapHeight) and
        // more hashmap remains — exactly one such REQ, until the answering
        // HMU extends hashmapHeight and clears [awaitingHmu]. When the
        // window (not the hashmap) is the limiter, just wait for inbound
        // parts to advance consecutiveHeight and slide the window.
        if (windowEnd >= hashmapHeight &&
            hashmapHeight < advertisement.totalParts &&
            !awaitingHmu
        ) {
            awaitingHmu = true
            return RequestBatch(emptyList(), exhausted = true, lastMapHash = hashmap[hashmapHeight - 1])
        }
        return null
    }

    /**
     * True when few enough part requests are still outstanding that the
     * caller should pump another [nextRequestBatch] burst. [LinkResourceReceiver]
     * checks this on every accepted chunk so the request window slides
     * forward in half-window bursts — keeping the pipeline full without
     * emitting a fresh RESOURCE_REQ for every single inbound chunk.
     */
    val needsRequestRefill: Boolean get() = outstanding * 2 < REQUEST_WINDOW

    /**
     * Monotonic transfer-progress signal: rises whenever a part is
     * accepted or the hashmap is extended. The retransmit watchdog
     * samples this to tell a stalled transfer from a slow one. Read-only.
     */
    val progressMark: Int get() = partsReceived + hashmapHeight

    /**
     * Build a REQ to re-drive a stalled transfer (loss recovery). RNS is a
     * datagram mesh; a dropped RESOURCE_REQ, part, or HMU otherwise stalls
     * the transfer forever — [nextRequestBatch] will not re-offer a part
     * already flagged `requested`. Unlike it, this re-asks for every
     * still-missing part inside the current window regardless of that flag
     * (the sender re-serves duplicates happily; [receivePart] drops them).
     * When no in-window part is missing the stall is a lost HMU pull, so
     * it re-sends the part-less exhausted REQ. Returns null when nothing is
     * outstanding. Read-only — never mutates resource state, so the
     * watchdog may call it off the packet-handling path.
     */
    fun retransmitBatch(): RequestBatch? {
        val limit = minOf(hashmapHeight, consecutiveHeight + REQUEST_WINDOW)
        val out = ArrayList<ByteArray>(REQ_MAX_HASHES)
        var i = consecutiveHeight
        while (i < limit && out.size < REQ_MAX_HASHES) {
            if (parts[i] == null) out.add(hashmap[i]!!)
            i++
        }
        if (out.isNotEmpty()) {
            return RequestBatch(out, exhausted = false, lastMapHash = null)
        }
        if (hashmapHeight < advertisement.totalParts) {
            return RequestBatch(emptyList(), exhausted = true, lastMapHash = hashmap[hashmapHeight - 1])
        }
        return null
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
        val totalSize = parts.sumOf {
            requireNotNull(it) { "internal: null part after isComplete" }.size.toLong()
        }
        // Defense in depth — receivePart already caps the running total,
        // but never allocate a buffer larger than the advertised transfer.
        if (totalSize > advertisement.transferSize) {
            throw ResourceError(
                "reassembled size $totalSize exceeds advertised transferSize " +
                    "${advertisement.transferSize}",
            )
        }
        val concatenated = ByteArray(totalSize.toInt())
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
        // Verify integrity: sha256(data || randomHash) == adv.h. Per
        // §10.2 step 5 the hash input is the post-decompression body
        // (INCLUDING the metadata prefix, if any) — so we verify
        // BEFORE stripping the metadata. Otherwise a peer could mint
        // a fake metadata prefix without invalidating the integrity
        // check.
        val verifyInput = ByteArray(data.size + advertisement.randomHash.size).also {
            data.copyInto(it, 0)
            advertisement.randomHash.copyInto(it, data.size)
        }
        val computed = crypto.sha256(verifyInput)
        if (!computed.contentEquals(advertisement.hash)) {
            throw ResourceError("integrity hash mismatch")
        }

        // §10.2 step 1 metadata prefix strip. When the ADV's
        // has_metadata flag (bit 5 of f) is set, the body starts with
        //   length(3, big-endian uint24) || msgpack(metadata_dict)
        // followed by the actual payload. Used by upstream NomadNet
        // `/file/` responses (Node.py:128-141) to deliver the file's
        // filename + size to the client. v1.1.24 adds the receive-
        // side strip; pre-fix `assemble` returned the bytes verbatim
        // including the prefix, which made the file response
        // indistinguishable from a malformed page response.
        if (!advertisement.hasMetadata) {
            return data
        }
        if (data.size < METADATA_LENGTH_PREFIX_SIZE) {
            throw ResourceError(
                "has_metadata but body too small for length prefix: ${data.size}B"
            )
        }
        val declaredLen =
            ((data[0].toInt() and 0xFF) shl 16) or
            ((data[1].toInt() and 0xFF) shl 8) or
             (data[2].toInt() and 0xFF)
        val prefixEnd = METADATA_LENGTH_PREFIX_SIZE + declaredLen
        if (declaredLen < 0 || prefixEnd > data.size) {
            throw ResourceError(
                "has_metadata declared length $declaredLen exceeds body (${data.size}B)"
            )
        }
        val metadataBytes = data.copyOfRange(METADATA_LENGTH_PREFIX_SIZE, prefixEnd)
        parsedMetadata = runCatching {
            val decoded = MessagePack.decode(metadataBytes)
            decoded as? Map<*, *>
        }.getOrNull()?.let { rawMap ->
            // msgpack maps decode as Map<Any?, Any?>; coerce the
            // public-facing type for caller convenience.
            @Suppress("UNCHECKED_CAST")
            rawMap as Map<Any?, Any?>
        } ?: throw ResourceError("metadata msgpack decode failed or not a map")

        return data.copyOfRange(prefixEnd, data.size)
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
     * Sender-side bundle for ONE transfer segment, produced by
     * [buildOutbound] (which returns one per segment — a single-segment
     * transfer is a list of one). All fields are ready-to-wire:
     *
     *   - [advBodyCipher] goes into a CTX_RESOURCE_ADV DATA packet body.
     *   - Each [chunks] entry goes into a CTX_RESOURCE DATA packet body.
     *
     * [advertisement] is exposed for inspection / sanity assertions and
     * mirrors what the receiver parses out of [advBodyCipher]; its
     * `hashmap` field is only the first ADV window (§10.4).
     *
     * [fullHashmap] is every 4-byte map_hash for the segment — the sender
     * needs the whole map (not just the ADV window) to look up requested
     * chunks and to build RESOURCE_HMU continuation windows (§10.7).
     */
    data class OutboundResource(
        val advertisement: ResourceAdvertisement,
        val advBodyCipher: ByteArray,
        val chunks: List<ByteArray>,
        val fullHashmap: List<ByteArray>,
    )

    companion object {
        const val RANDOM_HASH_SIZE = 4
        const val MAPHASH_LEN = 4
        /** §10.2 step 1 — the metadata prefix length is a 3-byte
         *  big-endian uint24. Caps the metadata at 16 MiB which is
         *  more than enough for `{"name": filename}` (typical ~30 B)
         *  but generous for future metadata extensions. */
        const val METADATA_LENGTH_PREFIX_SIZE = 3
        /** §10.4 — `⌊(RNS.Link.MDU − 134) / 4⌋`. With the default
         *  MTU=500, `Link.MDU = ⌊(500−1−19−48)/16⌋·16 − 1 = 431`, so this
         *  is `⌊(431 − 134) / 4⌋ = 74` (verified against upstream
         *  `RNS/Resource.py` + `RNS/Link.py` and the fwdsvc Go port).
         *  This MUST equal upstream RNS's value: an upstream receiver
         *  places RESOURCE_HMU windows at `segment_index · HASHMAP_MAX_LEN`
         *  using ITS constant, so the sender's window length has to match
         *  bytewise. (The receive side does NOT hardcode it — it adopts
         *  the sender's window length from the ADV fragment; see
         *  [Resource]'s `segLen`.) */
        const val HASHMAP_MAX_LEN = 74

        /** §10.11 — payloads larger than this are split into multiple
         *  segments (`1 MiB − 1`, upstream `Resource.MAX_EFFICIENT_SIZE`). */
        const val MAX_EFFICIENT_SIZE = 1 * 1024 * 1024 - 1

        /** Hard cap on a segment's advertised part count `n`. `parts` and
         *  `hashmap` are pre-sized from `n`, so an unbounded `n` is an
         *  allocation-DoS vector independent of the transferSize cap.
         *  16384 parts comfortably covers a full ~1 MiB segment (~2260
         *  parts at SDU 464) with headroom. */
        const val MAX_RESOURCE_PARTS = 16384

        /** §10.11 — cap on total segments `l`. Bounds the multi-segment
         *  reassembly accumulator; 8 segments ≈ 8 MiB at MAX_EFFICIENT_SIZE. */
        const val MAX_RESOURCE_SEGMENTS = 8

        /** Cap on the running total of a multi-segment transfer's
         *  reassembled bytes — defends the [LinkResourceReceiver]
         *  accumulator the way [MAX_RESOURCE_BYTES] defends one segment. */
        const val MAX_MULTISEGMENT_BYTES = 8L * 1024 * 1024

        /** §10.6 / §10.10 — receiver-side part-match search window. Map
         *  hashes are only unique within `2·WINDOW_MAX + HASHMAP_MAX_LEN`
         *  of a sliding-window position; bounding the [Resource.receivePart]
         *  search to this guard avoids mis-placing a part on a distant
         *  4-byte collision. `2·75 + 74 = 224` (upstream value). */
        const val COLLISION_GUARD_SIZE = 224

        /** Receiver-side request window: the most parts the receiver keeps
         *  requested ahead of its consecutive-received height. A sender
         *  only serves a requested part within [COLLISION_GUARD_SIZE] of
         *  that height (§10.6) — request further ahead and the sender
         *  silently skips those parts, the transfer stalls, and (the slots
         *  being flagged `requested`) they are never re-asked. Keeping the
         *  window well under the guard, with margin for the sender's
         *  lagging view of our height, guarantees every requested part is
         *  servable. Upstream RNS grows a window 4→48 dynamically; we use a
         *  fixed 96 — larger than one HMU hashmap segment (74) for
         *  throughput, comfortably under the 224 guard. */
        const val REQUEST_WINDOW = 96

        /** Max map_hashes the receiver packs into one RESOURCE_REQ so the
         *  request body stays inside a single link packet (§10.5). */
        const val REQ_MAX_HASHES = 80
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
        /**
         * Per SPEC §10.2 step 6 + upstream `RNS/Resource.py:338`:
         *   SDU = link.mtu - HEADER_MAXSIZE - IFAC_MIN_SIZE
         *       = 500 - 35 - 1
         *       = 464
         *
         * Pre-v1.1.20 used 433 which was a guess based on conservative
         * payload sizing. The mismatch broke Sideband interop: upstream
         * RNS receiver allocates `parts = [None] * ceil(t / 464)` slots,
         * but our hashmap_raw carried `ceil(t / 433)` entries — MORE
         * than the receiver's allocated array. Upstream
         * `hashmap_update` would IndexError on the extra entries, the
         * `accept()` try/except silently dropped the Resource, and the
         * receiver never sent a CTX_RESOURCE_REQ in response.
         *
         * Mobile↔mobile worked because OUR receiver derives total_parts
         * from `n` (our advertised count) rather than `t / sdu`, so our
         * own sender's chunk count matched our own receiver's slot
         * allocation. The bug only surfaced cross-implementation.
         *
         * Discovered 2026-05-13 by diff'ing upstream Resource.py:338
         * after the q-key fix (v1.1.19) didn't move the needle on
         * mobile→Sideband image sends.
         */
        const val DEFAULT_SDU = 464

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
         * Build the outbound wire bytes for a Resource carrying [plain]
         * over the active link. Returns one [OutboundResource] per
         * transfer segment (§10.11) — a payload that fits one segment is
         * a list of one. Mirror of upstream `RNS.Resource.advertise()` /
         * the wire algorithm in §10.2 + §10.4 + §10.11:
         *
         *  1. Split [plain] into ≤[MAX_EFFICIENT_SIZE] segment bodies.
         *  2. Per segment: prepend a 4-byte random_hash and Token-encrypt
         *     the whole (`random_hash || body`) blob — the OUTER encrypt;
         *     chunks are slices of that ciphertext, not encrypted per part.
         *  3. Split the outer ciphertext into ≤[sdu]-byte chunks.
         *  4. Compute the per-chunk hashmap `SHA-256(chunk || random_hash)[:4]`.
         *  5. Compute integrity `SHA-256(body || random_hash)`.
         *  6. Pack the advertisement msgpack dict. The ADV's `m` carries
         *     only the first [HASHMAP_MAX_LEN] map_hashes (§10.4); the rest
         *     ride RESOURCE_HMU. `o` carries segment 1's integrity hash on
         *     every segment so the receiver can correlate them.
         *
         * Payloads beyond [HASHMAP_MAX_LEN] chunks are no longer rejected —
         * the receiver pulls the remaining hashmap via RESOURCE_HMU. The
         * total is bounded by [MAX_MULTISEGMENT_BYTES].
         */
        suspend fun buildOutbound(
            plain: ByteArray,
            link: TokenCrypto,
            linkKey: ByteArray,
            linkId: ByteArray,
            crypto: CryptoProvider,
            requestId: ByteArray? = null,
            sdu: Int = DEFAULT_SDU,
        ): List<OutboundResource> {
            require(plain.size.toLong() <= MAX_MULTISEGMENT_BYTES) {
                "payload ${plain.size}B exceeds MAX_MULTISEGMENT_BYTES=$MAX_MULTISEGMENT_BYTES"
            }
            if (requestId != null) {
                require(requestId.size == 16) { "requestId must be 16 bytes, got ${requestId.size}" }
            }

            // §10.11 step 1: split into ≤MAX_EFFICIENT_SIZE segment bodies.
            val segmentBodies = mutableListOf<ByteArray>()
            var p = 0
            while (p < plain.size) {
                val e = (p + MAX_EFFICIENT_SIZE).coerceAtMost(plain.size)
                segmentBodies.add(plain.copyOfRange(p, e))
                p = e
            }
            if (segmentBodies.isEmpty()) segmentBodies.add(ByteArray(0))
            val totalSegments = segmentBodies.size
            check(totalSegments <= MAX_RESOURCE_SEGMENTS) {
                "payload needs $totalSegments segments > MAX_RESOURCE_SEGMENTS=$MAX_RESOURCE_SEGMENTS"
            }

            // Per-segment core wire bytes. Built for every segment up front
            // so the `o` (original hash) field can carry segment 1's
            // integrity hash on every ADV (§10.11).
            class SegCore(
                val randomHash: ByteArray,
                val chunks: List<ByteArray>,
                val fullHashmap: List<ByteArray>,
                val integrityHash: ByteArray,
                val transferSize: Long,
                val dataSize: Long,
            )
            val cores = segmentBodies.map { body ->
                val randomHash = crypto.randomBytes(RANDOM_HASH_SIZE)
                // §10.2 step 1-2: prepend random_hash, outer-encrypt the blob.
                // The receiver strips these 4 bytes after the outer-decrypt
                // without comparing them to anything (§10.8) — reusing
                // `randomHash` as the prefix is wire-correct.
                val outerPlain = ByteArray(randomHash.size + body.size).also {
                    randomHash.copyInto(it, 0)
                    body.copyInto(it, randomHash.size)
                }
                val outerCipher = link.encryptWithDerivedKey(outerPlain, linkKey)
                // §10.2 step 3: slice into SDU-sized chunks.
                val chunks = mutableListOf<ByteArray>()
                var offset = 0
                while (offset < outerCipher.size) {
                    val end = (offset + sdu).coerceAtMost(outerCipher.size)
                    chunks.add(outerCipher.copyOfRange(offset, end))
                    offset = end
                }
                if (chunks.isEmpty()) chunks.add(ByteArray(0))
                check(chunks.size <= MAX_RESOURCE_PARTS) {
                    "segment too large (${chunks.size} chunks > MAX_RESOURCE_PARTS=$MAX_RESOURCE_PARTS)"
                }
                // §10.2 step 4: hashmap[i] = SHA-256(chunk_i || random_hash)[:4]
                val fullHashmap = chunks.map { chunkHash(it, randomHash, crypto) }
                // §10.2 step 5: integrity over the uncompressed segment body.
                val integrityInput = ByteArray(body.size + randomHash.size).also {
                    body.copyInto(it, 0)
                    randomHash.copyInto(it, body.size)
                }
                SegCore(
                    randomHash = randomHash,
                    chunks = chunks,
                    fullHashmap = fullHashmap,
                    integrityHash = crypto.sha256(integrityInput),
                    transferSize = outerCipher.size.toLong(),
                    dataSize = body.size.toLong(),
                )
            }
            // §10.11 — `o` is the first segment's integrity hash everywhere.
            val originalHash = cores[0].integrityHash

            return cores.mapIndexed { idx, core ->
                // §10.4: the ADV's `m` carries only the first HASHMAP_MAX_LEN
                // map_hashes; the rest are pulled via RESOURCE_HMU (§10.7).
                val advWindow = core.fullHashmap.take(HASHMAP_MAX_LEN)
                val hashmapBytes = ByteArray(advWindow.sumOf { it.size }).also {
                    var off = 0
                    for (h in advWindow) {
                        h.copyInto(it, off)
                        off += h.size
                    }
                }
                // Flags: 0x01 encrypted, 0x04 split (multi-segment),
                // 0x10 isResponse (set when this Resource answers a REQUEST).
                val flags = 0x01 or
                    (if (totalSegments > 1) 0x04 else 0) or
                    (if (requestId != null) 0x10 else 0)

                // All 11 keys on every ADV — upstream RNS Resource.py:1373
                // unpacks `q` (and the rest) with no KeyError guard, so a
                // missing key breaks mobile→Sideband. `q` encodes null as
                // msgpack nil (0xC0), matching upstream's `self.q = None`.
                val advDict = LinkedHashMap<Any?, Any?>().apply {
                    put("f", flags)
                    put("m", hashmapBytes)
                    put("n", core.chunks.size)
                    put("t", core.transferSize)
                    put("d", core.dataSize)
                    put("h", core.integrityHash)
                    put("r", core.randomHash)
                    put("o", originalHash)
                    put("i", idx + 1)            // 1-based segment index
                    put("l", totalSegments)
                    put("q", requestId)
                }
                val advBodyCipher =
                    link.encryptWithDerivedKey(MessagePack.encode(advDict), linkKey)

                val advertisement = ResourceAdvertisement(
                    linkId        = linkId,
                    transferSize  = core.transferSize,
                    dataSize      = core.dataSize,
                    partsInAd     = advWindow.size,
                    totalParts    = core.chunks.size,
                    hash          = core.integrityHash,
                    randomHash    = core.randomHash,
                    originalHash  = originalHash,
                    segmentIndex  = idx + 1,
                    totalSegments = totalSegments,
                    requestId     = requestId,
                    encrypted     = true,
                    compressed    = false,
                    split         = totalSegments > 1,
                    isRequest     = false,
                    isResponse    = requestId != null,
                    hasMetadata   = false,
                    hashmap       = advWindow,
                )
                OutboundResource(advertisement, advBodyCipher, core.chunks, core.fullHashmap)
            }
        }
    }
}

class ResourceError(message: String) : RuntimeException(message)

/**
 * One windowed RESOURCE_REQ payload (§10.5) the receiver should send,
 * produced by [Resource.nextRequestBatch].
 *
 *  - [mapHashes]   — the 4-byte map_hashes whose parts are being requested.
 *                    Always empty when [exhausted] is set: a sender
 *                    discards the part list of an exhausted REQ (§10.7).
 *  - [exhausted]   — true for a part-less HMU pull: the known hashmap has
 *                    no unrequested parts left and more hashmap remains.
 *                    Sets the `0xFF` flag so the sender answers with a
 *                    RESOURCE_HMU. Never set on a batch carrying parts.
 *  - [lastMapHash] — non-null iff [exhausted]; the last map_hash the
 *                    receiver knows, which the sender uses to locate the
 *                    next hashmap window.
 */
class RequestBatch(
    val mapHashes: List<ByteArray>,
    val exhausted: Boolean,
    val lastMapHash: ByteArray?,
)

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

            val totalParts = reqInt("n")
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
            // §10.4 `n` is the part count for THIS segment. The hashmap `m`
            // fragment may be shorter — the rest arrives via RESOURCE_HMU
            // (§10.7). Cap `n` independently of transferSize: a hostile peer
            // could advertise a small transfer but a huge `n`, and the
            // `parts` / `hashmap` arrays are pre-sized from `n`.
            check(totalParts in 0..Resource.MAX_RESOURCE_PARTS) {
                "resource ad part count n=$totalParts outside 0..${Resource.MAX_RESOURCE_PARTS}"
            }
            val partsInAd = hashmap.size
            check(partsInAd <= totalParts) {
                "resource ad hashmap fragment ($partsInAd entries) exceeds part count ($totalParts)"
            }
            // §10.11 multi-segment. `i` / `l` are authoritative; cap `l` so
            // the multi-segment accumulator can't be driven unboundedly.
            val segmentIndex = reqInt("i")
            val totalSegments = reqInt("l")
            check(totalSegments in 1..Resource.MAX_RESOURCE_SEGMENTS) {
                "resource ad total segments l=$totalSegments outside 1..${Resource.MAX_RESOURCE_SEGMENTS}"
            }
            check(segmentIndex in 1..totalSegments) {
                "resource ad segment index i=$segmentIndex outside 1..$totalSegments"
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
                segmentIndex = segmentIndex,
                totalSegments = totalSegments,
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
