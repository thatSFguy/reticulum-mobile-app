package io.github.thatsfguy.reticulum.crypto

import io.github.thatsfguy.reticulum.protocol.KEYSIZE
import io.github.thatsfguy.reticulum.protocol.NAME_HASH_LENGTH
import io.github.thatsfguy.reticulum.protocol.TRUNCATED_HASHLENGTH

/**
 * A Reticulum identity: an Ed25519 signing keypair + an X25519
 * encryption keypair, plus an optional ratchet X25519 keypair for
 * forward-secrecy-capable announces.
 *
 * Port of reference/js-reference/identity.js Identity class.
 *
 * The public key is the 64-byte concatenation: X25519_pub(32) || Ed25519_pub(32).
 * identity_hash = SHA-256(publicKey)[:16]
 *
 * TODO: Implement generate(), loadFromPrivateKeys(), exportPrivateKeys(),
 *       sign(), computeDestinationHash(), computeNameHash().
 *       Use [CryptoProvider] for all platform-specific operations.
 */
class Identity(private val crypto: CryptoProvider) {

    // X25519 encryption keypair
    var encPrivKey: ByteArray? = null
        private set
    var encPubKey: ByteArray? = null
        private set

    // Ed25519 signing keypair
    var sigPrivKey: ByteArray? = null
        private set
    var sigPubKey: ByteArray? = null
        private set

    // X25519 ratchet keypair. Rotated by [rotateRatchet] on a slow
    // cadence (gated by the engine's 30-min interval per upstream RNS
    // RATCHET_INTERVAL) so transit nodes that dedupe on
    // (destHash, ratchet) keep propagating our re-announces, without
    // racing peers' in-flight DATA encrypted to the prior ratchet.
    var ratchetPrivKey: ByteArray? = null
        private set
    var ratchetPubKey: ByteArray? = null
        private set

    // The PREVIOUS ratchet's private key, retained so we can decrypt
    // messages still in flight from peers who encrypted to the prior
    // ratchet pub between our rotation and them seeing our new
    // announce. One slot is enough for sub-30-min in-flight tolerance;
    // upstream Python keeps a ring of `RATCHET_COUNT = 512`.
    var previousRatchetPrivKey: ByteArray? = null
        private set

    /** 64-byte public key: X25519_pub || Ed25519_pub */
    val publicKey: ByteArray
        get() = (encPubKey ?: ByteArray(32)) + (sigPubKey ?: ByteArray(32))

    /** 16-byte truncated SHA-256 of publicKey. Null until generated/loaded. */
    var hash: ByteArray? = null
        private set

    /**
     * Generate a fresh identity with random keypairs + ratchet.
     */
    suspend fun generate() {
        encPrivKey = crypto.generateX25519PrivateKey()
        encPubKey  = crypto.x25519PublicKey(encPrivKey!!)
        sigPrivKey = crypto.generateEd25519PrivateKey()
        sigPubKey  = crypto.ed25519PublicKey(sigPrivKey!!)
        ratchetPrivKey = crypto.generateX25519PrivateKey()
        ratchetPubKey  = crypto.x25519PublicKey(ratchetPrivKey!!)
        hash = crypto.truncatedHash(publicKey, TRUNCATED_HASHLENGTH)
    }

    /**
     * Load from previously exported private keys.
     * Derives public keys and identity hash from the private material.
     */
    suspend fun loadFromPrivateKeys(
        encPriv: ByteArray,
        sigPriv: ByteArray,
        ratchetPriv: ByteArray? = null,
    ) {
        encPrivKey = encPriv
        encPubKey  = crypto.x25519PublicKey(encPriv)
        sigPrivKey = sigPriv
        sigPubKey  = crypto.ed25519PublicKey(sigPriv)
        if (ratchetPriv != null) {
            ratchetPrivKey = ratchetPriv
            ratchetPubKey  = crypto.x25519PublicKey(ratchetPriv)
        }
        hash = crypto.truncatedHash(publicKey, TRUNCATED_HASHLENGTH)
    }

    /**
     * Load from a 64-byte public key (no private material). Used for
     * contacts whose identity we learn from announces.
     */
    suspend fun loadFromPublicKey(pubKey: ByteArray) {
        require(pubKey.size == KEYSIZE) { "Public key must be $KEYSIZE bytes" }
        encPubKey = pubKey.copyOfRange(0, 32)
        sigPubKey = pubKey.copyOfRange(32, 64)
        hash = crypto.truncatedHash(pubKey, TRUNCATED_HASHLENGTH)
    }

    /**
     * Generate a fresh ratchet keypair, replacing the current one. The
     * long-term encryption / signing keys and identity hash are NOT
     * touched, so destHash stays stable across rotations. The PREVIOUS
     * ratchet privkey is preserved in [previousRatchetPrivKey] so the
     * engine can decrypt in-flight DATA encrypted to the prior ratchet
     * pub. Caller is responsible for persisting both keys via the
     * identity repository (the engine does this in sendAnnounce).
     */
    suspend fun rotateRatchet() {
        previousRatchetPrivKey = ratchetPrivKey
        ratchetPrivKey = crypto.generateX25519PrivateKey()
        ratchetPubKey  = crypto.x25519PublicKey(ratchetPrivKey!!)
    }

    /** Ed25519 sign [data] with this identity's signing key. */
    fun sign(data: ByteArray): ByteArray {
        requireNotNull(sigPrivKey) { "No signing key loaded" }
        return crypto.ed25519Sign(data, sigPrivKey!!)
    }

    /** Ed25519 verify [signature] over [data] using this identity's public key. */
    fun verify(signature: ByteArray, data: ByteArray): Boolean {
        val pub = sigPubKey ?: return false
        return crypto.ed25519Verify(signature, data, pub)
    }

    /** Export private keys for persistence. */
    fun exportPrivateKeys(): Map<String, ByteArray> = buildMap {
        encPrivKey?.let { put("encPrivKey", it) }
        sigPrivKey?.let { put("sigPrivKey", it) }
        ratchetPrivKey?.let { put("ratchetPrivKey", it) }
    }
}

/**
 * Compute destination_hash = SHA-256(name_hash || identity_hash)[:16]
 *
 * IMPORTANT: name_hash = SHA-256(fullName)[:10] where fullName is e.g.
 * "lxmf.delivery". The identity's hex hash is NOT part of the input.
 * See CLAUDE.md "Key bugs" §1.
 */
suspend fun computeDestinationHash(
    crypto: CryptoProvider,
    fullName: String,
    identityHash: ByteArray,
): ByteArray {
    val nameHash = computeNameHash(crypto, fullName)
    val material = nameHash + identityHash
    return crypto.truncatedHash(material, TRUNCATED_HASHLENGTH)
}

/** Compute name_hash = SHA-256(fullName.toByteArray(UTF-8))[:10] */
suspend fun computeNameHash(crypto: CryptoProvider, fullName: String): ByteArray {
    return crypto.truncatedHash(fullName.encodeToByteArray(), NAME_HASH_LENGTH)
}
