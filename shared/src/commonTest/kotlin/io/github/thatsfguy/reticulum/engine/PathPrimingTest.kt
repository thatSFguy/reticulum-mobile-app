package io.github.thatsfguy.reticulum.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the [withPathPrimed] helper that consolidates the
 * `requestPath(destHash) → delay(settleMs) → block()` pattern that
 * was duplicated in three places (sendMessage, fetchNomadPage,
 * syncPropagation). Drift between the three sites would have been a
 * hard-to-spot bug — different settle windows on different code paths
 * would manifest as "sometimes works, sometimes doesn't."
 */
class PathPrimingTest {

    @Test fun `requestPath fires first, then settle window, then block`() = runTest {
        val events = mutableListOf<String>()
        val targetHash = ByteArray(16) { i -> (0x10 + i).toByte() }

        withPathPrimed(
            destHash = targetHash,
            requestPath = { hash ->
                assertContentEquals(targetHash, hash, "requestPath received unexpected destHash")
                events.add("requestPath@${currentTime}ms")
            },
            delayMs = { ms -> delay(ms) },
            settleMs = 1500L,
        ) {
            events.add("block@${currentTime}ms")
        }

        assertEquals(
            listOf("requestPath@0ms", "block@1500ms"),
            events,
            "expected requestPath at T=0 then block at T=1500ms",
        )
    }

    @Test fun `block still runs when requestPath throws`() = runTest {
        // The path request is a best-effort hint to the rnsd. If our
        // local rnsd is wedged or the wire is dropped at that exact
        // moment, the request might throw — but the actual send (the
        // block) should still proceed. Our retry loop and the rnsd's
        // own routing handle the missing-path case downstream.
        val events = mutableListOf<String>()
        val onFailureCalls = mutableListOf<Throwable>()

        withPathPrimed(
            destHash = ByteArray(16),
            requestPath = { throw RuntimeException("transport closed") },
            delayMs = { delay(it) },
            settleMs = 500L,
            onPathFailure = { onFailureCalls.add(it) },
        ) {
            events.add("block ran at ${currentTime}ms")
        }

        assertEquals(1, onFailureCalls.size, "onPathFailure should have been invoked once")
        assertEquals("transport closed", onFailureCalls.first().message)
        assertEquals(listOf("block ran at 500ms"), events,
            "block must run after settle window even though requestPath threw")
    }

    @Test fun `settle window is configurable`() = runTest {
        val events = mutableListOf<String>()
        withPathPrimed(
            destHash = ByteArray(16),
            requestPath = { },
            delayMs = { delay(it) },
            settleMs = 250L,
        ) {
            events.add("block@${currentTime}ms")
        }
        assertTrue(events == listOf("block@250ms"), "block ran at $events instead of T=250ms")
    }

    // Regression for the BLE inbound silent-drop bug surfaced 2026-05-03:
    // peers send `path?` requests for our destHash before sending us
    // LXMF DATA. Our app received those (51B DATA addressed to the
    // path-request service), but handleData filtered them out because
    // pkt.destHash didn't match ours, so we never re-announced. The
    // peer's path? timed out and the actual message never went out.
    // parsePathRequestTarget extracts the requested target so the
    // engine can compare it to our own destHash.

    @Test fun `parsePathRequestTarget returns first 16 bytes of valid payload`() {
        val target = ByteArray(16) { i -> (0x40 + i).toByte() }
        val tag    = ByteArray(16) { i -> (0xa0 + i).toByte() }
        val payload = target + tag
        assertContentEquals(target, parsePathRequestTarget(payload))
    }

    @Test fun `parsePathRequestTarget tolerates extra trailing bytes (transport-instance variant)`() {
        // Transport-enabled originators append their own identity hash
        // after the tag. We don't forward, so we ignore the extra bytes
        // — but we must still recover the target correctly.
        val target = ByteArray(16) { 0x55.toByte() }
        val tagPlus = ByteArray(32) { 0xaa.toByte() }
        assertContentEquals(target, parsePathRequestTarget(target + tagPlus))
    }

    @Test fun `parsePathRequestTarget returns null on short payload`() {
        for (size in 0 until 16) {
            assertNull(
                parsePathRequestTarget(ByteArray(size)),
                "payload of $size bytes must be rejected as too short",
            )
        }
    }

    @Test fun `parsePathRequestTarget accepts exactly-16 payload (target only, no tag)`() {
        // Defensive: even without the random tag, the target hash alone
        // is enough to act on. Don't require the tag.
        val target = ByteArray(16) { i -> (i + 1).toByte() }
        assertContentEquals(target, parsePathRequestTarget(target))
    }
}
