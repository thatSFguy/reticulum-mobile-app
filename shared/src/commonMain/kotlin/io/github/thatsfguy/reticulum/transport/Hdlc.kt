package io.github.thatsfguy.reticulum.transport

/**
 * HDLC frame encode/decode for Reticulum's TCP interface.
 *
 * rnsd's TCPClientInterface / TCPServerInterface frames every raw
 * Reticulum packet with HDLC before writing it to the socket. Wire
 * format is:
 *
 *   FLAG (0x7E) || escaped(packet_bytes) || FLAG (0x7E)
 *
 * Escape replaces in-band 0x7D with 0x7D 0x5D, and 0x7E with 0x7D 0x5E.
 *
 * Same escape logic as KISS but different byte values, and unlike KISS
 * there is no command byte prefix and no RSSI/SNR sidecar — the frame
 * IS the Reticulum packet.
 *
 * Source of truth: RNS/Interfaces/TCPInterface.py class HDLC.
 * Port of reference/js-reference/hdlc.js.
 */

const val HDLC_FLAG     = 0x7E
const val HDLC_ESC      = 0x7D
const val HDLC_ESC_MASK = 0x20

private val HDLC_FLAG_B = HDLC_FLAG.toByte()
private val HDLC_ESC_B  = HDLC_ESC.toByte()

/** Wrap one complete Reticulum packet into an HDLC frame. */
fun buildHdlcFrame(data: ByteArray): ByteArray {
    // Worst case: every byte needs escaping → 2x size, plus two flags.
    val out = ArrayList<Byte>(data.size + 2 + (data.size shr 3))
    out.add(HDLC_FLAG_B)
    for (b in data) {
        val v = b.toInt() and 0xFF
        if (v == HDLC_FLAG || v == HDLC_ESC) {
            out.add(HDLC_ESC_B)
            out.add((v xor HDLC_ESC_MASK).toByte())
        } else {
            out.add(b)
        }
    }
    out.add(HDLC_FLAG_B)
    return out.toByteArray()
}

/**
 * Streaming HDLC parser. Feed bytes as they arrive from the socket.
 * Partial frames are buffered across feeds, so chunk boundaries do
 * not matter. Empty frames (two FLAGs back to back) are silently
 * dropped — rnsd uses FLAG as both delimiter and keepalive idle.
 */
class HdlcParser(private val onFrame: (data: ByteArray) -> Unit) {
    private val buf = ArrayList<Byte>(512)
    private var inFrame = false
    private var escape = false

    /** Hard cap on a single in-flight frame. Without this a peer that
     *  emits a FLAG and then withholds the closing FLAG could grow the
     *  buffer without bound. RNS packets are at most a few hundred bytes
     *  + framing, so 64 KB is comfortably above legitimate traffic. */
    private val maxFrameBytes = 64 * 1024

    fun feed(bytes: ByteArray) {
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            if (b == HDLC_FLAG) {
                if (inFrame && buf.isNotEmpty()) {
                    onFrame(buf.toByteArray())
                }
                buf.clear()
                inFrame = true
                escape = false
                continue
            }
            if (!inFrame) continue

            if (escape) {
                escape = false
                buf.add((b xor HDLC_ESC_MASK).toByte())
            } else if (b == HDLC_ESC) {
                escape = true
            } else {
                buf.add(raw)
            }

            if (buf.size > maxFrameBytes) {
                // Runaway frame — discard and resync on the next FLAG.
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
