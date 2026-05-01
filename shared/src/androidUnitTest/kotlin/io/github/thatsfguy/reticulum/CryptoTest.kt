package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoTest {

    private val crypto = TestVectors.crypto

    @Test fun sha256Empty() = runTest {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            crypto.sha256(ByteArray(0)).toHex(),
        )
    }

    @Test fun x25519PublicKeyMatchesAlicePriv() {
        val derived = crypto.x25519PublicKey(TestVectors.Alice.encPriv)
        assertContentEquals(TestVectors.Alice.encPub, derived)
    }

    @Test fun x25519PublicKeyMatchesBobPriv() {
        val derived = crypto.x25519PublicKey(TestVectors.Bob.encPriv)
        assertContentEquals(TestVectors.Bob.encPub, derived)
    }

    @Test fun ed25519PublicKeyMatchesAliceSigPriv() {
        val derived = crypto.ed25519PublicKey(TestVectors.Alice.sigPriv)
        assertContentEquals(TestVectors.Alice.sigPub, derived)
    }

    @Test fun ed25519SignVerifyRoundTrip() {
        val msg = "hello world".encodeToByteArray()
        val sig = crypto.ed25519Sign(msg, TestVectors.Alice.sigPriv)
        assertEquals(64, sig.size)
        assertTrue(crypto.ed25519Verify(sig, msg, TestVectors.Alice.sigPub))
        // Tampered message should not verify
        assertTrue(!crypto.ed25519Verify(sig, "hello worle".encodeToByteArray(), TestVectors.Alice.sigPub))
    }

    @Test fun x25519DhSymmetric() {
        val s1 = crypto.x25519SharedSecret(TestVectors.Alice.encPriv, TestVectors.Bob.encPub)
        val s2 = crypto.x25519SharedSecret(TestVectors.Bob.encPriv, TestVectors.Alice.encPub)
        assertContentEquals(s1, s2)
    }

    /** RFC 5869 test vector A.1 — basic SHA-256 HKDF. */
    @Test fun hkdfRfc5869() = runTest {
        val ikm  = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes()
        val salt = "000102030405060708090a0b0c".hexToBytes()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes()
        val expected = ("3cb25f25faacd57a90434f64d0362f2a" +
                        "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                        "34007208d5b887185865").hexToBytes()
        val out = crypto.hkdfDerive(ikm, salt, info, 42)
        assertContentEquals(expected, out)
    }

    @Test fun aesCbcRoundTrip() = runTest {
        val key = ByteArray(32) { it.toByte() }
        val iv  = ByteArray(16) { (16 + it).toByte() }
        val pt  = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val ct  = crypto.aesCbcEncrypt(key, iv, pt)
        // Ciphertext is padded to a multiple of 16
        assertEquals(0, ct.size % 16)
        val back = crypto.aesCbcDecrypt(key, iv, ct)
        assertContentEquals(pt, back)
    }

    @Test fun hmacSha256Known() = runTest {
        // RFC 4231 §4.2 test case 1
        val key  = "0b".repeat(20).hexToBytes()
        val data = "Hi There".encodeToByteArray()
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7".hexToBytes()
        assertContentEquals(expected, crypto.hmacSha256(key, data))
    }
}
