package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_HMU
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_PRF
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_REQ
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.resource.RequestBatch
import io.github.thatsfguy.reticulum.resource.Resource
import io.github.thatsfguy.reticulum.resource.ResourceAdvertisement

/**
 * Shared inbound-Resource state machine for both [LinkSession] (initiator
 * side) and [ResponderLinkSession] (peer-initiated link). Handles the
 * spec §10 receive flow:
 *
 *   CTX_RESOURCE_ADV  → decrypt body, parse advertisement, issue the first
 *                       windowed CTX_RESOURCE_REQ (§10.5).
 *   CTX_RESOURCE      → match the slice into the hashmap; on the last part
 *                       of a segment, reassemble + send CTX_RESOURCE_PRF.
 *   CTX_RESOURCE_HMU  → apply a hashmap continuation window (§10.7) and
 *                       request the newly-revealed parts.
 *
 * Long resources whose hashmap doesn't fit one ADV (`n > HASHMAP_MAX_LEN`)
 * are driven by RESOURCE_HMU: each exhausted RESOURCE_REQ pulls the next
 * hashmap window. Multi-segment transfers (`l > 1`, §10.11) are sequenced
 * one [Resource] per segment here — each segment's payload is concatenated
 * in order and delivered once the final segment's proof has been sent.
 *
 * Why this exists separately: pre-2026-05-10 the receive-side state +
 * dispatch lived inline inside [LinkSession] and the responder side had
 * an `else -> ignore` catch-all (the LXMF-receiver-MVP comment). When an
 * LXMF server replied to a request by opening a NEW outbound link to us
 * (the standard fwdsvc / Sideband pattern), the reply landed on a
 * peer-initiated link as a Resource — and our responder dropped every
 * RESOURCE_ADV silently. This class lets both sides delegate to the
 * same code path; consumers differ only in [onAssembled].
 *
 * Counters ([advParts] / [advBytes] / [chunksReceived]) stay readable
 * after construction so [LinkSession.diagnosticSummary] can include
 * resource-progress info in its timeout report.
 */
