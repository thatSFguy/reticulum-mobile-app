package io.github.thatsfguy.reticulum.platform

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

/**
 * iOS implementation of [CryptoProvider]. CommonCrypto for SHA-256,
 * HMAC, AES-CBC, and secure-random; RFC 5869 HMAC-SHA-256 HKDF
 * implemented in pure Kotlin on top of [hmacSha256]. The Curve25519
 * surface (Ed25519 signing, X25519 key agreement) bridges to CryptoKit
 * via the `rcr_*` functions in `libReticulumCrypto.a` (built from
 * `shared/iosCryptoBridge/ReticulumCrypto.swift`) — CommonCrypto has no
 * Curve25519 API, CryptoKit does.
 *
 * Matches the `AndroidCryptoProvider` contract byte-for-byte; the
 * round-trip vectors in `reference/test-vectors.json` are validated by
 * the shared [io.github.thatsfguy.reticulum.crypto] contract tests
 * running on the `iosSimulatorArm64Test` target.
 *
 * AES note (CLAUDE.md "Key bugs" §2): we pass plaintext as-is to
 * `CCCrypt` with `kCCOptionPKCS7Padding`. Do NOT pre-pad — that's
 * exactly the double-padding bug we hit on the JS side and Java's
 * AES/CBC/PKCS5Padding shares this contract.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCryptoProvider : CryptoProvider {

    // ---- SHA-256 + truncated hash ---------------------------------------

    override suspend fun sha256(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        if (data.isEmpty()) {
            // CC_SHA256 takes a non-null pointer even with len=0 in
            // practice, but we route around it for clarity.
            out.usePinned { outPin ->
                CC_SHA256(null, 0u, outPin.addressOf(0).reinterpret())
            }
        } else {
            data.usePinned { dataPin ->
                out.usePinned { outPin ->
                    CC_SHA256(
                        dataPin.addressOf(0),
                        data.size.convert(),
                        outPin.addressOf(0).reinterpret(),
                    )
                }
            }
        }
        return out
    }

    override suspend fun truncatedHash(data: ByteArray, length: Int): ByteArray {
        require(length in 1..32) { "truncatedHash length must be 1..32, got $length" }
        return sha256(data).copyOfRange(0, length)
    }

    // ---- HMAC-SHA-256 ---------------------------------------------------

    override suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        // CCHmac doesn't accept null buffers even for empty inputs, so
        // hand it a pinned reference into a 1-byte stub when needed.
        val keyToPin = if (key.isEmpty()) ByteArray(1) else key
        val dataToPin = if (data.isEmpty()) ByteArray(1) else data
        keyToPin.usePinned { keyPin ->
            dataToPin.usePinned { dataPin ->
                out.usePinned { outPin ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        keyPin.addressOf(0), key.size.convert(),
                        dataPin.addressOf(0), data.size.convert(),
                        outPin.addressOf(0),
                    )
                }
            }
        }
        return out
    }

    // ---- HKDF-SHA-256 (RFC 5869, pure Kotlin on top of HMAC) ------------

    override suspend fun hkdfDerive(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length > 0) { "hkdfDerive length must be positive, got $length" }
        require(length <= 255 * 32) { "hkdfDerive length must be <= 255 * HashLen (8160) for SHA-256" }

        // Step 1 — Extract: PRK = HMAC-SHA256(salt, IKM).
        // Per §2.2, an empty salt is treated as HashLen zero bytes.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(effectiveSalt, ikm)

        // Step 2 — Expand: T(0) = empty, T(i) = HMAC-SHA256(PRK, T(i-1) || info || i).
        val out = ByteArray(length)
        var produced = 0
        var prevBlock = ByteArray(0)
        var counter = 1
        while (produced < length) {
            val input = ByteArray(prevBlock.size + info.size + 1)
            prevBlock.copyInto(input, 0)
            info.copyInto(input, prevBlock.size)
            input[input.size - 1] = counter.toByte()
            prevBlock = hmacSha256(prk, input)
            val take = minOf(prevBlock.size, length - produced)
            prevBlock.copyInto(out, produced, 0, take)
            produced += take
            counter += 1
        }
        return out
    }

    // ---- AES-256-CBC, PKCS#7 padding handled by CCCrypt -----------------

    override suspend fun aesCbcEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }
        require(iv.size == 16) { "AES IV must be 16 bytes, got ${iv.size}" }

        // PKCS#7 padding can add up to one full block (16B) past the
        // plaintext length; CCCrypt won't write more than that.
        val out = ByteArray(plaintext.size + 16)
        val moved = ccCrypt(
            op = kCCEncrypt,
            key = key,
            iv = iv,
            input = plaintext,
            output = out,
        )
        // Contract (matches AndroidCryptoProvider): return ONLY the
        // ciphertext. TokenCrypto is the sole caller and prepends the
        // IV itself when building the wire token. Prepending the IV
        // here doubles it on the wire and corrupts the recipient's
        // first plaintext block (LXMF source_hash) — the v1.0.2
        // outbound-send bug.
        return out.copyOf(moved)
    }

    override suspend fun aesCbcDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }
        require(iv.size == 16) { "AES IV must be 16 bytes, got ${iv.size}" }

        val out = ByteArray(ciphertext.size)
        val moved = ccCrypt(
            op = kCCDecrypt,
            key = key,
            iv = iv,
            input = ciphertext,
            output = out,
        )
        return out.copyOf(moved)
    }

    private fun ccCrypt(
        op: UInt,
        key: ByteArray,
        iv: ByteArray,
        input: ByteArray,
        output: ByteArray,
    ): Int = memScoped {
        val movedVar = alloc<size_tVar>()
        val rc = key.usePinned { keyPin ->
            iv.usePinned { ivPin ->
                input.usePinned { inPin ->
                    output.usePinned { outPin ->
                        CCCrypt(
                            op,
                            kCCAlgorithmAES,
                            kCCOptionPKCS7Padding,
                            keyPin.addressOf(0), key.size.convert(),
                            ivPin.addressOf(0),
                            // Empty input is legal (decrypt of an empty
                            // ciphertext returns empty plaintext); pin a
                            // 1-byte stub to avoid a null pointer.
                            if (input.isEmpty()) null else inPin.addressOf(0),
                            input.size.convert(),
                            outPin.addressOf(0), output.size.convert(),
                            movedVar.ptr,
                        )
                    }
                }
            }
        }
        check(rc == kCCSuccess) { "CCCrypt failed: rc=$rc" }
        movedVar.value.toInt()
    }

    // ---- Secure random --------------------------------------------------

    override fun randomBytes(length: Int): ByteArray {
        require(length >= 0) { "randomBytes length must be non-negative, got $length" }
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        out.usePinned { pin ->
            val rc = SecRandomCopyBytes(
                kSecRandomDefault,
                length.convert(),
                pin.addressOf(0),
            )
            check(rc == 0) { "SecRandomCopyBytes failed: $rc" }
        }
        return out
    }

    // ---- Curve25519 via the CryptoKit Swift wrapper ---------------------
    //
    // The cinterop'd functions live in libReticulumCrypto.a (built from
    // shared/iosCryptoBridge/ReticulumCrypto.swift). Each one wraps a
    // CryptoKit Curve25519 API as a C-callable function — see the
    // ReticulumCrypto.swift comments for the convention and rcr_*
    // signatures.

    override fun generateX25519PrivateKey(): ByteArray {
        val out = ByteArray(32)
        out.usePinned { p ->
            io.github.thatsfguy.reticulum.crypto.cinterop.rcr_x25519_keygen(
                p.addressOf(0).reinterpret()
            )
        }
        return out
    }

    override fun x25519PublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) {
            "X25519 private key must be 32 bytes, got ${privateKey.size}"
        }
        val out = ByteArray(32)
        val rc = privateKey.usePinned { privPin ->
            out.usePinned { outPin ->
                io.github.thatsfguy.reticulum.crypto.cinterop.rcr_x25519_pubkey(
                    privPin.addressOf(0).reinterpret(),
                    outPin.addressOf(0).reinterpret(),
                )
            }
        }
        check(rc == 0) { "rcr_x25519_pubkey failed: rc=$rc" }
        return out
    }

    override fun x25519SharedSecret(
        ourPrivateKey: ByteArray,
        theirPublicKey: ByteArray,
    ): ByteArray {
        require(ourPrivateKey.size == 32) {
            "X25519 private key must be 32 bytes, got ${ourPrivateKey.size}"
        }
        require(theirPublicKey.size == 32) {
            "X25519 public key must be 32 bytes, got ${theirPublicKey.size}"
        }
        val out = ByteArray(32)
        val rc = ourPrivateKey.usePinned { privPin ->
            theirPublicKey.usePinned { pubPin ->
                out.usePinned { outPin ->
                    io.github.thatsfguy.reticulum.crypto.cinterop.rcr_x25519_shared_secret(
                        privPin.addressOf(0).reinterpret(),
                        pubPin.addressOf(0).reinterpret(),
                        outPin.addressOf(0).reinterpret(),
                    )
                }
            }
        }
        check(rc == 0) { "rcr_x25519_shared_secret failed: rc=$rc" }
        return out
    }

    override fun generateEd25519PrivateKey(): ByteArray {
        val out = ByteArray(32)
        out.usePinned { p ->
            io.github.thatsfguy.reticulum.crypto.cinterop.rcr_ed25519_keygen(
                p.addressOf(0).reinterpret()
            )
        }
        return out
    }

    override fun ed25519PublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) {
            "Ed25519 private key must be 32 bytes, got ${privateKey.size}"
        }
        val out = ByteArray(32)
        val rc = privateKey.usePinned { privPin ->
            out.usePinned { outPin ->
                io.github.thatsfguy.reticulum.crypto.cinterop.rcr_ed25519_pubkey(
                    privPin.addressOf(0).reinterpret(),
                    outPin.addressOf(0).reinterpret(),
                )
            }
        }
        check(rc == 0) { "rcr_ed25519_pubkey failed: rc=$rc" }
        return out
    }

    override fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) {
            "Ed25519 private key must be 32 bytes, got ${privateKey.size}"
        }
        val out = ByteArray(64)
        // Pin a non-empty stub when message is empty — Swift's
        // Data(bytes:count:) tolerates count=0 with any pointer, but
        // some Kotlin/Native versions reject pinning a 0-byte array.
        val msgToPin = if (message.isEmpty()) ByteArray(1) else message
        val rc = privateKey.usePinned { privPin ->
            msgToPin.usePinned { msgPin ->
                out.usePinned { outPin ->
                    io.github.thatsfguy.reticulum.crypto.cinterop.rcr_ed25519_sign(
                        privPin.addressOf(0).reinterpret(),
                        msgPin.addressOf(0).reinterpret(),
                        message.size,
                        outPin.addressOf(0).reinterpret(),
                    )
                }
            }
        }
        check(rc == 0) { "rcr_ed25519_sign failed: rc=$rc" }
        return out
    }

    override fun ed25519Verify(
        signature: ByteArray,
        message: ByteArray,
        publicKey: ByteArray,
    ): Boolean {
        require(signature.size == 64) {
            "Ed25519 signature must be 64 bytes, got ${signature.size}"
        }
        require(publicKey.size == 32) {
            "Ed25519 public key must be 32 bytes, got ${publicKey.size}"
        }
        val msgToPin = if (message.isEmpty()) ByteArray(1) else message
        val rc = signature.usePinned { sigPin ->
            msgToPin.usePinned { msgPin ->
                publicKey.usePinned { pubPin ->
                    io.github.thatsfguy.reticulum.crypto.cinterop.rcr_ed25519_verify(
                        sigPin.addressOf(0).reinterpret(),
                        msgPin.addressOf(0).reinterpret(),
                        message.size,
                        pubPin.addressOf(0).reinterpret(),
                    )
                }
            }
        }
        // 1 = valid, 0 = invalid, -1 = pub key didn't parse. Treat the
        // parse-failure case as "invalid" — same outcome from the
        // caller's perspective and we don't want to surface a malformed
        // public key as an exception in a verify-during-handshake path.
        return rc == 1
    }
}
