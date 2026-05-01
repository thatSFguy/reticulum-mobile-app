package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IdentityCardTest {

    @Test fun roundTripWithRatchet() {
        val original = IdentityCard.Payload(
            destHash = "28b067284f739eead728dc74a7f0f064",
            publicKey = "124d33d0e72f42b678d9f62c612f6c2d8d9a74a396e83cf72eb7c00b049bf706" +
                        "0677ce05ff0dcd9dcea720bdad2f88202b2c8dc2a4649699f1e41524624ea1ec",
            ratchetPub = "6e762f063fa6360ff0673f95f15cc6de8d66ceebb36975ddc5b8f447b95e7120",
            displayName = "Alice Test",
        )
        val text = IdentityCard.encode(original)
        val decoded = IdentityCard.decode(text)
        assertEquals(original.destHash, decoded.destHash)
        assertEquals(original.publicKey, decoded.publicKey)
        assertEquals(original.ratchetPub, decoded.ratchetPub)
        assertEquals(original.displayName, decoded.displayName)
    }

    @Test fun ratchetOptional() {
        val original = IdentityCard.Payload(
            destHash = "00112233445566778899aabbccddeeff",
            publicKey = "ab".repeat(64),
            ratchetPub = null,
            displayName = "no ratchet",
        )
        val text = IdentityCard.encode(original)
        val decoded = IdentityCard.decode(text)
        assertNull(decoded.ratchetPub)
        assertEquals(original.publicKey, decoded.publicKey)
    }

    @Test fun rejectsShortDestHash() {
        val text = """{"destHash":"abc","publicKey":"${"a".repeat(128)}","displayName":"x"}"""
        assertFailsWith<IllegalArgumentException> { IdentityCard.decode(text) }
    }

    @Test fun rejectsShortPublicKey() {
        val text = """{"destHash":"${"0".repeat(32)}","publicKey":"deadbeef","displayName":"x"}"""
        assertFailsWith<IllegalArgumentException> { IdentityCard.decode(text) }
    }

    @Test fun ignoresUnknownKeys() {
        val text = """{"destHash":"${"0".repeat(32)}","publicKey":"${"a".repeat(128)}","extra":"yes","displayName":"x"}"""
        val decoded = IdentityCard.decode(text)
        assertEquals("x", decoded.displayName)
    }
}
