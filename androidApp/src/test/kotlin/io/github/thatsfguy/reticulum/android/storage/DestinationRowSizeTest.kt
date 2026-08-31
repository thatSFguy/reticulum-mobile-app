package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.thatsfguy.reticulum.engine.CURSOR_WINDOW_BUDGET_BYTES
import io.github.thatsfguy.reticulum.engine.MAX_DESTINATIONS
import io.github.thatsfguy.reticulum.engine.MAX_STORED_TELEMETRY_TOTAL_CHARS
import io.github.thatsfguy.reticulum.engine.MAX_STORED_TELEMETRY_VALUE_CHARS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/**
 * What a row of the Nodes list actually costs, measured.
 *
 * [MAX_DESTINATIONS] is a byte budget wearing a row count: Room hands a
 * `Flow` its entire result through one 2 MB CursorWindow, and the last
 * time that budget was guessed at rather than measured, a tester's
 * phone crashed with "Couldn't read row 1123, col 0 from CursorWindow".
 *
 * So this measures the row instead of estimating it, and fails if the
 * cap no longer fits — a new column, a loosened bound, or a raised cap
 * breaks the build here rather than in the field.
 */
@RunWith(RobolectricTestRunner::class)
class DestinationRowSizeTest {

    private lateinit var db: ReticulumDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ReticulumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() { db.close() }

    /**
     * The widest telemetry the ingest path can store, derived from the
     * ingest constants rather than restated — raise either bound and
     * this row grows, which is what should trip the assertion below.
     */
    private fun worstTelemetryJson(): String {
        val pairs = mutableListOf<Pair<String, String>>()
        var spent = 0
        var i = 0
        while (spent < MAX_STORED_TELEMETRY_TOTAL_CHARS) {
            val key = "k$i"
            val room = MAX_STORED_TELEMETRY_TOTAL_CHARS - spent - key.length - 2
            if (room <= 0) break
            val value = "v".repeat(minOf(room, MAX_STORED_TELEMETRY_VALUE_CHARS))
            pairs += key to value
            spent += key.length + value.length + 2
            i++
        }
        return pairs.joinToString(",", "{", "}") { "\"${it.first}\":\"${it.second}\"" }
    }

    private suspend fun insert(i: Int, worst: Boolean) {
        db.destinationDao().upsert(
            DestinationEntity(
                hash = "%032x".format(i),
                identityHash = "%032x".format(i),
                publicKey = ByteArray(64),
                destHash = ByteArray(16),
                nameHash = ByteArray(10),
                ratchetPub = ByteArray(32),
                // Announce.kt caps a display name at 64 chars; 4-byte
                // emoji is the worst those 64 chars can weigh.
                displayName = if (worst) "📡".repeat(64) else "node-$i",
                appName = "lxmf.delivery",
                appLabel = "LXMF",
                telemetryJson = if (worst) worstTelemetryJson() else "{\"battery\":\"87\",\"temp\":\"21.5\"}",
                lat = 44.1, lon = -85.2,
                // Deliberately fat: the point is that it does NOT ride
                // the list query.
                appDataHex = "ab".repeat(4 * 1024),
                lastSeen = i.toLong(),
                rssi = -101,
                favorite = false,
                source = "announce",
                hopCount = 3,
                nextHop = ByteArray(16),
                userLabel = null,
            ),
        )
    }

    /** Bytes the projection actually hands back, summed per row. */
    private fun bytesPerRow(rows: List<DestinationEntity>): Int {
        var total = 0L
        for (r in rows) {
            total += r.hash.toByteArray().size + r.identityHash.toByteArray().size
            total += r.publicKey.size + r.destHash.size + r.nameHash.size
            total += (r.ratchetPub?.size ?: 0) + (r.nextHop?.size ?: 0)
            total += r.displayName.toByteArray().size
            total += (r.appName?.toByteArray()?.size ?: 0) + (r.appLabel?.toByteArray()?.size ?: 0)
            total += (r.telemetryJson?.toByteArray()?.size ?: 0)
            total += r.appDataHex.toByteArray().size
            total += r.source.toByteArray().size + (r.userLabel?.toByteArray()?.size ?: 0)
            total += 8 * 3 + 4 * 4   // lat, lon, lastSeen + rssi/favorite/hidden/hopCount
        }
        return (total / rows.size).toInt()
    }

    private suspend fun measure(worst: Boolean): Int {
        db.clearAllTables()
        repeat(200) { insert(it, worst) }
        return bytesPerRow(db.destinationDao().observeAll().first())
    }

    /**
     * The invariant the cap rests on: even if every row maxed every
     * bound at once, the list stays inside the window budget.
     */
    @Test fun theCapFitsTheCursorWindowAtItsWorstCase() = runTest {
        val worst = measure(worst = true)
        val typical = measure(worst = false)
        val worstTotal = worst.toLong() * MAX_DESTINATIONS
        println(
            "listed row: typical=${typical}B worst=${worst}B | " +
                "cap=$MAX_DESTINATIONS -> typical=${typical.toLong() * MAX_DESTINATIONS / 1024}KiB " +
                "worst=${worstTotal / 1024}KiB budget=${CURSOR_WINDOW_BUDGET_BYTES / 1024}KiB",
        )
        assertTrue(
            worstTotal <= CURSOR_WINDOW_BUDGET_BYTES,
            "$MAX_DESTINATIONS rows × ${worst}B = ${worstTotal / 1024}KiB exceeds the " +
                "${CURSOR_WINDOW_BUDGET_BYTES / 1024}KiB budget — lower MAX_DESTINATIONS or " +
                "shrink the listed row before raising it",
        )
    }

    /**
     * app_data is the reason the cap could move at all: it dwarfs the
     * rest of the row, and the list does not carry it.
     */
    @Test fun theFatColumnIsWhatTheProjectionSaves() = runTest {
        repeat(1) { insert(it, worst = true) }
        val listed = bytesPerRow(db.destinationDao().observeAll().first())
        val full = bytesPerRow(listOfNotNull(db.destinationDao().get("%032x".format(0))))
        println("full row=${full}B vs listed row=${listed}B")
        assertTrue(full > listed * 5, "app_data should dominate the full row (full=$full listed=$listed)")
    }
}
