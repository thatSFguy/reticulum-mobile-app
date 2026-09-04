package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.nomad.NOMADNET_MARKUP_GUIDE
import io.github.thatsfguy.reticulum.transport.TcpInterface
import io.github.thatsfguy.reticulum.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeNotNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration test for [ReticulumEngine.fetchNomadPage] against a real
 * NomadNet-style node hosted by Python RNS.
 *
 * Skipped unless these env vars are present (typically set when running
 * `tools/test_nomadnet_node.py` locally — the script prints values to
 * copy):
 *
 *     NOMADNET_NODE_HASH   destination hash hex of the running node
 *     NOMADNET_TCP_HOST    rnsd to attach to (default 127.0.0.1 — the test
 *                          node listens on loopback, #59)
 *     NOMADNET_TCP_PORT    port (default 45420)
 *     NOMADNET_PAGE_PATH   path to fetch (default /page/index.mu)
 *     NOMADNET_PAGE_NEEDLE substring expected to appear in the fetched page
 *
 * Exercises end-to-end against a real network:
 *   - TcpInterface.connect to a real rnsd
 *   - Engine.attach + announce so the rnsd has a return path for us
 *   - Destination repo populated from the node's announce
 *   - LINKREQUEST → LRPROOF handshake
 *   - LinkSession.request with /page/ pathHash
 *   - RESPONSE decode (or Resource reassembly for large pages)
 *
 * If this test passes locally but the app on a phone fails to fetch
 * pages, the bug is platform-specific (BLE vs TCP, mobile NAT,
 * foreground service lifecycle), not in the protocol stack.
 */
class NomadNetLiveTest {

    // v0.1.57 — form-submission round-trip live test.
    //
    // Pre-v0.1.53 fetchNomadPage took `body: ByteArray` and callers
    // pre-msgpack-encoded their dicts → upstream Node.py:109 saw
    // bytes, isinstance(data, dict) was False, env_map stayed empty,
    // every form silently no-op'd. This test would have caught it:
    // upload data = mapOf("field_message" to <value>), assert the
    // server's echo handler sees the value (it sees a dict, returns
    // "got message: <value>"), assert that string lands back in our
    // response.
    @Test fun fetchNomadPageWithFormDataReachesServer() {
        val nodeHashHex = System.getenv("NOMADNET_NODE_HASH")
        assumeNotNull(nodeHashHex)
        val tcpHost = System.getenv("NOMADNET_TCP_HOST") ?: "127.0.0.1"
        val tcpPort = (System.getenv("NOMADNET_TCP_PORT") ?: "45420").toInt()
        val formPath = System.getenv("NOMADNET_FORM_PATH") ?: "/page/echo.mu"
        val field    = System.getenv("NOMADNET_FORM_FIELD") ?: "message"
        val value    = System.getenv("NOMADNET_FORM_VALUE") ?: "hello-world"
        val needle   = System.getenv("NOMADNET_FORM_NEEDLE") ?: "got message: hello-world"

        runBlocking {
            withLiveEngine(tcpHost, tcpPort, nodeHashHex!!) { engine, hashHex ->
                val data = mapOf("field_$field" to value)
                val result = withTimeout(60_000) {
                    engine.fetchNomadPage(hashHex, formPath, data = data)
                }
                assertTrue(
                    result.isSuccess,
                    "form fetchNomadPage failed: ${result.exceptionOrNull()?.message}",
                )
                val body = result.getOrThrow()
                assertTrue(
                    body.contains(needle),
                    "form echo response missing '$needle' — pre-v0.1.53 the dict landed " +
                        "as msgpack bin and env_map was empty.\n--- body ---\n$body\n--- end ---",
                )
            }
        }
    }

    // v0.1.57 — cross-node link follow round-trip.
    //
    // Fetches /page/links.mu (which embeds a `[label`<own_hex>:/page/index.mu]`
    // micron link), parses the body, finds the link, runs it through
    // parseLinkTarget, asserts a CrossNode result, then drives a fresh
    // fetchNomadPage to that hash + path and asserts the index-page
    // needle. Single-node self-link — exercises the parse + dispatch
    // path that v0.1.56 added without needing two NomadNet servers.
    // v1.2.114 — the `*` ("all fields") wildcard, live.
    //
    // Real NomadNet forms rarely name their widgets: comboard's login
    // page submits with `` `[  -> Login  `:/page/comboard/login.mu`action=submit|*] ``.
    // Pre-fix `buildFormSubmitData` had no `*` case, so the POST carried
    // `var_action=submit` and NOTHING the user typed — the page just
    // re-rendered the empty form and login was impossible (reported
    // against 1.2.113: "can't login using reticulum mobile", works in
    // Columba). Self-round-trip tests can't catch this class of bug
    // (playbook §5); only a real spec-compliant server can, which is
    // what this exercises: post deliberately-wrong credentials and
    // assert the server answers with a *credential* verdict rather than
    // handing back the blank login form.
    @Test fun starWildcardSubmitsTypedFieldsLive() {
        val nodeHashHex = System.getenv("NOMADNET_NODE_HASH")
        assumeNotNull(nodeHashHex)
        val tcpHost = System.getenv("NOMADNET_TCP_HOST") ?: "127.0.0.1"
        val tcpPort = (System.getenv("NOMADNET_TCP_PORT") ?: "45420").toInt()
        val loginPath = System.getenv("NOMADNET_LOGIN_PATH") ?: "/page/comboard/login.mu"
        val needle = System.getenv("NOMADNET_LOGIN_NEEDLE") ?: "Invalid"

        // Exactly what MicronView hands the engine for a `*` submit:
        // the link's own `key=value` params plus every widget on the
        // page, built by the shared helper under test.
        val data = io.github.thatsfguy.reticulum.nomad.buildFormSubmitData(
            listOf("action=submit", "*"),
            mapOf(
                "username" to (System.getenv("NOMADNET_LOGIN_USER") ?: "nomadnet-live-test"),
                "password" to (System.getenv("NOMADNET_LOGIN_PASS") ?: "definitely-not-the-password"),
            ),
        )
        assertTrue(
            data.containsKey("field_username") && data.containsKey("field_password"),
            "wildcard did not expand to the page's widgets: $data",
        )

        runBlocking {
            withLiveEngine(tcpHost, tcpPort, nodeHashHex!!) { engine, hashHex ->
                val result = withTimeout(60_000) {
                    engine.fetchNomadPage(hashHex, loginPath, data = data)
                }
                assertTrue(
                    result.isSuccess,
                    "login POST failed: ${result.exceptionOrNull()?.message}",
                )
                val body = result.getOrThrow()
                assertTrue(
                    body.contains(needle),
                    "server did not act on the submitted fields — expected '$needle'\n" +
                        "--- page ---\n$body\n--- end ---",
                )
            }
        }
    }

    @Test fun crossNodeLinkRoundTripsViaParseAndFetch() {
        val nodeHashHex = System.getenv("NOMADNET_NODE_HASH")
        assumeNotNull(nodeHashHex)
        val tcpHost = System.getenv("NOMADNET_TCP_HOST") ?: "127.0.0.1"
        val tcpPort = (System.getenv("NOMADNET_TCP_PORT") ?: "45420").toInt()
        val linksPath = System.getenv("NOMADNET_LINKS_PATH") ?: "/page/links.mu"
        val indexNeedle = System.getenv("NOMADNET_PAGE_NEEDLE") ?: "Hello"

        runBlocking {
            withLiveEngine(tcpHost, tcpPort, nodeHashHex!!) { engine, hashHex ->
                val linksResult = withTimeout(60_000) {
                    engine.fetchNomadPage(hashHex, linksPath)
                }
                assertTrue(linksResult.isSuccess, "links page fetch failed: ${linksResult.exceptionOrNull()?.message}")
                val source = linksResult.getOrThrow()

                val blocks = io.github.thatsfguy.reticulum.nomad.Micron.parse(source)
                val link = blocks.asSequence()
                    .flatMap {
                        when (it) {
                            is io.github.thatsfguy.reticulum.nomad.Block.Heading -> it.text.asSequence()
                            is io.github.thatsfguy.reticulum.nomad.Block.Paragraph -> it.runs.asSequence()
                            else -> emptySequence()
                        }
                    }
                    .filterIsInstance<io.github.thatsfguy.reticulum.nomad.Inline.Link>()
                    .firstOrNull()
                assertNotNull(link, "links page must contain at least one micron link\nsource:\n$source")

                val target = io.github.thatsfguy.reticulum.nomad.parseLinkTarget(link.target)
                val crossNode = target as? io.github.thatsfguy.reticulum.nomad.LinkTarget.CrossNode
                assertNotNull(crossNode, "expected CrossNode parse, got $target (raw: ${link.target})")
                kotlin.test.assertEquals(hashHex.lowercase(), crossNode.destHashHex)

                // Now follow the link — fetch the cross-node target.
                val followed = withTimeout(60_000) {
                    engine.fetchNomadPage(crossNode.destHashHex, crossNode.path)
                }
                assertTrue(followed.isSuccess, "follow fetch failed: ${followed.exceptionOrNull()?.message}")
                assertTrue(
                    followed.getOrThrow().contains(indexNeedle),
                    "followed-link page does not contain '$indexNeedle'",
                )
            }
        }
    }

    @Test fun fetchNomadPageReturnsExpectedContent() {
        val nodeHashHex = System.getenv("NOMADNET_NODE_HASH")
        assumeNotNull(nodeHashHex)
        val tcpHost = System.getenv("NOMADNET_TCP_HOST") ?: "127.0.0.1"
        val tcpPort = (System.getenv("NOMADNET_TCP_PORT") ?: "45420").toInt()
        val pagePath = System.getenv("NOMADNET_PAGE_PATH") ?: "/page/index.mu"
        val needle  = System.getenv("NOMADNET_PAGE_NEEDLE") ?: "Hello"

        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val destRepo = InMemoryDestRepo()
            val msgRepo  = InMemoryMsgRepo()
            val identRepo = InMemoryIdentityRepo()

            val transport = TcpInterface(tcpHost, tcpPort, scope) { }
            val engine = ReticulumEngine(
                crypto = TestVectors.crypto,
                identityRepo = identRepo,
                destinationRepo = destRepo,
                messageRepo = msgRepo,
                scope = scope,
                nowMs = { System.currentTimeMillis() },
                displayNameProvider = { "NomadNet Live Test" },
            )

            try {
                transport.connect()
                withTimeout(15_000) {
                    transport.state.first { it == TransportState.Connected }
                }
                engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
                engine.ensureIdentity()

                // Poll the destination repo for the node's announce. We also
                // request the path periodically to nudge the rnsd if it has
                // a stale or missing entry for the node.
                val seen = withTimeoutOrNull(120_000) {
                    while (true) {
                        val row = destRepo.get(nodeHashHex)
                        if (row != null && row.publicKey.size == 64) return@withTimeoutOrNull row
                        runCatching { engine.requestPath(hexToBytes(nodeHashHex)) }
                        delay(2_000)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                assertNotNull(
                    seen,
                    "Node announce for $nodeHashHex never arrived within 2 min — " +
                        "is the Python node running on $tcpHost:$tcpPort?",
                )

                val result = withTimeout(60_000) {
                    engine.fetchNomadPage(nodeHashHex, pagePath)
                }
                assertTrue(
                    result.isSuccess,
                    "fetchNomadPage returned failure: ${result.exceptionOrNull()?.message}",
                )
                val page = result.getOrThrow()
                assertTrue(
                    page.contains(needle),
                    "fetched page does not contain expected needle '$needle'\n" +
                        "--- page ---\n$page\n--- end ---",
                )
            } finally {
                runCatching { engine.detach() }
                runCatching { transport.disconnect() }
                scope.cancel()
            }
        }
    }

    /**
     * The fetch path, byte for byte, against a page we already hold a
     * golden copy of (#59).
     *
     * The test node serves NomadNet's own Markup guide read straight out of
     * [NOMADNET_MARKUP_GUIDE] — the same document `MicronGuideConformanceTest`
     * parses — so the parse-level golden and the on-device render cannot
     * drift apart. At ~20 KB the guide also arrives as a multi-part Resource
     * rather than a single RESPONSE packet, so an equality assertion here
     * covers reassembly, ordering and the metadata prefix as well: a
     * truncated or reordered transfer changes the string, and a needle
     * search would not have noticed.
     */
    @Test fun guidePageArrivesByteForByte() {
        val nodeHashHex = System.getenv("NOMADNET_NODE_HASH")
        assumeNotNull(nodeHashHex)
        val tcpHost = System.getenv("NOMADNET_TCP_HOST") ?: "127.0.0.1"
        val tcpPort = (System.getenv("NOMADNET_TCP_PORT") ?: "45420").toInt()
        val guidePath = System.getenv("NOMADNET_GUIDE_PATH") ?: "/page/guide.mu"

        runBlocking {
            withLiveEngine(tcpHost, tcpPort, nodeHashHex!!) { engine, hashHex ->
                val result = withTimeout(120_000) {
                    engine.fetchNomadPage(hashHex, guidePath)
                }
                assertTrue(
                    result.isSuccess,
                    "guide fetch failed: ${result.exceptionOrNull()?.message}",
                )
                val fetched = result.getOrThrow()
                kotlin.test.assertEquals(
                    NOMADNET_MARKUP_GUIDE.length,
                    fetched.length,
                    "guide came back a different length — transfer truncated, or the " +
                        "node is serving a different copy than the vendored fixture",
                )
                kotlin.test.assertEquals(
                    NOMADNET_MARKUP_GUIDE,
                    fetched,
                    "guide bytes differ from the vendored fixture",
                )
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.lowercase()
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Common rig: connect over TCP, wait for the test node's announce
     * to populate the destination repo, then run [block] with the
     * attached engine + the node's hash. Tears down on exit.
     */
    private suspend fun withLiveEngine(
        tcpHost: String,
        tcpPort: Int,
        nodeHashHex: String,
        block: suspend (ReticulumEngine, String) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val destRepo = InMemoryDestRepo()
        val msgRepo  = InMemoryMsgRepo()
        val identRepo = InMemoryIdentityRepo()
        val transport = TcpInterface(tcpHost, tcpPort, scope) { }
        val engine = ReticulumEngine(
            crypto = TestVectors.crypto,
            identityRepo = identRepo,
            destinationRepo = destRepo,
            messageRepo = msgRepo,
            scope = scope,
            nowMs = { System.currentTimeMillis() },
            displayNameProvider = { "NomadNet Live Test" },
        )
        try {
            transport.connect()
            withTimeout(15_000) { transport.state.first { it == TransportState.Connected } }
            engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
            engine.ensureIdentity()

            val seen = withTimeoutOrNull(120_000) {
                while (true) {
                    val row = destRepo.get(nodeHashHex)
                    if (row != null && row.publicKey.size == 64) return@withTimeoutOrNull row
                    runCatching { engine.requestPath(hexToBytes(nodeHashHex)) }
                    delay(2_000)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            assertNotNull(
                seen,
                "Node announce for $nodeHashHex never arrived within 2 min — " +
                    "is the Python node running on $tcpHost:$tcpPort?",
            )

            block(engine, nodeHashHex)
        } finally {
            runCatching { engine.detach() }
            runCatching { transport.disconnect() }
            scope.cancel()
        }
    }
}
