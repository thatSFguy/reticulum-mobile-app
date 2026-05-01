package io.github.thatsfguy.reticulum.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Common abstraction over every way the app can reach the Reticulum
 * network: BLE-attached RNode (KISS), USB-attached RNode (KISS), and
 * direct TCP attachment to an rnsd TCPServerInterface (HDLC).
 *
 * Implementations are responsible for framing. The bytes flowing in
 * and out of this interface are RAW Reticulum packets (i.e. starting
 * with the flags byte) — no KISS, no HDLC, no BLE GATT chunking.
 *
 * The shared Reticulum stack (announce parser, packet router, link
 * machine, LXMF) only ever sees [Transport] and never knows whether
 * the radio is across the room or across the internet.
 */
interface Transport {
    val state: StateFlow<TransportState>

    /** Stream of inbound Reticulum packets. Cold flow — collecting it
     *  starts the underlying read loop on the implementation's choice
     *  of dispatcher. The implementation is also free to keep one hot
     *  collector internally and replay through this flow. */
    val incoming: Flow<IncomingPacket>

    suspend fun connect()
    suspend fun disconnect()

    /** Send a raw Reticulum packet. The implementation handles framing
     *  (KISS for RNode-attached transports, HDLC for TCP). Returns when
     *  the bytes have been handed to the underlying transport, NOT when
     *  the remote has acknowledged — Reticulum's own proof/link flows
     *  cover delivery semantics on top of this. */
    suspend fun send(packet: ByteArray)
}

/**
 * One inbound packet plus whatever link-layer metadata the transport
 * could attach. RSSI/SNR are populated by the BLE/USB KISS path from
 * the RNode's CMD_STAT_RSSI / CMD_STAT_SNR sidecar frames; on TCP they
 * are always null because rnsd does not forward radio metrics.
 */
data class IncomingPacket(
    val packet: ByteArray,
    val rssi: Int? = null,
    val snr: Double? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is IncomingPacket &&
        packet.contentEquals(other.packet) &&
        rssi == other.rssi &&
        snr == other.snr

    override fun hashCode(): Int =
        (packet.contentHashCode() * 31 + (rssi ?: 0)) * 31 + (snr?.hashCode() ?: 0)
}

enum class TransportState { Disconnected, Connecting, Connected, Error }
