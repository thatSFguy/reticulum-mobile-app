package io.github.thatsfguy.reticulum.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the [KeychainIdentityVault] seal/unseal envelope — the part of
 * the iOS identity vault that closes audit 2026-05-13 HIGH-1. The Keychain
 * master-key fetch is injected with a fixed key here: a Kotlin/Native test
 * binary has no keychain-access entitlement, so the real bridge would
 * return errSecMissingEntitlement. The crypto envelope (AES-256-CBC +
 * HMAC-SHA256 over the real [IosCryptoProvider]) is what we're pinning.
 *
 * The Keychain key-management half (get-or-create, ThisDeviceOnly) can
 * only be exercised on a real device / hosted app and is verified manually.
 */
class KeychainIdentityVaultTest {

    private val masterA = ByteArray(32) { it.toByte() }
    private val masterB = ByteArray(32) { (255 - it).toByte() }

    private fun vault(master: ByteArray = masterA) =
        KeychainIdentityVault(crypto = IosCryptoProvider(), masterKeyProvider = { master })

    @Test
    fun roundTripsA32BytePrivateKey() = runTest {
        val v = vault()
        val key = ByteArray(32) { (it * 7).toByte() }
        val sealed = v.seal(key)
        assertFalse(sealed.contentEquals(key), "sealed blob must not equal the plaintext")
        assertEquals(key.toList(), v.unseal(sealed).toList())
    }

    @Test
    fun roundTripsVariousLengths() = runTest {
        val v = vault()
        for (n in intArrayOf(0, 1, 15, 16, 17, 31, 32, 64, 100)) {
            val data = ByteArray(n) { (it + n).toByte() }
            assertEquals(data.toList(), v.unseal(v.seal(data)).toList(), "round-trip failed for n=$n")
        }
    }

    @Test
    fun sealedLengthForA32ByteKeyIs97() = runTest {
        // The migration heuristic in IosIdentityRepo.load() relies on a
        // sealed blob being strictly longer than a 32-byte raw key. For a
        // 32-byte input: version(1) + iv(16) + mac(32) + ct(48, padded) = 97.
        val sealed = vault().seal(ByteArray(32))
        assertEquals(97, sealed.size)
        assertTrue(sealed.size > KeychainIdentityVault.RAW_PRIV_KEY_LEN)
    }

    @Test
    fun freshIvPerSealProducesDistinctBlobs() = runTest {
        val v = vault()
        val key = ByteArray(32) { 0x42 }
        val a = v.seal(key)
        val b = v.seal(key)
        assertFalse(a.contentEquals(b), "two seals of the same input must differ (random IV)")
        assertEquals(v.unseal(a).toList(), v.unseal(b).toList())
    }

    @Test
    fun tamperedCiphertextFailsUnseal() = runTest {
        val v = vault()
        val sealed = v.seal(ByteArray(32) { it.toByte() })
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { v.unseal(sealed) }
    }

    @Test
    fun tamperedVersionByteFailsUnseal() = runTest {
        val v = vault()
        val sealed = v.seal(ByteArray(32))
        sealed[0] = 0x02
        assertFailsWith<IllegalArgumentException> { v.unseal(sealed) }
    }

    @Test
    fun blobSealedByADifferentMasterKeyFailsUnseal() = runTest {
        val sealed = vault(masterA).seal(ByteArray(32) { it.toByte() })
        // A vault with a different master key derives a different MAC key,
        // so the authentication tag must not verify.
        assertFailsWith<IllegalArgumentException> { vault(masterB).unseal(sealed) }
    }

    @Test
    fun rejectsTruncatedBlob() = runTest {
        assertFailsWith<IllegalArgumentException> { vault().unseal(ByteArray(10)) }
    }
}
