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

    // ---- Audit 2026-08-31 F9: the PRAGMAs must actually execute ---------

    /**
     * `PRAGMA secure_delete = ON` is the entire 2026-07-28 M4
     * remediation — it is what zeroes freed pages so superseded
     * private-key bytes do not linger in the DB file. It was issued as
     * `query(...).close()`, and an Android cursor does not execute
     * until it is stepped, so the setting was never applied on any
     * build that shipped it. Read it back and prove otherwise.
     */
    @Test fun `secure_delete is actually enabled on the live connection`() = runTest {
        val db = ReticulumDatabase.get(ctx)
        db.openHelper.writableDatabase.query("PRAGMA secure_delete").use { c ->
            assertTrue(c.moveToFirst(), "PRAGMA secure_delete returned no row")
            assertTrue(c.getInt(0) != 0, "secure_delete must be ON, was ${c.getInt(0)}")
        }
    }

    // ---- Audit 2026-08-31 F3: the backup must follow a deletion ---------

    /**
     * The snapshot is otherwise taken once, at DB-open time, so
     * everything the user deliberately destroys mid-session survives in
     * a full file copy the app will not touch again until next launch.
     * "Reset identity" promises a new keypair; the old one must not be
     * sitting in `reticulum.db.bak` afterwards.
     */
    @Test fun `refreshBackup rewrites the copy after an identity reset`() = runTest {
        seedIdentity(enc = 7)
        val db = ReticulumDatabase.get(ctx)
        // The reset: the identity row is replaced in the LIVE db.
        db.identityDao().upsert(
            IdentityEntity(
                id = 0,
                encPrivKey = ByteArray(32) { 42 },
                sigPrivKey = ByteArray(32) { 43 },
                ratchetPrivKey = null,
            ),
        )
        ReticulumDatabase.refreshBackup(ctx)

        // Prove it by restoring from the backup: the old key must be gone.
        ReticulumDatabase.closeInstanceForTest()
        dbFile().delete(); walFile().delete(); shmFile().delete()
        val restored = ReticulumDatabase.get(ctx).identityDao().load()
        assertNotNull(restored)
        assertContentEquals(ByteArray(32) { 42 }, restored.encPrivKey,
            "the backup must hold the NEW identity, not the reset-away one")
    }

    /** Same for a deleted conversation — the rows must not live on in
     *  the copy. */
    @Test fun `refreshBackup rewrites the copy after messages are deleted`() = runTest {
        seedIdentity(enc = 7)
        val db = ReticulumDatabase.get(ctx)
        db.messageDao().insert(
            MessageEntity(
                contactHash = "aa".repeat(16), direction = "incoming",
                content = "a secret worth deleting", title = "",
                timestamp = 1L, state = "verified", attempts = 0,
                lastAttempt = 0L, lastError = null, rawPacket = null,
                packetHash = null, rssi = null,
            ),
        )
        ReticulumDatabase.refreshBackup(ctx)
        db.messageDao().deleteForContact("aa".repeat(16))
        ReticulumDatabase.refreshBackup(ctx)

        ReticulumDatabase.closeInstanceForTest()
        dbFile().delete(); walFile().delete(); shmFile().delete()
        val rows = ReticulumDatabase.get(ctx).messageDao().getAll()
        assertTrue(rows.isEmpty(), "deleted messages must not survive in the backup copy")
    }

    /** The invariant still holds: a refresh over a DB with no identity
     *  must not replace a good backup with an empty one. */
    @Test fun `refreshBackup will not overwrite a good backup with an empty DB`() = runTest {
        seedIdentity(enc = 5)
        val before = bakFile().length()
        ReticulumDatabase.closeInstanceForTest()
        dbFile().delete(); walFile().delete(); shmFile().delete()
        bakFile().delete()                       // no backup to restore from
        ReticulumDatabase.get(ctx)               // opens empty, no identity
        ReticulumDatabase.refreshBackup(ctx)
        assertFalse(
            bakFile().exists() && bakFile().length() < before / 2,
            "an identity-less DB must not be snapshotted",
        )
    }
}
