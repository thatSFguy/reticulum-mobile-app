package io.github.thatsfguy.reticulum.transport

/**
 * KISS frame encode/decode for the RNode BLE/Serial path.
 *
 * Port of reference/js-reference/kiss.js.
 *
 * Frame format: FEND + cmd + escaped(data) + FEND
 * The RNode sends RSSI + SNR frames before each CMD_DATA frame.
 *
 * The parser is streaming — BLE notifications split frames across
 * multiple chunks. We accumulate bytes and emit complete frames on
 * FEND boundaries.
 */

const val FEND  = 0xC0
const val FESC  = 0xDB
const val TFEND = 0xDC
const val TFESC = 0xDD

// RNode KISS commands (values match reference/js-reference/kiss.js
// which was tested against real RNode firmware).
const val CMD_DATA        = 0x00
const val CMD_FREQUENCY   = 0x01
const val CMD_BANDWIDTH   = 0x02
const val CMD_TXPOWER     = 0x03
const val CMD_SF          = 0x04
const val CMD_CR          = 0x05
const val CMD_RADIO_STATE = 0x06
const val CMD_DETECT      = 0x08
const val CMD_READY       = 0x0F
const val CMD_STAT_RSSI   = 0x23
const val CMD_STAT_SNR    = 0x24
const val CMD_STAT_BAT    = 0x27
const val CMD_BLINK       = 0x30
const val CMD_RANDOM      = 0x40
const val CMD_BOARD       = 0x47
const val CMD_PLATFORM    = 0x48
const val CMD_MCU         = 0x49
const val CMD_FW_VERSION  = 0x50
const val CMD_RESET       = 0x55
const val CMD_ERROR       = 0x90

const val DETECT_REQ  = 0x73
const val DETECT_RESP = 0x46

private val FEND_B  = FEND.toByte()
private val FESC_B  = FESC.toByte()
private val TFEND_B = TFEND.toByte()
private val TFESC_B = TFESC.toByte()

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
 * Escapes: 0xC0 → 0xDB 0xDC, 0xDB → 0xDB 0xDD.
 */
fun buildKissFrame(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
    val out = ArrayList<Byte>(data.size + 4)
    out.add(FEND_B)
    out.add((cmd and 0xFF).toByte())
    for (b in data) {
        when (b.toInt() and 0xFF) {
            FEND -> { out.add(FESC_B); out.add(TFEND_B) }
            FESC -> { out.add(FESC_B); out.add(TFESC_B) }
            else -> out.add(b)
        }
    }
    out.add(FEND_B)
    return out.toByteArray()
}

/**
 * Streaming KISS parser. Feed BLE notification chunks; it emits
 * complete (cmd, payload) pairs via the callback.
 *
 * The RNode emits CMD_STAT_RSSI + CMD_STAT_SNR frames immediately
 * before each CMD_DATA frame. Pairing those with the data frame is
 * the caller's responsibility — this parser only deframes.
 */
class KissParser(private val onFrame: (cmd: Int, payload: ByteArray) -> Unit) {
    private val buf = ArrayList<Byte>(512)
    private var inFrame = false
    private var escape = false

    /** Hard cap on a single in-flight frame. Mirrors HDLC's 64 KB
     *  ceiling — without this a peer (a malicious RNode or a proximity
     *  BLE-NUS impersonator) can emit a FEND and then stream bytes
     *  without ever closing the frame, growing `buf` until OOM. RNode
     *  KISS frames legitimately top out near MTU + RSSI/SNR sidecar
     *  (<700 B); 64 KB is comfortable headroom. Audit reference:
     *  2026-05-13 MED-1. */
    private val maxFrameBytes = 64 * 1024

    fun feed(bytes: ByteArray) {
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            if (b == FEND) {
                if (inFrame && buf.isNotEmpty()) {
                    val cmd = buf[0].toInt() and 0xFF
                    val payload = ByteArray(buf.size - 1) { buf[it + 1] }
                    onFrame(cmd, payload)
                }
                buf.clear()
                inFrame = true
                escape = false
                continue
            }
            if (!inFrame) continue

            if (escape) {
                escape = false
                when (b) {
                    TFEND -> buf.add(FEND_B)
                    TFESC -> buf.add(FESC_B)
                    else  -> buf.add(raw)
                }
            } else if (b == FESC) {
                escape = true
            } else {
                buf.add(raw)
            }

            if (buf.size > maxFrameBytes) {
                // Runaway frame — discard and resync on the next FEND.
                buf.clear()
                inFrame = false
                escape = false
            }
        }
    }

    fun reset() {
        buf.clear()
        inFrame = false
        escape = false
    }
}

/**
 * Decode an RSSI byte from a CMD_STAT_RSSI frame: signed value = byte - 157.
 * Convention from RNode firmware.
 */
fun decodeRssi(b: Byte): Int = (b.toInt() and 0xFF) - 157

/**
 * Decode an SNR byte from a CMD_STAT_SNR frame: signed Q6.2, divide by 4.
 */
fun decodeSnr(b: Byte): Double = b.toInt() / 4.0
