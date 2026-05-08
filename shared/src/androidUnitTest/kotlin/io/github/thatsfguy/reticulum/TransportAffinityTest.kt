package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-destination transport affinity (post-Columba mesh-amplification
 * fix, 2026-05-07): outbound LXMF + LINKREQ traffic should be routed
 * to the transport that last delivered the destination's announce,
 * not broadcast on every attached transport.
 *
 * Exercises [ReticulumEngine.forTest_sendToDestination] directly to
 * isolate the routing decision from `sendMessage`'s retry-loop
 * machinery. The retry loop launches a coroutine on the engine's
 * scope that suspends across multiple delays; under
 * StandardTestDispatcher in kotlinx-coroutines-test 1.8.1 those
 * suspensions don't reliably drain even with explicit detach +
 * cancelChildren, so going through sendMessage produces flaky
 * UncompletedCoroutinesError noise. Routing is the load-bearing
 * decision; testing it through the seam is the same code path
 * sendMessage / fetchNomadPage / syncPropagation reach.
 *
 * The handleAnnounce → affinity-update path is covered by manual
 * on-device verification — building a fully-valid announce packet
 * for an arbitrary identity in a unit test isn't worth the harness
 * complexity for one assignment line.
 */
class TransportAffinityTest {

    private val testPacket: ByteArray = ByteArray(64) { 0xAB.toByte() }
    private val testDestHashHex = "0123456789abcdef0123456789abcdef"

    @Test fun `pinned affinity routes only to that transport`() = runTest {
        val (engine, tcp, ble) = newEngineWithTwoTransports()
        engine.forTest_setDestAffinity(testDestHashHex, ReticulumEngine.TransportKind.Ble)

        engine.forTest_sendToDestination(testDestHashHex, testPacket)

        assertEquals(0, tcp.sentPackets.size, "TCP must NOT receive when affinity pinned to BLE")
        assertEquals(1, ble.sentPackets.size, "BLE must receive exactly once")

        cleanup(engine, tcp, ble)
    }

    @Test fun `unpinned affinity falls back to broadcast`() = runTest {
        val (engine, tcp, ble) = newEngineWithTwoTransports()
        // No affinity pinned — first contact / unknown peer

        engine.forTest_sendToDestination(testDestHashHex, testPacket)

        assertEquals(1, tcp.sentPackets.size, "unpinned affinity must broadcast (TCP missed)")
        assertEquals(1, ble.sentPackets.size, "unpinned affinity must broadcast (BLE missed)")

        cleanup(engine, tcp, ble)
    }

    @Test fun `affinity to never-attached kind falls back to broadcast on remaining`() = runTest {
        // Stale affinity scenario: peer's announce was heard via TCP
        // in a previous session, then user toggled TCP off. The
        // affinity entry persists in memory even though TCP isn't
        // attached anymore. sendToDestination must fall back to
        // broadcast on the remaining transports rather than silently
        // drop the packet.
        //
        // Modeled here as "affinity points to a kind that was never
        // attached at all" — same code path as detach-mid-session
        // (`transports[pinned] == null` is the load-bearing check),
        // but avoids the mid-test detach machinery that produces
        // uncompleted-coroutine teardown noise.
        val identity = SeededIdentityRepo()
        val dest = InMemoryDestRepo()
        val msg = InMemoryMsgRepo()
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = identity,
            destinationRepo = dest,
            messageRepo = msg,
            scope = this,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test" },
        )
        val ble = AffinityFakeTransport()
        engine.attach(ble, ReticulumEngine.TransportKind.Ble)
        testScheduler.runCurrent()
        ble.sentPackets.clear()

        // Affinity points to a kind that's not attached (TCP was never
        // attached in this fixture).
        engine.forTest_setDestAffinity(testDestHashHex, ReticulumEngine.TransportKind.Tcp)
        engine.forTest_sendToDestination(testDestHashHex, testPacket)

        assertEquals(
            1, ble.sentPackets.size,
            "stale-affinity-kind must fall back to broadcast on remaining attached transports",
        )

