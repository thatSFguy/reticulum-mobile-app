package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.IdentityArchive
import io.github.thatsfguy.reticulum.store.StoredIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [IdentityArchive] — passphrase-encrypted identity export.
 *
 * The fixture identity (Alice) is the canonical test vector from
 * [TestVectors] so any cross-test cross-references stay valid.
 *
 * [IdentityArchive.DEFAULT_PBKDF2_ITERATIONS] is 600k for production
 * security but tests use a much smaller value (1k) so the suite stays
 * fast — PBKDF2's iteration count is independent of the security
 * properties under test (round-trip, tamper detection, wrong-passphrase
 * rejection). The production iteration value is exercised separately
 * via the engine-level integration test.
 */
class IdentityArchiveTest {

    private val crypto = TestVectors.crypto
    private val testIterations = 1_000

    private val aliceWithRatchet = StoredIdentity(
        encPrivKey = TestVectors.Alice.encPriv,
        sigPrivKey = TestVectors.Alice.sigPriv,
        ratchetPrivKey = TestVectors.Alice.ratchetPriv,
    )

    private val aliceWithoutRatchet = StoredIdentity(
        encPrivKey = TestVectors.Alice.encPriv,
        sigPrivKey = TestVectors.Alice.sigPriv,
        ratchetPrivKey = null,
    )

    @Test fun roundtrip_withRatchet() = runTest {
        val passphrase = "correct horse battery staple"
        val archive = IdentityArchive.pack(aliceWithRatchet, passphrase, crypto, testIterations)
        val recovered = IdentityArchive.unpack(archive, passphrase, crypto).getOrThrow()

        assertContentEquals(aliceWithRatchet.encPrivKey, recovered.encPrivKey)
        assertContentEquals(aliceWithRatchet.sigPrivKey, recovered.sigPrivKey)
        assertContentEquals(aliceWithRatchet.ratchetPrivKey, recovered.ratchetPrivKey)
    }

    @Test fun roundtrip_withoutRatchet() = runTest {
        val passphrase = "no ratchet here"
        val archive = IdentityArchive.pack(aliceWithoutRatchet, passphrase, crypto, testIterations)
        val recovered = IdentityArchive.unpack(archive, passphrase, crypto).getOrThrow()

        assertContentEquals(aliceWithoutRatchet.encPrivKey, recovered.encPrivKey)
        assertContentEquals(aliceWithoutRatchet.sigPrivKey, recovered.sigPrivKey)
        assertEquals(null, recovered.ratchetPrivKey)
    }

    @Test fun unpack_wrongPassphrase_fails() = runTest {
        val archive = IdentityArchive.pack(aliceWithRatchet, "right one", crypto, testIterations)
        val result = IdentityArchive.unpack(archive, "wrong one", crypto)
        assertTrue(result.isFailure, "wrong passphrase must fail")
    }

    @Test fun unpack_emptyPassphrase_fails() = runTest {
        // Empty passphrase is a UX footgun — silently accepting it would
        // let users "encrypt" their identity to nothing. Pack must reject
        // it explicitly so the export UI can show a meaningful error.
        assertFailsWith<IllegalArgumentException> {
            IdentityArchive.pack(aliceWithRatchet, "", crypto, testIterations)
        }
    }

    @Test fun unpack_tamperedCiphertext_fails() = runTest {
        val passphrase = "test"
        val archive = IdentityArchive.pack(aliceWithRatchet, passphrase, crypto, testIterations)
        // Flip a bit somewhere in the ciphertext region (last few bytes
        // of the archive are guaranteed to be ciphertext for any
        // non-empty plaintext).
        val tampered = archive.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val result = IdentityArchive.unpack(tampered, passphrase, crypto)
        assertTrue(result.isFailure, "tampered ciphertext must fail HMAC")
    }

    @Test fun unpack_tamperedIv_fails() = runTest {
        // IV lives at a known offset (after magic+version+salt+iterations).
        // Tampering it breaks the HMAC because we sign over salt || iv ||
        // ciphertext, so unpack must reject — which proves we're encrypt-
        // then-MAC and the IV is part of the integrity envelope.
        val passphrase = "test"
        val archive = IdentityArchive.pack(aliceWithRatchet, passphrase, crypto, testIterations)
        val ivOffset = 4 + 1 + 16 + 4   // magic(4) + version(1) + salt(16) + iterations(4)
        val tampered = archive.copyOf().also { it[ivOffset] = (it[ivOffset].toInt() xor 0x01).toByte() }
        val result = IdentityArchive.unpack(tampered, passphrase, crypto)
        assertTrue(result.isFailure, "tampered IV must fail HMAC")
    }

    @Test fun unpack_garbageBytes_fails() = runTest {
        val result = IdentityArchive.unpack(ByteArray(200) { it.toByte() }, "anything", crypto)
        assertTrue(result.isFailure, "non-archive bytes must fail")
    }

    @Test fun unpack_truncated_fails() = runTest {
        val archive = IdentityArchive.pack(aliceWithRatchet, "test", crypto, testIterations)
        val truncated = archive.copyOfRange(0, archive.size - 10)
        val result = IdentityArchive.unpack(truncated, "test", crypto)
        assertTrue(result.isFailure, "truncated archive must fail")
    }

