package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.crypto.testCryptoProvider
import io.github.thatsfguy.reticulum.link.computeLinkId
import io.github.thatsfguy.reticulum.link.computePacketFullHash
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AgnosticLoraRouter] — identity-addressed routing over the lora-net
 * directory. Text-line formats are pinned to the firmware contract
 * (`loc <id> <node>`, dirdump `<id> -> <NODE>  ttl=<S>s`), confirmed
 * over the agent bridge 2026-06-10.
 */
class AgnosticLoraRouterTest {

    private val crypto = testCryptoProvider()

    private val selfId = "AA".repeat(16)               // our dest hash, 32 hex
    private val peerId = "BB".repeat(16)
    private val peerHash = ByteArray(16) { 0xBB.toByte() }
    private val selfHash = ByteArray(16) { 0xAA.toByte() }

    private fun router(fallback: String? = null) =
        AgnosticLoraRouter(selfId, fallback, crypto)

    private fun announce(dest: ByteArray = selfHash) = buildPacket(
        packetType = PACKET_ANNOUNCE, destType = DEST_SINGLE,
        destHash = dest, payload = ByteArray(140),
    )

    private fun data(dest: ByteArray = peerHash) = buildPacket(
        packetType = PACKET_DATA, destType = DEST_SINGLE,
        destHash = dest, payload = ByteArray(32),
    )

    private fun linkRequest(dest: ByteArray = peerHash) = buildPacket(
        packetType = PACKET_LINKREQ, destType = DEST_SINGLE,
        destHash = dest, payload = ByteArray(67),
    )

    @Test
    fun announceWithNoPeersIsDeferredThenUnicastOnDiscovery() = runTest {
        val r = router()
        assertIs<AgnosticLoraRouter.RouteDecision.Deferred>(
            r.routeOutbound(announce(), nowMs = 0),
        )
        // Peer appears via a dirdump row → its node should get the cached announce.
        val ev = r.onTextLine("  $peerId -> D97EEC3A  ttl=595s", nowMs = 1_000)
        assertNotNull(ev)
        assertEquals(listOf("D97EEC3A"), ev.newPeerNodes)
        assertNotNull(r.cachedAnnounceFor("D97EEC3A"))
        // Only once per cached announce.
        assertNull(r.cachedAnnounceFor("D97EEC3A"))
    }

    @Test
    fun freshAnnounceFansOutToAllKnownPeerNodesDeduped() = runTest {
        val r = router(fallback = "11223344")
        r.onTextLine("loc $peerId D97EEC3A", nowMs = 0)
        r.onTextLine("loc ${"CC".repeat(16)} D97EEC3A", nowMs = 0) // same node, second id
        r.onTextLine("loc ${"DD".repeat(16)} B51EEC13", nowMs = 0)
        val d = r.routeOutbound(announce(), nowMs = 1)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(setOf("D97EEC3A", "B51EEC13", "11223344"), d.targets.toSet())
        assertEquals(3, d.targets.size) // deduped
    }

    @Test
    fun dataToResolvedPeerRoutesToItsNode() = runTest {
        val r = router()
        r.onTextLine("loc $peerId D97EEC3A", nowMs = 0)
        val d = r.routeOutbound(data(), nowMs = 1)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(listOf("D97EEC3A"), d.targets)
    }

    @Test
    fun dataToUnknownPeerBuffersThenFlushesOnLoc() = runTest {
        val r = router()
        val raw = data()
        assertIs<AgnosticLoraRouter.RouteDecision.Buffered>(r.routeOutbound(raw, nowMs = 0))
        assertEquals(listOf(peerId), r.resolveWanted())
        r.onTextLine("loc $peerId B51EEC13", nowMs = 1)
        val flushed = r.drainRoutable(nowMs = 2)
        assertEquals(1, flushed.size)
        assertContentEquals(raw, flushed[0].first)
        assertEquals("B51EEC13", flushed[0].second)
        assertTrue(!r.hasPending())
    }

    @Test
    fun unknownPeerFallsBackToConfiguredUplink() = runTest {
        val r = router(fallback = "9828f51b") // lower-case input normalizes
        val d = r.routeOutbound(data(), nowMs = 0)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(listOf("9828F51B"), d.targets)
    }

    @Test
    fun inboundAnnounceLearnsReversePath() = runTest {
        val r = router()
        val ev = r.onInbound("d97eec3a", announce(dest = peerHash), nowMs = 0)
        assertNotNull(ev)
        assertEquals(listOf("D97EEC3A"), ev.newPeerNodes)
        val d = r.routeOutbound(data(), nowMs = 1)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(listOf("D97EEC3A"), d.targets)
    }

