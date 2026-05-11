package io.github.thatsfguy.reticulum.interop

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.store.DestinationRepository
import io.github.thatsfguy.reticulum.transport.TcpInterface
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end interop tests that drive our Kotlin engine against the
 * sibling [reticulum-forwarding-service] Go binary over a real rnsd on
 * loopback. Models the Go harness in
 * `../reticulum-forwarding-service/tests/interop/harness_test.go`
 * but with the Kotlin engine substituted for the Python case-script
 * peer — so a green test proves the engine talks the same wire bytes
 * as upstream RNS for this exchange.
 *
 * Skipped automatically when prerequisites are missing (rnsd not on
 * PATH, fwdsvc binary not built, Python RNS/LXMF not importable).
 * See [FwdsvcHarness.startOrSkip] for the probe rules.
 *
 * Each test owns a fresh harness (fresh rnsd + fresh fwdsvc + fresh
 * tempdir). Per-case isolation is what the Go harness gives up in
 * exchange for sharing rnsd across cases — we pay the ~2s rnsd boot
 * to avoid the static-shared-state foot-gun in JUnit, where one
 * test's state surviving into another would mask real bugs.
 */
class FwdsvcInteropTest {

    /**
     * Simplest path: `/version` reply is ~80 bytes plaintext, well
     * under the 295-byte opportunistic msgpack cap, so it never opens
     * a Link. If this case fails, something fundamental (announce,
     * Token encrypt, decrypt) is broken.
     */
    @Test fun opportunistic_short_version() = runBlocking {
        val harness = FwdsvcHarness.startOrSkip("opportunistic_short")
        Assume.assumeTrue("fwdsvc/rnsd prerequisites missing — skipping", harness != null)
        harness!!.use {
            driveCase(harness) { engine, fwdsvcHashHex ->
                val reply = sendAndAwaitReply(
                    engine = engine,
                    destHashHex = fwdsvcHashHex,
                    content = "/version",
                    timeoutMs = 45_000,
                    matcher = { it.contains("fwdsvc", ignoreCase = true) && it.contains("github.com", ignoreCase = true) },
                )
                assertTrue(reply != null, "no /version reply received from fwdsvc within timeout")
                println("[case] /version reply: ${reply!!.take(200)}")
            }
            harness.disarmLogDump()
        }
    }

    /**
     * `/join` then `/users` against an empty roster — reply is
     * "Users (1)" with our display name, ~30 bytes, fits in one link
     * DATA packet. Exercises the LRREQ → LRPROOF → link DATA path
     * (fwdsvc upgrades the reply to a Link because the caller sent
     * DIRECT, which our engine does by default for sendMessage).
     */
    @Test fun link_data_one_user() = runBlocking {
        val harness = FwdsvcHarness.startOrSkip("link_data_one_user")
        Assume.assumeTrue("fwdsvc/rnsd prerequisites missing — skipping", harness != null)
        harness!!.use {
            driveCase(harness) { engine, fwdsvcHashHex ->
                val joinReply = sendAndAwaitReply(
                    engine, fwdsvcHashHex, "/join", 45_000,
                ) { t -> "joined" in t.lowercase() || "already" in t.lowercase() }
                assertTrue(joinReply != null, "no /join reply received")
                println("[case] /join reply: $joinReply")

                delay(500)

                val usersReply = sendAndAwaitReply(
                    engine, fwdsvcHashHex, "/users", 45_000,
                ) { t -> t.startsWith("Users (") || t.startsWith("No users") }
                assertTrue(usersReply != null, "no /users reply received")
                println("[case] /users reply: $usersReply")
                assertTrue(
                    "Users (1)" in usersReply!!,
                    "expected 'Users (1)' header, got: $usersReply",
                )
            }
            harness.disarmLogDump()
        }
    }

    /**
     * `/join` then `/users` against a roster preloaded with 50 fake
     * users (sidecar `link_data_oversize.preload.state.json`). The
     * reply is ~1.4 KB plaintext — far past Reticulum's 500-byte MTU
     * and the 431-byte LinkMDU — so it must ride a SPEC §10 Resource
     * transfer. Two engine bugs this case pinpoints:
     *
     *  1. Link DATA / Resource packets must be HEADER_1 with no
     *     transport_id, even on multi-hop paths (relays don't strip
     *     transport_id from link_table-routed packets, which would
     *     trip the receiver's packet_filter "for other transport
     *     instance" drop).
     *  2. The initiator must send an LRRTT packet to the responder
     *     right after validating the LRPROOF — without it, the
     *     responder's link stays HANDSHAKE forever and
     *     resource_strategy stays ACCEPT_NONE, silently dropping
     *     every ADV.
     */
    @Test fun link_data_oversize_users() = runBlocking {
        val harness = FwdsvcHarness.startOrSkip("link_data_oversize")
        Assume.assumeTrue("fwdsvc/rnsd prerequisites missing — skipping", harness != null)
        harness!!.use {
            driveCase(harness) { engine, fwdsvcHashHex ->
                val joinReply = sendAndAwaitReply(
                    engine, fwdsvcHashHex, "/join", 45_000,
                ) { t -> "joined" in t.lowercase() || "already" in t.lowercase() }
                assertTrue(joinReply != null, "no /join reply received")
                println("[case] /join reply: $joinReply")

                delay(500)

                val usersReply = sendAndAwaitReply(
                    engine, fwdsvcHashHex, "/users", 60_000,
                ) { t -> t.startsWith("Users (") || t.startsWith("No users") }
                assertTrue(usersReply != null, "no /users reply received via Resource transfer")
                println("[case] /users reply (${usersReply!!.length} bytes): ${usersReply.take(200)}…")

                assertTrue(
                    "Users (51)" in usersReply,
                    "expected 'Users (51)' header (50 preload + 1 self), got prefix: ${usersReply.take(100)}",
                )
                val truncationFooter = "...and" in usersReply && "more" in usersReply
                val fullList = (usersReply.split("\n  ").size - 1) >= 51
                assertTrue(
                    truncationFooter || fullList,
                    "neither full list nor truncation footer present in reply",
                )
            }
            harness.disarmLogDump()
        }
    }

