package io.github.thatsfguy.reticulum.rrc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Classifier for the hub's structured room-state NOTICEs
 * (client-parity.md §3 / §4). camelCase names keep the iosTest K/N
 * compile happy.
 */
class RrcNoticeTest {

    @Test fun topicNoticeParsed() {
        val n = RrcNotices.classify("topic for #general is now: be excellent to each other")
        assertTrue(n is RrcNotice.Topic)
        assertEquals("#general", n.room)
        assertEquals("be excellent to each other", n.topic)
    }

    @Test fun clearedTopicParsedAsNull() {
        val n = RrcNotices.classify("topic for #general is now: (cleared)")
        assertTrue(n is RrcNotice.Topic)
        assertEquals(null, n.topic)
    }

    @Test fun modeNoticeParsed() {
        val n = RrcNotices.classify("mode for #general is now: +int")
        assertTrue(n is RrcNotice.Mode)
        assertEquals("#general", n.room)
        assertEquals("+int", n.modes)
    }

    @Test fun emptyModeParsedAsBlank() {
        val n = RrcNotices.classify("mode for #general is now: (none)")
        assertTrue(n is RrcNotice.Mode)
        assertEquals("", n.modes)
    }

    @Test fun roomInfoNoticeParsed() {
        val n = RrcNotices.classify("room #general: registered; mode=+int; topic=hello world")
        assertTrue(n is RrcNotice.RoomInfo)
        assertEquals("#general", n.room)
        assertTrue(n.registered)
        assertEquals("+int", n.modes)
        assertEquals("hello world", n.topic)
    }

    @Test fun roomInfoNoticeWithNoTopicNoModes() {
        val n = RrcNotices.classify("room #lobby: unregistered; mode=(none); topic=(none)")
        assertTrue(n is RrcNotice.RoomInfo)
        assertEquals("#lobby", n.room)
        assertEquals(false, n.registered)
        assertEquals("", n.modes)
        assertEquals(null, n.topic)
    }

    @Test fun roomListNoticeParsed() {
        val n = RrcNotices.classify(
            "Registered public rooms:\n  lobby\n  dev - kernel hacking",
        )
        assertTrue(n is RrcNotice.RoomList)
        assertEquals(2, n.rooms.size)
        assertEquals("lobby", n.rooms[0].name)
        assertEquals(null, n.rooms[0].topic)
        assertEquals("dev", n.rooms[1].name)
        assertEquals("kernel hacking", n.rooms[1].topic)
    }

    @Test fun emptyRoomListNoticeParsed() {
        val n = RrcNotices.classify("No public rooms registered")
        assertTrue(n is RrcNotice.RoomList)
        assertTrue(n.rooms.isEmpty())
    }

    // ---- history replay brackets (§7) --------------------------------

    @Test fun historyBracketsParsed() {
        val start = RrcNotices.classify("--- 3 messages from earlier ---")
        assertTrue(start is RrcNotice.HistoryStart)
        assertEquals(3, start.count)
        assertTrue(RrcNotices.classify("--- 1 message from the last 2h ---") is RrcNotice.HistoryStart)
        assertEquals(RrcNotice.HistoryEnd, RrcNotices.classify("--- end of history ---"))
    }

    /** Anything that isn't the hub's exact shape stays a plain notice —
     *  mis-reading one would silence a room's real messages. */
    @Test fun nearMissHistoryBracketIsPlain() {
        assertEquals(RrcNotice.Plain, RrcNotices.classify("--- messages from earlier ---"))
        assertEquals(RrcNotice.Plain, RrcNotices.classify("3 messages from earlier"))
    }

    // ---- mentions (§8) -----------------------------------------------

    @Test fun mentionAlertNamesItsRoom() {
        val n = RrcNotices.classify("you were mentioned in #ops by bob: can you look")
        assertTrue(n is RrcNotice.Mentioned)
        assertEquals("ops", n.room)
    }

    @Test fun heldMentionsHeaderParsed() {
        val n = RrcNotices.classify("--- 2 mention(s) while you were away ---")
        assertTrue(n is RrcNotice.HeldMentions)
        assertEquals(2, n.count)
    }

    // ---- /who, the roster @-completion is built from -----------------

    @Test fun whoReplyParsesNicksAndHashes() {
        val n = RrcNotices.classify("members in lobby: alice (6b621001912f), 8f2c1d44ab90")
        assertTrue(n is RrcNotice.Who)
        assertEquals("lobby", n.room)
        assertEquals(2, n.members.size)
        assertEquals("alice", n.members[0].nick)
        assertEquals("6b621001912f", n.members[0].hashPrefix)
        // A member with no nick is still a member; @<hashprefix> names
        // them exactly, which is the form the spec calls certain.
        assertEquals(null, n.members[1].nick)
        assertEquals("8f2c1d44ab90", n.members[1].hashPrefix)
    }

    @Test fun whoReplyMarksAwayMembers() {
        val n = RrcNotices.classify("members in lobby: alice (6b621001912f) [away]")
        assertTrue(n is RrcNotice.Who)
        assertEquals("alice", n.members.single().nick)
        assertTrue(n.members.single().away)
    }

    @Test fun anEmptyRoomParsesAsAnEmptyRoster() {
        val n = RrcNotices.classify("members in lobby: (none)")
        assertTrue(n is RrcNotice.Who)
        assertTrue(n.members.isEmpty())
    }

    @Test fun anUnidentifiedMemberIsSkipped() {
        val n = RrcNotices.classify("members in lobby: (unidentified), bob (aa11bb22cc33)")
        assertTrue(n is RrcNotice.Who)
        assertEquals(1, n.members.size)
        assertEquals("bob", n.members.single().nick)
    }

    @Test fun plainNoticeFallsThrough() {
        assertEquals(RrcNotice.Plain, RrcNotices.classify("welcome to the hub"))
        assertEquals(RrcNotice.Plain, RrcNotices.classify("kline added for a1b2c3d4"))
        assertEquals(RrcNotice.Plain, RrcNotices.classify(""))
    }
}
