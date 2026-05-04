package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.protocol.CTX_LINKIDENTIFY
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.buildPacket

/**
 * LXMF propagation /get client. Implements the 3-phase fetch protocol
 * documented at LXMF/LXMRouter.py request_messages_from_propagation_node.
 *
 * Round 1 — list:
 *   REQUEST /get  body=msgpack([null, null])
 *   RESPONSE      msgpack(list of 16-byte transient_ids on the node for us)
 *
 * Round 2 — fetch:
 *   REQUEST /get  body=msgpack([wants, haves, transferLimitKb])
 *   RESPONSE      msgpack(list of full opportunistic LXMF blobs)
 *
 * Round 3 — cleanup (fire-and-forget):
 *   REQUEST /get  body=msgpack([null, receivedTids])
 *   RESPONSE      ignored
 *
 * MVP limit: round 2 RESPONSE bytes that exceed a single packet's
 * encrypted MDU (~370 plaintext bytes after Token + msgpack overhead)
 * are delivered as a Reticulum Resource. We don't yet implement
 * Resource receive, so a multi-packet round 2 reply will time out.
 * The 3-phase skeleton + UI is here so that when Resource lands, only
 * the response body assembly changes.
 *
 * The LinkSession passed in MUST be ACTIVE (LRPROOF accepted) and have
 * already had [identify] called on it.
 */
class PropagationClient(
    private val session: LinkSession,
    private val identity: Identity,
    private val crypto: CryptoProvider,
    private val sender: suspend (ByteArray) -> Unit,
    private val logger: (String) -> Unit = {},
) {
    /**
     * Send a CTX_LINKIDENTIFY packet on the active link. Required by
     * upstream LXMRouter before it answers /get — the propagation node
     * uses the identity hash carried inside to look up our queue.
     */
    suspend fun identify() {
        val ciphertext = session.link.buildIdentifyPayload(identity)
        // Spec §12.5.2: dest_type = LINK for any packet addressed to a
        // link_id so the relay's link_table forwarding kicks in.
        val packet = buildPacket(
            destType = DEST_LINK,
            packetType = PACKET_DATA,
            destHash = session.link.linkId!!,
            context = CTX_LINKIDENTIFY,
            payload = ciphertext,
        )
        sender(packet)
        logger("→ LINKIDENTIFY (${identity.hash!!.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }})")
    }

    data class FetchResult(
        val tidsAdvertised: List<ByteArray>,
        val messagesReceived: List<ByteArray>,
        val multiPacketDeferred: Boolean,
    )

    /**
     * Run a full poll: list → fetch → cleanup. [decideWants] is given
     * the list of transient_ids the node has for us, and returns the
     * subset we want delivered (and the ones we claim as already have,
     * for delete). The MVP caller passes a strategy that wants
     * everything.
     *
     * Multi-packet round 2 responses (which propagation almost always
     * uses for any non-trivial queue) are now reassembled by the
     * LinkSession's Resource receive path. The
     * [FetchResult.multiPacketDeferred] flag is still surfaced for
     * resources that exceed our HASHMAP_MAX_LEN cap (~42 KB total).
     */
    suspend fun pollAll(
        decideWants: (List<ByteArray>) -> Pair<List<ByteArray>, List<ByteArray>> = { tids -> tids to emptyList() },
        transferLimitKb: Int = 256,
        roundTimeoutMs: Long = 30_000L,
    ): FetchResult {
        // Spec §11.1: 16-byte truncation of SHA-256(path).
        val pathHash = crypto.sha256("/get".encodeToByteArray()).copyOfRange(0, 16)

        // ---- Round 1: ask for list ----
        val r1Body = MessagePack.encode(listOf<Any?>(null, null))
        val r1Bytes = session.request(pathHash, r1Body, roundTimeoutMs)
            ?: run {
                logger("propagation /get round 1 timed out")
                return FetchResult(emptyList(), emptyList(), multiPacketDeferred = false)
            }
        val tids = parseTidList(r1Bytes)
        logger("/get round 1 → ${tids.size} message(s) queued")
        if (tids.isEmpty()) {
            return FetchResult(tids, emptyList(), multiPacketDeferred = false)
        }

        // ---- Round 2: fetch wants ----
        val (wants, haves) = decideWants(tids)
        if (wants.isEmpty()) {
            logger("/get round 2 skipped — caller wanted nothing")
            return FetchResult(tids, emptyList(), multiPacketDeferred = false)
        }
        val r2Body = MessagePack.encode(listOf(wants, haves, transferLimitKb))
        val r2Bytes = session.request(pathHash, r2Body, roundTimeoutMs)
        if (r2Bytes == null) {
            // Most likely a Resource that exceeded our HASHMAP_MAX_LEN
            // cap (we don't yet implement REQ/HMU on long resources) or
            // a genuine timeout. Either way the link layer logs the
            // specific resource error.
            logger("/get round 2 timed out — node may have exceeded resource limit")
            return FetchResult(tids, emptyList(), multiPacketDeferred = true)
        }
        val lxmBlobs = parseLxmList(r2Bytes)
        logger("/get round 2 → ${lxmBlobs.size} message blob(s)")

        // ---- Round 3: cleanup (fire-and-forget) ----
        val received = wants.take(lxmBlobs.size)
        if (received.isNotEmpty()) {
            val r3Body = MessagePack.encode(listOf<Any?>(null, received))
            // Don't wait — upstream sends with no response_callback. We
            // pump the request anyway so the node deletes; if it times
            // out we don't care.
            runCatching { session.request(pathHash, r3Body, 5_000L) }
            logger("/get round 3 → cleanup for ${received.size} tid(s)")
        }

        return FetchResult(tids, lxmBlobs, multiPacketDeferred = false)
    }

    private fun parseTidList(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val decoded = runCatching { MessagePack.decode(bytes) }.getOrNull() ?: return emptyList()
        if (decoded !is List<*>) return emptyList()
        return decoded.mapNotNull { (it as? ByteArray)?.takeIf { b -> b.size == 16 } }
    }

    private fun parseLxmList(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val decoded = runCatching { MessagePack.decode(bytes) }.getOrNull() ?: return emptyList()
        if (decoded !is List<*>) return emptyList()
        return decoded.mapNotNull { it as? ByteArray }
    }
}
