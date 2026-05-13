package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins for the image-send fallback constants introduced after the
 * v1.1.15 "image sending is slow" tester report. The actual end-to-end
 * behavior they drive — `tryDeliverOverLink` bailing after 2 attempts
 * when an image is attached, and `sendExistingMessage` writing the
 * marker before opportunistic fallback — needs a live LoRa link to
 * test end-to-end. These pins keep the contract stable so a future
 * "make link retries configurable" refactor can't silently bump
 * image-mode back to 5 attempts (and resurrect the ~4-minute wait)
 * or change the marker prefix that `MessageBubble`'s `imageDropped`
 * predicate keys on.
 */
class ImageFallbackConstantsTest {

    @Test fun `IMAGE_LINK_MAX_ATTEMPTS is 2 — fast-fail budget for image sends`() {
        // Each link-establishment attempt waits proofTimeoutForHops()
        // = 15-45s for an LRPROOF that may never arrive. Opportunistic
        // fallback can't carry images (single-packet MTU ~360 B vs a
        // ~10 KB JPEG), so we want the user to learn the image won't
        // go in ≈100s rather than the ~4 minutes the text-mode budget
        // of 5 attempts produces. If this drops to 1 the resilience
        // window for a single dropped LRPROOF disappears; if it grows
        // back to 5 the original UX bug returns.
        assertEquals(2, IMAGE_LINK_MAX_ATTEMPTS,
            "Image-mode retry budget regressed — see v1.1.16 commit and the v1.1.15 tester log " +
                "showing 4m38s wait before opportunistic fallback fired")
    }

    @Test fun `IMAGE_DROPPED_MARKER prefix is stable`() {
        // MessageBubble.kt + MessageBubble.swift both key on this exact
        // prefix to draw the "⚠ Image not delivered" indicator. The
        // string must end in a separator so the next test asserting
        // "starts with marker AND has a non-empty reason" doesn't break
        // on a marker that's flush against the reason.
        assertEquals("image dropped — ", IMAGE_DROPPED_MARKER,
            "Marker prefix changed — Android MessageBubble + iOS MessageBubble both check " +
                ".startsWith(\"image dropped — \") for the partial-delivery indicator")
        assertTrue(IMAGE_DROPPED_MARKER.endsWith(" "),
            "Marker must end with whitespace so reasons concat cleanly: marker + \"link establishment failed; only text was delivered\"")
    }

    @Test fun `marker + reason produces a stable lastError string`() {
        // Smoke test: the actual lastError the engine writes should
        // both pass the bubble's startsWith check AND contain a useful
        // reason after the marker for log forensics.
        val concrete = IMAGE_DROPPED_MARKER +
            "link establishment failed; only the text content was delivered"
        assertTrue(concrete.startsWith(IMAGE_DROPPED_MARKER))
        assertTrue(concrete.length > IMAGE_DROPPED_MARKER.length,
            "lastError must include a reason after the marker for diagnostic value")
    }
}
