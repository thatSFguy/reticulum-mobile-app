package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.computeLinkId
import io.github.thatsfguy.reticulum.protocol.parsePacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkTest {

    @Test fun linkIdMatchesTestVector() = runTest {
        val crypto = TestVectors.crypto
        val req = parsePacket(TestVectors.Link.linkRequestPacket)
        assertNotNull(req)
        val linkId = computeLinkId(req, crypto)
        assertContentEquals(TestVectors.Link.linkId, linkId)
    }

    @Test fun lrProofSignatureValidatesAgainstAlicePub() = runTest {
        val crypto = TestVectors.crypto
        val proofPkt = parsePacket(TestVectors.Link.lrProofPacket)
        assertNotNull(proofPkt)
        // PROOF payload = sig(64) + responder_x25519_pub(32) + signalling(3) = 99
        assertEquals(99, proofPkt.payload.size)

        val signature = proofPkt.payload.copyOfRange(0, 64)
        // The signedData per test-vectors covers link_id || responder_x25519_pub
        // || responder_long_term_sig_pub (Alice) || signalling.
        assertTrue(crypto.ed25519Verify(signature, TestVectors.Link.signedData, TestVectors.Alice.sigPub))
    }

    @Test fun responderRoundTrip() = runTest {
        val crypto = TestVectors.crypto

        // Initiator builds a LINKREQUEST aimed at Bob
        val (initiator, requestData) = Link.createInitiator(
            peerLongTermSigPub = TestVectors.Bob.sigPub,
            peerDestHash       = TestVectors.Bob.destHash,
            crypto             = crypto,
            nowMs              = 1_700_000_000_000L,
        )
        assertEquals(64 + 3, requestData.size)

        // Wrap it as the LINKREQUEST packet bytes the responder would receive.
        val reqPacketBytes = io.github.thatsfguy.reticulum.protocol.buildPacket(
            packetType = io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ,
            destHash   = TestVectors.Bob.destHash,
            payload    = requestData,
        )
        val reqPkt = parsePacket(reqPacketBytes)
        assertNotNull(reqPkt)
        initiator.setLinkIdFromPacket(reqPkt)

        // Responder is Bob — load his identity and validate the request
        val bob = Identity(crypto)
        bob.loadFromPrivateKeys(
            encPriv = TestVectors.Bob.encPriv,
            sigPriv = TestVectors.Bob.sigPriv,
            ratchetPriv = TestVectors.Bob.ratchetPriv,
        )
        val (responder, proofData) = Link.validateRequest(reqPkt, bob, crypto)
        assertContentEquals(initiator.linkId, responder.linkId)
        assertEquals(64 + 32 + 3, proofData.size)

        // Initiator validates the proof and derives the same key
        val res = initiator.validateProof(proofData, nowMs = 1_700_000_001_000L)
        val ok = res as Link.LrProofResult.Success
        assertContentEquals(responder.derivedKey, initiator.derivedKey)

        // Both sides can encrypt/decrypt with the link key
        val tc = TokenCrypto(crypto)
        val pt = "hi over link from alice".encodeToByteArray()
        val ct = tc.encryptWithDerivedKey(pt, initiator.derivedKey!!)
        val back = tc.decryptWithDerivedKey(ct, responder.derivedKey!!)
        assertContentEquals(pt, back)

        // And the LRRTT msgpack round-trips through the responder
        val rttBack = tc.decryptWithDerivedKey(ok.rttData, responder.derivedKey!!)
        assertEquals(true, rttBack.isNotEmpty())
    }

    @Test fun decryptHandshakeTestCiphertext() = runTest {
        val crypto = TestVectors.crypto
        val tc = TokenCrypto(crypto)
        val pt = tc.decryptWithDerivedKey(
            token = TestVectors.LinkHandshake.testCiphertext,
            derivedKey = TestVectors.LinkHandshake.derivedKey,
        )
        assertEquals(TestVectors.LinkHandshake.testPlaintext, pt.decodeToString())
    }
}
