package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.announce.extractStampCost
import io.github.thatsfguy.reticulum.announce.parseAnnounce
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.lxmf.LxmfStamp
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.parsePacket
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
import kotlin.test.assertNull

/**
 * SPEC §5.7.4 receive side — the local user's "require proof-of-work
 * stamps" setting. Two independent knobs, matching upstream:
 *
 *   - `stampCostProvider` → the cost we advertise in our announce
 *     `app_data[1]`, which is the ONLY way a sender learns to stamp.
 *   - `enforceStampsProvider` → upstream `_enforce_stamps`. Off by
 *     default: an unstamped message is still delivered and the
 *     shortfall only logged (`LXMRouter.py:1871-1872`).
 *
 * The interesting failure mode this pins is asymmetric: advertising a
 * cost while silently accepting everything is harmless, but enforcing
 * a cost our own sender computes differently would drop every message
 * from a correct peer. So the stamped-message case here mines the
 * stamp the way [ReticulumEngine.computeOutboundStamp] does — from
 * `message_id = SHA256(destHash || sourceHash || packed4)` — and
 * requires the receive path to score it as valid.
 */
class LxmfStampGateTest {

    private val cost = 8  // ~256 PoW iterations; ~a second of test time

    @Test fun `default engine advertises stamp_cost 0 and accepts unstamped mail`() = runTest {
        val rig = newRig(stampCost = 0, enforce = false)
        val peer = rig.knownPeer()

        rig.transport.inject(IncomingPacket(rig.opportunistic(peer, "hello", stamp = null), null))
        testScheduler.runCurrent()

        assertNotNull(
            rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" },
            "with no advertised cost every sender is correct not to stamp — must deliver",
        )
        assertNull(
            extractStampCost(rig.announcedAppData()),
            "stamp_cost 0 is upstream's 'no requirement' sentinel and must read back as null",
        )
        rig.drain(this)
    }

    @Test fun `a non-zero cost is advertised in our announce app_data`() = runTest {
        val rig = newRig(stampCost = 12, enforce = false)
        assertEquals(12, extractStampCost(rig.announcedAppData()),
            "senders read the cost from app_data[1] — it is the only channel that carries it")
        rig.drain(this)
    }

    @Test fun `cost above MAX_ADVERTISED_COST is clamped, not announced verbatim`() = runTest {
        val rig = newRig(stampCost = 99, enforce = true)
        assertEquals(
            LxmfStamp.MAX_ADVERTISED_COST, extractStampCost(rig.announcedAppData()),
            "a cost our own sender would refuse to compute would isolate us from our own peers",
        )
        rig.drain(this)
    }

    @Test fun `enforcement off delivers an unstamped message despite the advertised cost`() = runTest {
        val rig = newRig(stampCost = cost, enforce = false)
        val peer = rig.knownPeer()

        rig.transport.inject(IncomingPacket(rig.opportunistic(peer, "unstamped", stamp = null), null))
        testScheduler.runCurrent()

        val saved = rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" }
        assertNotNull(saved, "upstream's _enforce_stamps default is False — deliver and only log")
        assertEquals("unstamped", saved.content)
        rig.drain(this)
    }

    @Test fun `enforcement on drops an unstamped message`() = runTest {
        val rig = newRig(stampCost = cost, enforce = true)
        val peer = rig.knownPeer()

        rig.transport.inject(IncomingPacket(rig.opportunistic(peer, "unstamped", stamp = null), null))
        testScheduler.runCurrent()

        assertEquals(
            emptyList(), rig.repos.msg.getAll().filter { it.direction == "incoming" },
            "enforcing a cost must drop mail that carries no proof of work",
        )
        rig.drain(this)
    }

    @Test fun `enforcement on delivers a correctly stamped message`() = runTest {
        val rig = newRig(stampCost = cost, enforce = true)
        val peer = rig.knownPeer()
        val packet = rig.opportunistic(peer, "stamped", stamp = rig.mineStamp(peer, "stamped", cost))

        rig.transport.inject(IncomingPacket(packet, null))
        testScheduler.runCurrent()

        val saved = rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" }
        assertNotNull(saved, "a stamp mined over the canonical message_id must validate on receive")
        assertEquals("stamped", saved.content)
        rig.drain(this)
    }

    @Test fun `enforcement on drops a garbage stamp`() = runTest {
        val rig = newRig(stampCost = cost, enforce = true)
        val peer = rig.knownPeer()
        // 32 bytes of nothing: costs the sender nothing, and must not
        // pass. (It DOES cost us a workblock derivation — bounded by
        // LxmfStamp.STAMP_VERIFY_BUDGET_MS and memoised per message_id.)
        val packet = rig.opportunistic(peer, "garbage", stamp = ByteArray(LxmfStamp.STAMP_SIZE))

        rig.transport.inject(IncomingPacket(packet, null))
        testScheduler.runCurrent()

        assertEquals(
            emptyList(), rig.repos.msg.getAll().filter { it.direction == "incoming" },
            "a stamp that does no work must score below the cost and be dropped",
        )
        rig.drain(this)
    }

    // ---- harness (mirrors TelemetryOnlyLxmfTest) ------------------------

