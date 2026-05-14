package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.link.Link
import io.github.thatsfguy.reticulum.link.LinkState
import io.github.thatsfguy.reticulum.protocol.CTX_LINKIDENTIFY
import io.github.thatsfguy.reticulum.protocol.DEST_LINK
import io.github.thatsfguy.reticulum.protocol.PACKET_DATA
import io.github.thatsfguy.reticulum.protocol.buildPacket
import io.github.thatsfguy.reticulum.protocol.parsePacket
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin the SPEC §6.6 LINKIDENTIFY (context 0xFB) parse + verify on the
 * responder side. The bug fixed in v1.1.38 — tap-back reactions in
 * fwdsvc-hosted groups never reached the relay — was caused by the
 * responder having no way to recognise its link peer (we'd ignore
 * LINKIDENTIFY entirely, so the engine couldn't tell that a relayed
 * LXMF arrived via fwdsvc and route reactions back through it).
 *
 * Wire shape, from `RNS/Link.py:459-475` (sender) and `:1010-1026`
 * (receiver), test vector reconstructed against TestVectors.Alice:
 *
 *   ciphertext = TokenCrypto.encrypt(plaintext, link.derivedKey)
 *   plaintext  = public_key(64) || signature(64)           # 128 B
 *   signature  = Ed25519_sign(sigPriv, link_id(16) || public_key(64))
 *
 * On valid receipt the responder caches the initiator's lxmf.delivery
 * destHash on the session, which the engine then threads into
 * `arrivedViaDest` on inbound LXMF rows.
 */
class ResponderLinkIdentifyTest {

    @Test fun `valid LINKIDENTIFY caches peer lxmf_delivery destHash`() = runTest {
        val (session, link, _) = newResponderRig()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)

        // Signed_data per RNS/Link.py:469. Sign with Alice's long-term
        // sigPriv — the same key the destHash is derived from.
        val signedData = link.linkId!! + TestVectors.Alice.publicKey
        val signature = TestVectors.crypto.ed25519Sign(signedData, TestVectors.Alice.sigPriv)
        val plaintext = TestVectors.Alice.publicKey + signature
        assertEquals(128, plaintext.size, "LINKIDENTIFY plaintext is pubkey(64) + sig(64)")
        val ciphertext = tokenCrypto.encryptWithDerivedKey(plaintext, link.derivedKey!!)
        val pkt = parsePacket(buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_DATA,
            destHash   = link.linkId!!,
            context    = CTX_LINKIDENTIFY,
            payload    = ciphertext,
        ))!!

        assertNull(session.peerDestHashHex,
            "peerDestHashHex must start null — only LINKIDENTIFY sets it")
        session.handlePacket(pkt)

        // The expected destHash matches Alice's pre-computed
        // lxmf.delivery destination from TestVectors. Same recipe as
        // ReticulumEngine and the rest of the codebase use: SHA-256(
        // name_hash("lxmf.delivery") || identity_hash )[:16].
        val expectedHex = TestVectors.Alice.destHash.toHex()
        assertEquals(expectedHex, session.peerDestHashHex,
            "peer's lxmf.delivery destHash should be cached on successful LINKIDENTIFY")
    }

    @Test fun `LINKIDENTIFY with tampered signature is rejected`() = runTest {
        val (session, link, _) = newResponderRig()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)

        val signedData = link.linkId!! + TestVectors.Alice.publicKey
        val signature = TestVectors.crypto.ed25519Sign(signedData, TestVectors.Alice.sigPriv)
        // Flip one bit in the signature → Ed25519 verify fails.
        signature[0] = (signature[0].toInt() xor 0x01).toByte()
        val plaintext = TestVectors.Alice.publicKey + signature
        val ciphertext = tokenCrypto.encryptWithDerivedKey(plaintext, link.derivedKey!!)
        val pkt = parsePacket(buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_DATA,
            destHash   = link.linkId!!,
            context    = CTX_LINKIDENTIFY,
            payload    = ciphertext,
        ))!!

        session.handlePacket(pkt)
        assertNull(session.peerDestHashHex,
            "forged LINKIDENTIFY must NOT poison peerDestHashHex — otherwise outbound " +
                "reactions/replies could be misrouted by an on-path attacker.")
    }

    @Test fun `LINKIDENTIFY with wrong plaintext length is rejected`() = runTest {
        val (session, link, _) = newResponderRig()
        val tokenCrypto = TokenCrypto(TestVectors.crypto)

        // 96-byte plaintext (too short — half a signature missing).
        // Decrypts fine but length check at handleLinkIdentify rejects it.
        val plaintext = ByteArray(96) { (it + 1).toByte() }
        val ciphertext = tokenCrypto.encryptWithDerivedKey(plaintext, link.derivedKey!!)
        val pkt = parsePacket(buildPacket(
            destType   = DEST_LINK,
            packetType = PACKET_DATA,
            destHash   = link.linkId!!,
            context    = CTX_LINKIDENTIFY,
            payload    = ciphertext,
        ))!!

        session.handlePacket(pkt)
        assertNull(session.peerDestHashHex)
    }

    // ---- Helpers -----------------------------------------------------------

    private data class Rig(
        val session: ResponderLinkSession,
        val link: Link,
        val sentPackets: MutableList<ByteArray>,
    )

    private fun newResponderRig(): Rig {
        val crypto = TestVectors.crypto
        val link = Link(crypto)
        link.linkId = ByteArray(16) { (it + 0xa0).toByte() }
        val pseudoShared = ByteArray(32) { (it + 0xc0).toByte() }
        link.derivedKey = runBlocking {
            crypto.hkdfDerive(pseudoShared, link.linkId!!, ByteArray(0), 64)
        }
        link.state = LinkState.ACTIVE

        // Our (responder-side) identity — irrelevant to LINKIDENTIFY
        // parsing but required to construct the session. Bob plays the
        // role of "us"; the test peer that identifies is Alice.
        val identity = Identity(crypto)
        runBlocking { identity.loadFromPublicKey(TestVectors.Bob.publicKey) }

        val sentPackets = mutableListOf<ByteArray>()
        val session = ResponderLinkSession(
            link = link,
            identity = identity,
            crypto = crypto,
            sender = { sentPackets.add(it) },
            nowMs = { 1_700_000_000_000L },
            onLxmfReceived = { _, _, _, _, _ -> /* unused in these tests */ },
            onClose = { _, _ -> /* unused */ },
            logger = { },
        )
        return Rig(session, link, sentPackets)
    }
}
