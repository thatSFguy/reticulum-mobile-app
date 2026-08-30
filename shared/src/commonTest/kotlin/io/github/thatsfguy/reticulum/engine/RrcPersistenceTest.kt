package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.rrc.RrcLimits
import io.github.thatsfguy.reticulum.store.InMemoryRrcRepository
import io.github.thatsfguy.reticulum.store.StoredRrcRoom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun recordOutgoingPersistsOutgoingRowWithMsgId() = runTest {
        val repo = InMemoryRrcRepository()
        val msgId = ByteArray(8) { 0x55 }
        val id = newPersistence(repo).recordOutgoing(
            hubHash = hub, room = "#general", senderIdHash = sender,
            nick = "me", text = "sent it", timestamp = 2_000L, msgId = msgId,
        )
        val row = repo.getMessages(hub, "#general").single()
        assertEquals(id, row.id)
        assertEquals("outgoing", row.direction)
        assertEquals("sent it", row.text)
        // The outgoing row must carry the envelope id so the hub's
        // fan-out echo of this same message dedups against it.
        assertEquals("55".repeat(8), row.msgId)
    }

    @Test
    fun outgoingMsgIdDedupsTheHubEcho() = runTest {
        val repo = InMemoryRrcRepository()
        val persistence = newPersistence(repo)
        val msgId = ByteArray(8) { 0x5A }
        // We send a message — persisted as an outgoing row keyed on its id.
        persistence.recordOutgoing(
            hubHash = hub, room = "#general", senderIdHash = sender,
            nick = "me", text = "echo me", timestamp = 2_000L, msgId = msgId,
        )
        // The hub fans the same message back to every room member,
        // including us, carrying the same K_ID.
        persistence.onEvent(hub, roomMessage(text = "echo me", msgId = msgId))
        // The echo must be deduped against our outgoing row — one row only.
        val rows = repo.getMessages(hub, "#general")
        assertEquals(1, rows.size)
        assertEquals("outgoing", rows.single().direction)
    }

    // ---- inline system lines -----------------------------------------

    @Test
    fun roomSystemMessagePersistsAsASystemRow() = runTest {
        val repo = InMemoryRrcRepository()
        newPersistence(repo).onEvent(
            hub,
            RrcEvent.RoomSystemMessage("#general", "members in general: alice"),
        )
        val row = repo.getMessages(hub, "#general").single()
        assertEquals("system", row.direction)
        assertEquals("", row.senderIdHash)
    }

    @Test
    fun hubRefusalPersistsAsAnErrorRow() = runTest {
        val repo = InMemoryRrcRepository()
        newPersistence(repo).onEvent(
            hub,
            RrcEvent.RoomSystemMessage("#general", "not authorized", isError = true),
        )
        assertEquals("error", repo.getMessages(hub, "#general").single().direction)
    }

    /** The hub re-sends its room-info NOTICE on every JOIN and we
     *  auto-rejoin on every reconnect — a flaky link must not slowly
     *  fill the timeline with the same line. */
    @Test
    fun repeatedSystemLineIsStoredOnce() = runTest {
        val repo = InMemoryRrcRepository()
        val persistence = newPersistence(repo)
        val line = RrcEvent.RoomSystemMessage("#general", "room general: unregistered; mode=; topic=")
        persistence.onEvent(hub, line)
        persistence.onEvent(hub, line)
        assertEquals(1, repo.getMessages(hub, "#general").size)
        // A different line in between makes the next repeat legitimate.
        persistence.onEvent(hub, RrcEvent.RoomSystemMessage("#general", "topic changed"))
        persistence.onEvent(hub, line)
        assertEquals(3, repo.getMessages(hub, "#general").size)
    }

    @Test
    fun mentionFlagIsCarriedOntoTheRow() = runTest {
        val repo = InMemoryRrcRepository()
        newPersistence(repo).onEvent(
            hub,
            RrcEvent.RoomMessage(
                room = "#general", senderIdHash = sender, nick = "bob",
                text = "@alice look", timestampMs = 1L, msgId = ByteArray(8) { 9 },
                isMention = true,
            ),
        )
        assertTrue(repo.getMessages(hub, "#general").single().mention)
    }

    // ---- hub-side membership changes ---------------------------------

    /** The engine writes the room row for a join it initiated. This is
     *  the case it cannot see: the hub put us in a room by itself (an
     *  invite), where without a row the messages would arrive for a
     *  room the user has no way to open. */
    @Test
    fun ourOwnJoinCreatesTheRoomRow() = runTest {
        val repo = InMemoryRrcRepository()
        newPersistence(repo)
            .onEvent(hub, RrcEvent.Joined("#invited", emptyList(), isSelf = true))
        val room = repo.getRoomsForHub(hub).single()
        assertEquals("#invited", room.name)
        assertTrue(room.joined)
    }

    @Test
    fun beingRemovedClearsTheJoinedFlag() = runTest {
        val repo = InMemoryRrcRepository()
        repo.upsertRoom(StoredRrcRoom(hubHash = hub, name = "#general", joined = true))
        newPersistence(repo)
            .onEvent(hub, RrcEvent.Parted("#general", emptyList(), isSelf = true))
        assertTrue(repo.getRoomsForHub(hub).single().joined == false)
    }

    @Test
    fun somebodyElsesJoinChangesNothing() = runTest {
        val repo = InMemoryRrcRepository()
        newPersistence(repo).onEvent(hub, RrcEvent.Joined("#general", emptyList()))
        assertTrue(repo.getRoomsForHub(hub).isEmpty())
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
