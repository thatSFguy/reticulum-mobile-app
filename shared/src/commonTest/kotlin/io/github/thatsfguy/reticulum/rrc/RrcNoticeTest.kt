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

    @Test fun plainNoticeFallsThrough() {
        assertEquals(RrcNotice.Plain, RrcNotices.classify("welcome to the hub"))
        assertEquals(RrcNotice.Plain, RrcNotices.classify("kline added for a1b2c3d4"))
        assertEquals(RrcNotice.Plain, RrcNotices.classify(""))
    }
}
