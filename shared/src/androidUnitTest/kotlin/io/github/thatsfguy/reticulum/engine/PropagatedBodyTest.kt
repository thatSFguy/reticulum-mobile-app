package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.TokenCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

/**
 * SPEC §5.8.3: a `/get` body is `dest_hash(16) || token_ciphertext`, NOT the
 * LXMF plaintext. Until this test existed the engine fed the raw body straight
 * to unpackMessage, so every message retrieved from an upstream `lxmd` node
 * failed to unpack.
 */
class PropagatedBodyTest {

    private val crypto = TestVectors.crypto
    private val token = TokenCrypto(crypto)

    @Test fun `body is opened by stripping dest_hash and token-decrypting`() = runTest {
        val us = Identity(crypto).also { it.generate() }
        val ourDest = ByteArray(16) { 0x5A }
        val lxmfPlaintext = ByteArray(16 + 64 + 12) { it.toByte() }
        val body = ourDest + token.encrypt(lxmfPlaintext, us.encPubKey!!, us.hash!!)

        val opened = openPropagatedBody(body, ourDest, listOf(us.encPrivKey!!), us.hash!!, token)
        assertContentEquals(lxmfPlaintext, opened)
    }

    @Test fun `ratchet-encrypted body opens with the ratchet key`() = runTest {
        val us = Identity(crypto).also { it.generate() }
        val ourDest = ByteArray(16) { 0x11 }
        val lxmfPlaintext = ByteArray(100) { 7 }
        val body = ourDest + token.encrypt(lxmfPlaintext, us.ratchetPubKey!!, us.hash!!)

        val keys = listOfNotNull(us.ratchetPrivKey, us.encPrivKey)
        assertContentEquals(lxmfPlaintext, openPropagatedBody(body, ourDest, keys, us.hash!!, token))
    }

    @Test fun `body addressed to someone else is refused`() = runTest {
        val us = Identity(crypto).also { it.generate() }
        val body = ByteArray(16) { 0x22 } + token.encrypt(ByteArray(90), us.encPubKey!!, us.hash!!)
        assertFails {
            openPropagatedBody(body, ByteArray(16) { 0x33 }, listOf(us.encPrivKey!!), us.hash!!, token)
        }
    }
}
