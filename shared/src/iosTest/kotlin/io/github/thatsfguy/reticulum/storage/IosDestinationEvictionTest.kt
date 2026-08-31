package io.github.thatsfguy.reticulum.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS half of tiered destination eviction, mirroring Android's
 * `DestinationRetentionTest`.
 *
 * Worth testing on both platforms rather than trusting the Android one:
 * these are two separately written SQL statements against two different
 * SQLite bindings, and this one DELETEs rows on every user's device.
 *
 * camelCase test names keep the Kotlin/Native compile happy.
 */
class IosDestinationEvictionTest {

    private fun newDriver(): SqlDriver =
        NativeSqliteDriver(
            ReticulumIosDatabase.Schema,
            "test_evict_${Random.nextLong().toULong().toString(16)}.db",
        )

    private fun insertNode(
        driver: SqlDriver,
        hash: String,
        lastSeen: Long,
        rssi: Long? = null,
        hopCount: Long = 3,
        displayName: String = "LXMF",
        appLabel: String? = "LXMF",
    ) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO destinations " +
                "(hash, identityHash, publicKey, destHash, nameHash, ratchetPub, displayName, " +
                " appName, appLabel, telemetryJson, lat, lon, appDataHex, lastSeen, rssi, " +
                " favorite, source, hidden, hopCount, nextHop, userLabel) " +
                "VALUES (?, ?, x'00', x'00', x'00', NULL, ?, 'lxmf.delivery', ?, NULL, NULL, " +
                " NULL, '', ?, ?, 0, 'announce', 0, ?, NULL, NULL)",
            7,
        ) {
            bindString(0, hash)
            bindString(1, hash)
            bindString(2, displayName)
            bindString(3, appLabel)
            bindLong(4, lastSeen)
            bindLong(5, rssi)
            bindLong(6, hopCount)
        }
    }

    private fun survivors(driver: SqlDriver, db: ReticulumIosDatabase, keep: Long): Set<String> {
        db.reticulumIosDatabaseQueries.evictUnfavoritedOldest(keep)
        return db.reticulumIosDatabaseQueries.selectAllDestinations()
            .executeAsList().map { it.hash }.toSet()
    }

    /** A node heard first-hand over the radio outlives a fresher
     *  stranger from across a TCP mesh. */
    @Test fun aRadioNeighbourOutlivesAFresherStranger() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertNode(driver, "aa", lastSeen = 1, rssi = -95, hopCount = 1)
        insertNode(driver, "bb", lastSeen = 9_999)
        assertEquals(setOf("aa"), survivors(driver, db, keep = 1))
        driver.close()
    }

    /** RSSI alone is not "nearby" — the radio measures it on relayed
     *  announces too, so a multi-hop row gets no protection from it. */
    @Test fun aRelayedRowIsNotTreatedAsANeighbourJustForHavingRssi() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertNode(driver, "aa", lastSeen = 1, rssi = -95, hopCount = 4)
        insertNode(driver, "bb", lastSeen = 9_999)
        assertEquals(setOf("bb"), survivors(driver, db, keep = 1))
        driver.close()
    }

    @Test fun aNamedNodeOutlivesAnAnonymousOne() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertNode(driver, "aa", lastSeen = 1, displayName = "Rob's base")
        insertNode(driver, "bb", lastSeen = 9_999)
        assertEquals(setOf("aa"), survivors(driver, db, keep = 1))
        driver.close()
    }

    /** The generic service label is not a name — an unnamed LXMF peer
     *  carries it as its displayName and must not be promoted for it. */
    @Test fun theGenericServiceLabelDoesNotCountAsAName() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertNode(driver, "aa", lastSeen = 1, displayName = "LXMF", appLabel = "LXMF")
        insertNode(driver, "bb", lastSeen = 9_999, displayName = "LXMF", appLabel = "LXMF")
        assertEquals(setOf("bb"), survivors(driver, db, keep = 1))
        driver.close()
    }

    /** Tiering reorders; it does not replace recency. */
    @Test fun recencyStillDecidesInsideATier() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertNode(driver, "aa", lastSeen = 1, rssi = -95, hopCount = 1)
        insertNode(driver, "bb", lastSeen = 9_999, rssi = -95, hopCount = 1)
        assertEquals(setOf("bb"), survivors(driver, db, keep = 1))
        driver.close()
    }

    /** Tiering must not change how many rows are kept. */
    @Test fun theKeptCountIsUnchangedByTiering() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        for (i in 0 until 20) {
            insertNode(
                driver,
                hash = "n$i",
                lastSeen = i.toLong(),
                rssi = if (i % 2 == 0) -95L else null,
                hopCount = if (i % 2 == 0) 1L else 3L,
            )
        }
        assertEquals(5, survivors(driver, db, keep = 5).size)
        driver.close()
    }
}
