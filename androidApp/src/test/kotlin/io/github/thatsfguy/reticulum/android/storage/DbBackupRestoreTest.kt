package io.github.thatsfguy.reticulum.android.storage

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The data-loss safety net (2026-07-28 incident: androidx.sqlite's
 * onCorruption handler DELETED reticulum.db after a crash-loop, so the app
 * came up empty with a new identity and lost every message/favorite/pin).
 *
 * ReticulumDatabase.get now keeps a last-known-good backup and restores it
 * whenever the DB comes up without an identity but a healthy backup exists.
 * These tests pin the guarantee AND the two invariants that keep the net from
 * ever destroying data: a good backup is never overwritten by an empty DB, and
 * a healthy live DB is never overwritten by a stale backup.
 */
@RunWith(RobolectricTestRunner::class)
class DbBackupRestoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun dbFile() = ctx.getDatabasePath(ReticulumDatabase.DB_NAME)
    private fun walFile() = File(dbFile().parentFile, "${ReticulumDatabase.DB_NAME}-wal")
    private fun shmFile() = File(dbFile().parentFile, "${ReticulumDatabase.DB_NAME}-shm")
    private fun bakFile() = File(dbFile().parentFile, "reticulum.db.bak")

    @Before fun clean() = wipeAll()
    @After fun teardown() { ReticulumDatabase.closeInstanceForTest(); wipeAll() }

    private fun wipeAll() {
        ReticulumDatabase.closeInstanceForTest()
        dbFile().delete(); walFile().delete(); shmFile().delete()
        bakFile().delete(); File(bakFile().parentFile, "reticulum.db.bak.tmp").delete()
    }

    private suspend fun seedIdentity(enc: Byte) {
        val db = ReticulumDatabase.get(ctx)
        db.identityDao().upsert(
            IdentityEntity(
                id = 0,
                encPrivKey = ByteArray(32) { enc },
                sigPrivKey = ByteArray(32) { (enc + 1).toByte() },
                ratchetPrivKey = null,
            ),
        )
        // Re-open so get()'s post-open snapshot runs now that an identity exists.
        ReticulumDatabase.closeInstanceForTest()
        ReticulumDatabase.get(ctx)
        ReticulumDatabase.closeInstanceForTest()
    }

    @Test fun `fresh install writes no backup and does not crash`() = runTest {
        val db = ReticulumDatabase.get(ctx)
        assertNull(db.identityDao().load(), "fresh DB has no identity")
        assertFalse(bakFile().exists(), "no backup should be written until an identity exists")
    }

    @Test fun `healthy open snapshots a backup`() = runTest {
        seedIdentity(enc = 7)
        assertTrue(bakFile().exists() && bakFile().length() > 4096L, "backup snapshot should exist")
    }

    @Test fun `deleted DB file is restored from backup on next open`() = runTest {
        seedIdentity(enc = 9)
        // Simulate the wipe: the platform deleted reticulum.db (+ WAL/SHM).
        dbFile().delete(); walFile().delete(); shmFile().delete()
        assertFalse(dbFile().exists())

        val db = ReticulumDatabase.get(ctx)
        val id = db.identityDao().load()
        assertNotNull(id, "identity must be restored from the backup after a file-level wipe")
        assertContentEquals(ByteArray(32) { 9 }, id.encPrivKey, "restored the correct identity")
    }

    @Test fun `DB present but emptied is restored from backup (during-open wipe)`() = runTest {
        seedIdentity(enc = 5)
        // Simulate "opened but wiped": the file is present, but the identity is
        // gone (as if onCorruption recreated an empty DB during open).
        run {
            val db = ReticulumDatabase.get(ctx)
            db.identityDao().let { dao ->
                db.openHelper.writableDatabase.execSQL("DELETE FROM identity")
            }
            assertNull(db.identityDao().load(), "precondition: identity row removed")
            ReticulumDatabase.closeInstanceForTest()
        }

        val db = ReticulumDatabase.get(ctx)
        val id = db.identityDao().load()
        assertNotNull(id, "identity must be restored when the DB opens without one but a backup has data")
        assertContentEquals(ByteArray(32) { 5 }, id.encPrivKey)
    }

    @Test fun `healthy live DB is never overwritten by a stale backup`() = runTest {
        // Backup holds identity 'A'; live DB holds a DIFFERENT identity 'B'.
        seedIdentity(enc = 0xA)                 // creates backup with A, live has A
        run {                                    // overwrite the LIVE identity with B
            val db = ReticulumDatabase.get(ctx)
            db.identityDao().upsert(
                IdentityEntity(id = 0, encPrivKey = ByteArray(32) { 0xB }, sigPrivKey = ByteArray(32) { 0xC }, ratchetPrivKey = null),
            )
            ReticulumDatabase.closeInstanceForTest()
        }
        // Backup still holds A. Opening must NOT restore over the healthy B.
        val db = ReticulumDatabase.get(ctx)
        assertContentEquals(
            ByteArray(32) { 0xB }, db.identityDao().load()!!.encPrivKey,
            "a healthy live DB (identity B) must never be clobbered by the older backup (identity A)",
        )
    }

    @Test fun `empty DB never overwrites a good backup`() = runTest {
        seedIdentity(enc = 3)                    // good backup with identity 3
        val backupLenBefore = bakFile().length()
        // Wipe the live DB entirely (no identity, no backup-worthy content) and
        // ALSO remove the backup's data-worthiness guard by leaving it intact.
        dbFile().delete(); walFile().delete(); shmFile().delete()
        // Open: this restores from backup (good), then snapshots again — the
        // snapshot must still contain identity 3, never an empty DB.
        ReticulumDatabase.get(ctx)
        ReticulumDatabase.closeInstanceForTest()
        assertTrue(bakFile().length() >= backupLenBefore - 4096, "backup must remain a real DB, not be emptied")
        // And it still restores a valid identity afterwards.
        val db = ReticulumDatabase.get(ctx)
        assertNotNull(db.identityDao().load())
    }
}
