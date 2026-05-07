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

    @Test fun `affinity changes after re-pin`() = runTest {
        // Most-recent-announce wins semantic: a peer migrating from
        // LoRa to TCP-attached gateway should flip our outbound
        // routing on the next announce.
        val (engine, tcp, ble) = newEngineWithTwoTransports()
        engine.forTest_setDestAffinity(testDestHashHex, ReticulumEngine.TransportKind.Ble)
        engine.forTest_sendToDestination(testDestHashHex, testPacket)
        assertEquals(0, tcp.sentPackets.size, "step 1: BLE-only affinity must not hit TCP")
        assertEquals(1, ble.sentPackets.size)

        // Peer's announce now arrives via TCP — affinity flips
        engine.forTest_setDestAffinity(testDestHashHex, ReticulumEngine.TransportKind.Tcp)
        engine.forTest_sendToDestination(testDestHashHex, testPacket)
        assertEquals(1, tcp.sentPackets.size, "step 2: TCP-pinned affinity must route to TCP")
        assertEquals(1, ble.sentPackets.size, "step 2: BLE must NOT receive after re-pin")

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

    private fun TestScope.newEngineWithTwoTransports(): FixtureHandle {
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
