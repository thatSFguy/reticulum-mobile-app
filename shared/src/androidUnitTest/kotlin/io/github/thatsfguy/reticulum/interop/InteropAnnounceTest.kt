package io.github.thatsfguy.reticulum.interop

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.announce.buildAnnounce
import io.github.thatsfguy.reticulum.announce.extractDisplayName
import io.github.thatsfguy.reticulum.announce.parseAnnounce
import io.github.thatsfguy.reticulum.announce.validateAnnounce
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_BROADCAST
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live cross-implementation tests against upstream Python RNS / LXMF
 * via the python_peer.py subprocess helper. Each test calls
 * [PythonPeer.startOrSkip] and skips cleanly if Python or rns/lxmf
 * aren't available — we don't want CI failures on machines without
 * the upstream install.
 *
 * Pattern ported from
 * `reticulum-forwarding-service/tests/interop/interop_test.go`.
 */
class InteropAnnounceTest {

    private val crypto = TestVectors.crypto

    /**
     * Python builds + signs an announce → we parse + verify it. Catches
     * any breakage in our parser (§4.5 announce validation) or
     * signed-data construction (§4.5 step 5).
     */
    @Test fun pythonToUs_announce() = runBlocking {
        val peer = PythonPeer.startOrSkip()
        Assume.assumeTrue("python/rns/lxmf not available", peer != null)
        peer!!.use {
            peer.call("make_identity", mapOf("label" to "alice"))
            val r = peer.call("build_announce", mapOf(
                "label"        to "alice",
                "full_name"    to "lxmf.delivery",
                "display_name" to "AliceLive",
            ))
            val wireHex   = r["wire_bytes_hex"] as String
            val wantDest  = r["dest_hash_hex"]  as String

            val pkt = parsePacket(wireHex.hexToBytes())
            assertNotNull(pkt)
            assertEquals(PACKET_ANNOUNCE, pkt.packetType)
            assertEquals(wantDest, pkt.destHash.toHex())

            val ann = parseAnnounce(pkt.payload, pkt.contextFlag, pkt.destHash, crypto)
            assertNotNull(ann)
            assertTrue(validateAnnounce(ann, crypto), "Python's signed announce failed our validate")
            assertEquals("AliceLive", extractDisplayName(ann.appData))
        }
    }

    /**
     * We build + sign an announce → Python validates it. Catches our
     * outbound encoder regressions, especially the §5.6.1 canonical-
     * msgpack rules (umsgpack-strict path-1 verify).
     */
    @Test fun usToPython_announce() = runBlocking {
        val peer = PythonPeer.startOrSkip()
        Assume.assumeTrue(peer != null)
        peer!!.use {
            val id = Identity(crypto).also { it.generate() }
            val appData = MessagePack.encode(
                listOf("BobLive".encodeToByteArray(), 0L)
            )
            val (destHash, payload, hasRatchet) = buildAnnounce(
                identity   = id,
                crypto     = crypto,
                appName    = "lxmf.delivery",
                appData    = appData,
                ratchetPub = id.ratchetPubKey,
                nowSeconds = System.currentTimeMillis() / 1000,
            )
            val packet = buildPacket(
                headerType   = HEADER_1,
                contextFlag  = if (hasRatchet) 1 else 0,
                transportType = TRANSPORT_BROADCAST,
                destType     = DEST_SINGLE,
                packetType   = PACKET_ANNOUNCE,
                destHash     = destHash,
                context      = CTX_NONE,
                payload      = payload,
            )

            val r = peer.call("validate_announce", mapOf(
                "wire_bytes_hex" to packet.toHex(),
            ))
            assertEquals(true, r["verified"], "Python rejected our announce: ${r["error"]}")
            assertEquals(destHash.toHex(), r["dest_hash_hex"])
            // RNS.Identity.recall returns the announcer's combined
            // X25519+Ed25519 pubkey hex — must match the publicKey we
            // signed under.
            assertEquals(id.publicKey.toHex(), r["pub_hex"])
        }
    }
}
