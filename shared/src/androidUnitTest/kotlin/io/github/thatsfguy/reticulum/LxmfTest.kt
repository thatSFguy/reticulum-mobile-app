package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.lxmf.SignatureVariant
import io.github.thatsfguy.reticulum.lxmf.unpackMessage
import io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature
import io.github.thatsfguy.reticulum.protocol.parsePacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LxmfTest {

    @Test fun decryptAliceToBobOpportunistic() = runTest {
        val crypto = TestVectors.crypto

        // Parse the Reticulum packet
        val packet = parsePacket(TestVectors.LxmfSend.packet)
        assertNotNull(packet)
        assertContentEquals(TestVectors.Bob.destHash, packet.destHash)

        // Payload = ephPub(32) + token(IV+ct+HMAC). Use Bob's keys; try ratchet first.
        val tokenCrypto = TokenCrypto(crypto)
        val plaintext = tokenCrypto.decrypt(
            token = packet.payload,
            candidatePrivKeys = listOf(TestVectors.Bob.ratchetPriv, TestVectors.Bob.encPriv),
            ourIdentityHash = TestVectors.Bob.identityHash,
        )

        // Plaintext = source_hash(16) + signature(64) + msgpack
        val msg = unpackMessage(plaintext, TestVectors.Bob.destHash, crypto)
        assertContentEquals(TestVectors.Alice.destHash, msg.sourceHash)
        assertEquals(TestVectors.LxmfSend.content, msg.content)

        // Verify the signature using Alice's identity
        val alice = Identity(crypto)
        alice.loadFromPublicKey(TestVectors.Alice.publicKey)
        val variant = verifyMessageSignature(msg, alice, crypto)
        assertNotNull(variant, "LXMF signature did not validate against either variant")
        // We expect the stripped variant to win since the JS sender re-encodes too,
        // but ORIGINAL is also acceptable depending on encoder drift.
        assertEquals(true, variant == SignatureVariant.STRIPPED || variant == SignatureVariant.ORIGINAL)
    }
}
