package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.announce.concatBytes
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.link.LINK_DEFAULT_MTU
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.MODE_AES256_CBC
import io.github.thatsfguy.reticulum.link.computeLinkId
import io.github.thatsfguy.reticulum.link.encodeSignalling
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.buildPacket
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

    // ---------------------------------------------------------------------
    // SPEC §6.2 / §6.6 — LRPROOF signed_data signalling conditional.
    //
    // The proof body is 96 bytes (no signalling) OR 99 bytes (signalling
    // appended). signed_data MUST include signalling iff the body does.
    // Pre-v0.1.84 we unconditionally appended cached LRREQ signalling
    // on verify, which broke every link handshake against a fwdsvc /
    // legacy peer (their 96B proofs signed without signalling).
    //
    // These tests synthesize the proof body byte-for-byte using the
    // crypto primitives directly (external-oracle style per playbook §5),
    // not by round-tripping through our own emitter. If validateProof
    // ever drifts from the SPEC layout, these fail without needing the
    // live interop harness.
    // ---------------------------------------------------------------------

    private suspend fun primeInitiator(): Triple<Link, ByteArray, ByteArray> {
        val crypto = TestVectors.crypto
        val (initiator, requestData) = Link.createInitiator(
            peerLongTermSigPub = TestVectors.Bob.sigPub,
            peerDestHash       = TestVectors.Bob.destHash,
            crypto             = crypto,
            nowMs              = 1_700_000_000_000L,
        )
        val reqBytes = buildPacket(
            packetType = PACKET_LINKREQ,
            destHash   = TestVectors.Bob.destHash,
            payload    = requestData,
        )
        val reqPkt = parsePacket(reqBytes)!!
        initiator.setLinkIdFromPacket(reqPkt)
        val responderXPriv = crypto.generateX25519PrivateKey()
        val responderXPub  = crypto.x25519PublicKey(responderXPriv)
        return Triple(initiator, initiator.linkId!!, responderXPub)
    }

    @Test fun validateProof_acceptsNoSignallingLrProof_96B() = runTest {
        val crypto = TestVectors.crypto
        val (initiator, linkId, responderXPub) = primeInitiator()

        // signed_data WITHOUT signalling — what fwdsvc / legacy peers emit.
        val signedData = concatBytes(listOf(linkId, responderXPub, TestVectors.Bob.sigPub))
        val signature  = crypto.ed25519Sign(signedData, TestVectors.Bob.sigPriv)
        val proofBody  = concatBytes(listOf(signature, responderXPub))
        assertEquals(96, proofBody.size)

        val res = initiator.validateProof(proofBody, nowMs = 1_700_000_001_000L)
        assertTrue(res is Link.LrProofResult.Success, "Expected Success, got $res")
    }

    @Test fun validateProof_rejects96BProofSignedWithCachedSignalling() = runTest {
        // Regression catch for the pre-v0.1.84 bug. If the proof body is
        // 96B (no signalling) but the signer wrongly appended cached LRREQ
        // signalling to signed_data, verification MUST fail under the
        // fixed code (which uses body-presence, not the cached value).
        val crypto = TestVectors.crypto
        val (initiator, linkId, responderXPub) = primeInitiator()
        val cachedSignalling = initiator.signallingBytes!!

        val wrongSignedData = concatBytes(listOf(
            linkId, responderXPub, TestVectors.Bob.sigPub, cachedSignalling,
        ))
        val signature = crypto.ed25519Sign(wrongSignedData, TestVectors.Bob.sigPriv)
        val proofBody = concatBytes(listOf(signature, responderXPub))
        assertEquals(96, proofBody.size)

        val res = initiator.validateProof(proofBody, nowMs = 1_700_000_001_000L)
        val fail = res as? Link.LrProofResult.Failure
        assertNotNull(fail, "Expected Failure, got $res")
        assertTrue(fail.reason.contains("signature", ignoreCase = true),
            "Expected signature failure, got: ${fail.reason}")
    }

    @Test fun validateProof_acceptsWithSignallingLrProof_99B() = runTest {
        val crypto = TestVectors.crypto
        val (initiator, linkId, responderXPub) = primeInitiator()
        val signalling = encodeSignalling(LINK_DEFAULT_MTU, MODE_AES256_CBC)

        val signedData = concatBytes(listOf(
            linkId, responderXPub, TestVectors.Bob.sigPub, signalling,
        ))
        val signature  = crypto.ed25519Sign(signedData, TestVectors.Bob.sigPriv)
        val proofBody  = concatBytes(listOf(signature, responderXPub, signalling))
        assertEquals(99, proofBody.size)

        val res = initiator.validateProof(proofBody, nowMs = 1_700_000_001_000L)
        assertTrue(res is Link.LrProofResult.Success, "Expected Success, got $res")
    }

    @Test fun validateProof_rejects99BProofSignedWithoutSignalling() = runTest {
        // Symmetric drift catch: 99B body but signer omitted signalling
        // from signed_data — verification MUST fail.
        val crypto = TestVectors.crypto
        val (initiator, linkId, responderXPub) = primeInitiator()
        val signalling = encodeSignalling(LINK_DEFAULT_MTU, MODE_AES256_CBC)

        val wrongSignedData = concatBytes(listOf(linkId, responderXPub, TestVectors.Bob.sigPub))
        val signature = crypto.ed25519Sign(wrongSignedData, TestVectors.Bob.sigPriv)
        val proofBody = concatBytes(listOf(signature, responderXPub, signalling))
        assertEquals(99, proofBody.size)

        val res = initiator.validateProof(proofBody, nowMs = 1_700_000_001_000L)
        val fail = res as? Link.LrProofResult.Failure
        assertNotNull(fail, "Expected Failure, got $res")
        assertTrue(fail.reason.contains("signature", ignoreCase = true),
            "Expected signature failure, got: ${fail.reason}")
    }
}
