package io.github.thatsfguy.reticulum.crypto

import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-platform CryptoProvider contract. These tests run on both
 * Android (Bouncy Castle + JCA) and iOS (CryptoKit + CommonCrypto)
 * via the [testCryptoProvider] expect/actual.
 *
 * Test vectors are inlined from `reference/test-vectors.json` so the
 * common source set has no dependency on platform-specific test
 * fixture loaders.
 *
 * The [aesCbcEncryptReturnsJustCiphertextNotIvPrefixed] case is the
 * sentinel for the v1.0.2 iOS outbound-send bug: iOS implementation
 * was prepending `iv` to its return value, but TokenCrypto.kt
 * unconditionally builds the wire token as
 * `ephPub + iv + ciphertext + hmac` — so an IV-prefixed return
 * doubled the IV in the on-wire token and corrupted the recipient's
 * first plaintext block (LXMF source_hash).
 */
class CryptoProviderContractTest {

    private val crypto = testCryptoProvider()

    // Alice / Bob keys from reference/test-vectors.json
    private val aliceSigPriv = "30d13e0cb6171d246e09ecabb9018b75c1b6faef4a3e7484675990f47d4d51a4".hexToBytes()
    private val aliceSigPub  = "0677ce05ff0dcd9dcea720bdad2f88202b2c8dc2a4649699f1e41524624ea1ec".hexToBytes()
    private val aliceEncPriv = "859771582966c5ce9f4433dffca70e97cf6134c2d1ea7b8eccac85da24299009".hexToBytes()
    private val aliceEncPub  = "124d33d0e72f42b678d9f62c612f6c2d8d9a74a396e83cf72eb7c00b049bf706".hexToBytes()
    private val bobEncPriv   = "1b853674a9e6c99de171e53b9f6328f8b76f265ff281117c9f3112880c23f87a".hexToBytes()
    private val bobEncPub    = "5f206374fc1467f269725338cc61dd5c41e8a9d4b305a1d073b7f4149aacd342".hexToBytes()
    private val bobIdHash    = "78eb70f61b79245e6cfebd56ece88372".hexToBytes()

    // ---- AES-CBC contract -------------------------------------------------

    /**
     * Sentinel: aesCbcEncrypt returns ONLY the ciphertext — IV is NOT
     * prepended. TokenCrypto.kt:51-54 prepends the IV itself when
     * building the wire token; if the provider also prepends, the
     * token contains a duplicate IV and outbound LXMF is corrupted.
     */
    @Test fun aesCbcEncryptReturnsJustCiphertextNotIvPrefixed() = runTest {
        val key = ByteArray(32) { it.toByte() }
        val iv  = ByteArray(16) { (0x10 + it).toByte() }
        // 20-byte plaintext → PKCS#7-padded to one block over: 32 bytes.
        val pt = "x".repeat(20).encodeToByteArray()
        val ct = crypto.aesCbcEncrypt(key, iv, pt)
        assertEquals(
            32, ct.size,
            "aesCbcEncrypt must return only ciphertext (32 bytes for 20-byte input). " +
                "Got ${ct.size} — likely IV is being prepended (would be 48). " +
                "First 16 bytes: ${ct.copyOfRange(0, minOf(16, ct.size)).toHex()}",
        )
    }

    @Test fun aesCbcRoundTripSelfConsistent() = runTest {
        val key = ByteArray(32) { it.toByte() }
        val iv  = ByteArray(16) { (0x10 + it).toByte() }
        val pt = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val ct = crypto.aesCbcEncrypt(key, iv, pt)
        assertEquals(0, ct.size % 16, "ciphertext must be a multiple of 16")
        val back = crypto.aesCbcDecrypt(key, iv, ct)
        assertContentEquals(pt, back)
    }

