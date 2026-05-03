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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        // Collect log lines in the background using the TestScope. Replay = 0
        // on the engine's events flow means we must subscribe before we call
        // sendMessage; using launch on `this` (the TestScope) keeps the
        // collector tied to the test's lifecycle.
        val captured = mutableListOf<String>()
        val collectorJob = launch {
            engine.events.collect { ev ->
                if (ev is ReticulumEngine.EngineEvent.Log) captured.add(ev.line)
            }
        }
        // Yield so the collector subscribes before sendMessage emits.
        yield()

        // Engine has NEVER had attach() called → transport is null.
        val msgId = engine.sendMessage(bobDest.toHex(), "this should fail loudly")

        // Drain pending emissions, then stop the collector so runTest can
        // complete without the long-running job leaking. Drain again
        // after cancel so the cancellation actually propagates.
        testScheduler.advanceUntilIdle()
        collectorJob.cancel()
        testScheduler.advanceUntilIdle()

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

    @Test fun `retry loop bails when transport detaches between primary send and retry`() = runTest {
        // Regression test for the residual silent no-op bug after v0.1.31:
        // Engine.sendMessage's primary path checks `transport == null`
        // explicitly, but the async retry coroutine still uses
        // `runCatching { transport?.send(packet) }` which silently no-ops
        // when transport detached mid-flight, leaving the message stuck
        // on state="sent" forever rather than flipping to failed.
        val (engine, repos) = newEngine()

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

        val captured = mutableListOf<String>()
        val collectorJob = launch {
            engine.events.collect { ev ->
                if (ev is ReticulumEngine.EngineEvent.Log) captured.add(ev.line)
            }
        }
        yield()

        val transport = FakeTransport()
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)

        // Primary send completes (with transport attached).
        val msgId = engine.sendMessage(bobDest.toHex(), "this should succeed primary then fail at retry")

        // Verify the primary send made it to the wire.
        assertEquals(
            "sent", repos.msg.getById(msgId)?.state,
            "primary send should have set state to 'sent'",
        )

        // Now detach the transport. The async retry coroutine is still
        // alive in the test scope, scheduled for ~5s from now.
        engine.detach()

        // Advance virtual time past the first retry's MSG_BACKOFF_MS[0]
        // (5000ms) plus the message-state-check delay so the retry
        // coroutine actually runs its loop body. Then cancel the
        // log collector and drain again so runTest sees no
        // uncompleted children when it exits.
        testScheduler.advanceUntilIdle()
        collectorJob.cancel()
        testScheduler.advanceUntilIdle()

        val saved = repos.msg.getById(msgId)
        assertNotNull(saved, "message should still exist")
        assertEquals(
            "failed", saved.state,
            "retry should detect transport went away and flip state to 'failed'; " +
                "current state '${saved.state}' indicates the silent no-op bug returned",
        )
        assertTrue(
            captured.any { "transport detached" in it || "no transport" in it },
            "expected explicit log about transport-detached-at-retry; got: $captured",
        )
    }

    @Test fun `sendMessage to unknown destination throws and persists nothing`() = runTest {
        val (engine, repos) = newEngine()
        val unknownHex = "deadbeef".repeat(4) // 32 hex chars but not in repo

        assertFailsWith<IllegalStateException>(
            "sendMessage must throw when dest is not in the repo, not silently no-op",
        ) {
            engine.sendMessage(unknownHex, "hi")
        }

        assertEquals(
            0, repos.msg.getAll().size,
            "no message should have been persisted before the unknown-dest check fired",
        )
    }

    @Test fun `transport-send-throws marks message failed and logs exception class`() = runTest {
        val (engine, repos) = newEngine()
        val bobHex = seedKnownDestination(repos)

        // Transport that throws on send — simulates a socket that died
        // between the primary path-prime and the actual write.
        val throwingTransport = ThrowingTransport(IllegalStateException("socket closed"))
        engine.attach(throwingTransport, ReticulumEngine.TransportKind.Tcp)

        val captured = mutableListOf<String>()
        val collectorJob = launch {
            engine.events.collect { ev ->
                if (ev is ReticulumEngine.EngineEvent.Log) captured.add(ev.line)
            }
        }
        yield()

        val msgId = engine.sendMessage(bobHex, "doomed")
        testScheduler.advanceUntilIdle()
        collectorJob.cancel()

        val saved = repos.msg.getById(msgId)
        assertNotNull(saved, "message should be persisted even when send throws")
        assertEquals("failed", saved.state, "send-throws must flip state to failed")
        assertTrue(
            captured.any { "send threw" in it && "IllegalStateException" in it },
            "expected log line naming exception class; got: $captured",
        )
        engine.detach()
        testScheduler.advanceUntilIdle()
    }

    @Test fun `concurrent sendMessage calls produce distinct msgIds`() = runTest {
        val (engine, repos) = newEngine()
        val bobHex = seedKnownDestination(repos)
        val transport = FakeTransport()
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)

        // Fire two sends in parallel coroutines. Each one walks through
        // the full sendMessage flow including primePath's delay, save,
        // send, retry-launch.
        val a = async { engine.sendMessage(bobHex, "first") }
        val b = async { engine.sendMessage(bobHex, "second") }
        testScheduler.advanceUntilIdle()
        val idA = a.await()
        val idB = b.await()

        assertTrue(idA != idB, "concurrent sends must produce distinct msgIds (got both = $idA)")
        val msgs = repos.msg.getAll()
        assertEquals(
            2, msgs.size,
            "both messages must be persisted independently",
        )
        // Both should reach state="sent" (proof match is async; not delivered yet)
        for (id in listOf(idA, idB)) {
            val saved = repos.msg.getById(id)
            assertNotNull(saved)
            assertTrue(
                saved.state == "sent" || saved.state == "delivered",
                "msg #$id has unexpected state ${saved.state}",
            )
        }
        engine.detach()
        testScheduler.advanceUntilIdle()
    }

    /** Generate Bob and seed his destination row. Returns his destHash hex. */
    private suspend fun seedKnownDestination(repos: TestRepos): String {
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
        return bobDest.toHex()
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

        // Drain the scheduler so the launched reannounceJob's first
        // iteration runs and emits the announce into FakeTransport.
        testScheduler.advanceUntilIdle()

        assertTrue(
            fakeTransport.sentPackets.isNotEmpty(),
            "FakeTransport.sentPackets is empty — the reannounceJob never sent on attach",
        )
        val firstSend = fakeTransport.sentPackets.first()
        val parsed = parsePacket(firstSend)
        assertNotNull(parsed, "first outbound packet must parse")
        assertEquals(
            PACKET_ANNOUNCE, parsed.packetType,
            "first outbound packet after attach must be the post-switch announce; got pktType=${parsed.packetType}",
        )

        engine.detach()
        // detach() cancels the engine's three long-running jobs but
        // cancellation is non-blocking; runTest checks for incomplete
        // children when the block exits and throws
        // UncompletedCoroutinesError if it sees any. Drain so the
        // cancellations actually propagate.
        testScheduler.advanceUntilIdle()
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

/** Always throws on send(). Used to simulate a socket that died between
 *  the primary path-prime and the actual write — verifies that the
 *  engine's runCatching wrapper surfaces the exception class+message
 *  to the diagnostics log and flips the message state to failed. */
internal class ThrowingTransport(private val cause: Throwable) : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()
    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() { _state.value = TransportState.Disconnected }
    override suspend fun send(packet: ByteArray) { throw cause }
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
