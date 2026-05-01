package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.announce.extractDisplayName
import io.github.thatsfguy.reticulum.announce.parseAnnounce
import io.github.thatsfguy.reticulum.announce.validateAnnounce
import io.github.thatsfguy.reticulum.protocol.parsePacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnnounceTest {

    @Test fun parseAndValidateAliceAnnounce() = runTest {
        val crypto = TestVectors.crypto
        val packet = parsePacket(TestVectors.Announce.packet)
        assertNotNull(packet)
        // Header sanity: contextFlag=1 (ratchet present), packetType=ANNOUNCE(1)
        assertEquals(1, packet.contextFlag)
        assertEquals(1, packet.packetType)
        assertContentEquals(TestVectors.Alice.destHash, packet.destHash)

        val announce = parseAnnounce(packet.payload, packet.contextFlag, packet.destHash, crypto)
        assertNotNull(announce)
        assertContentEquals(TestVectors.Alice.publicKey, announce.publicKey)
        assertContentEquals(TestVectors.Alice.identityHash, announce.identityHash)
        assertNotNull(announce.ratchet)
        assertContentEquals(TestVectors.Alice.ratchetPub, announce.ratchet)

        // Display name check
        val name = extractDisplayName(announce.appData)
        assertEquals(TestVectors.Announce.displayName, name)

        // Signature must validate
        assertTrue(validateAnnounce(announce, crypto), "announce signature failed to validate")
    }
}
