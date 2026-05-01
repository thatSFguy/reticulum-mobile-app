package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

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
