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
        assertEquals(mapOf("$hub/general" to 2), repos.observeRrcUnread().first())
    }

    @Test fun readingTheRoomClearsTheCount() = runTest {
        seedRoom()
        addMessage()
        addMessage()
        repos.markRrcRoomRead(hub, "general")
        assertTrue(repos.observeRrcUnread().first().isEmpty())
    }

    /** Our own messages, and the hub's system / error lines, are not
     *  something the user has to catch up on. */
    @Test fun onlyIncomingLinesCount() = runTest {
        seedRoom()
        addMessage(direction = "outgoing")
        addMessage(direction = "system")
        addMessage(direction = "error")
        assertTrue(repos.observeRrcUnread().first().isEmpty())
    }

    @Test fun theMarkerNeverMovesBackwards() = runTest {
        seedRoom()
        addMessage()
        val newest = addMessage()
        repos.markRrcRoomRead(hub, "general")
        // A later, older-looking write must not resurrect the unreads.
        repos.rrc.upsertRoom(StoredRrcRoom(hubHash = hub, name = "general", joined = true))
        assertEquals(newest, repos.getRrcRoom(hub, "general")?.lastReadMessageId)
        assertTrue(repos.observeRrcUnread().first().isEmpty())
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

    @Test fun unreadIsPerRoom() = runTest {
        seedRoom("general")
        seedRoom("lobby")
        addMessage(room = "general")
        addMessage(room = "lobby")
        addMessage(room = "lobby")
        repos.markRrcRoomRead(hub, "general")
        assertEquals(mapOf("$hub/lobby" to 2), repos.observeRrcUnread().first())
    }
}
