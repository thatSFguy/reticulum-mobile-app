package io.github.thatsfguy.reticulum.crypto

import io.github.thatsfguy.reticulum.store.StoredIdentity

/**
 * Pack / unpack a [StoredIdentity] for off-device backup with passphrase
 * encryption. Lets the user move their RNS identity between phones,
 * back it up before reinstalling the app, etc.
 *
 * ## Wire format (binary)
 *
 * ```
 * magic        4 bytes   "RMID"
 * version      1 byte    0x01
 * salt        16 bytes   random per export
 * iterations   4 bytes   PBKDF2 iteration count, big-endian
 * iv          16 bytes   AES-CBC IV, random per export
 * hmac        32 bytes   HMAC-SHA256 over salt || iterations || iv || ciphertext
 * ct_len       2 bytes   ciphertext length, big-endian
 * ciphertext  ct_len B   AES-256-CBC encrypted plaintext (PKCS#7 padded)
 * ```
 *
 * Total fixed overhead: 75 bytes. Plaintext is 65 or 98 bytes (see
 * below). After AES-CBC PKCS#7 padding the ciphertext rounds up to the
 * next 16-byte boundary, so the full archive is ~155–170 bytes —
 * easily fits in any text-share medium.
 *
 * ## Plaintext format (inside the AES-CBC envelope)
 *
 * ```
 * pt_version   1 byte    0x01
 * enc_priv    32 bytes   X25519 private (RNS encryption key)
 * sig_priv    32 bytes   Ed25519 private (RNS signing key)
 * has_ratchet  1 byte    0x00 or 0x01
 * ratchet_priv 32 bytes  optional, present only if has_ratchet == 0x01
 * ```
 *
 * ## Crypto
 *
 * - **KDF**: PBKDF2-HMAC-SHA256(passphrase, salt, iterations) →
 *   64-byte master key.
 * - **Key split**: HKDF-Expand(master, info=`"reticulum-mobile-identity-export-v1"`,
 *   len=64) → signing_key(32) || encryption_key(32). Salt is empty for
 *   the HKDF stage because the master is already PBKDF2-stretched
 *   per-export.
 * - **Cipher**: AES-256-CBC with random IV. Token-style.
 * - **Integrity**: HMAC-SHA256 over (salt || iterations_be || iv || ct).
 *   Verified BEFORE decryption (encrypt-then-MAC) to prevent
 *   padding-oracle attacks. Same construction as our LXMF
 *   [TokenCrypto].
 *
 * Wrong passphrase → HMAC fails → [unpack] returns
 * `Result.failure`. Tampering with any byte of salt / iterations / IV /
 * ciphertext → HMAC fails identically.
 *
 * ## Iteration count
 *
 * The pure-Kotlin PBKDF2 helper here uses [CryptoProvider.hmacSha256]
 * per iteration. JCA-backed HMAC on Android is fast enough that 600k
 * iterations finish in ~5–10 seconds on modern phones — acceptable for
 * a one-time export/import operation. The default is exposed as
 * [DEFAULT_PBKDF2_ITERATIONS] and overridable for tests / future
 * tuning. Iteration count is encoded in the archive so a future bump
 * doesn't break existing backups.
 */
object IdentityArchive {

    private const val MAGIC_LEN = 4
    private const val VERSION_LEN = 1
    private const val SALT_LEN = 16
    private const val ITERATIONS_LEN = 4
    private const val IV_LEN = 16
    private const val HMAC_LEN = 32
    private const val CT_LEN_LEN = 2

    private const val HEADER_LEN = MAGIC_LEN + VERSION_LEN + SALT_LEN + ITERATIONS_LEN +
        IV_LEN + HMAC_LEN + CT_LEN_LEN  // 75

    private val MAGIC = byteArrayOf(
        'R'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte(),
    )
    private const val VERSION: Byte = 0x01
    private const val PT_VERSION: Byte = 0x01

    /** OWASP 2023+ recommended minimum for PBKDF2-SHA256. */
    const val DEFAULT_PBKDF2_ITERATIONS: Int = 600_000

    private const val HKDF_INFO = "reticulum-mobile-identity-export-v1"

    /** Pack [identity] into a passphrase-encrypted archive blob. */
    suspend fun pack(
        identity: StoredIdentity,
        passphrase: String,
        crypto: CryptoProvider,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must be non-empty" }
        require(iterations > 0) { "iterations must be positive" }
        require(identity.encPrivKey.size == 32) { "encPrivKey must be 32 bytes" }
        require(identity.sigPrivKey.size == 32) { "sigPrivKey must be 32 bytes" }
        identity.ratchetPrivKey?.let {
            require(it.size == 32) { "ratchetPrivKey must be 32 bytes" }
        }

        val salt = crypto.randomBytes(SALT_LEN)
        val iv = crypto.randomBytes(IV_LEN)

        val (signingKey, encryptionKey) = deriveKeys(passphrase, salt, iterations, crypto)
        val plaintext = packPlaintext(identity)
        val ciphertext = crypto.aesCbcEncrypt(encryptionKey, iv, plaintext)

        val iterationsBytes = intBE(iterations)
        val ctLenBytes = u16BE(ciphertext.size)
        val hmacInput = salt + iterationsBytes + iv + ciphertext
        val hmac = crypto.hmacSha256(signingKey, hmacInput)

        return MAGIC + byteArrayOf(VERSION) + salt + iterationsBytes + iv + hmac +
            ctLenBytes + ciphertext
    }

