package io.github.thatsfguy.reticulum.transport

/**
 * Demux for the agnostic-LoRa-Net node's NUS/serial byte stream
 * (SPEC: `agnostic-lora-net/docs/mobile-app-testing.md` §0.3).
 *
 * The node multiplexes two things onto one byte stream:
 *   - HDLC frames (`0x7E … 0x7E`) carrying tunnel envelopes, and
 *   - plain text console lines (`loc …`, `registered …`, `[dir] …`,
 *     heartbeat) terminated by LF.
 *
 * Port of the reference demux in `AgnosticLoraInterface.py::_read_loop`,
 * kept byte-for-byte faithful:
 *   - FLAG (0x7E) TOGGLES frame state: first FLAG opens, second closes.
 *     (Unlike [HdlcParser]'s delimiter semantics — the node emits frames
 *     as discrete FLAG…FLAG pairs, never sharing a delimiter.)
 *   - Any FLAG clears the text accumulator: a frame boundary is never
 *     part of a console line.
 *   - In-frame: ESC (0x7D) + next^0x20 un-escaping; oversize frames stop
 *     accumulating (bytes dropped) but stay in-frame until the closing
 *     FLAG resyncs.
 *   - Out-of-frame: LF emits the accumulated line, CR is dropped, lines
 *     are capped at 200 chars.
 */
class NusDemux(
    private val onFrame: (ByteArray) -> Unit,
    private val onTextLine: (String) -> Unit,
) {
    private var inFrame = false
    private var escape = false
    private val frame = ArrayList<Byte>(512)
    private val text = StringBuilder(64)

    /** Reference cap: HW_MTU + 8 (`AgnosticLoraInterface.py`). */
    private val maxFrameBytes = 500 + 8
    private val maxLineChars = 200

    fun feed(bytes: ByteArray) {
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            if (b == HDLC_FLAG) {
                if (inFrame) {
                    inFrame = false
                    if (frame.isNotEmpty()) onFrame(frame.toByteArray())
                } else {
                    inFrame = true
                    escape = false
                    frame.clear()
                }
                text.setLength(0)
                continue
            }
            if (inFrame) {
                if (frame.size < maxFrameBytes) {
                    if (escape) {
                        escape = false
                        frame.add((b xor HDLC_ESC_MASK).toByte())
                    } else if (b == HDLC_ESC) {
                        escape = true
                    } else {
                        frame.add(raw)
                    }
                }
                continue
            }
            // Out-of-frame: console text.
            when {
                b == 0x0A -> {
                    val line = text.toString()
                    text.setLength(0)
                    if (line.isNotBlank()) onTextLine(line)
                }
                b != 0x0D && text.length < maxLineChars -> text.append(b.toChar())
            }
        }
    }

    fun reset() {
        inFrame = false
        escape = false
        frame.clear()
        text.setLength(0)
    }
}
