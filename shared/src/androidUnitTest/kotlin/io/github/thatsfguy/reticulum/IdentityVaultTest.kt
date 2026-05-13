package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.IdentityVault
import io.github.thatsfguy.reticulum.crypto.PlaintextIdentityVault
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Contract tests for [IdentityVault] implementations. The
 * production Android implementation
 * ([AndroidKeystoreIdentityVault]) sits in androidApp/ and needs
 * an instrumented test runner (it talks to the real Android
 * Keystore); these tests cover the pass-through impl that ships
 * on iOS and that swaps in for unit-test rigs.
 *
 * Any new vault implementation must satisfy:
 *   1. seal(plain) returns a byte string distinct from plain (we
 *      don't enforce this here for the pass-through, only for
 *      real-crypto impls).
 *   2. unseal(seal(plain)) == plain.
 *   3. unseal on garbage / wrong-vault bytes throws.
 *
 * Audit reference: 2026-05-13 HIGH-1 follow-up.
 */
class IdentityVaultTest {

    private val vault: IdentityVault = PlaintextIdentityVault()

    @Test fun roundtrip_x25519PrivateKey() = runTest {
        val privKey = TestVectors.Alice.encPriv
        val sealed = vault.seal(privKey)
        val recovered = vault.unseal(sealed)
        assertContentEquals(privKey, recovered)
    }

    @Test fun roundtrip_ed25519PrivateKey() = runTest {
        val sigKey = TestVectors.Alice.sigPriv
        val sealed = vault.seal(sigKey)
        val recovered = vault.unseal(sealed)
        assertContentEquals(sigKey, recovered)
    }

    @Test fun roundtrip_ratchetKey() = runTest {
        val ratchet = TestVectors.Alice.ratchetPriv!!
        val sealed = vault.seal(ratchet)
        val recovered = vault.unseal(sealed)
        assertContentEquals(ratchet, recovered)
    }

    @Test fun sealReturnsIndependentCopy() = runTest {
        // The pass-through vault MUST defensive-copy on both sides
        // so a caller mutating the input plaintext doesn't poison
        // a previously-sealed BLOB, and mutating a recovered
        // plaintext doesn't poison the next unseal. Audit
        // reference: 2026-05-13 HIGH-1 — same hygiene rule the
        // KeystoreVault implementation has by virtue of running
        // bytes through a Cipher (which copies internally).
        val privKey = TestVectors.Alice.encPriv.copyOf()
        val sealed = vault.seal(privKey)
        privKey[0] = 0x00  // mutate caller's original
        val recovered = vault.unseal(sealed)
        // Recovered MUST match the ORIGINAL, not the mutated input.
        assertContentEquals(TestVectors.Alice.encPriv, recovered)
    }
}

/**
 * Contract test scaffolding any future IdentityVault impl can
 * extend by overriding [vault]. The Keystore-backed Android
 * implementation extends this in an instrumented test target
 * (separately, not in shared) so the contract stays bound to
 * the wider impl set.
 */
abstract class IdentityVaultContractTest {
    protected abstract val vault: IdentityVault

    @Test fun unsealFailsOnGarbage() = runTest {
        // Random bytes that did not come from this vault's seal()
        // must fail unseal — either via IllegalArgumentException
        // (malformed envelope) or an AEAD-tag mismatch (real
        // crypto impls). PlaintextIdentityVault skips this check
        // because its "sealed" bytes are just the plaintext.
        if (vault is PlaintextIdentityVault) return@runTest
        assertFailsWith<Throwable> {
            vault.unseal(ByteArray(64) { 0x42 })
        }
    }
}
