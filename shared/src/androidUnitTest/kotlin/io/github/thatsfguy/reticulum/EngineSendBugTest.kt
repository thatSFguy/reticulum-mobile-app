package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.store.DestinationRepository
import io.github.thatsfguy.reticulum.store.IdentityRepository
import io.github.thatsfguy.reticulum.store.MessageRepository
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for two v0.1.31 bugs that were invisible in production
 * because they only surfaced as silent loss:
 *   - sendMessage's `transport?.send(packet)` no-op'd when transport was
 *     null (mid-disconnect, mid-reconnect, mid-transport-switch).
 *     The "msg #N: sending" log line ran first, so the diagnostics view
 *     said we sent even though no bytes hit the wire.
 *   - lastAnnounceMs persisted across attach()/detach(), so a transport
 *     switch carried the throttle from the previous rnsd. The new rnsd
 *     never saw our announce and inbound proofs back to us silently
 *     failed to route.
 */
class EngineSendBugTest {

    @Test fun `sendMessage with no transport marks message failed and logs explicit error`() = runTest {
        val (engine, repos) = newEngine()

        // Make Bob a known reachable destination so sendMessage gets past the
        // require() guards and only fails at the actual send() step.
        val bob = Identity(TestVectors.crypto).also { it.generate() }
        val bobDest = computeDestinationHash(TestVectors.crypto, "lxmf.delivery", bob.hash!!)
        repos.dest.upsertFromAnnounce(StoredDestination(
            hash = bobDest.toHex(),
            identityHash = bob.hash!!.toHex(),
            publicKey = bob.publicKey,
            destHash = bobDest,
            nameHash = ByteArray(0),
            ratchetPub = bob.ratchetPubKey,
            displayName = "Bob",
            appName = "lxmf.delivery",
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = true,
            source = "test",
            hopCount = 1,
        ))

        // Collect log lines in the background so we can assert on them.
        val captured = mutableListOf<String>()
        val collectorJob = kotlinx.coroutines.GlobalScope.launch {
            engine.events.collect { ev ->
                if (ev is ReticulumEngine.EngineEvent.Log) captured.add(ev.line)
            }
        }
        // Tiny yield so the collector is subscribed before sendMessage emits.
        kotlinx.coroutines.delay(50)

        // Engine has NEVER had attach() called → transport is null.
        val msgId = engine.sendMessage(bobDest.toHex(), "this should fail loudly")

        // Drain a couple more microticks so the trailing log emits land.
        kotlinx.coroutines.delay(100)
        collectorJob.cancel()

        val saved = repos.msg.getById(msgId)
        assertNotNull(saved, "message should have been persisted")
        assertEquals("failed", saved.state, "state should flip to failed when transport is null")
        assertTrue(
            captured.any { "no transport attached" in it },
            "expected an explicit 'no transport attached' log line; got: $captured",
        )
        assertTrue(
            captured.none { "✓ delivered" in it },
            "must NOT log delivered when no transport was available",
        )
    }

    @Test fun `attach resets the announce throttle so the new transport gets a fresh announce`() = runTest {
        val (engine, _) = newEngine()

        // Force the throttle into the "blocked" state by sending an announce
        // first (this updates lastAnnounceMs even with no transport).
        engine.sendAnnounce()

        // Now attach a fake transport. The reannounceJob's first iteration
        // should fire IMMEDIATELY despite lastAnnounceMs being recent —
        // because attach() resets it to 0.
        val fakeTransport = FakeTransport()
        engine.attach(fakeTransport, ReticulumEngine.TransportKind.Tcp)

        val firstSend = withTimeout(2_000) { fakeTransport.outbound.first() }
        val parsed = parsePacket(firstSend)
        assertNotNull(parsed, "first outbound packet must parse")
        assertEquals(
            PACKET_ANNOUNCE, parsed.packetType,
            "first outbound packet after attach must be the post-switch announce; instead got pktType=${parsed.packetType}"
        )

        engine.detach()
    }

    // ---- Test infrastructure ------------------------------------------------

    private data class TestRepos(
        val identity: IdentityRepository,
        val dest: DestinationRepository,
        val msg: MessageRepository,
    )

    private fun TestScope.newEngine(): Pair<ReticulumEngine, TestRepos> {
        val repos = TestRepos(
            identity = InMemoryIdentityRepo(),
            dest     = InMemoryDestRepo(),
            msg      = InMemoryMsgRepo(),
        )
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = repos.identity,
            destinationRepo = repos.dest,
            messageRepo = repos.msg,
            scope = this,
            nowMs = { 1_700_000_000_000L },
            displayNameProvider = { "Test Sender" },
        )
        return engine to repos
    }
}

/** Records every send() call into a SharedFlow so tests can assert on what
 *  the engine pushed to the wire. State is stuck on Connected — tests that
 *  need to simulate disconnect can manipulate the StateFlow directly. */
internal class FakeTransport : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    val outbound = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val sentPackets = mutableListOf<ByteArray>()

    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() { _state.value = TransportState.Disconnected }
    override suspend fun send(packet: ByteArray) {
        sentPackets.add(packet)
        outbound.emit(packet)
    }
}

internal class InMemoryIdentityRepo : IdentityRepository {
    private var stored: StoredIdentity? = null
    override suspend fun save(identity: StoredIdentity) { stored = identity }
    override suspend fun load(): StoredIdentity? = stored
}

internal class InMemoryDestRepo : DestinationRepository {
    private val rows = mutableMapOf<String, StoredDestination>()
    override suspend fun upsertFromAnnounce(record: StoredDestination) { rows[record.hash] = record }
    override suspend fun upsertManualStub(record: StoredDestination) { rows.putIfAbsent(record.hash, record) }
    override suspend fun get(hash: String): StoredDestination? = rows[hash]
    override suspend fun getAll(): List<StoredDestination> = rows.values.toList()
    override suspend fun setFavorite(hash: String, favorite: Boolean) {
        rows[hash]?.let { rows[hash] = it.copy(favorite = favorite, hidden = false) }
    }
    override suspend fun delete(hash: String) { rows[hash]?.let { rows[hash] = it.copy(hidden = true) } }
    override suspend fun deleteAll() { rows.clear() }
}

internal class InMemoryMsgRepo : MessageRepository {
    private val rows = mutableMapOf<Long, StoredMessage>()
    private var nextId: Long = 1
    override suspend fun save(message: StoredMessage): Long {
        val id = if (message.id == 0L) nextId++ else message.id
        rows[id] = message.copy(id = id)
        return id
    }
    override suspend fun getById(id: Long): StoredMessage? = rows[id]
    override suspend fun getForContact(contactHash: String): List<StoredMessage> =
        rows.values.filter { it.contactHash == contactHash }.sortedBy { it.timestamp }
    override suspend fun getAll(): List<StoredMessage> = rows.values.toList()
    override suspend fun getOutgoingByPacketHash(hash: String): StoredMessage? =
        rows.values.firstOrNull { it.packetHash == hash && it.direction == "outgoing" }
    override suspend fun updateState(
        id: Long, state: String?, attempts: Int?, lastAttempt: Long?,
        lastError: String?, packetHash: String?,
    ) {
        rows[id]?.let {
            rows[id] = it.copy(
                state = state ?: it.state,
                attempts = attempts ?: it.attempts,
                lastAttempt = lastAttempt ?: it.lastAttempt,
                lastError = lastError ?: it.lastError,
                packetHash = packetHash ?: it.packetHash,
            )
        }
    }
    override suspend fun deleteForContact(contactHash: String) {
        rows.entries.removeAll { it.value.contactHash == contactHash }
    }
}
