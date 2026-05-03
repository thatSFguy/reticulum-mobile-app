package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.announce.buildRandomHash
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

    // Regression for the random_hash bug surfaced 2026-05-03 (verified
    // against RNS 1.2.0 in reticulum-specifications/SPEC.md §4):
    // random_hash[5..10] is NOT random — it carries the emission Unix
    // timestamp as a big-endian uint40. Pre-fix the field was 10 fully
    // random bytes; upstream RNS uses the timestamp portion as the
    // path-merge tiebreaker (`RNS/Transport.py:1700-1745`), so random
    // "timestamps" caused upstream's path table to churn unpredictably
    // and peers' replies to race against mis-ordered cache decisions.

    @Test fun `buildRandomHash places 5 random bytes followed by 5-byte BE timestamp`() {
        val random = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        // 0x123456789A = 78,187,493,530 — fits in 40 bits
        val ts = 0x123456789AL
        val out = buildRandomHash(random, ts)
        assertEquals(10, out.size)
        // First 5 bytes = exactly the random bytes we supplied
        for (i in 0 until 5) {
            assertEquals(random[i], out[i], "byte $i must equal random input")
        }
        // Last 5 bytes = big-endian uint40 encoding of ts
        assertEquals(0x12.toByte(), out[5], "BE byte 0 (high)")
        assertEquals(0x34.toByte(), out[6], "BE byte 1")
        assertEquals(0x56.toByte(), out[7], "BE byte 2")
        assertEquals(0x78.toByte(), out[8], "BE byte 3")
        assertEquals(0x9A.toByte(), out[9], "BE byte 4 (low)")
    }

    @Test fun `buildRandomHash with realistic Unix timestamp`() {
        // 2025-01-01 00:00:00 UTC = 1735689600 = 0x67748580
        // (still fits well within uint40's 0..2^40-1 range)
        val ts = 1735689600L
        val out = buildRandomHash(ByteArray(5), ts)
        // Big-endian uint40: high byte 0 (since ts < 2^32), then 0x67_74_85_80
        assertEquals(0x00.toByte(), out[5])
        assertEquals(0x67.toByte(), out[6])
        assertEquals(0x74.toByte(), out[7])
        assertEquals(0x85.toByte(), out[8])
        assertEquals(0x80.toByte(), out[9])
    }

    @Test fun `buildRandomHash rejects wrong-length random input`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            buildRandomHash(ByteArray(4), 1L)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            buildRandomHash(ByteArray(6), 1L)
        }
    }

    @Test fun `buildRandomHash rejects timestamp exceeding 40 bits`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            // 2^40 = 1_099_511_627_776 — first value that doesn't fit
            buildRandomHash(ByteArray(5), 0x100_00000000L)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            buildRandomHash(ByteArray(5), -1L)
        }
    }
}
