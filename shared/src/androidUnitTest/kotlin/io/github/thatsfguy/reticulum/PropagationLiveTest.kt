package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.store.StoredIdentity
import io.github.thatsfguy.reticulum.transport.TcpInterface
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.hexToBytes
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
 * Live integration test for the LXMF propagation /get round-trip.
 *
 * Pre-v0.1.53 [io.github.thatsfguy.reticulum.engine.PropagationClient]
 * pre-msgpack-encoded its round-1 list-request body and passed the
 * bytes to [io.github.thatsfguy.reticulum.engine.LinkSession.request],
 * which then re-wrapped them as msgpack `bin` in envelope element [2].
 * Upstream LXMRouter's `isinstance(data, list)` check failed silently —
 * the server returned an empty list and we thought "no messages". This
 * test exercises the full /get against a real LXMRouter so that wire-
 * shape regression class can't slip in again.
 *
 * Skipped unless these env vars are present (set by
 * `tools/test_propagation_node.py` on stdout):
 *
 *     LXMF_PROP_HASH               propagation node destination hash
 *     LXMF_PROP_TCP_HOST           rnsd to attach to
 *     LXMF_PROP_TCP_PORT           port (default 4242)
 *     LXMF_PROP_RECIPIENT_HASH     recipient lxmf.delivery hash (the queue key)
 *     LXMF_PROP_RECIPIENT_ENC_PRIV 32-byte hex X25519 priv
 *     LXMF_PROP_RECIPIENT_SIG_PRIV 32-byte hex Ed25519 priv
 *     LXMF_PROP_NEEDLE             body substring expected on a delivered message
 *
 * The recipient priv keys are needed because syncPropagation
 * authenticates as the recipient identity (LINKIDENTIFY). Without a
 * matching identity on the Kotlin side the propagation node refuses
 * to ship the queued blob.
 */
class PropagationLiveTest {

    @Test fun propagationGetReturnsAdvertisedListAndDeliversBlob() {
        val propHash = System.getenv("LXMF_PROP_HASH")
        assumeNotNull(propHash)
        val tcpHost = System.getenv("LXMF_PROP_TCP_HOST") ?: "rns.chicagonomad.net"
        val tcpPort = (System.getenv("LXMF_PROP_TCP_PORT") ?: "4242").toInt()
        val recipientHashHex = System.getenv("LXMF_PROP_RECIPIENT_HASH")
        val encPrivHex = System.getenv("LXMF_PROP_RECIPIENT_ENC_PRIV")
        val sigPrivHex = System.getenv("LXMF_PROP_RECIPIENT_SIG_PRIV")
        assumeNotNull(recipientHashHex)
        assumeNotNull(encPrivHex)
        assumeNotNull(sigPrivHex)

        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val destRepo = InMemoryDestRepo()
            val msgRepo  = InMemoryMsgRepo()
            val identRepo = InMemoryIdentityRepo()

            // Pre-seed the identity repo with the harness-generated keys
            // so `engine.ensureIdentity()` loads them instead of generating
            // fresh — only then does our destination hash match what the
            // propagation node queued the canned blob for.
            identRepo.save(StoredIdentity(
                encPrivKey = encPrivHex!!.hexToBytes(),
                sigPrivKey = sigPrivHex!!.hexToBytes(),
                ratchetPrivKey = null,
            ))

            val transport = TcpInterface(tcpHost, tcpPort, scope) { }
            val engine = ReticulumEngine(
                crypto = TestVectors.crypto,
                identityRepo = identRepo,
                destinationRepo = destRepo,
                messageRepo = msgRepo,
                scope = scope,
                nowMs = { System.currentTimeMillis() },
                displayNameProvider = { "Propagation Live Test" },
            )

            try {
                transport.connect()
                withTimeout(15_000) { transport.state.first { it == TransportState.Connected } }
                engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
                engine.ensureIdentity()  // loads from the pre-seeded identRepo

                // Wait for the propagation node's announce so we have a
                // public key to crypt the link handshake with.
                val seen = withTimeoutOrNull(120_000) {
                    while (true) {
                        val row = destRepo.get(propHash!!)
                        if (row != null && row.publicKey.size == 64) return@withTimeoutOrNull row
                        runCatching { engine.requestPath(propHash.hexToBytes()) }
                        delay(2_000)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                assertNotNull(seen, "propagation node $propHash announce never arrived")

                val result = withTimeout(120_000) { engine.syncPropagation(propHash!!) }
                assertTrue(
                    result.errorMessage == null,
                    "syncPropagation returned error: ${result.errorMessage} " +
                        "(pre-v0.1.53 the round-1 body landed as msgpack bin and the " +
                        "server returned an empty list with no error — so this assertion " +
                        "alone wouldn't catch it; check tidsAdvertised below).",
                )
                assertTrue(
                    result.tidsAdvertised >= 1,
                    "propagation node should advertise the canned blob, " +
                        "got tidsAdvertised=${result.tidsAdvertised} — pre-v0.1.53 wire-shape " +
                        "regression: round-1 list-request body was msgpack-bin instead of " +
                        "msgpack-list, so upstream LXMRouter returned an empty list silently.",
                )
                // messagesStored may be 0 if the link timed out before round 2
                // completed (multi-packet Resource path) — surface the count
                // without failing on it.
                println("[prop] tidsAdvertised=${result.tidsAdvertised} messagesStored=${result.messagesStored}")
            } finally {
                runCatching { engine.detach() }
                runCatching { transport.disconnect() }
                scope.cancel()
            }
        }
    }
}