internal class LinkResourceReceiver(
    private val link: Link,
    private val tokenCrypto: TokenCrypto,
    private val crypto: CryptoProvider,
    private val sender: suspend (ByteArray) -> Unit,
    private val logger: (String) -> Unit,
    /** Invoked with the fully-reassembled plaintext after PRF emit. For a
     *  multi-segment transfer this is the concatenation of every segment,
     *  fired once after the final segment's proof.
     *  `metadata` is non-null when the (first segment's) ADV `has_metadata`
     *  flag was set and the §10.2 step 1 prefix decoded successfully.
     *  `requestId` is the ADV's `q` field (16-byte truncated hash
     *  identifying which outbound request this Resource answers); null for
     *  non-response Resources. */
    private val onAssembled: suspend (plain: ByteArray, metadata: Map<Any?, Any?>?, requestId: ByteArray?) -> Unit,
    /** Hook for the initiator-side path that needs to release a pending
     *  request_id deferred when ADV decrypt/parse or assembly fails;
     *  responder side leaves this as the default no-op. */
    private val onAdvParseFailure: suspend () -> Unit = {},
) {
    /** Active inbound resource segment — set on RESOURCE_ADV, cleared when
     *  the segment's last chunk arrives. */
    private var pending: Resource? = null

    /** Cross-segment accumulator for a multi-segment transfer (`l > 1`).
     *  Null for single-segment transfers and between transfers. */
    private var multi: MultiSegment? = null

    /** Total parts the most recent advertisement promised. -1 before any ADV. */
    var advParts: Int = -1
        private set

    /** Total advertised transfer size in bytes. -1L before any ADV. */
    var advBytes: Long = -1L
        private set

    /** Chunks accepted into the current (or most recent) resource segment. */
    var chunksReceived: Int = 0
        private set

    /** State for an in-progress multi-segment (§10.11) reassembly. */
    private class MultiSegment(val originalHash: ByteArray, val totalSegments: Int) {
        /** Segment index the next RESOURCE_ADV must carry. */
        var nextExpected: Int = 2
        /** Assembled payloads of completed segments, in segment order. */
        val segments = ArrayList<ByteArray>()
        var totalBytes: Int = 0
        /** Metadata + request_id are taken from the first segment. */
        var metadata: Map<Any?, Any?>? = null
        var requestId: ByteArray? = null
    }

    /** Process a CTX_RESOURCE_ADV packet — decrypt + parse the
     *  advertisement, wire up multi-segment state, and issue the first
     *  windowed RESOURCE_REQ. */
    suspend fun handleAdvertisement(pkt: Packet) {
        val plain = runCatching {
            tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
        }.onFailure { logger("RESOURCE_ADV decrypt failed: ${it.message}") }.getOrNull()
            ?: return
        val adv = runCatching { ResourceAdvertisement.parse(plain, link.linkId!!) }
            .onFailure { logger("RESOURCE_ADV parse failed: ${it.message}") }
            .getOrNull() ?: run {
                onAdvParseFailure()
                return
            }

        // §10.11 multi-segment sequencing: segment 1 starts a fresh
        // accumulator; later segments must continue the same transfer
        // (same original_hash, same total, next expected index).
        if (adv.totalSegments > 1) {
            if (adv.segmentIndex == 1) {
                multi = MultiSegment(adv.originalHash, adv.totalSegments)
            } else {
                val ms = multi
                if (ms == null ||
                    !ms.originalHash.contentEquals(adv.originalHash) ||
                    adv.totalSegments != ms.totalSegments ||
                    adv.segmentIndex != ms.nextExpected
                ) {
                    logger(
                        "RESOURCE_ADV multi-segment out of sequence " +
                            "(i=${adv.segmentIndex}/${adv.totalSegments}) — dropping"
                    )
                    multi = null
                    onAdvParseFailure()
                    return
                }
            }
        } else {
            multi = null
        }

        logger(
            "RESOURCE_ADV t=${adv.transferSize}B parts=${adv.totalParts} (ad=${adv.partsInAd}) " +
                "seg=${adv.segmentIndex}/${adv.totalSegments} compressed=${adv.compressed}"
        )
        advParts = adv.totalParts
        advBytes = adv.transferSize
        chunksReceived = 0
        val res = Resource(
            advertisement = adv,
            link = tokenCrypto,
            linkKey = link.derivedKey!!,
        )
        pending = res
        // A zero-part segment can never produce a chunk to drive finalize();
        // resolve it immediately so the caller isn't left waiting.
        if (res.isComplete) finalize(res) else pumpRequests(res)
    }

    /** Process a CTX_RESOURCE chunk packet. On final-chunk arrival of the
     *  active segment, reassembles + sends CTX_RESOURCE_PRF. */
    suspend fun handleChunk(pkt: Packet) {
        val res = pending ?: run {
            logger("RESOURCE chunk arrived without prior ADV — dropping")
            return
        }
        // §10.2 step 4 + step 6 / §10.12: the full
        //   random_hash || (compressed?) data
        // blob is link-encrypted ONCE, then sliced into parts. Each chunk
        // on the wire is a slice of the already-encrypted whole — NOT
        // individually encrypted. The hashmap match is over the ciphertext
        // slice; the outer decrypt happens once over the full
        // concatenation in Resource.assemble() after all parts are in.
        val accepted = runCatching { res.receivePart(pkt.payload, crypto) }
            .onFailure { logger("receivePart threw: ${it.message}") }
            .getOrDefault(false)
        if (!accepted) {
            logger("RESOURCE chunk did not match any known hashmap slot")
            return
        }
        chunksReceived++
        if (res.isComplete) finalize(res)
    }

    /** Process a CTX_RESOURCE_HMU packet (§10.7) — a hashmap continuation
     *  window for a resource whose full hashmap didn't fit one ADV. Applies
     *  the window, then requests the parts it just revealed. */
    suspend fun handleHmu(pkt: Packet) {
        val res = pending ?: run {
            logger("RESOURCE_HMU arrived without active resource — dropping")
            return
        }
        val plain = runCatching {
            tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
        }.onFailure { logger("RESOURCE_HMU decrypt failed: ${it.message}") }.getOrNull()
            ?: return
        // Body: resource_hash(32) || msgpack([segment_index, hashmap_bytes]).
        if (plain.size <= 32) {
            logger("RESOURCE_HMU body too short (${plain.size}B) — dropping")
            return
        }
        val resourceHash = plain.copyOfRange(0, 32)
        if (!resourceHash.contentEquals(res.advertisement.hash)) {
            logger("RESOURCE_HMU resource hash mismatch — dropping")
            return
        }
        val decoded = runCatching { MessagePack.decode(plain.copyOfRange(32, plain.size)) }
            .onFailure { logger("RESOURCE_HMU msgpack decode failed: ${it.message}") }
            .getOrNull()
        if (decoded !is List<*> || decoded.size < 2) {
            logger("RESOURCE_HMU body is not a [segment, hashmap] pair — dropping")
            return
        }
        val segment = (decoded[0] as? Number)?.toInt() ?: run {
            logger("RESOURCE_HMU segment index is not a number — dropping")
            return
        }
        val hashmapBytes = when (val h = decoded[1]) {
            is ByteArray -> h
            is String -> h.encodeToByteArray()
            else -> {
                logger("RESOURCE_HMU hashmap field is not bytes — dropping")
                return
            }
        }
        val applied = runCatching { res.hashmapUpdate(segment, hashmapBytes) }
            .onFailure { logger("RESOURCE_HMU apply failed: ${it.message}") }
            .isSuccess
        if (!applied) return
        logger("RESOURCE_HMU segment $segment (+${hashmapBytes.size / Resource.MAPHASH_LEN} hashes)")
        pumpRequests(res)
    }

    /** Issue every RESOURCE_REQ batch the resource can currently produce.
     *  Each batch is one windowed request; the loop drains the known
     *  hashmap and stops once [Resource.nextRequestBatch] has nothing
     *  more to ask for (an exhausted batch's HMU reply re-enters here). */
    private suspend fun pumpRequests(res: Resource) {
        while (true) {
            val batch = res.nextRequestBatch() ?: break
            val ok = runCatching { sendResourceReq(res, batch) }
                .onFailure { logger("RESOURCE_REQ send failed: ${it.message}") }
                .isSuccess
            if (!ok) break
        }
    }

    /** §10.5 RESOURCE_REQ body layout:
     *    exhausted_flag(1) || [last_map_hash(4) if exhausted]
     *      || resource_hash(32) || N×map_hash(4)
     *  Token-encrypted with the link's derived key, sent as DATA with
     *  context = CTX_RESOURCE_REQ to the link_id (DEST_LINK per §12.5.2). */
    private suspend fun sendResourceReq(res: Resource, batch: RequestBatch) {
        val resourceHash = res.advertisement.hash
        val mh = Resource.MAPHASH_LEN
        val extra = if (batch.exhausted) mh else 0
        val body = ByteArray(1 + extra + resourceHash.size + batch.mapHashes.size * mh)
        var off = 0
        body[off++] = if (batch.exhausted) 0xFF.toByte() else 0x00  // HASHMAP_IS_(NOT_)EXHAUSTED
        if (batch.exhausted) {
            // last_map_hash — non-null whenever exhausted is set, but guard
            // anyway so a logic slip can't slice past the array bounds.
            (batch.lastMapHash ?: ByteArray(mh)).copyInto(body, off)
            off += mh
        }
        resourceHash.copyInto(body, off)
        off += resourceHash.size
        for (entry in batch.mapHashes) {
            entry.copyInto(body, off)
            off += mh
        }
        val ciphertext = tokenCrypto.encryptWithDerivedKey(body, link.derivedKey!!)
        val packet = buildPacket(
            destType = DEST_LINK,
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_RESOURCE_REQ,
            payload = ciphertext,
        )
        sender(packet)
        logger(
            "→ RESOURCE_REQ (${batch.mapHashes.size} parts" +
                (if (batch.exhausted) ", exhausted — HMU requested" else "") + ")"
        )
    }

    private suspend fun finalize(res: Resource) {
        pending = null
        val adv = res.advertisement
        val segmentPlain = runCatching { res.assemble(crypto) }
            .onFailure { logger("resource assemble failed: ${it.message}") }
            .getOrNull() ?: run {
                multi = null
                onAdvParseFailure()
                return
            }

        // PRF emit (mandatory — without it the sender retransmits). For a
        // multi-segment transfer the proof of segment N is also what tells
        // the sender to advertise segment N+1 (§10.8 / §10.11).
        runCatching {
            val proofPayload = res.buildProofPayload(segmentPlain, crypto)
            val proofPacket = buildPacket(
                headerType = HEADER_1,
                destType = DEST_LINK,
                packetType = PACKET_PROOF,
                destHash = link.linkId!!,
                context = CTX_RESOURCE_PRF,
                payload = proofPayload,
            )
            sender(proofPacket)
            logger(
                "→ RESOURCE_PRF (${segmentPlain.size}B, " +
                    "segment ${adv.segmentIndex}/${adv.totalSegments})"
            )
        }.onFailure { logger("resource PRF send failed: ${it.message}") }

        // Single-segment: deliver straight away.
        if (adv.totalSegments <= 1) {
            onAssembled(segmentPlain, res.parsedMetadata, adv.requestId)
            return
        }

        // §10.11 multi-segment: accumulate this segment's payload. Each
        // segment's assemble() peels its own metadata (only segment 1 has
        // any), so the concatenation is the original payload.
        val ms = multi ?: run {
            logger("multi-segment finalize without accumulator state — dropping")
            return
        }
        ms.segments.add(segmentPlain)
        ms.totalBytes += segmentPlain.size
        if (adv.segmentIndex == 1) {
            ms.metadata = res.parsedMetadata
            ms.requestId = adv.requestId
        }
        if (ms.totalBytes.toLong() > Resource.MAX_MULTISEGMENT_BYTES) {
            logger(
                "multi-segment transfer ${ms.totalBytes}B exceeded " +
                    "${Resource.MAX_MULTISEGMENT_BYTES}B cap — aborting"
            )
            multi = null
            onAdvParseFailure()
            return
        }
        if (adv.segmentIndex >= adv.totalSegments) {
            val whole = ByteArray(ms.totalBytes)
            var off = 0
            for (seg in ms.segments) {
                seg.copyInto(whole, off)
                off += seg.size
            }
            logger("multi-segment complete: ${adv.totalSegments} segments, ${whole.size}B")
            val metadata = ms.metadata
            val requestId = ms.requestId
            multi = null
            onAssembled(whole, metadata, requestId)
        } else {
            ms.nextExpected = adv.segmentIndex + 1
            logger("segment ${adv.segmentIndex}/${adv.totalSegments} done — awaiting next ADV")
        }
    }
}
