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

    // ---- what survives eviction ----------------------------------

    /** Insert a row with the fields the eviction tiers key on. */
    private suspend fun insertNode(
        hash: String,
        lastSeen: Long,
        rssi: Int? = null,
        hopCount: Int = 3,
        displayName: String = "LXMF",
        appLabel: String? = "LXMF",
    ) {
        db.destinationDao().upsert(
            DestinationEntity(
                hash = hash, identityHash = hash,
                publicKey = ByteArray(64), destHash = ByteArray(16), nameHash = ByteArray(10),
                ratchetPub = null,
                displayName = displayName,
                appName = "lxmf.delivery", appLabel = appLabel,
                telemetryJson = null, lat = null, lon = null, appDataHex = "",
                lastSeen = lastSeen, rssi = rssi, favorite = false, source = "announce",
                hopCount = hopCount,
            ),
        )
    }

    private suspend fun survivors(keep: Int): Set<String> {
        db.destinationDao().evictUnfavoritedOldest(keep)
        return db.destinationDao().observeAll().first().map { it.hash }.toSet()
    }

    /**
     * The point of the tiering. A node heard first-hand over the radio
     * outranks a fresher stranger from across a TCP mesh — recency
     * alone would let mesh churn evict the user's own neighbourhood.
     */
    @Test fun aRadioNeighbourOutlivesAFresherStranger() = runTest {
        insertNode("aa", lastSeen = 1, rssi = -95, hopCount = 1)
        insertNode("bb", lastSeen = 9_999)
        assertEquals(setOf("aa"), survivors(keep = 1))
    }

    /** RSSI alone is not "nearby" — our RNode measures it on relayed
     *  announces too, so a multi-hop row gets no protection from it. */
    @Test fun aRelayedRowIsNotTreatedAsANeighbourJustForHavingRssi() = runTest {
        insertNode("aa", lastSeen = 1, rssi = -95, hopCount = 4)
        insertNode("bb", lastSeen = 9_999)
        assertEquals(setOf("bb"), survivors(keep = 1), "hop 4 is not a radio neighbour")
    }

    /** A name the operator chose beats an anonymous row. */
    @Test fun aNamedNodeOutlivesAnAnonymousOne() = runTest {
        insertNode("aa", lastSeen = 1, displayName = "Rob's base")
        insertNode("bb", lastSeen = 9_999)
        assertEquals(setOf("aa"), survivors(keep = 1))
    }

    /** …but the generic service label is not a name. An unnamed LXMF
     *  peer carries displayName = "LXMF" from the known-services table,
     *  and must not be promoted for it. */
    @Test fun theGenericServiceLabelDoesNotCountAsAName() = runTest {
        insertNode("aa", lastSeen = 1, displayName = "LXMF", appLabel = "LXMF")
        insertNode("bb", lastSeen = 9_999, displayName = "LXMF", appLabel = "LXMF")
        assertEquals(setOf("bb"), survivors(keep = 1), "same tier, so recency decides")
    }

    /** Within a tier the newest still wins — tiering reorders, it does
     *  not replace recency. */
    @Test fun recencyStillDecidesInsideATier() = runTest {
        insertNode("aa", lastSeen = 1, rssi = -95, hopCount = 1)
        insertNode("bb", lastSeen = 9_999, rssi = -95, hopCount = 1)
        assertEquals(setOf("bb"), survivors(keep = 1))
    }

    /** Tiering must not change HOW MANY rows are kept — that is what
     *  the CursorWindow budget is spent on. */
    @Test fun theKeptCountIsUnchangedByTiering() = runTest {
        repeat(20) { insertNode("%02x".format(it), lastSeen = it.toLong(), rssi = if (it % 2 == 0) -95 else null, hopCount = if (it % 2 == 0) 1 else 3) }
        assertEquals(5, survivors(keep = 5).size)
    }

    // ---- hub discovery must not inherit the recency window ---------

    /**
     * The bug this exists to prevent. The list Flow is the 2500 most
     * recently seen rows, which on a busy mesh is a window of TIME —
     * measured at ~44 new rows a minute, so 2500 rows is hours and the
     * old 1000-row form was 22 minutes. A hub announcing hourly (the
     * correct cadence) falls out of it between announces, which is how
     * two users saw a hub "pop in" hours later. Asking for the aspect
     * directly has to return it regardless of where it ranks.
     */
    @Test fun aHubIsFoundEvenWhenItRanksBelowTheListWindow() = runTest {
        // One old hub, then more fresh rows than the list will ever return.
        insertNode("hub", lastSeen = 1, displayName = "MichMesh RRC Hub", appLabel = "RRC hub")
        db.destinationDao().upsert(
            db.destinationDao().get("hub")!!.copy(appName = "rrc.hub"),
        )
        repeat(3_000) { insertNode("f%04x".format(it), lastSeen = 1_000L + it) }

        val listed = db.destinationDao().observeAll().first()
        assertTrue(listed.none { it.hash == "hub" }, "precondition: the hub is outside the list window")

        val hubs = db.destinationDao().observeByAppName("rrc.hub").first()
        assertEquals(listOf("hub"), hubs.map { it.hash }, "the direct query must find it anyway")
    }

    /** It returns only that aspect, and newest-first. */
    @Test fun theAspectQueryReturnsOnlyThatAspectNewestFirst() = runTest {
        for ((h, t) in listOf("a" to 5L, "b" to 9L)) {
            insertNode(h, lastSeen = t)
            db.destinationDao().upsert(db.destinationDao().get(h)!!.copy(appName = "rrc.hub"))
        }
        insertNode("other", lastSeen = 100)   // lxmf.delivery
        val hubs = db.destinationDao().observeByAppName("rrc.hub").first()
        assertEquals(listOf("b", "a"), hubs.map { it.hash })
    }

    /** Hidden (soft-deleted) rows stay hidden here too. */
    @Test fun aHiddenHubIsNotDiscovered() = runTest {
        insertNode("hub", lastSeen = 1)
        db.destinationDao().upsert(
            db.destinationDao().get("hub")!!.copy(appName = "rrc.hub", hidden = true),
        )
        assertTrue(db.destinationDao().observeByAppName("rrc.hub").first().isEmpty())
    }

    /**
     * Querying whole is pointless if eviction deletes the row first.
     * Hubs are few and deliberately slow to announce, so the count cap
     * is the wrong instrument for them — like propagation nodes, they
     * are exempt.
     */
    @Test fun aHubSurvivesEvictionNoMatterHowStaleItIs() = runTest {
        insertNode("hub", lastSeen = 1)
        db.destinationDao().upsert(db.destinationDao().get("hub")!!.copy(appName = "rrc.hub"))
        repeat(50) { insertNode("n%02x".format(it), lastSeen = 500L + it) }

        db.destinationDao().evictUnfavoritedOldest(5)
        assertEquals(
            listOf("hub"),
            db.destinationDao().observeByAppName("rrc.hub").first().map { it.hash },
            "the oldest row in the table, but a hub — it must not be evicted",
        )
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
