package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.rrc.RrcLimits
import io.github.thatsfguy.reticulum.store.InMemoryRrcRepository
import io.github.thatsfguy.reticulum.store.StoredRrcRoom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RrcPersistence] — maps an [RrcSession]'s events onto the
 * [io.github.thatsfguy.reticulum.store.RrcRepository].
 *
 * camelCase test names keep the iosTest Kotlin/Native compile happy.
 */
class RrcPersistenceTest {

    private val hub = "ab".repeat(16)
    private val sender = ByteArray(16) { 0x11 }

    private fun newPersistence(repo: InMemoryRrcRepository, now: Long = 5_000L) =
        RrcPersistence(repo, nowMs = { now })

    private fun roomMessage(
        room: String = "#general",
        text: String = "hello",
        ts: Long = 1_000L,
        msgId: ByteArray = ByteArray(8) { 0x42 },
    ) = RrcEvent.RoomMessage(
        room = room,
        senderIdHash = sender,
        nick = "bob",
        text = text,
        timestampMs = ts,
        msgId = msgId,
    )

    @Test
    fun welcomedStampsHubLastConnected() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertHub(
            io.github.thatsfguy.reticulum.store.StoredRrcHub(
                destHash = hub, displayName = "Hub", addedAt = 0L,
            ),
        )
        newPersistence(repo, now = 9_999L)
            .onEvent(hub, RrcEvent.Welcomed("MyHub", RrcLimits()))
        assertEquals(9_999L, repo.getHub(hub)?.lastConnectedAt)
    }

    @Test
    fun roomMessagePersistsAsIncomingRow() = runTest {
        val repo = InMemoryRrcRepository()
        newPersistence(repo).onEvent(hub, roomMessage(text = "hi there"))
        val rows = repo.getMessages(hub, "#general")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("incoming", row.direction)
        assertEquals("hi there", row.text)
        assertEquals("bob", row.nick)
        assertEquals("11".repeat(16), row.senderIdHash)
        assertEquals("42".repeat(8), row.msgId)
    }

    @Test
    fun duplicateMsgIdIsDropped() = runTest {
        val repo = InMemoryRrcRepository()
        val persistence = newPersistence(repo)
        val dupId = ByteArray(8) { 0x7 }
        persistence.onEvent(hub, roomMessage(text = "first", msgId = dupId))
        persistence.onEvent(hub, roomMessage(text = "echo", msgId = dupId))
        assertEquals(1, repo.getMessages(hub, "#general").size)
        assertEquals("first", repo.getMessages(hub, "#general").single().text)
    }

    @Test
    fun roomMessageBumpsRoomActivityWhenRoomExists() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertRoom(StoredRrcRoom(hub, "#general", joined = true, lastActivityAt = 100L))
        newPersistence(repo).onEvent(hub, roomMessage(ts = 8_000L))
        assertEquals(8_000L, repo.getRoomsForHub(hub).single().lastActivityAt)
        // The room's joined flag must survive the activity bump.
        assertTrue(repo.getRoomsForHub(hub).single().joined)
    }

    @Test
    fun roomMessageForUnknownRoomStillSavesHistory() = runTest {
        val repo = InMemoryRrcRepository()
        // No room row — touchRoom is a no-op but the message must
        // still be persisted so history is never lost.
        newPersistence(repo).onEvent(hub, roomMessage(room = "#ghost"))
        assertEquals(1, repo.getMessages(hub, "#ghost").size)
        assertTrue(repo.getRoomsForHub(hub).isEmpty())
    }

    @Test
    fun recordOutgoingPersistsOutgoingRow() = runTest {
        val repo = InMemoryRrcRepository()
        val id = newPersistence(repo).recordOutgoing(
            hubHash = hub, room = "#general", senderIdHash = sender,
            nick = "me", text = "sent it", timestamp = 2_000L,
        )
        val row = repo.getMessages(hub, "#general").single()
        assertEquals(id, row.id)
        assertEquals("outgoing", row.direction)
        assertEquals("sent it", row.text)
        assertNull(row.msgId)
    }

    @Test
    fun transientEventsArePersistedAsNoOps() = runTest {
        val repo = InMemoryRrcRepository()
        val persistence = newPersistence(repo)
        persistence.onEvent(hub, RrcEvent.Notice("#general", "topic changed"))
        persistence.onEvent(hub, RrcEvent.HubError("#general", "rate limited"))
        persistence.onEvent(hub, RrcEvent.Joined("#general", emptyList()))
        persistence.onEvent(hub, RrcEvent.Parted("#general", emptyList()))
        persistence.onEvent(hub, RrcEvent.StateChanged(RrcState.WELCOMED))
        assertTrue(repo.getAllHubs().isEmpty())
        assertTrue(repo.getRoomsForHub(hub).isEmpty())
        assertTrue(repo.getMessages(hub, "#general").isEmpty())
    }
}
