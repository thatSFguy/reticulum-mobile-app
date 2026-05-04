package io.github.thatsfguy.reticulum.android.ui.graph

import io.github.thatsfguy.reticulum.store.StoredDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Topology tests for GraphScreen's relay-aware view. These cover the
 * data shape (nodes, edges, roles) — colors and radii are presentation
 * and live in GraphScreen.kt.
 */
class GraphTopologyTest {

    @Test fun `empty destinations gives just the me node and no edges`() {
        val t = buildTopology(destinations = emptyList(), ourHash = ME_HASH)
        assertEquals(1, t.nodes.size)
        assertEquals(NodeRole.ME, t.nodes.single().role)
        assertEquals(ME_ID, t.nodes.single().id)
        assertTrue(t.edges.isEmpty())
    }

    @Test fun `direct destination edges straight to me`() {
        val bob = dest("bobhash", hops = 1, nextHop = null, name = "Bob")
        val t = buildTopology(listOf(bob), ourHash = ME_HASH)
        assertEquals(2, t.nodes.size)
        val leaf = t.nodes.first { it.role == NodeRole.LEAF }
        assertEquals("bobhash", leaf.id)
        assertEquals(1, t.edges.size)
        val edge = t.edges.single()
        assertEquals(ME_ID, edge.from)
        assertEquals("bobhash", edge.to)
    }

    @Test fun `multi-hop destination with known nextHop routes via a relay`() {
        val transitId = ByteArray(16) { 0x42 }
        val bob = dest("bobhash", hops = 3, nextHop = transitId, name = "Bob via R1")
        val t = buildTopology(listOf(bob), ourHash = ME_HASH)

        // me + relay + leaf = 3 nodes
        assertEquals(3, t.nodes.size)
        val relay = t.nodes.firstOrNull { it.role == NodeRole.RELAY }
        assertNotNull(relay, "expected a synthetic RELAY node for the unknown relay")
        assertTrue(relay.id.startsWith(RELAY_PREFIX), "synthetic relay id must use the relay: prefix")

        // me → relay and relay → leaf, no direct me → leaf edge
        assertEquals(2, t.edges.size)
        val meRelay = t.edges.first { it.from == ME_ID }
        assertEquals(relay.id, meRelay.to)
        val relayLeaf = t.edges.first { it.from == relay.id }
        assertEquals("bobhash", relayLeaf.to)
        assertEquals(3, relayLeaf.hopLabel, "hop count carried on the relay→leaf edge")
    }

    @Test fun `multi-hop destination with unknown nextHop falls back to me direct`() {
        val bob = dest("bobhash", hops = 3, nextHop = null, name = "Bob (no path)")
        val t = buildTopology(listOf(bob), ourHash = ME_HASH)
        assertEquals(2, t.nodes.size, "no relay synthesized when nextHop is missing")
        assertEquals(NodeRole.LEAF, t.nodes.first { it.id == "bobhash" }.role)
        val edge = t.edges.single()
        assertEquals(ME_ID, edge.from)
        assertEquals("bobhash", edge.to)
    }

    @Test fun `destinations sharing the same nextHop cluster under a single relay`() {
        val transitId = ByteArray(16) { 0x42 }
        val bob   = dest("bobhash",   hops = 2, nextHop = transitId, name = "Bob")
        val carol = dest("carolhash", hops = 2, nextHop = transitId, name = "Carol")
        val dave  = dest("davehash",  hops = 2, nextHop = transitId, name = "Dave")
        val t = buildTopology(listOf(bob, carol, dave), ourHash = ME_HASH)

        val relays = t.nodes.filter { it.role == NodeRole.RELAY }
        assertEquals(1, relays.size, "all three leaves must share one relay node")
        val relayId = relays.single().id

        // 1 me→relay edge + 3 relay→leaf edges = 4 total
        assertEquals(4, t.edges.size)
        val relayOut = t.edges.filter { it.from == relayId }.map { it.to }.toSet()
        assertEquals(setOf("bobhash", "carolhash", "davehash"), relayOut)
    }

    @Test fun `relay whose own announce is known uses the announced display name`() {
        // The relay (transport_id) matches a destination we've already
        // received an announce from — so we know its display name.
        val r1Hash = "aabbccddeeff00112233445566778899"
        val r1Bytes = r1Hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val r1   = dest(r1Hash, hops = 1, nextHop = null, name = "R1 Transport", appName = "rnstransport.broadcasts")
        val bob  = dest("bobhash", hops = 2, nextHop = r1Bytes, name = "Bob")
        val t = buildTopology(listOf(r1, bob), ourHash = ME_HASH)

        // R1 must appear exactly once — as a RELAY, NOT a separate LEAF.
        val r1Nodes = t.nodes.filter { it.id == r1Hash }
        assertEquals(1, r1Nodes.size, "R1 should appear once with the relay role winning")
        assertEquals(NodeRole.RELAY, r1Nodes.single().role)
        assertEquals("R1 Transport", r1Nodes.single().displayName)

        // No me→leaf edge to R1 (it's a relay) — only me→relay + relay→bob.
        assertEquals(2, t.edges.size)
        val meEdge = t.edges.first { it.from == ME_ID }
        assertEquals(r1Hash, meEdge.to)
        val relayEdge = t.edges.first { it.from == r1Hash }
        assertEquals("bobhash", relayEdge.to)
    }

    @Test fun `the our-hash destination is excluded from leaves`() {
        val us = dest(ME_HASH, hops = 0, nextHop = null, name = "us")
        val bob = dest("bobhash", hops = 1, nextHop = null, name = "Bob")
        val t = buildTopology(listOf(us, bob), ourHash = ME_HASH)
        assertEquals(2, t.nodes.size, "expected only ME + Bob; us-as-dest must be filtered out")
        assertNull(t.nodes.firstOrNull { it.id == ME_HASH && it.role == NodeRole.LEAF })
    }

    @Test fun `direct destinations carry hopLabel of 1`() {
        val bob = dest("bobhash", hops = 1, nextHop = null, name = "Bob")
        val t = buildTopology(listOf(bob), ourHash = ME_HASH)
        assertEquals(1, t.edges.single().hopLabel)
    }

    @Test fun `maxLeaves caps the leaf count`() {
        val many = (1..100).map { dest("hash$it", hops = 1, nextHop = null, name = "n$it") }
        val t = buildTopology(many, ourHash = ME_HASH, maxLeaves = 10)
        // ME + 10 leaves
        assertEquals(11, t.nodes.size)
    }

    private companion object {
        private const val ME_HASH = "0000000000000000000000000000beef"
    }

    private fun dest(
        hash: String,
        hops: Int,
        nextHop: ByteArray?,
        name: String,
        appName: String? = "lxmf.delivery",
    ): StoredDestination = StoredDestination(
        hash = hash,
        identityHash = "",
        publicKey = ByteArray(0),
        destHash = ByteArray(16),
        nameHash = ByteArray(0),
        ratchetPub = null,
        displayName = name,
        appName = appName,
        appLabel = null,
        telemetry = null,
        lat = null, lon = null,
        appDataHex = "",
        lastSeen = 0,
        rssi = null,
        favorite = false,
        source = "test",
        hopCount = hops,
        nextHop = nextHop,
    )
}
