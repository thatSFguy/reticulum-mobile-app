package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.computeLinkId
import io.github.thatsfguy.reticulum.protocol.CTX_LRPROOF
import io.github.thatsfguy.reticulum.protocol.CTX_LRRTT
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Inbound links need no authentication, so what a stranger's requests
 *  can replace or allocate has to be limited here. */
class ResponderSessionLimitsTest {

    private class Rig(val scope: TestScope) {
        var now = 1_700_000_000_000L
        val transport = LinkRequestTransport()
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = InMemoryIdentityRepo(),
            destinationRepo = InMemoryDestRepo(),
            messageRepo = InMemoryMsgRepo(),
            scope = scope.backgroundScope,
            nowMs = { now },
            displayNameProvider = { "Test" },
        )
        private var ourDest = ByteArray(0)

        suspend fun start() {
            ourDest = computeDestinationHash(
                TestVectors.crypto, "lxmf.delivery", engine.ensureIdentity().hash!!,
            )
            engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
            scope.testScheduler.runCurrent()
        }

        suspend fun linkRequest(): ByteArray {
            val (_, data) = Link.createInitiator(
                peerLongTermSigPub = ByteArray(32) { 1 },
                peerDestHash = ourDest,
                crypto = TestVectors.crypto,
                nowMs = now,
            )
            return buildPacket(
                headerType = HEADER_1, destType = DEST_SINGLE, packetType = PACKET_LINKREQ,
                destHash = ourDest, payload = data,
            )
        }

        suspend fun receive(packet: ByteArray) {
            transport.inject(packet)
            scope.testScheduler.runCurrent()
        }

        /** Send a fresh request and return its link id. */
        suspend fun open(): String {
            val request = linkRequest()
            receive(request)
            return computeLinkId(parsePacket(request)!!, TestVectors.crypto).toHex()
        }

        suspend fun completeHandshake(linkId: ByteArray) = receive(buildPacket(
            headerType = HEADER_1, destType = DEST_LINK, packetType = PACKET_DATA,
            destHash = linkId, context = CTX_LRRTT, payload = ByteArray(32),
        ))

        fun proofsSent(): Int = transport.sent.mapNotNull { parsePacket(it) }
            .count { it.packetType == PACKET_PROOF && it.context == CTX_LRPROOF }
    }

    private fun hexToId(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    @Test fun `replayed request is ignored and sends no second proof`() = runTest {
        val rig = Rig(this).also { it.start() }
        val request = rig.linkRequest()

        rig.receive(request)
        rig.receive(request)

        assertEquals(1, rig.proofsSent())
        assertEquals(1, rig.engine.responderSessionIds().size)
    }

    @Test fun `a burst beyond the limit gets no proof until the bucket refills`() = runTest {
        val rig = Rig(this).also { it.start() }

        repeat(LINK_REQUEST_BURST + 5) { rig.open() }
        assertEquals(LINK_REQUEST_BURST, rig.proofsSent())

        rig.now += LINK_REQUEST_REFILL_MS
        rig.open()
        assertEquals(LINK_REQUEST_BURST + 1, rig.proofsSent())
    }

    @Test fun `silent session is dropped once stale`() = runTest {
        val rig = Rig(this).also { it.start() }
        val old = rig.open()

        rig.now += RESPONDER_STALE_MS + 1
        val fresh = rig.open()

        assertEquals(setOf(fresh), rig.engine.responderSessionIds())
    }

    @Test fun `session within the stale window is kept`() = runTest {
        val rig = Rig(this).also { it.start() }
        val first = rig.open()

        rig.now += RESPONDER_STALE_MS
        rig.open()

        assertTrue(first in rig.engine.responderSessionIds())
    }

    @Test fun `sessions never exceed the cap and the oldest handshake goes first`() = runTest {
        val rig = Rig(this).also { it.start() }
        val first = rig.open()
        repeat(MAX_RESPONDER_SESSIONS - 1) { rig.now += LINK_REQUEST_REFILL_MS; rig.open() }
        assertEquals(MAX_RESPONDER_SESSIONS, rig.engine.responderSessionIds().size)

        rig.now += LINK_REQUEST_REFILL_MS
        val newest = rig.open()

        val ids = rig.engine.responderSessionIds()
        assertEquals(MAX_RESPONDER_SESSIONS, ids.size)
        assertFalse(first in ids)
        assertTrue(newest in ids)
    }

    @Test fun `a flood evicts handshakes before an established link`() = runTest {
        val rig = Rig(this).also { it.start() }
        val established = rig.open()
        rig.completeHandshake(hexToId(established))

        repeat(MAX_RESPONDER_SESSIONS * 2) { rig.now += LINK_REQUEST_REFILL_MS; rig.open() }

        assertTrue(established in rig.engine.responderSessionIds())
    }
}

/** Channel-backed so injected packets are delivered under runTest. */
private class LinkRequestTransport : Transport {
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
