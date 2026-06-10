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
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.transport.AgnosticLoraRouter
import io.github.thatsfguy.reticulum.transport.AgnosticLoraTunnel
import io.github.thatsfguy.reticulum.transport.IncomingPacket
import io.github.thatsfguy.reticulum.transport.NusDemux
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.buildHdlcFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE transport for an **agnostic-LoRa-Net** node tunnel
 * (SPEC: `agnostic-lora-net/docs/tcp-bridge.md`, `mobile-app-testing.md` §0).
 *
 * Wire-wise this is [TcpInterface]-over-BLE: raw Reticulum packets, HDLC
 * framed, each wrapped in the node's typed tunnel envelope
 * (`[0x01][len][locator LE][packet]`, see [AgnosticLoraTunnel]).
 *
 * **Identity-addressed** (contract §0.5 *identity* mode): on every connect
 * we `register` our 16-byte RNS destination hash with the mesh's
 * distributed directory and poll `dirdump` to enumerate peers; the
 * [AgnosticLoraRouter] then routes each outbound packet to the node
 * currently serving its destination, fans announces out to every known
 * peer node, buffers until destinations resolve, and pins link traffic
 * to the node its LINKREQUEST traversed. [uplinkNodeId] is now an
 * *optional* fallback/gateway pin — not required, and deliberately not
 * auto-filled from the attached node (the §0.5 trap).
 *
 * The NUS stream is demuxed ([NusDemux]) into HDLC frames (tunnel) and
 * console text lines (`loc`/`registered`/dirdump rows → the router;
 * heartbeat → ignored). All writes — text commands and frames alike —
 * serialize through one lock (§0.6) and are chunked to ≤20 B (§0.2:
 * the node's per-write FIFO, not the ATT MTU, is the binding limit; a
 * 221B announce written in one 244B-budget chunk arrives truncated and
 * silently dies — the v1.2.51 incident).
 *
 * No RSSI/SNR sidecar: the firmware abstracts the SX1262, so
 * [IncomingPacket] always reports `rssi = null, snr = null`.
 *
 * Permissions are the caller's responsibility — hold BLUETOOTH_CONNECT
 * (and BLUETOOTH_SCAN if a scan found the device) before constructing this.
 */
