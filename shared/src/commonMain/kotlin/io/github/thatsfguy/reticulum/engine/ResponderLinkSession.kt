package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.LinkState
import io.github.thatsfguy.reticulum.lxmf.SignatureVariant
import io.github.thatsfguy.reticulum.lxmf.unpackLinkMessage
import io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature
import io.github.thatsfguy.reticulum.protocol.CTX_KEEPALIVE
import io.github.thatsfguy.reticulum.protocol.CTX_LINKCLOSE
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.link.computePacketFullHash
import io.github.thatsfguy.reticulum.transport.toHex

/**
 * Responder-side driver for a Reticulum Link that someone else opened to
 * us. Created in [ReticulumEngine.handleIncoming] when an inbound
 * PACKET_LINKREQ addressed to our destination passes [Link.validateRequest].
 *
 * Responsibilities:
 *  - Decrypt incoming DATA packets on the link with the pre-derived
 *    session key and parse them as link-delivered LXMF.
 *  - Hand each decrypted message to [onLxmfReceived] so the engine can
 *    persist it via the same path as opportunistic messages (notification
 *    fires from there).
 *  - Emit a per-packet PROOF receipt for every CONTEXT_NONE DATA packet
 *    immediately on decrypt success — without this, peers' delivery
 *    receipt timeouts fire and the same message is retransmitted on a
 *    fresh link every ~15 seconds (CLAUDE.md gotcha #6).
 *  - Echo KEEPALIVE pings (initiator: 0xFF → responder: 0xFE), drop the
 *    session on LINKCLOSE.
 *
 * The proof wire format follows the explicit form documented in
 * `reference/PROTOCOL_NOTES.md` §13:
 *   packet_hash = SHA256(hashable_part)            # full 32 bytes
 *   signature   = ed25519_sign(longTermSig, hash)  # 64 bytes
 *   proof_data  = packet_hash || signature         # 96 bytes
 * sent as a PACKET_PROOF with flags 0x0F
 * (HEADER_1 | DEST_LINK | PACKET_PROOF), context 0x00, dest = link_id.
 *
 * The MVP only handles single-packet messages on the link; multi-packet
 * Resource flows (large LXMF, NomadNet form posts, propagation round 2)
 * still need separate scaffolding.
 */
class ResponderLinkSession internal constructor(
    val link: Link,
    private val identity: Identity,
    private val crypto: CryptoProvider,
    private val sender: suspend (ByteArray) -> Unit,
    private val nowMs: () -> Long,
    private val onLxmfReceived: suspend (
        plaintextLxmf: ByteArray,
        senderDestHashHex: String,
        rssi: Int?,
    ) -> Unit,
    private val onClose: suspend (linkIdHex: String, reason: String) -> Unit,
    private val logger: (String) -> Unit = {},
) : LinkPump {
    private val tokenCrypto = TokenCrypto(crypto)

    /** Wall-clock millis of last activity — used by the engine to expire
     *  silent links past STALE_TIME (720s in upstream RNS). */
    @Volatile var lastActivityMs: Long = nowMs()
        private set

    override suspend fun handlePacket(pkt: Packet) {
        lastActivityMs = nowMs()
        val ctx = pkt.context
        logger("rx ctx=0x${ctx.toString(16).padStart(2, '0')} payload=${pkt.payload.size}B")

        when (ctx) {
            CTX_NONE -> handleData(pkt)
            CTX_KEEPALIVE -> handleKeepAlive(pkt)
            CTX_LINKCLOSE -> {
                onClose(link.linkId!!.toHex(), "peer closed link")
            }
            else -> {
                // RESOURCE_*, REQUEST/RESPONSE on a peer-initiated link are
                // out of scope for the LXMF receiver MVP. Log and ignore so
                // the link stays open for the LXMF DATA we DO understand.
                logger("ignoring ctx 0x${ctx.toString(16).padStart(2, '0')} on responder link")
            }
        }
    }

    private suspend fun handleData(pkt: Packet) {
        if (link.state != LinkState.HANDSHAKE && link.state != LinkState.ACTIVE) {
            logger("data on non-active link (state=${link.state}) — dropping")
            return
        }
        // The first inbound DATA after we send LRPROOF implicitly confirms
        // the link is alive on the peer's side. Promote HANDSHAKE → ACTIVE.
        if (link.state == LinkState.HANDSHAKE) {
            link.state = LinkState.ACTIVE
            link.establishedAtMs = nowMs()
            logger("link active (first data)")
        }

        val plaintext = runCatching {
            tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
        }.onFailure { logger("decrypt failed: ${it.message}") }.getOrNull() ?: return

        // Send the packet receipt ASAP, before any potentially-failing
        // higher-layer parsing — what the proof attests to is "we received
        // and decrypted these bytes," not "we successfully unpacked LXMF."
        runCatching { sendPacketProof(pkt) }
            .onFailure { logger("packet proof send failed: ${it.message}") }

        // The LXMF link wire format prefixes our own dest_hash, then the
        // sender's, then signature, then msgpack payload. We pass the
        // whole plaintext through the dual-variant verifier.
        val msg = runCatching { unpackLinkMessage(plaintext, crypto) }
            .onFailure { logger("link LXMF unpack failed: ${it.message}") }
            .getOrNull() ?: return
        val senderHashHex = msg.sourceHash.toHex()

        onLxmfReceived(plaintext, senderHashHex, null)
    }

    private suspend fun handleKeepAlive(pkt: Packet) {
        // Initiator pings with a single 0xFF byte; responder echoes 0xFE
        // with the same context. Without this, idle links time out at
        // STALE_TIME (~12 min) and downstream messages get retried on a
        // fresh link after that.
        if (pkt.payload.isEmpty() || pkt.payload[0] != 0xFF.toByte()) {
            logger("unexpected keepalive payload — ignoring")
            return
        }
        val pong = buildPacket(
            headerType = HEADER_1,
            destType = DEST_LINK,
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_KEEPALIVE,
            payload = byteArrayOf(0xFE.toByte()),
        )
        sender(pong)
    }

    private suspend fun sendPacketProof(originalDataPacket: Packet) {
        val fullHash = computePacketFullHash(originalDataPacket, crypto)  // 32 bytes
        val signature = identity.sign(fullHash)                            // 64 bytes
        val proofData = ByteArray(fullHash.size + signature.size).also {
            fullHash.copyInto(it, 0)
            signature.copyInto(it, fullHash.size)
        }
        val proofPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash = link.linkId!!,
            context = CTX_NONE,
            payload = proofData,
        )
        sender(proofPacket)
    }

    /**
     * Verify a freshly-received link-delivered LXMF against a sender's
     * cached identity. Returns the matched [SignatureVariant] or null if
     * neither variant validates. Public so the engine can re-run
     * verification once an unknown sender's announce later arrives.
     */
    suspend fun tryVerify(plaintext: ByteArray, senderIdentity: Identity): SignatureVariant? {
        val msg = unpackLinkMessage(plaintext, crypto)
        return verifyMessageSignature(msg, senderIdentity, crypto)
    }
}
