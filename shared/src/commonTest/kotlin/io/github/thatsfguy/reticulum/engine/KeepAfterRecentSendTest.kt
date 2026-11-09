package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class KeepAfterRecentSendTest {
    private val sent = 1_700_000_000_000L

    @Test fun `a reply slightly behind our send moves after it`() {
        assertEquals(sent, keepAfterRecentSend(sent - 300L, sent))
    }

    @Test fun `the window edge still moves`() {
        assertEquals(sent, keepAfterRecentSend(sent - RECENT_SEND_ORDER_WINDOW_MS, sent))
    }

    @Test fun `an older message keeps its own time`() {
        val older = sent - RECENT_SEND_ORDER_WINDOW_MS - 1L
        assertEquals(older, keepAfterRecentSend(older, sent))
    }

    @Test fun `a message after our send is unchanged`() {
        assertEquals(sent + 5L, keepAfterRecentSend(sent + 5L, sent))
    }

    @Test fun `with nothing sent the time is unchanged`() {
        assertEquals(sent, keepAfterRecentSend(sent, null))
    }
}
