package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.announce.buildAnnounce
import io.github.thatsfguy.reticulum.announce.parseAnnounce
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.protocol.CTX_LRPROOF
import io.github.thatsfguy.reticulum.protocol.CTX_NONE
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.DEST_SINGLE
import io.github.thatsfguy.reticulum.protocol.HEADER_1
import io.github.thatsfguy.reticulum.protocol.PACKET_ANNOUNCE
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.PACKET_LINKREQ
import io.github.thatsfguy.reticulum.protocol.PACKET_PROOF
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Level-2 cross-validation harness. Mirrors the webclient's
 * tests/roundtrip.mjs: runs *our actual* protocol modules end to end
 * and writes a JSON document with two identities, an announce, an
 * encrypted LXMF, a LRPROOF, and a full handshake.
 *
 * The output file is consumed by tools/validate_vectors.py, which
 * reconstructs each artifact under upstream Python RNS and asserts
 * that it accepts everything we produce. CI runs both. If our code
 * regresses against RNS, the validator fails.
 *
 * The test also does a battery of self-consistency checks before
 * emitting the file so locally-broken vectors don't reach Python.
 */
class RoundtripGenerator {

    @Test fun generateVectors() = runTest {
        val crypto = TestVectors.crypto

        // ---- Two fresh identities ------------------------------------------
        val alice = Identity(crypto).also { it.generate() }
        val aliceDest = computeDestinationHash(crypto, "lxmf.delivery", alice.hash!!)

        val bob = Identity(crypto).also { it.generate() }
        val bobDest = computeDestinationHash(crypto, "lxmf.delivery", bob.hash!!)

        // ---- Scenario A: Alice's ratchet announce --------------------------
        val aliceName = "AliceTest"
        val appData = io.github.thatsfguy.reticulum.codec.MessagePack.encode(
            listOf(aliceName.encodeToByteArray(), 0)
        )
        val (announceDestHash, announcePayload, hasRatchet) = buildAnnounce(
            identity = alice,
            crypto = crypto,
            appName = "lxmf.delivery",
            appData = appData,
            ratchetPub = alice.ratchetPubKey,
        )
        assertContentEquals(aliceDest, announceDestHash)
        assertTrue(hasRatchet)

        val announcePacket = buildPacket(
            headerType = HEADER_1,
            contextFlag = 1,
            destType = DEST_SINGLE,
            packetType = PACKET_ANNOUNCE,
            destHash = announceDestHash,
            context = CTX_NONE,
            payload = announcePayload,
        )

        // Self-parse — catches encoding bugs before Python touches it.
        val parsedSelf = parsePacket(announcePacket)
        assertNotNull(parsedSelf)
        val parsedAnnounce = parseAnnounce(parsedSelf.payload, parsedSelf.contextFlag, parsedSelf.destHash, crypto)
        assertNotNull(parsedAnnounce)
        assertContentEquals(alice.ratchetPubKey, parsedAnnounce.ratchet)

        // ---- Scenario B: Alice → Bob opportunistic LXMF --------------------
        val content = "hello from RoundtripGenerator.kt"
        val lxmfPlain = packMessage(
            sourceIdentity = alice,
            destHash = bobDest,
            sourceHash = aliceDest,
            title = "",
            content = content,
            timestampSeconds = 1_700_000_000.0,
            crypto = crypto,
        )
        val tokenCrypto = TokenCrypto(crypto)
        val encrypted = tokenCrypto.encrypt(lxmfPlain, bob.ratchetPubKey!!, bob.hash!!)

        val lxmfPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = bobDest,
            context = CTX_NONE,
            payload = encrypted,
        )

