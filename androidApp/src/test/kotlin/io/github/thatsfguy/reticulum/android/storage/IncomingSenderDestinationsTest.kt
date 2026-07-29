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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * observeIncomingSenderDestinations resolves the name of anyone we've received
 * a message from, from their preserved row — even when they've dropped out of
 * observeAll's top-1000 recency window on a busy mesh. Fixes inbox / conversation
 * entries degrading to "(unknown sender)" while the row is still in the table.
 * Set 2026-07-28.
 */
@RunWith(RobolectricTestRunner::class)
class IncomingSenderDestinationsTest {

    private lateinit var db: ReticulumDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ReticulumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() { db.close() }

    private fun destination(hash: String, name: String) = DestinationEntity(
        hash = hash, identityHash = "00".repeat(16), publicKey = ByteArray(64),
        destHash = ByteArray(16), nameHash = ByteArray(10), ratchetPub = null,
        displayName = name, appName = "lxmf.delivery", appLabel = "LXMF delivery",
        telemetryJson = null, lat = null, lon = null, appDataHex = "",
        lastSeen = 1L, rssi = null, favorite = false, source = "announce",
    )

    private fun incomingMessageFrom(hash: String) = MessageEntity(
        contactHash = hash, direction = "incoming", content = "hi", title = "",
        timestamp = 1L, state = "verified", attempts = 0, lastAttempt = 0,
        lastError = null, rawPacket = null, packetHash = null, rssi = null,
    )

    @Test fun `returns the destination of an incoming-message sender`() = runTest {
        val sender = "aa".repeat(16)
        db.destinationDao().upsert(destination(sender, name = "Alice"))
        db.messageDao().insert(incomingMessageFrom(sender))

        val rows = db.destinationDao().observeIncomingSenderDestinations().first()
        assertEquals(1, rows.size)
        assertEquals("Alice", rows.single().displayName, "sender's name resolves from the preserved row")
    }

    @Test fun `ignores destinations we have no incoming message from`() = runTest {
        db.destinationDao().upsert(destination("bb".repeat(16), name = "NoMessages"))
        // An OUTGOING-only contact must not appear as an incoming sender.
        val outOnly = "cc".repeat(16)
        db.destinationDao().upsert(destination(outOnly, name = "OutgoingOnly"))
        db.messageDao().insert(
            incomingMessageFrom(outOnly).copy(direction = "outgoing"),
        )

        val rows = db.destinationDao().observeIncomingSenderDestinations().first()
        assertEquals(emptyList(), rows.map { it.displayName })
    }

    @Test fun `resolves the sender even with no destination-list limit in play`() = runTest {
        // The query has no LIMIT, so a sender is returned regardless of how many
        // other rows exist — the exact case that made observeAll (LIMIT 1000)
        // miss out-of-window conversation partners.
        val sender = "dd".repeat(16)
        db.destinationDao().upsert(destination(sender, name = "Bob"))
        db.messageDao().insert(incomingMessageFrom(sender))
        // Add many unrelated destinations that would rank ABOVE the sender.
        repeat(50) { i -> db.destinationDao().upsert(destination("%032x".format(i), name = "n$i")) }

        val resolved = db.destinationDao().observeIncomingSenderDestinations().first()
            .firstOrNull { it.hash == sender }
        assertNotNull(resolved, "the message-sender is resolved regardless of ranking")
        assertEquals("Bob", resolved.displayName)
    }
}
