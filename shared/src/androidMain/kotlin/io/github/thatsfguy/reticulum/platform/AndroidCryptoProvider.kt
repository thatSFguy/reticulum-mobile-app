package io.github.thatsfguy.reticulum.platform

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android implementation of [CryptoProvider]. JCA for SHA-256, AES-CBC and
 * HMAC-SHA256; Bouncy Castle for X25519, Ed25519 and HKDF-SHA256.
 *
 * AES is configured as "AES/CBC/PKCS5Padding" — JCA does PKCS#7 padding
 * automatically. We pass the plaintext as-is. See CLAUDE.md "Key bugs" §2.
 */
class AndroidCryptoProvider : CryptoProvider {

    private val random = SecureRandom()

    override suspend fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override suspend fun truncatedHash(data: ByteArray, length: Int): ByteArray =
        sha256(data).copyOfRange(0, length)

    override fun generateX25519PrivateKey(): ByteArray {
        val priv = X25519PrivateKeyParameters(random)
        return priv.encoded
    }

    override fun x25519PublicKey(privateKey: ByteArray): ByteArray {
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        return priv.generatePublicKey().encoded
    }

    override fun x25519SharedSecret(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(ourPrivateKey, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPublicKey, 0), out, 0)
        return out
    }

    override fun generateEd25519PrivateKey(): ByteArray {
        val priv = Ed25519PrivateKeyParameters(random)
        return priv.encoded
    }

    override fun ed25519PublicKey(privateKey: ByteArray): ByteArray {
        val priv = Ed25519PrivateKeyParameters(privateKey, 0)
        return priv.generatePublicKey().encoded
    }

    override fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        val priv = Ed25519PrivateKeyParameters(privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    override fun ed25519Verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val pub = Ed25519PublicKeyParameters(publicKey, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pub)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun hkdfDerive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    override suspend fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }
        require(iv.size == 16) { "AES-CBC IV must be 16 bytes, got ${iv.size}" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }

    override suspend fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }
        require(iv.size == 16) { "AES-CBC IV must be 16 bytes, got ${iv.size}" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    override suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun randomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        random.nextBytes(out)
        return out
    }
}

/** Convenience factory. */
fun androidCryptoProvider(): CryptoProvider = AndroidCryptoProvider()
