package io.github.thatsfguy.reticulum.android.ui

import io.github.thatsfguy.reticulum.android.ui.screens.RoomRowItem
import io.github.thatsfguy.reticulum.android.ui.screens.buildRoomRows
import io.github.thatsfguy.reticulum.store.StoredRrcMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The RRC room timeline model — day headings, the unread marker, and
 * which lines continue a run from the same sender.
 *
 * Pure Kotlin (no Compose), because the interesting parts are ordering
 * and key uniqueness rather than layout.
 */
class RoomTimelineTest {

    private val day1 = 1_760_000_000_000L          // some wall-clock day
    private val day2 = day1 + 26 * 3_600_000L      // the next one

    private fun msg(
        id: Long,
        text: String = "hi",
        ts: Long = day1,
        direction: String = "incoming",
        sender: String = "aa",
        nick: String? = "bob",
    ) = StoredRrcMessage(
        id = id, hubHash = "h", room = "r", direction = direction,
        senderIdHash = sender, nick = nick, text = text, timestamp = ts,
    )

    @Test fun everyRowKeyIsUnique() {
        // Timestamps in an RRC room come from every member's own clock,
        // so they can walk the calendar backwards — which used to be
        // able to emit the same day label twice and crash LazyColumn on
        // a duplicate key.
        val rows = buildRoomRows(
            listOf(
                msg(1, ts = day1),
                msg(2, ts = day2),
                msg(3, ts = day1),
                msg(4, ts = day2),
            ),
            unreadAfterId = 0L,
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate row key: $keys")
    }

    @Test fun aDayHeadingIsEmittedOnEachChangeOfDay() {
        val rows = buildRoomRows(
            listOf(msg(1, ts = day1), msg(2, ts = day1), msg(3, ts = day2)),
            unreadAfterId = null,
        )
        assertEquals(2, rows.count { it is RoomRowItem.DaySeparator })
    }

    /** A clockless LoRa sender puts seconds-since-boot in K_TS, which
     *  lands in 1970 — it must not open the room with a heading fifty
     *  years out of date. */
    @Test fun aClocklessTimestampGetsNoDayHeading() {
        val rows = buildRoomRows(listOf(msg(1, ts = 90_720L)), unreadAfterId = null)
        assertTrue(rows.none { it is RoomRowItem.DaySeparator })
        assertEquals(1, rows.size)
    }

    @Test fun theUnreadMarkerGoesBeforeTheFirstUnreadIncomingLine() {
        val rows = buildRoomRows(
            listOf(msg(1), msg(2), msg(3)),
            unreadAfterId = 1L,
        )
        val marker = rows.indexOfFirst { it is RoomRowItem.UnreadMarker }
        val second = rows.indexOfFirst { it is RoomRowItem.Line && it.msg.id == 2L }
        assertEquals(second - 1, marker)
        assertEquals(1, rows.count { it is RoomRowItem.UnreadMarker })
    }

    @Test fun nothingUnreadMeansNoMarker() {
        val rows = buildRoomRows(listOf(msg(1), msg(2)), unreadAfterId = 2L)
        assertTrue(rows.none { it is RoomRowItem.UnreadMarker })
    }

    /** Our own messages are not something to catch up on. */
    @Test fun ourOwnMessagesDoNotRaiseTheMarker() {
        val rows = buildRoomRows(
            listOf(msg(1), msg(2, direction = "outgoing")),
            unreadAfterId = 1L,
        )
        assertTrue(rows.none { it is RoomRowItem.UnreadMarker })
    }

    @Test fun consecutiveMessagesFromOneSenderAreGrouped() {
        val rows = buildRoomRows(
            listOf(msg(1), msg(2, ts = day1 + 1_000L), msg(3, sender = "bb", nick = "carol")),
            unreadAfterId = null,
        )
        val lines = rows.filterIsInstance<RoomRowItem.Line>()
        assertTrue(!lines[0].grouped, "first line always shows its header")
        assertTrue(lines[1].grouped, "same sender, moments later")
        assertTrue(!lines[2].grouped, "different sender")
    }

    @Test fun aLongGapBreaksTheGrouping() {
        val rows = buildRoomRows(
            listOf(msg(1), msg(2, ts = day1 + 10 * 60_000L)),
            unreadAfterId = null,
        )
        assertTrue(!rows.filterIsInstance<RoomRowItem.Line>()[1].grouped)
    }

    @Test fun systemLinesAreNeverGrouped() {
        val rows = buildRoomRows(
            listOf(
                msg(1, direction = "system", sender = "", nick = null),
                msg(2, direction = "system", sender = "", nick = null),
            ),
            unreadAfterId = null,
        )
        assertTrue(rows.filterIsInstance<RoomRowItem.Line>().none { it.grouped })
    }
}
