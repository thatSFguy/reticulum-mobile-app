package io.github.thatsfguy.reticulum.android.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #61: the foreground-service notification is ongoing, so a badge on
 * its channel keeps the launcher icon badged forever. Channels only exist on
 * a device, so this pins the fix at source level: the service channel is
 * created with setShowBadge(false) under a new id, and the old (badged) id
 * is only ever referenced to delete it.
 */
class ServiceChannelBadgeTest {

    private val src = File(
        "src/main/kotlin/io/github/thatsfguy/reticulum/android/service/ReticulumService.kt",
    ).readText()

    @Test fun `service channel is created without a badge`() {
        val create = Regex("""createNotificationChannel\(NotificationChannel\(\s*CHANNEL_SERVICE,.*?\}\)""",
            RegexOption.DOT_MATCHES_ALL).find(src)
        checkNotNull(create) { "service channel creation block not found" }
        assertTrue("setShowBadge(false)" in create.value, "service channel must disable badges")
    }

    @Test fun `service channel uses a new id and deletes the old one`() {
        assertTrue("""CHANNEL_SERVICE  = "reticulum_service_quiet"""" in src)
        assertTrue("""LEGACY_CHANNEL_SERVICE = "reticulum_service"""" in src)
        val uses = Regex("""\bLEGACY_CHANNEL_SERVICE\b""").findAll(src).map { m ->
            src.substring(maxOf(0, m.range.first - 40), m.range.last + 1)
        }.toList()
        // One declaration + one delete; nothing may post to the old channel.
        assertEquals(2, uses.size, "LEGACY_CHANNEL_SERVICE referenced unexpectedly: $uses")
        assertTrue(uses.any { "deleteNotificationChannel(" in it })
    }
}
