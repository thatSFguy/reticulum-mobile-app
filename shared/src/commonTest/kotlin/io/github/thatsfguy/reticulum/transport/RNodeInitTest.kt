package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.platform.RadioConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [rnodeRadioInitCommands] — the RNode radio-bring-up sequence shared by
 * all three RNode-driving transports (Android BLE, iOS BLE, BtClassic).
 *
 * Guards the wire details that regress silently and can't be exercised
 * through the platform transports (which need a live GATT / RFCOMM
 * connection): command ORDER, CMD_DETECT presence, and payload encoding.
 * Aligned with RNS `RNodeInterface.initRadio()` — issue #18.
 *
 * camelCase test names keep the iosTest Kotlin/Native compile happy.
 */
class RNodeInitTest {

    // The values from issue #18's log, so the test doubles as a concrete
    // worked example: 868.4 MHz, BW 500 kHz, SF 8, CR 6, 20 dBm.
    private val cfg = RadioConfig(
        frequencyHz = 868_400_000L,
        bandwidthHz = 500_000L,
        spreadingFactor = 8,
        codingRate = 6,
        txPowerDbm = 20,
    )

    /** Independent big-endian uint32 decode — NOT reusing the encoder,
     *  so the freq/bw assertions actually verify the byte order. */
    private fun beToLong(b: ByteArray): Long {
        require(b.size == 4)
        return ((b[0].toLong() and 0xFF) shl 24) or
            ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or
            (b[3].toLong() and 0xFF)
    }

    @Test
    fun commandOrderMatchesRnsInitRadio() {
        val order = rnodeRadioInitCommands(cfg).map { it.first }
        assertEquals(
            listOf(
                CMD_DETECT,      // detect first — RNS gates radio config on this
                CMD_FREQUENCY,
                CMD_BANDWIDTH,
                CMD_TXPOWER,     // tx power BEFORE sf/cr (RNS order)
                CMD_SF,
                CMD_CR,
                CMD_RADIO_STATE, // radio on last
            ),
            order,
        )
    }

    @Test
    fun detectCommandCarriesDetectReq() {
        val (cmd, payload) = rnodeRadioInitCommands(cfg).first()
        assertEquals(CMD_DETECT, cmd)
        assertContentEquals(byteArrayOf(DETECT_REQ.toByte()), payload)
    }

    @Test
    fun frequencyAndBandwidthAreBigEndianUint32() {
        val cmds = rnodeRadioInitCommands(cfg).toMap()
        val freq = cmds.getValue(CMD_FREQUENCY)
        val bw = cmds.getValue(CMD_BANDWIDTH)
        assertEquals(4, freq.size)
        assertEquals(4, bw.size)
        assertEquals(868_400_000L, beToLong(freq))
        assertEquals(500_000L, beToLong(bw))
        // Explicit MSB-first byte check on frequency: 868_400_000 = 0x33C2BB80.
        assertContentEquals(byteArrayOf(0x33, 0xC2.toByte(), 0xBB.toByte(), 0x80.toByte()), freq)
    }

    @Test
    fun singleByteParamsCarryRawValues() {
        val cmds = rnodeRadioInitCommands(cfg).toMap()
        assertContentEquals(byteArrayOf(20), cmds.getValue(CMD_TXPOWER))
        assertContentEquals(byteArrayOf(8), cmds.getValue(CMD_SF))
        assertContentEquals(byteArrayOf(6), cmds.getValue(CMD_CR))
        assertContentEquals(byteArrayOf(0x01), cmds.getValue(CMD_RADIO_STATE))
    }

    @Test
    fun sequenceIsExactlySevenCommands() {
        // A stray extra/missing command (e.g. accidentally dropping
        // CMD_DETECT again) should fail loudly.
        assertEquals(7, rnodeRadioInitCommands(cfg).size)
    }
}
