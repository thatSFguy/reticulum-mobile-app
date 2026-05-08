package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.protocol.PATH_STALE_MS
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Sticky-shortest-path semantics on inbound announces (post-affinity
 * fix follow-up, 2026-05-08): the wider mesh re-emits each peer's own
 * announce at higher hop counts. Without sticky-shortest, every
 * via-T re-emit overwrote a known direct-LoRa path with hopCount=2
 * and nextHop=T, flipping outbound DATA into the §2.3 HEADER_2-via-T
 * code path even when the direct path was still live.
 *
 * Tests exercise [ReticulumEngine.forTest_mergePathFromAnnounce]
 * directly. The handleAnnounce → mergePathFromAnnounce wiring is
 * covered by the on-device flow.
 */
class PathMergeTest {

    private val testHash = "0123456789abcdef0123456789abcdef"
    private val relayA = ByteArray(16) { 0xAA.toByte() }
    private val relayB = ByteArray(16) { 0xBB.toByte() }

    @Test fun `first announce always adopts`() = runTest {
        val (engine, _) = newEngine(nowHolder = TestNowHolder(0L))

        val merged = engine.forTest_mergePathFromAnnounce(
            destHashHex = testHash,
            existingHopCount = null,
            existingNextHop = null,
            newHopCount = 3,
            newNextHop = relayA,
        )
        assertEquals(3, merged.hopCount)
        assertContentEquals(relayA, merged.nextHop)

        cleanup(engine)
    }

    @Test fun `worse-hops announce within window does NOT overwrite`() = runTest {
        // The actual bug we're closing: A heard B directly at hops=1,
        // T then re-emits B's announce at hops=2 with T as transport_id.
        // The hops=2 record must be ignored.
        val nowHolder = TestNowHolder(1_000L)
        val (engine, _) = newEngine(nowHolder = nowHolder)
        // Seed an existing direct-LoRa path AND mark it as just-refreshed.
        engine.forTest_setBestPathSeenMs(testHash, 1_000L)

        val merged = engine.forTest_mergePathFromAnnounce(
            destHashHex = testHash,
            existingHopCount = 1,
            existingNextHop = null,
            newHopCount = 2,
            newNextHop = relayA,
        )
        assertEquals(1, merged.hopCount, "direct-path hop count must survive a worse via-relay re-emit")
        assertNull(merged.nextHop, "direct path's null nextHop must NOT be overwritten with the relay's id")

        cleanup(engine)
    }

    @Test fun `fewer-hops announce adopts immediately`() = runTest {
        // Mobility / new-route case: peer becomes reachable directly
        // when previously we only had a via-relay path.
        val nowHolder = TestNowHolder(1_000L)
        val (engine, _) = newEngine(nowHolder = nowHolder)
        engine.forTest_setBestPathSeenMs(testHash, 1_000L)

        val merged = engine.forTest_mergePathFromAnnounce(
            destHashHex = testHash,
            existingHopCount = 3,
            existingNextHop = relayA,
            newHopCount = 1,
            newNextHop = null,
        )
        assertEquals(1, merged.hopCount)
        assertNull(merged.nextHop, "shorter direct path must overwrite the previously cached relay")

        cleanup(engine)
    }

    @Test fun `equal-hop announce on same path refreshes`() = runTest {
        // Routine peer re-announce on the SAME path. Adopt to refresh
        // the staleness clock without changing the route.
        val nowHolder = TestNowHolder(1_000L)
        val (engine, _) = newEngine(nowHolder = nowHolder)
        engine.forTest_setBestPathSeenMs(testHash, 1_000L)

        val merged = engine.forTest_mergePathFromAnnounce(
            destHashHex = testHash,
            existingHopCount = 2,
            existingNextHop = relayA,
            newHopCount = 2,
            newNextHop = relayA,
        )
        assertEquals(2, merged.hopCount)
        assertContentEquals(relayA, merged.nextHop)

        cleanup(engine)
    }

    @Test fun `equal-hop announce with null nextHop preserves prior relay`() = runTest {
        // Edge case: a HEADER_1 re-announce arrives on the same hop
        // count — but transportId is null in HEADER_1. Don't blank out
        // the cached relay record; just refresh.
        val nowHolder = TestNowHolder(1_000L)
        val (engine, _) = newEngine(nowHolder = nowHolder)
        engine.forTest_setBestPathSeenMs(testHash, 1_000L)

        val merged = engine.forTest_mergePathFromAnnounce(
            destHashHex = testHash,
            existingHopCount = 2,
            existingNextHop = relayA,
            newHopCount = 2,
            newNextHop = null,  // HEADER_1 — no transportId
        )
        assertEquals(2, merged.hopCount)
        assertContentEquals(relayA, merged.nextHop, "null new-nextHop must NOT blank out the cached relay")

        cleanup(engine)
    }

    @Test fun `stale path allows worse-hops adoption`() = runTest {
        // After PATH_STALE_MS of silence on the good path, anything
        // gets adopted — the peer probably moved or the path went
        // down and we need the new route, even if it's worse.
        val nowHolder = TestNowHolder(0L)
        val (engine, _) = newEngine(nowHolder = nowHolder)
        engine.forTest_setBestPathSeenMs(testHash, 0L)

        nowHolder.value = PATH_STALE_MS + 1_000L
        val merged = engine.forTest_mergePathFromAnnounce(
            destHashHex = testHash,
            existingHopCount = 1,
            existingNextHop = null,
            newHopCount = 5,
            newNextHop = relayB,
        )
        assertEquals(5, merged.hopCount, "stale path must be replaceable regardless of new hop count")
        assertContentEquals(relayB, merged.nextHop)

        cleanup(engine)
    }

    @Test fun `stale window resets on each adoption`() = runTest {
        // Adopting refreshes bestPathSeenMs, so subsequent worse-hops
        // announces continue to be rejected for another full window.
        val nowHolder = TestNowHolder(0L)
        val (engine, _) = newEngine(nowHolder = nowHolder)

        engine.forTest_mergePathFromAnnounce(testHash, null, null, 1, null)

        nowHolder.value = PATH_STALE_MS - 1_000L  // just inside the window
        val merged1 = engine.forTest_mergePathFromAnnounce(testHash, 1, null, 4, relayA)
        assertEquals(1, merged1.hopCount, "still inside window — direct path wins")

        nowHolder.value = PATH_STALE_MS + 5_000L  // past the window
        val merged2 = engine.forTest_mergePathFromAnnounce(testHash, 1, null, 4, relayA)
        assertEquals(4, merged2.hopCount, "past window — worse path adoptable")

        cleanup(engine)
    }

    // ---- shared fixture ---------------------------------------------------

    private fun TestScope.newEngine(
        nowHolder: TestNowHolder,
    ): Pair<ReticulumEngine, TestNowHolder> {
        val identity = SeededIdentityRepo()
        val dest = InMemoryDestRepo()
        val msg = InMemoryMsgRepo()
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = identity,
            destinationRepo = dest,
            messageRepo = msg,
            scope = this,
            nowMs = { nowHolder.value },
            displayNameProvider = { "Test" },
        )
        return engine to nowHolder
    }

    private fun TestScope.cleanup(engine: ReticulumEngine) {
        engine.detach()
        coroutineContext.cancelChildren()
        testScheduler.advanceUntilIdle()
    }
}
