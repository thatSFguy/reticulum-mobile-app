package io.github.thatsfguy.reticulum.transport

/**
 * KISS frame encode/decode for the RNode BLE/Serial path.
 *
 * Port of reference/js-reference/kiss.js.
 *
 * Frame format: FEND + cmd + escaped(data) + FEND
 * The RNode sends RSSI + SNR frames before each CMD_DATA frame.
 *
 * The parser must be streaming — BLE notifications split frames
 * across multiple chunks. Accumulate bytes and emit complete
 * frames on FEND boundaries.
 */

const val FEND  = 0xC0
const val FESC  = 0xDB
const val TFEND = 0xDC
const val TFESC = 0xDD

// RNode KISS commands
const val CMD_DATA       = 0x00
const val CMD_FREQUENCY  = 0x01
const val CMD_BANDWIDTH  = 0x02
const val CMD_TXPOWER    = 0x03
const val CMD_SF         = 0x04
const val CMD_CR         = 0x05
const val CMD_RADIO_STATE = 0x06
const val CMD_DETECT     = 0x08
const val CMD_STAT_RSSI  = 0x21
const val CMD_STAT_SNR   = 0x22
const val CMD_STAT_BAT   = 0x24
const val CMD_BLINK      = 0x2F
const val CMD_FW_VERSION = 0x28
const val CMD_BOARD      = 0x47
const val CMD_PLATFORM   = 0x48
const val CMD_MCU        = 0x49
const val CMD_RESET      = 0x55
const val CMD_ERROR      = 0x90

const val DETECT_REQ  = 0x73
const val DETECT_RESP = 0x46

/** Byte → 2-char lowercase hex. */
fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Hex string → ByteArray. */
fun String.hexToBytes(): ByteArray {
    val hex = this.lowercase()
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Build a KISS frame: FEND + cmd + escaped(data) + FEND.
 *
 * TODO: Implement byte-stuffing (FEND→FESC+TFEND, FESC→FESC+TFESC).
 *       See reference/js-reference/kiss.js buildFrame().
 */
fun buildKissFrame(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
    TODO("Port from reference/js-reference/kiss.js buildFrame()")
}

/**
 * Streaming KISS parser. Feed BLE notification chunks; it emits
 * complete (cmd, payload) pairs via the callback.
 *
 * TODO: Port from reference/js-reference/kiss.js KissParser class.
 *       Must handle: byte accumulation across chunks, FEND boundary
 *       detection, escape sequence decoding, and the RNode convention
 *       of sending CMD_STAT_RSSI + CMD_STAT_SNR before CMD_DATA.
 */
class KissParser(private val onFrame: (cmd: Int, payload: ByteArray) -> Unit) {
    fun feed(bytes: ByteArray) {
        TODO("Port from reference/js-reference/kiss.js KissParser")
    }
    fun reset() {
        TODO()
    }
}
