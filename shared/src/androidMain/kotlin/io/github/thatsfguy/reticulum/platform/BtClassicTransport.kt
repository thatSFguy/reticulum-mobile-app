package io.github.thatsfguy.reticulum.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM/SPP) transport. Talks to an RNode that
 * exposes the standard Serial Port Profile UUID — this is the same
 * KISS-over-serial wire format the BLE NUS path carries, just with a
 * full-duplex byte stream instead of GATT notifications.
 *
 * Why have this alongside [BleTransport]? RFCOMM doesn't suffer from
 * BLE's MTU chunking or per-write turnaround latency, so for chatty
 * radio config sequences and bulk Resource transfers it's noticeably
 * snappier. Some users will also have RNode firmware variants paired
 * only via Classic.
 *
 * Pairing flow: the user pairs the RNode in Android system Settings
 * first; the in-app picker just enumerates `bondedDevices`. We don't
 * run discovery here.
 *
 * Threading: read loop is a child of [scope] on [Dispatchers.IO]. The
 * cancellation mechanism is socket close — the blocking
 * [InputStream.read] unblocks with [java.io.IOException] when the
 * socket is closed from another thread, which is how `disconnect()`
 * propagates.
 */
@SuppressLint("MissingPermission")
class BtClassicTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null

    private val writeLock = Mutex()

    /** RSSI/SNR sidecar values received just before the next CMD_DATA frame. */
    private var pendingRssi: Int? = null
    private var pendingSnr: Double? = null

    private val parser = KissParser { cmd, payload ->
        when (cmd) {
            CMD_STAT_RSSI -> if (payload.isNotEmpty()) pendingRssi = decodeRssi(payload[0])
            CMD_STAT_SNR  -> if (payload.isNotEmpty()) pendingSnr  = decodeSnr(payload[0])
            CMD_DATA      -> {
                _incoming.tryEmit(IncomingPacket(packet = payload, rssi = pendingRssi, snr = pendingSnr))
                pendingRssi = null
                pendingSnr  = null
            }
            else -> Unit
        }
    }

    override suspend fun connect() {
        if (_state.value == TransportState.Connected ||
            _state.value == TransportState.Connecting) return
        _state.value = TransportState.Connecting
        try {
            // Discovery is heavyweight; if we kicked one off from elsewhere
            // it'll slow this RFCOMM connect to a crawl. Cancel it.
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            runCatching { mgr.adapter?.cancelDiscovery() }

            val s = withContext(Dispatchers.IO) {
                val sk = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sk.connect()
                sk
            }
            socket = s
            input  = s.inputStream
            output = s.outputStream
            parser.reset()

            readJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(4096)
                val ins = input ?: return@launch
                try {
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        parser.feed(buf.copyOf(n))
                    }
                    _state.value = TransportState.Disconnected
                } catch (t: Throwable) {
                    _state.value = TransportState.Error
                }
            }
            _state.value = TransportState.Connected
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            closeQuietly()
            throw t
        }
    }

    override suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        closeQuietly()
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(packet: ByteArray) {
        sendKissCommand(CMD_DATA, packet)
    }

    /** Send any KISS command (radio config, blink, etc.) to the attached
     *  RNode over RFCOMM. Mirrors [BleTransport.sendKissCommand]. */
    suspend fun sendKissCommand(cmd: Int, payload: ByteArray) {
        val frame = buildKissFrame(cmd, payload)
        val out = output ?: error("BtClassicTransport not connected")
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                out.write(frame)
                out.flush()
            }
        }
    }

    /** Push the LoRa radio config to the RNode. Same sequence as
     *  [BleTransport.applyRadioConfig], reusing [sendKissCommand] on the
     *  RFCOMM stream. The 120ms inter-command pause mirrors BLE so the
     *  firmware has time to apply each setting before the next lands. */
    suspend fun applyRadioConfig(config: RadioConfig) {
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_FREQUENCY, uint32BE(config.frequencyHz.toLong()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_BANDWIDTH, uint32BE(config.bandwidthHz.toLong()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_SF, byteArrayOf(config.spreadingFactor.toByte()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_CR, byteArrayOf(config.codingRate.toByte()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_TXPOWER, byteArrayOf(config.txPowerDbm.toByte()))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_RADIO_STATE, byteArrayOf(0x01))
    }

    private fun uint32BE(v: Long): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr  8) and 0xFF).toByte(),
        ( v          and 0xFF).toByte(),
    )

    private fun closeQuietly() {
        try { input?.close() } catch (_: Throwable) {}
        try { output?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        input = null
        output = null
        socket = null
    }

    companion object {
        /** Standard Serial Port Profile UUID. ESP32 SerialBT and most
         *  RNode Classic firmwares advertise SPP under this well-known UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Resolve a Classic [BluetoothDevice] by MAC address. Caller holds BLUETOOTH_CONNECT. */
        @SuppressLint("MissingPermission")
        fun deviceByAddress(context: Context, address: String): BluetoothDevice {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter = mgr.adapter
                ?: error("No Bluetooth adapter on this device")
            return adapter.getRemoteDevice(address)
        }
    }
}
