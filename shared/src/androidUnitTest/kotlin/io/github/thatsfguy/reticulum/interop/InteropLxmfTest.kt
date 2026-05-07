package io.github.thatsfguy.reticulum.interop

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.announce.buildAnnounce
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.lxmf.unpackMessage
import io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.TRANSPORT_BROADCAST
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Live cross-implementation tests for opportunistic LXMF — Python builds
 * + Token-encrypts → we decrypt + parse + verify, and vice versa. Catches
 * §3.2 token-crypto regressions, §5.3 LXMF wire-format breaks, and the
 * §5.6.1 canonical msgpack rules (which strict-mode RNS uses for its
 * raw-bytes signature verification path).
 */
class InteropLxmfTest {

    private val crypto = TestVectors.crypto

    /**
     * Python builds an LXMF from alice → bob, Token-encrypts to bob's
     * encryption key, hands us the ciphertext. We decrypt with bob's
     * private keys, parse the opportunistic body, verify alice's signature.
     */
    @Test fun pythonToUs_lxmf() = runBlocking {
        val peer = PythonPeer.startOrSkip()
        Assume.assumeTrue("python/rns/lxmf not available", peer != null)
        peer!!.use {
            peer.call("make_identity", mapOf("label" to "alice"))
            val bobInfo = peer.call("make_identity", mapOf("label" to "bob"))
            val bobPriv = (bobInfo["priv_hex"] as String).hexToBytes()
            val bob = Identity(crypto).also {
                it.loadFromPrivateKeys(
                    encPriv = bobPriv.copyOfRange(0, 32),
                    sigPriv = bobPriv.copyOfRange(32, 64),
                )
            }
            val bobDest = computeDestinationHash(crypto, "lxmf.delivery", bob.hash!!)

            val r = peer.call("build_lxmf", mapOf(
                "src_label"     to "alice",
                "dst_label"     to "bob",
                "dst_full_name" to "lxmf.delivery",
                "title"         to "live test",
                "content"       to "hi from python",
            ))
            val ciphertext = (r["ciphertext_hex"] as String).hexToBytes()
            assertEquals(bobDest.toHex(), r["dst_dest_hash_hex"])

            val tokenCrypto = TokenCrypto(crypto)
            val plain = tokenCrypto.decrypt(
                token             = ciphertext,
                candidatePrivKeys = listOfNotNull(bob.ratchetPrivKey, bob.encPrivKey),
                ourIdentityHash   = bob.hash!!,
            )

            val msg = unpackMessage(plain, bobDest, crypto)
            assertEquals("live test", msg.title)
            assertEquals("hi from python", msg.content)

            // Pull alice's pub from her priv (idempotent make_identity)
            // and verify her signature on Python's LXMF.
            val aliceInfo = peer.call("make_identity", mapOf("label" to "alice"))
            val alicePriv = (aliceInfo["priv_hex"] as String).hexToBytes()
            val alice = Identity(crypto).also {
                it.loadFromPrivateKeys(
                    encPriv = alicePriv.copyOfRange(0, 32),
                    sigPriv = alicePriv.copyOfRange(32, 64),
                )
            }
            val variant = verifyMessageSignature(msg, alice, crypto)
            assertNotNull(variant, "Python's LXMF signature failed our verifier")

            val aliceDest = computeDestinationHash(crypto, "lxmf.delivery", alice.hash!!)
            assertContentEquals(aliceDest, msg.sourceHash)
        }
    }

    /**
     * We build + sign + Token-encrypt an LXMF from alice → bob. Python
     * decrypts, parses, runs its own signature_validated check using
     * alice's pub from a freshly-validated announce we hand it.
     */
    @Test fun usToPython_lxmf() = runBlocking {
        val peer = PythonPeer.startOrSkip()
        Assume.assumeTrue(peer != null)
        peer!!.use {
            // Generate alice + bob locally; ship their privs to Python so
            // it has the recipient's keys for decrypt and the sender's
            // identity reachable for sig-verify.
            val alice = Identity(crypto).also { it.generate() }
            val bob   = Identity(crypto).also { it.generate() }

            val alicePrivHex = (alice.encPrivKey!! + alice.sigPrivKey!!).toHex()
            val bobPrivHex   = (bob.encPrivKey!!   + bob.sigPrivKey!!).toHex()
            peer.call("make_identity", mapOf("label" to "alice", "priv_hex" to alicePrivHex))
            peer.call("make_identity", mapOf("label" to "bob",   "priv_hex" to bobPrivHex))

            val aliceDest = computeDestinationHash(crypto, "lxmf.delivery", alice.hash!!)
            val bobDest   = computeDestinationHash(crypto, "lxmf.delivery", bob.hash!!)

            // Pack + sign opportunistic LXMF body, Token-encrypt to bob.
            val plain = packMessage(
                sourceIdentity   = alice,
                destHash         = bobDest,
                sourceHash       = aliceDest,
                title            = "live test",
                content          = "hi from kotlin",
                timestampSeconds = (System.currentTimeMillis() / 1000.0),
                crypto           = crypto,
            )
            val tokenCrypto = TokenCrypto(crypto)
            val ciphertext = tokenCrypto.encrypt(
                plaintext             = plain,
                recipientEncPub       = bob.encPubKey!!,
                recipientIdentityHash = bob.hash!!,
            )

            // Hand alice's announce to Python so its known_destinations
            // gets her pub for sig verify (RNS.Identity.recall is keyed
            // on dest_hash and gets populated by validate_announce).
            val appData = MessagePack.encode(listOf("AliceLive".encodeToByteArray(), 0L))
            val (annDest, annPayload, hasRatchet) = buildAnnounce(
                identity   = alice,
                crypto     = crypto,
                appName    = "lxmf.delivery",
                appData    = appData,
                ratchetPub = alice.ratchetPubKey,
                nowSeconds = System.currentTimeMillis() / 1000,
            )
            val annPacket = buildPacket(
                headerType    = HEADER_1,
                contextFlag   = if (hasRatchet) 1 else 0,
                transportType = TRANSPORT_BROADCAST,
                destType      = DEST_SINGLE,
                packetType    = PACKET_ANNOUNCE,
                destHash      = annDest,
                context       = CTX_NONE,
                payload       = annPayload,
            )
            val vRes = peer.call("validate_announce", mapOf(
                "wire_bytes_hex" to annPacket.toHex(),
            ))
            assertEquals(true, vRes["verified"], "Python rejected alice's announce: ${vRes["error"]}")

            // Ask Python to decrypt + parse + verify our LXMF.
            val r = peer.call("decrypt_lxmf", mapOf(
                "ciphertext_hex" to ciphertext.toHex(),
                "dst_label"      to "bob",
                "dst_full_name"  to "lxmf.delivery",
            ))
            assertEquals(true, r["verified"], "Python rejected our LXMF signature: $r")
            assertEquals("live test", r["title"])
            assertEquals("hi from kotlin", r["content"])
            assertEquals(aliceDest.toHex(), r["source_hash_hex"])
        }
    }
}
