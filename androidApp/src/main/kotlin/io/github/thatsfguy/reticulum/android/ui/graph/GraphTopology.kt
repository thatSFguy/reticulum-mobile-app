package io.github.thatsfguy.reticulum.android.ui.graph

import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.transport.toHex

/**
 * Pure topology layer for the GraphScreen — no Compose / Color / Canvas
 * dependencies, so it's testable without Robolectric.
 *
 * Promotes each unique `nextHop` (the first-hop transport_id captured from
 * inbound HEADER_2 announces, persisted on [StoredDestination] as of v0.1.40)
 * to its own synthetic relay node. Routes leaves through their relay so the
 * graph reflects "you reach Bob via R1" rather than the previous flat star
 * where every destination edged directly to "me".
 *
 * What we model:
 *   - One [TopologyNode] per unique destination (role = LEAF or RELAY,
 *     never both — a relay slot wins if any other dest cites it).
 *   - "me" pinned at the centre with role = ME.
 *   - Edges follow the shortest cached path:
 *       hopCount == 1                            → me → leaf
 *       hopCount > 1 && nextHop != null          → me → relay → leaf
 *       hopCount > 1 && nextHop == null          → me → leaf (degraded fallback)
 *
 * What we DO NOT model:
 *   - Hops past the first relay. RNS only exposes the first transport_id to a
 *     leaf, so any 3+ hop path is shown as a single relay edge with a hop
 *     count label — the intermediate links are unknown and we don't pretend
 *     to know them. A future recursive heuristic could chain known relay
 *     announces, but that's deliberately out of scope here.
 */

internal enum class NodeRole { ME, RELAY, LEAF }

internal data class TopologyNode(
    val id: String,                       // dest_hash hex, "me", or "relay:<hex>" for unannounced relays
    val role: NodeRole,
    val displayName: String,              // already-resolved label for rendering
    val hopCount: Int,                    // 0 for ME and RELAY (RELAY's own hop count is unknown)
    val sourceDest: StoredDestination?,   // null for ME and synthetic relays we have no announce for
    val isFavorite: Boolean = false,
    val appName: String? = null,
)

internal data class TopologyEdge(
    val from: String,
    val to: String,
    val hopLabel: Int? = null,            // shown on me→leaf direct edges (1) or me→relay edges (relay's own dest hopCount)
)

internal data class Topology(
    val nodes: List<TopologyNode>,
    val edges: List<TopologyEdge>,
)

/**
 * Build the relay-aware topology from the destination list.
 *
 * Cap input at [maxLeaves] so very busy meshes don't make the screen
 * unreadable. Relays are not subject to the cap (small fixed set in
 * practice).
 */
internal fun buildTopology(
    destinations: List<StoredDestination>,
    ourHash: String?,
    maxLeaves: Int = 60,
): Topology {
    val nodes = mutableListOf<TopologyNode>()
    val edges = mutableListOf<TopologyEdge>()

    if (ourHash != null) {
        nodes += TopologyNode(
            id = ME_ID, role = NodeRole.ME, displayName = "me",
            hopCount = 0, sourceDest = null,
        )
    }

    // Index existing announces by dest_hash hex so we can resolve a relay
    // node's display name when its transport_id matches a destination we've
    // already seen (e.g. a transport node that also announces itself).
    val byHash = destinations.associateBy { it.hash.lowercase() }

    // Take the leaves we'll render. Filter "us" so we don't double-render.
    val leaves = destinations
        .asSequence()
        .filter { it.hash != ourHash }
        .take(maxLeaves)
        .toList()

    // First pass: discover relays. A unique nextHop hex used by any
    // multi-hop leaf becomes a relay node. We assign a stable id either
    // to the matching announced destination (if present in byHash) or to
    // a synthetic "relay:<hex>" id for unannounced relays.
    val relayIds = LinkedHashMap<String, String>()  // nextHopHex → nodeId
    for (d in leaves) {
        val nh = d.nextHop ?: continue
        if (d.hopCount <= 1) continue
        val nhHex = nh.toHex()
        if (relayIds.containsKey(nhHex)) continue
        val matchingDest = byHash[nhHex]
        val nodeId = matchingDest?.hash ?: "$RELAY_PREFIX$nhHex"
        relayIds[nhHex] = nodeId
        nodes += TopologyNode(
            id = nodeId,
            role = NodeRole.RELAY,
            displayName = matchingDest?.displayName?.takeIf { it.isNotBlank() }
                ?: "relay ${nhHex.take(6)}",
            hopCount = matchingDest?.hopCount ?: 0,
            sourceDest = matchingDest,
            isFavorite = matchingDest?.favorite ?: false,
            appName = matchingDest?.appName,
        )
    }

    // Second pass: emit leaf nodes + edges. A destination that was already
    // promoted to a relay in pass 1 doesn't get a second LEAF node — its
    // relay role wins to keep the graph from showing two glyphs for the
    // same dest_hash.
    val emittedRelayDestHashes = relayIds.values.filter { !it.startsWith(RELAY_PREFIX) }.toSet()
    for (d in leaves) {
        if (d.hash in emittedRelayDestHashes) continue
        nodes += TopologyNode(
            id = d.hash,
            role = NodeRole.LEAF,
            displayName = d.displayName.ifBlank { d.hash.take(6) },
            hopCount = d.hopCount,
            sourceDest = d,
            isFavorite = d.favorite,
            appName = d.appName,
        )
    }

    // Third pass: edges. me → relay (one per relay), relay → leaf (each
    // multi-hop leaf to its relay), me → leaf direct (single-hop leaves and
    // the degraded fallback when nextHop is missing).
    if (ourHash != null) {
        for (relayNodeId in relayIds.values) {
            val relayNode = nodes.firstOrNull { it.id == relayNodeId } ?: continue
            edges += TopologyEdge(from = ME_ID, to = relayNodeId, hopLabel = relayNode.hopCount.takeIf { it > 0 })
        }
        for (d in leaves) {
            if (d.hash in emittedRelayDestHashes) continue
            val nhHex = d.nextHop?.toHex()
            val relayNodeId = if (d.hopCount > 1 && nhHex != null) relayIds[nhHex] else null
            if (relayNodeId != null) {
                edges += TopologyEdge(from = relayNodeId, to = d.hash, hopLabel = d.hopCount)
            } else {
                edges += TopologyEdge(from = ME_ID, to = d.hash, hopLabel = d.hopCount.takeIf { it > 0 })
            }
        }
    }

    return Topology(nodes = nodes, edges = edges)
}

internal const val ME_ID = "me"
internal const val RELAY_PREFIX = "relay:"
