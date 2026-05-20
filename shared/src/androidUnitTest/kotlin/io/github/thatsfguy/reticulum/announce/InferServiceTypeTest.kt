package io.github.thatsfguy.reticulum.announce

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.transport.hexToBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * `inferServiceType` reverse-looks-up a destination's service type from
 * its 16-byte destination hash plus the 64-byte public key — the exact
 * scenario a QR-scan / cut-and-paste card presents.
 *
 * Tester report (2026-05-20): an RRC-hub QR scanned via Settings → Add
 * by hash showed up in Nodes/Contacts first and only migrated to the
 * Rooms tab a minute later when the actual hub's own announce arrived.
 * Cause: applyIdentityCard hard-coded `appName = "lxmf.delivery"`
 * because the IdentityCard wire format carries no service-type field.
 * Reverse-lookup against KNOWN_DESTINATIONS at scan time closes that gap.
 */
class InferServiceTypeTest {

    private val crypto = TestVectors.crypto

    @Test
    fun `recognises an lxmf delivery destination`() = runBlocking {
        // Alice's destination hash from the JS reference test vectors
        // is the lxmf.delivery destination for her identity. The
        // inverse lookup should pick it up.
        val match = inferServiceType(
            destHash = TestVectors.Alice.destHash,
            publicKey = TestVectors.Alice.publicKey,
            crypto = crypto,
        )
        assertNotNull(match, "Alice's destHash + publicKey should resolve to a known service")
        assertEquals("lxmf.delivery", match.name)
        assertEquals("LXMF delivery", match.label)
    }

    @Test
    fun `recognises an rrc hub destination`() = runBlocking {
        // Construct an rrc.hub destination for Alice's identity, then
        // verify inferServiceType resolves it. This exercises the case
        // the tester actually hit — a QR-shared RRC hub being correctly
        // categorised at scan time instead of after the next announce.
        val rrcNameHash = "ac9fd3a81e4036f86e1d".hexToBytes()
        val identityHash = crypto.truncatedHash(TestVectors.Alice.publicKey, 16)
        val rrcDestHash = crypto.truncatedHash(rrcNameHash + identityHash, 16)

        val match = inferServiceType(
            destHash = rrcDestHash,
            publicKey = TestVectors.Alice.publicKey,
            crypto = crypto,
        )
        assertNotNull(match, "rrc.hub destHash + publicKey should resolve")
        assertEquals("rrc.hub", match.name)
        assertEquals("RRC hub", match.label)
    }

    @Test
    fun `returns null when nothing matches`() = runBlocking {
        // A wrong destHash for Alice's identity — same shape, no
        // valid service mapping. Caller is expected to fall back to
        // a sensible default (lxmf.delivery in applyIdentityCard).
        val nonsense = ByteArray(16) { 0xAA.toByte() }
        val match = inferServiceType(
            destHash = nonsense,
            publicKey = TestVectors.Alice.publicKey,
            crypto = crypto,
        )
        assertNull(match, "random destHash must not collide with any known service")
    }

    @Test
    fun `rejects wrong-size inputs`() = runBlocking {
        val publicKey = TestVectors.Alice.publicKey
        try {
            inferServiceType(
                destHash = ByteArray(15),    // wrong length
                publicKey = publicKey,
                crypto = crypto,
            )
            error("expected IllegalArgumentException for wrong-size destHash")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            inferServiceType(
                destHash = ByteArray(16),
                publicKey = ByteArray(63),   // wrong length
                crypto = crypto,
            )
            error("expected IllegalArgumentException for wrong-size publicKey")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
