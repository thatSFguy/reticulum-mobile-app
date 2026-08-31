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

    // ---- reactions (rrc-extensions.md) --------------------------------

    private val targetId = "a41b9c33d2e05f18"

    private suspend fun seedTarget(
        repo: InMemoryRrcRepository,
        room: String = "#general",
        msgId: String = targetId,
    ) {
        repo.saveMessage(
            io.github.thatsfguy.reticulum.store.StoredRrcMessage(
                hubHash = hub, room = room, direction = "incoming",
                senderIdHash = "bb", nick = "bob", text = "the message",
                timestamp = 1L, msgId = msgId,
            ),
        )
    }

    @Test
    fun aReactionIsFoldedOntoItsTarget() = runTest {
        val repo = InMemoryRrcRepository()
        seedTarget(repo)
        newPersistence(repo).onEvent(
            hub,
            RrcEvent.RoomReaction("#general", targetId, "\uD83D\uDC4D", "cc"),
        )
        val row = repo.getMessages(hub, "#general").single()
        val reactions = io.github.thatsfguy.reticulum.store.ReactionsJson.decode(row.reactionsJson)
        assertEquals(listOf("cc"), reactions["\uD83D\uDC4D"])
        // And never as a line of its own.
        assertEquals(1, repo.getMessages(hub, "#general").size)
    }

    /**
     * A K_ID is 8 sender-chosen random bytes that no hub enforces
     * uniqueness on, so resolving one outside the room it arrived in
     * would let a reaction be steered onto an unrelated message
     * (`rrc-extensions.md` §5).
     */
    @Test
    fun aReactionNeverResolvesAcrossRooms() = runTest {
        val repo = InMemoryRrcRepository()
        seedTarget(repo, room = "#general")
        newPersistence(repo).onEvent(
            hub,
            RrcEvent.RoomReaction("#other", targetId, "\uD83D\uDC4D", "cc"),
        )
        assertNullReactions(repo, "#general")
    }

    /** "If it is not held, drop the reaction silently. Do not display
     *  it as a message." (§3) */
    @Test
    fun aReactionForAnUnheldMessageIsDroppedSilently() = runTest {
        val repo = InMemoryRrcRepository()
        newPersistence(repo).onEvent(
            hub,
            RrcEvent.RoomReaction("#general", targetId, "\uD83D\uDC4D", "cc"),
        )
        assertTrue(repo.getMessages(hub, "#general").isEmpty())
    }

    /** Apply and retract are idempotent BECAUSE the mesh is lossy and a
     *  message can arrive twice — a toggle would flip twice and land in
     *  the wrong state (§2). */
    @Test
    fun applyingTwiceIsTheSameAsApplyingOnce() = runTest {
        val repo = InMemoryRrcRepository()
        seedTarget(repo)
        val p = newPersistence(repo)
        val event = RrcEvent.RoomReaction("#general", targetId, "\uD83D\uDC4D", "cc")
        p.onEvent(hub, event)
        p.onEvent(hub, event)
        val reactions = io.github.thatsfguy.reticulum.store.ReactionsJson
            .decode(repo.getMessages(hub, "#general").single().reactionsJson)
        assertEquals(listOf("cc"), reactions["\uD83D\uDC4D"])
    }

    @Test
    fun retractingRemovesOnlyThatReactorsEntry() = runTest {
        val repo = InMemoryRrcRepository()
        seedTarget(repo)
        val p = newPersistence(repo)
        p.onEvent(hub, RrcEvent.RoomReaction("#general", targetId, "\uD83D\uDC4D", "cc"))
        p.onEvent(hub, RrcEvent.RoomReaction("#general", targetId, "\uD83D\uDC4D", "dd"))
        p.onEvent(
            hub,
            RrcEvent.RoomReaction("#general", targetId, "\uD83D\uDC4D", "cc", retract = true),
        )
        val reactions = io.github.thatsfguy.reticulum.store.ReactionsJson
            .decode(repo.getMessages(hub, "#general").single().reactionsJson)
        assertEquals(listOf("dd"), reactions["\uD83D\uDC4D"])
    }

    @Test
    fun retractingTwiceIsHarmless() = runTest {
        val repo = InMemoryRrcRepository()
        seedTarget(repo)
        val p = newPersistence(repo)
        val retract =
            RrcEvent.RoomReaction("#general", targetId, "\uD83D\uDC4D", "cc", retract = true)
        p.onEvent(hub, retract)
        p.onEvent(hub, retract)
        assertNullReactions(repo, "#general")
    }

    @Test
    fun aReplyRecordsItsAnchor() = runTest {
        val repo = InMemoryRrcRepository()
        newPersistence(repo).onEvent(
            hub,
            RrcEvent.RoomMessage(
                room = "#general", senderIdHash = sender, nick = "bob",
                text = "yes, exactly that", timestampMs = 1L,
                msgId = ByteArray(8) { 7 }, replyToMsgId = targetId,
            ),
        )
        assertEquals(targetId, repo.getMessages(hub, "#general").single().replyToMsgId)
    }

    private suspend fun assertNullReactions(repo: InMemoryRrcRepository, room: String) {
        val row = repo.getMessages(hub, room).singleOrNull() ?: return
        val reactions = io.github.thatsfguy.reticulum.store.ReactionsJson.decode(row.reactionsJson)
        assertTrue(reactions.isEmpty(), "expected no reactions, got $reactions")
    }

    /**
     * The LXMF store path has bounded stored text since audit
     * 2026-07-28; the RRC one did not. Everything on an RRC row is
     * hub-supplied, and a `notice`/`motd` Resource can carry up to
     * RRC_MAX_RESOURCE_BYTES of it. Audit reference: 2026-08-31 F7.
     */
    @Test
    fun storedRoomTextAndNickAreBounded() = runTest {
        val repo = InMemoryRrcRepository()
        val persistence = newPersistence(repo)
        val huge = "x".repeat(200 * 1024)
        persistence.onEvent(
            hub,
            RrcEvent.RoomMessage(
                room = "#general", senderIdHash = ByteArray(16) { 3 }, nick = huge,
                text = huge, timestampMs = 1L, msgId = ByteArray(8) { 1 },
            ),
        )
        val row = repo.getMessages(hub, "#general").single()
        assertTrue(row.text.length < huge.length, "stored text must be bounded")
        assertTrue((row.nick?.length ?: 0) < huge.length, "stored nick must be bounded")
    }

    /** An ordinary line is stored byte-for-byte — the bound is a
     *  backstop, not a truncation the user can notice. */
    @Test
    fun anOrdinaryRoomLineIsStoredIntact() = runTest {
        val repo = InMemoryRrcRepository()
        val persistence = newPersistence(repo)
        persistence.onEvent(
            hub,
            RrcEvent.RoomMessage(
                room = "#general", senderIdHash = ByteArray(16) { 3 }, nick = "bob",
                text = "yes, exactly that", timestampMs = 1L, msgId = ByteArray(8) { 1 },
            ),
        )
        val row = repo.getMessages(hub, "#general").single()
        assertEquals("yes, exactly that", row.text)
        assertEquals("bob", row.nick)
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