    @Test fun pack_isRandomized() = runTest {
        // Two pack() calls on the same input must produce different
        // bytes (different salt + IV). Otherwise an attacker who sees
        // two backups can confirm they're the same identity by byte
        // comparison.
        val passphrase = "same"
        val a = IdentityArchive.pack(aliceWithRatchet, passphrase, crypto, testIterations)
        val b = IdentityArchive.pack(aliceWithRatchet, passphrase, crypto, testIterations)
        assertNotEquals(a.toList(), b.toList(), "salt + IV must be random")

        // Both still unpack to the same plaintext.
        val ra = IdentityArchive.unpack(a, passphrase, crypto).getOrThrow()
        val rb = IdentityArchive.unpack(b, passphrase, crypto).getOrThrow()
        assertContentEquals(ra.encPrivKey, rb.encPrivKey)
        assertContentEquals(ra.sigPrivKey, rb.sigPrivKey)
    }

    @Test fun pack_magicHeader() = runTest {
        // Self-describing magic so a future format upgrade can fork on
        // the version byte without ambiguity. Lock the prefix here so
        // a casual refactor doesn't silently change the wire format.
        val archive = IdentityArchive.pack(aliceWithRatchet, "test", crypto, testIterations)
        assertEquals('R'.code.toByte(), archive[0])
        assertEquals('M'.code.toByte(), archive[1])
        assertEquals('I'.code.toByte(), archive[2])
        assertEquals('D'.code.toByte(), archive[3])
        assertEquals(0x01.toByte(), archive[4], "version 1")
    }

    @Test fun roundtrip_preserves_destination_hash() = runTest {
        // End-to-end regression for tester report 2026-05-12: "exported
        // identity, upgraded the iOS app, re-imported, but the destination
        // hash is different." If the priv keys round-trip correctly AND
        // the crypto provider is deterministic (same priv → same pub),
        // the derived destination hash MUST be identical. Catching this
        // here means if a bug ever creeps into pack/unpack, public-key
        // derivation, or destination-hash computation, the suite fails
        // loudly instead of silently shipping a regenerated-identity bug.
        //
        // Two flows checked:
        //   (a) Generate a fresh random identity (matches first-run UX),
        //       export, unpack, recompute destHash, expect equality.
        //   (b) Same with the canonical Alice vectors so the assertion
        //       runs against known bytes too (covers any sign-extension
        //       / encoding regression in the public-key derivation).

        // (a) — freshly-generated identity
        val fresh = io.github.thatsfguy.reticulum.crypto.Identity(crypto).also { it.generate() }
        val freshStored = StoredIdentity(
            encPrivKey = fresh.encPrivKey!!,
            sigPrivKey = fresh.sigPrivKey!!,
            ratchetPrivKey = fresh.ratchetPrivKey,
        )
        val originalDestHash = io.github.thatsfguy.reticulum.crypto.computeDestinationHash(
            crypto, "lxmf.delivery", fresh.hash!!
        )
        val archive = IdentityArchive.pack(freshStored, "round-trip", crypto, testIterations)
        val recovered = IdentityArchive.unpack(archive, "round-trip", crypto).getOrThrow()
        val recoveredIdentity = io.github.thatsfguy.reticulum.crypto.Identity(crypto).also {
            it.loadFromPrivateKeys(recovered.encPrivKey, recovered.sigPrivKey, recovered.ratchetPrivKey)
        }
        val recoveredDestHash = io.github.thatsfguy.reticulum.crypto.computeDestinationHash(
            crypto, "lxmf.delivery", recoveredIdentity.hash!!
        )
        assertContentEquals(
            originalDestHash, recoveredDestHash,
            "freshly-generated identity must round-trip with the same destination hash",
        )

        // (b) — canonical Alice vectors
        val aliceArchive = IdentityArchive.pack(aliceWithRatchet, "alice", crypto, testIterations)
        val aliceRecovered = IdentityArchive.unpack(aliceArchive, "alice", crypto).getOrThrow()
        val aliceOriginal = io.github.thatsfguy.reticulum.crypto.Identity(crypto).also {
            it.loadFromPrivateKeys(
                aliceWithRatchet.encPrivKey, aliceWithRatchet.sigPrivKey, aliceWithRatchet.ratchetPrivKey,
            )
        }
        val aliceRoundTripped = io.github.thatsfguy.reticulum.crypto.Identity(crypto).also {
            it.loadFromPrivateKeys(
                aliceRecovered.encPrivKey, aliceRecovered.sigPrivKey, aliceRecovered.ratchetPrivKey,
            )
        }
        assertContentEquals(
            io.github.thatsfguy.reticulum.crypto.computeDestinationHash(crypto, "lxmf.delivery", aliceOriginal.hash!!),
            io.github.thatsfguy.reticulum.crypto.computeDestinationHash(crypto, "lxmf.delivery", aliceRoundTripped.hash!!),
            "Alice's destHash must round-trip identically",
        )
        // Also pin the underlying identity hash (the input to destHash)
        // so a regression in pub-key derivation surfaces with a tighter
        // signal than the layered destHash assertion above.
        assertContentEquals(
            aliceOriginal.hash, aliceRoundTripped.hash,
            "Alice's identity hash must round-trip identically",
        )
    }
}
