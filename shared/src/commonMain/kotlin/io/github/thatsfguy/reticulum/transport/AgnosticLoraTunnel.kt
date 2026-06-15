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
 * As of node firmware v2 (self-certifying identity) the node id is a
 * **16-byte blake2b hash, in canonical byte order — no endianness**: the
 * display hex maps straight to the wire bytes (`b0459c80…4e3e` ⇄
 * `b0 45 9c 80 … 4e 3e`, `byte[0]` first), matching the firmware's
 * `nid_write`/`nid_read` plain `memcpy` (`mesh_types.h` §NodeId). This
 * reverses the pre-v2 4-byte id, which was a `uint32` sent little-endian
 * (`struct.pack("<I")`); switching the firmware's `node_id_t` to a
 * `uint8_t[16]` made the same `memcpy` natural-order, so the byte-reversal
 * vanished in both directions. [NODE_ID_BYTES] is the single isolation
 * point for the width — the parser already reads `addr_len` off the wire
 * rather than assuming it, so the envelope path needed no change.
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

    /** Width of a node-id locator on the wire: 16 bytes since firmware v2
     *  (a blake2b pub-key hash; was 4 in the pre-v2 FICR-id era). The only
     *  spot that hard-codes the width — everything else is `addr_len`-driven. */
    const val NODE_ID_BYTES = 16

    const val ADDR_TYPE_LOCATOR = 0x01

    /** Reserved — identity-addressed delivery (node resolves id → locator).
     *  Not live in firmware; we never emit it, and [decodeFrame] ignores it. */
    const val ADDR_TYPE_IDENTITY = 0x02

    /** BLE advertised-name prefix current firmware uses: `ALN-<label>`, where
     *  `<label>` is a user-set friendly name or, by default, the first 8 hex
     *  of the node id. The advertised name is a **discovery filter ONLY —
     *  never a node-id source**: with a friendly name it carries no id at all,
     *  and even the default carries only 8 of the 32 hex. Get the full 32-hex
     *  id after connect from the `registered … at <node>` ack or a
     *  `[hb] … node=<node>` line (both full, 32 hex). */
    const val ADVERTISED_NAME_PREFIX = "ALN-"

    /** Legacy prefix used by pre-friendly-name firmware (`AgnLoRa-<8hex>`).
     *  Still accepted by [isAdvertisedName] so older nodes stay scannable. */
    const val LEGACY_ADVERTISED_NAME_PREFIX = "AgnLoRa-"

    /** True if [name] is one of our nodes' advertised names — current `ALN-`
     *  or legacy `AgnLoRa-`. For the BLE scan filter only; the matched name is
     *  never a node-id source (see [ADVERTISED_NAME_PREFIX]). */
    fun isAdvertisedName(name: String?): Boolean =
        name != null && (
            name.startsWith(ADVERTISED_NAME_PREFIX, ignoreCase = true) ||
                name.startsWith(LEGACY_ADVERTISED_NAME_PREFIX, ignoreCase = true)
            )

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
     * display/directory hex form (`"B0459C80…4E3E"`), or `null` for frames
     * [decodeFrame] would reject. Inbound frames carry the node the
     * payload arrived from; the identity router uses it for reverse-path
     * learning (an inbound announce binds its sender to that node).
     * `addr_len`-driven, so any locator width round-trips.
     */
    fun sourceFromFrame(frame: ByteArray): String? {
        if (frame.size < 2) return null
        val addrType = frame[0].toInt() and 0xFF
        val addrLen = frame[1].toInt() and 0xFF
        if (addrType != ADDR_TYPE_LOCATOR) return null
        if (addrLen == 0 || frame.size < 2 + addrLen) return null
        val sb = StringBuilder(addrLen * 2)
        // The node id is a byte string in canonical order (no endianness),
        // so the id hex is just the wire bytes in order, byte[0] first.
        for (i in 2 until 2 + addrLen) {
            val v = frame[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * Parse a node-id hex string (`"b0459c80…4e3e"`, the form printed by the
     * node's `info`/`pub` console and in `loc`/heartbeat lines) into its
     * [NODE_ID_BYTES]-byte wire form. The id is a byte string in **canonical
     * order — no endianness**: `byte[0]` is the first hex pair, matching the
     * firmware's `nid_read` `memcpy`. (Pre-v2 4-byte ids were little-endian;
     * the v2 `uint8_t[16]` made the same `memcpy` natural-order.) Parsing is
     * case-insensitive. Returns `null` unless the string is exactly
     * [NODE_ID_BYTES]*2 hex digits — note a 128-bit id won't fit a `ULong`,
     * so this walks hex pairs rather than parsing a single integer.
     */
    fun locatorFromHex(hex: String): ByteArray? {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X")
        if (clean.length != NODE_ID_BYTES * 2) return null
        val out = ByteArray(NODE_ID_BYTES)
        for (i in 0 until NODE_ID_BYTES) {
            val hi = clean[2 * i].digitToIntOrNull(16) ?: return null
            val lo = clean[2 * i + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** True if [hex] is a syntactically valid uplink node id. */
    fun isValidNodeIdHex(hex: String): Boolean = locatorFromHex(hex) != null

    /**
     * The display label after the advertised-name prefix (`ALN-kitchen` →
     * `"kitchen"`, `ALN-b0459c80` → `"b0459c80"`, legacy `AgnLoRa-b0459c80` →
     * `"b0459c80"`), or `null` if [name] is absent / lacks a known prefix.
     *
     * This is a DISPLAY LABEL ONLY. With a friendly name it is not hex at all,
     * and even the default is just the first 8 of 32 hex — so it must NEVER be
     * passed to [locatorFromHex] or used to address a frame. Get the full
     * 32-hex node id from the `registered … at <node>` ack or a
     * `[hb] … node=<node>` line (handled by the router).
     */
    fun labelFromAdvertisedName(name: String?): String? {
        if (name == null) return null
        for (prefix in listOf(ADVERTISED_NAME_PREFIX, LEGACY_ADVERTISED_NAME_PREFIX)) {
            if (name.startsWith(prefix, ignoreCase = true)) {
                return name.substring(prefix.length).trim().ifEmpty { null }
            }
        }
        return null
    }
}