        cleanup(engine, ble)
    }

    @Test fun `affinity changes after manual re-pin`() = runTest {
        // sendToDestination always honours whatever's currently in the
        // affinity map. The smarter UPDATE rules live in
        // updateAffinityFromAnnounce — see the cases below.
        val (engine, tcp, ble) = newEngineWithTwoTransports()
        engine.forTest_setDestAffinity(testDestHashHex, ReticulumEngine.TransportKind.Ble)
        engine.forTest_sendToDestination(testDestHashHex, testPacket)
        assertEquals(0, tcp.sentPackets.size, "step 1: BLE-only affinity must not hit TCP")
        assertEquals(1, ble.sentPackets.size)

        engine.forTest_setDestAffinity(testDestHashHex, ReticulumEngine.TransportKind.Tcp)
        engine.forTest_sendToDestination(testDestHashHex, testPacket)
        assertEquals(1, tcp.sentPackets.size, "step 2: TCP-pinned affinity must route to TCP")
        assertEquals(1, ble.sentPackets.size, "step 2: BLE must NOT receive after re-pin")

        cleanup(engine, tcp, ble)
    }

    // ---- updateAffinityFromAnnounce semantics ---------------------------

    @Test fun `re-pin with more hops does NOT override shorter path`() = runTest {
        // Production failure 2026-05-08: with BLE (LoRa direct, 1 hop)
        // AND TCP (wide rnsd mesh) attached, the TCP-side mesh re-emits
        // the peer's own LoRa announce back to us at 5+ hops. Naive
        // "most-recent-announce wins" flipped affinity to TCP every
        // time, sending LINKREQ via the long path — exceeded the
        // LRPROOF timeout, fallback to opportunistic.
        val (engine, tcp, ble) = newEngineWithTwoTransports()

        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Ble, hops = 1)
        assertEquals(ReticulumEngine.TransportKind.Ble, engine.forTest_getDestAffinity(testDestHashHex))

        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Tcp, hops = 5)
        assertEquals(
            ReticulumEngine.TransportKind.Ble, engine.forTest_getDestAffinity(testDestHashHex),
            "TCP announce with more hops must NOT override the shorter direct-LoRa path",
        )

        cleanup(engine, tcp, ble)
    }

    @Test fun `re-pin with fewer hops adopts the better path`() = runTest {
        // Mobility / new-route case: peer moves into TCP-gateway range
        // at 1 hop while we still cache an older 3-hop BLE entry.
        // Strictly fewer hops on the new kind wins.
        val (engine, tcp, ble) = newEngineWithTwoTransports()

        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Ble, hops = 3)
        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Tcp, hops = 1)
        assertEquals(
            ReticulumEngine.TransportKind.Tcp, engine.forTest_getDestAffinity(testDestHashHex),
            "fewer hops on a different kind must win",
        )

        cleanup(engine, tcp, ble)
    }

    @Test fun `equal-hop announce on different kind keeps existing pin`() = runTest {
        // Sticky on ties so transient hop-count fluctuation doesn't
        // flap. The peer is equally reachable on both transports —
        // staying put is fine.
        val (engine, tcp, ble) = newEngineWithTwoTransports()

        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Ble, hops = 2)
        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Tcp, hops = 2)
        assertEquals(
            ReticulumEngine.TransportKind.Ble, engine.forTest_getDestAffinity(testDestHashHex),
            "equal-hop announce on a different kind must NOT override existing pin",
        )

        cleanup(engine, tcp, ble)
    }

    @Test fun `same-kind announce refreshes hops and lastSeen`() = runTest {
        // Subsequent announces on the SAME kind should always update
        // the entry (so a peer's hops-shrunk-from-3-to-2 path is
        // reflected, and lastSeenMs stays current to keep the pin
        // out of the stale window).
        val (engine, tcp, ble) = newEngineWithTwoTransports()

        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Ble, hops = 3)
        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Ble, hops = 2)
        val entry = engine.forTest_getDestAffinityEntry(testDestHashHex)
        assertEquals(ReticulumEngine.TransportKind.Ble, entry?.kind)
        assertEquals(2, entry?.hops, "same-kind re-announce must refresh hops")

        cleanup(engine, tcp, ble)
    }

    @Test fun `stale pin is replaceable regardless of hop count`() = runTest {
        // After 10+ minutes of silence on the pinned kind we assume the
        // peer moved (or the path went down) and accept any new
        // announce, even with more hops. Without this the pin can
        // "survive" the peer leaving its original network and outbound
        // traffic black-holes.
        val nowHolder = TestNowHolder(0L)
        val (engine, tcp, ble) = newEngineWithTwoTransports(nowMsRef = nowHolder)

        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Ble, hops = 1)
        nowHolder.value = 11 * 60_000L
        engine.forTest_updateAffinityFromAnnounce(testDestHashHex, ReticulumEngine.TransportKind.Tcp, hops = 5)
        assertEquals(
            ReticulumEngine.TransportKind.Tcp, engine.forTest_getDestAffinity(testDestHashHex),
            "stale pin must be replaceable by any new announce regardless of hops",
        )

        cleanup(engine, tcp, ble)
    }

    // ---- shared fixture ---------------------------------------------------

    private data class FixtureHandle(
        val engine: ReticulumEngine,
        val tcp: AffinityFakeTransport,
        val ble: AffinityFakeTransport,
    )

    private operator fun FixtureHandle.component1() = engine
    private operator fun FixtureHandle.component2() = tcp
    private operator fun FixtureHandle.component3() = ble

    private fun TestScope.newEngineWithTwoTransports(
        nowMsRef: TestNowHolder = TestNowHolder(1_700_000_000_000L),
    ): FixtureHandle {
        val identity = SeededIdentityRepo()
        val dest = InMemoryDestRepo()
        val msg = InMemoryMsgRepo()
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = identity,
            destinationRepo = dest,
            messageRepo = msg,
            scope = this,
            nowMs = { nowMsRef.value },
            displayNameProvider = { "Test" },
        )
        val tcp = AffinityFakeTransport()
        val ble = AffinityFakeTransport()
        engine.attach(tcp, ReticulumEngine.TransportKind.Tcp)
        engine.attach(ble, ReticulumEngine.TransportKind.Ble)
        // Drain the "I just attached" announces (broadcast on attach
        // because lastAnnounceMs reset) so they don't pollute the
        // sentPackets count for the routing-decision assertions.
        testScheduler.runCurrent()
        tcp.sentPackets.clear()
        ble.sentPackets.clear()
        return FixtureHandle(engine, tcp, ble)
    }

    private fun TestScope.cleanup(
        engine: ReticulumEngine,
        vararg transports: AffinityFakeTransport,
    ) {
        // Channels closed first so per-kind pump's collect can exit
        // naturally — pumpJob.cancel() alone leaves it suspended on
        // Channel.receive under StandardTestDispatcher.
        transports.forEach {
            kotlinx.coroutines.runBlocking { it.disconnect() }
        }
        engine.detach()
        coroutineContext.cancelChildren()
        testScheduler.advanceUntilIdle()
    }
}