    /**
     * Unpack a [pack]ed archive. Returns [Result.failure] on:
     *  - malformed/truncated archive (bad magic, length, etc.)
     *  - HMAC mismatch (wrong passphrase OR tampered bytes — same wire
     *    error to avoid leaking which one happened)
     *  - corrupted plaintext after decrypt
     */
    suspend fun unpack(
        archive: ByteArray,
        passphrase: String,
        crypto: CryptoProvider,
    ): Result<StoredIdentity> = runCatching {
        require(archive.size >= HEADER_LEN) { "archive too short (${archive.size} < $HEADER_LEN)" }

        var off = 0
        val magic = archive.copyOfRange(off, off + MAGIC_LEN); off += MAGIC_LEN
        require(magic.contentEquals(MAGIC)) { "bad magic — not a Reticulum identity archive" }

        val version = archive[off]; off += VERSION_LEN
        require(version == VERSION) { "unsupported archive version 0x${version.toInt() and 0xFF}" }

        val salt = archive.copyOfRange(off, off + SALT_LEN); off += SALT_LEN
        val iterations = readIntBE(archive, off); off += ITERATIONS_LEN
        require(iterations > 0) { "non-positive iteration count" }

        val iv = archive.copyOfRange(off, off + IV_LEN); off += IV_LEN
        val hmac = archive.copyOfRange(off, off + HMAC_LEN); off += HMAC_LEN
        val ctLen = readU16BE(archive, off); off += CT_LEN_LEN

        require(archive.size == off + ctLen) { "ciphertext length mismatch" }
        val ciphertext = archive.copyOfRange(off, off + ctLen)

        val (signingKey, encryptionKey) = deriveKeys(passphrase, salt, iterations, crypto)

        val expectedHmac = crypto.hmacSha256(signingKey, salt + intBE(iterations) + iv + ciphertext)
        require(constantTimeEquals(hmac, expectedHmac)) { "HMAC mismatch — wrong passphrase or tampered" }

        val plaintext = crypto.aesCbcDecrypt(encryptionKey, iv, ciphertext)
        parsePlaintext(plaintext)
    }

    private suspend fun deriveKeys(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
        crypto: CryptoProvider,
    ): Pair<ByteArray, ByteArray> {
        val passBytes = passphrase.encodeToByteArray()
        val master = pbkdf2HmacSha256(passBytes, salt, iterations, dkLen = 64, crypto)
        val derived = crypto.hkdfDerive(
            ikm = master,
            salt = ByteArray(0),
            info = HKDF_INFO.encodeToByteArray(),
            length = 64,
        )
        return derived.copyOfRange(0, 32) to derived.copyOfRange(32, 64)
    }

    private fun packPlaintext(identity: StoredIdentity): ByteArray {
        val hasRatchet = identity.ratchetPrivKey != null
        val size = 1 + 32 + 32 + 1 + (if (hasRatchet) 32 else 0)
        val out = ByteArray(size)
        var o = 0
        out[o] = PT_VERSION; o += 1
        identity.encPrivKey.copyInto(out, o); o += 32
        identity.sigPrivKey.copyInto(out, o); o += 32
        out[o] = if (hasRatchet) 0x01 else 0x00; o += 1
        if (hasRatchet) identity.ratchetPrivKey!!.copyInto(out, o)
        return out
    }

    private fun parsePlaintext(plaintext: ByteArray): StoredIdentity {
        require(plaintext.size >= 1 + 32 + 32 + 1) { "plaintext too short" }
        require(plaintext[0] == PT_VERSION) { "unsupported plaintext version" }
        val encPriv = plaintext.copyOfRange(1, 33)
        val sigPriv = plaintext.copyOfRange(33, 65)
        val hasRatchet = plaintext[65]
        val ratchetPriv: ByteArray? = when (hasRatchet.toInt() and 0xFF) {
            0x00 -> {
                require(plaintext.size == 66) { "stray bytes after no-ratchet plaintext" }
                null
            }
            0x01 -> {
                require(plaintext.size == 66 + 32) { "ratchet flag set but plaintext length wrong" }
                plaintext.copyOfRange(66, 98)
            }
            else -> error("invalid has_ratchet flag 0x${hasRatchet.toInt() and 0xFF}")
        }
        return StoredIdentity(
            encPrivKey = encPriv,
            sigPrivKey = sigPriv,
            ratchetPrivKey = ratchetPriv,
        )
    }

    /**
     * Pure-Kotlin PBKDF2-HMAC-SHA256 (RFC 2898 §5.2). Calls the
     * provider's [CryptoProvider.hmacSha256] [iterations] times per
     * 32-byte output block. Avoids needing a per-platform PBKDF2
     * binding at the cost of ~5–10s for production-grade iteration
     * counts on a modern phone — acceptable for a one-time
     * export/import.
     */
    private suspend fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int,
        crypto: CryptoProvider,
    ): ByteArray {
        val hLen = 32  // HMAC-SHA256 output bytes
        val out = ByteArray(dkLen)
        val blocks = (dkLen + hLen - 1) / hLen
        var written = 0
        for (i in 1..blocks) {
            var u = crypto.hmacSha256(password, salt + intBE(i))
            val t = u.copyOf()
            for (j in 2..iterations) {
                u = crypto.hmacSha256(password, u)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val take = minOf(hLen, dkLen - written)
            t.copyInto(out, written, 0, take)
            written += take
        }
        return out
    }

    private fun intBE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun u16BE(v: Int): ByteArray {
        require(v in 0..0xFFFF) { "u16 out of range: $v" }
        return byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    private fun readU16BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /**
     * Constant-time byte-array equality. `ByteArray.contentEquals` short-
     * circuits on first mismatch which leaks timing for HMAC compare.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
