package io.github.thatsfguy.reticulum.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        appName: String = "lxmf.delivery",
    ) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO destinations " +
                "(hash, identityHash, publicKey, destHash, nameHash, ratchetPub, displayName, " +
                " appName, appLabel, telemetryJson, lat, lon, appDataHex, lastSeen, rssi, " +
                " favorite, source, hidden, hopCount, nextHop, userLabel) " +
                "VALUES (?, ?, x'00', x'00', x'00', NULL, ?, ?, ?, NULL, NULL, " +
                " NULL, '', ?, ?, 0, 'announce', 0, ?, NULL, NULL)",
            8,
        ) {
            bindString(0, hash)
            bindString(1, hash)
            bindString(2, displayName)
            bindString(3, appName)
            bindString(4, appLabel)
            bindLong(5, lastSeen)
            bindLong(6, rssi)
            bindLong(7, hopCount)
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
        // Compared against a FIRST-HAND row: the relayed one loses even
        // though it is fresher. (It used to be compared against an
        // off-RF row and lose that too — F4 gave anything our radio
        // heard a tier of its own.)
        insertNode(driver, "aa", lastSeen = 9_999, rssi = -95, hopCount = 4)
        insertNode(driver, "bb", lastSeen = 1, rssi = -95, hopCount = 1)
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

    /**
     * A hub is infrastructure you join, not churn — few in number and
     * deliberately slow to announce, which is exactly what made it lose
     * a recency race against propagation and delivery announces. Like
     * propagation nodes, hubs are exempt from the count cap.
     */
    @Test fun anRrcHubSurvivesEvictionNoMatterHowStaleItIs() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertNode(driver, "hub", lastSeen = 1, appName = "rrc.hub", displayName = "MichMesh RRC Hub")
        for (i in 0 until 20) insertNode(driver, "n$i", lastSeen = 500L + i)
        assertTrue(survivors(driver, db, keep = 3).contains("hub"), "a hub must not be evicted")
        driver.close()
    }

    /**
     * The named tier is attacker-assignable — a display name is
     * whatever the announcer wrote in its own app_data — so it must not
     * outrank a locally-measured signal. Audit reference: 2026-08-31 F4.
     */
    @Test fun aNamedStrangerDoesNotOutrankAnythingOurRadioHeard() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertNode(driver, "heard", lastSeen = 1, rssi = -110, hopCount = 3)
        insertNode(driver, "named", lastSeen = 9_999, displayName = "Totally Legit Node")
        assertEquals(setOf("heard"), survivors(driver, db, keep = 1))
        driver.close()
    }

    /**
     * The exemption is an allowance, not an absence of one.
     *
     * `appName` is resolved from the announce's public `name_hash`, so
     * anyone can announce as `rrc.hub` from unlimited self-signed
     * identities. Exempting the aspect from the shared cap made it the
     * one place in the table where rows accumulate forever; the
     * per-aspect trim is what bounds it. Audit reference: 2026-08-31 F1.
     */
    @Test fun aFloodOfSelfDeclaredHubsIsTrimmedToThePerAspectCap() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        val q = db.reticulumIosDatabaseQueries
        for (i in 0 until 300) {
            insertNode(driver, "h$i", lastSeen = 1_000L + i, appName = "rrc.hub")
        }
        // The shared eviction skips the aspect, by design.
        q.evictUnfavoritedOldest(10)
        assertEquals(
            300,
            q.selectAllDestinations().executeAsList().count { it.appName == "rrc.hub" },
            "precondition: the shared cap does not touch an exempt aspect",
        )

        q.evictByAppNameOldest("rrc.hub", 256)
        assertEquals(
            256,
            q.selectAllDestinations().executeAsList().count { it.appName == "rrc.hub" },
        )
        driver.close()
    }

    /** Newest survives the per-aspect trim, oldest loses. */
    @Test fun thePerAspectTrimKeepsTheNewestRows() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        insertNode(driver, "old", lastSeen = 1, appName = "rrc.hub")
        insertNode(driver, "new", lastSeen = 9, appName = "rrc.hub")
        db.reticulumIosDatabaseQueries.evictByAppNameOldest("rrc.hub", 1)
        assertEquals(
            listOf("new"),
            db.reticulumIosDatabaseQueries.selectAllDestinations()
                .executeAsList().filter { it.appName == "rrc.hub" }.map { it.hash },
        )
        driver.close()
    }

    /** Trimming one aspect must not touch anything else in the table. */
    @Test fun thePerAspectTrimTouchesOnlyThatAspect() {
        val driver = newDriver()
        val db = ReticulumIosDatabase(driver)
        for (i in 0 until 5) insertNode(driver, "h$i", lastSeen = 1L + i, appName = "rrc.hub")
        for (i in 0 until 5) insertNode(driver, "d$i", lastSeen = 1L + i)
        db.reticulumIosDatabaseQueries.evictByAppNameOldest("rrc.hub", 0)
        val left = db.reticulumIosDatabaseQueries.selectAllDestinations().executeAsList()
        assertEquals(0, left.count { it.appName == "rrc.hub" })
        assertEquals(5, left.count { it.appName == "lxmf.delivery" })
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
