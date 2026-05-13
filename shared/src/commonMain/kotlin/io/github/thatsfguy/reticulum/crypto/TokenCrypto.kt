package io.github.thatsfguy.reticulum.crypto

/**
 * Reticulum Token encrypt/decrypt — a modified Fernet construction.
 *
 * Port of reference/js-reference/crypto.js encrypt() / decrypt().
 *
 * On-wire format (matches JS reference and Python RNS):
 *   ephemeral_pub(32) || iv(16) || aes_ciphertext || hmac(32)
 *
 * Encryption (opportunistic LXMF):
 *   1. Generate ephemeral X25519 keypair
 *   2. ECDH: shared = X25519(ephemeral_priv, recipient_enc_pub)
 *   3. HKDF(shared, salt=recipient_identity_hash, info=empty, len=64)
 *      → signing_key(32) + encryption_key(32)
 *   4. Random IV (16 bytes)
 *   5. AES-256-CBC encrypt (platform handles PKCS#7 padding)
 *   6. HMAC-SHA256(signing_key, IV + ciphertext)
 *   7. Wire = ephemeral_pub(32) + IV(16) + ciphertext + HMAC(32)
 *
 * Decryption is the reverse. HMAC is verified BEFORE decryption
 * (encrypt-then-MAC, prevents padding oracle attacks).
 *
 * Multi-key fallback: decrypt() tries a list of candidate private
 * keys (ratchet first, then long-term identity key) so senders who
 * haven't seen our ratchet yet can still reach us.
 */
class TokenCrypto(private val crypto: CryptoProvider) {

    /**
     * Encrypt [plaintext] to [recipientEncPub] (32-byte X25519 pub).
     * [recipientIdentityHash] is the 16-byte truncated hash used as HKDF salt.
     *
     * @return ephemeral_pub(32) + IV(16) + ciphertext + HMAC(32)
     */
    suspend fun encrypt(
        plaintext: ByteArray,
        recipientEncPub: ByteArray,
        recipientIdentityHash: ByteArray,
    ): ByteArray {
        val ephPriv = crypto.generateX25519PrivateKey()
        val ephPub  = crypto.x25519PublicKey(ephPriv)

        val shared = crypto.x25519SharedSecret(ephPriv, recipientEncPub)

        val derived = crypto.hkdfDerive(shared, recipientIdentityHash, ByteArray(0), 64)
        val signingKey    = derived.copyOfRange(0, 32)
        val encryptionKey = derived.copyOfRange(32, 64)

        val iv = crypto.randomBytes(16)
        val ciphertext = crypto.aesCbcEncrypt(encryptionKey, iv, plaintext)
        val hmac = crypto.hmacSha256(signingKey, iv + ciphertext)

        return ephPub + iv + ciphertext + hmac
    }

    /**
     * Decrypt a Token using one or more candidate private keys.
     * Tries each key in order; the first successful HMAC+decrypt wins.
     *
     * @param token ephemeral_pub(32) + IV(16) + ciphertext + HMAC(32)
     * @param candidatePrivKeys X25519 private keys to try (ratchet first, identity second)
     * @param ourIdentityHash 16-byte HKDF salt
     * @return Plaintext
     * @throws IllegalStateException if no key succeeds
     */
    suspend fun decrypt(
        token: ByteArray,
        candidatePrivKeys: List<ByteArray>,
        ourIdentityHash: ByteArray,
    ): ByteArray {
        // 32 ephPub + 16 IV + at least 16 ciphertext (one block) + 32 HMAC = 96
        require(token.size >= 32 + 16 + 16 + 32) { "Token too short: ${token.size}" }

        val ephPub     = token.copyOfRange(0, 32)
        val iv         = token.copyOfRange(32, 48)
        val ciphertext = token.copyOfRange(48, token.size - 32)
        val hmacGiven  = token.copyOfRange(token.size - 32, token.size)

        var lastError: Exception? = null

        for (priv in candidatePrivKeys) {
            try {
                val shared  = crypto.x25519SharedSecret(priv, ephPub)
                val derived = crypto.hkdfDerive(shared, ourIdentityHash, ByteArray(0), 64)
                val signingKey    = derived.copyOfRange(0, 32)
                val encryptionKey = derived.copyOfRange(32, 64)

                val hmacComputed = crypto.hmacSha256(signingKey, iv + ciphertext)
                // Constant-time compare. ByteArray.contentEquals
                // short-circuits on first mismatch which leaks
                // per-byte timing on the HMAC verify; over noisy
                // radio links the signal is small but using the
                // safe primitive everywhere is cheap hygiene.
                // Audit reference: 2026-05-13 LOW-7.
                if (!constantTimeEquals(hmacComputed, hmacGiven)) continue

                return crypto.aesCbcDecrypt(encryptionKey, iv, ciphertext)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("No candidate key could decrypt the token")
    }

    /**
     * Encrypt with a pre-derived 64-byte key (for established Links).
     * Wire format: iv(16) + ciphertext + hmac(32) — NO ephemeral pub prefix
     * because the link's session key was already negotiated.
     */
    suspend fun encryptWithDerivedKey(plaintext: ByteArray, derivedKey: ByteArray): ByteArray {
        require(derivedKey.size == 64) { "Derived link key must be 64 bytes" }
        val signingKey    = derivedKey.copyOfRange(0, 32)
        val encryptionKey = derivedKey.copyOfRange(32, 64)
        val iv = crypto.randomBytes(16)
        val ciphertext = crypto.aesCbcEncrypt(encryptionKey, iv, plaintext)
        val hmac = crypto.hmacSha256(signingKey, iv + ciphertext)
        return iv + ciphertext + hmac
    }

    /**
     * Decrypt a token produced with [encryptWithDerivedKey].
     */
    suspend fun decryptWithDerivedKey(token: ByteArray, derivedKey: ByteArray): ByteArray {
        require(derivedKey.size == 64) { "Derived link key must be 64 bytes" }
        require(token.size >= 16 + 16 + 32) { "Link token too short: ${token.size}" }
        val signingKey    = derivedKey.copyOfRange(0, 32)
        val encryptionKey = derivedKey.copyOfRange(32, 64)
        val iv         = token.copyOfRange(0, 16)
        val ciphertext = token.copyOfRange(16, token.size - 32)
        val hmacGiven  = token.copyOfRange(token.size - 32, token.size)
        val hmacComputed = crypto.hmacSha256(signingKey, iv + ciphertext)
        // Constant-time compare on the link-decrypt path. Same
        // rationale as the opportunistic-decrypt twin above. Audit
        // reference: 2026-05-13 LOW-7.
        if (!constantTimeEquals(hmacComputed, hmacGiven)) {
            throw IllegalStateException("HMAC verification failed")
        }
        return crypto.aesCbcDecrypt(encryptionKey, iv, ciphertext)
    }
}
