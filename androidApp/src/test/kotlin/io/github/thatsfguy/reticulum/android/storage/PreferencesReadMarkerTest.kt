package io.github.thatsfguy.reticulum.android.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * The per-contact direct-message read marker — the same shape as the
 * RRC rooms' `lastReadMessageId`, and the thing that decides whether
 * the Messages badge is telling the truth.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesReadMarkerTest {

    private val alice = "aa".repeat(16)
    private val bob = "bb".repeat(16)

    private fun prefs() = Preferences(ApplicationProvider.getApplicationContext())

    @Test fun markerRoundTripsPerContact() {
        val p = prefs()
        p.setLastReadMessageId(alice, 12L)
        p.setLastReadMessageId(bob, 7L)
        assertEquals(12L, p.lastReadMessageIds.value[alice])
        assertEquals(7L, p.lastReadMessageIds.value[bob])
    }

    /** A stale UI event must not be able to resurrect unread messages. */
    @Test fun markerNeverMovesBackwards() {
        val p = prefs()
        p.setLastReadMessageId(alice, 12L)
        p.setLastReadMessageId(alice, 3L)
        assertEquals(12L, p.lastReadMessageIds.value[alice])
    }

    @Test fun markerSurvivesAReload() {
        prefs().setLastReadMessageId(alice, 42L)
        // A fresh Preferences over the same SharedPreferences file is
        // what a cold start sees.
        assertEquals(42L, prefs().lastReadMessageIds.value[alice])
    }

    @Test fun anUnknownContactHasNoMarker() {
        assertEquals(null, prefs().lastReadMessageIds.value[alice])
    }
}
