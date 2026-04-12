package io.github.thatsfguy.reticulum.crypto

/**
 * Platform-independent crypto interface. Declared in commonMain,
 * implemented per-platform in androidMain/iosMain via expect/actual
 * or dependency injection.
 *
 * Every function in this interface has a corresponding JS
 * implementation in reference/js-reference/crypto.js or
 * reference/js-reference/identity.js. The test vectors in
 * reference/test-vectors.json verify correctness.
 */
interface CryptoProvider {

    /** SHA-256 hash, full 32-byte output. */
    suspend fun sha256(data: ByteArray): ByteArray

    /** SHA-256 hash truncated to [length] bytes. Used for identity_hash (16), name_hash (10), dest_hash (16). */
    suspend fun truncatedHash(data: ByteArray, length: Int = 16): ByteArray

    /** Generate a random X25519 private key (32 bytes, clamped). */
    fun generateX25519PrivateKey(): ByteArray

    /** Derive the X25519 public key (32 bytes) from a private key. */
    fun x25519PublicKey(privateKey: ByteArray): ByteArray

    /** X25519 Diffie-Hellman: shared_secret = X25519(ourPriv, theirPub). Returns 32 bytes. */
    fun x25519SharedSecret(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray

    /** Generate an Ed25519 private key (seed, 32 bytes). */
    fun generateEd25519PrivateKey(): ByteArray

    /** Derive the Ed25519 public key (32 bytes) from a private key/seed. */
    fun ed25519PublicKey(privateKey: ByteArray): ByteArray

    /** Ed25519 sign. Returns 64-byte signature. */
    fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray

    /** Ed25519 verify. Returns true if signature is valid. */
    fun ed25519Verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean

    /**
     * HKDF-SHA256 key derivation.
     *
     * @param ikm Input key material (e.g., ECDH shared secret)
     * @param salt Salt (e.g., identity_hash or link_id). NOT secret, just unique per context.
     * @param info Context info. Empty ByteArray for Reticulum's current protocol.
     * @param length Output length in bytes (typically 64: 32 signing + 32 encryption)
     */
    suspend fun hkdfDerive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /**
     * AES-256-CBC encryption. Platform MUST handle PKCS#7 padding internally.
     * DO NOT pre-pad — see CLAUDE.md "PKCS#7 double padding" bug.
     *
     * @return IV (16 bytes) + ciphertext
     */
    suspend fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * AES-256-CBC decryption with PKCS#7 unpadding handled by the platform.
     *
     * @param key 32-byte AES key
     * @param iv 16-byte IV
     * @param ciphertext The encrypted data (with PKCS#7 padding inside)
     * @return Plaintext with padding removed
     */
    suspend fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray

    /** HMAC-SHA256. Returns 32-byte MAC. */
    suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** Generate [length] bytes of cryptographically secure random data. */
    fun randomBytes(length: Int): ByteArray
}
