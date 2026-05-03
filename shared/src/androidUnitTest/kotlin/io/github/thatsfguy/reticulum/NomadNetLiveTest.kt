package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.engine.ReticulumEngine
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
 *     NOMADNET_TCP_HOST    rnsd to attach to (default rns.chicagonomad.net)
 *     NOMADNET_TCP_PORT    port (default 4242)
 *     NOMADNET_PAGE_PATH   path to fetch (default :/page/index.mu)
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

    @Test fun fetchNomadPageReturnsExpectedContent() {
        val nodeHashHex = System.getenv("NOMADNET_NODE_HASH")
        assumeNotNull(nodeHashHex)
        val tcpHost = System.getenv("NOMADNET_TCP_HOST") ?: "rns.chicagonomad.net"
        val tcpPort = (System.getenv("NOMADNET_TCP_PORT") ?: "4242").toInt()
        val pagePath = System.getenv("NOMADNET_PAGE_PATH") ?: ":/page/index.mu"
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

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.lowercase()
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