@SuppressLint("MissingPermission")
class AgnosticLoraBleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    /** Our directory id: the 16-byte `lxmf.delivery` destination hash, 32 hex chars. */
    private val selfDestHashHex: String,
    /** Optional static fallback node (e.g. an RNS-bridge gateway). Blank/null = directory only. */
    private val uplinkNodeId: String?,
    crypto: CryptoProvider,
    private val logger: (String) -> Unit = {},
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    private val router = AgnosticLoraRouter(
        selfIdHex = selfDestHashHex,
        fallbackUplinkHex = uplinkNodeId,
        crypto = crypto,
    )

    /** Router state is touched from the BLE callback thread, the engine's
     *  send path, and the poll job — serialize it. */
    private val routerLock = Mutex()

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23 // ATT minimum
    private var pollJob: Job? = null

    private val writeLock = Mutex()

    private val demux = NusDemux(
        onFrame = { frame -> handleTunnelFrame(frame) },
        onTextLine = { line -> handleTextLine(line) },
    )

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
            // The peer-side debugging contract asks for this integer; writes
            // stay fixed at SAFE_WRITE_CHUNK regardless.
            logger("AgnLoRa: MTU negotiated = $negotiatedMtu (status=$status); write chunk fixed at ${SAFE_WRITE_CHUNK}B")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descWriteContinuation?.resume(Unit)
            } else {
                descWriteContinuation?.resumeWithException(IllegalStateException("CCCD write failed: $status"))
            }
            descWriteContinuation = null
        }

        // API 33+ (Android 13+) delivers the notification payload through this
        // 3-arg callback. The deprecated 2-arg one below sees a NULL
        // characteristic.value on these versions, so if we only override that,
        // inbound silently dies — confirmed on a Galaxy A42 (API 33): 0 rx
        // frames until this override was added, even with a healthy link.
        // Both are kept: the 3-arg fires on 33+, the 2-arg on older.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            demux.feed(value)
        }

        @Deprecated("Pre-API-33 callback, kept for compatibility with minSdk 26.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            demux.feed(data)
        }
    }

    override suspend fun connect() {
        if (_state.value == TransportState.Connected) return
        _state.value = TransportState.Connecting
        // Validate config up front — bad hex is a config error, not a
        // transient, so fail fast (IllegalArgumentException stops the
        // supervisor instead of spinning the radio).
        require(selfDestHashHex.length == 32 && selfDestHashHex.all { it.isHexDigit() }) {
            "Invalid self destination hash '$selfDestHashHex' — expected 32 hex digits"
        }
        val fallback = uplinkNodeId?.trim().orEmpty()
        if (fallback.isNotEmpty()) {
            requireNotNull(AgnosticLoraTunnel.locatorFromHex(fallback)) {
                "Invalid fallback node id '$fallback' — expected ${AgnosticLoraTunnel.NODE_ID_BYTES * 2} hex digits (or leave it blank)"
            }
        }
        try {
            connectAndDiscover()
            // Ask for the low-latency connection interval BEFORE anything else.
            // BLE defaults to a relaxed interval; under sustained LoRa TX some
            // centrals (notably Samsung) let the link lapse and the supervision
            // timer fires ~5s after each transmit (§0.8).
            requestConnectionPriorityHigh()
            requestMtu(247)
            findNusCharacteristics()
            enableRxNotifications()
            demux.reset()
            _state.value = TransportState.Connected
            // Directory bring-up (contract: register once per BLE session,
            // the serving node re-floods on its own every ~240s). Send the
            // router's normalized (uppercase) id — directory lookups are
            // case-sensitive, so registration and resolves must agree.
            writeText("register ${router.selfIdHex}")
            writeText("dirdump")
            startDirectoryPoll()
            logger(
                "AgnLoRa: tunnel ready (id ${router.selfIdHex}" +
                    (if (fallback.isNotEmpty()) ", fallback $fallback)" else ", directory addressing)"),
            )
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

    /** Request the low-latency connection interval. Fire-and-forget — the
     *  result arrives asynchronously via onConnectionUpdated and isn't worth
     *  blocking on; we just log whether the request was accepted. */
    private fun requestConnectionPriorityHigh() {
        val ok = gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) ?: false
        logger("AgnLoRa: requestConnectionPriority(HIGH) -> $ok")
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
        pollJob?.cancel()
        pollJob = null
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        txChar = null
        rxChar = null
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(packet: ByteArray) {
        check(txChar != null && gatt != null) { "AgnosticLoraBleTransport not connected" }
        when (val d = routerLock.withLock { router.routeOutbound(packet, nowMs()) }) {
            is AgnosticLoraRouter.RouteDecision.Send -> {
                for (node in d.targets) writeTunnelFrame(node, packet)
            }
            is AgnosticLoraRouter.RouteDecision.Buffered -> {
                val wanted = routerLock.withLock { router.resolveWanted() }
                logger("AgnLoRa: buffered ${packet.size}B until destination resolves (${wanted.size} wanted)")
                // Kick a resolve immediately rather than waiting for the poll.
                wanted.take(4).forEach { writeText("resolve $it") }
            }
            is AgnosticLoraRouter.RouteDecision.Deferred -> {
                logger("AgnLoRa: deferred ${packet.size}B — ${d.reason}")
            }
        }
    }

    /** Inbound HDLC frame body from the demux (BLE callback thread). */
    private fun handleTunnelFrame(frame: ByteArray) {
        val packet = AgnosticLoraTunnel.decodeFrame(frame) ?: run {
            logger("AgnLoRa rx: DROP ${frame.size}B (not LOCATOR / truncated)")
            return
        }
        val src = AgnosticLoraTunnel.sourceFromFrame(frame) ?: return
        logger("AgnLoRa rx: pkt ${packet.size}B from $src")
        _incoming.tryEmit(IncomingPacket(packet = packet, rssi = null, snr = null))
        scope.launch {
            val ev = routerLock.withLock { router.onInbound(src, packet, nowMs()) }
            if (ev != null) handleDirectoryEvent(ev)
        }
    }

    /** Console text line from the demux (BLE callback thread). */
    private fun handleTextLine(line: String) {
        scope.launch {
            val ev = routerLock.withLock { router.onTextLine(line, nowMs()) } ?: return@launch
            handleDirectoryEvent(ev)
        }
    }

    /** React to directory changes: greet new peer nodes with our cached
     *  announce, and flush sends that just became routable. */
    private suspend fun handleDirectoryEvent(ev: AgnosticLoraRouter.DirectoryEvent) {
        if (ev.summary.isNotEmpty()) logger("AgnLoRa: ${ev.summary}")
        for (node in ev.newPeerNodes) {
            val announce = routerLock.withLock { router.cachedAnnounceFor(node) } ?: continue
            logger("AgnLoRa: sending cached announce to new peer node $node")
            runCatching { writeTunnelFrame(node, announce) }
                .onFailure { logger("AgnLoRa: announce to $node failed: ${it.message}") }
        }
        if (ev.routesChanged) {
            val flushed = routerLock.withLock { router.drainRoutable(nowMs()) }
            for ((raw, node) in flushed) {
                logger("AgnLoRa: flushing buffered ${raw.size}B -> $node")
                runCatching { writeTunnelFrame(node, raw) }
                    .onFailure { logger("AgnLoRa: flush to $node failed: ${it.message}") }
            }
        }
    }

    /** Periodic directory upkeep: fast `resolve` retries while sends are
     *  buffered, slow `dirdump` re-enumeration otherwise. */
    private fun startDirectoryPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var sinceDirdumpMs = 0L
            while (isActive && _state.value == TransportState.Connected) {
                delay(POLL_TICK_MS)
                sinceDirdumpMs += POLL_TICK_MS
                runCatching {
                    if (routerLock.withLock { router.hasPending() }) {
                        routerLock.withLock { router.resolveWanted() }.take(4).forEach {
                            writeText("resolve $it")
                        }
                    }
                    if (sinceDirdumpMs >= DIRDUMP_INTERVAL_MS) {
                        sinceDirdumpMs = 0L
                        writeText("dirdump")
                    }
                }.onFailure { logger("AgnLoRa: directory poll error: ${it.message}") }
            }
        }
    }

    private suspend fun writeTunnelFrame(nodeHex: String, packet: ByteArray) {
        val locator = AgnosticLoraTunnel.locatorFromHex(nodeHex)
            ?: throw IllegalStateException("unroutable node id '$nodeHex'")
        val frame = buildHdlcFrame(AgnosticLoraTunnel.encodeLocatorFrame(locator, packet))
        logger("AgnLoRa tx: pkt ${packet.size}B -> $nodeHex (frame ${frame.size}B, ${(frame.size + SAFE_WRITE_CHUNK - 1) / SAFE_WRITE_CHUNK} chunks)")
        writeRaw(frame)
    }

    private suspend fun writeText(line: String) {
        writeRaw((line + "\n").encodeToByteArray())
    }

    /** Chunked no-response write. SPEC `mobile-app-testing.md` §0.2: chunk
     *  to ≤20 B and let the node reassemble byte-by-byte from its FIFO —
     *  the node-side per-write buffer, not the ATT MTU (247 here), is the
     *  binding limit. One lock for frames AND text commands (§0.6) so a
     *  command never interleaves mid-frame. */
    private suspend fun writeRaw(data: ByteArray) {
        val tx = txChar ?: error("AgnosticLoraBleTransport not connected")
        val g = gatt ?: error("AgnosticLoraBleTransport not connected")
        writeLock.withLock {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(data.size, offset + SAFE_WRITE_CHUNK)
                val chunk = data.copyOfRange(offset, end)
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
                    delay(50)
                }
                offset = end
            }
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        /** Max bytes per BLE write (`mobile-app-testing.md` §0.2). */
        private const val SAFE_WRITE_CHUNK = 20

        private const val POLL_TICK_MS = 5_000L
        private const val DIRDUMP_INTERVAL_MS = 90_000L

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

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
