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

    // -- DATA_RX payload decoding (spec §4 test vectors T1-T5,
    //    2026-05-26 mobile_ble_integration.md revision) ----------------

    private fun hexBytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    @Test fun dataRxT1DirectNeighborStrongLink() {
        // T1: payload hex 9C 8A C5 0C 01 DE AD BE EF
        //   src=0x9C8A, rssi=-59, snr=12, hops=1, rns=[DE,AD,BE,EF]
        val payload = hexBytes(0x9C, 0x8A, 0xC5, 0x0C, 0x01, 0xDE, 0xAD, 0xBE, 0xEF)
        val decoded = decodeLoraMeshDataRxPayload(payload)
        val expected = LoraMeshDataRxFrame(
            srcNode = 0x9C8A,
            rssiDbm = -59,
            snrDb = 12,
            hops = 1,
            rnsBytes = hexBytes(0xDE, 0xAD, 0xBE, 0xEF),
        )
        assertEquals(expected, decoded)
    }

    @Test fun dataRxT2ThreeHopWeakLastHopNegativeSnrEmptyRns() {
        // T2: payload hex 7C 63 A6 EC 03
        //   src=0x7C63, rssi=-90, snr=-20, hops=3, rns=[]
        val payload = hexBytes(0x7C, 0x63, 0xA6, 0xEC, 0x03)
        val decoded = decodeLoraMeshDataRxPayload(payload)
        val expected = LoraMeshDataRxFrame(
            srcNode = 0x7C63,
            rssiDbm = -90,
            snrDb = -20,
            hops = 3,
            rnsBytes = ByteArray(0),
        )
        assertEquals(expected, decoded)
    }

    @Test fun dataRxT3UnknownHopsLongerRns() {
        // T3: payload hex 12 34 D8 0A 00 || 0x00..0x10 (17 bytes RNS)
        //   src=0x1234, rssi=-40, snr=10, hops=0
        val payload = hexBytes(
            0x12, 0x34, 0xD8, 0x0A, 0x00,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
        )
        val decoded = decodeLoraMeshDataRxPayload(payload)
        val expected = LoraMeshDataRxFrame(
            srcNode = 0x1234,
            rssiDbm = -40,
            snrDb = 10,
            hops = 0,
            rnsBytes = hexBytes(
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            ),
        )
        assertEquals(expected, decoded)
    }

    @Test fun dataRxT4MinimumValidFrameAllBoundaryValues() {
        // T4: payload hex FF FF 80 80 FF — smallest legal frame.
        //   src=0xFFFF, rssi=-128, snr=-128, hops=255, rns=[]
        // Exercises the int8 sign-extension corner (0x80 → -128) and
        // the uint8 mask (0xFF → 255, NOT -1). If hops sign-extends to
        // -1, the UI would say "hops = -1" instead of "255".
        val payload = hexBytes(0xFF, 0xFF, 0x80, 0x80, 0xFF)
        val decoded = decodeLoraMeshDataRxPayload(payload)
        val expected = LoraMeshDataRxFrame(
            srcNode = 0xFFFF,
            rssiDbm = -128,
            snrDb = -128,
            hops = 255,
            rnsBytes = ByteArray(0),
        )
        assertEquals(expected, decoded)
    }

    @Test fun dataRxT5ZeroRssiZeroSnrTrailingRnsByte() {
        // T5: payload hex 00 01 00 00 01 AA
        //   src=0x0001, rssi=0, snr=0, hops=1, rns=[0xAA]
        val payload = hexBytes(0x00, 0x01, 0x00, 0x00, 0x01, 0xAA)
        val decoded = decodeLoraMeshDataRxPayload(payload)
        val expected = LoraMeshDataRxFrame(
            srcNode = 0x0001,
            rssiDbm = 0,
            snrDb = 0,
            hops = 1,
            rnsBytes = hexBytes(0xAA),
        )
        assertEquals(expected, decoded)
    }

    @Test fun dataRxTooShortReturnsNull() {
        // <5 bytes of payload is malformed per spec §4 T4 caveat —
        // decoder returns null so the caller can log + drop instead
        // of mis-aligning the RNS bytes (which would crash upstream
        // with an "interface disconnected" packet-parse failure).
        assertEquals(null, decodeLoraMeshDataRxPayload(ByteArray(0)))
        assertEquals(null, decodeLoraMeshDataRxPayload(hexBytes(0x9C, 0x8A)))
        assertEquals(null, decodeLoraMeshDataRxPayload(hexBytes(0x9C, 0x8A, 0xC5, 0x0C)))
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
