package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pin the chat-ordering contract for [correctClocklessTimestamp]: pre-2020
 * clockless values and future-from-our-perspective values both fall back
 * to our local arrival time, while reasonable-past values pass through
 * untouched. The future-clamp branch was added 2026-05-11 after user
 * report of "occasional out-of-order bubbles on both iOS and Android"
 * — a peer with a clock running a few seconds ahead of the local device
 * would otherwise have its messages sort after the reply the user just
 * typed.
 */
class CorrectClocklessTimestampTest {

    private val nowMs = 1_762_000_000_000L  // 2025-10-31 ish, well past the 2020 cutoff

    @Test fun `pre-2020 clockless value falls back to arrival time`() {
        // RNode without RTC sends time.time() = seconds-since-boot. 90720s
        // = ~25h, well below the 2020 cutoff. Without substitution this
        // would sort at the very top of every conversation.
        val senderSeconds = 90_720.0
        assertEquals(nowMs, correctClocklessTimestamp(senderSeconds, nowMs))
    }

    @Test fun `tiny single-digit seconds-since-boot falls back to arrival time`() {
        assertEquals(nowMs, correctClocklessTimestamp(30.0, nowMs))
    }

    @Test fun `sender clock ahead of ours clamps down to arrival time`() {
        // Peer's wall clock is 5s ahead of ours. Without clamping, their
        // message would sort AFTER any reply the user types in the next
        // 5s (which uses our nowMs) — the reply appears above the message
        // it's replying to. Confirmed on production user report 2026-05-11.
        val senderSeconds = (nowMs + 5_000L) / 1000.0
        assertEquals(nowMs, correctClocklessTimestamp(senderSeconds, nowMs))
    }

    @Test fun `sender clock slightly behind ours passes through untouched`() {
        // Peer is 3s behind us. Their message timestamps appear in the
        // recent-past, ordered correctly relative to each other and to
        // our locally-stamped outgoing messages.
        val senderMs = nowMs - 3_000L
        val senderSeconds = senderMs / 1000.0
        assertEquals(senderMs, correctClocklessTimestamp(senderSeconds, nowMs))
    }

    @Test fun `reasonable past timestamp from a synced clock passes through`() {
        // Just after the 2020 cutoff — represents a real wall-clock send
        // from a peer with a working RTC. Must not get rewritten.
        val senderMs = 1_577_836_800_000L + 60_000L
        val senderSeconds = senderMs / 1000.0
        assertEquals(senderMs, correctClocklessTimestamp(senderSeconds, nowMs))
    }

    @Test fun `exact 2020 cutoff passes through`() {
        // Boundary check: 1_577_836_800_000 (2020-01-01 UTC) is the
        // first ms that is NOT treated as clockless.
        val cutoffMs = 1_577_836_800_000L
        val senderSeconds = cutoffMs / 1000.0
        assertEquals(cutoffMs, correctClocklessTimestamp(senderSeconds, nowMs))
    }

    @Test fun `exact nowMs passes through`() {
        val senderSeconds = nowMs / 1000.0
        assertEquals(nowMs, correctClocklessTimestamp(senderSeconds, nowMs))
    }

    @Test fun `a full second into the future clamps to nowMs`() {
        // The future-clamp branch fires whenever senderMs > nowMs. Use a
        // 1-second offset so the seconds-as-Double round-trip can't lose
        // the strict-greater relation to sub-ms rounding.
        val futureMs = nowMs + 1_000L
        assertEquals(nowMs, correctClocklessTimestamp(futureMs / 1000.0, nowMs))
    }
}
