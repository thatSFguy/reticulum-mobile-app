package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.link.computeLinkId
import io.github.thatsfguy.reticulum.link.computePacketFullHash
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.DEST_PLAIN
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.parsePacket
import kotlin.concurrent.Volatile

/**
 * Identity-addressed routing for the agnostic-LoRa-Net tunnel
 * (SPEC: `agnostic-lora-net/docs/distributed-lookup-plan.md`,
 * `mobile-app-testing.md` §0.4/§0.5 *identity* mode; firmware contract
 * confirmed 2026-06-10 over the agent bridge).
 *
 * The mesh's distributed directory maps an opaque id (we use our 16-byte
 * RNS destination hash, 32 hex chars) to the node currently serving it.
 * This router holds the client-side state of that scheme:
 *
 *   - **bindings** — id → node, learned from `dirdump` enumerations and
 *     `loc` resolve answers (text lines), and passively from inbound
 *     announces (src node of the frame that carried them).
 *   - **link routes** — link_id → node, learned when a LINKREQUEST passes
 *     through in either direction, so established-link traffic (whose
 *     dest is the link_id, not a directory id) routes to the right node.
 *   - **reverse routes** — truncated-packet-hash → origin node, pinned on
 *     every inbound DATA packet. Opportunistic delivery proofs are
 *     addressed to the proved packet's truncated hash (not a directory
 *     id); upstream routes them via `Transport.reverse_table`, and this
 *     map plays that role for the tunnel. Without it proofs fell through
 *     to the fallback — on a phone whose stale fallback named its own
 *     node, straight into the RF echo black hole (BR-5).
 *   - **attached node** — the node we are BLE-attached to, learned from
 *     the `registered <n>-byte id at <NODE>` ack (and heartbeats). A
 *     frame addressed to it never goes RF — pre-fw-0.4.5 it died in the
 *     echo filter, 0.4.5+ loops it back to us — so it is excluded from
 *     every routing decision, and inbound frames carrying it as source
 *     are loopbacks of our own mistakes, never learned from (BR-5).
 *   - **pending** — outbound packets buffered (bounded) until their
 *     destination resolves. THE rule from the desktop bring-up: never
 *     drop while unresolved, flush on resolve (§0.4).
 *   - **cached self-announce** — our latest announce, re-unicast to every
 *     newly discovered peer node so they learn our path promptly instead
 *     of waiting for the next periodic re-announce.
 *
 * Announces have no single recipient, so they fan out: one unicast per
 * known peer node (per firmware guidance — no tunnel flood exists; a
 * 221B announce is ~2-3s of airtime per hop at this PHY).
 *
 * Pure protocol state — no platform APIs, no clock (callers pass
 * `nowMs`), so it is unit-testable and shared with the future iOS
 * transport. The BLE transport owns the I/O: it feeds text lines and
 * inbound frames in, and executes the returned routing decisions.
 */
