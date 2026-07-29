package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
// (assertNotNull/assertTrue intentionally unused — a thrown SQLiteException
//  from onOpen fails the test before any assert, which is the real guard.)

/**
 * Regression guard for the 10298 crash-on-open: the secure_delete callback
 * (audit M4) ran `PRAGMA secure_delete = ON` (and `wal_checkpoint`) via
 * execSQL, but those pragmas ECHO a result row and Android's execSQL rejects
 * any result-returning statement — crashing the app on every DB open. The fix
 * routes them through query() and wraps everything in runCatching.
 *
 * Unlike StorageRoundTripTest, this builds the DB WITH secureDeleteCallback
 * attached (the production path) so onOpen actually runs — the earlier test
 * used a bare inMemoryDatabaseBuilder and never exercised the callback, which
 * is how the regression shipped.
 */
@RunWith(RobolectricTestRunner::class)
class SecureDeleteCallbackTest {

    private lateinit var db: ReticulumDatabase

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ReticulumDatabase::class.java)
            .addCallback(ReticulumDatabase.secureDeleteCallback(context))
            .allowMainThreadQueries()
            .build()
    }

    @After fun teardown() { db.close() }

    @Test fun `opening the DB with the secure-delete callback does not throw`() = runTest {
        // The first real DB access forces open(), which fires onOpen and runs
        // the secure_delete pragma. If the callback throws (the 10298 bug),
        // this upsert raises SQLiteException before the assert.
        val dao = db.destinationDao()
        dao.upsert(DestinationEntity(
            hash = "aabbccdd",
            identityHash = "11223344",
            publicKey = ByteArray(64) { it.toByte() },
            destHash  = ByteArray(16) { (it * 2).toByte() },
            nameHash  = ByteArray(10) { (it * 3).toByte() },
            ratchetPub = null,
            displayName = "Alice",
            appName = "lxmf.delivery",
            appLabel = "LXMF delivery",
            telemetryJson = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = 1700000000L,
            rssi = -42,
            favorite = false,
            source = "announce",
        ))
        // A read after the pragma ran still works.
        assertEquals("Alice", dao.get("aabbccdd")?.displayName)
    }
}
