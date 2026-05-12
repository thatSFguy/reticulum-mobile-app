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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Ignore
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

    @Test fun `sendMessage with no transport parks the message in queued state for later drain`() = runTest {
        // v1.1.10 behavior change: previously sendMessage marked the row
        // "failed" the moment hasAnyTransport() returned false, forcing the
        // user to retype on every brief RNode drop. Now it parks the row
        // in "queued" state and ReticulumEngine.drainQueuedOutgoing picks
        // it up on the next successful attach. /users taps that hit a
        // BLE-supervisor backoff window survive the window.
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

        // Engine has NEVER had attach() called → transports is empty.
        val msgId = engine.sendMessage(bobDest.toHex(), "this should park, not fail")

        testScheduler.advanceUntilIdle()
        collectorJob.cancel()
        testScheduler.advanceUntilIdle()

        val saved = repos.msg.getById(msgId)
        assertNotNull(saved, "message should have been persisted")
        assertEquals("queued", saved.state, "state should be 'queued' when no transport, not 'failed'")
        assertTrue(
            captured.any { "queued" in it && "no transport" in it },
            "expected an explicit 'queued — no transport' log line; got: $captured",
        )
        assertTrue(
            captured.none { "✓ delivered" in it },
            "must NOT log delivered when no transport was available",
        )
        drainTestScope(engine)
    }

    @Test fun `attach drains queued outgoing messages so they leave queued state`() = runTest {
        // Companion to the queued-on-no-transport test: once a transport
        // attaches, ReticulumEngine.drainQueuedOutgoing flips every queued
        // outgoing row to "pending" and re-runs the send path so the
        // bytes actually hit the wire. Without this hook, the queued
        // state would be a one-way trip.
        //
        // We deliberately do NOT advanceUntilIdle after attach — the full
        // send path includes tryDeliverOverLink which loops with
        // LINK_RETRY_INTERVAL_MS+ delays summing past runTest's default
        // 60s virtual-time budget (same coroutine-leak class as the
        // @Ignore'd sibling tests below). runCurrent() drives the drain
        // coroutine far enough that the row's state flips off "queued"
        // (to "pending" or further) before drainTestScope cancels the
        // in-flight send via cancelChildren.
        val (engine, repos) = newEngine()
        val bobHex = seedKnownDestination(repos)

        val msgId = engine.sendMessage(bobHex, "queued then drained")
        assertEquals("queued", repos.msg.getById(msgId)?.state, "must start parked")

        val transport = ThrowingTransport(IllegalStateException("simulated send-time failure"))
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
        testScheduler.runCurrent()

        val finalState = repos.msg.getById(msgId)?.state
        assertTrue(
            finalState != null && finalState != "queued",
            "drain should have moved the row off 'queued' — saw '$finalState'",
        )

        transport.disconnect()
        drainTestScope(engine)
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
        transport.disconnect()
        drainTestScope(engine)
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
        drainTestScope(engine)
    }

    @Test fun `sendMessage to a manual-stub contact parks the row as queued waiting for an announce`() = runTest {
        // Manual-stub contacts (addManualDestination, pre-announce) have
        // publicKey.size == 0 and identityHash == "". Evolution of this
        // path:
        //   v1.0.22 — require()-throws crashed iOS via the @Throws-less
        //     suspend bridge when the tester tapped Send on a manual
        //     contact.
        //   v1.0.23 — converted to state="failed" soft-fail. No crash,
        //     but tester report 2026-05-11 said the message just got a
        //     red X and died with no recovery path.
        //   v1.0.25 — convert further to state="queued"; the engine
        //     fires an active path? request and handleAnnounce drains
        //     the queue once the contact's announce arrives. This test
        //     pins the queued-state contract; the companion test below
        //     pins the drain-on-announce companion.
        val (engine, repos) = newEngine()
        val stubHex = "0123456789abcdef".repeat(2) // 32 hex chars
        val stubBytes = ByteArray(16) { i -> ((i * 17 + 3) and 0xFF).toByte() }
        repos.dest.upsertManualStub(StoredDestination(
            hash = stubHex,
            identityHash = "",
            publicKey = ByteArray(0),
            destHash = stubBytes,
            nameHash = ByteArray(0),
            ratchetPub = null,
            displayName = "",
            appName = null,
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = true,
            source = "manual",
            hopCount = 0,
        ))

        val msgId = engine.sendMessage(stubHex, "queued for an announce")

        val saved = repos.msg.getById(msgId)
        assertNotNull(saved, "message must be persisted even for a not-yet-messagable contact")
        assertEquals(
            "queued", saved.state,
            "manual-stub send must park in 'queued' (not 'failed') so handleAnnounce can drain it later",
        )
        assertTrue(
            saved.lastError?.contains("public key") == true ||
                saved.lastError?.contains("identity hash") == true,
            "lastError should mention the missing-key reason so the UI tooltip is actionable; got: ${saved.lastError}",
        )
        drainTestScope(engine)
    }

    @Test fun `handleAnnounce drains queued messages once the contact's public key fills in`() = runTest {
        // Companion to the manual-stub queued-on-no-key test: when an
        // announce arrives for a contact that previously had no key,
        // any messages parked in "queued" state must auto-fire. This
        // is the load-bearing fix for tester report 2026-05-11
        // ("manually added contact, message just got an X") — the
        // queued state alone wouldn't fix the report; the drain-on-
        // pubkey-arrival is what closes the loop.
        val (engine, repos) = newEngine()

        // Stage 1: build the manual stub and queue a message to it.
        val bob = Identity(TestVectors.crypto).also { it.generate() }
        val bobDest = computeDestinationHash(TestVectors.crypto, "lxmf.delivery", bob.hash!!)
        val bobHex = bobDest.toHex()
        repos.dest.upsertManualStub(StoredDestination(
            hash = bobHex,
            identityHash = "",
            publicKey = ByteArray(0),
            destHash = bobDest,
            nameHash = ByteArray(0),
            ratchetPub = null,
            displayName = "",
            appName = null,
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = true,
            source = "manual",
            hopCount = 0,
        ))
        val msgId = engine.sendMessage(bobHex, "queued — drain on Bob's announce")
        assertEquals("queued", repos.msg.getById(msgId)?.state, "must start parked waiting for the key")

        // Stage 2: simulate Bob's announce arriving. Skip parsing the
        // wire-format packet and call upsertFromAnnounce directly with
        // the now-known keys — the test seam mirrors what the real
        // announce handler does (it computes the same merged row and
        // calls upsertFromAnnounce + drainQueuedOutgoing). Then attach
        // a throwing transport and explicitly invoke drainQueuedOutgoing
        // via the public attach hook (drainQueuedOutgoing also runs
        // there). Throwing avoids the 60s virtual-clock retry-loop
        // budget the @Ignore'd sibling tests hit.
        repos.dest.upsertFromAnnounce(StoredDestination(
            hash = bobHex,
            identityHash = bob.hash!!.toHex(),
            publicKey = bob.publicKey,
            destHash = bobDest,
            nameHash = ByteArray(0),
            ratchetPub = bob.ratchetPubKey,
            displayName = "Bob (post-announce)",
            appName = "lxmf.delivery",
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = true,
            source = "announce",
            hopCount = 1,
        ))
        val transport = ThrowingTransport(IllegalStateException("simulated send-time failure"))
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
        testScheduler.runCurrent()

        val finalState = repos.msg.getById(msgId)?.state
        assertTrue(
            finalState != null && finalState != "queued",
            "drainQueuedOutgoing should have re-fired the send once Bob's key was known — saw '$finalState'",
        )

        transport.disconnect()
        drainTestScope(engine)
    }

    // Ignored: leaks coroutines under runTest's structured-concurrency check.
    // The engine's reannounceJob is a `while (true) { ... delay(N) }` loop on
    // the TestScope, and even after detach() + cancelChildren the cleanup
    // does not propagate before runTest's 60s dispatch timeout fires
    // UncompletedCoroutinesError. Same shape as the other two ignored tests
    // below — needs a bigger refactor (engine on backgroundScope, or a
    // dedicated job a test can fully await) to unblock.
    @Ignore
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
        // Channel-close on the test transport gives the engine's pump
        // collect a clean exit; drainTestScope then handles the rest.
        throwingTransport.disconnect()
        drainTestScope(engine)
    }

    @Ignore  // see note on transport-send-throws above — same coroutine-leak class
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
        transport.disconnect()
        drainTestScope(engine)
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

    // §2.3 originator HEADER_1→HEADER_2 conversion. Without this conversion,
    // upstream RNS Transport silently drops our outbound DATA at line 1497
    // because the inbound DATA-forwarding branch only fires for packets
    // with transport_id != None. This was the chronic Mob-App-outbound
    // failure we tracked down 2026-05-03 by offline replay-decrypt
    // (the crypto was correct; the framing was wrong).

    @Test fun `sendMessage emits HEADER_2 with transport_id when destination is via a transit relay`() = runTest {
        val (engine, repos) = newEngine()
        val transport = FakeTransport()
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)

        val bob = Identity(TestVectors.crypto).also { it.generate() }
        val bobDest = computeDestinationHash(TestVectors.crypto, "lxmf.delivery", bob.hash!!)
        val transitId = ByteArray(16) { 0xab.toByte() }
        repos.dest.upsertFromAnnounce(StoredDestination(
            hash = bobDest.toHex(),
            identityHash = bob.hash!!.toHex(),
            publicKey = bob.publicKey,
            destHash = bobDest,
            nameHash = ByteArray(0),
            ratchetPub = bob.ratchetPubKey,
            displayName = "Bob via transit",
            appName = "lxmf.delivery",
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = true,
            source = "test",
            hopCount = 2,                  // > 1 → conversion required per §2.3
            nextHop = transitId,           // known transport_id of the transit relay
        ))

        engine.sendMessage(bobDest.toHex(), "via transit")

        // Drop the announce(s) the engine fired on attach; pick the first DATA.
        // Filter: match the destination_hash slot to bobDest. The slot
        // is at offset 2 for HEADER_1 and offset 18 for HEADER_2 — check
        // both. This skips the announce-on-attach packet AND the path?
        // packet (which sendMessage's primePath fires before the actual
        // send; path? has a different dest_hash, the well-known
        // 6b9f66... target).
        val data = transport.sentPackets.firstOrNull { p ->
            (p.size >= 18 && p.copyOfRange(2, 18).contentEquals(bobDest)) ||
            (p.size >= 34 && p.copyOfRange(18, 34).contentEquals(bobDest))
        }
        assertNotNull(data, "expected at least one outbound DATA packet")
        val parsed = parsePacket(data)
        assertNotNull(parsed)
        assertEquals(1, parsed.headerType, "must be HEADER_2 when hops>1 and nextHop known")
        assertEquals(1, parsed.transportType, "transport_type must be TRANSPORT (1) for HEADER_2 forwarded DATA")
        kotlin.test.assertContentEquals(transitId, parsed.transportId, "transport_id must equal the cached nextHop")
        kotlin.test.assertContentEquals(bobDest, parsed.destHash, "dest_hash must follow the transport_id slot intact")

        transport.disconnect()
        drainTestScope(engine)
    }

    @Test fun `sendMessage stays on HEADER_1 when hopCount is 1 even if nextHop is known`() = runTest {
        // Reason: §2.3 only mandates conversion when path_table HOPS > 1.
        // Our hopCount uses upstream's "wire_hops + 1" semantic, so a
        // value of 1 means a directly-attached destination — no transit
        // relay between us. HEADER_1 is correct in this case.
        val (engine, repos) = newEngine()
        val transport = FakeTransport()
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)

        val bob = Identity(TestVectors.crypto).also { it.generate() }
        val bobDest = computeDestinationHash(TestVectors.crypto, "lxmf.delivery", bob.hash!!)
        repos.dest.upsertFromAnnounce(StoredDestination(
            hash = bobDest.toHex(),
            identityHash = bob.hash!!.toHex(),
            publicKey = bob.publicKey,
            destHash = bobDest,
            nameHash = ByteArray(0),
            ratchetPub = bob.ratchetPubKey,
            displayName = "Bob direct",
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
            nextHop = ByteArray(16) { 0xab.toByte() },
        ))

        engine.sendMessage(bobDest.toHex(), "direct")

        // Filter: match the destination_hash slot to bobDest. The slot
        // is at offset 2 for HEADER_1 and offset 18 for HEADER_2 — check
        // both. This skips the announce-on-attach packet AND the path?
        // packet (which sendMessage's primePath fires before the actual
        // send; path? has a different dest_hash, the well-known
        // 6b9f66... target).
        val data = transport.sentPackets.firstOrNull { p ->
            (p.size >= 18 && p.copyOfRange(2, 18).contentEquals(bobDest)) ||
            (p.size >= 34 && p.copyOfRange(18, 34).contentEquals(bobDest))
        }
        assertNotNull(data)
        val parsed = parsePacket(data)
        assertNotNull(parsed)
        assertEquals(0, parsed.headerType, "must stay on HEADER_1 when hops==1 (directly attached)")
        kotlin.test.assertEquals(null, parsed.transportId)

        transport.disconnect()
        drainTestScope(engine)
    }

    @Test fun `sendMessage stays on HEADER_1 when nextHop is unknown despite multi-hop path`() = runTest {
        // Defensive: if our path table reports the destination is far
        // away but we never recorded a transport_id for it (e.g. only
        // ever saw a HEADER_1 announce echo), we have nothing to fill
        // the HEADER_2 slot with. Falling back to HEADER_1 means the
        // packet still hits the wire (and may still be dropped by a
        // transit, but that's no worse than before this fix).
        val (engine, repos) = newEngine()
        val transport = FakeTransport()
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)

        val bob = Identity(TestVectors.crypto).also { it.generate() }
        val bobDest = computeDestinationHash(TestVectors.crypto, "lxmf.delivery", bob.hash!!)
        repos.dest.upsertFromAnnounce(StoredDestination(
            hash = bobDest.toHex(),
            identityHash = bob.hash!!.toHex(),
            publicKey = bob.publicKey,
            destHash = bobDest,
            nameHash = ByteArray(0),
            ratchetPub = bob.ratchetPubKey,
            displayName = "Bob far",
            appName = "lxmf.delivery",
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = true,
            source = "test",
            hopCount = 3,
            nextHop = null,
        ))

        engine.sendMessage(bobDest.toHex(), "no transit_id known")

        // Filter: match the destination_hash slot to bobDest. The slot
        // is at offset 2 for HEADER_1 and offset 18 for HEADER_2 — check
        // both. This skips the announce-on-attach packet AND the path?
        // packet (which sendMessage's primePath fires before the actual
        // send; path? has a different dest_hash, the well-known
        // 6b9f66... target).
        val data = transport.sentPackets.firstOrNull { p ->
            (p.size >= 18 && p.copyOfRange(2, 18).contentEquals(bobDest)) ||
            (p.size >= 34 && p.copyOfRange(18, 34).contentEquals(bobDest))
        }
        assertNotNull(data)
        val parsed = parsePacket(data)
        assertNotNull(parsed)
        assertEquals(0, parsed.headerType, "no transport_id known → fall back to HEADER_1")

        transport.disconnect()
        drainTestScope(engine)
    }

    // §2.3 conversion also applies to LINKREQUEST. Without it, fetchNomadPage
    // and syncPropagation send a HEADER_1 LINKREQ to a multi-hop nomad /
    // propagation node; upstream RNS Transport silently drops the packet
    // because it lacks transport_id. Reproduced 2026-05-03 against
    // tools/test_nomadnet_node.py via local transport node — LINKREQ never
    // reached the responder, the LRPROOF never came back, fetch failed at
    // the 45s timeout. Same shape as the v0.1.40 DATA bug.
    @Test fun `fetchNomadPage emits HEADER_2 LINKREQUEST when destination is via a transit relay`() = runTest {
        val (engine, repos) = newEngine()
        val transport = FakeTransport()
        engine.attach(transport, ReticulumEngine.TransportKind.Tcp)

        val nomad = Identity(TestVectors.crypto).also { it.generate() }
        val nomadDest = computeDestinationHash(TestVectors.crypto, "nomadnetwork.node", nomad.hash!!)
        val transitId = ByteArray(16) { 0xcd.toByte() }
        repos.dest.upsertFromAnnounce(StoredDestination(
            hash = nomadDest.toHex(),
            identityHash = nomad.hash!!.toHex(),
            publicKey = nomad.publicKey,
            destHash = nomadDest,
            nameHash = ByteArray(0),
            ratchetPub = nomad.ratchetPubKey,
            displayName = "Nomad via transit",
            appName = "nomadnetwork.node",
            appLabel = null,
            telemetry = null,
            lat = null, lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = false,
            source = "test",
            hopCount = 2,                  // > 1 → LINKREQ HEADER_2 conversion required
            nextHop = transitId,
        ))

        // fetchNomadPage will run primePath (1.5s delay), send the LINKREQ,
        // then suspend on awaitProof for 45s without an LRPROOF. runTest's
        // virtual scheduler auto-advances through both delays — the call
        // returns a Result.failure but the LINKREQ has already hit the wire
        // by then. Same pattern as the sendMessage HEADER_2 tests above.
        val result = engine.fetchNomadPage(nomadDest.toHex())
        assertTrue(result.isFailure, "expected timeout failure (no responder); got ${result.getOrNull()}")

        // Find the LINKREQUEST: packet_type bits = 0x02 in flags low 2 bits,
        // and dest_hash slot matches nomadDest (offset 18 for HEADER_2,
        // offset 2 for HEADER_1 fallback).
        val linkReq = transport.sentPackets.firstOrNull { p ->
            if (p.size < 19) return@firstOrNull false
            val pktType = p[0].toInt() and 0x03
            if (pktType != 0x02) return@firstOrNull false
            p.copyOfRange(2, 18).contentEquals(nomadDest) ||
            (p.size >= 34 && p.copyOfRange(18, 34).contentEquals(nomadDest))
        }
        assertNotNull(linkReq, "expected an outbound LINKREQUEST to the nomad destination")
        val parsed = parsePacket(linkReq)
        assertNotNull(parsed)
        assertEquals(2, parsed.packetType, "must be PACKET_LINKREQ")
        assertEquals(1, parsed.headerType, "must be HEADER_2 when hops>1 and nextHop known")
        assertEquals(1, parsed.transportType, "transport_type must be TRANSPORT (1) for HEADER_2 forwarded LINKREQ")
        kotlin.test.assertContentEquals(transitId, parsed.transportId, "transport_id must equal the cached nextHop")
        kotlin.test.assertContentEquals(nomadDest, parsed.destHash, "dest_hash must follow the transport_id slot intact")

        transport.disconnect()
        drainTestScope(engine)
    }

    @Ignore  // see note on transport-send-throws above — same coroutine-leak class
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

        fakeTransport.disconnect()
        drainTestScope(engine)
    }

    // ---- Test infrastructure ------------------------------------------------

    private data class TestRepos(
        val identity: IdentityRepository,
        val dest: DestinationRepository,
        val msg: MessageRepository,
    )

    /**
     * Build the engine using the TestScope itself. backgroundScope was
     * tried (and works for cleanup) but its launches don't reliably
     * fire under testScheduler.advanceUntilIdle() in this kotlinx-
     * coroutines-test version — the reannounceJob's first iteration
     * never ran, so the announce-throttle-reset test couldn't see any
     * sent packet.
     *
     * Cleanup is each test's responsibility: end with [drainTestScope]
     * which calls engine.detach() (cancels the 3 attach jobs the engine
     * owns), then coroutineContext.cancelChildren() (kills sendMessage's
     * retry-loop and any test-launched collectors), then drains the
     * scheduler so all those cancellations propagate.
     */
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

    /**
     * Cancel everything the engine owns AND everything the test
     * launched on TestScope, then drain so the cancellations propagate
     * before runTest's structured-concurrency check fires. Three steps
     * because cancelChildren alone misses the engine's flow.collect
     * suspensions that live inside the attach-jobs, while detach alone
     * misses the per-message retry-loop scope.launch from sendMessage.
     */
    private fun TestScope.drainTestScope(engine: ReticulumEngine) {
        engine.detach()
        coroutineContext.cancelChildren()
        testScheduler.advanceUntilIdle()
    }
}

