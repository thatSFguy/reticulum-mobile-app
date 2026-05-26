package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Codec-parity tests for the post-2026-05-26 LoraMesh KISS wire
 * format: FEND CMD payload FEND, no CRC trailer. Three pre-CRC-drop
 * tests (`crc16CheckValue`, `parserResyncsAfterBadCrc`,
 * `escapesCrcByteThatLooksLikeFend`) were removed when the spec
 * dropped the CRC — see `docs/mobile_ble_integration.md` §3.
 */
class LoraMeshKissTest {

    @Test fun emptyPayloadFraming() {
        // NODE_INFO_REQ has zero payload. Frame is just FEND CMD FEND.
        val frame = buildLoraMeshFrame(LM_CMD_NODE_INFO_REQ)
        val expected = byteArrayOf(
            0xC0.toByte(),
            LM_CMD_NODE_INFO_REQ.toByte(),
            0xC0.toByte(),
        )
        assertContentEquals(expected, frame)
    }

    @Test fun escapesFendAndFescInPayload() {
        // A DATA_TX payload containing the two special bytes must
        // expand each one into a two-byte escape sequence so the
        // parser doesn't mistake them for frame delimiters.
        val payload = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x42)
        val frame = buildLoraMeshFrame(LM_CMD_DATA_TX, payload)
        val expected = byteArrayOf(
            0xC0.toByte(), LM_CMD_DATA_TX.toByte(),
            0xDB.toByte(), 0xDC.toByte(),  // 0xC0 → FESC TFEND
            0xDB.toByte(), 0xDD.toByte(),  // 0xDB → FESC TFESC
            0x42,
            0xC0.toByte(),
        )
        assertContentEquals(expected, frame)
    }

    @Test fun parserRoundTripsRegisterIdentity() {
        // REGISTER_IDENTITY carries a 16-byte identity hash.
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
        // BLE notifications can arrive byte-by-byte under bad
        // conditions. The parser must tolerate that — spec §10 open
        // question #4.
        val frame = buildLoraMeshFrame(
            LM_CMD_DATA_TX,
            ByteArray(16) { 0 } + byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
        )
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val parser = LoraMeshKissParser({ cmd, p -> frames += cmd to p })
        for (b in frame) parser.feed(byteArrayOf(b))
        assertEquals(1, frames.size)
        assertEquals(LM_CMD_DATA_TX, frames[0].first)
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

    @Test fun parserResyncsAfterBadEscape() {
        // A FESC followed by anything other than TFEND/TFESC mid-frame
        // discards the in-flight frame; the next FEND starts a clean one.
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val errors = mutableListOf<LoraMeshDecodeError>()
        val parser = LoraMeshKissParser(
            onFrame = { cmd, payload -> frames += cmd to payload },
            onError = { err, _ -> errors += err },
        )
        // FEND CMD=0x00 FESC 0xFF — corrupt escape sequence.
        parser.feed(byteArrayOf(0xC0.toByte(), 0x00, 0xDB.toByte(), 0xFF.toByte()))
        // Then a clean frame; should deliver despite the prior error.
        parser.feed(buildLoraMeshFrame(LM_CMD_REGISTER_IDENTITY, ByteArray(16)))

        assertEquals(1, errors.size)
        assertEquals(LoraMeshDecodeError.BadEscape, errors[0])
        assertEquals(1, frames.size)
        assertEquals(LM_CMD_REGISTER_IDENTITY, frames[0].first)
    }
}
