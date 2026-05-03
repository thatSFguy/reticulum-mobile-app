package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import io.github.thatsfguy.reticulum.lxmf.SignatureVariant
import io.github.thatsfguy.reticulum.lxmf.packMessage
import io.github.thatsfguy.reticulum.lxmf.unpackMessage
import io.github.thatsfguy.reticulum.lxmf.verifyMessageSignature
import io.github.thatsfguy.reticulum.protocol.parsePacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ---- Edge cases — added per the Tier 2 plan -----------------------------
    // These pin malformed-input behavior so a corrupted LXMF blob from the
    // mesh produces a clear error instead of silent data loss.

    @Test fun `unpackMessage throws clearly when plaintext is too short`() = runTest {
        val crypto = TestVectors.crypto
        val tooShort = ByteArray(16 + 64) // missing the trailing msgpack
        assertFailsWith<IllegalArgumentException>(
            "unpackMessage must reject plaintext shorter than 16+64+1; got silent garbage",
        ) {
            unpackMessage(tooShort, TestVectors.Bob.destHash, crypto)
        }
    }

    @Test fun `unpackMessage throws when msgpack body is not a list`() = runTest {
        val crypto = TestVectors.crypto
        // sourceHash(16) + signature(64) + msgpack(map instead of list)
        val notAList = TestVectors.Alice.destHash +
            ByteArray(64) +
            MessagePack.encode(mapOf("not" to "a list"))
        assertFailsWith<IllegalArgumentException>(
            "unpackMessage must reject msgpack that decodes to a map (not the expected list)",
        ) {
            unpackMessage(notAList, TestVectors.Bob.destHash, crypto)
        }
    }

    @Test fun `unpackMessage throws when msgpack list has fewer than 4 elements`() = runTest {
        val crypto = TestVectors.crypto
        val shortList = TestVectors.Alice.destHash +
            ByteArray(64) +
            MessagePack.encode(listOf<Any?>(1700000000.0, "title", "content"))  // missing fields map
        assertFailsWith<IllegalArgumentException>(
            "unpackMessage must reject msgpack lists with < 4 elements",
        ) {
            unpackMessage(shortList, TestVectors.Bob.destHash, crypto)
        }
    }

    @Test fun `unpackMessage decodes title and content even when sender used String type`() = runTest {
        // Some senders msgpack-encode title/content as `str` instead of `bin`.
        // Our decodeField helper handles both. Lock it down.
        val crypto = TestVectors.crypto
        val mp = MessagePack.encode(listOf<Any?>(
            1700000000.0,
            "string-typed title",   // String → msgpack str
            "string-typed content", // String → msgpack str
            emptyMap<Any?, Any?>(),
        ))
        val plaintext = TestVectors.Alice.destHash + ByteArray(64) + mp
        val msg = unpackMessage(plaintext, TestVectors.Bob.destHash, crypto)
        assertEquals("string-typed title", msg.title)
        assertEquals("string-typed content", msg.content)
    }

    @Test fun `verifyMessageSignature returns null when sender pubkey does not match`() = runTest {
        val crypto = TestVectors.crypto

        // Build a real, signed LXMF using Alice's identity — so the signature
        // is well-formed, just signed by Alice not Bob.
        val alice = Identity(crypto)
        alice.loadFromPrivateKeys(TestVectors.Alice.encPriv, TestVectors.Alice.sigPriv, TestVectors.Alice.ratchetPriv)
        val bob = Identity(crypto)
        bob.loadFromPrivateKeys(TestVectors.Bob.encPriv, TestVectors.Bob.sigPriv, TestVectors.Bob.ratchetPriv)

        val plaintext = packMessage(
            sourceIdentity = alice,
            destHash = TestVectors.Bob.destHash,
            sourceHash = TestVectors.Alice.destHash,
            title = "",
            content = "hi from alice",
            timestampSeconds = 1700000000.0,
            crypto = crypto,
        )
        val msg = unpackMessage(plaintext, TestVectors.Bob.destHash, crypto)

        // Try to verify against the WRONG identity (Bob's pub instead of Alice's).
        val wrongIdentity = Identity(crypto)
        wrongIdentity.loadFromPublicKey(TestVectors.Bob.publicKey)
        val variant = verifyMessageSignature(msg, wrongIdentity, crypto)
        assertNull(variant, "verify must return null (not throw) when sender pub doesn't match")
    }
}
