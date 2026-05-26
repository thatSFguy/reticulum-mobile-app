package io.github.thatsfguy.reticulum.transport

/**
 * KISS dialect for the reticulum-loramesh firmware. Same FEND/FESC
 * byte values as the RNode KISS path in [Kiss.kt], but a different
 * command set. The firmware is a distance-vector mesh router that
 * abstracts radio routing; from the host's POV it just delivers
 * opaque Reticulum packet bytes, prefixed/suffixed by the mesh's
 * framing.
 *
 * Spec: docs/mobile_ble_integration.md §3 (framing) and §4 (commands).
 * Reference: `host/rns_loramesh.py::KissDecoder` in the firmware repo.
 *
 * Why a separate file and a separate parser?
 *   1. The opcode space conflicts with RNode KISS (both use 0x00 for
 *      DATA, but DATA_TX here carries a 16-byte dst_identity_hash
 *      prefix the RNode path doesn't have; DATA_RX carries a 2-byte
 *      src_node prefix the RNode path doesn't have).
 *   2. The transport ships no RSSI/SNR sidecar — the firmware abstracts
 *      multi-hop, so per-message radio metrics are not meaningful at
 *      this layer.
 *
 * Wire format history:
 *   - Pre-2026-05-26: frames carried a CRC-16/CCITT-FALSE trailer
 *     (big-endian, over `cmd || payload`, before escape encoding).
 *   - 2026-05-26: spec revised to drop the CRC. Both transports we
 *     run over (BLE GATT, USB-CDC) already carry their own integrity
 *     check at the link layer, so the app-level CRC was redundant and
 *     a real source of decode-error bugs whenever the encoder/decoder
 *     disagreed on byte order or polynomial. Spec §3.
 *
 * Keeping the codecs separate prevents accidental cross-wiring (e.g.
 * pushing an RNode CMD_RADIO_STATE into a LoraMesh node which would
 * interpret it as a 1-byte CONFIG_CMD).
 */

// LoraMesh KISS opcodes (host → firmware)
const val LM_CMD_DATA_TX           = 0x00
const val LM_CMD_DIAG_ENABLE       = 0x01
const val LM_CMD_CONFIG_CMD        = 0x02
const val LM_CMD_NODE_INFO_REQ     = 0x03
const val LM_CMD_REGISTER_IDENTITY = 0x04
const val LM_CMD_DUMP_STATE        = 0x05

// LoraMesh KISS opcodes (firmware → host)
const val LM_CMD_DATA_RX           = 0x00
const val LM_CMD_DIAG_EVENT        = 0x01
const val LM_CMD_CONFIG_REPLY      = 0x02

/** Maximum decoded frame size (CMD + payload) the firmware accepts.
 *  Anything larger is dropped firmware-side with BAD_LENGTH; mirror
 *  that on the host so a corrupted incoming stream can't grow `buf`
 *  without bound. Spec §3. */
const val LM_MAX_FRAME_BYTES = 512

/**
 * Build a LoraMesh KISS frame: FEND + escaped(cmd || payload) + FEND.
 *
 * Escape rules are identical to the RNode KISS path: 0xC0 → 0xDB 0xDC,
 * 0xDB → 0xDB 0xDD. No CRC trailer (post-2026-05-26 spec; see file
 * header).
 */
fun buildLoraMeshFrame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
    val body = ByteArray(1 + payload.size)
    body[0] = (cmd and 0xFF).toByte()
    payload.copyInto(body, destinationOffset = 1)

    val out = ArrayList<Byte>(body.size + 4)
    out.add(FEND_B)
    for (b in body) {
        when (b.toInt() and 0xFF) {
            FEND -> { out.add(FESC_B); out.add(TFEND_B) }
            FESC -> { out.add(FESC_B); out.add(TFESC_B) }
            else -> out.add(b)
        }
    }
    out.add(FEND_B)
    return out.toByteArray()
}

private val FEND_B  = FEND.toByte()
private val FESC_B  = FESC.toByte()
private val TFEND_B = TFEND.toByte()
private val TFESC_B = TFESC.toByte()

/** Reason a [LoraMeshKissParser] discarded an in-flight frame. The
 *  caller is expected to log these and continue — KISS self-syncs on
 *  the next FEND, so single bad frames are not fatal. */
enum class LoraMeshDecodeError {
    /** Frame body grew past [LM_MAX_FRAME_BYTES] before a closing FEND. */
    BadLength,

    /** Escape sequence `FESC X` where X was neither TFEND nor TFESC. */
    BadEscape,
}

/**
 * Streaming LoraMesh KISS decoder. Feed bytes from each BLE
 * notification (or USB / TCP chunk) and the callback fires once per
 * fully-decoded frame. The parser self-syncs on the next FEND, so
 * transient bad frames don't break subsequent frames.
 *
 * Per-byte feed is supported (and matches the firmware-side reference
 * impl). On Android, BLE notifications can arrive in batches with one
 * `onCharacteristicChanged` per byte under bad conditions — the
 * parser must be tolerant of that.
 */
class LoraMeshKissParser(
    private val onFrame: (cmd: Int, payload: ByteArray) -> Unit,
    private val onError: (LoraMeshDecodeError, rejected: ByteArray) -> Unit = { _, _ -> },
) {
    private val buf = ArrayList<Byte>(LM_MAX_FRAME_BYTES)
    private var inFrame = false
    private var escape = false

    fun feed(bytes: ByteArray) {
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            if (b == FEND) {
                finishFrame()
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
                    else -> {
                        onError(LoraMeshDecodeError.BadEscape, snapshot())
                        buf.clear()
                        inFrame = false
                    }
                }
            } else if (b == FESC) {
                escape = true
            } else {
                buf.add(raw)
            }

            if (buf.size > LM_MAX_FRAME_BYTES) {
                onError(LoraMeshDecodeError.BadLength, snapshot())
                buf.clear()
                inFrame = false
                escape = false
            }
        }
    }

    private fun snapshot(): ByteArray = ByteArray(buf.size) { buf[it] }

    private fun finishFrame() {
        if (!inFrame) return
        if (buf.isEmpty()) {
            // Back-to-back FENDs are a sync artifact, not an error.
            // (Pre-2026-05-26 the parser also rejected frames shorter
            // than CMD+CRC = 3 bytes; with CRC gone, any non-empty
            // frame is a valid CMD + zero-length payload.)
            return
        }
        val cmd = buf[0].toInt() and 0xFF
        val payload = ByteArray(buf.size - 1) { buf[it + 1] }
        onFrame(cmd, payload)
    }

    fun reset() {
        buf.clear()
        inFrame = false
        escape = false
    }
}
