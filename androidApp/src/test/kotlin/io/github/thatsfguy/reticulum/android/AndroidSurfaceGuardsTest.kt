package io.github.thatsfguy.reticulum.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit L5 + L7: the exported-launcher deep-link gate and the save-filename
 * extension neutralization. Both act on attacker-controlled input.
 */
class AndroidSurfaceGuardsTest {

    // ---- L5: open_contact must be a 16-byte (32 hex) destination hash ----

    @Test fun `contact hash gate accepts a 32-hex hash`() {
        assertTrue(CONTACT_HASH_RE.matches("a".repeat(32)))
        assertTrue(CONTACT_HASH_RE.matches("0123456789abcdefABCDEF0123456789"))
    }

    @Test fun `contact hash gate rejects wrong length and non-hex`() {
        assertFalse(CONTACT_HASH_RE.matches(""))
        assertFalse(CONTACT_HASH_RE.matches("a".repeat(31)), "too short")
        assertFalse(CONTACT_HASH_RE.matches("a".repeat(33)), "too long")
        assertFalse(CONTACT_HASH_RE.matches("g".repeat(32)), "non-hex")
        assertFalse(CONTACT_HASH_RE.matches("../../etc/passwd"))
    }

    // ---- L7: borrowed-trust save extensions get neutralized to .txt -------

    @Test fun `dangerous extensions are neutralized`() {
        assertEquals("identity.rmid.txt", neutralizeDangerousExtension("identity.rmid"))
        assertEquals("update.apk.txt", neutralizeDangerousExtension("update.apk"))
        assertEquals("note.html.txt", neutralizeDangerousExtension("note.html"))
        assertEquals("icon.svg.txt", neutralizeDangerousExtension("icon.svg"))
        assertEquals("run.sh.txt", neutralizeDangerousExtension("run.sh"))
        // Case-insensitive.
        assertEquals("X.APK.txt", neutralizeDangerousExtension("X.APK"))
    }

    @Test fun `safe extensions pass through unchanged`() {
        for (name in listOf("photo.jpg", "report.pdf", "clip.ogg", "data.bin", "noext", "a.tar.gz")) {
            assertEquals(name, neutralizeDangerousExtension(name), "$name must be unchanged")
        }
    }
}