class AgnosticLoraRouter(
    selfIdHex: String,
    fallbackUplinkHex: String?,
    private val crypto: CryptoProvider,
) {
    /** Our directory id: the 16-byte RNS destination hash, upper-hex. */
    val selfIdHex: String = selfIdHex.uppercase()

    /** Optional static gateway node — routes anything the directory
     *  can't. Kept for attaching to an RNS-bridge node; NOT auto-filled
     *  from the attached node any more (that was the §0.5 trap). */
    val fallbackUplinkHex: String? =
        fallbackUplinkHex?.trim()?.uppercase()?.ifEmpty { null }

    private class Binding(var nodeHex: String, var lastSeenMs: Long)
    private class ReverseRoute(val nodeHex: String, val seenMs: Long)

    /** The node this client is BLE-attached to (8 hex), once learned.
     *  Volatile: written under the owner's lock, read racily by the
     *  transport's inbound path for the loopback drop. */
    @Volatile
    var attachedNodeHex: String? = null
        private set

    private val bindings = HashMap<String, Binding>()       // idHex -> binding
    private val linkRoutes = HashMap<String, String>()      // linkIdHex -> nodeHex
    private val reverseRoutes = LinkedHashMap<String, ReverseRoute>() // truncHashHex -> origin
    private val pending = ArrayDeque<ByteArray>()
    private var cachedSelfAnnounce: ByteArray? = null
    private val announcedTo = HashSet<String>()             // nodes that got the cached announce

    sealed class RouteDecision {
        /** Write the packet to each of these node ids (wire-form hex). */
        data class Send(val targets: List<String>) : RouteDecision()
        /** Held until the destination resolves; flushed by [drainRoutable]. */
        object Buffered : RouteDecision()
        /** Nothing to do now (e.g. announce with no peers known yet —
         *  it is cached and will be unicast on first discovery). */
        data class Deferred(val reason: String) : RouteDecision()
    }

    /** A parsed directory text line, for the transport's logging. */
    data class DirectoryEvent(
        val summary: String,
        /** Peer nodes first seen by this event — each should receive our
         *  cached announce (see [cachedAnnounceFor]). */
        val newPeerNodes: List<String>,
        /** True when bindings changed and [drainRoutable] is worth calling. */
        val routesChanged: Boolean,
    )

    /**
     * Decide where [raw] goes. May mutate state: caches self-announces,
     * buffers unroutable packets, and records LINKREQUEST link-ids
     * against their target so later link traffic follows them.
     */
    suspend fun routeOutbound(raw: ByteArray, nowMs: Long): RouteDecision {
        prune(nowMs)
        val packet = parsePacket(raw)
            ?: return RouteDecision.Deferred("unparseable packet (${raw.size}B)")

        if (packet.packetType == PACKET_ANNOUNCE) {
            if (packet.destHash.toHexUpper() == selfIdHex) {
                cachedSelfAnnounce = raw
                announcedTo.clear() // fresh announce supersedes; re-send to all
            }
            val targets = fanoutTargets()
            if (targets.isEmpty()) return RouteDecision.Deferred("no peers known yet")
            announcedTo.addAll(targets)
            return RouteDecision.Send(targets)
        }

        // PLAIN destinations are broadcast-ish (path requests etc.) —
        // their dest hash is never a directory id, so buffering would
        // queue them forever and spam useless resolves. Fan out like an
        // announce, or drop with a note when nobody is known yet.
        if (packet.destType == DEST_PLAIN) {
            val targets = fanoutTargets()
            if (targets.isEmpty()) return RouteDecision.Deferred("broadcast with no peers known")
            return RouteDecision.Send(targets)
        }

        val node = resolveNodeFor(packet)
        if (node == null) {
            // A delivery proof's dest is the proved packet's truncated
            // hash — never a directory id, only routable via the reverse
            // table. With no route left, buffering would just spam
            // resolves; drop instead — the peer's retransmit re-pins the
            // route and triggers a fresh proof.
            if (packet.packetType == PACKET_PROOF && packet.destType != DEST_LINK) {
                return RouteDecision.Deferred("proof origin unknown (no reverse route) — peer retry re-pins")
            }
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(raw)
            return RouteDecision.Buffered
        }
        recordLinkRequest(packet.raw, node)
        return RouteDecision.Send(listOf(node))
    }

    /** Route lookup for a single-recipient packet: link table for link
     *  dests, bindings then reverse table otherwise, fallback last. The
     *  attached node is never a valid answer (BR-5: a frame to it loops
     *  back to us instead of going RF). */
    private fun resolveNodeFor(packet: io.github.thatsfguy.reticulum.protocol.Packet): String? {
        val destHex = packet.destHash.toHexUpper()
        val learned = if (packet.destType == DEST_LINK) {
            linkRoutes[destHex]
        } else {
            bindings[destHex]?.nodeHex ?: reverseRoutes[destHex]?.nodeHex
        }
        return learned?.takeIf { it != attachedNodeHex } ?: usableFallback()
    }

    private fun usableFallback(): String? =
        fallbackUplinkHex?.takeIf { it != attachedNodeHex }

    /**
     * Learn from an inbound packet delivered by [srcNodeHex]:
     * an announce binds its dest hash to the delivering node (free
     * reverse-path knowledge, ahead of the next dirdump), and a
     * LINKREQUEST pins its link_id there so our LRPROOF and the link
     * traffic that follows route back.
     */
    suspend fun onInbound(srcNodeHex: String, raw: ByteArray, nowMs: Long): DirectoryEvent? {
        val packet = parsePacket(raw) ?: return null
        val src = srcNodeHex.uppercase()
        // A frame "from" our own node is a loopback of something we
        // misaddressed (fw 0.4.5 echoes self-addressed frames back).
        // Learning from it would poison the tables — e.g. our own
        // looped-back LINKREQ re-pinning its link to our own node (BR-5).
        if (src == attachedNodeHex) return null
        when (packet.packetType) {
            PACKET_ANNOUNCE -> {
                val id = packet.destHash.toHexUpper()
                if (id != selfIdHex) return upsert(id, src, nowMs, origin = "announce")
            }
            PACKET_LINKREQ -> {
                val linkId = computeLinkId(packet, crypto).toHexUpper()
                linkRoutes[linkId] = src
                // Link pins are route changes too: anything buffered for
                // this link_id (e.g. an LRPROOF that raced the pin) must
                // flush now — nothing else will ever resolve a link dest.
                return DirectoryEvent("", emptyList(), routesChanged = true)
            }
            PACKET_DATA -> {
                // Reverse table: this packet's delivery proof will be
                // addressed to its truncated hash — pin the origin node
                // now so the proof routes back (upstream's reverse_table).
                val trunc = computePacketFullHash(packet, crypto)
                    .copyOfRange(0, 16).toHexUpper()
                reverseRoutes.remove(trunc)
                reverseRoutes[trunc] = ReverseRoute(src, nowMs)
                while (reverseRoutes.size > MAX_REVERSE_ROUTES) {
                    reverseRoutes.remove(reverseRoutes.keys.first())
                }
            }
        }
        return null
    }

    /**
     * Parse a console [line] from the node. Recognized:
     *   `loc <idhex> <node8hex>`                  — resolve answer
     *   `<idhex> -> <NODE8HEX>  ttl=<S>s`         — dirdump binding row
     *   `[dir] <N> binding(s):` / `registered …`  — logged, no state
     */
    fun onTextLine(line: String, nowMs: Long): DirectoryEvent? {
        val trimmed = line.trim()
        LOC_RE.matchEntire(trimmed)?.let { m ->
            return upsert(m.groupValues[1].uppercase(), m.groupValues[2].uppercase(), nowMs, "loc")
        }
        BINDING_RE.matchEntire(trimmed)?.let { m ->
            return upsert(m.groupValues[1].uppercase(), m.groupValues[2].uppercase(), nowMs, "dirdump")
        }
        if (trimmed.startsWith("registered", ignoreCase = true)) {
            // `registered <n>-byte id at <NODE>` — the authoritative
            // source for which node we are attached to (we always
            // register on connect, so this arrives once per session).
            val note = REGISTERED_RE.find(trimmed)
                ?.let { learnAttachedNode(it.groupValues[1].uppercase()) }
                .orEmpty()
            return DirectoryEvent("register ack: $trimmed$note", emptyList(), routesChanged = false)
        }
        if (trimmed.startsWith("[hb]")) {
            HB_NODE_RE.find(trimmed)?.let { learnAttachedNode(it.groupValues[1].uppercase()) }
            return null // heartbeats stay silent
        }
        return null
    }

    /** Record which node we are attached to and scrub it from every
     *  table — frames addressed to it loop back to us instead of going
     *  RF (BR-5). Returns a log note for the first learn, "" otherwise. */
    private fun learnAttachedNode(nodeHex: String): String {
        if (attachedNodeHex == nodeHex) return ""
        attachedNodeHex = nodeHex
        bindings.entries.removeAll { it.value.nodeHex == nodeHex }
        linkRoutes.entries.removeAll { it.value == nodeHex }
        reverseRoutes.entries.removeAll { it.value.nodeHex == nodeHex }
        return if (fallbackUplinkHex == nodeHex) {
            " — attached node $nodeHex; configured fallback IS this node, ignoring it (BR-5)"
        } else {
            " — attached node $nodeHex"
        }
    }

    private fun upsert(idHex: String, nodeHex: String, nowMs: Long, origin: String): DirectoryEvent? {
        if (idHex == selfIdHex) {
            // Our own registration echoing back. Its node is the one we
            // registered through — bootstrap the attached-node fact from
            // it only while unknown (a stale flood echo can name an old
            // node; the register ack / heartbeat stay authoritative).
            if (attachedNodeHex == null) learnAttachedNode(nodeHex)
            return null
        }
        // One BLE client per node: a "peer" binding at our own node is a
        // stale or echoed registration, and routing to it would loop
        // back to us. Never store it.
        if (nodeHex == attachedNodeHex) return null
        val existing = bindings[idHex]
        val isNewNode = nodeHex !in announcedTo && bindings.values.none { it.nodeHex == nodeHex }
        val moved = existing != null && existing.nodeHex != nodeHex
        if (existing == null) {
            bindings[idHex] = Binding(nodeHex, nowMs)
        } else {
            existing.nodeHex = nodeHex
            existing.lastSeenMs = nowMs
        }
        if (existing != null && !moved && !isNewNode) return null // ttl refresh only
        return DirectoryEvent(
            summary = if (existing == null) "peer discovered ($origin): $idHex @ $nodeHex"
                      else "peer moved ($origin): $idHex -> $nodeHex",
            newPeerNodes = if (isNewNode) listOf(nodeHex) else emptyList(),
            routesChanged = existing == null || moved,
        )
    }

    /** Pending packets that became routable — send each, in order. The
     *  still-unroutable remainder stays queued. */
    suspend fun drainRoutable(nowMs: Long): List<Pair<ByteArray, String>> {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList<Pair<ByteArray, String>>()
        val keep = ArrayDeque<ByteArray>()
        while (pending.isNotEmpty()) {
            val raw = pending.removeFirst()
            val packet = parsePacket(raw)
            val node = packet?.let { resolveNodeFor(it) }
            if (node != null && packet != null) {
                recordLinkRequest(raw, node)
                out.add(raw to node)
            } else if (packet != null) {
                keep.addLast(raw)
            }
        }
        pending.addAll(keep)
        return out
    }

    /** Our cached announce for a newly discovered [nodeHex], or null if
     *  none cached / that node already got the current one. Marks it sent. */
    fun cachedAnnounceFor(nodeHex: String): ByteArray? {
        val n = nodeHex.uppercase()
        val a = cachedSelfAnnounce ?: return null
        if (!announcedTo.add(n)) return null
        return a
    }

    /** Directory ids worth `resolve`-ing right now: destinations of
     *  buffered packets (link-dest packets resolve via traffic, not the
     *  directory, so they are excluded). */
    fun resolveWanted(): List<String> {
        val want = LinkedHashSet<String>()
        for (raw in pending) {
            val p = parsePacket(raw) ?: continue
            if (p.destType != DEST_LINK) want.add(p.destHash.toHexUpper())
        }
        return want.toList()
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun knownPeerNodes(): List<String> = bindings.values.map { it.nodeHex }.distinct()

    /** Every known peer node plus the fallback, deduped, insertion order.
     *  Never the attached node — that's us (BR-5). */
    private fun fanoutTargets(): List<String> {
        val targets = LinkedHashSet<String>()
        bindings.values.forEach { targets.add(it.nodeHex) }
        usableFallback()?.let { targets.add(it) }
        attachedNodeHex?.let { targets.remove(it) }
        return targets.toList()
    }

    private suspend fun recordLinkRequest(raw: ByteArray, targetNode: String) {
        val packet = parsePacket(raw) ?: return
        if (packet.packetType != PACKET_LINKREQ) return
        linkRoutes[computeLinkId(packet, crypto).toHexUpper()] = targetNode
    }

    private fun prune(nowMs: Long) {
        bindings.entries.removeAll { nowMs - it.value.lastSeenMs > BINDING_STALE_MS }
        reverseRoutes.entries.removeAll { nowMs - it.value.seenMs > BINDING_STALE_MS }
    }

    companion object {
        /** Local staleness window. The directory's own TTL is 600s with
         *  240s re-floods; a binding we haven't seen confirmed in this
         *  long is gone or the peer moved. */
        const val BINDING_STALE_MS = 10 * 60_000L

        /** Same bound as the reference interface's `_pending`. */
        const val MAX_PENDING = 64

        /** Reverse-table cap; proofs fire within seconds of the DATA, so
         *  even a busy link needs only a handful of live entries. */
        const val MAX_REVERSE_ROUTES = 256

        private val LOC_RE = Regex("""loc\s+([0-9A-Fa-f]+)\s+([0-9A-Fa-f]{8})""")
        private val BINDING_RE = Regex("""([0-9A-Fa-f]+)\s*->\s*([0-9A-Fa-f]{8})\s+ttl=\d+s?""")

        /** Firmware register ack: `registered <n>-byte id at <NODE8HEX>`. */
        private val REGISTERED_RE = Regex("""registered\s+\d+-byte\s+id\s+at\s+([0-9A-Fa-f]{8})""")

        /** Heartbeat's own-node field: `[hb] up=…  node=<NODE8HEX> …`. */
        private val HB_NODE_RE = Regex("""node=([0-9A-Fa-f]{8})""")
    }
}

internal fun ByteArray.toHexUpper(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
