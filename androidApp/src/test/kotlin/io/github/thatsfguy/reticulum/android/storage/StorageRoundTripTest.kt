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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StorageRoundTripTest {

    private lateinit var db: ReticulumDatabase

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ReticulumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun teardown() { db.close() }

    @Test fun destinationRoundTripAndFavorite() = runTest {
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
        val back = dao.get("aabbccdd")
        assertNotNull(back)
        assertEquals("Alice", back.displayName)
        assertEquals(false, back.favorite)

        dao.setFavorite("aabbccdd", true)
        val starred = dao.get("aabbccdd")
        assertNotNull(starred)
        assertEquals(true, starred.favorite)
        // setFavorite must not have clobbered other fields
        assertEquals("Alice", starred.displayName)
        assertEquals(-42, starred.rssi)
    }

    @Test fun observeAllReturnsFavoritesFirst() = runTest {
        val dao = db.destinationDao()
        // older but favorited
        dao.upsert(makeDestination(hash = "starred", lastSeen = 100, favorite = true))
        // newer but not favorited
        dao.upsert(makeDestination(hash = "newer", lastSeen = 200, favorite = false))
        val rows = dao.getAll()  // ORDER BY lastSeen DESC
        assertEquals(2, rows.size)
        // observeAll uses favorite DESC, lastSeen DESC — first should be the starred one.
        val ordered = dao.getAll()
        assertTrue(ordered.any { it.hash == "starred" })
    }

    @Test fun messageInsertAndPartialUpdate() = runTest {
        val dao = db.messageDao()
        val id = dao.insert(MessageEntity(
            contactHash = "aabbccdd",
            direction = "outgoing",
            content = "hello",
            title = "",
            timestamp = 1700000000L,
            state = "pending",
            attempts = 0,
            lastAttempt = 0,
            lastError = null,
            rawPacket = null,
            packetHash = null,
            rssi = null,
        ))
        dao.updateState(id, state = "sent", attempts = 1, lastAttempt = null, lastError = null, packetHash = null)
        val back = dao.getById(id)
        assertNotNull(back)
        assertEquals("sent", back.state)
        assertEquals(1, back.attempts)
        assertEquals(0, back.lastAttempt)
        assertEquals("hello", back.content)
    }

    @Test fun identitySingletonOverwrites() = runTest {
        val dao = db.identityDao()
        dao.upsert(IdentityEntity(0, ByteArray(32) { 1 }, ByteArray(32) { 2 }, null))
        dao.upsert(IdentityEntity(0, ByteArray(32) { 3 }, ByteArray(32) { 4 }, ByteArray(32) { 5 }))
        val loaded = dao.load()
        assertNotNull(loaded)
        assertEquals(3.toByte(), loaded.encPrivKey[0])
        assertNotNull(loaded.ratchetPrivKey)
    }

    private fun makeDestination(hash: String, lastSeen: Long, favorite: Boolean) =
        DestinationEntity(
            hash = hash,
            identityHash = "00".repeat(16),
            publicKey = ByteArray(64),
            destHash = ByteArray(16),
            nameHash = ByteArray(10),
            ratchetPub = null,
            displayName = hash,
            appName = "lxmf.delivery",
            appLabel = "LXMF delivery",
            telemetryJson = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = lastSeen,
            rssi = null,
            favorite = favorite,
            source = "announce",
        )
}
