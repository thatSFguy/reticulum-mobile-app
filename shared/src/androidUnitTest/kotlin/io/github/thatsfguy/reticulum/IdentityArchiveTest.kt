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

        assertContentEquals(aliceWithRatchet.encPrivKey, recovered.identity.encPrivKey)
        assertContentEquals(aliceWithRatchet.sigPrivKey, recovered.identity.sigPrivKey)
        assertContentEquals(aliceWithRatchet.ratchetPrivKey, recovered.identity.ratchetPrivKey)
        // No displayName supplied to pack → v0x02 encodes name_len=0 →
        // unpack returns an empty string (distinguishable from a v0x01
        // legacy archive which returns null).
        assertEquals("", recovered.displayName)
    }

    @Test fun roundtrip_withoutRatchet() = runTest {
        val passphrase = "no ratchet here just keys"
        val archive = IdentityArchive.pack(aliceWithoutRatchet, passphrase, crypto, testIterations)
        val recovered = IdentityArchive.unpack(archive, passphrase, crypto).getOrThrow()

        assertContentEquals(aliceWithoutRatchet.encPrivKey, recovered.identity.encPrivKey)
        assertContentEquals(aliceWithoutRatchet.sigPrivKey, recovered.identity.sigPrivKey)
        assertEquals(null, recovered.identity.ratchetPrivKey)
    }

    @Test fun roundtrip_withDisplayName() = runTest {
        // The v0x02 plaintext layout carries an optional display name
        // so a user's chosen label round-trips through export/import
        // without forcing them to retype it on the new device.
        // Tester report 2026-05-12 motivated this: "I want to include
        // the name in the identity file."
        val passphrase = "name test passphrase value"
        val name = "Blue42 👋"  // includes a non-BMP emoji to pin UTF-8 width handling
        val archive = IdentityArchive.pack(
            aliceWithRatchet, passphrase, crypto, testIterations, displayName = name,
        )
        val recovered = IdentityArchive.unpack(archive, passphrase, crypto).getOrThrow()

        assertContentEquals(aliceWithRatchet.encPrivKey, recovered.identity.encPrivKey)
        assertContentEquals(aliceWithRatchet.sigPrivKey, recovered.identity.sigPrivKey)
        assertContentEquals(aliceWithRatchet.ratchetPrivKey, recovered.identity.ratchetPrivKey)
        assertEquals(name, recovered.displayName)
    }

    @Test fun roundtrip_withEmptyDisplayName() = runTest {
        // User has not set a display name yet (provider returns "").
        // Must encode cleanly as name_len=0 and decode back to an
        // empty string — NOT null (null is reserved for v0x01 legacy).
        val passphrase = "no name yet at this time"
        val archive = IdentityArchive.pack(
            aliceWithRatchet, passphrase, crypto, testIterations, displayName = "",
        )
        val recovered = IdentityArchive.unpack(archive, passphrase, crypto).getOrThrow()
        assertEquals("", recovered.displayName)
    }

    @Test fun unpack_v01LegacyArchive_returnsNullDisplayName() = runTest {
        // Regression guard: existing .rmid files produced by older
        // builds (the tester's 187-byte v0x01 export from 2026-05-12
        // being the motivating case) must still decrypt cleanly. We
        // can't call IdentityArchive.pack with PT_VERSION=0x01 from
        // here because that constant is private, so we re-derive
        // every byte of a v0x01 archive directly using the public
        // CryptoProvider primitives. If parsePlaintext stops
        // accepting v0x01 in the future, this assertion fails BEFORE
        // any tester's old backup gets bricked on a fresh install.
        val passphrase = "legacy archive passphrase"
        val iterations = testIterations

        // 1. v0x01 plaintext: pt_version=0x01, enc(32), sig(32), has_ratchet=1, ratchet(32) = 98 bytes
        val pt = ByteArray(1 + 32 + 32 + 1 + 32).also {
            it[0] = 0x01
            aliceWithRatchet.encPrivKey.copyInto(it, 1)
            aliceWithRatchet.sigPrivKey.copyInto(it, 33)
            it[65] = 0x01
            aliceWithRatchet.ratchetPrivKey!!.copyInto(it, 66)
        }

        // 2. PBKDF2 + HKDF to match deriveKeys(). Cloned here so we
        //    don't have to expose deriveKeys as test-only API.
        val passBytes = passphrase.encodeToByteArray()
        val salt = ByteArray(16) { (0x42 + it).toByte() }
        val iv = ByteArray(16) { (0x90 + it).toByte() }
        // PBKDF2-HMAC-SHA256(pass, salt, iterations) → 64-byte master
        val master = ByteArray(64)
        var written = 0
        for (i in 1..2) {
            val saltAndCounter = salt + byteArrayOf(0, 0, 0, i.toByte())
            var u = crypto.hmacSha256(passBytes, saltAndCounter)
            val t = u.copyOf()
            for (j in 2..iterations) {
                u = crypto.hmacSha256(passBytes, u)
                for (k in 0 until 32) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            t.copyInto(master, written, 0, 32); written += 32
        }
        val derived = crypto.hkdfDerive(
            ikm = master,
            salt = ByteArray(0),
            info = "reticulum-mobile-identity-export-v1".encodeToByteArray(),
            length = 64,
        )
        val signingKey = derived.copyOfRange(0, 32)
        val encryptionKey = derived.copyOfRange(32, 64)

        // 3. AES-CBC encrypt + HMAC + assemble envelope.
        val ct = crypto.aesCbcEncrypt(encryptionKey, iv, pt)
        val iterBytes = byteArrayOf(
            ((iterations ushr 24) and 0xFF).toByte(),
            ((iterations ushr 16) and 0xFF).toByte(),
            ((iterations ushr 8) and 0xFF).toByte(),
            (iterations and 0xFF).toByte(),
        )
        val hmac = crypto.hmacSha256(signingKey, salt + iterBytes + iv + ct)
        val ctLenBytes = byteArrayOf(((ct.size ushr 8) and 0xFF).toByte(), (ct.size and 0xFF).toByte())
        val magic = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte())
        val archive = magic + byteArrayOf(0x01) + salt + iterBytes + iv + hmac + ctLenBytes + ct

        // 4. Decrypt via the public unpack path → must succeed AND
        //    yield a null displayName so the caller knows to keep
        //    the existing local name in place.
        val recovered = IdentityArchive.unpack(archive, passphrase, crypto).getOrThrow()
        assertContentEquals(aliceWithRatchet.encPrivKey, recovered.identity.encPrivKey)
        assertContentEquals(aliceWithRatchet.sigPrivKey, recovered.identity.sigPrivKey)
        assertContentEquals(aliceWithRatchet.ratchetPrivKey, recovered.identity.ratchetPrivKey)
        assertEquals(null, recovered.displayName)
    }

    @Test fun unpack_wrongPassphrase_fails() = runTest {
        val archive = IdentityArchive.pack(aliceWithRatchet, "right one is correct phrase", crypto, testIterations)
        val result = IdentityArchive.unpack(archive, "wrong one is correct phrase", crypto)
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

    @Test fun pack_weakPassphrase_rejected() = runTest {
        // Audit reference: 2026-05-13 HIGH-3. PBKDF2-HMAC-SHA256 buys
        // time proportional to passphrase entropy; a 6-character
        // lowercase passphrase or a dictionary word falls in seconds
        // of offline GPU work. pack() must reject anything below the
        // policy in assessPassphrase so a programmatic caller can't
        // bypass the UI's strength meter.
        assertFailsWith<IllegalArgumentException> {
            IdentityArchive.pack(aliceWithRatchet, "short", crypto, testIterations)
        }
        assertFailsWith<IllegalArgumentException> {
            // 12 chars but only one character class — too narrow.
            IdentityArchive.pack(aliceWithRatchet, "allloweronly", crypto, testIterations)
        }
    }

    @Test fun unpack_tamperedCiphertext_fails() = runTest {
        val passphrase = "test passphrase value here"
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
        val passphrase = "test passphrase value here"
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
        val phrase = "test passphrase value here"
        val archive = IdentityArchive.pack(aliceWithRatchet, phrase, crypto, testIterations)
        val truncated = archive.copyOfRange(0, archive.size - 10)
        val result = IdentityArchive.unpack(truncated, phrase, crypto)
        assertTrue(result.isFailure, "truncated archive must fail")
    }

    @Test fun pack_isRandomized() = runTest {
        // Two pack() calls on the same input must produce different
        // bytes (different salt + IV). Otherwise an attacker who sees
        // two backups can confirm they're the same identity by byte
        // comparison.
        val passphrase = "same passphrase value here"
        val a = IdentityArchive.pack(aliceWithRatchet, passphrase, crypto, testIterations)
        val b = IdentityArchive.pack(aliceWithRatchet, passphrase, crypto, testIterations)
        assertNotEquals(a.toList(), b.toList(), "salt + IV must be random")

        // Both still unpack to the same plaintext.
        val ra = IdentityArchive.unpack(a, passphrase, crypto).getOrThrow()
        val rb = IdentityArchive.unpack(b, passphrase, crypto).getOrThrow()
        assertContentEquals(ra.identity.encPrivKey, rb.identity.encPrivKey)
        assertContentEquals(ra.identity.sigPrivKey, rb.identity.sigPrivKey)
    }

    @Test fun pack_magicHeader() = runTest {
        // Self-describing magic so a future format upgrade can fork on
        // the version byte without ambiguity. Lock the prefix here so
        // a casual refactor doesn't silently change the wire format.
        val archive = IdentityArchive.pack(aliceWithRatchet, "test passphrase value here", crypto, testIterations)
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
        val archive = IdentityArchive.pack(freshStored, "round-trip-passphrase-test", crypto, testIterations)
        val recovered = IdentityArchive.unpack(archive, "round-trip-passphrase-test", crypto).getOrThrow()
        val recoveredIdentity = io.github.thatsfguy.reticulum.crypto.Identity(crypto).also {
            it.loadFromPrivateKeys(
                recovered.identity.encPrivKey,
                recovered.identity.sigPrivKey,
                recovered.identity.ratchetPrivKey,
            )
        }
        val recoveredDestHash = io.github.thatsfguy.reticulum.crypto.computeDestinationHash(
            crypto, "lxmf.delivery", recoveredIdentity.hash!!
        )
        assertContentEquals(
            originalDestHash, recoveredDestHash,
            "freshly-generated identity must round-trip with the same destination hash",
        )

        // (b) — canonical Alice vectors
        val aliceArchive = IdentityArchive.pack(aliceWithRatchet, "alice canonical passphrase", crypto, testIterations)
        val aliceRecovered = IdentityArchive.unpack(aliceArchive, "alice canonical passphrase", crypto).getOrThrow()
        val aliceOriginal = io.github.thatsfguy.reticulum.crypto.Identity(crypto).also {
            it.loadFromPrivateKeys(
                aliceWithRatchet.encPrivKey, aliceWithRatchet.sigPrivKey, aliceWithRatchet.ratchetPrivKey,
            )
        }
        val aliceRoundTripped = io.github.thatsfguy.reticulum.crypto.Identity(crypto).also {
            it.loadFromPrivateKeys(
                aliceRecovered.identity.encPrivKey,
                aliceRecovered.identity.sigPrivKey,
                aliceRecovered.identity.ratchetPrivKey,
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
