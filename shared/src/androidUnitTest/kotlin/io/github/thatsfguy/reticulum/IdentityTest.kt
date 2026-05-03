package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityTest {

    @Test fun aliceIdentityHashAndDestHash() = runTest {
        val crypto = TestVectors.crypto
        val id = Identity(crypto)
        id.loadFromPrivateKeys(
            encPriv = TestVectors.Alice.encPriv,
            sigPriv = TestVectors.Alice.sigPriv,
            ratchetPriv = TestVectors.Alice.ratchetPriv,
        )
        assertContentEquals(TestVectors.Alice.publicKey, id.publicKey)
        assertContentEquals(TestVectors.Alice.identityHash, id.hash!!)

        val dest = computeDestinationHash(crypto, "lxmf.delivery", id.hash!!)
        assertContentEquals(TestVectors.Alice.destHash, dest)
    }

    @Test fun rotateRatchetChangesRatchetButNotIdentity() = runTest {
        // Outbound-delivery bug 2026-05-03: every announce after the
        // first one in a session reuses the same ratchet pubkey, so
        // transit nodes dedupe (destHash, ratchet) and our subsequent
        // announces never propagate to siblings on the same TCP rnsd.
        // Identity.rotateRatchet() exists to swap in a fresh ratchet
        // without touching the long-term encryption / signing keys (so
        // our destHash and identity stay stable across rotations).
        val crypto = TestVectors.crypto
        val id = Identity(crypto)
        id.generate()

        val origEncPriv  = id.encPrivKey!!.copyOf()
        val origEncPub   = id.encPubKey!!.copyOf()
        val origSigPriv  = id.sigPrivKey!!.copyOf()
        val origSigPub   = id.sigPubKey!!.copyOf()
        val origHash     = id.hash!!.copyOf()
        val origRatchetPriv = id.ratchetPrivKey!!.copyOf()
        val origRatchetPub  = id.ratchetPubKey!!.copyOf()

        id.rotateRatchet()

        // Ratchet must change.
        assertNotNull(id.ratchetPrivKey)
        assertNotNull(id.ratchetPubKey)
        assertFalse(
            id.ratchetPrivKey!!.contentEquals(origRatchetPriv),
            "rotateRatchet must generate a new ratchet privkey",
        )
        assertFalse(
            id.ratchetPubKey!!.contentEquals(origRatchetPub),
            "rotateRatchet must generate a new ratchet pubkey",
        )

        // Long-term keys + identity hash must NOT change. If they did,
        // every announce would change destHash and break every cached
        // path on the network.
        assertContentEquals(origEncPriv, id.encPrivKey)
        assertContentEquals(origEncPub,  id.encPubKey)
        assertContentEquals(origSigPriv, id.sigPrivKey)
        assertContentEquals(origSigPub,  id.sigPubKey)
        assertContentEquals(origHash,    id.hash)
    }

    @Test fun rotateRatchetTwiceProducesThreeDistinctRatchets() = runTest {
        val crypto = TestVectors.crypto
        val id = Identity(crypto)
        id.generate()
        val r0 = id.ratchetPubKey!!.copyOf()
        id.rotateRatchet()
        val r1 = id.ratchetPubKey!!.copyOf()
        id.rotateRatchet()
        val r2 = id.ratchetPubKey!!.copyOf()
        assertFalse(r0.contentEquals(r1), "1st rotation must change ratchet")
        assertFalse(r1.contentEquals(r2), "2nd rotation must change ratchet again")
        assertFalse(r0.contentEquals(r2), "3 ratchets must all be distinct")
    }

    // Regression for the v0.1.36 mobile-to-mobile ratchet-race bug.
    // The receiver of an in-flight DATA encrypted to our prior ratchet
    // pub must still be able to decrypt — so rotation MUST stash the
    // outgoing privkey for one more cycle.
    @Test fun rotateRatchetPreservesPreviousPrivkey() = runTest {
        val crypto = TestVectors.crypto
        val id = Identity(crypto)
        id.generate()
        val priv0 = id.ratchetPrivKey!!.copyOf()

        id.rotateRatchet()

        assertNotNull(id.previousRatchetPrivKey)
        assertContentEquals(
            priv0, id.previousRatchetPrivKey,
            "after first rotation, previousRatchetPrivKey must equal the pre-rotation privkey",
        )
        assertFalse(
            id.ratchetPrivKey!!.contentEquals(priv0),
            "current ratchet privkey must have rotated to a fresh value",
        )
    }

    @Test fun rotateRatchetTwiceShiftsPreviousByOne() = runTest {
        val crypto = TestVectors.crypto
        val id = Identity(crypto)
        id.generate()
        val priv0 = id.ratchetPrivKey!!.copyOf()
        id.rotateRatchet()
        val priv1 = id.ratchetPrivKey!!.copyOf()
        id.rotateRatchet()
        // After 2 rotations: previous holds priv1 (the one we just rotated
        // out); priv0 is gone. We only keep ONE history slot, not a ring.
        assertContentEquals(priv1, id.previousRatchetPrivKey)
        assertFalse(id.ratchetPrivKey!!.contentEquals(priv0))
        assertFalse(id.ratchetPrivKey!!.contentEquals(priv1))
    }

    @Test fun bobIdentityHashAndDestHash() = runTest {
        val crypto = TestVectors.crypto
        val id = Identity(crypto)
        id.loadFromPrivateKeys(
            encPriv = TestVectors.Bob.encPriv,
            sigPriv = TestVectors.Bob.sigPriv,
            ratchetPriv = TestVectors.Bob.ratchetPriv,
        )
        assertContentEquals(TestVectors.Bob.publicKey, id.publicKey)
        assertContentEquals(TestVectors.Bob.identityHash, id.hash!!)

        val dest = computeDestinationHash(crypto, "lxmf.delivery", id.hash!!)
        assertContentEquals(TestVectors.Bob.destHash, dest)
    }
}
