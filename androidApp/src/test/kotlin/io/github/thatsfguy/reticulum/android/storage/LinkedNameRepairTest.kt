package io.github.thatsfguy.reticulum.android.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The v22 → v23 repair that un-sticks placeholder names on
 * link-discovered nodes.
 *
 * Exercises `ReticulumDatabase.MIGRATION_22_23` itself — the same object
 * the database runs on upgrade — rather than a copy of its SQL. It is
 * data-only (v22 and v23 have identical columns), so a current-schema
 * database populated with the pre-fix shape stands in faithfully for an
 * install arriving from v22.
 *
 * Worth testing carefully for the reason every repair is: it runs on
 * every existing install and it destroys data. The thing it must never
 * destroy is a nickname the user actually typed.
 */
@RunWith(RobolectricTestRunner::class)
class LinkedNameRepairTest {

    @Before fun setup() {
        ReticulumDatabase.closeInstanceForTest()
        ReticulumDatabase.get(ApplicationProvider.getApplicationContext())
    }

    @After fun teardown() { ReticulumDatabase.closeInstanceForTest() }

    private fun db() =
        ReticulumDatabase.get(ApplicationProvider.getApplicationContext()).openHelper.writableDatabase

    private fun repair() = ReticulumDatabase.MIGRATION_22_23.migrate(db())

    /** Insert a destination row the way the pre-fix code wrote one:
     *  provenance string in `userLabel`, real name in `displayName`. */
    private fun insertDest(hash: String, displayName: String, userLabel: String?) {
        db().execSQL(
            "INSERT OR REPLACE INTO destinations " +
                "(hash, identityHash, publicKey, destHash, nameHash, ratchetPub, displayName, " +
                " appName, appLabel, telemetryJson, lat, lon, appDataHex, lastSeen, rssi, favorite, " +
                " source, hidden, hopCount, nextHop, userLabel) " +
                "VALUES (?, '', X'', X'', X'', NULL, ?, NULL, NULL, NULL, NULL, NULL, '', 0, NULL, 1, " +
                " 'manual', 0, 0, NULL, ?)",
            arrayOf(hash, displayName, userLabel),
        )
    }

    private fun userLabelOf(hash: String): String? =
        db().query("SELECT userLabel FROM destinations WHERE hash = ?", arrayOf(hash)).use {
            if (it.moveToFirst()) (if (it.isNull(0)) null else it.getString(0)) else null
        }

    private fun displayNameOf(hash: String): String =
        db().query("SELECT displayName FROM destinations WHERE hash = ?", arrayOf(hash)).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }

    @Test fun everyPlaceholderWeEverWroteIsCleared() {
        val placeholders = listOf(
            "(via cross-node link)",
            "(via cross-node form)",
            "(via shared link)",
            "(via nomad link)",
        )
        for ((i, placeholder) in placeholders.withIndex()) {
            insertDest("%02x".format(i).repeat(16), "Amber Pages", placeholder)
        }
        repair()
        for (i in placeholders.indices) {
            val hash = "%02x".format(i).repeat(16)
            assertNull(userLabelOf(hash), "placeholder ${placeholders[i]} survived the repair")
            // The announced name was there all along, masked.
            assertEquals("Amber Pages", displayNameOf(hash))
        }
    }

    /** The whole risk of this migration in one test: a nickname the
     *  user typed must survive, including one that merely looks like
     *  parenthesised prose. Only our four exact strings go. */
    @Test fun aRealUserNicknameIsUntouched() {
        val kept = mapOf(
            "aa".repeat(16) to "Dad's node",
            "bb".repeat(16) to "(the one in the barn)",
            "cc".repeat(16) to "via cross-node link",
            "dd".repeat(16) to "(via cross-node link) mirror",
        )
        for ((hash, label) in kept) insertDest(hash, "", label)
        repair()
        for ((hash, label) in kept) assertEquals(label, userLabelOf(hash), "clobbered $label")
    }

    @Test fun aRowWithNoLabelIsUnaffected() {
        val hash = "ee".repeat(16)
        insertDest(hash, "Interlib", null)
        repair()
        assertNull(userLabelOf(hash))
        assertEquals("Interlib", displayNameOf(hash))
    }

    /** Runs on every install, including ones with nothing to repair. */
    @Test fun repairIsIdempotent() {
        val hash = "ff".repeat(16)
        insertDest(hash, "Amber Pages", "(via cross-node link)")
        repair()
        repair()
        assertNull(userLabelOf(hash))
        assertEquals("Amber Pages", displayNameOf(hash))
    }
}
