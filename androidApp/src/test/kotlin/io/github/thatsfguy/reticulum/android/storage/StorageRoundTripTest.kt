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

    @Test fun contactRoundTrip() = runTest {
        val dao = db.contactDao()
        dao.upsert(ContactEntity(
            hash = "aabbccdd",
            identityHash = "11223344",
            publicKey = ByteArray(64) { it.toByte() },
            destHash  = ByteArray(16) { (it * 2).toByte() },
            nameHash  = ByteArray(10) { (it * 3).toByte() },
            ratchetPub = null,
            displayName = "Alice",
            lastSeen = 1700000000L,
            rssi = -42,
        ))
        val back = dao.get("aabbccdd")
        assertNotNull(back)
        assertEquals("Alice", back.displayName)
        assertEquals(-42, back.rssi)
        assertEquals(1, dao.getAll().size)
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

        // Update only state and attempts; other fields should be preserved (COALESCE)
        dao.updateState(id, state = "sent", attempts = 1, lastAttempt = null, lastError = null, packetHash = null)
        val back = dao.getById(id)
        assertNotNull(back)
        assertEquals("sent", back.state)
        assertEquals(1, back.attempts)
        assertEquals(0, back.lastAttempt)   // unchanged
        assertEquals("hello", back.content) // unchanged
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
}
