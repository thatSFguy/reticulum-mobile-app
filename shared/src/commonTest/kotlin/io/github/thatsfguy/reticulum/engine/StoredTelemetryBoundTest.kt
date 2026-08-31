package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The bound on how much telemetry a destination row keeps.
 *
 * With `appDataHex` out of the list query, `telemetryJson` is the last
 * column on that row whose size a *peer* decides — and the list now
 * returns up to [MAX_DESTINATIONS] rows through Android's 2 MB
 * CursorWindow. One announce with a thousand telemetry pairs would
 * otherwise be enough to push the query back over the edge, which is
 * the crash the row cap exists to prevent.
 */
class StoredTelemetryBoundTest {

    private fun pairs(n: Int) = (0 until n).associate { "k$it" to "v$it" }

    @Test fun ordinaryTelemetryIsKeptExactlyAsItArrived() {
        val t = mapOf("battery" to "87", "temp" to "21.5", "volt" to "4.01")
        assertSame(t, boundStoredTelemetry(t), "a normal payload must not be copied or altered")
    }

    @Test fun aBsentTelemetryStaysAbsent() {
        assertNull(boundStoredTelemetry(null))
    }

    @Test fun anOverlongPayloadIsCutToTheCap() {
        val bounded = boundStoredTelemetry(pairs(5_000))
        assertEquals(MAX_STORED_TELEMETRY_PAIRS, bounded?.size)
    }

    /** Exactly at the cap is not "past" it. */
    @Test fun thePayloadAtTheCapIsUntouched() {
        val t = pairs(MAX_STORED_TELEMETRY_PAIRS)
        assertEquals(MAX_STORED_TELEMETRY_PAIRS, boundStoredTelemetry(t)?.size)
        assertSame(t, boundStoredTelemetry(t))
    }

    /** What survives is the front of the payload, so a sender putting
     *  the useful fields first still gets them displayed. */
    @Test fun theKeptPairsAreTheFirstOnes() {
        val bounded = boundStoredTelemetry(pairs(100))!!
        assertTrue(bounded.containsKey("k0"))
        assertEquals("v0", bounded["k0"])
        assertTrue(!bounded.containsKey("k99"), "the tail past the cap is dropped")
    }

    /** The cap is a row-size guard, so it has to be small enough to
     *  matter against thousands of rows in one 2 MB window. */
    @Test fun theCapIsSmallEnoughToBeWorthHaving() {
        assertTrue(
            MAX_STORED_TELEMETRY_PAIRS <= 64,
            "a cap this loose would not keep the list query inside the CursorWindow",
        )
    }
}
