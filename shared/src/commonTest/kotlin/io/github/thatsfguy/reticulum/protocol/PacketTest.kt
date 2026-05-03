package io.github.thatsfguy.reticulum.protocol

import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

    @Test fun `parsePacket on HEADER_2 input shorter than 35 bytes returns null instead of crashing`() {
        // HEADER_2 needs flags+hops + 16 transportId + 16 destHash + context
        // = 35 bytes minimum. Garbage bytes from a relay (mid-disconnect
        // TCP / dropped KISS reassembly) with the HEADER_2 flag bit set
        // and length 19-34 must NOT crash the engine pump; parser must
        // defensively return null.
        val flags = ((1 and 0x01) shl 6).toByte()  // headerType = HEADER_2
        for (size in HEADER_MINSIZE..34) {
            val short = ByteArray(size).also { it[0] = flags }
            assertNull(
                parsePacket(short),
                "parsePacket(HEADER_2 size=$size) must return null, not throw or return partial",
            )
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

    // ---- HEADER_2 originator build (§2.3) ----------------------------------
    //
    // Reason: chronic Mob-App-outbound-doesn't-deliver bug. Upstream
    // RNS/Transport.py:1497 only forwards inbound DATA packets that
    // carry transport_id != None (HEADER_2). A leaf client whose path
    // table reports the destination via a transit relay MUST emit the
    // packet in HEADER_2 form, with the next-hop transport_id sitting
    // between the flags+hops bytes and the destination_hash. Confirmed
    // 2026-05-03 via offline replay-decrypt: our outbound crypto was
    // correct end-to-end; the receiver just never saw the bytes because
    // transport silently dropped HEADER_1 packets that needed routing.

    @Test fun `buildPacket with HEADER_2 prepends transport_id before destHash`() {
        val transportId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".hexToBytes()
        val destHash    = "0123456789abcdef0123456789abcdef".hexToBytes()
        val payload     = "cafebabe".hexToBytes()
        val raw = buildPacket(
            headerType = HEADER_2,
            transportType = TRANSPORT_TRANSPORT,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = destHash,
            transportId = transportId,
            context = CTX_NONE,
            payload = payload,
        )
        // Wire layout: flags(1) hops(1) transport_id(16) dest_hash(16) context(1) payload(...)
        assertEquals(2 + 16 + 16 + 1 + payload.size, raw.size)
        assertEquals(0, raw[1].toInt(), "hops byte = 0")
        assertContentEquals(transportId, raw.copyOfRange(2, 18))
        assertContentEquals(destHash, raw.copyOfRange(18, 34))
        assertEquals(CTX_NONE, raw[34].toInt() and 0xFF)
        assertContentEquals(payload, raw.copyOfRange(35, raw.size))
    }

    @Test fun `buildPacket HEADER_2 then parsePacket round-trips transport_id and destHash`() {
        val transportId = "72b75cf1beb00b22d11877355e2346b7".hexToBytes()
        val destHash    = "09c24aa87a14d369e7461dc02a126846".hexToBytes()
        val payload     = ByteArray(208) { it.toByte() }  // mock token payload size
        val raw = buildPacket(
            headerType = HEADER_2,
            transportType = TRANSPORT_TRANSPORT,
            destType = DEST_SINGLE,
            packetType = PACKET_DATA,
            destHash = destHash,
            transportId = transportId,
            payload = payload,
        )
        val parsed = parsePacket(raw)
        assertNotNull(parsed)
        assertEquals(HEADER_2, parsed.headerType)
        assertEquals(TRANSPORT_TRANSPORT, parsed.transportType)
        assertContentEquals(transportId, parsed.transportId)
        assertContentEquals(destHash, parsed.destHash)
        assertContentEquals(payload, parsed.payload)
    }

    @Test fun `buildPacket HEADER_2 without transportId throws`() {
        // Defensive: silently emitting a 19-byte packet with the
        // HEADER_2 flag bit but no transport_id slot would put garbage
        // in the destHash field on the wire. Refuse to build.
        assertFails {
            buildPacket(
                headerType = HEADER_2,
                destHash = ByteArray(16),
                transportId = null,
            )
        }
    }

    @Test fun `buildPacket HEADER_2 rejects wrong-size transportId`() {
        assertFails {
            buildPacket(
                headerType = HEADER_2,
                destHash = ByteArray(16),
                transportId = ByteArray(15),
            )
        }
        assertFails {
            buildPacket(
                headerType = HEADER_2,
                destHash = ByteArray(16),
                transportId = ByteArray(17),
            )
        }
    }

    @Test fun `buildPacket HEADER_1 ignores transportId argument`() {
        // Backwards compat: existing callers don't supply transportId.
        // If a caller passes one with HEADER_1, we silently drop it
        // rather than fail — HEADER_1 has no slot for it.
        val raw = buildPacket(
            headerType = HEADER_1,
            destHash = ByteArray(16),
            transportId = ByteArray(16) { 0xff.toByte() },
        )
        // Wire size is HEADER_1's: flags+hops+destHash+context = 19.
        assertEquals(19, raw.size)
    }
}
