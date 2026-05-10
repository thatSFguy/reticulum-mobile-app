package io.github.thatsfguy.reticulum.platform

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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS BLE NUS transport. Mirrors the Android `BleTransport` contract
 * exactly so the engine treats the two interchangeably:
 *
 *   - opens a `CBCentralManager` + `CBPeripheral` connection
 *   - discovers the Nordic UART Service + TX/RX characteristics
 *   - enables notifications on the RX characteristic
 *   - feeds inbound bytes into a [KissParser] and emits CMD_DATA frames
 *     as [IncomingPacket]s, attaching the most recent RSSI/SNR sidecar
 *   - on send, KISS-frames the outbound packet (CMD_DATA) and writes
 *     to the TX characteristic chunked at the BLE MTU
 *
 * Caller responsibilities — same as Android's `BleTransport`:
 *   - Hold the `bluetooth-central` background mode in Info.plist (Phase
 *     4 wires this in `iosApp/project.yml`).
 *   - Discover the peripheral via `CBCentralManager.scanForPeripherals`
 *     OR retrieve it by stored identifier via
 *     `retrievePeripheralsWithIdentifiers`. This class doesn't scan;
 *     it takes an already-discovered peripheral and connects.
 *
 * Threading: CoreBluetooth invokes our delegate methods on whatever
 * dispatch queue we passed to `CBCentralManager.init(delegate:queue:)`
 * — the default is the main queue. Callbacks are thus serialized by
 * GCD and we don't need our own lock around the continuation refs.
 * Coroutines resumed from a CB callback land on Default dispatcher
 * by Kotlin's resume semantics.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBleTransport(
    private val central: CBCentralManager,
    private val peripheral: CBPeripheral,
    private val scope: CoroutineScope,
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    private var txChar: CBCharacteristic? = null
    private var rxChar: CBCharacteristic? = null

    /** RSSI/SNR sidecar values received just before the next CMD_DATA frame. */
    private var pendingRssi: Int? = null
    private var pendingSnr: Double? = null

    private val writeLock = Mutex()

    /** The [connect] suspending function parks here, waiting for the
     *  full chain (CB connect → service discovery → characteristic
     *  discovery → CCCD enable) to complete. Resumed from the
     *  delegate's `didUpdateNotificationStateFor` callback. */
    private var readyContinuation: CancellableContinuation<Unit>? = null

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

    // Single delegate object implementing both protocols so we hold one
    // strong reference (CB takes weak references to delegates).
    private val bleDelegate = object : NSObject(),
        CBCentralManagerDelegateProtocol,
        CBPeripheralDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            // Only meaningful before connect; we ignore later transitions.
            // If the Bluetooth radio is off when the user taps connect,
            // CB will fail the connect with .poweredOff and we surface
            // the error via didFailToConnectPeripheral.
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            // CB-level connection up; now discover the NUS service.
            didConnectPeripheral.delegate = this
            didConnectPeripheral.discoverServices(listOf(NUS_SERVICE_UUID))
        }

        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            failConnect("CB connect failed: ${error?.localizedDescription ?: "unknown"}")
        }

        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            _state.value = TransportState.Disconnected
            // If we were mid-connect and the peer dropped, surface the error.
            error?.let { failConnect("BLE disconnected during connect: ${it.localizedDescription}") }
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                failConnect("Service discovery failed: ${didDiscoverServices.localizedDescription}")
                return
            }
            val nus = peripheral.services?.filterIsInstance<CBService>()?.firstOrNull {
                it.UUID == NUS_SERVICE_UUID
            }
            if (nus == null) {
                failConnect("NUS service ${NUS_SERVICE_UUID.UUIDString} not advertised by peer")
                return
            }
            peripheral.discoverCharacteristics(
                listOf(NUS_TX_UUID, NUS_RX_UUID),
                forService = nus,
            )
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (error != null) {
                failConnect("Characteristic discovery failed: ${error.localizedDescription}")
                return
            }
            val chars = didDiscoverCharacteristicsForService.characteristics
                ?.filterIsInstance<CBCharacteristic>()
                ?: emptyList()
            txChar = chars.firstOrNull { it.UUID == NUS_TX_UUID }
            rxChar = chars.firstOrNull { it.UUID == NUS_RX_UUID }
            val rx = rxChar
            if (txChar == null || rx == null) {
                failConnect("NUS TX/RX characteristic missing on peer")
                return
            }
            peripheral.setNotifyValue(true, forCharacteristic = rx)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                failConnect("Enable-notifications failed: ${error.localizedDescription}")
                return
            }
            // Notifications on RX are now live — the link is fully ready.
            // Resume the connect() suspending function exactly once.
            parser.reset()
            _state.value = TransportState.Connected
            val cont = readyContinuation
            readyContinuation = null
            cont?.resume(Unit)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) return
            val data = didUpdateValueForCharacteristic.value ?: return
            val bytes = data.toByteArray()
            if (bytes.isNotEmpty()) parser.feed(bytes)
        }
    }

    init {
        central.delegate = bleDelegate
    }

    override suspend fun connect() {
        if (_state.value == TransportState.Connected) return
        _state.value = TransportState.Connecting
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                readyContinuation = cont
                cont.invokeOnCancellation { disconnectInternal() }
                central.connectPeripheral(peripheral, options = null)
            }
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            disconnectInternal()
            throw t
        }
    }

    private fun failConnect(reason: String) {
        _state.value = TransportState.Error
        val cont = readyContinuation
        readyContinuation = null
        cont?.resumeWithException(IllegalStateException(reason))
    }

    override suspend fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        runCatching { central.cancelPeripheralConnection(peripheral) }
        txChar = null
        rxChar = null
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(packet: ByteArray) {
        sendKissCommand(CMD_DATA, packet)
    }

    /**
     * Push the LoRa radio config to the RNode and turn the radio on.
     * Mirrors `BleTransport.applyRadioConfig` on Android: freq → bw →
     * sf → cr → txp → radio_state(on), each as a fire-and-forget KISS
     * command with a small inter-command delay so the firmware can
     * settle each setting before the next one lands.
     */
    suspend fun applyRadioConfig(config: RadioConfig) {
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_FREQUENCY, uint32BE(config.frequencyHz))
        kotlinx.coroutines.delay(120)
        sendKissCommand(io.github.thatsfguy.reticulum.transport.CMD_BANDWIDTH, uint32BE(config.bandwidthHz))
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

    /** Send any KISS command (radio config, blink, etc.). Mirrors
     *  Android's `BleTransport.sendKissCommand`.
     *
     *  Hardened against post-suspend stale state (tester report
     *  2026-05-10: app crashed on screen-unlock after a long lock).
     *  Two failure modes guarded:
     *    - txChar nil because the connection was torn down while the
     *      app was suspended → silently no-op + flip state to
     *      Disconnected so the engine's transport-state collector
     *      sees the change and tears down the link cleanly. Throwing
     *      here would propagate up an engine coroutine and (without
     *      the IosEngineFactory crashGuard) crash the app.
     *    - peripheral.state != .connected because iOS invalidated
     *      the connection during suspension. CBPeripheral.writeValue
     *      against a disconnected peripheral can raise an NSException
     *      that K/N's runtime translates to Throwable; same crash
     *      vector. Check before calling and bail gracefully.
     *    - peripheral.writeValue itself throwing for any other reason
     *      (unhandleable from the SDK level — wrap in runCatching). */
    suspend fun sendKissCommand(cmd: Int, payload: ByteArray) {
        val frame = buildKissFrame(cmd, payload)
        val tx = txChar
        if (tx == null) {
            _state.value = TransportState.Disconnected
            return
        }
        if (peripheral.state != CBPeripheralStateConnected) {
            _state.value = TransportState.Disconnected
            return
        }
        // CB's `maximumWriteValueLength(forType:)` returns the largest
        // payload it'll accept in one write; we chunk the KISS frame to
        // that size. -3 mirrors the ATT-MTU overhead Android subtracts.
        val mtu = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
            .toInt()
            .coerceAtLeast(23)
        val chunkSize = (mtu - 3).coerceAtLeast(20)

        writeLock.withLock {
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(frame.size, offset + chunkSize)
                val chunk = frame.copyOfRange(offset, end)
                val ok = runCatching {
                    peripheral.writeValue(
                        chunk.toNSData(),
                        forCharacteristic = tx,
                        type = CBCharacteristicWriteWithoutResponse,
                    )
                }
                if (ok.isFailure) {
                    // CB threw — peripheral likely invalid. Flip
                    // state so the engine notices, then bail.
                    _state.value = TransportState.Disconnected
                    return
                }
                offset = end
            }
        }
    }

    companion object {
        val NUS_SERVICE_UUID: CBUUID =
            CBUUID.UUIDWithString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_UUID: CBUUID =
            CBUUID.UUIDWithString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID: CBUUID =
            CBUUID.UUIDWithString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    }
}

// ---- NSData ↔ ByteArray helpers --------------------------------------

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), this.size.convert())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = this.length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), this.bytes, len.convert())
    }
    return out
}
