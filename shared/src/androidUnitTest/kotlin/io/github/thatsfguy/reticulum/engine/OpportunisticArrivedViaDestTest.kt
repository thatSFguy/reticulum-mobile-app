package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.store.StoredDestination
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
import kotlin.test.assertNotNull

/**
 * Pins the v1.1.39 routing fix: opportunistic-received LXMFs MUST tag
 * the saved row with `arrivedViaDest = source_hash`. fwdsvc fans out
 * short bubbles (~100-150 B "[Nick] body") via opportunistic delivery
 * — Delivery.Send tries opportunistic first and only switches to a
 * Link on ErrPayloadTooLarge — so this is the LIVE path for relayed
 * messages and the one v1.1.38's LINKIDENTIFY-only fix missed.
 *
 * Two pins:
 *  1. **Relay-shaped (fwdsvc rebroadcast)**: source_hash is the
 *     relay's lxmf.delivery destination (fwdsvc re-signs as itself
 *     when fanning out). Reaction sent from the resulting row MUST
 *     route through the relay → arrivedViaDest = source_hash.
 *  2. **Direct 1:1 chat (regression guard)**: source_hash IS the
 *     conversation peer. arrivedViaDest still gets populated (= peer)
 *     so the rule is uniform, but at send time
 *     `effectiveDest = arrivedViaDest ?: contactHash` resolves to the
 *     same destination either way → routing is byte-identical to
 *     pre-v1.1.38 direct chats.
 *
 * Audit reference: 2026-05-14 fwdsvc maintainer's clarification
 * (internal/lxmf/delivery.go opportunistic-first + rebroadcast wire
 * shape with re-signing).
 */
class OpportunisticArrivedViaDestTest {

    @Test fun `opportunistic relay-shaped LXMF sets arrivedViaDest to source_hash`() = runTest {
        val crypto = TestVectors.crypto
        val rig = newRig()
        // Bind the engine's identity by triggering its lazy-load path.
        // ensureIdentity() generates a fresh keypair on first call and
        // persists it through InMemoryIdentityRepo; subsequent calls
        // return the same Identity. We need its hash + ratchetPubKey
        // to encrypt a packet TARGETED at our destination.
        val us = rig.engine.ensureIdentity()
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", us.hash!!)

        // Relay (fwdsvc-shaped). In fwdsvc's rebroadcast model the LXMF
        // body's source_hash is the relay's lxmf.delivery destination,
        // not the original human sender — fwdsvc re-signs under its
        // own identity before fanning out.
        val relay = Identity(crypto).also { it.generate() }
        val relayDest = computeDestinationHash(crypto, "lxmf.delivery", relay.hash!!)

        // Register the relay's announce so signature verification
        // succeeds and the row lands "verified". Without this the
        // row would still save (state="unverified") and the
        // arrivedViaDest assertion would still pass — but verifying
        // the verified path exercises the full inbound code lane.
        rig.repos.dest.upsertFromAnnounce(storedFor(relay, relayDest, displayName = "fwdsvc-test"))
        rig.engine.attach(rig.transport, ReticulumEngine.TransportKind.Tcp)

        val opportunisticPacket = buildOpportunisticLxmf(
            crypto = crypto,
            sourceIdentity = relay,
            sourceHash = relayDest,
            recipient = us,
            recipientDest = ourDest,
            content = "[BlueP] test 1",
        )
        rig.transport.inject(IncomingPacket(opportunisticPacket, null))
        // runCurrent (NOT advanceUntilIdle) — same reason
        // EngineSendBugTest.drainQueuedOutgoingTest cites: the engine's
        // reannounce loop has an infinite `delay(...)` that
        // advanceUntilIdle would expand into an infinite virtual-time
        // loop, blowing past runTest's 60 s budget. runCurrent drives
        // the pump far enough to consume the channel value, call
        // handleIncoming → handleData → messageRepo.save, without
        // touching the periodic delay coroutines.
        testScheduler.runCurrent()

        val saved = rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" }
        assertNotNull(saved, "engine should have persisted the inbound opportunistic LXMF")
        assertEquals(
            relayDest.toHex(),
            saved.arrivedViaDest,
            "arrivedViaDest MUST equal source_hash on the opportunistic path so " +
                "sendReaction routes through the relay (fwdsvc fanout). Pre-v1.1.39 this " +
                "was null on opportunistic, leaving reactions to egress direct to the " +
                "original sender and bypass the relay entirely.",
        )
        assertEquals(relayDest.toHex(), saved.contactHash)

        drain(rig)
    }