/**
 * Channel-backed test transport. The engine's pump does
 * `transport.incoming.collect { }` which suspends forever waiting for
 * emissions when [_incoming] is a SharedFlow — and that suspension
 * doesn't reliably respond to coroutine cancellation under
 * StandardTestDispatcher in kotlinx-coroutines-test 1.8.1, which is
 * what trips runTest's structured-concurrency check.
 *
 * Backing [incoming] with a [Channel] flipped via [receiveAsFlow]
 * gives the engine's collect a clean exit path: when the test calls
 * [disconnect], the channel is closed, the collect terminates
 * naturally, and the pump coroutine completes. No more
 * UncompletedCoroutinesError.
 */
internal class ThrowingTransport(private val cause: Throwable) : Transport {
    private val _state = MutableStateFlow(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = kotlinx.coroutines.channels.Channel<IncomingPacket>(64)
    override val incoming: Flow<IncomingPacket> = _incoming.receiveAsFlow()
    override suspend fun connect() { _state.value = TransportState.Connected }
    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        _incoming.close()
    }
    override suspend fun send(packet: ByteArray) { throw cause }
}

/** Records every send() call so tests can assert on what the engine
 *  pushed to the wire. Channel-backed [incoming] (see ThrowingTransport
 *  doc above for why) closed on [disconnect] so the engine's pump
 *  coroutine can complete cleanly under runTest. */
internal class FakeTransport : Transport {
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
    override suspend fun send(packet: ByteArray) {
        sentPackets.add(packet)
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
    override suspend fun setUserLabel(hash: String, label: String?) {
        rows[hash]?.let { rows[hash] = it.copy(userLabel = label?.takeIf { s -> s.isNotBlank() }?.trim()) }
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