        // ---- Scenario B': parse-announce-then-encrypt (production path) ---
        // This mirrors what the running app does: ingest a peer's announce,
        // store its parsed publicKey / ratchet / identityHash, then later
        // use those exact stored values to encrypt a message back. The
        // standard scenario B uses the locally-known Identity object
        // directly — so a bug in handleAnnounce's field extraction would
        // be invisible there and would only show in production.
        //
        // The peer here is Bob — we already built his announce above as
        // part of A (well, we built Alice's; let's also build Bob's so
        // we can ingest it):
        val bobName = "BobTest"
        val bobAppData = io.github.thatsfguy.reticulum.codec.MessagePack.encode(
            listOf(bobName.encodeToByteArray(), 0)
        )
        val (bobAnnounceDest, bobAnnouncePayload, bobHasRatchet) = buildAnnounce(
            identity = bob,
            crypto = crypto,
            appName = "lxmf.delivery",
            appData = bobAppData,
            ratchetPub = bob.ratchetPubKey,
        )
        assertContentEquals(bobDest, bobAnnounceDest)
        assertTrue(bobHasRatchet)
        val bobAnnouncePacket = buildPacket(
            headerType = HEADER_1,
            contextFlag = 1,
            destType = DEST_SINGLE,
            packetType = PACKET_ANNOUNCE,
            destHash = bobAnnounceDest,
            context = CTX_NONE,
            payload = bobAnnouncePayload,
        )
        // Parse it back through the same code path handleAnnounce uses.
        val bobAnnounceSelfParse = parsePacket(bobAnnouncePacket)
        assertNotNull(bobAnnounceSelfParse)
        val bobParsedAnnounce = parseAnnounce(
            bobAnnounceSelfParse.payload,
            bobAnnounceSelfParse.contextFlag,
            bobAnnounceSelfParse.destHash,
            crypto,
        )
        assertNotNull(bobParsedAnnounce)
        // These four fields are exactly what StoredDestination caches
        // and what sendMessage reads back at encrypt time.
        val parsedBobPublicKey = bobParsedAnnounce.publicKey         // 64 bytes (X25519 || Ed25519)
        val parsedBobRatchetPub = bobParsedAnnounce.ratchet          // 32 bytes — what we encrypt to
        val parsedBobIdentityHash = bobParsedAnnounce.identityHash   // 16 bytes — HKDF salt
        val parsedBobDestHash = bobAnnounceSelfParse.destHash        // 16 bytes — packet dest field

        // Self-consistency: the parsed values must match Bob's known
        // identity, otherwise handleAnnounce is silently corrupting the
        // peer's keys before they ever reach sendMessage.
        assertContentEquals(bob.publicKey,    parsedBobPublicKey,    "publicKey drifted through parseAnnounce")
        assertContentEquals(bob.ratchetPubKey, parsedBobRatchetPub,  "ratchet drifted through parseAnnounce")
        assertContentEquals(bob.hash,         parsedBobIdentityHash, "identityHash drifted through parseAnnounce")
        assertContentEquals(bobDest,          parsedBobDestHash,     "destHash drifted through parseAnnounce")

