package io.github.thatsfguy.reticulum.protocol

/**
 * Parsed Reticulum packet header + payload.
 *
 * Port of js/reticulum.js parsePacket(). Both HEADER_1 (direct) and
 * HEADER_2 (transport-forwarded) layouts are handled. The [transportId]
 * field is non-null only for HEADER_2 packets.
 *
 * Reference: CLAUDE.md "Packet header" section.
 */
data class Packet(
    val raw: ByteArray,
    val flags: Int,
    val hops: Int,
    val headerType: Int,
    val contextFlag: Int,
    val transportType: Int,
    val destType: Int,
    val packetType: Int,
    val destHash: ByteArray,       // 16 bytes
    val transportId: ByteArray?,   // 16 bytes, HEADER_2 only
    val context: Int,
    val payload: ByteArray,
)

/**
 * Parse raw bytes into a [Packet]. Returns null if the data is too
 * short for a valid header.
 *
 * Port of: reference/js-reference/reticulum.js parsePacket()
 */
fun parsePacket(data: ByteArray): Packet? {
    if (data.size < HEADER_MINSIZE) return null

    val flags   = data[0].toInt() and 0xFF
    val hops    = data[1].toInt() and 0xFF

    // Bit 7 is the IFAC flag (SPEC §2.1). An IFAC-protected packet
    // carries an ifac_size-byte authentication field between the hops
    // byte and the addresses; this app attaches only to open
    // (non-IFAC) interfaces and has no IFAC key, so it can neither
    // authenticate nor correctly slice such a packet. Drop it cleanly
    // rather than mis-parsing the address/context fields.
    if ((flags and 0x80) != 0) return null

    val headerType    = (flags shr 6) and 0x01
    val contextFlag   = (flags shr 5) and 0x01
    val transportType = (flags shr 4) and 0x01
    val destType      = (flags shr 2) and 0x03
    val packetType    = flags and 0x03

    return if (headerType == HEADER_1) {
        val destHash = data.copyOfRange(2, 2 + TRUNCATED_HASHLENGTH)
        val context  = data[2 + TRUNCATED_HASHLENGTH].toInt() and 0xFF
        val payload  = data.copyOfRange(2 + TRUNCATED_HASHLENGTH + 1, data.size)
        Packet(data, flags, hops, headerType, contextFlag, transportType,
               destType, packetType, destHash, null, context, payload)
    } else {
        // HEADER_2: transport_id(16) + destination_hash(16) + context(1).
        // HEADER_MINSIZE is the HEADER_1 minimum (19); HEADER_2 needs an
        // extra 16 bytes for the transport_id. Without this guard, garbage
        // bytes from a relay (mid-disconnect TCP, dropped KISS frame mid
        // BLE reassembly) with the HEADER_2 flag bit set would throw
        // ArrayIndexOutOfBoundsException at the context-byte read and
        // crash the engine pump. Return null instead so handleIncoming
        // skips the packet.
        val header2Min = 2 + 2 * TRUNCATED_HASHLENGTH + 1  // = 35
        if (data.size < header2Min) return null

        val transportId = data.copyOfRange(2, 2 + TRUNCATED_HASHLENGTH)
        val destHash    = data.copyOfRange(2 + TRUNCATED_HASHLENGTH, 2 + 2 * TRUNCATED_HASHLENGTH)
        val context     = data[2 + 2 * TRUNCATED_HASHLENGTH].toInt() and 0xFF
        val payload     = data.copyOfRange(2 + 2 * TRUNCATED_HASHLENGTH + 1, data.size)
        Packet(data, flags, hops, headerType, contextFlag, transportType,
               destType, packetType, destHash, transportId, context, payload)
    }
}

/**
 * Build a Reticulum packet from components. Defaults to HEADER_1; pass
 * `headerType = HEADER_2` together with a 16-byte `transportId` to emit
 * the transport-forwarded form.
 *
 * HEADER_2 originator emission is required by spec §2.3 whenever the
 * sender's path table reports the destination via a transit relay.
 * Upstream `RNS/Transport.py:1497` only forwards inbound DATA packets
 * that carry `transport_id != None`; a leaf client that always emits
 * HEADER_1 has its packets silently dropped at the first transit
 * transport. The `transportId` slot sits between the hops byte and the
 * destination_hash on the wire (§2.1).
 *
 * Port of: reference/js-reference/reticulum.js buildPacket() (which
 * was HEADER_1-only — HEADER_2 is the new path).
 */
fun buildPacket(
    headerType: Int = HEADER_1,
    contextFlag: Int = 0,
    transportType: Int = TRANSPORT_BROADCAST,
    destType: Int = DEST_SINGLE,
    packetType: Int = PACKET_DATA,
    hops: Int = 0,
    destHash: ByteArray,
    transportId: ByteArray? = null,
    context: Int = CTX_NONE,
    payload: ByteArray = ByteArray(0),
): ByteArray {
    val flags = ((headerType and 0x01) shl 6) or
                ((contextFlag and 0x01) shl 5) or
                ((transportType and 0x01) shl 4) or
                ((destType and 0x03) shl 2) or
                (packetType and 0x03)

    if (headerType == HEADER_2) {
        require(transportId != null) {
            "HEADER_2 requires a 16-byte transportId; pass null to emit HEADER_1"
        }
        require(transportId.size == TRUNCATED_HASHLENGTH) {
            "transportId must be $TRUNCATED_HASHLENGTH bytes, got ${transportId.size}"
        }
        val header = ByteArray(2 + 2 * TRUNCATED_HASHLENGTH + 1)
        header[0] = flags.toByte()
        header[1] = hops.toByte()
        transportId.copyInto(header, 2)
        destHash.copyInto(header, 2 + TRUNCATED_HASHLENGTH)
        header[2 + 2 * TRUNCATED_HASHLENGTH] = context.toByte()
        return header + payload
    }

    val header = ByteArray(2 + TRUNCATED_HASHLENGTH + 1)
    header[0] = flags.toByte()
    header[1] = hops.toByte()
    destHash.copyInto(header, 2)
    header[2 + TRUNCATED_HASHLENGTH] = context.toByte()

    return header + payload
}
