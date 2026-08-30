package io.github.thatsfguy.reticulum.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS half of the v11 → v12 repair for rooms split in two by a `#`
 * (`11.sqm`), the counterpart of Android's `RrcRoomNameRepairTest` for
 * `MIGRATION_21_22`.
 *
 * Worth testing on both platforms rather than trusting the Android one:
 * these are two separately written SQL scripts, and this one runs on
 * every existing iOS install and DELETEs rows.
 *
 * It drives `ReticulumIosDatabase.Schema.migrate(driver, 11, 12)` — the
 * generated migration itself, not a copy of its statements. Running it
 * against a current-schema database is faithful because 11.sqm is
 * data-only: v11 and v12 have identical columns, so the only difference
 * between a real v11 install and this fixture is the data, which is
 * exactly what the fixture supplies.
 *
 * camelCase test names keep the Kotlin/Native compile happy.
 */
class IosRrcRoomNameRepairTest {

    private val hub = "ab".repeat(16)
    private val otherHub = "cd".repeat(16)

    /** A fresh on-disk database per test — NativeSqliteDriver is
     *  file-backed, and a random name keeps runs isolated. */
    private fun newDriver(): SqlDriver =
        NativeSqliteDriver(
            ReticulumIosDatabase.Schema,
            "test_rrcrepair_${Random.nextLong().toULong().toString(16)}.db",
        )

    /** Run only the 11 → 12 step: the room-name repair. */
    private fun repair(driver: SqlDriver) {
        ReticulumIosDatabase.Schema.migrate(driver, 11L, 12L)
    }

    /** Insert a room row directly, so a de-normalised name can be
     *  written the way the pre-fix code wrote one. */
    private fun insertRoom(
        driver: SqlDriver,
        hubHash: String,
        name: String,
        joined: Boolean,
        read: Long = 0L,
    ) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO rrc_room " +
                "(hubHash, name, joined, lastActivityAt, lastReadMessageId, notifyMode) " +
                "VALUES (?, ?, ?, 0, ?, 'all')",
            4,
        ) {
            bindString(0, hubHash)
            bindString(1, name)
            bindLong(2, if (joined) 1L else 0L)
            bindLong(3, read)
        }
    }

    private fun insertMessage(
        driver: SqlDriver,
        hubHash: String,
        room: String,
        text: String,
        direction: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO rrc_message " +
                "(hubHash, room, direction, senderIdHash, nick, text, timestamp, msgId, mention) " +
                "VALUES (?, ?, ?, 'aa', NULL, ?, 1, NULL, 0)",
            4,
        ) {
            bindString(0, hubHash)
            bindString(1, room)
            bindString(2, direction)
            bindString(3, text)
        }
    }

    private fun rooms(db: ReticulumIosDatabase, hubHash: String) =
        db.reticulumIosDatabaseQueries.selectRrcRoomsForHub(hubHash).executeAsList()

    private fun messages(db: ReticulumIosDatabase, hubHash: String, room: String) =
        db.reticulumIosDatabaseQueries.selectRrcMessages(hubHash, room).executeAsList()

    @Test fun aSigiledRoomIsRenamedToWhatTheHubCallsIt() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertRoom(driver, hub, "#general", joined = true)
        repair(driver)
        val row = rooms(db, hub).single()
        assertEquals("general", row.name)
        assertEquals(1L, row.joined)
        driver.close()
    }

    @Test fun caseAndPaddingAreNormalisedToo() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertRoom(driver, hub, "  #General ", joined = true)
        repair(driver)
        assertEquals("general", rooms(db, hub).single().name)
        driver.close()
    }

    /** The split history is the other half of the bug: our own sends
     *  went to `#general`, the hub's fan-out to `general`. */
    @Test fun splitHistoryIsReunited() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertRoom(driver, hub, "#general", joined = true)
        insertMessage(driver, hub, "#general", "mine", "outgoing")
        insertMessage(driver, hub, "general", "theirs", "incoming")
        repair(driver)
        assertEquals(2, messages(db, hub, "general").size)
        assertTrue(messages(db, hub, "#general").isEmpty())
        driver.close()
    }

    /** Both spellings can exist. The normalised row is the one the hub
     *  agrees with, so it survives — and it must not lose a joined flag
     *  that only the de-normalised twin carried. */
    @Test fun aDuplicateIsMergedAndJoinedIsCarriedOver() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertRoom(driver, hub, "general", joined = false, read = 7L)
        insertRoom(driver, hub, "#general", joined = true)
        repair(driver)
        val row = rooms(db, hub).single()
        assertEquals("general", row.name)
        assertEquals(1L, row.joined, "joined must survive the merge")
        assertEquals(7L, row.lastReadMessageId, "the survivor keeps its read marker")
        driver.close()
    }

    @Test fun anAlreadyNormalisedRoomIsUntouched() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertRoom(driver, hub, "general", joined = true, read = 3L)
        insertMessage(driver, hub, "general", "hi", "incoming")
        repair(driver)
        val row = rooms(db, hub).single()
        assertEquals("general", row.name)
        assertEquals(1L, row.joined)
        assertEquals(3L, row.lastReadMessageId)
        assertEquals(1, messages(db, hub, "general").size)
        driver.close()
    }

    /** The repair must not reach across hubs — two hubs can each have a
     *  room of the same name, and they are different rooms. */
    @Test fun roomsOnDifferentHubsAreNotMerged() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertRoom(driver, hub, "#general", joined = true)
        insertRoom(driver, otherHub, "general", joined = false)
        repair(driver)
        assertEquals("general", rooms(db, hub).single().name)
        assertEquals(1L, rooms(db, hub).single().joined)
        assertEquals("general", rooms(db, otherHub).single().name)
        assertEquals(0L, rooms(db, otherHub).single().joined)
        driver.close()
    }

    @Test fun repairingTwiceChangesNothing() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertRoom(driver, hub, "#general", joined = true)
        insertMessage(driver, hub, "#general", "mine", "outgoing")
        repair(driver)
        val namesAfterFirst = rooms(db, hub).map { it.name }
        val countAfterFirst = messages(db, hub, "general").size
        repair(driver)
        assertEquals(namesAfterFirst, rooms(db, hub).map { it.name })
        assertEquals(countAfterFirst, messages(db, hub, "general").size)
        driver.close()
    }

    @Test fun anEmptyDatabaseSurvivesTheRepair() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        repair(driver)
        assertTrue(rooms(db, hub).isEmpty())
        driver.close()
    }
}
