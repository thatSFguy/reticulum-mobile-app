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
import io.github.thatsfguy.reticulum.transport.CMD_DATA
import io.github.thatsfguy.reticulum.transport.CMD_STAT_RSSI
import io.github.thatsfguy.reticulum.transport.CMD_STAT_SNR
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.KissParser
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.buildKissFrame
import io.github.thatsfguy.reticulum.transport.decodeRssi
import io.github.thatsfguy.reticulum.transport.decodeSnr
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
 * Nordic UART Service (NUS) BLE transport. Talks to an RNode (or any
 * NUS-speaking device) by:
 *
 *   - opening a GATT connection to a previously-discovered [BluetoothDevice]
 *   - discovering the NUS service + TX/RX characteristics
 *   - requesting a 247-byte MTU (KISS frames may need multiple notifications)
 *   - enabling notifications on the RX characteristic
 *   - feeding inbound bytes into a [KissParser] and emitting CMD_DATA frames
 *     as [IncomingPacket]s, attaching the most recent RSSI/SNR values
 *   - on send, KISS-framing the outbound packet (CMD_DATA) and writing to
 *     the TX characteristic in MTU-sized chunks (no-response writes)
 *
 * Permissions are not requested here — Activity/Service layer must hold
 * BLUETOOTH_CONNECT (and BLUETOOTH_SCAN if scanning was used to find the
 * device) before constructing this transport.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23 // ATT minimum

    /** RSSI/SNR sidecar values received just before the next CMD_DATA frame. */
    private var pendingRssi: Int? = null
    private var pendingSnr: Double? = null

    private val writeLock = Mutex()

    private val parser = KissParser { cmd, payload ->
        when (cmd) {
            CMD_STAT_RSSI -> if (payload.isNotEmpty()) pendingRssi = decodeRssi(payload[0])
            CMD_STAT_SNR  -> if (payload.isNotEmpty()) pendingSnr  = decodeSnr(payload[0])
            CMD_DATA      -> {
                _incoming.tryEmit(IncomingPacket(packet = payload, rssi = pendingRssi, snr = pendingSnr))
                pendingRssi = null
                pendingSnr  = null
            }
            // Ignore other RNode info frames (CMD_BOARD, CMD_FW_VERSION, etc.)
            else -> Unit
        }
    }

    // Each callback completion resumes the corresponding suspending operation.
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
                    val err = if (status != BluetoothGatt.GATT_SUCCESS)
                        IllegalStateException("BLE disconnected, status=$status") else null
                    if (err != null) {
                        connectContinuation?.resumeWithException(err)
                    } else {
                        // Resume connect if it was waiting; otherwise propagate normal disconnect
                        connectContinuation?.resumeWithException(IllegalStateException("BLE disconnected before ready"))
                    }
                    connectContinuation = null
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

        // API 33+ (Android 13+) delivers notification bytes via this 3-arg
        // callback; the deprecated 2-arg below sees a null characteristic.value
        // on those versions, so inbound dies if only the 2-arg is overridden
        // (confirmed on a Galaxy A42 / API 33). Keep both: 3-arg fires on 33+,
        // 2-arg on older.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            parser.feed(value)
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
        try {
            connectAndDiscover()
            requestMtu(247)
            findNusCharacteristics()
            enableRxNotifications()
            parser.reset()
            _state.value = TransportState.Connected
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            disconnectInternal()
            throw t
        }
    }

    private suspend fun connectAndDiscover() {
        // GATT auto-disconnect/reconnect is the caller's responsibility; we use auto=false.
        val g = device.connectGatt(context, false, callback)
        gatt = g

        // Wait for ServicesDiscovered (which we trigger from STATE_CONNECTED).
        // The connect callback may chain into the services callback before we
        // suspend, so we suspend on services rather than the raw connection.
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
        val service = gatt?.getService(NUS_SERVICE_UUID)
            ?: throw IllegalStateException("NUS service ${NUS_SERVICE_UUID} not found on device")
        txChar = service.getCharacteristic(NUS_TX_UUID)
            ?: throw IllegalStateException("NUS TX characteristic not found")
        rxChar = service.getCharacteristic(NUS_RX_UUID)
            ?: throw IllegalStateException("NUS RX characteristic not found")
    }

    private suspend fun enableRxNotifications() {
        val rx = rxChar ?: error("RX char missing")
        val g = gatt ?: error("GATT missing")
        if (!g.setCharacteristicNotification(rx, true)) {
            throw IllegalStateException("setCharacteristicNotification(true) returned false")
        }
        val cccd = rx.getDescriptor(CCCD_UUID)
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
        sendKissCommand(CMD_DATA, packet)
    }

    /** Send any KISS command (radio config, blink, etc.) to the attached
     *  RNode. Caller specifies the command byte; payload encoding is
     *  command-specific (uint32 BE for frequency/bandwidth, single byte
     *  for SF/CR/TX/state, raw packet bytes for CMD_DATA). */
    suspend fun sendKissCommand(cmd: Int, payload: ByteArray) {
        val frame = buildKissFrame(cmd, payload)
        val tx = txChar ?: error("BleTransport not connected")
        val g  = gatt   ?: error("BleTransport not connected")
        // ATT MTU - 3 bytes of overhead = max useful payload per write
        val chunkSize = (negotiatedMtu - 3).coerceAtLeast(20)

        writeLock.withLock {
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(frame.size, offset + chunkSize)
                val chunk = frame.copyOfRange(offset, end)
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                tx.value = chunk
                // Android's BLE stack has a small internal queue for
                // no-response writes; consecutive writes during radio
                // config can return false if the queue is briefly busy.
                // Retry with a short backoff before giving up — much
                // more reliable than failing the whole config sequence
                // on a transient busy.
                var attempts = 0
                while (!g.writeCharacteristic(tx)) {
                    attempts++
                    if (attempts >= 5) {
                        throw IllegalStateException(
                            "writeCharacteristic returned false after $attempts attempts " +
                                "(cmd=0x${cmd.toString(16)} offset=$offset chunkSize=${chunk.size})"
                        )
                    }
                    kotlinx.coroutines.delay(50)
                }
                offset = end
            }
        }
    }

    /**
     * Push the LoRa radio config to the RNode and turn the radio on.
     *
     * Mirrors RNS `RNodeInterface.initRadio()` — the exact sequence
     * Sideband uses (issue #18): detect → freq → bw → txpower → sf → cr
     * → radio_state(on), followed by a settle before the link is used.
     * Two deliberate alignments with upstream vs. our older sequence:
     *
     *  - We send CMD_DETECT first. RNS gates radio config on a DETECT_RESP;
     *    we send it best-effort (no hard gate) so a slow/older firmware
     *    that works today can't regress into a failed connect. The reply
     *    is a non-DATA frame the KissParser already ignores.
     *  - TX power is set before SF/CR (RNS order), and we pause ~2s after
     *    radio_state on — RNS does `if use_ble: sleep(2)` after initRadio
     *    to let the SX1262 leave the config transient and settle into RX
     *    before any traffic. Attaching + announcing immediately (our old
     *    behavior) races that transition on some firmware/hardware.
     *
     * Each command is fire-and-forget with a small inter-command pause so
     * the firmware can apply each setting before the next one lands.
     */
    suspend fun applyRadioConfig(config: RadioConfig) {
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_DETECT, byteArrayOf(io.github.thatsfguy.reticulum.transport.DETECT_REQ.toByte()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_FREQUENCY, uint32BE(config.frequencyHz.toLong()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_BANDWIDTH, uint32BE(config.bandwidthHz.toLong()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_TXPOWER, byteArrayOf(config.txPowerDbm.toByte()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_SF, byteArrayOf(config.spreadingFactor.toByte()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_CR, byteArrayOf(config.codingRate.toByte()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_RADIO_STATE, byteArrayOf(0x01))
        // Settle before the engine attaches and starts sending — see above.
        kotlinx.coroutines.delay(2000)
    }

    private fun uint32BE(v: Long): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr  8) and 0xFF).toByte(),
        ( v          and 0xFF).toByte(),
    )

    companion object {
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_UUID:      UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_UUID:      UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID:        UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Resolve a BLE [BluetoothDevice] by MAC address. Caller holds BLUETOOTH_CONNECT. */
        @SuppressLint("MissingPermission")
        fun deviceByAddress(context: Context, address: String): BluetoothDevice {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return mgr.adapter.getRemoteDevice(address)
        }
    }
}