        // Now encrypt using ONLY the parsed values, exactly as
        // sendMessage does in production (recipientEncPub = ratchet ?:
        // pub[:32], recipientIdHash = identityHash).
        val productionPlain = packMessage(
            sourceIdentity = alice,
            destHash = parsedBobDestHash,
            sourceHash = aliceDest,
            title = "",
            content = "hello via parsed announce",
            timestampSeconds = 1_700_000_001.0,
            crypto = crypto,
        )
        val productionEncPub = parsedBobRatchetPub ?: parsedBobPublicKey.copyOfRange(0, 32)
        assertNotNull(productionEncPub)
        val productionToken = tokenCrypto.encrypt(productionPlain, productionEncPub, parsedBobIdentityHash)
        val productionPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = parsedBobDestHash,
            context = CTX_NONE,
            payload = productionToken,
        )

        // ---- Scenario C: mock initiator → Alice (responder) ----------------
        // Fixed peer keys so the harness is reproducible across runs.
        val peerX = "4a4b4c4d4e4f5051525354555657585960616263646566676869707172737475".hexToBytes()
        val peerSig = "8081828384858687888990919293949596979899aabbccddeeff000102030405".hexToBytes()
        val signalling = byteArrayOf(0x20.toByte(), 0x01.toByte(), 0xF4.toByte()) // mtu=500 mode=1
        val lrData = peerX + peerSig + signalling

        val lrPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_LINKREQ,
            destHash = aliceDest,
            context = CTX_NONE,
            payload = lrData,
        )
        val parsedLrReq = parsePacket(lrPacket)
        assertNotNull(parsedLrReq)
        val (responderLink, _) = Link.validateRequest(parsedLrReq, alice, crypto)

        val lrProofPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash = responderLink.linkId!!,
            context = CTX_LRPROOF,
            payload = responderLink.cachedProofData!!,
        )

        // signed_data = link_id + ourEphX25519Pub + ourLongTermSigPub + signalling
        val signedData = responderLink.linkId!! +
            responderLink.ourX25519Pub!! +
            responderLink.ourSigPub!! +
            responderLink.signallingBytes!!

        // ---- Scenario D: Alice initiates link to Bob, full handshake -------
        val (aliceInitiator, aliceLrData) = Link.createInitiator(
            peerLongTermSigPub = bob.sigPubKey!!,
            peerDestHash = bobDest,
            crypto = crypto,
            nowMs = 1_700_000_000_000L,
        )
        val aliceLrPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_LINKREQ,
            destHash = bobDest,
            context = CTX_NONE,
            payload = aliceLrData,
        )
        val parsedAliceLr = parsePacket(aliceLrPacket)
        assertNotNull(parsedAliceLr)
        aliceInitiator.setLinkIdFromPacket(parsedAliceLr)

        val (bobResponder, bobProofData) = Link.validateRequest(parsedAliceLr, bob, crypto)
        assertContentEquals(aliceInitiator.linkId, bobResponder.linkId)

        val bobLrProofPacket = buildPacket(
            headerType = HEADER_1,
            destType = DEST_LINK,
            packetType = PACKET_PROOF,
            destHash = bobResponder.linkId!!,
            context = CTX_LRPROOF,
            payload = bobProofData,
        )

        val parsedBobProof = parsePacket(bobLrProofPacket)
        assertNotNull(parsedBobProof)
        val proofResult = aliceInitiator.validateProof(parsedBobProof.payload, nowMs = 1_700_000_001_000L)
        assertTrue(proofResult is Link.LrProofResult.Success, "validateProof failed: $proofResult")

        assertContentEquals(aliceInitiator.derivedKey, bobResponder.derivedKey)

        val linkPlain = "hi over link from alice".encodeToByteArray()
        val linkCipher = tokenCrypto.encryptWithDerivedKey(linkPlain, aliceInitiator.derivedKey!!)
        val linkRoundTrip = tokenCrypto.decryptWithDerivedKey(linkCipher, bobResponder.derivedKey!!)
        assertEquals("hi over link from alice", linkRoundTrip.decodeToString())

        // ---- Emit JSON -----------------------------------------------------
        val rttSeconds = (proofResult as Link.LrProofResult.Success).rtt
        val json = buildJson(
            alice = identityRecord(alice, aliceDest),
            bob = identityRecord(bob, bobDest),
            announceDisplayName = aliceName,
            announceHasRatchet = hasRatchet,
            announcePacket = announcePacket,
            lxmfContent = content,
            lxmfPacket = lxmfPacket,
            lxmfViaAnnounceContent = "hello via parsed announce",
            lxmfViaAnnouncePacket = productionPacket,
            bobAnnouncePacket = bobAnnouncePacket,
            lrPacket = lrPacket,
            lrLinkId = responderLink.linkId!!,
            lrProofPacket = lrProofPacket,
            lrSignedData = signedData,
            handshakeLinkIdInitiator = aliceInitiator.linkId!!,
            handshakeLinkIdResponder = bobResponder.linkId!!,
            handshakeDerivedKey = aliceInitiator.derivedKey!!,
            handshakeRttSeconds = rttSeconds,
            handshakeLinkReqPacket = aliceLrPacket,
            handshakeLrProofPacket = bobLrProofPacket,
            handshakeTestCiphertext = linkCipher,
            handshakeTestPlaintext = "hi over link from alice",
        )

        val outPath = System.getenv("ROUNDTRIP_OUT")
            ?: "${System.getProperty("user.dir")}/build/roundtrip-vectors.json"
        val outFile = File(outPath)
        outFile.parentFile?.mkdirs()
        outFile.writeText(json)
        println("[RoundtripGenerator] wrote ${outFile.length()} bytes to $outPath")
    }

    private data class IdentityRecord(
        val encPriv: ByteArray, val sigPriv: ByteArray, val ratchetPriv: ByteArray,
        val encPub: ByteArray, val sigPub: ByteArray, val ratchetPub: ByteArray,
        val publicKey: ByteArray, val identityHash: ByteArray, val destHash: ByteArray,
    )

    private fun identityRecord(id: Identity, destHash: ByteArray) = IdentityRecord(
        encPriv = id.encPrivKey!!,
        sigPriv = id.sigPrivKey!!,
        ratchetPriv = id.ratchetPrivKey!!,
        encPub = id.encPubKey!!,
        sigPub = id.sigPubKey!!,
        ratchetPub = id.ratchetPubKey!!,
        publicKey = id.publicKey,
        identityHash = id.hash!!,
        destHash = destHash,
    )

    @Suppress("LongParameterList")
    private fun buildJson(
        alice: IdentityRecord, bob: IdentityRecord,
        announceDisplayName: String, announceHasRatchet: Boolean, announcePacket: ByteArray,
        lxmfContent: String, lxmfPacket: ByteArray,
        lxmfViaAnnounceContent: String, lxmfViaAnnouncePacket: ByteArray, bobAnnouncePacket: ByteArray,
        lrPacket: ByteArray, lrLinkId: ByteArray, lrProofPacket: ByteArray, lrSignedData: ByteArray,
        handshakeLinkIdInitiator: ByteArray, handshakeLinkIdResponder: ByteArray,
        handshakeDerivedKey: ByteArray, handshakeRttSeconds: Double,
        handshakeLinkReqPacket: ByteArray, handshakeLrProofPacket: ByteArray,
        handshakeTestCiphertext: ByteArray, handshakeTestPlaintext: String,
    ): String = buildString {
        append("{\n")
        append("  \"version\": 1,\n")
        append("  \"alice\": "); appendIdentity(alice); append(",\n")
        append("  \"bob\": "); appendIdentity(bob); append(",\n")
        append("  \"announce\": {\n")
        append("    \"displayName\": \"").append(announceDisplayName).append("\",\n")
        append("    \"hasRatchet\": ").append(announceHasRatchet).append(",\n")
        append("    \"packet\": \"").append(announcePacket.toHex()).append("\"\n")
        append("  },\n")
        append("  \"lxmf_send\": {\n")
        append("    \"from\": \"alice\",\n")
        append("    \"to\": \"bob\",\n")
        append("    \"content\": \"").append(jsonEscape(lxmfContent)).append("\",\n")
        append("    \"packet\": \"").append(lxmfPacket.toHex()).append("\"\n")
        append("  },\n")
        append("  \"lxmf_send_via_announce\": {\n")
        append("    \"from\": \"alice\",\n")
        append("    \"to\": \"bob\",\n")
        append("    \"content\": \"").append(jsonEscape(lxmfViaAnnounceContent)).append("\",\n")
        append("    \"packet\": \"").append(lxmfViaAnnouncePacket.toHex()).append("\",\n")
        append("    \"announcePacket\": \"").append(bobAnnouncePacket.toHex()).append("\"\n")
        append("  },\n")
        append("  \"link\": {\n")
        append("    \"linkRequestPacket\": \"").append(lrPacket.toHex()).append("\",\n")
        append("    \"linkId\": \"").append(lrLinkId.toHex()).append("\",\n")
        append("    \"lrProofPacket\": \"").append(lrProofPacket.toHex()).append("\",\n")
        append("    \"signedData\": \"").append(lrSignedData.toHex()).append("\"\n")
        append("  },\n")
        append("  \"link_handshake\": {\n")
        append("    \"linkIdInitiator\": \"").append(handshakeLinkIdInitiator.toHex()).append("\",\n")
        append("    \"linkIdResponder\": \"").append(handshakeLinkIdResponder.toHex()).append("\",\n")
        append("    \"derivedKey\": \"").append(handshakeDerivedKey.toHex()).append("\",\n")
        append("    \"rttSeconds\": ").append(handshakeRttSeconds).append(",\n")
        append("    \"linkReqPacket\": \"").append(handshakeLinkReqPacket.toHex()).append("\",\n")
        append("    \"lrProofPacket\": \"").append(handshakeLrProofPacket.toHex()).append("\",\n")
        append("    \"testCiphertext\": \"").append(handshakeTestCiphertext.toHex()).append("\",\n")
        append("    \"testPlaintext\": \"").append(jsonEscape(handshakeTestPlaintext)).append("\"\n")
        append("  }\n")
        append("}\n")
    }

    private fun StringBuilder.appendIdentity(id: IdentityRecord) {
        append("{\n")
        append("    \"encPriv\": \"").append(id.encPriv.toHex()).append("\",\n")
        append("    \"sigPriv\": \"").append(id.sigPriv.toHex()).append("\",\n")
        append("    \"ratchetPriv\": \"").append(id.ratchetPriv.toHex()).append("\",\n")
        append("    \"encPub\": \"").append(id.encPub.toHex()).append("\",\n")
        append("    \"sigPub\": \"").append(id.sigPub.toHex()).append("\",\n")
        append("    \"ratchetPub\": \"").append(id.ratchetPub.toHex()).append("\",\n")
        append("    \"publicKey\": \"").append(id.publicKey.toHex()).append("\",\n")
        append("    \"identityHash\": \"").append(id.identityHash.toHex()).append("\",\n")
        append("    \"destHash\": \"").append(id.destHash.toHex()).append("\"\n")
        append("  }")
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"'  -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
}
