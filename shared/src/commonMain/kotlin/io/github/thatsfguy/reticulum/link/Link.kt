package io.github.thatsfguy.reticulum.link

import io.github.thatsfguy.reticulum.announce.concatBytes
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.SIGLENGTH
import io.github.thatsfguy.reticulum.protocol.TRUNCATED_HASHLENGTH

/**
 * Reticulum Link: an encrypted bidirectional channel between two
 * destinations. Port of reference/js-reference/link.js.
 *
 * Layout reference:
 *   LINKREQUEST payload = peer_x25519_pub(32) + peer_ed25519_pub(32) + signalling(3)
 *   LRPROOF payload     = signature(64) + responder_x25519_pub(32) + signalling(3)
 *   signed_data         = link_id + responder_x25519_pub + responder_long_term_sig_pub + signalling
 *
 * Both sides derive the session key:
 *   shared = X25519(our_ephemeral_priv, their_ephemeral_pub)
 *   key64  = HKDF(shared, salt=link_id, info=empty, len=64)
 *   first 32 bytes = HMAC signing key, last 32 = AES-256 encryption key
 */
const val ECPUBSIZE        = 64    // 32 X25519 + 32 Ed25519
const val LINK_MTU_SIZE    = 3
const val LINK_KEYSIZE     = 64
const val LINK_DEFAULT_MTU = 500
const val MODE_AES256_CBC  = 0x01

