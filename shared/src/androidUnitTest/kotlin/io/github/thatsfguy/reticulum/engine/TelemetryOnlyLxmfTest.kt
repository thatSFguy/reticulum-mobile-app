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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #49: telemetry-only LXMFs (FIELD_TELEMETRY 0x02 /
 * FIELD_TELEMETRY_STREAM 0x03, SPEC §5.9.1 — location sharing from
 * Sideband / Columba / signalk-reticulum) carried no displayable
 * content but were stored + notified anyway. The bubble list filters
 * non-renderable rows, so the user got an empty-message notification
 * and an unread tick against a conversation showing nothing. Fix:
 * [isTelemetryOnlyLxmf] gates all three inbound paths (opportunistic,
 * link, propagation) — drop before store/notify, after the delivery
 * proof so the sender doesn't retry.
 */
class TelemetryOnlyLxmfTest {

    // ---- predicate pins -------------------------------------------------

    @Test fun `telemetry-only with no content is dropped-eligible`() {
        assertTrue(isTelemetryOnlyLxmf("", "", mapOf<Any?, Any?>(0x02 to byteArrayOf(1, 2))))
        assertTrue(isTelemetryOnlyLxmf("", "", mapOf<Any?, Any?>(0x03 to listOf<Any?>())))
        // msgpack decoders surface integer keys at varying widths.
        assertTrue(isTelemetryOnlyLxmf("", "", mapOf<Any?, Any?>(2L to byteArrayOf(1))))
    }

    @Test fun `telemetry riding on a real message is kept`() {
        assertFalse(isTelemetryOnlyLxmf("hello", "", mapOf<Any?, Any?>(0x02 to byteArrayOf(1))),
            "text + telemetry must store normally")
        assertFalse(isTelemetryOnlyLxmf("", "subject", mapOf<Any?, Any?>(0x02 to byteArrayOf(1))),
            "title + telemetry must store normally")
        assertFalse(
            isTelemetryOnlyLxmf("", "", mapOf<Any?, Any?>(
                0x02 to byteArrayOf(1),
                0x06 to listOf<Any?>("webp", byteArrayOf(1, 2, 3)),
            )),
            "image + telemetry must store normally",
        )
        assertFalse(
            isTelemetryOnlyLxmf("", "", mapOf<Any?, Any?>(
                0x02 to byteArrayOf(1),
                0x05 to listOf<Any?>(listOf<Any?>("f.txt", byteArrayOf(1))),
            )),
            "file + telemetry must store normally",
        )
        assertFalse(
            isTelemetryOnlyLxmf("", "", mapOf<Any?, Any?>(
                0x03 to listOf<Any?>(),
                0x07 to listOf<Any?>(16, byteArrayOf(1)),
            )),
            "audio + telemetry must store normally",
        )
    }

    @Test fun `content-less message WITHOUT telemetry fields is not matched`() {
        // Don't over-drop: an empty message with no telemetry (however
        // odd) is outside issue #49's scope — narrow fix, narrow gate.
        assertFalse(isTelemetryOnlyLxmf("", "", emptyMap()))
        assertFalse(isTelemetryOnlyLxmf("", "", mapOf<Any?, Any?>(0x04 to byteArrayOf(1))))
    }

    // ---- engine-level pin (opportunistic path) --------------------------

    @Test fun `telemetry-only opportunistic LXMF is not stored, telemetry-plus-text is`() = runTest {
        val crypto = TestVectors.crypto
        val rig = newRig()
        val us = rig.engine.ensureIdentity()
        val ourDest = computeDestinationHash(crypto, "lxmf.delivery", us.hash!!)
        val sender = Identity(crypto).also { it.generate() }
        val senderDest = computeDestinationHash(crypto, "lxmf.delivery", sender.hash!!)
        rig.repos.dest.upsertFromAnnounce(storedFor(sender, senderDest, "Columba-ish"))
        rig.engine.attach(rig.transport, ReticulumEngine.TransportKind.Tcp)

        // A Sideband-style location share: empty content/title, only a
        // telemetry snapshot field. Must produce NO stored row.
        rig.transport.inject(IncomingPacket(buildOpportunisticLxmf(
            crypto, sender, senderDest, us, ourDest,
            content = "",
            fields = mapOf<Any?, Any?>(0x02 to byteArrayOf(0x0A, 0x0B, 0x0C)),
        ), null))
        testScheduler.runCurrent()
        assertEquals(
            emptyList(), rig.repos.msg.getAll().filter { it.direction == "incoming" },
            "telemetry-only LXMF must not be stored (phantom notification + unread tick, issue #49)",
        )

        // Same sender, telemetry + real text → stores normally.
        rig.transport.inject(IncomingPacket(buildOpportunisticLxmf(
            crypto, sender, senderDest, us, ourDest,
            content = "position attached",
            fields = mapOf<Any?, Any?>(0x02 to byteArrayOf(0x0A)),
        ), null))
        testScheduler.runCurrent()
        val saved = rig.repos.msg.getAll().firstOrNull { it.direction == "incoming" }
        assertNotNull(saved, "telemetry riding on a real message must still store")
        assertEquals("position attached", saved.content)

        drain(rig)
    }

    // ---- harness (mirrors OpportunisticArrivedViaDestTest) --------------

    private data class Rig(
        val engine: ReticulumEngine,
        val repos: TestRepos,
        val transport: TelemetryInjectTransport,
    )

    private data class TestRepos(
        val identity: InMemoryIdentityRepo,
        val dest: InMemoryDestRepo,
        val msg: InMemoryMsgRepo,
    )

    private fun TestScope.newRig(): Rig {
        val repos = TestRepos(InMemoryIdentityRepo(), InMemoryDestRepo(), InMemoryMsgRepo())
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = repos.identity,
            destinationRepo = repos.dest,
            messageRepo = repos.msg,
            scope = this,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test Receiver" },
        )
        return Rig(engine, repos, TelemetryInjectTransport())
    }

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
        fields: Map<Any?, Any?> = emptyMap(),
    ): ByteArray {
        val lxmfPlain = packMessage(
            sourceIdentity = sourceIdentity,
            destHash = recipientDest,
            sourceHash = sourceHash,
            title = "",
            content = content,
            timestampSeconds = 1_700_000_000.0,
            fields = fields,
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

/** Channel-backed injectable transport — see the twin in
 *  OpportunisticArrivedViaDestTest for why Channel + receiveAsFlow is
 *  mandatory under runTest's structured-concurrency check. */
private class TelemetryInjectTransport : Transport {
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
