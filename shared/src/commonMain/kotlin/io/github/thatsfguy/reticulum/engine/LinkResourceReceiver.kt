package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_PRF
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_REQ
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.resource.Resource
import io.github.thatsfguy.reticulum.resource.ResourceAdvertisement

/**
 * Shared inbound-Resource state machine for both [LinkSession] (initiator
 * side) and [ResponderLinkSession] (peer-initiated link). Handles the
 * spec §10 receive flow:
 *
 *   CTX_RESOURCE_ADV  → decrypt body, parse advertisement, send
 *                       CTX_RESOURCE_REQ for the full hashmap (§10.5).
 *   CTX_RESOURCE      → append slice to hashmap; on the last part,
 *                       reassemble + send CTX_RESOURCE_PRF, then hand
 *                       the plaintext to [onAssembled].
 *
 * Why this exists separately: pre-2026-05-10 the receive-side state +
 * dispatch lived inline inside [LinkSession] and the responder side had
 * an `else -> ignore` catch-all (the LXMF-receiver-MVP comment). When an
 * LXMF server replied to a request by opening a NEW outbound link to us
 * (the standard fwdsvc / Sideband pattern), the reply landed on a
 * peer-initiated link as a Resource — and our responder dropped every
 * RESOURCE_ADV silently. This class lets both sides delegate to the
 * same code path; consumers differ only in [onAssembled]:
 *
 *   - [LinkSession]: completes the awaiting `responseDeferred` after
 *     decoding the `[request_id, response_data]` envelope.
 *   - [ResponderLinkSession]: unpacks the plaintext as link-delivered
 *     LXMF and routes through the same `onLxmfReceived` callback that
 *     single-packet CTX_NONE DATA uses.
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
    /** Invoked with the fully-reassembled plaintext after PRF emit. */
    private val onAssembled: suspend (ByteArray) -> Unit,
    /** Hook for the initiator-side path that needs to release a
     *  pending request_id deferred when ADV decrypt or parse fails;
     *  responder side leaves this as the default no-op. */
    private val onAdvParseFailure: suspend () -> Unit = {},
) {
    /** Active inbound resource — set on RESOURCE_ADV, finalized when
     *  the last chunk arrives. */
    private var pending: Resource? = null

    /** Total parts the advertisement promised. -1 before any ADV. */
    var advParts: Int = -1
        private set

    /** Total advertised transfer size in bytes. -1L before any ADV. */
    var advBytes: Long = -1L
        private set

    /** Chunks accepted into the current (or most recent) resource. */
    var chunksReceived: Int = 0
        private set

    /** Process a CTX_RESOURCE_ADV packet. Decrypts the body, parses
     *  the advertisement, and immediately fires CTX_RESOURCE_REQ for
     *  every entry in the hashmap. */
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
        logger("RESOURCE_ADV t=${adv.transferSize}B parts=${adv.totalParts} compressed=${adv.compressed} encrypted=${adv.encrypted}")
        advParts = adv.totalParts
        advBytes = adv.transferSize
        chunksReceived = 0
        pending = Resource(
            advertisement = adv,
            link = tokenCrypto,
            linkKey = link.derivedKey!!,
        )
        // Spec §10.5 — receiver MUST issue RESOURCE_REQ to request
        // chunks; the sender doesn't push them unsolicited. Our
        // HASHMAP_MAX_LEN cap (84 chunks) keeps this within the
        // link.mdu budget.
        runCatching { sendResourceReq(adv) }
            .onFailure { logger("RESOURCE_REQ send failed: ${it.message}") }
    }

    /** Process a CTX_RESOURCE chunk packet. On final-chunk arrival,
     *  reassembles + sends CTX_RESOURCE_PRF + invokes [onAssembled]. */
    suspend fun handleChunk(pkt: Packet) {
        val res = pending ?: run {
            logger("RESOURCE chunk arrived without prior ADV — dropping")
            return
        }
        // Spec §10.2 step 4 + step 6: the full
        //   random_hash || (compressed?) data
        // blob is link-encrypted ONCE, then sliced into parts. Each
        // chunk on the wire is a slice of the already-encrypted
        // whole — NOT individually encrypted. The hashmap match is
        // over the on-the-wire ciphertext slice; the outer decrypt
        // happens once over the full concatenation in
        // Resource.assemble() after all parts are in.
        val accepted = runCatching { res.receivePart(pkt.payload, crypto) }
            .onFailure { logger("receivePart threw: ${it.message}") }
            .getOrDefault(false)
        if (!accepted) {
            logger("RESOURCE chunk did not match any hashmap slot")
            return
        }
        chunksReceived++
        if (res.isComplete) finalize(res)
    }

    /** §10.5 RESOURCE_REQ body layout:
     *    exhausted_flag(1=0x00) || resource_hash(32) || N×map_hash(4)
     *  Token-encrypted with the link's derived key, sent as DATA with
     *  context = CTX_RESOURCE_REQ to the link_id (DEST_LINK per §12.5.2). */
    private suspend fun sendResourceReq(adv: ResourceAdvertisement) {
        val hashmapBytes = ByteArray(adv.hashmap.sumOf { it.size })
        var off = 0
        for (entry in adv.hashmap) {
            entry.copyInto(hashmapBytes, off)
            off += entry.size
        }
        val body = ByteArray(1 + adv.hash.size + hashmapBytes.size).also {
            it[0] = 0x00  // HASHMAP_IS_NOT_EXHAUSTED
            adv.hash.copyInto(it, 1)
            hashmapBytes.copyInto(it, 1 + adv.hash.size)
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
        logger("→ RESOURCE_REQ (${adv.hashmap.size} parts requested)")
    }

    private suspend fun finalize(res: Resource) {
        pending = null
        val plain = runCatching { res.assemble(crypto) }
            .onFailure { logger("resource assemble failed: ${it.message}") }
            .getOrNull() ?: run {
                onAdvParseFailure()
                return
            }

        // PRF emit (mandatory — without it the sender retransmits the
        // whole resource until MAX_RETRIES).
        runCatching {
            val proofPayload = res.buildProofPayload(plain, crypto)
            val proofPacket = buildPacket(
                headerType = HEADER_1,
                destType = DEST_LINK,
                packetType = PACKET_PROOF,
                destHash = link.linkId!!,
                context = CTX_RESOURCE_PRF,
                payload = proofPayload,
            )
            sender(proofPacket)
            logger("→ RESOURCE_PRF (${plain.size}B reassembled)")
        }.onFailure { logger("resource PRF send failed: ${it.message}") }

        onAssembled(plain)
    }
}
