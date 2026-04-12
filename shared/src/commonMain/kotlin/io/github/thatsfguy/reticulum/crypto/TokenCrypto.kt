package io.github.thatsfguy.reticulum.crypto

/**
 * Reticulum Token encrypt/decrypt — a modified Fernet construction.
 *
 * Port of reference/js-reference/crypto.js encrypt() / decrypt().
 *
 * Encryption (opportunistic LXMF):
 *   1. Generate ephemeral X25519 keypair
 *   2. ECDH: shared = X25519(ephemeral_priv, recipient_enc_pub)
 *   3. HKDF(shared, salt=recipient_identity_hash, info=empty, len=64)
 *      → signing_key(32) + encryption_key(32)
 *   4. Random IV (16 bytes)
 *   5. AES-256-CBC encrypt (platform handles PKCS#7 padding)
 *   6. HMAC-SHA256(signing_key, IV + ciphertext)
 *   7. Token = ephemeral_pub(32) + HMAC(32) + IV(16) + ciphertext
 *
 * Decryption:
 *   1. Extract ephemeral_pub(32) from token
 *   2. ECDH: shared = X25519(our_enc_priv, ephemeral_pub)
 *   3. HKDF → signing_key + encryption_key
 *   4. Verify HMAC BEFORE decrypting (encrypt-then-MAC)
 *   5. AES-256-CBC decrypt
 *
 * Multi-key fallback: decrypt() tries a list of candidate private
 * keys (ratchet first, then long-term identity key) so senders who
 * haven't seen our ratchet yet can still reach us.
 *
 * TODO: Implement encrypt() and decrypt() using CryptoProvider.
 *       See reference/js-reference/crypto.js for the exact byte layout.
 */
class TokenCrypto(private val crypto: CryptoProvider) {

    /**
     * Encrypt [plaintext] to [recipientEncPub] (32-byte X25519 pub).
     * [recipientIdentityHash] is the 16-byte truncated hash used as HKDF salt.
     *
     * @return The full Token: ephemeral_pub(32) + HMAC(32) + IV(16) + ciphertext
     */
    suspend fun encrypt(
        plaintext: ByteArray,
        recipientEncPub: ByteArray,
        recipientIdentityHash: ByteArray,
    ): ByteArray {
        // 1. Ephemeral X25519 keypair
        val ephPriv = crypto.generateX25519PrivateKey()
        val ephPub  = crypto.x25519PublicKey(ephPriv)

        // 2. ECDH
        val shared = crypto.x25519SharedSecret(ephPriv, recipientEncPub)

        // 3. HKDF → 64 bytes: signing(32) + encryption(32)
        val derived = crypto.hkdfDerive(shared, recipientIdentityHash, ByteArray(0), 64)
        val signingKey    = derived.copyOfRange(0, 32)
        val encryptionKey = derived.copyOfRange(32, 64)

        // 4. Random IV
        val iv = crypto.randomBytes(16)

        // 5. AES-256-CBC encrypt (DO NOT pre-pad — platform handles PKCS#7)
        val ciphertext = crypto.aesCbcEncrypt(encryptionKey, iv, plaintext)

        // 6. HMAC over IV + ciphertext
        val hmac = crypto.hmacSha256(signingKey, iv + ciphertext)

        // 7. Assemble token
        return ephPub + hmac + iv + ciphertext
    }

    /**
     * Decrypt a Token using one or more candidate private keys.
     * Tries each key in order; the first successful HMAC+decrypt wins.
     *
     * @param token The full Token bytes
     * @param candidatePrivKeys List of X25519 private keys to try (ratchet first, then identity)
     * @param ourIdentityHash 16-byte HKDF salt
     * @return Decrypted plaintext
     * @throws IllegalStateException if no key succeeds
     */
    suspend fun decrypt(
        token: ByteArray,
        candidatePrivKeys: List<ByteArray>,
        ourIdentityHash: ByteArray,
    ): ByteArray {
        require(token.size >= 32 + 32 + 16) { "Token too short" }

        val ephPub     = token.copyOfRange(0, 32)
        val hmacGiven  = token.copyOfRange(32, 64)
        val iv         = token.copyOfRange(64, 80)
        val ciphertext = token.copyOfRange(80, token.size)

        var lastError: Exception? = null

        for (priv in candidatePrivKeys) {
            try {
                val shared  = crypto.x25519SharedSecret(priv, ephPub)
                val derived = crypto.hkdfDerive(shared, ourIdentityHash, ByteArray(0), 64)
                val signingKey    = derived.copyOfRange(0, 32)
                val encryptionKey = derived.copyOfRange(32, 64)

                // Verify HMAC BEFORE decrypting (encrypt-then-MAC)
                val hmacComputed = crypto.hmacSha256(signingKey, iv + ciphertext)
                if (!hmacComputed.contentEquals(hmacGiven)) continue

                return crypto.aesCbcDecrypt(encryptionKey, iv, ciphertext)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("No candidate key could decrypt the token")
    }
}
