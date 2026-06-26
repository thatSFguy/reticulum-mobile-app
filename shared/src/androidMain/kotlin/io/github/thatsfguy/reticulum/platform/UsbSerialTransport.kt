package io.github.thatsfguy.reticulum.platform

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.thatsfguy.reticulum.platform.usbserial.RNODE_USB_BAUD
import io.github.thatsfguy.reticulum.platform.usbserial.UsbSerialPort
import io.github.thatsfguy.reticulum.platform.usbserial.UsbSerialProber
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * USB-serial transport for a USB-attached RNode (issue #41). Same
 * KISS-over-serial wire format as [BtClassicTransport] and [BleTransport],
 * carried over a USB-OTG cable instead of Bluetooth. A wired link can't be
 * eavesdropped over the air and doesn't need Bluetooth permissions.
 *
 * **EXPERIMENTAL — not yet verified against hardware.** USB endpoint I/O
 * can't run in CI (no device), so this ships behind the `usbEnabled` toggle
 * (default off) per CLAUDE.md RULE #1. Supported chips: CDC-ACM and CP210x
 * (see [io.github.thatsfguy.reticulum.platform.usbserial.UsbSerialProber]);
 * CH34x / FTDI are TODO for the device-in-hand pass.
 *
 * USB permission MUST already be granted (via [UsbManager.requestPermission])
 * before [connect] — the service handles that handshake. Threading mirrors
 * BtClassic: the read loop is a child of [scope] on [Dispatchers.IO], and
 * cancellation closes the connection so the blocking bulk read unwinds.
 * Device-detach is detected by the service's USB-detach receiver, which
 * calls [disconnect] (the bulk read alone can't tell idle from unplug).
 */
class UsbSerialTransport(
    private val context: Context,
    private val device: UsbDevice,
    private val scope: CoroutineScope,
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    private var port: UsbSerialPort? = null
    private var readJob: Job? = null
    private val writeLock = Mutex()

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
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usbManager.hasPermission(device)) {
                error("USB permission not granted for ${device.deviceName}")
            }
            val p = withContext(Dispatchers.IO) {
                val conn = usbManager.openDevice(device)
                    ?: error("Failed to open USB device ${device.deviceName}")
                UsbSerialProber.open(device, conn, RNODE_USB_BAUD)
                    ?: run {
                        conn.close()
                        error("No USB-serial driver for VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
                    }
            }
            port = p
            parser.reset()

            readJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(4096)
                try {
                    while (isActive) {
                        val n = p.read(buf, READ_TIMEOUT_MS)
                        if (n > 0) parser.feed(buf.copyOf(n))
                        // n <= 0 is an idle timeout (bulkTransfer can't
                        // distinguish idle from unplug); keep looping.
                        // disconnect()/the service detach receiver ends us.
                    }
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

    /** Send any KISS command to the attached RNode over USB serial.
     *  Mirrors [BtClassicTransport.sendKissCommand]. */
    suspend fun sendKissCommand(cmd: Int, payload: ByteArray) {
        val frame = buildKissFrame(cmd, payload)
        val p = port ?: error("UsbSerialTransport not connected")
        writeLock.withLock {
            withContext(Dispatchers.IO) { p.write(frame, WRITE_TIMEOUT_MS) }
        }
    }

    /** Push the LoRa radio config to the RNode — same shared sequence as
     *  [BtClassicTransport.applyRadioConfig]. */
    suspend fun applyRadioConfig(config: RadioConfig) {
        val cmds = io.github.thatsfguy.reticulum.transport.rnodeRadioInitCommands(config)
        cmds.forEachIndexed { i, (cmd, payload) ->
            sendKissCommand(cmd, payload)
            if (i < cmds.lastIndex) {
                kotlinx.coroutines.delay(io.github.thatsfguy.reticulum.transport.RNODE_INIT_INTERCMD_DELAY_MS)
            }
        }
        kotlinx.coroutines.delay(io.github.thatsfguy.reticulum.transport.RNODE_INIT_SETTLE_MS)
    }

    private fun closeQuietly() {
        runCatching { port?.close() }
        port = null
    }

    companion object {
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 2000

        /** Resolve an attached [UsbDevice] by its system device name
         *  (e.g. `/dev/bus/usb/001/002`). Returns null if it's no longer
         *  attached. */
        fun deviceByName(context: Context, deviceName: String): UsbDevice? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
        }
    }
}