/** Encode a 3-byte signalling field carrying mtu (low 21 bits) + mode (top 3 bits). */
fun encodeSignalling(mtu: Int, mode: Int): ByteArray {
    val v = (mtu and 0x1FFFFF) or ((mode and 0x07) shl 21)
    return byteArrayOf(((v ushr 16) and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
}

data class Signalling(val mtu: Int, val mode: Int)

fun decodeSignalling(bytes: ByteArray): Signalling {
    val v = ((bytes[0].toInt() and 0xFF) shl 16) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
             (bytes[2].toInt() and 0xFF)
    return Signalling(mtu = v and 0x1FFFFF, mode = (v ushr 21) and 0x07)
}

/**
 * Compute the link_id for a LINKREQUEST packet.
 *
 *   hashable_part = (flags & 0x0F) || raw[2:]            (HEADER_1)
 *   hashable_part = (flags & 0x0F) || raw[18:]           (HEADER_2)
 *   if payload.size > ECPUBSIZE:
 *       hashable_part = hashable_part[:-(payload.size - ECPUBSIZE)]
 *   link_id = SHA-256(hashable)[0..16]
 */
suspend fun computeLinkId(packet: Packet, crypto: CryptoProvider): ByteArray {
    val flagsLow = packet.flags and 0x0F
    val skipBytes = if (packet.headerType == HEADER_1) 2 else 2 + TRUNCATED_HASHLENGTH
    val tail = packet.raw.copyOfRange(skipBytes, packet.raw.size)
    var hp = ByteArray(1 + tail.size)
    hp[0] = flagsLow.toByte()
    tail.copyInto(hp, 1)
    if (packet.payload.size > ECPUBSIZE) {
        val diff = packet.payload.size - ECPUBSIZE
        hp = hp.copyOfRange(0, hp.size - diff)
    }
    return crypto.sha256(hp).copyOfRange(0, 16)
}

/**
 * Compute the full SHA-256 packet hash used for receipts (PROOF packets).
 *
 *   hashable_part = (flags & 0x0F) || raw[2:]            (HEADER_1)
 *   hashable_part = (flags & 0x0F) || raw[18:]           (HEADER_2)
 *   return SHA-256(hashable)   // full 32 bytes, no signalling stripping
 */
suspend fun computePacketFullHash(packet: Packet, crypto: CryptoProvider): ByteArray {
    val flagsLow = packet.flags and 0x0F
    val skipBytes = if (packet.headerType == HEADER_1) 2 else 2 + TRUNCATED_HASHLENGTH
    val tail = packet.raw.copyOfRange(skipBytes, packet.raw.size)
    val hp = ByteArray(1 + tail.size)
    hp[0] = flagsLow.toByte()
    tail.copyInto(hp, 1)
    return crypto.sha256(hp)
}

enum class LinkState { PENDING, HANDSHAKE, ACTIVE, CLOSED }

/**
 * One Reticulum Link session. Holds session keys, ephemeral keypairs, and
 * derived state. Use [validateRequest] (responder) or [createInitiator]
 * (initiator) to construct.
 */
class Link(private val crypto: CryptoProvider) {
    var linkId: ByteArray? = null; internal set
    var isInitiator: Boolean = false; internal set
    var state: LinkState = LinkState.PENDING; internal set

    var ourX25519Priv: ByteArray? = null; internal set
    var ourX25519Pub: ByteArray? = null; internal set
    var ourSigPriv: ByteArray? = null; internal set
    var ourSigPub: ByteArray? = null; internal set

    var peerX25519Pub: ByteArray? = null; internal set
    var peerEd25519Pub: ByteArray? = null; internal set
    var peerLongTermSigPub: ByteArray? = null; internal set

    var derivedKey: ByteArray? = null; internal set
    var mtu: Int = LINK_DEFAULT_MTU; internal set
    var mode: Int = MODE_AES256_CBC; internal set
    var signallingBytes: ByteArray? = null; internal set
    var ownerDestHash: ByteArray? = null; internal set
    var cachedProofData: ByteArray? = null; internal set

    var createdAtMs: Long = 0; internal set
    var establishedAtMs: Long = 0; internal set
    var rttSeconds: Double = 0.0; internal set

    private val tokenCrypto = TokenCrypto(crypto)

    suspend fun encrypt(plaintext: ByteArray): ByteArray =
        tokenCrypto.encryptWithDerivedKey(plaintext, requireNotNull(derivedKey) { "Link not established" })

    suspend fun decrypt(ciphertext: ByteArray): ByteArray =
        tokenCrypto.decryptWithDerivedKey(ciphertext, requireNotNull(derivedKey) { "Link not established" })

    /**
     * Initiator: validate an inbound LRPROOF and complete the handshake.
     *
     * The proof's signature is checked against the long-term Ed25519 pub
     * supplied at [createInitiator] (learned from the peer's announce).
     * On success, derives the session key and returns the LRRTT bytes the
     * caller should transmit (Token-encrypted msgpack of the RTT).
     */
    suspend fun validateProof(proofPayload: ByteArray, nowMs: Long): LrProofResult {
        check(isInitiator) { "validateProof called on responder link" }
        check(state == LinkState.PENDING) { "Link state is $state, expected PENDING" }
        if (proofPayload.size != SIGLENGTH + 32 && proofPayload.size != SIGLENGTH + 32 + LINK_MTU_SIZE) {
            return LrProofResult.Failure("LRPROOF payload size ${proofPayload.size} not 96 or 99")
        }

        val signature   = proofPayload.copyOfRange(0, SIGLENGTH)
        val peerXPub    = proofPayload.copyOfRange(SIGLENGTH, SIGLENGTH + 32)
        val signalling  = if (proofPayload.size == SIGLENGTH + 32 + LINK_MTU_SIZE) {
            proofPayload.copyOfRange(SIGLENGTH + 32, SIGLENGTH + 32 + LINK_MTU_SIZE)
        } else {
            requireNotNull(signallingBytes) { "no cached signalling for legacy LRPROOF" }
        }

        val signedData = concatBytes(listOf(
            requireNotNull(linkId) { "linkId not set; call setLinkIdFromPacket first" },
            peerXPub,
            requireNotNull(peerLongTermSigPub) { "peerLongTermSigPub missing" },
            signalling,
        ))
        if (!crypto.ed25519Verify(signature, signedData, peerLongTermSigPub!!)) {
            return LrProofResult.Failure("LRPROOF signature verification failed")
        }

        peerX25519Pub = peerXPub
        signallingBytes = signalling
        val sigDecoded = decodeSignalling(signalling)
        if (sigDecoded.mode != mode) {
            return LrProofResult.Failure("LRPROOF mode 0x${sigDecoded.mode.toString(16)} mismatch")
        }
        if (sigDecoded.mtu > 0) mtu = sigDecoded.mtu

        val shared = crypto.x25519SharedSecret(ourX25519Priv!!, peerXPub)
        derivedKey = crypto.hkdfDerive(shared, linkId!!, ByteArray(0), LINK_KEYSIZE)
        state = LinkState.ACTIVE
        establishedAtMs = nowMs
        rttSeconds = (establishedAtMs - createdAtMs) / 1000.0

        val rttMsgpack = MessagePack.encode(rttSeconds)
        val rttCipher = tokenCrypto.encryptWithDerivedKey(rttMsgpack, derivedKey!!)
        return LrProofResult.Success(rttData = rttCipher, rtt = rttSeconds)
    }

    /** Tell the initiator link what its link_id is, computed from the packed LINKREQUEST. */
    suspend fun setLinkIdFromPacket(packet: Packet) {
        linkId = computeLinkId(packet, crypto)
    }

    sealed class LrProofResult {
        data class Success(val rttData: ByteArray, val rtt: Double) : LrProofResult()
        data class Failure(val reason: String) : LrProofResult()
    }

    companion object {
        /**
         * Responder: validate an incoming LINKREQUEST and produce an LRPROOF payload.
         *
         * @return The new link (state=HANDSHAKE) and the LRPROOF payload to wrap in
         *   a PACKET_PROOF with context=CTX_LRPROOF addressed to the link_id.
         */
        suspend fun validateRequest(
            packet: Packet,
            ourIdentity: Identity,
            crypto: CryptoProvider,
        ): Pair<Link, ByteArray> {
            val data = packet.payload
            require(data.size == ECPUBSIZE || data.size == ECPUBSIZE + LINK_MTU_SIZE) {
                "Invalid LINKREQUEST payload size ${data.size}"
            }

            val peerXPub = data.copyOfRange(0, 32)
            val peerEdPub = data.copyOfRange(32, 64)

            var mtu = LINK_DEFAULT_MTU
            var mode = MODE_AES256_CBC
            if (data.size == ECPUBSIZE + LINK_MTU_SIZE) {
                val sig = decodeSignalling(data.copyOfRange(ECPUBSIZE, ECPUBSIZE + LINK_MTU_SIZE))
                if (sig.mtu > 0) mtu = sig.mtu
                mode = sig.mode
            }
            require(mode == MODE_AES256_CBC) { "Unsupported link mode 0x${mode.toString(16)}" }

            val link = Link(crypto)
            link.linkId = computeLinkId(packet, crypto)
            link.ourX25519Priv = crypto.generateX25519PrivateKey()
            link.ourX25519Pub  = crypto.x25519PublicKey(link.ourX25519Priv!!)
            link.ourSigPriv    = ourIdentity.sigPrivKey
            link.ourSigPub     = ourIdentity.sigPubKey
            link.peerX25519Pub  = peerXPub
            link.peerEd25519Pub = peerEdPub
            link.mtu = mtu
            link.mode = mode
            link.ownerDestHash = packet.destHash

            val shared = crypto.x25519SharedSecret(link.ourX25519Priv!!, peerXPub)
            link.derivedKey = crypto.hkdfDerive(shared, link.linkId!!, ByteArray(0), LINK_KEYSIZE)
            link.state = LinkState.HANDSHAKE

            link.signallingBytes = encodeSignalling(mtu, mode)
            val signedData = concatBytes(listOf(
                link.linkId!!,
                link.ourX25519Pub!!,
                link.ourSigPub!!,
                link.signallingBytes!!,
            ))
            val signature = ourIdentity.sign(signedData)
            val proofData = concatBytes(listOf(signature, link.ourX25519Pub!!, link.signallingBytes!!))
            link.cachedProofData = proofData

            return link to proofData
        }

        /**
         * Initiator: build a LINKREQUEST aimed at a known peer.
         *
         * @param peerLongTermSigPub responder's 32-byte Ed25519 pub from its announce.
         * @param peerDestHash responder's destination hash (16 bytes).
         * @return The new link (state=PENDING; linkId not yet known) and the
         *   LINKREQUEST payload bytes. Caller wraps these in a PACKET_LINKREQ
         *   addressed to peerDestHash, then immediately calls [setLinkIdFromPacket].
         */
        suspend fun createInitiator(
            peerLongTermSigPub: ByteArray,
            peerDestHash: ByteArray,
            crypto: CryptoProvider,
            nowMs: Long,
        ): Pair<Link, ByteArray> {
            val link = Link(crypto)
            link.isInitiator = true
            link.peerLongTermSigPub = peerLongTermSigPub
            link.ownerDestHash = peerDestHash
            link.createdAtMs = nowMs

            link.ourX25519Priv = crypto.generateX25519PrivateKey()
            link.ourX25519Pub  = crypto.x25519PublicKey(link.ourX25519Priv!!)
            link.ourSigPriv    = crypto.generateEd25519PrivateKey()
            link.ourSigPub     = crypto.ed25519PublicKey(link.ourSigPriv!!)

            link.signallingBytes = encodeSignalling(link.mtu, link.mode)
            val requestData = concatBytes(listOf(
                link.ourX25519Pub!!,
                link.ourSigPub!!,
                link.signallingBytes!!,
            ))
            return link to requestData
        }
    }
}