    // ---- shared driver ----------------------------------------------------

    /**
     * Boots a [ReticulumEngine] attached to the harness's rnsd via
     * TCP, sends an announce, waits for fwdsvc's announce to land
     * (so our destRepo has its 64-byte public key), then hands the
     * engine + fwdsvc hash to [body]. Cleans up the scope + transport
     * regardless of how [body] exits.
     */
    private suspend fun driveCase(
        harness: FwdsvcHarness,
        body: suspend (engine: ReticulumEngine, fwdsvcHashHex: String) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val destRepo = InMemoryDestRepo()
        // Tee TcpInterface tx-side and engine Log events to stdout so a
        // failing test gives us a full wire-trace post-mortem alongside
        // the harness's rnsd + fwdsvc dumps. Goes to System.out which
        // JUnit captures under <system-out> in the XML report.
        val transport = TcpInterface(harness.rnsdHost, harness.rnsdPort, scope) { line ->
            println("[engine-tx] $line")
        }
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = InMemoryIdentityRepo(),
            destinationRepo = destRepo,
            messageRepo = InMemoryMsgRepo(),
            scope = scope,
            nowMs = { System.currentTimeMillis() },
            displayNameProvider = { "interop-driver" },
        )
        val logSink = scope.launch {
            engine.events.collect { evt ->
                if (evt is ReticulumEngine.EngineEvent.Log) {
                    println("[engine] ${evt.line}")
                }
            }
        }
        try {
            transport.connect()
            withTimeout(15_000) { transport.state.first { it == TransportState.Connected } }
            engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
            engine.ensureIdentity()
            // Tell fwdsvc we exist before we expect a reply. fwdsvc holds
            // off on responding to a command from a destHash it doesn't
            // recognize until our announce arrives.
            engine.sendAnnounce()

            waitForFwdsvcAnnounce(engine, destRepo, harness.fwdsvcDeliveryHashHex, timeoutMs = 60_000)
            // 3s settle to match _common.py — let fwdsvc cache our
            // announce before the first command races its path? path.
            delay(3_000)

            body(engine, harness.fwdsvcDeliveryHashHex)
        } finally {
            logSink.cancel()
            runCatching { engine.detach() }
            runCatching { transport.disconnect() }
            scope.cancel()
        }
    }

    /**
     * Polls [destRepo] until fwdsvc's announce has arrived and brought
     * us its public key. Sends a path? every 4s while waiting — same
     * cadence as `_common.py`'s `wait_for_fwdsvc`. Throws on timeout.
     */
    private suspend fun waitForFwdsvcAnnounce(
        engine: ReticulumEngine,
        destRepo: DestinationRepository,
        fwdsvcHashHex: String,
        timeoutMs: Long,
    ) {
        val hashBytes = fwdsvcHashHex.hexToBytes()
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastReq = 0L
        while (System.currentTimeMillis() < deadline) {
            val row = destRepo.get(fwdsvcHashHex)
            if (row != null && row.publicKey.size == 64) return
            val now = System.currentTimeMillis()
            if (now - lastReq > 4_000) {
                runCatching { engine.requestPath(hashBytes) }
                lastReq = now
            }
            delay(200)
        }
        throw IllegalStateException("fwdsvc announce never arrived within ${timeoutMs}ms")
    }

    /**
     * Calls [ReticulumEngine.sendMessage] with [content] addressed to
     * [destHashHex] and returns the next inbound LXMF whose decoded
     * content matches [matcher] — or null on overall timeout. Uses
     * [ReticulumEngine.events] subscriptions filtered by
     * [ReticulumEngine.EngineEvent.MessageReceived] from [destHashHex].
     */
    private suspend fun sendAndAwaitReply(
        engine: ReticulumEngine,
        destHashHex: String,
        content: String,
        timeoutMs: Long,
        matcher: (String) -> Boolean,
    ): String? {
        val replies = Channel<String>(Channel.UNLIMITED)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = collectorScope.launch {
            engine.events.collect { evt ->
                if (evt is ReticulumEngine.EngineEvent.MessageReceived &&
                    evt.contactHash.equals(destHashHex, ignoreCase = true)
                ) {
                    replies.trySend(evt.content)
                }
            }
        }
        try {
            engine.sendMessage(destinationHash = destHashHex, content = content)
            return withTimeoutOrNull(timeoutMs) {
                var match: String? = null
                while (match == null) {
                    val r = replies.receive()
                    if (matcher(r)) match = r
                }
                match
            }
        } finally {
            job.cancel()
            collectorScope.cancel()
        }
    }
}
