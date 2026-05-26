package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoraMeshKissTest {

    /** Well-known check value for CRC-16/CCITT-FALSE: the ASCII string
     *  "123456789" hashes to 0x29B1. Lifted from the standard test
     *  vector that every CCITT-FALSE impl agrees on; if our impl
     *  matches this, it'll match the firmware's. */
    @Test fun crc16CheckValue() {
        val data = "123456789".encodeToByteArray()
        assertEquals(0x29B1, crc16CcittFalse(data))
    }

    @Test fun emptyPayloadFraming() {
        // NODE_INFO_REQ has zero payload. The body is just the CMD
        // byte (0x03), CRC is over that single byte.
        val frame = buildLoraMeshFrame(LM_CMD_NODE_INFO_REQ)
        val cmdByte = byteArrayOf(LM_CMD_NODE_INFO_REQ.toByte())
        val crc = crc16CcittFalse(cmdByte)
        val expected = byteArrayOf(
            0xC0.toByte(),
            LM_CMD_NODE_INFO_REQ.toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
            0xC0.toByte(),
        )
        assertContentEquals(expected, frame)
    }

    @Test fun escapesCrcByteThatLooksLikeFend() {
        // Hand-craft a payload whose CMD||payload CRC is 0x..C0 so
        // the CRC trailer needs FEND-escaping in the wire stream.
        // Probe for one: try payload values until the LSB of the CRC is 0xC0.
        var payload: ByteArray? = null
        for (n in 0..0xFF) {
            val candidate = byteArrayOf(LM_CMD_DATA_TX.toByte(), n.toByte())
            if ((crc16CcittFalse(candidate) and 0xFF) == FEND) {
                payload = byteArrayOf(n.toByte())
                break
            }
        }
        assertTrue(payload != null, "no probe payload found; CCITT is well-distributed enough this shouldn't happen")
        val frame = buildLoraMeshFrame(LM_CMD_DATA_TX, payload!!)
        // The escaped form must contain the FESC,TFEND sequence
        // (0xDB 0xDC) somewhere — the framer can't emit a bare 0xC0
        // anywhere except the leading and trailing FEND.
        val interior = frame.copyOfRange(1, frame.size - 1)
        assertTrue(
            interior.toList().windowed(2).any {
                (it[0].toInt() and 0xFF) == FESC && (it[1].toInt() and 0xFF) == TFEND
            },
            "expected FESC,TFEND escape sequence for CRC byte 0xC0",
        )
    }

    @Test fun parserRoundTripsRegisterIdentity() {
        // REGISTER_IDENTITY carries a 16-byte identity hash. Round-trip
        // a representative one through the framer + parser.
        val identity = ByteArray(16) { it.toByte() }
        val frame = buildLoraMeshFrame(LM_CMD_REGISTER_IDENTITY, identity)

        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val errors = mutableListOf<LoraMeshDecodeError>()
        val parser = LoraMeshKissParser(
            onFrame = { cmd, payload -> frames += cmd to payload },
            onError = { err, _ -> errors += err },
        )
        parser.feed(frame)

        assertEquals(0, errors.size)
        assertEquals(1, frames.size)
        assertEquals(LM_CMD_REGISTER_IDENTITY, frames[0].first)
        assertContentEquals(identity, frames[0].second)
    }

    @Test fun parserHandlesSplitChunks() {
        // BLE notifications can arrive byte-by-byte. The parser must
        // tolerate that — spec §10 open question #4.
        val frame = buildLoraMeshFrame(LM_CMD_DATA_TX, ByteArray(16) { 0 } + byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()))
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val parser = LoraMeshKissParser({ cmd, p -> frames += cmd to p })
        for (b in frame) parser.feed(byteArrayOf(b))
        assertEquals(1, frames.size)
        assertEquals(LM_CMD_DATA_TX, frames[0].first)
    }

    @Test fun parserResyncsAfterBadCrc() {
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val errors = mutableListOf<LoraMeshDecodeError>()
        val parser = LoraMeshKissParser(
            onFrame = { cmd, payload -> frames += cmd to payload },
            onError = { err, _ -> errors += err },
        )
        // First a frame with deliberately corrupted CRC.
        val good = buildLoraMeshFrame(LM_CMD_NODE_INFO_REQ)
        val corrupt = good.copyOf()
        // Flip a CRC byte (second-to-last; last is FEND, the one before is CRC-lo).
        corrupt[corrupt.size - 2] = (corrupt[corrupt.size - 2].toInt() xor 0xFF).toByte()
        parser.feed(corrupt)
        // Then a clean frame — must still be delivered.
        parser.feed(buildLoraMeshFrame(LM_CMD_REGISTER_IDENTITY, ByteArray(16)))

        assertEquals(1, errors.size)
        assertEquals(LoraMeshDecodeError.BadCrc, errors[0])
        assertEquals(1, frames.size)
        assertEquals(LM_CMD_REGISTER_IDENTITY, frames[0].first)
    }

    @Test fun parserIgnoresBackToBackFends() {
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val errors = mutableListOf<LoraMeshDecodeError>()
        val parser = LoraMeshKissParser({ c, p -> frames += c to p }, { err, _ -> errors += err })
        // FEND FEND is a sync artifact, not a frame.
        parser.feed(byteArrayOf(0xC0.toByte(), 0xC0.toByte()))
        parser.feed(buildLoraMeshFrame(LM_CMD_NODE_INFO_REQ))
        assertEquals(0, errors.size, "empty frame is not an error: $errors")
        assertEquals(1, frames.size)
    }
}
