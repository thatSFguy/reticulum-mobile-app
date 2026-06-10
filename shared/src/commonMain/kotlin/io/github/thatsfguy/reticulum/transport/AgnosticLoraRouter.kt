package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.link.computeLinkId
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.parsePacket

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

    private val bindings = HashMap<String, Binding>()       // idHex -> binding
    private val linkRoutes = HashMap<String, String>()      // linkIdHex -> nodeHex
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
            val targets = LinkedHashSet<String>()
            bindings.values.forEach { targets.add(it.nodeHex) }
            fallbackUplinkHex?.let { targets.add(it) }
            if (targets.isEmpty()) return RouteDecision.Deferred("no peers known yet")
            announcedTo.addAll(targets)
            return RouteDecision.Send(targets.toList())
        }

        val destHex = packet.destHash.toHexUpper()
        val node = if (packet.destType == DEST_LINK) {
            linkRoutes[destHex]
        } else {
            bindings[destHex]?.nodeHex
        } ?: fallbackUplinkHex

        if (node == null) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(raw)
            return RouteDecision.Buffered
        }
        recordLinkRequest(packet.raw, node)
        return RouteDecision.Send(listOf(node))
    }

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
        when (packet.packetType) {
            PACKET_ANNOUNCE -> {
                val id = packet.destHash.toHexUpper()
                if (id != selfIdHex) return upsert(id, src, nowMs, origin = "announce")
            }
            PACKET_LINKREQ -> {
                val linkId = computeLinkId(packet, crypto).toHexUpper()
                linkRoutes[linkId] = src
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
            return DirectoryEvent("register ack: $trimmed", emptyList(), routesChanged = false)
        }
        return null
    }

    private fun upsert(idHex: String, nodeHex: String, nowMs: Long, origin: String): DirectoryEvent? {
        if (idHex == selfIdHex) return null // our own registration echoing back
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
            val destHex = packet?.destHash?.toHexUpper()
            val node = when {
                packet == null -> null
                packet.destType == DEST_LINK -> linkRoutes[destHex]
                else -> bindings[destHex]?.nodeHex
            } ?: fallbackUplinkHex
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

    private suspend fun recordLinkRequest(raw: ByteArray, targetNode: String) {
        val packet = parsePacket(raw) ?: return
        if (packet.packetType != PACKET_LINKREQ) return
        linkRoutes[computeLinkId(packet, crypto).toHexUpper()] = targetNode
    }

    private fun prune(nowMs: Long) {
        bindings.entries.removeAll { nowMs - it.value.lastSeenMs > BINDING_STALE_MS }
    }

    companion object {
        /** Local staleness window. The directory's own TTL is 600s with
         *  240s re-floods; a binding we haven't seen confirmed in this
         *  long is gone or the peer moved. */
        const val BINDING_STALE_MS = 10 * 60_000L

        /** Same bound as the reference interface's `_pending`. */
        const val MAX_PENDING = 64

        private val LOC_RE = Regex("""loc\s+([0-9A-Fa-f]+)\s+([0-9A-Fa-f]{8})""")
        private val BINDING_RE = Regex("""([0-9A-Fa-f]+)\s*->\s*([0-9A-Fa-f]{8})\s+ttl=\d+s?""")
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