/** Mutable now-source for tests that exercise time-dependent logic
 *  (the affinity stale window). Plain holder — no thread-safety
 *  concerns under runTest's StandardTestDispatcher. */
internal class TestNowHolder(var value: Long)

/** Identity repo pre-seeded so [ReticulumEngine.ensureIdentity] never
 *  has to generate fresh keys (slow under SecureRandom). */
internal class SeededIdentityRepo : IdentityRepository {
    private var stored: StoredIdentity? = StoredIdentity(
        encPrivKey = TestVectors.Alice.encPriv,
        sigPrivKey = TestVectors.Alice.sigPriv,
        ratchetPrivKey = TestVectors.Alice.ratchetPriv,
    )
    override suspend fun save(identity: StoredIdentity) { stored = identity }
    override suspend fun load(): StoredIdentity? = stored
}

/** Channel-backed Transport that records every send for assertions.
 *  Mirrors EngineSendBugTest's FakeTransport — see there for the
 *  Channel-vs-SharedFlow rationale. */
internal class AffinityFakeTransport : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = kotlinx.coroutines.channels.Channel<IncomingPacket>(64)
    override val incoming: Flow<IncomingPacket> = _incoming.receiveAsFlow()

    val sentPackets = mutableListOf<ByteArray>()

    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        _incoming.close()
    }
    override suspend fun send(packet: ByteArray) { sentPackets.add(packet) }
}
