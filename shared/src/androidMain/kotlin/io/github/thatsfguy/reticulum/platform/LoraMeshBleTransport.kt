package io.github.thatsfguy.reticulum.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * the firmware's custom KISS dialect — see [LoraMeshKissParser] /
 * [buildLoraMeshFrame] and the spec at
 * `docs/mobile_ble_integration.md` §3-4.
 *
 * Key differences vs [BleTransport]:
 *   - Custom KISS dialect (no CRC trailer post-2026-05-26 spec
 *     revision; the BLE link layer's CRC-24 covers integrity).
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
    /** When true, request BLE bonding (Passkey-Entry pairing) before
     *  opening the GATT connection. Most in-field firmware is still
     *  in Just-Works mode where this forces an SMP exchange the
     *  firmware doesn't expect — failed bond, wedged BLE state,
     *  ~2 min hung connectGatt until cancellation. Default off; the
     *  user opts in from Settings when their firmware actually
     *  requires the bond. */
    private val requireEncryption: Boolean = false,
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
        // Hex-dump the rejected frame so a stuck/lossy wire stream
        // gives us bytes to compare against the firmware reference.
        // Originally added in v1.2.27 chasing BadCrc; with the CRC
        // gone in the 2026-05-26 spec, only BadLength / BadEscape
        // can fire here, but the hex-dump remains useful for any
        // future framing dispute.
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
                    // Flush Android's per-device GATT cache before
                    // discoverServices. After the failed-bond cascade
                    // earlier in v1.2.28–v1.2.32, Samsung's BLE stack
                    // held stale service/characteristic info from
                    // those broken connections — when the bond finally
                    // succeeded and we re-subscribed, the new CCCD
                    // write went to a stale cache and the firmware's
                    // notifications never reached our
                    // onCharacteristicChanged callback. `refresh()` is
                    // a stable-but-unlisted public method (since
                    // Android 4.x) used by every BLE app that talks
                    // to peripherals whose GATT changes after bond.
                    // Pixel doesn't seem to need it; Samsung A42 does.
                    refreshGattCache(g)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Surface the HCI reason so a firmware-side agent
                    // can diagnose timed-disconnect patterns. Common
                    // codes: 0x08 SUPERVISION_TIMEOUT, 0x13 REMOTE_USER_DISC,
                    // 0x16 LOCAL_HOST_DISC, 0x22 LL_RESPONSE_TIMEOUT,
                    // 0x3B UNACCEPTABLE_CONN_PARAMS, 0x85 (133) GATT_ERROR.
                    // v1.2.36 fielded a 5.5-6s repeating disconnect that
                    // the previous "BLE disconnected, status=N"
                    // log line didn't surface above the engine event
                    // pipeline — now we name the reason in plain text.
                    val reasonName = bleDisconnectReason(status)
                    logger("loramesh: BLE disconnected status=$status ($reasonName)")
                    _state.value = TransportState.Disconnected
                    val err = IllegalStateException(
                        "BLE disconnected, status=$status ($reasonName)",
                    )
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
            // Per docs/mobile_ble_integration.md §2 (2026-05-26
            // revision): firmware default is Just-Works pairing, NOT
            // Passkey-Entry. Operators can opt into Passkey-Entry per
            // board via `CMD_SET_BLE_PASSKEY` (opcode 0x09, six ASCII
            // digits; empty payload reverts to Just-Works).
            //
            // History: v1.2.28-v1.2.32 hard-required `createBond()`
            // on the assumption Passkey-Entry was default. On
            // Just-Works firmware that broke the connection entirely
            // (SMP fails server-side after 5s, firmware wedges, our
            // `connectGatt` hangs ~2 min until cancellation).
            // v1.2.33 made bonding opt-in via this flag.
            //
            // With the revised spec, default-off still matches firmware
            // factory default. Users whose operator set a passkey flip
            // "Require encrypted BLE link" in Settings to ON; the OS
            // pairing prompt then handles the digits.
            if (requireEncryption) {
                runCatching { ensureBonded() }
                    .onFailure {
                        logger(
                            "loramesh: bond attempt failed (${it.message}). " +
                                "If the firmware is in Just-Works mode, turn OFF " +
                                "'Require encrypted BLE link' in Settings.",
                        )
                        // Don't fall through — the failed SMP leaves
                        // the firmware's BLE stack wedged. Bail
                        // cleanly and let the supervisor back off.
                        throw IllegalStateException(
                            "BLE bonding failed against ${device.address}",
                        )
                    }
            }
            connectAndDiscover()
            // Per docs/mobile_ble_integration.md §13 M2 (2026-05-26
            // Samsung-compatibility addendum): Samsung's BLE stack
            // ignores peripheral PPCP and forces sup=500 (5 s),
            // tearing the link down 5.04 s after every LoRa TX-DONE.
            // CONNECTION_PRIORITY_HIGH (11.25-15 ms interval, 0
            // latency, 20 s supervision) is honoured more reliably
            // than PPCP and is the single most important Samsung
            // mitigation. Pixels handle BALANCED fine; HIGH there
            // costs a bit more battery but isn't disruptive. Issue
            // this as early as possible after service discovery —
            // before MTU exchange — so the initial conn-param state
            // is correct while announces are flying. §2.5 also calls
            // for BALANCED on non-Samsung but §13 supersedes for
            // Samsung specifically.
            val priority = if (isSamsungDevice()) {
                android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH
            } else {
                android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            }
            gatt?.requestConnectionPriority(priority)
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

    /** Invoke the hidden `BluetoothGatt.refresh()` to drop the
     *  Android-side service cache for this device. Stable-but-unlisted
     *  public method since API 18; reflected the same way every BLE
     *  app does. Returns true when the call dispatched, false on
     *  reflection failure (we don't propagate — refresh is
     *  best-effort, the connection still works without it). */
    @SuppressLint("DiscouragedPrivateApi")
    private fun refreshGattCache(g: BluetoothGatt): Boolean = try {
        val method = g.javaClass.getMethod("refresh")
        (method.invoke(g) as? Boolean) ?: false
    } catch (_: Throwable) {
        false
    }

    /** Map an HCI disconnect status code to a short readable name.
     *  Covers the codes most likely to show up in BLE GATT callbacks
     *  on Android. Unknown codes fall through to a hex string so we
     *  don't lose the value to a generic "other" label. */
    private fun bleDisconnectReason(status: Int): String = when (status) {
        0 -> "GATT_SUCCESS (clean local close)"
        0x08 -> "SUPERVISION_TIMEOUT"
        0x13 -> "REMOTE_USER_TERMINATED"
        0x16 -> "LOCAL_HOST_TERMINATED"
        0x19 -> "INSUFFICIENT_RESOURCES"
        0x22 -> "LL_RESPONSE_TIMEOUT"
        0x28 -> "INSTANT_PASSED"
        0x29 -> "PAIRING_NOT_SUPPORTED"
        0x3B -> "UNACCEPTABLE_CONN_PARAMS"
        0x3D -> "MIC_FAILURE"
        0x3E -> "CONNECTION_FAILED_TO_ESTABLISH"
        0x85 -> "GATT_ERROR (133) — typically Android-side BLE stack fault"
        0xFF -> "OTHER_END_TERMINATED_UNKNOWN"
        else -> "0x${status.toString(16)}"
    }

    /**
     * Block until [device] is in BOND_BONDED, initiating pairing if
     * necessary. The Android OS surfaces its own passkey dialog (or
     * a system notification on locked phones) when `createBond()`
     * runs against a peripheral that advertises Passkey-Entry — we
     * do NOT render our own UI for that, just wait for the user to
     * complete it.
     *
     * BOND_BONDED already → returns immediately.
     * BOND_BONDING in-flight → joins the existing attempt.
     * BOND_NONE → kicks off createBond() and waits.
     *
     * Times out after 60s; the user has plenty of time to find the
     * notification and type six digits. Failure (BOND_NONE arrives
     * via the broadcast, or `createBond()` returns false, or the
     * timeout fires) throws IllegalStateException with a message
     * the supervisor logs to the engine event channel. The retry
     * loop will then back off and try again.
     */
    private suspend fun ensureBonded() {
        if (device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
            logger("loramesh: device already bonded")
            return
        }

        logger("loramesh: pairing — please enter the passkey (factory default 123456)")

        suspendCancellableCoroutine<Unit> { cont ->
            val filter = IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val target: android.bluetooth.BluetoothDevice? =
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    if (target?.address != device.address) return
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE,
                        android.bluetooth.BluetoothDevice.BOND_NONE,
                    )
                    when (state) {
                        android.bluetooth.BluetoothDevice.BOND_BONDED -> {
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(Unit)
                        }
                        android.bluetooth.BluetoothDevice.BOND_NONE -> {
                            // Only treat as failure when transitioning OUT
                            // of BONDING; an initial BOND_NONE before we
                            // call createBond() is expected.
                            val previous = intent.getIntExtra(
                                android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                                android.bluetooth.BluetoothDevice.BOND_NONE,
                            )
                            if (previous == android.bluetooth.BluetoothDevice.BOND_BONDING) {
                                runCatching { context.unregisterReceiver(this) }
                                if (cont.isActive) {
                                    // Don't claim "wrong passkey" — the OS often
                                    // never even rendered the prompt before SMP
                                    // failed. The actual root cause is one of
                                    // {firmware-doesn't-support-bonding, IO-caps
                                    // mismatch, user-typed-wrong-pin}, and we
                                    // can't tell from this side. v1.2.32 fielded
                                    // a misleading "wrong passkey" message and
                                    // the user spent 90 minutes hunting a
                                    // passkey prompt that never came.
                                    cont.resumeWithException(
                                        IllegalStateException(
                                            "SMP pairing rejected by ${device.address} — firmware may not support bonding (Just-Works mode), or the passkey was wrong",
                                        ),
                                    )
                                }
                            }
                        }
                        android.bluetooth.BluetoothDevice.BOND_BONDING -> {
                            logger("loramesh: bonding in progress…")
                        }
                    }
                }
            }
            context.registerReceiver(receiver, filter)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            // Kick off pairing. createBond() returns false if a bond
            // attempt is already in flight or the device is unreachable.
            // The former is fine (the receiver will catch the eventual
            // state change); the latter, we let the broadcast timeout
            // surface naturally via the supervisor.
            if (device.bondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
                val ok = device.createBond()
                logger("loramesh: createBond() returned $ok")
                if (!ok && device.bondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("createBond() failed — device unreachable or BLE off"),
                        )
                    }
                }
            }
        }
        logger("loramesh: bonded")
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

        /** True on Samsung-manufactured devices. Used to opt into the
         *  Samsung-specific BLE mitigations in
         *  docs/mobile_ble_integration.md §13 — at the moment this
         *  means `CONNECTION_PRIORITY_HIGH` instead of `BALANCED` so
         *  the central-side conn params stick before Samsung's
         *  PPCP-override kicks in, plus a UI banner warning the user
         *  about the post-TX disconnect cycle. */
        fun isSamsungDevice(): Boolean =
            android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        @SuppressLint("MissingPermission")
        fun deviceByAddress(context: Context, address: String): BluetoothDevice {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return mgr.adapter.getRemoteDevice(address)
        }

        /**
         * Drop the OS-side bond for [address] so the next connect re-pairs
         * from scratch. Used when the operator has rotated the firmware's
         * passkey via `CMD_SET_BLE_PASSKEY` (opcode 0x09) — without this
         * the cached bond keeps "succeeding" against a firmware that no
         * longer recognises it, and notifications come back garbled.
         *
         * `BluetoothDevice.removeBond()` is a stable-but-unlisted public
         * method (since API 18); reflect into it the same way every other
         * BLE app does. Returns a [ForgetBondResult] describing what
         * actually happened so the UI can show meaningful Toast feedback
         * — `removeBond()`-returns-true on a device that's already
         * BOND_NONE looks identical to a successful unbond from the
         * caller's perspective, which left the v1.2.30 button feeling
         * dead when v1.2.28's failed pair attempt had already cleared
         * the bond state.
         */
        @SuppressLint("MissingPermission")
        fun forgetBond(context: Context, address: String): ForgetBondResult {
            val device = runCatching { deviceByAddress(context, address) }.getOrNull()
                ?: return ForgetBondResult.DeviceUnreachable
            return when (device.bondState) {
                android.bluetooth.BluetoothDevice.BOND_NONE -> ForgetBondResult.AlreadyUnbonded
                android.bluetooth.BluetoothDevice.BOND_BONDING -> {
                    // Can't cleanly cancel a bond-in-flight from a public
                    // API; the bond will fall back to NONE on its own
                    // when SMP times out. Tell the user to wait.
                    ForgetBondResult.PendingBondInFlight
                }
                else -> {
                    val ok = try {
                        val method = device.javaClass.getMethod("removeBond")
                        method.invoke(device) as? Boolean ?: false
                    } catch (_: Throwable) {
                        false
                    }
                    if (ok) ForgetBondResult.Cleared else ForgetBondResult.Failed
                }
            }
        }
    }

    /** Outcome of [forgetBond] for UI feedback. */
    enum class ForgetBondResult(val userMessage: String) {
        Cleared("Pairing forgotten — next connect will re-pair from scratch"),
        AlreadyUnbonded("No active pairing to forget"),
        PendingBondInFlight("Pairing still in progress — wait a few seconds and retry"),
        DeviceUnreachable("Device not found"),
        Failed("Couldn't forget pairing — clear it from Android Settings → Bluetooth"),
    }
}