    @Test fun `opportunistic 1-to-1 direct LXMF sets arrivedViaDest equal to peer (no regression)`() = runTest {
        // Regression guard for the simplification. In a direct chat
        // source_hash IS the conversation peer; arrivedViaDest gets
        // populated to the same value contactHash already had, so the
        // send-time override (`effectiveDest = arrivedViaDest ?: …`)
        // resolves to the peer either way. Routing is byte-identical
        // to pre-v1.1.38 direct chats — verified here by asserting
        // arrivedViaDest == contactHash.
        val crypto = TestVectors.crypto
        val rig = newRig()
        val us = rig.engine.ensureIdentity()
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", us.hash!!)

        val peer = Identity(crypto).also { it.generate() }
        val peerDest = computeDestinationHash(crypto, "lxmf.delivery", peer.hash!!)

        rig.repos.dest.upsertFromAnnounce(storedFor(peer, peerDest, displayName = "DirectPeer"))
        rig.engine.attach(rig.transport, ReticulumEngine.TransportKind.Tcp)

        val opportunisticPacket = buildOpportunisticLxmf(
            crypto = crypto,
            sourceIdentity = peer,
            sourceHash = peerDest,
            recipient = us,
            recipientDest = ourDest,
            content = "direct hello",
        )
        rig.transport.inject(IncomingPacket(opportunisticPacket, null))
        // runCurrent (NOT advanceUntilIdle) — same reason
        // EngineSendBugTest.drainQueuedOutgoingTest cites: the engine's
        // reannounce loop has an infinite `delay(...)` that
        // advanceUntilIdle would expand into an infinite virtual-time
        // loop, blowing past runTest's 60 s budget. runCurrent drives
        // the pump far enough to consume the channel value, call
        // handleIncoming → handleData → messageRepo.save, without
        // touching the periodic delay coroutines.
        testScheduler.runCurrent()

        val saved = rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" }
        assertNotNull(saved)
        assertEquals(peerDest.toHex(), saved.arrivedViaDest,
            "direct chat: arrivedViaDest = source_hash = peer")
        assertEquals(
            saved.contactHash,
            saved.arrivedViaDest,
            "REGRESSION GUARD: in 1-to-1 direct chats arrivedViaDest MUST equal contactHash " +
                "so the send-time routing override is a no-op vs. legacy direct-to-peer.",
        )

        drain(rig)
    }

    // ---- Helpers -----------------------------------------------------------

    private data class Rig(
        val engine: ReticulumEngine,
        val repos: TestRepos,
        val transport: InjectableTransport,
    )

    private data class TestRepos(
        val identity: InMemoryIdentityRepo,
        val dest: InMemoryDestRepo,
        val msg: InMemoryMsgRepo,
    )

    private fun TestScope.newRig(): Rig {
        val repos = TestRepos(
            identity = InMemoryIdentityRepo(),
            dest = InMemoryDestRepo(),
            msg = InMemoryMsgRepo(),
        )
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = repos.identity,
            destinationRepo = repos.dest,
            messageRepo = repos.msg,
            scope = this,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test Receiver" },
        )
        return Rig(engine, repos, InjectableTransport())
    }

    /** Mirrors EngineSendBugTest.drainTestScope. Closing the inbound
     *  channel is what lets the engine's `incoming.collect` suspension
     *  exit naturally under StandardTestDispatcher — without that the
     *  runTest structured-concurrency check fires
     *  UncompletedCoroutinesError. */
    private suspend fun TestScope.drain(rig: Rig) {
        rig.transport.disconnect()
        rig.engine.detach()
        coroutineContext.cancelChildren()
        testScheduler.advanceUntilIdle()
    }

    private fun storedFor(id: Identity, dest: ByteArray, displayName: String) =
        StoredDestination(
            hash = dest.toHex(),
            identityHash = id.hash!!.toHex(),
            publicKey = id.publicKey,
            destHash = dest,
            nameHash = ByteArray(0),
            ratchetPub = id.ratchetPubKey,
            displayName = displayName,
            appName = "lxmf.delivery",
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = false,
            source = "test",
            hopCount = 1,
        )

    private suspend fun buildOpportunisticLxmf(
        crypto: io.github.thatsfguy.reticulum.crypto.CryptoProvider,
        sourceIdentity: Identity,
        sourceHash: ByteArray,
        recipient: Identity,
        recipientDest: ByteArray,
        content: String,
    ): ByteArray {
        val lxmfPlain = packMessage(
            sourceIdentity = sourceIdentity,
            destHash = recipientDest,
            sourceHash = sourceHash,
            title = "",
            content = content,
            timestampSeconds = 1_700_000_000.0,
            crypto = crypto,
        )
        val encrypted = TokenCrypto(crypto).encrypt(
            lxmfPlain,
            recipient.ratchetPubKey!!,
            recipient.hash!!,
        )
        return buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = recipientDest,
            context = CTX_NONE,
            payload = encrypted,
        )
    }
}

/**
 * Test-only [Transport] that exposes an [inject] method so tests can
 * drop a synthetic inbound packet into the engine's incoming flow.
 * Mirrors [io.github.thatsfguy.reticulum.FakeTransport]'s
 * channel-backed design (the comment in that file explains why a
 * Channel + receiveAsFlow is mandatory under runTest's structured-
 * concurrency check) but adds [inject] which the FakeTransport in
 * EngineSendBugTest keeps private.
 */
private class InjectableTransport : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = Channel<IncomingPacket>(64)
    override val incoming: Flow<IncomingPacket> = _incoming.receiveAsFlow()

    suspend fun inject(packet: IncomingPacket) {
        _incoming.send(packet)
    }

    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        _incoming.close()
    }
    override suspend fun send(packet: ByteArray) { /* outbound is irrelevant for these inbound tests */ }
}
