package io.github.thatsfguy.reticulum.android.storage

import io.github.thatsfguy.reticulum.transport.KnownTcpNodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Lock the default-rotation list shape so future edits notice when a
 * load-bearing entry gets dropped or a port is mistyped. Tests the
 * post-Columba load-distribution rotation introduced 2026-05-07.
 */
class KnownTcpNodesTest {

    @Test fun listIsNonEmpty() {
        assertTrue(KnownTcpNodes.DEFAULTS.isNotEmpty(), "default rotation must not be empty")
    }

    @Test fun allEntriesAreWellFormed() {
        for ((host, port) in KnownTcpNodes.DEFAULTS) {
            assertTrue(host.isNotBlank(), "host must not be blank: '$host'")
            assertTrue(port in 1..65535, "port must be valid: $host:$port")
            // Defensive: prevent raw IPs sneaking in. IP-only entries
            // bit-rot when the operator migrates; DNS-named entries
            // can be redirected by the operator. The probe sweep on
            // 2026-05-07 deliberately excluded raw-IP candidates.
            assertTrue(
                host.any { it.isLetter() },
                "raw-IP defaults bit-rot — use DNS names: '$host'",
            )
        }
    }

    @Test fun includesMichMeshOriginOperator() {
        // The MichMesh operator did the homework on us before
        // (helping us reproduce HEADER_2 routing bugs against their
        // node). Including their node in the rotation is an explicit
        // commitment to that operator — DON'T drop them just because
        // the rotation grew. They go from being 1-of-1 default to
        // 1-of-N, which is the actual load-balancing fix.
        val hosts = KnownTcpNodes.DEFAULTS.map { it.first.lowercase() }
        assertContains(hosts, "rns.michmesh.net")
    }

    @Test fun pickRandomReturnsListEntry() {
        // Repeat enough times to make a "lucky pick that happened to
        // be in the list anyway" extremely unlikely if pickRandom
        // were broken (e.g. returning hardcoded value).
        repeat(50) {
            val pick = KnownTcpNodes.pickRandom()
            assertContains(KnownTcpNodes.DEFAULTS, pick)
        }
    }
}
