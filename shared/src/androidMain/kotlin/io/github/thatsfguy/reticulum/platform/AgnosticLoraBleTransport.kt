package io.github.thatsfguy.reticulum.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.github.thatsfguy.reticulum.transport.AgnosticLoraTunnel
import io.github.thatsfguy.reticulum.transport.HdlcParser
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.buildHdlcFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE transport for an **agnostic-LoRa-Net** node tunnel
 * (SPEC: `agnostic-lora-net/docs/tcp-bridge.md`).
 *
 * Wire-wise this is [TcpInterface]-over-BLE: it carries raw Reticulum
 * packets, framed with HDLC, but each frame is wrapped in the node's typed
 * tunnel envelope (`[0x01][len][uplink locator LE][packet]`, see
 * [AgnosticLoraTunnel]). Above this transport the app is identical to a TCP
 * hub — announces flow in, contacts/nodes populate, messages send/receive —
 * because the payload is opaque Reticulum and the mesh routes it for us.
 *
 * GATT mechanics mirror [BleTransport] (same Nordic UART Service, same MTU
 * negotiation and chunked no-response writes), with two differences:
 *   - Framing is HDLC + the tunnel envelope, not KISS. Tunnel mode is
 *     automatic over BLE — no `tunnel\n` switch (that's the USB path).
 *   - No RSSI/SNR sidecar: the firmware abstracts the SX1262, so
 *     [IncomingPacket] always reports `rssi = null, snr = null` (same as the
 *     TCP path).
 *
 * The [uplinkNodeId] is the single mesh node this transport addresses — the
 * BLE equivalent of a TCP hub's host:port. Every outbound packet is stamped
 * with it; RNS Transport routes the rest of the network behind that node.
 * It is the node-id hex (`"9828F51B"`), typically auto-filled from the
 * scanned `AgnLoRa-<id>` advertised name.
 *
 * Permissions are the caller's responsibility — hold BLUETOOTH_CONNECT
 * (and BLUETOOTH_SCAN if a scan found the device) before constructing this.
 */
@SuppressLint("MissingPermission")
class AgnosticLoraBleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val uplinkNodeId: String,
    private val logger: (String) -> Unit = {},
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    /** Uplink locator in wire form (LE bytes). Resolved in [connect] so a
     *  malformed id surfaces as a clean IllegalArgumentException there. */
    private var uplinkLocator: ByteArray = ByteArray(0)

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23 // ATT minimum

    private val writeLock = Mutex()

    private val parser = HdlcParser { frame ->
        // The HDLC frame body is the tunnel envelope; strip it to the raw
        // Reticulum packet. Non-LOCATOR / truncated frames decode to null
        // and are dropped (matches the firmware's own tunnel_rx_frame).
        AgnosticLoraTunnel.decodeFrame(frame)?.let { packet ->
            _incoming.tryEmit(IncomingPacket(packet = packet, rssi = null, snr = null))
        }
    }

    // Each callback completion resumes the corresponding suspending operation.
    private var servicesContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    private var mtuContinuation: kotlinx.coroutines.CancellableContinuation<Int>? = null
    private var descWriteContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    private var connectContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = TransportState.Connecting
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = TransportState.Disconnected
                    connectContinuation?.resumeWithException(
                        IllegalStateException("BLE disconnected (status=$status) before ready"),
                    )
                    connectContinuation = null
                    servicesContinuation?.resumeWithException(
                        IllegalStateException("BLE disconnected (status=$status) before service discovery"),
                    )
                    servicesContinuation = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesContinuation?.resumeWithException(IllegalStateException("Service discovery failed: $status"))
            } else {
                servicesContinuation?.resume(Unit)
            }
            servicesContinuation = null
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                mtuContinuation?.resume(mtu)
            } else {
                mtuContinuation?.resume(negotiatedMtu) // proceed with default
            }
            mtuContinuation = null
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descWriteContinuation?.resume(Unit)
            } else {
                descWriteContinuation?.resumeWithException(IllegalStateException("CCCD write failed: $status"))
            }
            descWriteContinuation = null
        }

        @Deprecated("Pre-API-33 callback, kept for compatibility with minSdk 26.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            parser.feed(data)
        }
    }

    override suspend fun connect() {
        if (_state.value == TransportState.Connected) return
        _state.value = TransportState.Connecting
        // Resolve the uplink locator up front — a bad node id is a config
        // error, not a transient, so fail fast before touching the radio.
        uplinkLocator = AgnosticLoraTunnel.locatorFromHex(uplinkNodeId)
            ?: throw IllegalArgumentException(
                "Invalid uplink node id '$uplinkNodeId' — expected ${AgnosticLoraTunnel.NODE_ID_BYTES * 2} hex digits",
            )
        try {
            connectAndDiscover()
            requestMtu(247)
            findNusCharacteristics()
            enableRxNotifications()
            parser.reset()
            logger("AgnLoRa: tunnel ready (uplink $uplinkNodeId)")
            _state.value = TransportState.Connected
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            disconnectInternal()
            throw t
        }
    }

    private suspend fun connectAndDiscover() {
        val g = device.connectGatt(context, false, callback)
        gatt = g
        // Suspend on service discovery — the connect callback chains
        // straight into discoverServices(), so waiting on services covers
        // the whole connect → discovered handshake.
        suspendCancellableCoroutine<Unit> { cont ->
            servicesContinuation = cont
            cont.invokeOnCancellation { disconnectInternal() }
        }
    }

    private suspend fun requestMtu(target: Int): Int =
        suspendCancellableCoroutine { cont ->
            mtuContinuation = cont
            val ok = gatt?.requestMtu(target) ?: false
            if (!ok) {
                mtuContinuation = null
                cont.resume(negotiatedMtu)
            }
        }

    private fun findNusCharacteristics() {
        val service = gatt?.getService(BleTransport.NUS_SERVICE_UUID)
            ?: throw IllegalStateException("NUS service ${BleTransport.NUS_SERVICE_UUID} not found on device")
        txChar = service.getCharacteristic(BleTransport.NUS_TX_UUID)
            ?: throw IllegalStateException("NUS TX characteristic not found")
        rxChar = service.getCharacteristic(BleTransport.NUS_RX_UUID)
            ?: throw IllegalStateException("NUS RX characteristic not found")
    }

    private suspend fun enableRxNotifications() {
        val rx = rxChar ?: error("RX char missing")
        val g = gatt ?: error("GATT missing")
        if (!g.setCharacteristicNotification(rx, true)) {
            throw IllegalStateException("setCharacteristicNotification(true) returned false")
        }
        val cccd = rx.getDescriptor(BleTransport.CCCD_UUID)
            ?: throw IllegalStateException("RX has no CCCD")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        suspendCancellableCoroutine<Unit> { cont ->
            descWriteContinuation = cont
            if (!g.writeDescriptor(cccd)) {
                descWriteContinuation = null
                cont.resumeWithException(IllegalStateException("writeDescriptor returned false"))
            }
        }
    }

    override suspend fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        txChar = null
        rxChar = null
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(packet: ByteArray) {
        val tx = txChar ?: error("AgnosticLoraBleTransport not connected")
        val g  = gatt   ?: error("AgnosticLoraBleTransport not connected")
        // [type][len][uplink locator][packet] → HDLC.
        val frame = buildHdlcFrame(AgnosticLoraTunnel.encodeLocatorFrame(uplinkLocator, packet))
        // ATT MTU - 3 bytes of overhead = max useful payload per write.
        val chunkSize = (negotiatedMtu - 3).coerceAtLeast(20)

        writeLock.withLock {
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(frame.size, offset + chunkSize)
                val chunk = frame.copyOfRange(offset, end)
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                tx.value = chunk
                // Android's no-response write queue can briefly report busy;
                // retry with a short backoff before giving up (same approach
                // as BleTransport's KISS writer).
                var attempts = 0
                while (!g.writeCharacteristic(tx)) {
                    attempts++
                    if (attempts >= 5) {
                        throw IllegalStateException(
                            "writeCharacteristic returned false after $attempts attempts " +
                                "(offset=$offset chunkSize=${chunk.size})",
                        )
                    }
                    kotlinx.coroutines.delay(50)
                }
                offset = end
            }
        }
    }

    companion object {
        /** BLE advertised-name prefix these nodes use (`AgnLoRa-<id>`). */
        const val ADVERTISED_NAME_PREFIX = AgnosticLoraTunnel.ADVERTISED_NAME_PREFIX

        /** Resolve a BLE [BluetoothDevice] by MAC address. Caller holds BLUETOOTH_CONNECT. */
        @SuppressLint("MissingPermission")
        fun deviceByAddress(context: Context, address: String): BluetoothDevice {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return mgr.adapter.getRemoteDevice(address)
        }
    }
}
