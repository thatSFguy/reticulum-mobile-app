package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 2026-07-29: a propagation node (appName = "lxmf.propagation") must survive
 * the announce-flood eviction, even though it's not favorited and has no
 * message history — you sync FROM it, but its delivered messages are stored
 * under the original sender's hash, so the message-history exemption never
 * covers the prop node. Before the fix it was evicted on a busy mesh, so a
 * later sync failed with "Unknown propagation node".
 */
@RunWith(RobolectricTestRunner::class)
class PropagationNodeEvictionTest {

    private lateinit var db: ReticulumDatabase
    private lateinit var dao: DestinationDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ReticulumDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.destinationDao()
    }

    @After fun teardown() { db.close() }

    private fun dest(hash: String, appName: String?, lastSeen: Long) = DestinationEntity(
        hash = hash, identityHash = "00".repeat(16), publicKey = ByteArray(64),
        destHash = ByteArray(16), nameHash = ByteArray(10), ratchetPub = null,
        displayName = hash, appName = appName, appLabel = null, telemetryJson = null,
        lat = null, lon = null, appDataHex = "", lastSeen = lastSeen, rssi = null,
        favorite = false, source = "announce",
    )

    @Test fun `propagation node survives eviction while ordinary announces are trimmed`() = runTest {
        val propHash = "pp".repeat(16)
        // The prop node is the OLDEST row (lowest lastSeen) — so ordering alone
        // would evict it first. It must be exempt regardless.
        dao.upsert(dest(propHash, appName = "lxmf.propagation", lastSeen = 1L))
        // 20 ordinary (lxmf.delivery) announce-only rows, all newer.
        repeat(20) { i ->
            dao.upsert(dest("%032x".format(i), appName = "lxmf.delivery", lastSeen = 100L + i))
        }

        // Keep only 5 ordinary rows; everything else un-exempt is evicted.
        dao.evictUnfavoritedOldest(keepCount = 5)

        assertNotNull(
            dao.get(propHash),
            "the propagation node must NOT be evicted — sync resolves it via a direct get()",
        )
        // Sanity: ordinary rows WERE trimmed (15 of the 20 removed).
        val remainingOrdinary = dao.getAll().count { it.appName == "lxmf.delivery" }
        assertNull(
            dao.get("%032x".format(0)),
            "the oldest ordinary announce rows should have been evicted",
        )
        kotlin.test.assertEquals(5, remainingOrdinary, "keepCount ordinary rows retained")
    }

    @Test fun `ordinary announce with no message history is still evictable`() = runTest {
        // Guard against over-exempting: a plain lxmf.delivery announce with no
        // favorite/label/message stays evictable.
        repeat(10) { i -> dao.upsert(dest("%032x".format(i), appName = "lxmf.delivery", lastSeen = i.toLong())) }
        dao.evictUnfavoritedOldest(keepCount = 3)
        kotlin.test.assertEquals(3, dao.getAll().size)
    }
}
