package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.crypto.testCryptoProvider
import io.github.thatsfguy.reticulum.link.computeLinkId
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
    fun heartbeatAndNoiseLinesAreIgnored() = runTest {
        val r = router()
        assertNull(r.onTextLine("[hb] up=1616s  node=9828F51B  nbrs=1 routes=2 txq=0 stk=1223", 0))
        assertNull(r.onTextLine("[ble] adv=1 connected=1 rx=902 tx=0 frames=4 fmax=219 PIN=123456", 0))
        assertNull(r.onTextLine("[dir] 2 binding(s):", 0))
        assertTrue(r.knownPeerNodes().isEmpty())
    }
}
