package io.github.thatsfguy.reticulum.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TCP supervisor's reconnect ramp.
 *
 * The case that drove this into its own file: `RNS.MichMesh.net` had
 * denylisted a user's address, which presents as a completed TCP
 * handshake followed immediately by a close. Classifying that as a read
 * failure pinned the client at the read ramp's 60s ceiling — ~1,440
 * attempts a day at a door that was shut.
 */
class TcpRetryPolicyTest {

    private fun decide(
        survivedMs: Long?,
        read: Long = TcpRetryPolicy.READ_FAIL_FLOOR_MS,
        connect: Long = TcpRetryPolicy.CONNECT_FAIL_FLOOR_MS,
    ) = TcpRetryPolicy.decide(survivedMs, read, connect)

    @Test
    fun `a socket that never connected is a connect failure`() {
        val d = decide(survivedMs = null)
        assertFalse(d.wasReadFailure)
        assertEquals(TcpRetryPolicy.CONNECT_FAIL_FLOOR_MS, d.delayBaseMs)
    }

    /** The denylist signature: accepted, then closed in well under a second. */
    @Test
    fun `accepted then closed immediately is a refusal, not a read failure`() {
        val d = decide(survivedMs = 40L)
        assertFalse(d.wasReadFailure)
        assertEquals(TcpRetryPolicy.CONNECT_FAIL_FLOOR_MS, d.delayBaseMs)
        // and it must not touch the read ramp at all
        assertEquals(TcpRetryPolicy.READ_FAIL_FLOOR_MS, d.nextReadFailBackoffMs)
    }

    /**
     * The regression that made the naive fix worse than the bug: the
     * connect ramp used to reset the moment a socket was established, so
     * a refusing server reset it on every attempt and pinned it at the
     * floor. A refusal must leave the ramp climbing.
     */
    @Test
    fun `a refusal never resets the connect ramp`() {
        var connect = TcpRetryPolicy.CONNECT_FAIL_FLOOR_MS
        val waits = mutableListOf<Long>()
        repeat(8) {
            val d = decide(survivedMs = 40L, connect = connect)
            waits += d.delayBaseMs
            connect = d.nextConnectFailBackoffMs
        }
        assertEquals(
            listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L),
            waits,
        )
    }

    @Test
    fun `the connect ramp is capped at five minutes`() {
        val d = decide(survivedMs = null, connect = TcpRetryPolicy.CONNECT_FAIL_CEILING_MS)
        assertEquals(TcpRetryPolicy.CONNECT_FAIL_CEILING_MS, d.nextConnectFailBackoffMs)
    }

    @Test
    fun `a real session that dies takes the fast read ramp`() {
        val d = decide(survivedMs = 30_000L)
        assertTrue(d.wasReadFailure)
        assertEquals(TcpRetryPolicy.READ_FAIL_FLOOR_MS, d.delayBaseMs)
        assertEquals(10_000L, d.nextReadFailBackoffMs)
    }

    @Test
    fun `the read ramp is capped at one minute`() {
        val d = decide(survivedMs = 30_000L, read = TcpRetryPolicy.READ_FAIL_CEILING_MS)
        assertEquals(TcpRetryPolicy.READ_FAIL_CEILING_MS, d.nextReadFailBackoffMs)
    }

    /** A long-lived attachment dying is a NAT idle timeout — come back fast. */
    @Test
    fun `a healthy long-lived session restarts the read ramp at the floor`() {
        val d = decide(survivedMs = 90_000L, read = TcpRetryPolicy.READ_FAIL_CEILING_MS)
        assertTrue(d.wasReadFailure)
        assertEquals(TcpRetryPolicy.READ_FAIL_FLOOR_MS, d.delayBaseMs)
    }

    /** Having been usable proves the connect path works; earn the ramp back. */
    @Test
    fun `a read failure resets the connect ramp`() {
        val d = decide(survivedMs = 30_000L, connect = TcpRetryPolicy.CONNECT_FAIL_CEILING_MS)
        assertEquals(TcpRetryPolicy.CONNECT_FAIL_FLOOR_MS, d.nextConnectFailBackoffMs)
    }

    /** Either side of the line, and nothing surprising in between. */
    @Test
    fun `the usable threshold is the boundary`() {
        assertFalse(decide(survivedMs = TcpRetryPolicy.USABLE_AFTER_MS - 1).wasReadFailure)
        assertTrue(decide(survivedMs = TcpRetryPolicy.USABLE_AFTER_MS).wasReadFailure)
    }

    /**
     * The point of the whole change, stated as a budget: a full day
     * against a node that refuses us must cost hundreds of attempts, not
     * thousands.
     */
    @Test
    fun `a day of refusals stays under 300 attempts`() {
        var connect = TcpRetryPolicy.CONNECT_FAIL_FLOOR_MS
        var elapsed = 0L
        var attempts = 0
        val day = 24 * 60 * 60 * 1000L
        while (elapsed < day) {
            val d = decide(survivedMs = 40L, connect = connect)
            elapsed += d.delayBaseMs
            connect = d.nextConnectFailBackoffMs
            attempts++
        }
        assertTrue("$attempts attempts/day", attempts < 300)
        // and for contrast, the old behaviour's ceiling
        assertTrue(day / TcpRetryPolicy.READ_FAIL_CEILING_MS > 1_400)
    }
}
