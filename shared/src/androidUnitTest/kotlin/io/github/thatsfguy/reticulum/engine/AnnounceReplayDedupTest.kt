package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.announce.buildAnnounce
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit 2026-07-28 M2/M3: announce replay dedup MUST key on the announce's
 * `random_hash` (the SIGNED body), not the full packet hash. random_hash is
 * upstream's replay key (SPEC §4.5 step 6.3, RNS/Transport.py:1710,1735,1748).
 * Keying on the packet let an attacker flip the UNSIGNED header (hops,
 * transport_id) to replay the same signed announce past dedup — re-adopting a
 * rolled-back ratchet_pub (M2, forward-secrecy downgrade) and re-pointing our
 * path's next hop at the attacker (M3). We deliberately do NOT gate on the
 * timestamp half of random_hash (SPEC §9.6 clockless senders, §9.10
 * microReticulum incl. our own repeater/Faketec emit non-monotonic /
 * fully-random timestamps), so a genuinely fresh re-announce is still
 * processed.
 */
class AnnounceReplayDedupTest {

    private fun TestScope.newEngine(destRepo: InMemoryDestRepo) = ReticulumEngine(
        crypto = TestVectors.crypto,
        identityRepo = InMemoryIdentityRepo(),
        destinationRepo = destRepo,
        messageRepo = InMemoryMsgRepo(),
        scope = this,
        nowMs = { 1_700_000_000_000L },
        displayNameProvider = { "Test" },
    )

    @Test fun `header-modified replay is deduped but a fresh re-announce is processed`() = runTest {
        val crypto = TestVectors.crypto
        val destRepo = InMemoryDestRepo()
        val engine = newEngine(destRepo)
        val transport = ReplayInjectTransport()
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)

        val sender = Identity(crypto).also { it.generate() }
        val (destHash, payload, hasRatchet) = buildAnnounce(
            identity = sender, crypto = crypto,
            ratchetPub = sender.ratchetPubKey, nowSeconds = 1_700_000_000L,
        )
        val destHex = destHash.toHex()
        val ctxFlag = if (hasRatchet) 1 else 0

        fun announceAt(hops: Int, body: ByteArray = payload, ratchet: Boolean = hasRatchet) =
            buildPacket(
                headerType = HEADER_1, contextFlag = if (ratchet) 1 else 0,
                destType = DEST_SINGLE, packetType = PACKET_ANNOUNCE,
                hops = hops, destHash = destHash, payload = body,
            )

        // 1) Genuine announce at 3 hops → stored hopCount = hops + 1 = 4.
        transport.inject(IncomingPacket(announceAt(hops = 3), null))
        testScheduler.runCurrent()
        assertEquals(4, destRepo.get(destHex)?.hopCount, "first announce stores hopCount = hops+1")

        // 2) SAME signed body, header hop byte forged to 0 (would lower the
        //    path if processed). Different packet hash, identical random_hash
        //    → must be DEDUPED.
        transport.inject(IncomingPacket(announceAt(hops = 0), null))
        testScheduler.runCurrent()
        assertEquals(
            4, destRepo.get(destHex)?.hopCount,
            "header-modified replay must be deduped — hopCount must NOT drop to 1 (M3)",
        )

        // 3) A genuinely fresh re-announce (new random_hash — buildAnnounce
        //    draws fresh random bytes each call) at 1 hop is NOT deduped:
        //    proves we key on the full random_hash, not the timestamp, so
        //    legit/clockless re-announces still flow.
        val (_, freshPayload, freshRatchet) = buildAnnounce(
            identity = sender, crypto = crypto,
            ratchetPub = sender.ratchetPubKey, nowSeconds = 1_700_000_000L,
        )
        transport.inject(IncomingPacket(announceAt(hops = 1, body = freshPayload, ratchet = freshRatchet), null))
        testScheduler.runCurrent()
        assertEquals(
            2, destRepo.get(destHex)?.hopCount,
            "a fresh re-announce (new random_hash) must be processed — hopCount updates to 2",
        )

        transport.disconnect()
        engine.detach()
        coroutineContext.cancelChildren()
        testScheduler.advanceUntilIdle()
    }
}

/** Channel-backed injectable transport — see the twins in
 *  OpportunisticArrivedViaDestTest / TelemetryOnlyLxmfTest for why
 *  Channel + receiveAsFlow is required under runTest. */
private class ReplayInjectTransport : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = Channel<IncomingPacket>(64)
    override val incoming: Flow<IncomingPacket> = _incoming.receiveAsFlow()
    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        _incoming.close()
    }
    override suspend fun send(packet: ByteArray) = Unit
    suspend fun inject(packet: IncomingPacket) { _incoming.send(packet) }
}
