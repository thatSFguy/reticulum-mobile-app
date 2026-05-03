package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.announce.extractDisplayName
import io.github.thatsfguy.reticulum.announce.parseAnnounce
import io.github.thatsfguy.reticulum.announce.resolveDisplayName
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

    // Regression for the BLE-side reply-attribution bug surfaced 2026-05-03:
    // a Ratdeck contact briefly showed as "LXMF delivery" after an inbound
    // reply because the next minimal re-announce (no app_data) overwrote
    // the existing real display name with the KnownDestinations label
    // fallback. Order must be: extracted > existing > knownLabel > "".

    @Test fun `resolveDisplayName prefers a freshly extracted name`() {
        assertEquals(
            "ratdeck1",
            resolveDisplayName(extracted = "ratdeck1", existing = "older", knownLabel = "LXMF delivery"),
        )
    }

    @Test fun `resolveDisplayName keeps existing name when extracted is null`() {
        assertEquals(
            "ratdeck1",
            resolveDisplayName(extracted = null, existing = "ratdeck1", knownLabel = "LXMF delivery"),
            "minimal re-announce must not clobber the real name we already had",
        )
    }

    @Test fun `resolveDisplayName keeps existing name when extracted is blank`() {
        assertEquals(
            "ratdeck1",
            resolveDisplayName(extracted = "", existing = "ratdeck1", knownLabel = "LXMF delivery"),
        )
    }

    @Test fun `resolveDisplayName falls back to knownLabel when no real name anywhere`() {
        assertEquals(
            "LXMF delivery",
            resolveDisplayName(extracted = null, existing = null, knownLabel = "LXMF delivery"),
        )
        assertEquals(
            "LXMF delivery",
            resolveDisplayName(extracted = null, existing = "", knownLabel = "LXMF delivery"),
        )
    }

    @Test fun `resolveDisplayName returns empty when all sources blank`() {
        assertEquals("", resolveDisplayName(null, null, null))
        assertEquals("", resolveDisplayName("", "", ""))
    }
}