    /** NIST SP 800-38A AES-256-CBC test vector F.2.5 single-block. */
    @Test fun aesCbcKnownAnswerSingleBlock() = runTest {
        val key = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".hexToBytes()
        val iv  = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val pt  = "6bc1bee22e409f96e93d7e117393172a".hexToBytes()
        val expectedCt = "f58c4c04d6e5f1ba779eabfb5f7bfbd6".hexToBytes()
        val ct = crypto.aesCbcEncrypt(key, iv, pt)
        // First 16 bytes are the data block; PKCS#7 adds a full pad block
        // because plaintext is exactly one block.
        assertContentEquals(
            expectedCt,
            ct.copyOfRange(0, 16),
            "AES-256-CBC first block must match NIST F.2.5",
        )
    }

    // ---- HMAC-SHA-256 -----------------------------------------------------

    /** RFC 4231 §4.2 test case 1. */
    @Test fun hmacSha256RfcVector() = runTest {
        val key  = "0b".repeat(20).hexToBytes()
        val data = "Hi There".encodeToByteArray()
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7".hexToBytes()
        assertContentEquals(expected, crypto.hmacSha256(key, data))
    }

    // ---- HKDF-SHA-256 -----------------------------------------------------

    /** RFC 5869 §A.1 basic SHA-256 test vector. */
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

    // ---- Curve25519 -------------------------------------------------------

    @Test fun ed25519PublicKeyMatchesAliceTestVector() {
        val derived = crypto.ed25519PublicKey(aliceSigPriv)
        assertContentEquals(
            aliceSigPub, derived,
            "Ed25519 public key derivation must produce the JS-reference test-vector pub for Alice",
        )
    }

    @Test fun ed25519SignVerifyRoundTrip() {
        val msg = "the announce signed_data goes here".encodeToByteArray()
        val sig = crypto.ed25519Sign(msg, aliceSigPriv)
        assertEquals(64, sig.size)
        assertTrue(crypto.ed25519Verify(sig, msg, aliceSigPub))
        assertTrue(!crypto.ed25519Verify(sig, "tampered".encodeToByteArray(), aliceSigPub))
    }

    @Test fun x25519PublicKeyMatchesAliceTestVector() {
        assertContentEquals(aliceEncPub, crypto.x25519PublicKey(aliceEncPriv))
    }

    @Test fun x25519SymmetricSharedSecret() {
        val s1 = crypto.x25519SharedSecret(aliceEncPriv, bobEncPub)
        val s2 = crypto.x25519SharedSecret(bobEncPriv, aliceEncPub)
        assertContentEquals(s1, s2)
    }

    // ---- TokenCrypto round-trip — the actual outbound LXMF send path ------

    /**
     * This is the test that catches the v1.0.2 iOS bug end-to-end:
     * encrypt as if sending opportunistic LXMF to Bob, then decrypt
     * with Bob's private key. If aesCbcEncrypt's contract is wrong,
     * decryption succeeds technically (HMAC happens to match because
     * both sender and receiver hash the same buffer) but the recovered
     * plaintext is corrupted in the first 16-byte block.
     */
    @Test fun tokenEncryptDecryptRoundTrip() = runTest {
        val token = TokenCrypto(crypto)
        val pt = "this is what would be the LXMF payload bytes (>= 16 chars)".encodeToByteArray()
        val wire = token.encrypt(pt, bobEncPub, bobIdHash)
        val back = token.decrypt(wire, listOf(bobEncPriv), bobIdHash)
        assertContentEquals(pt, back)
    }

    /** Same but for the link-context flavour used after a Link is up. */
    @Test fun tokenEncryptWithDerivedKeyRoundTrip() = runTest {
        val token = TokenCrypto(crypto)
        val derived = ByteArray(64) { (it * 7 + 11).toByte() }
        val pt = "link-context message body".encodeToByteArray()
        val wire = token.encryptWithDerivedKey(pt, derived)
        val back = token.decryptWithDerivedKey(wire, derived)
        assertContentEquals(pt, back)
    }
}
