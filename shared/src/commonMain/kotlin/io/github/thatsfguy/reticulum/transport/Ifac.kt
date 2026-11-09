package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Interface Access Codes (SPEC §2.1 bit 7; upstream RNS 1.5.7
 * `Transport.handle_outgoing_ifac`, `Transport.handle_ifac`, the IFAC gate
 * at the top of `Transport.preprocess_inbound`, and the key derivation in
 * `Reticulum._add_interface`).
 *
 * An IFAC key is 64 bytes derived from a network name and/or passphrase;
 * its second half is an Ed25519 seed. Every packet on an IFAC interface
 * carries the last `size` bytes of that key's signature over the packet,
 * inserted after the two header bytes, and every other byte is XOR-masked
 * with an HKDF stream keyed by that tag. Ed25519 is deterministic, so
 * checking a tag means re-signing: the key is a shared secret, not a
 * public key. IFAC is access control plus header obfuscation — the
 * payload's own encryption is unchanged.
 *
 * Pinned to bytes produced by upstream RNS in `IfacTest`.
 */
class IfacKey private constructor(
    private val crypto: CryptoProvider,
    private val raw: ByteArray,
    /** Tag length in bytes (upstream `ifac_size` / 8), 1..64. */
    val size: Int,
) {
    private val signingSeed = raw.copyOfRange(32, 64)

    private fun tag(packet: ByteArray): ByteArray {
        val sig = crypto.ed25519Sign(packet, signingSeed)
        return sig.copyOfRange(sig.size - size, sig.size)
    }

    /** Seal an outbound packet: tag after byte 2, mask the rest, set bit 7. */
    suspend fun seal(packet: ByteArray): ByteArray {
        require(packet.size >= 2) { "packet too short to seal" }
        val tag = tag(packet)
        val mask = rnsHkdf(crypto, packet.size + size, tag, raw)
        val out = ByteArray(packet.size + size)
        out[0] = (packet[0].toInt() or 0x80).toByte()
        out[1] = packet[1]
        tag.copyInto(out, 2)
        packet.copyInto(out, 2 + size, 2)
        for (i in out.indices) {
            if (i < 2 || i >= 2 + size) out[i] = (out[i].toInt() xor mask[i].toInt()).toByte()
        }
        out[0] = (out[0].toInt() or 0x80).toByte()
        return out
    }

    /**
     * Verify and strip the tag of an inbound frame. Returns the plain packet,
     * or null when the IFAC flag is missing, the frame is too short, or the
     * tag doesn't match — upstream drops all three as IFAC violations.
     */
    suspend fun unseal(frame: ByteArray): ByteArray? {
        if (frame.size <= 2 + size || (frame[0].toInt() and 0x80) == 0) return null
        val tag = frame.copyOfRange(2, 2 + size)
        val mask = rnsHkdf(crypto, frame.size, tag, raw)
        val plain = ByteArray(frame.size - size)
        plain[0] = ((frame[0].toInt() xor mask[0].toInt()) and 0x7F).toByte()
        plain[1] = (frame[1].toInt() xor mask[1].toInt()).toByte()
        for (i in 2 + size until frame.size) {
            plain[i - size] = (frame[i].toInt() xor mask[i].toInt()).toByte()
        }
        return if (constantTimeEquals(tag(plain), tag)) plain else null
    }

    companion object {
        /** `Reticulum.IFAC_SALT`. */
        private val IFAC_SALT: ByteArray =
            "adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        const val MIN_SIZE = 1
        const val MAX_SIZE = 64

        /**
         * `Reticulum._add_interface`: SHA-256 of the network name and of the
         * passphrase (each only if set), concatenated, hashed again, then
         * HKDF'd to 64 bytes with the fixed IFAC salt. At least one of the
         * two must be non-empty — with neither, upstream has no IFAC at all.
         */
        suspend fun derive(crypto: CryptoProvider, networkName: String, passphrase: String): ByteArray {
            require(networkName.isNotEmpty() || passphrase.isNotEmpty()) {
                "IFAC needs a network name or a passphrase"
            }
            var origin = ByteArray(0)
            if (networkName.isNotEmpty()) origin += crypto.sha256(networkName.encodeToByteArray())
            if (passphrase.isNotEmpty()) origin += crypto.sha256(passphrase.encodeToByteArray())
            return rnsHkdf(crypto, 64, crypto.sha256(origin), IFAC_SALT)
        }

        /** Wrap an already-derived 64-byte key (e.g. unsealed from storage). */
        fun fromKey(crypto: CryptoProvider, key: ByteArray, size: Int): IfacKey {
            require(key.size == 64) { "IFAC key must be 64 bytes" }
            require(size in MIN_SIZE..MAX_SIZE) { "IFAC size must be $MIN_SIZE..$MAX_SIZE bytes" }
            return IfacKey(crypto, key.copyOf(), size)
        }

        /**
         * `RNS.Cryptography.hkdf` with an empty context: RFC 5869
         * HKDF-SHA256, except the one-byte block counter wraps at 256
         * instead of failing. The mask spans the whole frame, and on a link
         * with a negotiated MTU (or a long test frame) that can exceed the
         * 8160 bytes standard HKDF allows — so this can't be the platform
         * HKDF.
         */
        internal suspend fun rnsHkdf(
            crypto: CryptoProvider,
            length: Int,
            ikm: ByteArray,
            salt: ByteArray,
        ): ByteArray {
            val prk = crypto.hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
            val out = ByteArray(length)
            var block = ByteArray(0)
            var written = 0
            var i = 0
            while (written < length) {
                block = crypto.hmacSha256(prk, block + byteArrayOf((i + 1).toByte()))
                val n = minOf(block.size, length - written)
                block.copyInto(out, written, 0, n)
                written += n
                i++
            }
            return out
        }

        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}

/**
 * A [Transport] that applies IFAC to everything crossing it. Wrap the raw
 * interface (TCP etc.) with this and the engine above sees ordinary packets.
 *
 * Inbound follows upstream's gate exactly: on an IFAC interface a frame
 * without the IFAC flag, too short for a tag, or with a wrong tag is
 * dropped — never passed through unauthenticated.
 */
class IfacTransport(
    private val inner: Transport,
    private val key: IfacKey,
    private val onViolation: (String) -> Unit = {},
) : Transport {
    override val state: StateFlow<TransportState> get() = inner.state

    override val endpoint: String? get() = inner.endpoint

    override val incoming: Flow<IncomingPacket> = inner.incoming.mapNotNull { pkt ->
        val plain = key.unseal(pkt.packet)
        if (plain == null) {
            onViolation(
                if ((pkt.packet.firstOrNull()?.toInt() ?: 0) and 0x80 == 0) "missing IFAC flag"
                else "invalid IFAC tag",
            )
            null
        } else pkt.copy(packet = plain)
    }

    override suspend fun connect() = inner.connect()
    override suspend fun disconnect() = inner.disconnect()
    override suspend fun send(packet: ByteArray) = inner.send(key.seal(packet))
}