    private class Rig(
        val engine: ReticulumEngine,
        val repos: TestRepos,
        val transport: StampInjectTransport,
        val crypto: CryptoProvider,
    ) {
        lateinit var ourDest: ByteArray

        private var attached = false

        suspend fun attach() {
            if (attached) return
            engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
            ourDest = computeDestinationHash(crypto, "lxmf.delivery", engine.ensureIdentity().hash!!)
            attached = true
        }

        /** A sender whose announce we already hold, so the inbound
         *  message verifies and reaches the stamp gate rather than
         *  being filtered earlier as unverified. */
        suspend fun knownPeer(): Peer {
            val sender = Identity(crypto).also { it.generate() }
            val senderDest = computeDestinationHash(crypto, "lxmf.delivery", sender.hash!!)
            repos.dest.upsertFromAnnounce(storedFor(sender, senderDest))
            attach()
            return Peer(sender, senderDest)
        }

        /** The app_data our announce actually puts on the wire — sent
         *  through the real announce path and parsed back off the
         *  transport, so a regression in either half shows up here. */
        suspend fun announcedAppData(): ByteArray {
            attach()
            engine.sendAnnounce()
            val announce = transport.sent
                .mapNotNull { parsePacket(it) }
                .last { it.packetType == PACKET_ANNOUNCE }
            val parsed = parseAnnounce(
                payload = announce.payload,
                contextFlag = announce.contextFlag,
                destHashFromHeader = announce.destHash,
                crypto = crypto,
            )
            assertNotNull(parsed, "engine must emit a parseable announce")
            return parsed.appData
        }

        /** The packed 4-element payload the message_id is derived from
         *  — identical on both sides, which is the whole contract. */
        private fun packed4(content: String) = MessagePack.encode(
            listOf(TIMESTAMP, ByteArray(0), content.encodeToByteArray(), emptyMap<Any?, Any?>()),
        )

        suspend fun mineStamp(peer: Peer, content: String, cost: Int): ByteArray {
            val messageId = LxmfStamp.computeMessageId(
                destHash = ourDest,
                sourceHash = peer.destHash,
                packedPayload4 = packed4(content),
                crypto = crypto,
            )
            return LxmfStamp.findStamp(LxmfStamp.buildWorkblock(messageId, crypto), cost, crypto)
        }

        suspend fun opportunistic(peer: Peer, content: String, stamp: ByteArray?): ByteArray {
            val plain = packMessage(
                sourceIdentity = peer.identity,
                destHash = ourDest,
                sourceHash = peer.destHash,
                title = "",
                content = content,
                timestampSeconds = TIMESTAMP,
                fields = emptyMap(),
                crypto = crypto,
                stamp = stamp,
            )
            val us = engine.ensureIdentity()
            val encrypted = TokenCrypto(crypto).encrypt(plain, us.ratchetPubKey!!, us.hash!!)
            return buildPacket(
                headerType = HEADER_1,
                destType = DEST_SINGLE,
                packetType = PACKET_DATA,
                destHash = ourDest,
                context = CTX_NONE,
                payload = encrypted,
            )
        }

        suspend fun drain(scope: TestScope) {
            transport.disconnect()
            engine.detach()
            scope.coroutineContext.cancelChildren()
            scope.testScheduler.advanceUntilIdle()
        }
    }

    private class Peer(val identity: Identity, val destHash: ByteArray)

    private class TestRepos(
        val identity: InMemoryIdentityRepo,
        val dest: InMemoryDestRepo,
        val msg: InMemoryMsgRepo,
    )

    private fun TestScope.newRig(stampCost: Int, enforce: Boolean): Rig {
        val repos = TestRepos(InMemoryIdentityRepo(), InMemoryDestRepo(), InMemoryMsgRepo())
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = repos.identity,
            destinationRepo = repos.dest,
            messageRepo = repos.msg,
            scope = this,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test Receiver" },
            stampCostProvider = { stampCost },
            enforceStampsProvider = { enforce },
        )
        return Rig(engine, repos, StampInjectTransport(), TestVectors.crypto)
    }

    private companion object {
        const val TIMESTAMP = 1_700_000_000.0
    }
}

private fun storedFor(id: Identity, dest: ByteArray) = StoredDestination(
    hash = dest.toHex(),
    identityHash = id.hash!!.toHex(),
    publicKey = id.publicKey,
    destHash = dest,
    nameHash = ByteArray(0),
    ratchetPub = id.ratchetPubKey,
    displayName = "Stamped Peer",
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

/** Channel-backed injectable transport — see the twin in
 *  TelemetryOnlyLxmfTest for why Channel + receiveAsFlow is mandatory
 *  under runTest's structured-concurrency check. */
private class StampInjectTransport : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = Channel<IncomingPacket>(64)
    override val incoming: Flow<IncomingPacket> = _incoming.receiveAsFlow()
    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        _incoming.close()
    }
    val sent = mutableListOf<ByteArray>()
    override suspend fun send(packet: ByteArray) { sent.add(packet) }
    suspend fun inject(packet: IncomingPacket) { _incoming.send(packet) }
}
