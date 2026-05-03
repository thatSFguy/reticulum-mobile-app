package io.github.thatsfguy.reticulum.protocol

import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

/**
 * Boundary + round-trip coverage for [parsePacket] and [buildPacket].
 *
 * The parser is the hot path for every byte that arrives off the wire
 * — a malformed packet from a noisy relay (mid-disconnect TCP, dropped
 * KISS frame across BLE chunks) must not crash the engine pump. These
 * tests pin "returns null" behavior on truncation so a refactor that
 * accidentally throws would be caught.
 */
class PacketTest {

    // ---- Truncation / boundary returns null --------------------------------

    @Test fun `parsePacket on empty input returns null`() {
        assertNull(parsePacket(ByteArray(0)))
    }

    @Test fun `parsePacket on input shorter than HEADER_MINSIZE returns null`() {
        // HEADER_MINSIZE is 19 (flags + hops + 16 destHash + context).
        // Anything shorter MUST return null, not throw or read past end.
        for (size in 0 until HEADER_MINSIZE) {
            assertNull(
                parsePacket(ByteArray(size)),
                "parsePacket(ByteArray($size)) should be null but was non-null",
            )
        }
    }

    @Test fun `parsePacket on HEADER_2 input shorter than 35 bytes still returns null or parses with zero payload`() {
        // HEADER_2 needs flags+hops + 16 transportId + 16 destHash + context = 35 bytes minimum.
        // The current parser checks data.size >= HEADER_MINSIZE (19) which is the
        // HEADER_1 minimum, then trusts the headerType bit. A HEADER_2 packet of
        // 19-34 bytes will read past end. Test pins current behavior so a
        // refactor doesn't accidentally regress to a crash.
        val flags = ((1 and 0x01) shl 6).toByte()  // headerType = HEADER_2
        for (size in HEADER_MINSIZE..34) {
            val short = ByteArray(size).also { it[0] = flags }
            // Either null (defensive — better) or non-null with zero/garbage
            // payload (current behavior). Just must not throw.
            runCatching { parsePacket(short) }.getOrThrow()
        }
    }

    // ---- Round-trip integrity ---------------------------------------------

    @Test fun `buildPacket then parsePacket recovers every field for a HEADER_1 announce`() {
        val destHash = "0123456789abcdef0123456789abcdef".hexToBytes()
        val payload = "deadbeefdeadbeefdeadbeefdeadbeef".hexToBytes()
        val raw = buildPacket(
            headerType = HEADER_1,
            contextFlag = 1,
            transportType = TRANSPORT_BROADCAST,
            destType = DEST_SINGLE,
            packetType = PACKET_ANNOUNCE,
            hops = 0,
            destHash = destHash,
            context = CTX_NONE,
            payload = payload,
        )
        val parsed = parsePacket(raw)
        assertNotNull(parsed)
        assertEquals(HEADER_1, parsed.headerType)
        assertEquals(1, parsed.contextFlag)
        assertEquals(TRANSPORT_BROADCAST, parsed.transportType)
        assertEquals(DEST_SINGLE, parsed.destType)
        assertEquals(PACKET_ANNOUNCE, parsed.packetType)
        assertEquals(0, parsed.hops)
        assertContentEquals(destHash, parsed.destHash)
        assertNull(parsed.transportId, "HEADER_1 packets carry no transport_id")
        assertEquals(CTX_NONE, parsed.context)
        assertContentEquals(payload, parsed.payload)
    }

    @Test fun `buildPacket then parsePacket recovers every field for a HEADER_1 PROOF`() {
        // Mirrors the wire layout we send for opportunistic delivery proofs.
        val destHash = "fedcba9876543210fedcba9876543210".hexToBytes()
        val payload = ByteArray(64) { it.toByte() }  // pretend 64-byte signature
        val raw = buildPacket(
            headerType = HEADER_1,
            destType = DEST_SINGLE,
            packetType = PACKET_PROOF,
            destHash = destHash,
            context = CTX_NONE,
            payload = payload,
        )
        val parsed = parsePacket(raw)
        assertNotNull(parsed)
        assertEquals(PACKET_PROOF, parsed.packetType)
        assertEquals(DEST_SINGLE, parsed.destType)
        assertEquals(CTX_NONE, parsed.context)
        assertContentEquals(payload, parsed.payload)
    }

    @Test fun `flags byte encodes packetType in bits 1-0 and destType in bits 3-2`() {
        // Cross-check that buildPacket's flag composition matches what
        // parsePacket pulls back out — without this, a bug-fix on one side
        // could silently misalign the other.
        for (packetType in listOf(PACKET_DATA, PACKET_ANNOUNCE, PACKET_LINKREQ, PACKET_PROOF)) {
            for (destType in listOf(DEST_SINGLE, DEST_GROUP, DEST_PLAIN, DEST_LINK)) {
                val raw = buildPacket(
                    packetType = packetType,
                    destType = destType,
                    destHash = ByteArray(16),
                )
                val parsed = parsePacket(raw)
                assertNotNull(parsed, "packetType=$packetType destType=$destType failed to parse")
                assertEquals(
                    packetType, parsed.packetType,
                    "packetType round-trip mismatch for ($packetType, $destType)",
                )
                assertEquals(
                    destType, parsed.destType,
                    "destType round-trip mismatch for ($packetType, $destType)",
                )
            }
        }
    }

    @Test fun `contextFlag bit is preserved through round-trip`() {
        val withFlag = buildPacket(
            contextFlag = 1,
            destHash = ByteArray(16),
        )
        val withoutFlag = buildPacket(
            contextFlag = 0,
            destHash = ByteArray(16),
        )
        assertEquals(1, parsePacket(withFlag)?.contextFlag)
        assertEquals(0, parsePacket(withoutFlag)?.contextFlag)
    }

    @Test fun `payload of zero bytes round-trips to empty payload`() {
        // Some PROOF emissions / KEEPALIVEs are zero-length payloads.
        val raw = buildPacket(
            destHash = ByteArray(16),
            payload = ByteArray(0),
        )
        val parsed = parsePacket(raw)
        assertNotNull(parsed)
        assertEquals(0, parsed.payload.size)
    }
}
