package io.github.thatsfguy.reticulum.android.storage

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v21 → v22 repair for rooms split in two by a `#`.
 *
 * This exercises `ReticulumDatabase.MIGRATION_21_22` itself — the same
 * object the database runs on upgrade — rather than a copy of its SQL.
 * It is data-only (v21 and v22 have identical columns), so a
 * current-schema database populated with the pre-fix shape is a
 * faithful stand-in for an install arriving from v21.
 *
 * Worth testing carefully: it runs on every existing install, and it
 * DELETES rows.
 */
@RunWith(RobolectricTestRunner::class)
class RrcRoomNameRepairTest {

    private lateinit var repos: Repositories
    private val hub = "ab".repeat(16)
    private val otherHub = "cd".repeat(16)

    @Before fun setup() {
        ReticulumDatabase.closeInstanceForTest()
        repos = Repositories.create(ApplicationProvider.getApplicationContext())
    }

    @After fun teardown() { ReticulumDatabase.closeInstanceForTest() }

    private fun db() =
        ReticulumDatabase.get(ApplicationProvider.getApplicationContext()).openHelper.writableDatabase

    private fun repair() = ReticulumDatabase.MIGRATION_21_22.migrate(db())

    /** Insert a room row bypassing the repository, so a de-normalised
     *  name can be written the way the pre-fix code wrote one. */
    private fun insertRoom(hubHash: String, name: String, joined: Boolean, read: Long = 0) {
        db().execSQL(
            "INSERT OR REPLACE INTO rrc_room " +
                "(hubHash, name, joined, lastActivityAt, lastReadMessageId, notifyMode) " +
                "VALUES (?, ?, ?, 0, ?, 'all')",
            arrayOf(hubHash, name, if (joined) 1 else 0, read),
        )
    }

    private fun insertMessage(hubHash: String, room: String, text: String, direction: String) {
        db().execSQL(
            "INSERT INTO rrc_message " +
                "(hubHash, room, direction, senderIdHash, nick, text, timestamp, msgId, mention) " +
                "VALUES (?, ?, ?, 'aa', NULL, ?, 1, NULL, 0)",
            arrayOf(hubHash, room, direction, text),
        )
    }

    private fun roomNames(hubHash: String = hub): List<String> {
        val out = mutableListOf<String>()
        db().query("SELECT name FROM rrc_room WHERE hubHash = ? ORDER BY name", arrayOf(hubHash))
            .use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    private fun joinedOf(name: String): Boolean? {
        db().query(
            "SELECT joined FROM rrc_room WHERE hubHash = ? AND name = ?", arrayOf(hub, name),
        ).use { return if (it.moveToNext()) it.getInt(0) != 0 else null }
    }

    @Test fun aSigiledRoomIsRenamedToWhatTheHubCallsIt() = runTest {
        insertRoom(hub, "#general", joined = true)
        repair()
        assertEquals(listOf("general"), roomNames())
        assertEquals(true, joinedOf("general"))
    }

    @Test fun caseAndPaddingAreNormalisedToo() = runTest {
        insertRoom(hub, "  #General ", joined = true)
        repair()
        assertEquals(listOf("general"), roomNames())
    }

    /** The split history is the other half of the bug: our own sends
     *  went to `#general`, the hub's fan-out to `general`. */
    @Test fun splitHistoryIsReunited() = runTest {
        insertRoom(hub, "#general", joined = true)
        insertMessage(hub, "#general", "mine", "outgoing")
        insertMessage(hub, "general", "theirs", "incoming")
        repair()
        assertEquals(2, repos.rrc.getMessages(hub, "general").size)
        assertTrue(repos.rrc.getMessages(hub, "#general").isEmpty())
    }

    /** Both spellings can exist. The normalised row is the one the hub
     *  agrees with, so it survives — and it must not lose a joined flag
     *  that only the de-normalised twin carried. */
    @Test fun aDuplicateIsMergedAndJoinedIsCarriedOver() = runTest {
        insertRoom(hub, "general", joined = false, read = 7)
        insertRoom(hub, "#general", joined = true)
        repair()
        assertEquals(listOf("general"), roomNames())
        assertEquals(true, joinedOf("general"), "joined must survive the merge")
        // The surviving row keeps its own read marker.
        assertEquals(7L, repos.getRrcRoom(hub, "general")?.lastReadMessageId)
    }

    @Test fun anAlreadyNormalisedRoomIsUntouched() = runTest {
        insertRoom(hub, "general", joined = true, read = 3)
        insertMessage(hub, "general", "hi", "incoming")
        repair()
        assertEquals(listOf("general"), roomNames())
        assertEquals(true, joinedOf("general"))
        assertEquals(3L, repos.getRrcRoom(hub, "general")?.lastReadMessageId)
        assertEquals(1, repos.rrc.getMessages(hub, "general").size)
    }

    /** The repair must not reach across hubs — two hubs can each have a
     *  room of the same name, and they are different rooms. */
    @Test fun roomsOnDifferentHubsAreNotMerged() = runTest {
        insertRoom(hub, "#general", joined = true)
        insertRoom(otherHub, "general", joined = false)
        repair()
        assertEquals(listOf("general"), roomNames(hub))
        assertEquals(listOf("general"), roomNames(otherHub))
        assertEquals(true, joinedOf("general"))
    }

    @Test fun repairingTwiceChangesNothing() = runTest {
        insertRoom(hub, "#general", joined = true)
        insertMessage(hub, "#general", "mine", "outgoing")
        repair()
        val after = roomNames() to repos.rrc.getMessages(hub, "general").size
        repair()
        assertEquals(after.first, roomNames())
        assertEquals(after.second, repos.rrc.getMessages(hub, "general").size)
    }

    @Test fun anEmptyDatabaseSurvivesTheRepair() = runTest {
        repair()
        assertTrue(roomNames().isEmpty())
        assertNull(repos.getRrcRoom(hub, "general"))
    }
}
