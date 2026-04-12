package io.github.thatsfguy.reticulum.transport

/**
 * HDLC frame encode/decode for the WebSocket → rnsd path.
 *
 * Port of reference/js-reference/hdlc.js.
 * Same escape logic as KISS but different byte values.
 * No RSSI/SNR metadata on this path — frames are raw Reticulum packets.
 *
 * TODO: Port encodeFrame() and HdlcParser from reference/js-reference/hdlc.js.
 */

const val HDLC_FLAG     = 0x7E
const val HDLC_ESC      = 0x7D
const val HDLC_ESC_MASK = 0x20

fun buildHdlcFrame(data: ByteArray): ByteArray {
    TODO("Port from reference/js-reference/hdlc.js encodeFrame()")
}

class HdlcParser(private val onFrame: (data: ByteArray) -> Unit) {
    fun feed(bytes: ByteArray) {
        TODO("Port from reference/js-reference/hdlc.js HdlcParser")
    }
    fun reset() {
        TODO()
    }
}
