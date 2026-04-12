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
        // HEADER_2: transport_id(16) + destination_hash(16)
        val transportId = data.copyOfRange(2, 2 + TRUNCATED_HASHLENGTH)
        val destHash    = data.copyOfRange(2 + TRUNCATED_HASHLENGTH, 2 + 2 * TRUNCATED_HASHLENGTH)
        val context     = data[2 + 2 * TRUNCATED_HASHLENGTH].toInt() and 0xFF
        val payload     = data.copyOfRange(2 + 2 * TRUNCATED_HASHLENGTH + 1, data.size)
        Packet(data, flags, hops, headerType, contextFlag, transportType,
               destType, packetType, destHash, transportId, context, payload)
    }
}

/**
 * Build a Reticulum packet from components. Always produces HEADER_1.
 *
 * Port of: reference/js-reference/reticulum.js buildPacket()
 */
fun buildPacket(
    headerType: Int = HEADER_1,
    contextFlag: Int = 0,
    transportType: Int = TRANSPORT_BROADCAST,
    destType: Int = DEST_SINGLE,
    packetType: Int = PACKET_DATA,
    hops: Int = 0,
    destHash: ByteArray,
    context: Int = CTX_NONE,
    payload: ByteArray = ByteArray(0),
): ByteArray {
    val flags = ((headerType and 0x01) shl 6) or
                ((contextFlag and 0x01) shl 5) or
                ((transportType and 0x01) shl 4) or
                ((destType and 0x03) shl 2) or
                (packetType and 0x03)

    val header = ByteArray(2 + TRUNCATED_HASHLENGTH + 1)
    header[0] = flags.toByte()
    header[1] = hops.toByte()
    destHash.copyInto(header, 2)
    header[2 + TRUNCATED_HASHLENGTH] = context.toByte()

    return header + payload
}
