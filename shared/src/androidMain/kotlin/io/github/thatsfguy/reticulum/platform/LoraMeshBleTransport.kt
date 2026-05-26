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
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.LM_CMD_CONFIG_REPLY
import io.github.thatsfguy.reticulum.transport.LM_CMD_DATA_RX
import io.github.thatsfguy.reticulum.transport.LM_CMD_DATA_TX
import io.github.thatsfguy.reticulum.transport.LM_CMD_DIAG_EVENT
import io.github.thatsfguy.reticulum.transport.LM_CMD_NODE_INFO_REQ
import io.github.thatsfguy.reticulum.transport.LM_CMD_REGISTER_IDENTITY
import io.github.thatsfguy.reticulum.transport.LoraMeshKissParser
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.buildLoraMeshFrame
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE transport for a reticulum-loramesh firmware node.
 *
 * Shares the Nordic UART Service UUIDs with [BleTransport] (the
 * firmware reuses an off-the-shelf BLE NUS implementation) but speaks
 * the firmware's custom KISS-CRC16 dialect — see [LoraMeshKissParser]
 * / [buildLoraMeshFrame] and the spec at
 * `docs/mobile_ble_integration.md` §3-4.
 *
 * Key differences vs [BleTransport]:
 *   - Every frame is CRC-protected (CRC-16/CCITT-FALSE); bad-CRC
 *     frames are dropped at the parser, not punted up the stack.
 *   - On every connect we MUST send `REGISTER_IDENTITY` with the
 *     local destination hash so the firmware emits `ANNOUNCE_FORWARD`
 *     frames that claim this identity to the mesh. The firmware does
 *     not persist host-identity registration across reboots
 *     (battery swap, OTA), so re-registering unconditionally is the
 *     safest policy (spec §5 "MUST do" + §7 reconnect detection).
 *   - Outgoing DATA_TX frames carry a 16-byte `dst_identity_hash`
 *     prefix before the Reticulum bytes. For v1 we ship all-zero
 *     and let the firmware's broadcast-flood fallback route — see
 *     spec §10 open question #1.
 *   - Incoming DATA_RX frames carry a 2-byte `src_node` prefix that
 *     must be stripped before handing the bytes to the engine.
 *   - No RSSI/SNR sidecar. The firmware abstracts multi-hop routing
 *     so per-message radio metrics aren't meaningful at this layer;
 *     [IncomingPacket.rssi] / `.snr` are always `null`.
 *
 * Permissions: Activity/Service holds BLUETOOTH_CONNECT
 * (and BLUETOOTH_SCAN if scanning was used) before constructing.
 */
