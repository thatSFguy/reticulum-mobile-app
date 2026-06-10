package io.github.thatsfguy.reticulum.transport

/**
 * agnostic-LoRa-Net tunnel envelope (SPEC: `agnostic-lora-net/docs/tcp-bridge.md` §2.3).
 *
 * A node exposes a byte tunnel over BLE-NUS (or USB serial) that carries
 * HDLC-framed messages. Inside each HDLC frame is a typed, length-prefixed
 * address followed by the opaque payload — for us, a raw Reticulum packet:
 *
 * ```
 * frame body := [u8 addr_type][u8 addr_len][addr bytes…][payload…]
 *   outbound (host → node): addr = dst (uplink) node id  → mesh delivers there
 *   inbound  (node → host): addr = src node id           ← arrived from there
 * ```
 *
 * `addr_type 0x01 = LOCATOR` (a node id) is the only live type. `0x02 =
 * IDENTITY` is reserved for a future "send to an app identity and let the
 * node resolve it" path (the distributed-lookup plan) and is rejected by
 * current firmware — we never emit it.
 *
 * The node id is **4 bytes, little-endian today**; it widens to a 16-byte
 * pub-key hash later (`identity-vs-locator.md` §6). [NODE_ID_BYTES] is the
 * single isolation point for that migration — the parser already reads
 * `addr_len` off the wire rather than assuming a width, so a peer that
 * advertises a wider locator round-trips without code change here.
 *
 * This object is the platform-independent core; the per-platform BLE
 * transports (Android `AgnosticLoraBleTransport`, future iOS) layer GATT +
 * [buildHdlcFrame]/[HdlcParser] on top of it. Keep it free of platform APIs.
 *
 * Verified byte-for-byte against the node firmware (`src/main.cpp`
 * `tunnel_emit`/`tunnel_rx_frame`) and the reference RNS interface
 * (`AgnosticLoraInterface.py`).
 */
object AgnosticLoraTunnel {

    /** Width of a node-id locator on the wire. 4 today; bump to 16 when the
     *  mesh widens node ids to a pub-key hash. The only spot that hard-codes
     *  the width — everything else is `addr_len`-driven. */
    const val NODE_ID_BYTES = 4

    const val ADDR_TYPE_LOCATOR = 0x01

    /** Reserved — identity-addressed delivery (node resolves id → locator).
     *  Not live in firmware; we never emit it, and [decodeFrame] ignores it. */
    const val ADDR_TYPE_IDENTITY = 0x02

    /** BLE advertised-name prefix a node uses, e.g. `AgnLoRa-9828F51B`. */
    const val ADVERTISED_NAME_PREFIX = "AgnLoRa-"

    /**
     * Wrap a raw Reticulum [payload] in a LOCATOR envelope addressed to
     * [locator] (the uplink node id, already in wire form — see
     * [locatorFromHex]). The result is the HDLC frame *body*; the caller
     * passes it through [buildHdlcFrame] before writing to the tunnel.
     */
    fun encodeLocatorFrame(locator: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArray(2 + locator.size + payload.size)
        out[0] = ADDR_TYPE_LOCATOR.toByte()
        out[1] = locator.size.toByte()
        locator.copyInto(out, destinationOffset = 2)
        payload.copyInto(out, destinationOffset = 2 + locator.size)
        return out
    }

    /**
     * Strip the envelope from a de-HDLC'd [frame] body and return the raw
     * Reticulum packet, or `null` when the frame is not a LOCATOR frame we
     * can use: too short, truncated against its own `addr_len`, or a
     * non-LOCATOR type (IDENTITY / unknown — silently ignored, matching the
     * firmware's own `tunnel_rx_frame`).
     *
     * We don't need the inbound `src` locator for Reticulum (RNS identifies
     * the sender from the packet itself), so it's dropped here. A future
     * reverse-path cache would read `frame[2 until 2+addrLen]` instead.
     */
    fun decodeFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val addrType = frame[0].toInt() and 0xFF
        val addrLen = frame[1].toInt() and 0xFF
        if (addrType != ADDR_TYPE_LOCATOR) return null
        if (frame.size < 2 + addrLen) return null
        return frame.copyOfRange(2 + addrLen, frame.size)
    }

    /**
     * The **source** locator of a de-HDLC'd inbound [frame], as the
     * display/directory hex form (`"9828F51B"`), or `null` for frames
     * [decodeFrame] would reject. Inbound frames carry the node the
     * payload arrived from; the identity router uses it for reverse-path
     * learning (an inbound announce binds its sender to that node).
     * `addr_len`-driven, so a future 16-byte locator round-trips.
     */
    fun sourceFromFrame(frame: ByteArray): String? {
        if (frame.size < 2) return null
        val addrType = frame[0].toInt() and 0xFF
        val addrLen = frame[1].toInt() and 0xFF
        if (addrType != ADDR_TYPE_LOCATOR) return null
        if (addrLen == 0 || frame.size < 2 + addrLen) return null
        val sb = StringBuilder(addrLen * 2)
        // Wire form is little-endian; the id form is big-endian hex.
        for (i in (2 + addrLen - 1) downTo 2) {
            val v = frame[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * Parse a node-id hex string (`"9828F51B"`, the form printed in the
     * banner and embedded in the `AgnLoRa-<id>` BLE name) into its
     * [NODE_ID_BYTES]-byte **little-endian** wire form, matching the
     * reference interface's `struct.pack("<I", peer)`. Returns `null` if the
     * string isn't exactly [NODE_ID_BYTES]*2 hex digits.
     */
    fun locatorFromHex(hex: String): ByteArray? {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X")
        if (clean.length != NODE_ID_BYTES * 2) return null
        val value = clean.toULongOrNull(16) ?: return null
        return ByteArray(NODE_ID_BYTES) { i -> ((value shr (8 * i)) and 0xFFuL).toByte() }
    }

    /** True if [hex] is a syntactically valid uplink node id. */
    fun isValidNodeIdHex(hex: String): Boolean = locatorFromHex(hex) != null

    /**
     * Pull the node-id hex out of an advertised name like `AgnLoRa-9828F51B`,
     * or `null` if [name] is absent / doesn't carry the prefix. Used to
     * auto-fill the uplink locator from the node the user just scanned.
     */
    fun nodeIdFromAdvertisedName(name: String?): String? {
        if (name == null) return null
        if (!name.startsWith(ADVERTISED_NAME_PREFIX, ignoreCase = true)) return null
        return name.substring(ADVERTISED_NAME_PREFIX.length).trim().ifEmpty { null }
    }
}
