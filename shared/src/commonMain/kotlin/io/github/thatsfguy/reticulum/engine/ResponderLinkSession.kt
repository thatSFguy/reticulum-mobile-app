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
import io.github.thatsfguy.reticulum.protocol.CTX_LINKIDENTIFY
import io.github.thatsfguy.reticulum.protocol.CTX_LRRTT
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_ADV
import io.github.thatsfguy.reticulum.protocol.CTX_RESOURCE_HMU
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.protocol.KEYSIZE
import io.github.thatsfguy.reticulum.protocol.SIGLENGTH
import io.github.thatsfguy.reticulum.protocol.TRUNCATED_HASHLENGTH
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.link.computePacketFullHash
import io.github.thatsfguy.reticulum.transport.toHex
// Multiplatform @Volatile (since Kotlin 1.9). `kotlin.jvm.Volatile`
// is JVM-only and breaks the Native compile; this one is a no-op on
// JS but writes through to the JMM volatile on JVM and to the
// Kotlin/Native memory model's atomic field on iOS.
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope

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
        hopCount: Int?,
        linkPeerDestHashHex: String?,
    ) -> Unit,
    private val onClose: suspend (linkIdHex: String, reason: String) -> Unit,
    private val logger: (String) -> Unit = {},
) : LinkPump {
    private val tokenCrypto = TokenCrypto(crypto)

    /** Inbound-Resource state machine. Added 2026-05-10 — the previous
     *  `else -> ignore` catch-all silently dropped every CTX_RESOURCE_ADV
     *  / CTX_RESOURCE that arrived on a peer-initiated link, which broke
     *  the standard LXMF reply pattern: an LXMF server (Sideband, fwdsvc,
     *  others) often replies to a request by opening a NEW outbound link
     *  to the client and Resource-transferring the reply over it. From
     *  the mobile app's perspective that reply lands on a responder-side
     *  link as a Resource, so without this wiring those replies were
     *  invisible.
     *
     *  When the resource is fully reassembled, [onAssembled] unpacks
     *  the plaintext as a link-delivered LXMF body and routes through
     *  [onLxmfReceived] — same path single-packet CTX_NONE DATA uses,
     *  so propagation into the inbox + notification fires identically. */
    private val resourceReceiver: LinkResourceReceiver = LinkResourceReceiver(
        link = link,
        tokenCrypto = tokenCrypto,
        crypto = crypto,
        sender = sender,
        logger = logger,
        nowMs = nowMs,
        // Responder side doesn't currently exercise file responses
        // (those are server→client; we're a client) but the callback
        // signature now includes metadata + requestId for parity with
        // the initiator's LinkResourceReceiver. Both are unused on
        // this path — inbound responder Resources are LXMF bodies.
        onAssembled = { plain, _, _ ->
            // Diagnostic prefix dump so we can tell from a tester's
            // log exactly what shape the assembled bytes have.
            // fwdsvc / Sideband Resource replies might be link-LXMF
            // (dest_hash(16) + source_hash(16) + sig + msgpack) or
            // LXMF DIRECT (source_hash(16) + sig + msgpack).
            // unpackLinkMessage only matches the former; if the
            // bytes are direct, that throws and the message gets
            // dropped silently. The prefix dump + dual-format
            // attempt below makes the failure visible and recovers
            // the direct-format case.
            val prefix = plain.take(32).joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            logger("Resource assembled ${plain.size}B; prefix=$prefix")

            // Try link-LXMF format first (matches CTX_NONE DATA on
            // responder-side links — same code path as inbound
            // single-packet LXMF that's already known to work).
            val linkMsg = runCatching { unpackLinkMessage(plain, crypto) }.getOrNull()
            if (linkMsg != null) {
                logger("Resource decoded as link-LXMF, sender=${linkMsg.sourceHash.toHex()}")
                onLxmfReceived(plain, linkMsg.sourceHash.toHex(), null, null, peerDestHashHex)
                return@LinkResourceReceiver
            }

            // Fall back to direct (opportunistic) LXMF — the format
            // most upstream servers use for replies. unpackMessage
            // verifies the recipient dest_hash matches ours; we
            // derive ours via computeDestinationHash on the bound
            // identity (lxmf.delivery + identity.hash[:16]).
            val ourDestHashBytes = identity.hash?.let {
                io.github.thatsfguy.reticulum.crypto.computeDestinationHash(
                    crypto, "lxmf.delivery", it,
                )
            }
            val directMsg = ourDestHashBytes?.let { ours ->
                runCatching {
                    io.github.thatsfguy.reticulum.lxmf.unpackMessage(plain, ours, crypto)
                }.getOrNull()
            }
            if (directMsg != null) {
                logger("Resource decoded as direct LXMF, sender=${directMsg.sourceHash.toHex()}")
                onLxmfReceived(plain, directMsg.sourceHash.toHex(), null, null, peerDestHashHex)
                return@LinkResourceReceiver
            }

            logger("Resource decode failed: neither link-LXMF nor direct LXMF — dropping ${plain.size}B")
        },
    )

    /** Wall-clock millis of last activity — used by the engine to expire
     *  silent links past STALE_TIME (720s in upstream RNS). */
    @Volatile var lastActivityMs: Long = nowMs()
        private set

    /** Wire the §10 inbound-Resource retransmit watchdog onto the engine
     *  scope. Called by the engine right after this session is created —
     *  the responder side has no keepalive loop of its own, so without
     *  this a lost RESOURCE part/HMU on a peer-initiated link (the Fwd
     *  service / Sideband reply pattern) would stall the transfer forever. */
    fun attachResourceScope(scope: CoroutineScope) {
        resourceReceiver.attachScope(scope)
    }

    /** Initiator's long-term lxmf.delivery destination hash (16 B hex),
     *  set after a SPEC §6.6 LINKIDENTIFY (context 0xFB) passes Ed25519
     *  validation. Stays null on links from peers who never identify
     *  (upstream Python only sends LINKIDENTIFY when the app calls
     *  `link.identify(identity)`, typically before an ALLOW_LIST REQUEST
     *  or — for fwdsvc — right after the link reaches ACTIVE so the
     *  service knows which recipient queue to bind the link to).
     *
     *  We surface this through [onLxmfReceived]'s `linkPeerDestHashHex`
     *  parameter so the engine can tag inbound LXMFs with `arrivedViaDest`
     *  when the carrying link's peer differs from the LXMF's `source_hash`.
     *  That's the fwdsvc relay case: link from fwdsvc → us, LXMF body
     *  source = the original sender. Without this signal, react/reply
     *  routes direct to source_hash and bypasses fwdsvc fanout. */
    @Volatile var peerDestHashHex: String? = null
        private set

    override suspend fun handlePacket(pkt: Packet, rssi: Int?) {
        lastActivityMs = nowMs()
        val ctx = pkt.context
        logger("rx ctx=0x${ctx.toString(16).padStart(2, '0')} payload=${pkt.payload.size}B")

        when (ctx) {
            CTX_NONE -> handleData(pkt, rssi)
            CTX_KEEPALIVE -> handleKeepAlive(pkt)
            CTX_LINKCLOSE -> {
                // Per spec §6.7.3 the LINKCLOSE body is the link_id
                // Token-encrypted with the link's session key. Receivers
                // MUST decrypt and verify plaintext == link_id before
                // accepting the close — otherwise a stray packet (or a
                // peer who learned our link_id) can prematurely tear the
                // link down.
                val plain = runCatching {
                    tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
                }.onFailure { logger("LINKCLOSE decrypt failed: ${it.message}") }.getOrNull() ?: return
                if (!plain.contentEquals(link.linkId!!)) {
                    logger("LINKCLOSE body != link_id — ignoring (got ${plain.size}B)")
                    return
                }
                onClose(link.linkId!!.toHex(), "peer closed link")
            }
            CTX_LRRTT -> {
                // Per the fwdsvc v1.3.2 interop test header
                // (../reticulum-forwarding-service/tests/interop/cases/
                // link_data_oversize.py): when an initiator (e.g.
                // fwdsvc opening a new outbound link to us so it can
                // Resource-transfer a /users reply) sends LRRTT after
                // validating its LRPROOF, the responder MUST advance
                // its link state from HANDSHAKE to ACTIVE — otherwise
                // upstream Python's resource_strategy stays at the
                // default ACCEPT_NONE and silently drops every
                // RESOURCE_ADV that follows. We don't model
                // resource_strategy explicitly (we accept anything
                // that decrypts), but matching the state-machine
                // transition keeps the link alive and gives the rest
                // of the engine a correct "this link is established"
                // signal. RTT payload itself is informational; the
                // initiator already has its own measurement.
                promoteToActiveIfHandshake("LRRTT")
            }
            CTX_RESOURCE_ADV -> {
                // Belt-and-braces — promote even if the LRRTT branch
                // didn't fire (e.g. peer running older Python without
                // the v1.3.2 fix). RESOURCE_ADV proves the peer
                // accepted our LRPROOF and is now sending data.
                promoteToActiveIfHandshake("first RESOURCE_ADV")
                resourceReceiver.handleAdvertisement(pkt)
            }
            CTX_RESOURCE -> {
                resourceReceiver.handleChunk(pkt)
            }
            CTX_RESOURCE_HMU -> {
                resourceReceiver.handleHmu(pkt)
            }
            CTX_LINKIDENTIFY -> {
                // SPEC §6.6: initiator-side identify proof. Cache the
                // initiator's lxmf.delivery destHash on this session so
                // subsequent inbound LXMFs can be tagged with the
                // carrying link's peer (the engine uses that to route
                // reactions / replies back through a relay like fwdsvc
                // when the LXMF body's source_hash differs from the
                // link peer's destHash).
                handleLinkIdentify(pkt)
            }
            // CTX_REQUEST / CTX_RESPONSE on a peer-initiated link
            // remain out of scope (we don't yet do propagation /get
            // over a responder link). KEEPALIVE / LRRTT etc. likewise.
            // Log + ignore so the link stays open for the contexts we
            // DO handle.
            else -> {
                logger("ignoring ctx 0x${ctx.toString(16).padStart(2, '0')} on responder link")
            }
        }
    }

    private fun promoteToActiveIfHandshake(reason: String) {
        if (link.state == LinkState.HANDSHAKE) {
            link.state = LinkState.ACTIVE
            link.establishedAtMs = nowMs()
            logger("link active ($reason)")
        }
    }

    private suspend fun handleData(pkt: Packet, rssi: Int?) {
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

        onLxmfReceived(plaintext, senderHashHex, rssi, pkt.hops, peerDestHashHex)
    }

    /**
     * SPEC §6.6 LINKIDENTIFY (context 0xFB). Wire shape, from
     * `RNS/Link.py:459-475` (sender) and `:1010-1026` (receiver):
     *
     *   ciphertext = Token-encrypt(plaintext, link.derivedKey)
     *   plaintext  = public_key(64) || signature(64)        # 128 B
     *   signature  = Ed25519_sign(sigPriv, link_id(16) || public_key(64))
     *
     * Validation steps mirror upstream:
     *  1) Decrypt with the established link's session key.
     *  2) Length must be exactly KEYSIZE + SIGLENGTH (64 + 64).
     *  3) Ed25519-verify the signature against `link_id || public_key`
     *     using the Ed25519 pub embedded in `public_key[32..64]`.
     *  4) On success, derive the peer's lxmf.delivery destHash and cache
     *     it on this session for downstream LXMF tagging.
     *
     * A bad LINKIDENTIFY is dropped silently (logged at info level) —
     * leaving `peerDestHashHex = null` means downstream reactions/replies
     * fall back to routing-by-source_hash, which is the legacy behavior.
     * No way for a forged identify to misroute outbound traffic.
     */
    private suspend fun handleLinkIdentify(pkt: Packet) {
        val plaintext = runCatching {
            tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
        }.onFailure { logger("LINKIDENTIFY decrypt failed: ${it.message}") }.getOrNull() ?: return

        if (plaintext.size != KEYSIZE + SIGLENGTH) {
            logger("LINKIDENTIFY wrong size: ${plaintext.size}B (need ${KEYSIZE + SIGLENGTH})")
            return
        }
        val publicKey = plaintext.copyOfRange(0, KEYSIZE)
        val signature = plaintext.copyOfRange(KEYSIZE, KEYSIZE + SIGLENGTH)
        val signedData = link.linkId!! + publicKey
        val sigPub = publicKey.copyOfRange(32, 64)
        if (!crypto.ed25519Verify(signature, signedData, sigPub)) {
            logger("LINKIDENTIFY signature invalid — dropping")
            return
        }

        val peerIdentityHash = crypto.truncatedHash(publicKey, TRUNCATED_HASHLENGTH)
        val peerDest = computeDestinationHash(crypto, "lxmf.delivery", peerIdentityHash)
        peerDestHashHex = peerDest.toHex()
        logger("✓ LINKIDENTIFY ok, peer=lxmf.delivery@${peerDestHashHex!!.take(8)}…")
    }

    private suspend fun handleKeepAlive(pkt: Packet) {
        // Per spec §6.7.1 the KEEPALIVE body is Token-encrypted with the
        // link's session key — wire body is iv(16) || ct || hmac(32); the
        // decrypted plaintext is the single sentinel byte 0xFF (ping).
        // The responder echoes 0xFE (pong), also Token-encrypted.
        val plain = runCatching {
            tokenCrypto.decryptWithDerivedKey(pkt.payload, link.derivedKey!!)
        }.onFailure { logger("KEEPALIVE decrypt failed: ${it.message}") }.getOrNull() ?: return
        if (plain.size != 1 || plain[0] != 0xFF.toByte()) {
            logger("KEEPALIVE plaintext != 0xFF (got ${plain.size}B) — ignoring")
            return
        }
        val pongCipher = tokenCrypto.encryptWithDerivedKey(byteArrayOf(0xFE.toByte()), link.derivedKey!!)
        val pong = buildPacket(
            headerType = HEADER_1,
            destType = DEST_LINK,
            packetType = PACKET_DATA,
            destHash = link.linkId!!,
            context = CTX_KEEPALIVE,
            payload = pongCipher,
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
