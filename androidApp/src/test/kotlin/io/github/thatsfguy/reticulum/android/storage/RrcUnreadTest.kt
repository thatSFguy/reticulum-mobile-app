package io.github.thatsfguy.reticulum.android.storage

import androidx.test.core.app.ApplicationProvider
import io.github.thatsfguy.reticulum.store.StoredRrcMessage
import io.github.thatsfguy.reticulum.store.StoredRrcRoom
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
 * RRC unread accounting (schema v19) through the repository surface the
 * UI and the notification path actually use: the per-room read marker,
 * what counts as unread, and the per-room notification mode.
 */
@RunWith(RobolectricTestRunner::class)
class RrcUnreadTest {

    private lateinit var repos: Repositories
    private val hub = "ab".repeat(16)

    @Before fun setup() {
        ReticulumDatabase.closeInstanceForTest()
        repos = Repositories.create(ApplicationProvider.getApplicationContext())
    }

    @After fun teardown() { ReticulumDatabase.closeInstanceForTest() }

    private suspend fun seedRoom(name: String = "general") {
        repos.rrc.upsertRoom(StoredRrcRoom(hubHash = hub, name = name, joined = true))
    }

    /** Run one raw statement against the same database the repository
     *  uses — how the migration executes the backfill. */
    private fun runBackfill(sql: String) {
        ReticulumDatabase.get(ApplicationProvider.getApplicationContext())
            .openHelper.writableDatabase.execSQL(sql)
    }

    private suspend fun addMessage(
        room: String = "general",
        direction: String = "incoming",
        mention: Boolean = false,
    ): Long = repos.rrc.saveMessage(
        StoredRrcMessage(
            hubHash = hub, room = room, direction = direction, senderIdHash = "aa",
            nick = "bob", text = "hi", timestamp = 1L, mention = mention,
        ),
    )

    @Test fun incomingMessagesPastTheMarkerCountAsUnread() = runTest {
        seedRoom()
        addMessage()
        addMessage()
        assertEquals(mapOf("$hub/general" to UnreadTally(total = 2)), repos.observeUnreadTally().first())
    }

    @Test fun readingTheRoomClearsTheCount() = runTest {
        seedRoom()
        addMessage()
        addMessage()
        repos.markRrcRoomRead(hub, "general")
        assertTrue(repos.observeUnreadTally().first().isEmpty())
    }

    /** Our own messages, and the hub's system / error lines, are not
     *  something the user has to catch up on. */
    @Test fun onlyIncomingLinesCount() = runTest {
        seedRoom()
        addMessage(direction = "outgoing")
        addMessage(direction = "system")
        addMessage(direction = "error")
        assertTrue(repos.observeUnreadTally().first().isEmpty())
    }

    @Test fun theMarkerNeverMovesBackwards() = runTest {
        seedRoom()
        addMessage()
        val newest = addMessage()
        repos.markRrcRoomRead(hub, "general")
        // A later, older-looking write must not resurrect the unreads.
        repos.rrc.upsertRoom(StoredRrcRoom(hubHash = hub, name = "general", joined = true))
        assertEquals(newest, repos.getRrcRoom(hub, "general")?.lastReadMessageId)
        assertTrue(repos.observeUnreadTally().first().isEmpty())
    }

    /**
     * The engine re-upserts a room on every join and auto-rejoin, from
     * a model built out of the wire — where the read marker and the
     * notify mode do not exist, so both arrive at their defaults.
     * Room's REPLACE would reset them on every reconnect, resurrecting
     * unreads and un-muting a muted room.
     */
    @Test fun upsertingARoomKeepsItsNotifyMode() = runTest {
        seedRoom()
        repos.setRrcRoomNotifyMode(hub, "general", StoredRrcRoom.NOTIFY_MENTIONS)
        repos.rrc.upsertRoom(StoredRrcRoom(hubHash = hub, name = "general", joined = true))
        assertEquals(StoredRrcRoom.NOTIFY_MENTIONS, repos.getRrcRoom(hub, "general")?.notifyMode)
    }

    @Test fun mentionFlagSurvivesTheRoundTrip() = runTest {
        seedRoom()
        addMessage(mention = true)
        assertTrue(repos.rrc.getMessages(hub, "general").single().mention)
    }

    // ---- the upgrade backfill ----------------------------------------
    //
    // v19 added lastReadMessageId with a column default of 0, which
    // means "nothing in this room has ever been read" — so the upgrade
    // itself flagged every message the user had already seen as new.
    // v20 carries the repair. Both run the statement asserted here.

    @Test fun theBackfillMarksExistingHistoryAsRead() = runTest {
        seedRoom("general")
        seedRoom("lobby")
        addMessage(room = "general")
        val newestGeneral = addMessage(room = "general")
        val newestLobby = addMessage(room = "lobby")
        // Pre-repair state: everything looks unread.
        assertEquals(3, repos.observeUnreadTally().first().values.sumOf { it.total })

        runBackfill(ReticulumDatabase.BACKFILL_RRC_READ_MARKERS)

        assertEquals(newestGeneral, repos.getRrcRoom(hub, "general")?.lastReadMessageId)
        assertEquals(newestLobby, repos.getRrcRoom(hub, "lobby")?.lastReadMessageId)
        assertTrue(repos.observeUnreadTally().first().isEmpty())
    }

    /** The v20 repair is scoped to rooms still at 0 so a room the user
     *  opened after upgrading keeps the marker they earned — and so a
     *  genuinely unread room is not left permanently shouting. */
    @Test fun theRepairLeavesAnEarnedMarkerAlone() = runTest {
        seedRoom("general")
        val first = addMessage(room = "general")
        repos.markRrcRoomRead(hub, "general")
        addMessage(room = "general")

        runBackfill(ReticulumDatabase.BACKFILL_RRC_READ_MARKERS + " WHERE lastReadMessageId = 0")

        assertEquals(first, repos.getRrcRoom(hub, "general")?.lastReadMessageId)
        assertEquals(1, repos.observeUnreadTally().first().values.single().total)
    }

    @Test fun theBackfillIsHarmlessOnAnEmptyRoom() = runTest {
        seedRoom("quiet")
        runBackfill(ReticulumDatabase.BACKFILL_RRC_READ_MARKERS)
        assertEquals(0L, repos.getRrcRoom(hub, "quiet")?.lastReadMessageId)
    }

    // ---- mention counting --------------------------------------------

    /** The badge is muted for ordinary traffic and red only when some
     *  of it names us, so the two have to be counted separately. */
    @Test fun mentionsAreCountedAlongsideTheTotal() = runTest {
        seedRoom()
        addMessage()
        addMessage(mention = true)
        addMessage()
        val unread = repos.observeUnreadTally().first().values.single()
        assertEquals(3, unread.total)
        assertEquals(1, unread.mentions)
        assertTrue(unread.hasMention)
    }

    @Test fun aRoomWithNoMentionsIsNotFlagged() = runTest {
        seedRoom()
        addMessage()
        assertTrue(!repos.observeUnreadTally().first().values.single().hasMention)
    }

    @Test fun unreadIsPerRoom() = runTest {
        seedRoom("general")
        seedRoom("lobby")
        addMessage(room = "general")
        addMessage(room = "lobby")
        addMessage(room = "lobby")
        repos.markRrcRoomRead(hub, "general")
        assertEquals(mapOf("$hub/lobby" to UnreadTally(total = 2)), repos.observeUnreadTally().first())
    }
}
