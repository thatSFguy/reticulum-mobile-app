package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The bounds on the telemetry a destination row keeps.
 *
 * With `appDataHex` out of the list query, `telemetryJson` is the last
 * column on a listed row whose size a *peer* decides — and that list
 * returns up to [MAX_DESTINATIONS] rows through Android's 2 MB
 * CursorWindow, with the margin measured in `DestinationRowSizeTest`.
 *
 * A pair count alone does not bound it: `stringifyValue` passes a
 * string value through unchanged and renders nested lists and maps
 * recursively, so a single pair can carry an arbitrarily wide value.
 * All three limits are pinned here for that reason.
 */
class StoredTelemetryBoundTest {

    private fun weight(t: Map<String, String>) =
        t.entries.sumOf { it.key.length + it.value.length + 2 }

    @Test fun ordinaryTelemetryIsKeptExactlyAsItArrived() {
        val t = mapOf("battery" to "87", "temp" to "21.5", "volt" to "4.01")
        assertSame(t, boundStoredTelemetry(t), "a normal payload must not be copied or altered")
    }

    /** Real telemetry off an RNode / transport node — the shape this
     *  exists to leave alone. */
    @Test fun aRealisticInterfaceReportFitsWithRoomToSpare() {
        val t = mapOf(
            "interfaceType" to "RNodeInterface",
            "bandwidthBps" to "125000",
            "mtu" to "500",
            "lat" to "44.1042",
            "lon" to "-85.2311",
        )
        assertSame(t, boundStoredTelemetry(t))
        assertTrue(weight(t) < MAX_STORED_TELEMETRY_TOTAL_CHARS)
    }

    @Test fun absentTelemetryStaysAbsent() {
        assertNull(boundStoredTelemetry(null))
    }

    /** The bound that a pair count alone would miss: one pair, one
     *  enormous value. */
    @Test fun aSingleEnormousValueIsTruncated() {
        val bounded = boundStoredTelemetry(mapOf("blob" to "x".repeat(50_000)))!!
        assertEquals(1, bounded.size)
        assertTrue(
            bounded["blob"]!!.length <= MAX_STORED_TELEMETRY_VALUE_CHARS,
            "a value must not exceed the per-value bound",
        )
        assertTrue(bounded["blob"]!!.endsWith("…"), "truncation should be visible")
    }

    @Test fun aFloodOfPairsIsCutToTheTotalBudget() {
        val bounded = boundStoredTelemetry((0 until 5_000).associate { "k$it" to "v$it" })!!
        assertTrue(bounded.size <= MAX_STORED_TELEMETRY_PAIRS)
        assertTrue(
            weight(bounded) <= MAX_STORED_TELEMETRY_TOTAL_CHARS,
            "the whole map must fit the total budget, not just each pair",
        )
    }

    /** Many big values: every bound applies at once. */
    @Test fun everyBoundAppliesTogether() {
        val bounded = boundStoredTelemetry((0 until 200).associate { "key$it" to "v".repeat(900) })!!
        assertTrue(bounded.size <= MAX_STORED_TELEMETRY_PAIRS)
        assertTrue(bounded.values.all { it.length <= MAX_STORED_TELEMETRY_VALUE_CHARS })
        assertTrue(weight(bounded) <= MAX_STORED_TELEMETRY_TOTAL_CHARS)
    }

    /** What survives is the front of the payload, so a sender putting
     *  the useful fields first still gets them displayed. */
    @Test fun theKeptPairsAreTheFirstOnes() {
        val bounded = boundStoredTelemetry((0 until 500).associate { "k$it" to "v$it" })!!
        assertEquals("v0", bounded["k0"])
        assertTrue(!bounded.containsKey("k499"), "the tail past the budget is dropped")
    }

    /** A key long enough to matter is not a telemetry key. */
    @Test fun anAbsurdKeyIsDroppedRatherThanStored() {
        val bounded = boundStoredTelemetry(
            mapOf("x".repeat(5_000) to "1", "battery" to "87"),
        )!!
        assertEquals(mapOf("battery" to "87"), bounded)
    }

    /** The bounds have to stay small enough to be worth having against
     *  thousands of rows in one 2 MB window. */
    @Test fun theBoundsAreTightEnoughToMatter() {
        assertTrue(MAX_STORED_TELEMETRY_TOTAL_CHARS <= 256)
        assertTrue(MAX_STORED_TELEMETRY_VALUE_CHARS <= MAX_STORED_TELEMETRY_TOTAL_CHARS)
    }
}