    @Test
    fun outboundLinkRequestPinsLinkTraffic() = runTest {
        val r = router()
        r.onTextLine("loc $peerId D97EEC3A", nowMs = 0)
        val lr = linkRequest()
        val sent = r.routeOutbound(lr, nowMs = 1)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(sent)
        val linkId = computeLinkId(parsePacket(lr)!!, crypto)
        val linkPacket = buildPacket(
            packetType = PACKET_DATA, destType = DEST_LINK,
            destHash = linkId, payload = ByteArray(16),
        )
        val d = r.routeOutbound(linkPacket, nowMs = 2)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(listOf("D97EEC3A"), d.targets)
    }

    @Test
    fun inboundLinkRequestRoutesOurRepliesBack() = runTest {
        val r = router()
        val lr = linkRequest(dest = selfHash)
        r.onInbound("B51EEC13", lr, nowMs = 0)
        val linkId = computeLinkId(parsePacket(lr)!!, crypto)
        val proof = buildPacket(
            packetType = PACKET_DATA, destType = DEST_LINK,
            destHash = linkId, payload = ByteArray(99),
        )
        val d = r.routeOutbound(proof, nowMs = 1)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(listOf("B51EEC13"), d.targets)
    }

    @Test
    fun ownRegistrationEchoIsIgnored() = runTest {
        val r = router()
        assertNull(r.onTextLine("loc $selfId 9828F51B", nowMs = 0))
        assertNull(r.onTextLine("  $selfId -> 9828F51B  ttl=600s", nowMs = 0))
        assertTrue(r.knownPeerNodes().isEmpty())
    }

    @Test
    fun registeredAckIsReportedButChangesNothing() = runTest {
        val r = router()
        val ev = r.onTextLine("registered 16-byte id at 9828F51B", nowMs = 0)
        assertNotNull(ev)
        assertTrue(!ev.routesChanged)
        assertTrue(ev.newPeerNodes.isEmpty())
    }

    @Test
    fun staleBindingsPruneAfterWindow() = runTest {
        val r = router()
        r.onTextLine("loc $peerId D97EEC3A", nowMs = 0)
        // Just inside the window: still routable.
        var d = r.routeOutbound(data(), nowMs = AgnosticLoraRouter.BINDING_STALE_MS - 1)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        // Beyond it: pruned → buffered.
        d = r.routeOutbound(data(), nowMs = AgnosticLoraRouter.BINDING_STALE_MS + 1)
        assertIs<AgnosticLoraRouter.RouteDecision.Buffered>(d)
    }

    @Test
    fun plainDestBroadcastsFanOutInsteadOfBuffering() = runTest {
        // Path requests etc. have PLAIN dests that are never directory
        // ids — buffering them would queue forever and spam resolves.
        val r = router()
        val pathReq = buildPacket(
            packetType = PACKET_DATA,
            destType = io.github.thatsfguy.reticulum.protocol.DEST_PLAIN,
            destHash = ByteArray(16) { 0x77 }, payload = ByteArray(32),
        )
        assertIs<AgnosticLoraRouter.RouteDecision.Deferred>(r.routeOutbound(pathReq, nowMs = 0))
        assertTrue(!r.hasPending())
        r.onTextLine("loc $peerId D97EEC3A", nowMs = 1)
        val d = r.routeOutbound(pathReq, nowMs = 2)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(listOf("D97EEC3A"), d.targets)
        assertTrue(r.resolveWanted().isEmpty())
    }

    // ── BR-5: the attached node is us — never a routing target ────────

    @Test
    fun registerAckLearnsAttachedNodeAndNeutralizesMatchingFallback() = runTest {
        // The field bug: a stale fallback (auto-filled pre-v1.2.52) named
        // the phone's own node, so unroutable packets — delivery proofs
        // above all — were sent to ourselves and black-holed.
        val r = router(fallback = "9828F51B")
        val ev = r.onTextLine("registered 16-byte id at 9828F51B", nowMs = 0)
        assertNotNull(ev)
        assertTrue(ev.summary.contains("attached node 9828F51B"))
        assertEquals("9828F51B", r.attachedNodeHex)
        // Fallback no longer routes anything...
        assertIs<AgnosticLoraRouter.RouteDecision.Buffered>(r.routeOutbound(data(), nowMs = 1))
        // ...and fanout excludes it.
        r.onTextLine("loc $peerId D97EEC3A", nowMs = 2)
        val d = r.routeOutbound(announce(), nowMs = 3)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(listOf("D97EEC3A"), d.targets)
    }

    @Test
    fun heartbeatLearnsAttachedNodeSilently() = runTest {
        val r = router(fallback = "9828F51B")
        assertNull(r.onTextLine("[hb] up=1616s  node=9828F51B  nbrs=1 routes=2 txq=0 stk=1223", 0))
        assertEquals("9828F51B", r.attachedNodeHex)
        assertIs<AgnosticLoraRouter.RouteDecision.Buffered>(r.routeOutbound(data(), nowMs = 1))
    }

    @Test
    fun ownBindingRowBootstrapsAttachedNode() = runTest {
        // Pre-fw-0.4.5 the initial directory dump included our OWN
        // binding — its node is the one we registered through.
        val r = router()
        assertNull(r.onTextLine("  $selfId -> 9828F51B  ttl=600s", nowMs = 0))
        assertEquals("9828F51B", r.attachedNodeHex)
    }

