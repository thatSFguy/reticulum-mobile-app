package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.thatsfguy.reticulum.crypto.IdentityVault
import io.github.thatsfguy.reticulum.store.StoredIdentity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit H1: the private keys must NEVER be written to the plaintext columns
 * because of a TRANSIENT vault failure (device locked, vendor flake). The
 * plaintext fallback is reserved for the permanent KeystoreUnavailableException.
 * This is the exact code that shipped untested; pinning it now.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityVaultFallbackTest {

    private lateinit var db: ReticulumDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ReticulumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() { db.close() }

    /** Reversible pass-through "vault" — seal/unseal are identity, so a sealed
     *  row round-trips without a real Keystore. */
    private class PassThroughVault : IdentityVault {
        override suspend fun seal(plaintext: ByteArray) = plaintext.copyOf()
        override suspend fun unseal(sealed: ByteArray) = sealed.copyOf()
    }

    private class ThrowingVault(private val ex: () -> Throwable) : IdentityVault {
        override suspend fun seal(plaintext: ByteArray): ByteArray = throw ex()
        override suspend fun unseal(sealed: ByteArray): ByteArray = throw ex()
    }

    private fun identity(enc: Byte) = StoredIdentity(
        encPrivKey = ByteArray(32) { enc },
        sigPrivKey = ByteArray(32) { (enc + 1).toByte() },
        ratchetPrivKey = null,
    )

    @Test fun `KeystoreUnavailable writes the plaintext fallback`() = runTest {
        val repo = IdentityRepoImpl(
            db.identityDao(),
            ThrowingVault { KeystoreUnavailableException("no keystore on this device", null) },
        )
        repo.save(identity(0x11))

        val row = db.identityDao().load()
        assertContentEquals(ByteArray(32) { 0x11 }, row!!.encPrivKey, "device-can't-seal → plaintext fallback")
        assertTrue(row.encPrivKeyEnc == null || row.encPrivKeyEnc!!.isEmpty(), "no sealed blob when vault unavailable")
    }

    @Test fun `transient failure writes NOTHING (no plaintext leak) on first save`() = runTest {
        val repo = IdentityRepoImpl(
            db.identityDao(),
            ThrowingVault { RuntimeException("device locked (UserNotAuthenticated)") },
        )
        repo.save(identity(0x22))

        assertNull(
            db.identityDao().load(),
            "a transient seal failure must DEFER, never write the keys in plaintext",
        )
    }

    @Test fun `transient failure preserves an existing sealed row`() = runTest {
        val sealedRepo = IdentityRepoImpl(db.identityDao(), PassThroughVault())
        sealedRepo.save(identity(0x33))                       // healthy sealed row for identity 0x33

        // A later save while the device is locked must NOT overwrite it with
        // plaintext or clear it.
        val lockedRepo = IdentityRepoImpl(
            db.identityDao(),
            ThrowingVault { RuntimeException("device locked") },
        )
        lockedRepo.save(identity(0x44))

        val loaded = sealedRepo.load()
        assertContentEquals(
            ByteArray(32) { 0x33 }, loaded!!.encPrivKey,
            "the existing sealed identity (0x33) must survive a transient failure, not be replaced by 0x44",
        )
        val row = db.identityDao().load()!!
        assertTrue(row.encPrivKey.isEmpty(), "sealed row's plaintext column stays empty — no leak")
    }
}
