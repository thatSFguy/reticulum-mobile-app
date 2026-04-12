package io.github.thatsfguy.reticulum.link

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity

/**
 * Reticulum Link: an encrypted bidirectional channel between two
 * destinations, used for LXMF link delivery (multi-packet messages,
 * delivery receipts with Ed25519 signatures).
 *
 * Port of reference/js-reference/link.js.
 * Full protocol details in CLAUDE.md "Reticulum Link protocol" section.
 *
 * The Link class handles both responder (incoming LINKREQUEST) and
 * initiator (outgoing LINKREQUEST) roles. Each role generates fresh
 * ephemeral X25519 keypairs per session.
 *
 * TODO: Port the following from reference/js-reference/link.js:
 *   - Link.validateRequest()    — responder: parse LINKREQUEST, derive keys, build LRPROOF
 *   - Link.createInitiator()    — initiator: build LINKREQUEST, generate ephemeral keys
 *   - Link.validateProof()      — initiator: verify received LRPROOF
 *   - computeLinkId()           — SHA-256(full_packet_hash_of_LINKREQUEST)[:16]
 *   - computePacketFullHash()   — SHA-256 of the FULL packet (header + payload)
 *   - encodeSignalling() / decodeSignalling()  — 3-byte signalling field
 *   - Session encrypt/decrypt using the derived link keys
 *
 * IMPORTANT: link_id is derived from the FULL packet (including
 * the Reticulum header), not just the payload. See CLAUDE.md.
 */
class Link(
    private val crypto: CryptoProvider,
) {
    var linkId: ByteArray? = null
        private set
    var isInitiator: Boolean = false
        private set
    var state: LinkState = LinkState.PENDING
        private set

    // Session keys, derived via HKDF after the handshake.
    var signingKey: ByteArray? = null
        private set
    var encryptionKey: ByteArray? = null
        private set

    // Ephemeral keypairs (per-session, per-role)
    var ourX25519Priv: ByteArray? = null; private set
    var ourX25519Pub: ByteArray? = null; private set
    var ourSigPriv: ByteArray? = null; private set  // only for initiator (ephemeral Ed25519)

    // Peer's keys learned during handshake
    var peerX25519Pub: ByteArray? = null; private set
    var peerSigPub: ByteArray? = null; private set

    companion object {
        /**
         * Responder: validate an incoming LINKREQUEST and produce an LRPROOF.
         *
         * @return Pair of (Link with derived keys, LRPROOF payload to send back)
         */
        suspend fun validateRequest(
            packet: io.github.thatsfguy.reticulum.protocol.Packet,
            ourIdentity: Identity,
            crypto: CryptoProvider,
        ): Pair<Link, ByteArray> {
            TODO("Port from reference/js-reference/link.js Link.validateRequest()")
        }

        /**
         * Initiator: create a LINKREQUEST for a known peer.
         *
         * @return Pair of (Link awaiting proof, LINKREQUEST payload to send)
         */
        suspend fun createInitiator(
            peerLongTermSigPub: ByteArray,
            peerDestHash: ByteArray,
            crypto: CryptoProvider,
        ): Pair<Link, ByteArray> {
            TODO("Port from reference/js-reference/link.js Link.createInitiator()")
        }
    }

    /**
     * Initiator: validate the received LRPROOF and complete the handshake.
     */
    suspend fun validateProof(proofPayload: ByteArray): Boolean {
        TODO("Port from reference/js-reference/link.js link.validateProof()")
    }
}

enum class LinkState { PENDING, ACTIVE, CLOSED }

/**
 * Compute the full SHA-256 hash of a Reticulum packet (header + payload),
 * which is used as input for link_id derivation.
 *
 * IMPORTANT: this hashes the ENTIRE packet including the 19-byte header,
 * not just the payload. link_id = SHA-256(this_hash)[:16].
 */
suspend fun computePacketFullHash(packet: ByteArray, crypto: CryptoProvider): ByteArray {
    return crypto.sha256(packet)
}