    @Test
    fun bindingAtAttachedNodeIsPurgedAndRejected() = runTest {
        // One BLE client per node: a "peer" at our own node is stale or
        // an echo, and routing to it loops back to us.
        val r = router()
        r.onTextLine("loc $peerId 9828F51B", nowMs = 0) // learned before we know better
        r.onTextLine("registered 16-byte id at 9828F51B", nowMs = 1) // purges it
        assertIs<AgnosticLoraRouter.RouteDecision.Buffered>(r.routeOutbound(data(), nowMs = 2))
        assertNull(r.onTextLine("loc $peerId 9828F51B", nowMs = 3)) // rejected outright now
        assertTrue(r.knownPeerNodes().isEmpty())
    }

    @Test
    fun loopbackInboundLearnsNothing() = runTest {
        // fw 0.4.5 echoes self-addressed frames back. Our own looped-back
        // LINKREQ must not re-pin its link to our own node.
        val r = router()
        r.onTextLine("registered 16-byte id at D97EEC3A", nowMs = 0)
        val lr = linkRequest(dest = selfHash)
        assertNull(r.onInbound("D97EEC3A", lr, nowMs = 1))
        val linkId = computeLinkId(parsePacket(lr)!!, crypto)
        val onLink = buildPacket(
            packetType = PACKET_DATA, destType = DEST_LINK,
            destHash = linkId, payload = ByteArray(16),
        )
        assertIs<AgnosticLoraRouter.RouteDecision.Buffered>(r.routeOutbound(onLink, nowMs = 2))
    }

    @Test
    fun bufferedLinkPacketFlushesWhenTheLinkRequestPinArrives() = runTest {
        // Responder race: the engine can emit an LRPROOF before the
        // router has pinned the inbound LINKREQ's link route. The pin
        // must then flush it — nothing else ever resolves a link dest.
        val r = router()
        val lr = linkRequest(dest = selfHash)
        val linkId = computeLinkId(parsePacket(lr)!!, crypto)
        val lrproof = buildPacket(
            packetType = PACKET_PROOF, destType = DEST_LINK,
            destHash = linkId, payload = ByteArray(99),
        )
        assertIs<AgnosticLoraRouter.RouteDecision.Buffered>(r.routeOutbound(lrproof, nowMs = 0))
        val ev = r.onInbound("B51EEC13", lr, nowMs = 1)
        assertNotNull(ev)
        assertTrue(ev.routesChanged)
        val flushed = r.drainRoutable(nowMs = 2)
        assertEquals(1, flushed.size)
        assertEquals("B51EEC13", flushed[0].second)
    }

    // ── BR-5: reverse table routes delivery proofs to the origin ──────

    @Test
    fun inboundDataPinsReverseRouteForDeliveryProof() = runTest {
        // Opportunistic delivery proofs are addressed to the proved
        // packet's truncated hash (Transport.reverse_table upstream).
        val r = router()
        val inboundData = data(dest = selfHash)
        r.onInbound("D97EEC3A", inboundData, nowMs = 0)
        val truncHash = computePacketFullHash(parsePacket(inboundData)!!, crypto)
            .copyOfRange(0, 16)
        val proof = buildPacket(
            packetType = PACKET_PROOF, destType = DEST_SINGLE,
            destHash = truncHash, payload = ByteArray(64),
        )
        val d = r.routeOutbound(proof, nowMs = 1)
        assertIs<AgnosticLoraRouter.RouteDecision.Send>(d)
        assertEquals(listOf("D97EEC3A"), d.targets)
    }

    @Test
    fun proofWithNoReverseRouteIsDroppedNotBuffered() = runTest {
        // A proof's dest is never a directory id — buffering it would
        // spam resolves forever. The peer's retransmit re-pins instead.
        val r = router()
        val proof = buildPacket(
            packetType = PACKET_PROOF, destType = DEST_SINGLE,
            destHash = ByteArray(16) { 0x42 }, payload = ByteArray(64),
        )
        assertIs<AgnosticLoraRouter.RouteDecision.Deferred>(r.routeOutbound(proof, nowMs = 0))
        assertTrue(!r.hasPending())
        assertTrue(r.resolveWanted().isEmpty())
    }

    @Test
    fun heartbeatAndNoiseLinesAreIgnored() = runTest {
        val r = router()
        assertNull(r.onTextLine("[hb] up=1616s  node=9828F51B  nbrs=1 routes=2 txq=0 stk=1223", 0))
        assertNull(r.onTextLine("[ble] adv=1 connected=1 rx=902 tx=0 frames=4 fmax=219 PIN=123456", 0))
        assertNull(r.onTextLine("[dir] 2 binding(s):", 0))
        assertTrue(r.knownPeerNodes().isEmpty())
    }
}