@SuppressLint("MissingPermission")
class LoraMeshBleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    /** Local Reticulum destination hash (16 bytes) to register with
     *  the mesh on every connect. Caller resolves this from the
     *  engine's identity before constructing the transport — without
     *  it the firmware will discard our packets as unaddressed.
     *  Spec §4 REGISTER_IDENTITY + §5 "MUST do". */
    private val localIdentityHash: ByteArray,
    private val logger: (String) -> Unit = {},
) : Transport {

    init {
        require(localIdentityHash.size == 16) {
            "localIdentityHash must be 16 bytes, got ${localIdentityHash.size}"
        }
    }

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null  // notifies us (firmware TX)
    private var txChar: BluetoothGattCharacteristic? = null  // we write here  (firmware RX)
    private var negotiatedMtu: Int = 23

    private val writeLock = Mutex()

    private val parser = LoraMeshKissParser(
        onFrame = { cmd, payload -> handleFrame(cmd, payload) },
        // Hex-dump the rejected frame so we can compare bytes
        // against the firmware reference when CRC fails on the
        // wire. Without this every BadCrc looks identical in logs
        // and there's nothing to diagnose against. Added v1.2.27
        // chasing the first-frame-after-connect BadCrc seen in
        // v1.2.26 against rlm-fa11bf.
        onError = { err, bytes -> logger("loramesh: decode error: $err (${bytes.size}B) ${bytes.toHex()}") },
    )

    private fun handleFrame(cmd: Int, payload: ByteArray) {
        when (cmd) {
            LM_CMD_DATA_RX -> {
                // Strip the 2-byte src_node prefix before passing
                // the bytes up — without this RNS.Transport.inbound
                // sees a packet with two extra bytes glued to the
                // header byte and silently drops it. Spec §4 DATA_RX
                // payload layout.
                if (payload.size <= 2) {
                    logger("loramesh: DATA_RX too short (${payload.size} B)")
                    return
                }
                val rnsBytes = payload.copyOfRange(2, payload.size)
                _incoming.tryEmit(IncomingPacket(packet = rnsBytes, rssi = null, snr = null))
            }
            LM_CMD_DIAG_EVENT -> {
                // ASCII status line — surface to caller's logger.
                logger("loramesh DIAG: ${payload.decodeToString()}")
            }
            LM_CMD_CONFIG_REPLY -> {
                // NODE_INFO_REQ reply is a single-line ASCII status
                // (firmware Phase 6); Phase 7+ becomes msgpack.
                logger("loramesh CONFIG: ${payload.decodeToString()}")
            }
            else -> {
                // Future opcodes (0x80..0xFF reserved for extensions).
                logger("loramesh: unhandled cmd=0x${cmd.toString(16)}")
            }
        }
    }

    private var connectContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    private var servicesContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    private var mtuContinuation: kotlinx.coroutines.CancellableContinuation<Int>? = null
    private var descWriteContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = TransportState.Connecting
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = TransportState.Disconnected
                    val err = IllegalStateException("BLE disconnected, status=$status")
                    connectContinuation?.resumeWithException(err)
                    connectContinuation = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesContinuation?.resumeWithException(
                    IllegalStateException("Service discovery failed: $status"),
                )
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
                mtuContinuation?.resume(negotiatedMtu)
            }
            mtuContinuation = null
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descWriteContinuation?.resume(Unit)
            } else {
                descWriteContinuation?.resumeWithException(
                    IllegalStateException("CCCD write failed: $status"),
                )
            }
            descWriteContinuation = null
        }

        @Deprecated("Pre-API-33 callback, kept for compatibility with minSdk 26.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            // Per-notification raw hex dump — capped to a length the
            // adb logcat ring buffer can comfortably hold. The parser
            // already handles cross-notification frame assembly, but
            // when CRC is failing the WIRE bytes are what we need to
            // see (a per-chunk view shows whether the firmware's
            // BLEUart is dropping bytes mid-frame). Added v1.2.27.
            logger("loramesh rx ${data.size}B: ${data.toHex()}")
            parser.feed(data)
        }
    }

    override suspend fun connect() {
        if (_state.value == TransportState.Connected) return
        _state.value = TransportState.Connecting
        try {
            connectAndDiscover()
            requestMtu(247)
            findNusCharacteristics()
            enableRxNotifications()
            parser.reset()
            _state.value = TransportState.Connected

            // Spec §5: "Send REGISTER_IDENTITY after every reconnect."
            // Firmware does not persist host-identity registration; we
            // re-register unconditionally rather than try to detect
            // whether we already did.
            sendKissCommand(LM_CMD_REGISTER_IDENTITY, localIdentityHash)
            // NODE_INFO_REQ is optional but it's a cheap way to confirm
            // the host link is alive end-to-end on every connect, and
            // the reply lands in DIAG_EVENT for the user's diagnostics view.
            sendKissCommand(LM_CMD_NODE_INFO_REQ, ByteArray(0))
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            disconnectInternal()
            throw t
        }
    }

    private suspend fun connectAndDiscover() {
        val g = device.connectGatt(context, false, callback)
        gatt = g
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
        // The firmware uses the same Nordic UART Service UUIDs as the
        // RNode BLE path — see [BleTransport.NUS_SERVICE_UUID]. We
        // reuse those constants rather than re-declaring them so the
        // scanner can filter by a single service UUID and let the
        // user disambiguate at pick time (RNode vs `rlm-xxxxxx`).
        val service = gatt?.getService(BleTransport.NUS_SERVICE_UUID)
            ?: throw IllegalStateException("NUS service not found on LoraMesh device")
        // Nordic convention: "TX" is what the PERIPHERAL sends — the
        // characteristic the central (us) subscribes for notifications.
        // The firmware doc lists characteristic 0x2 as "RX (firmware
        // receives)" → we write there. 0x3 is "TX (firmware notifies)" →
        // we subscribe there. Reusing the UUIDs from [BleTransport] but
        // with the LoraMesh semantics; same wire UUIDs, same direction.
        txChar = service.getCharacteristic(BleTransport.NUS_TX_UUID)
            ?: throw IllegalStateException("NUS write characteristic not found")
        rxChar = service.getCharacteristic(BleTransport.NUS_RX_UUID)
            ?: throw IllegalStateException("NUS notify characteristic not found")
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
        // DATA_TX wire format: dst_identity_hash[16] || reticulum_bytes.
        // v1 ships all-zero dst hash and lets the firmware's
        // broadcast-flood fallback route. Spec §5 + §10 question #1.
        val frame = ByteArray(16 + packet.size)
        // zero-init is the default; just append the packet.
        packet.copyInto(frame, destinationOffset = 16)
        sendKissCommand(LM_CMD_DATA_TX, frame)
    }

    private suspend fun sendKissCommand(cmd: Int, payload: ByteArray) {
        val frame = buildLoraMeshFrame(cmd, payload)
        val tx = txChar ?: error("LoraMeshBleTransport not connected")
        val g  = gatt   ?: error("LoraMeshBleTransport not connected")
        val chunkSize = (negotiatedMtu - 3).coerceAtLeast(20)

        writeLock.withLock {
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(frame.size, offset + chunkSize)
                val chunk = frame.copyOfRange(offset, end)
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                tx.value = chunk
                var attempts = 0
                while (!g.writeCharacteristic(tx)) {
                    attempts++
                    if (attempts >= 5) {
                        throw IllegalStateException(
                            "writeCharacteristic returned false after $attempts attempts " +
                                "(cmd=0x${cmd.toString(16)} offset=$offset chunkSize=${chunk.size})",
                        )
                    }
                    kotlinx.coroutines.delay(50)
                }
                offset = end
            }
        }
    }

    companion object {
        /** Advertised-name prefix the firmware uses (`rlm-<6 hex>`).
         *  Used by the scanner to discriminate LoraMesh devices from
         *  RNode devices — both advertise the same NUS service UUID. */
        const val ADVERTISED_NAME_PREFIX = "rlm-"

        @SuppressLint("MissingPermission")
        fun deviceByAddress(context: Context, address: String): BluetoothDevice {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return mgr.adapter.getRemoteDevice(address)
        }
    }
}
