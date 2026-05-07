package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.engine.LinkSession
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.computePacketFullHash
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that `LinkSession.handleDataProof` verifies the Ed25519 signature
 * over the packet hash before accepting a delivery confirmation, per
 * spec §6.5.1 / `RNS/PacketReceipt.py validate_proof`. Without the
 * signature check, anyone who saw the encrypted DATA packet on the
 * wire (transit relay, RF/BLE/TCP eavesdropper) could forge a 32-byte
 * hash-match PROOF and trick the sender into marking a message as
 * delivered while silently dropping it — security review finding 2026-05-07.
 *
 * Bootstraps a real ACTIVE initiator-side Link via the same public
 * Link.createInitiator → validateRequest → validateProof dance as
 * [LinkTest.responderRoundTrip], then exercises the proof-receive
 * path through [LinkSession.handlePacket] with both forged and
 * legitimate proof bodies.
 */
class LinkSessionProofTest {

    @Test fun forgedProof_hashMatchButBadSignature_isRejected() = runTest {
        val (session, _, dataPacketRaw, fullHash) = setupLinkAndSendData(this)

        // Forge a proof that has the right hash but garbage signature
        // bytes. The hash is the only thing the original code checked;
        // a fix that adds Ed25519 verification will reject this.
        val forgedSig = ByteArray(64) { 0x55 }
        val forgedProofPayload = fullHash + forgedSig
        val forgedProofPacket = buildProofPacket(session.link.linkId!!, forgedProofPayload)

        val proofPkt = parsePacket(forgedProofPacket)
        assertNotNull(proofPkt)
        session.handlePacket(proofPkt, rssi = null)

        // The send call is suspending in a child coroutine with a 250ms
        // timeout. Advance virtual time past it so the awaiter resolves.
        advanceTimeBy(500)

        // After the forged proof is rejected, the awaiter should still
        // be pending (or have timed out to false). We're not racing
        // against a real responder, so a timeout is the only resolution.
        // assertFalse on the deferred captures both possibilities:
        //   - currently rejecting forged → timeout fires → false
        //   - bug regressed (no signature check) → completes true
        // The bug-regressed path fails this assertion immediately.
        assertFalse(
            sendResult.isCompleted && sendResult.getCompleted() == true,
            "forged proof must NOT be accepted as a valid delivery confirmation",
        )

        // Suppress unused warning — referenced via private field below
        @Suppress("UNUSED_EXPRESSION") dataPacketRaw
    }

    @Test fun legitProof_validSignature_isAccepted() = runTest {
        val crypto = TestVectors.crypto
        val (session, bob, dataPacketRaw, fullHash) = setupLinkAndSendData(this)

        // Build a real proof exactly as ResponderLinkSession would: sign
        // the full hash with Bob's long-term Ed25519 priv, prepend hash.
        val signature = bob.sign(fullHash)
        val proofPayload = fullHash + signature
        val proofPacket = buildProofPacket(session.link.linkId!!, proofPayload)

        val proofPkt = parsePacket(proofPacket)
        assertNotNull(proofPkt)
        session.handlePacket(proofPkt, rssi = null)

        advanceTimeBy(500)

        assertTrue(
            sendResult.isCompleted,
            "valid proof must complete the awaiter",
        )
        assertTrue(
            sendResult.getCompleted(),
            "valid proof must complete the awaiter with delivered=true",
        )

        // Suppress unused warning
        @Suppress("UNUSED_EXPRESSION") dataPacketRaw
    }

    // ---- shared setup -----------------------------------------------------

    /**
     * Stand up a fully-active initiator-side LinkSession by running the
     * complete LINKREQUEST → LRPROOF handshake against a real responder
     * (Bob), then send one DATA packet via [LinkSession.sendDataAndAwaitProof]
     * in a child coroutine. Returns the session, the responder identity
     * (so tests can sign valid proofs), the captured outbound DATA packet
     * bytes, and its full hash (the value any proof must match).
     *
     * Stores the in-flight delivery deferred in [sendResult] so test
     * assertions can inspect whether the send succeeded.
     */
    private suspend fun setupLinkAndSendData(testScope: TestScope): SetupHandle {
        val crypto = TestVectors.crypto

        // Initiator: Alice — synthetic ephemeral keys; just call createInitiator.
        val (initiator, requestData) = Link.createInitiator(
            peerLongTermSigPub = TestVectors.Bob.sigPub,
            peerDestHash       = TestVectors.Bob.destHash,
            crypto             = crypto,
            nowMs              = 1_700_000_000_000L,
        )

        val reqPacketBytes = buildPacket(
            packetType = io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ,
            destHash   = TestVectors.Bob.destHash,
            payload    = requestData,
        )
        val reqPkt = parsePacket(reqPacketBytes)
        assertNotNull(reqPkt)
        initiator.setLinkIdFromPacket(reqPkt)

        // Responder: Bob — load identity + validate the request to derive
        // the LRPROOF that the initiator will accept.
        val bob = Identity(crypto)
        bob.loadFromPrivateKeys(
            encPriv = TestVectors.Bob.encPriv,
            sigPriv = TestVectors.Bob.sigPriv,
            ratchetPriv = TestVectors.Bob.ratchetPriv,
        )
        val (_, proofData) = Link.validateRequest(reqPkt, bob, crypto)

        // Initiator validates the proof — link transitions to ACTIVE,
        // derivedKey is populated, peerLongTermSigPub is already set
        // from createInitiator.
        val proofResult = initiator.validateProof(proofData, nowMs = 1_700_000_001_000L)
        assertTrue(proofResult is Link.LrProofResult.Success, "LRPROOF must succeed in setup")

        // Capture every packet the session emits via the sender lambda.
        val outbound = mutableListOf<ByteArray>()
        val session = LinkSession(
            link    = initiator,
            crypto  = crypto,
            sender  = { pkt -> outbound.add(pkt) },
            nowMs   = { 1_700_000_002_000L },
            logger  = { /* swallow */ },
        )

        // Kick off a send in a child coroutine — short timeout so the
        // forged-proof test can assert "not delivered" within the
        // runTest virtual clock.
        sendResult = testScope.async {
            session.sendDataAndAwaitProof(
                plaintext = "test message".encodeToByteArray(),
                timeoutMs = 250L,
            )
        }
        // Yield once so the send coroutine actually runs and registers
        // its awaiter in pendingDataProofs before tests feed proofs.
        kotlinx.coroutines.yield()
        // The send writes to outbound[]; capture the DATA packet and its
        // full hash so the test can build a matching proof.
        assertTrue(outbound.isNotEmpty(), "send must have emitted a DATA packet")
        val dataPacketRaw = outbound[0]
        val dataPkt = parsePacket(dataPacketRaw)
        assertNotNull(dataPkt)
        val fullHash = computePacketFullHash(dataPkt, crypto)

        return SetupHandle(session, bob, dataPacketRaw, fullHash)
    }

    private fun buildProofPacket(linkId: ByteArray, payload: ByteArray): ByteArray =
        buildPacket(
            headerType = HEADER_1,
            destType   = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash   = linkId,
            context    = CTX_NONE,
            payload    = payload,
        )

    /** Captures the in-flight `sendDataAndAwaitProof` deferred so test
     *  assertions can inspect its eventual value (true=delivered,
     *  false=timeout). Reset per @Test by being assigned in setup. */
    private lateinit var sendResult: kotlinx.coroutines.Deferred<Boolean>

    private data class SetupHandle(
        val session: LinkSession,
        val bob: Identity,
        val dataPacketRaw: ByteArray,
        val fullHash: ByteArray,
    )

    private fun TestScope.advanceTimeBy(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }
}
