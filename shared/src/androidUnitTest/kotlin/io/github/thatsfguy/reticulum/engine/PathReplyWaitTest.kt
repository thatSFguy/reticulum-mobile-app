package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.announce.buildAnnounce
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_2
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.Packet
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_TRANSPORT
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Nomad fetch asks for a fresh path and then builds its LINKREQUEST.
 * The wait between the two must end when the reply arrives, including a
 * reply replay dedup then discards, and must not outlast its cap.
 */
class PathReplyWaitTest {
    private val staleHop = ByteArray(16) { 0x11 }
    private val freshHop = ByteArray(16) { 0x22 }

    private suspend fun newIdentity() = Identity(TestVectors.crypto).also { it.generate() }

    private inner class Fixture(scope: TestScope, val peer: Identity) {
        val destRepo = InMemoryDestRepo()
        val transport = RecordingTransport()
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = InMemoryIdentityRepo(),
            destinationRepo = destRepo,
            messageRepo = InMemoryMsgRepo(),
            scope = scope,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test" },
        )

        /** A signed announce from [identity], relayed via [nextHop]. */
        suspend fun announce(identity: Identity, hops: Int, nextHop: ByteArray): Pair<String, ByteArray> {
            val (destHash, payload, hasRatchet) = buildAnnounce(
                identity = identity, crypto = TestVectors.crypto,
                ratchetPub = identity.ratchetPubKey, nowSeconds = 1_700_000_000L,
            )
            return destHash.toHex() to buildPacket(
                headerType = HEADER_2, transportType = TRANSPORT_TRANSPORT,
                contextFlag = if (hasRatchet) 1 else 0,
                destType = DEST_SINGLE, packetType = PACKET_ANNOUNCE, hops = hops,
                destHash = destHash, transportId = nextHop, payload = payload,
            )
        }

        fun linkRequests(): List<Packet> = transport.sent.mapNotNull { parsePacket(it) }
            .filter { it.packetType == PACKET_LINKREQ }
    }

    private suspend fun TestScope.finish(f: Fixture) {
        f.transport.disconnect()
        f.engine.detach()
        coroutineContext.cancelChildren()
        testScheduler.advanceUntilIdle()
    }

    @Test fun `link request leaves as soon as a new route arrives and uses it`() = runTest {
        val f = Fixture(this, newIdentity())
        f.engine.attach(f.transport, ReticulumEngine.TransportKind.Tcp)
        val (hash, stale) = f.announce(f.peer, hops = 4, nextHop = staleHop)
        f.transport.inject(stale)
        testScheduler.runCurrent()

        async { f.engine.fetchNomadPage(hash, proofTimeoutMs = 60_000L, responseTimeoutMs = 60_000L) }
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()
        assertEquals(0, f.linkRequests().size, "must wait while no reply has arrived")

        val (_, fresh) = f.announce(f.peer, hops = 2, nextHop = freshHop)
        f.transport.inject(fresh)
        testScheduler.runCurrent()

        assertEquals(1, f.linkRequests().size, "must not sit out the rest of the wait")
        assertContentEquals(freshHop, f.linkRequests().single().transportId)
        finish(f)
    }

    @Test fun `a repeated announce still ends the wait`() = runTest {
        val f = Fixture(this, newIdentity())
        f.engine.attach(f.transport, ReticulumEngine.TransportKind.Tcp)
        val (hash, known) = f.announce(f.peer, hops = 2, nextHop = freshHop)
        f.transport.inject(known)
        testScheduler.runCurrent()

        async { f.engine.fetchNomadPage(hash, proofTimeoutMs = 60_000L, responseTimeoutMs = 60_000L) }
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()
        assertEquals(0, f.linkRequests().size)

        // Same random_hash, so replay dedup drops it as a repeat.
        f.transport.inject(known)
        testScheduler.runCurrent()

        assertEquals(1, f.linkRequests().size, "a deduped reply is still a reply")
        finish(f)
    }

    @Test fun `with no reply the link request leaves at the cap`() = runTest {
        val f = Fixture(this, newIdentity())
        f.engine.attach(f.transport, ReticulumEngine.TransportKind.Tcp)
        val (hash, known) = f.announce(f.peer, hops = 2, nextHop = staleHop)
        f.transport.inject(known)
        testScheduler.runCurrent()

        async { f.engine.fetchNomadPage(hash, proofTimeoutMs = 60_000L, responseTimeoutMs = 60_000L) }
        testScheduler.advanceTimeBy(PATH_REPLY_WAIT_TCP_MS - 100L)
        testScheduler.runCurrent()
        assertEquals(0, f.linkRequests().size, "left before the cap")

        testScheduler.advanceTimeBy(200L)
        testScheduler.runCurrent()
        assertEquals(1, f.linkRequests().size, "must go out at the cap regardless")
        finish(f)
    }

    @Test fun `another node's announce does not end the wait`() = runTest {
        val f = Fixture(this, newIdentity())
        f.engine.attach(f.transport, ReticulumEngine.TransportKind.Tcp)
        val (hash, known) = f.announce(f.peer, hops = 2, nextHop = staleHop)
        f.transport.inject(known)
        testScheduler.runCurrent()

        async { f.engine.fetchNomadPage(hash, proofTimeoutMs = 60_000L, responseTimeoutMs = 60_000L) }
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()

        val other = newIdentity()
        f.transport.inject(f.announce(other, hops = 2, nextHop = freshHop).second)
        testScheduler.runCurrent()

        assertTrue(f.linkRequests().isEmpty(), "an unrelated announce is not the reply")
        finish(f)
    }

    @Test fun `RF keeps the longer cap`() {
        assertEquals(PATH_SETTLE_RF_MS, pathReplyWaitMsFor(anyRfAttached = true))
        assertEquals(PATH_REPLY_WAIT_TCP_MS, pathReplyWaitMsFor(anyRfAttached = false))
    }
}

/** Channel-backed so injected packets are delivered under runTest. */
private class RecordingTransport : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = Channel<IncomingPacket>(64)
    override val incoming: Flow<IncomingPacket> = _incoming.receiveAsFlow()
    val sent = mutableListOf<ByteArray>()
    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        _incoming.close()
    }
    override suspend fun send(packet: ByteArray) { sent += packet }
    suspend fun inject(packet: ByteArray) { _incoming.send(IncomingPacket(packet, null)) }
}
