package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many announces the app holds, and what it costs to read them.
 *
 * The cap exists because of Android's 2 MB CursorWindow, which is a
 * BYTE budget rather than a row budget — v1.1.26 lowered it 5000 → 1000
 * after a tester crashed with "Couldn't read row 1123, col 0 from
 * CursorWindow" on a busy mesh. Raising it back up is only safe because
 * the row got cheaper, so that is what these pin: the widest column is
 * no longer in the list query, and the count that column paid for.
 */
@RunWith(RobolectricTestRunner::class)
class DestinationRetentionTest {

    private lateinit var db: ReticulumDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ReticulumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() { db.close() }

    /** A destination carrying the largest app_data the ingest cap
     *  allows — 4 KB of bytes, so 8 KB of hex on the row. */
    private suspend fun insert(i: Int, fatAppData: Boolean = false) {
        db.destinationDao().upsert(
            DestinationEntity(
                hash = "%032x".format(i),
                identityHash = "%032x".format(i),
                publicKey = ByteArray(64),
                destHash = ByteArray(16),
                nameHash = ByteArray(10),
                ratchetPub = null,
                displayName = "node-$i",
                appName = "lxmf.delivery",
                appLabel = "LXMF",
                telemetryJson = null,
                lat = null, lon = null,
                appDataHex = if (fatAppData) "ab".repeat(4 * 1024) else "",
                lastSeen = i.toLong(),
                rssi = null,
                favorite = false,
                source = "announce",
            ),
        )
    }

    /** The cap the engine evicts to is 2500; the list query has to be
     *  able to return that many or holding them is pointless. */
    @Test fun theListReturnsWellPastTheOldThousandRowCeiling() = runTest {
        repeat(1_200) { insert(it) }
        assertEquals(1_200, db.destinationDao().observeAll().first().size)
    }

    /**
     * The widest column is not in the list query. Without this, 2500
     * rows of worst-case app_data would be ~20 MB through a 2 MB
     * window — the crash the original cap was lowered to avoid.
     */
    @Test fun theListDoesNotCarryAppDataHex() = runTest {
        insert(1, fatAppData = true)
        val listed = db.destinationDao().observeAll().first().single()
        assertEquals("", listed.appDataHex, "appDataHex must not ride the list query")
        // Everything the list actually renders is still there.
        assertEquals("node-1", listed.displayName)
        assertEquals("lxmf.delivery", listed.appName)
    }

    /** …and the full row still has it, because the stamp-cost path
     *  re-reads through get(hash) and needs the real value. */
    @Test fun theFullRowStillCarriesAppDataHex() = runTest {
        insert(1, fatAppData = true)
        val full = db.destinationDao().get("%032x".format(1))
        assertTrue((full?.appDataHex?.length ?: 0) > 8000, "get(hash) must return the real app_data")
    }

    /** Favorites sort first, so they are in the result no matter how
     *  many announce-only rows are ahead of them by recency. */
    @Test fun favouritesAreAlwaysInTheResult() = runTest {
        repeat(50) { insert(it) }
        db.destinationDao().setFavorite("%032x".format(0), true)
        val listed = db.destinationDao().observeAll().first()
        assertTrue(listed.first().favorite, "a favorite must sort ahead of newer announce rows")
    }
}
