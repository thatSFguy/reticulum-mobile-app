package io.github.thatsfguy.reticulum.android.ui

import io.github.thatsfguy.reticulum.android.storage.IncomingUnread
import io.github.thatsfguy.reticulum.android.storage.UnreadTally
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-conversation unread counting for direct messages — the pure half
 * of ReticulumViewModel.unreadCounts.
 */
class UnreadTallyTest {

    private val alice = "aa".repeat(16)
    private val bob = "bb".repeat(16)

    /** Three incoming messages, ids 1..3, an hour apart. */
    private fun rows(vararg ids: Long, base: Long = 1_760_000_000_000L) =
        ids.map { IncomingUnread(id = it, timestamp = base + it * 3_600_000L) }

    @Test fun countsMessagesPastTheIdMarker() {
        val out = computeUnreadTallies(
            incoming = mapOf(alice to rows(1, 2, 3)),
            readIds = mapOf(alice to 1L),
            readTimes = emptyMap(),
            important = emptySet(),
        )
        assertEquals(2, out.getValue(alice).total)
    }

    @Test fun nothingUnreadIsOmitted() {
        val out = computeUnreadTallies(
            incoming = mapOf(alice to rows(1, 2)),
            readIds = mapOf(alice to 2L),
            readTimes = emptyMap(),
            important = emptySet(),
        )
        assertTrue(out.isEmpty())
    }

    /**
     * The upgrade case. A conversation that has not been opened since
     * the read marker changed from timestamps to row ids has no id
     * marker — falling through to "count everything" would flag every
     * already-read message as new, which is exactly the bug this
     * fallback exists to avoid.
     */
    @Test fun aConversationWithNoIdMarkerFallsBackToTheTimestampMarker() {
        val messages = rows(1, 2, 3)
        val out = computeUnreadTallies(
            incoming = mapOf(alice to messages),
            readIds = emptyMap(),
            // Read up to and including the second message.
            readTimes = mapOf(alice to messages[1].timestamp),
            important = emptySet(),
        )
        assertEquals(1, out.getValue(alice).total)
    }

    @Test fun theIdMarkerWinsOverTheLegacyTimestampOne() {
        val messages = rows(1, 2, 3)
        val out = computeUnreadTallies(
            incoming = mapOf(alice to messages),
            readIds = mapOf(alice to 3L),
            // A stale legacy value that would say "all three unread".
            readTimes = mapOf(alice to 0L),
            important = emptySet(),
        )
        assertTrue(out.isEmpty())
    }

    /** A peer whose clock runs fast used to leave a message that stayed
     *  unread no matter how often the conversation was read; row ids are
     *  ours and monotonic, so they don't care. */
    @Test fun aFutureDatedMessageIsStillMarkedReadById() {
        val future = listOf(IncomingUnread(id = 7L, timestamp = Long.MAX_VALUE / 2))
        val out = computeUnreadTallies(
            incoming = mapOf(alice to future),
            readIds = mapOf(alice to 7L),
            readTimes = emptyMap(),
            important = emptySet(),
        )
        assertTrue(out.isEmpty())
    }

    // ---- what earns red ----------------------------------------------

    @Test fun anOrdinaryConversationIsNeverAMention() {
        val out = computeUnreadTallies(
            incoming = mapOf(alice to rows(1)),
            readIds = mapOf(alice to 0L),
            readTimes = emptyMap(),
            important = emptySet(),
        )
        assertTrue(!out.getValue(alice).hasMention)
    }

    @Test fun aContactOrPinnedThreadEarnsRed() {
        val out = computeUnreadTallies(
            incoming = mapOf(alice to rows(1, 2), bob to rows(1)),
            readIds = mapOf(alice to 0L, bob to 0L),
            readTimes = emptyMap(),
            important = setOf(alice),
        )
        assertTrue(out.getValue(alice).hasMention)
        assertEquals(2, out.getValue(alice).mentions)
        assertTrue(!out.getValue(bob).hasMention)
    }

    @Test fun tallesSumForTheTabBadge() {
        val out = computeUnreadTallies(
            incoming = mapOf(alice to rows(1, 2), bob to rows(1)),
            readIds = mapOf(alice to 0L, bob to 0L),
            readTimes = emptyMap(),
            important = setOf(alice),
        )
        val summed = out.values.fold(UnreadTally()) { acc, u -> acc + u }
        assertEquals(3, summed.total)
        assertEquals(2, summed.mentions)
        assertTrue(summed.hasMention)
    }
}
