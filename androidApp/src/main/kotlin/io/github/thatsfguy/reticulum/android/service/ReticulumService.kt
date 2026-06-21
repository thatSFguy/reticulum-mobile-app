package io.github.thatsfguy.reticulum.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.thatsfguy.reticulum.android.MainActivity
import io.github.thatsfguy.reticulum.android.R
import io.github.thatsfguy.reticulum.android.platform.BlePermissions
import io.github.thatsfguy.reticulum.android.platform.BtReconnectSignals
import io.github.thatsfguy.reticulum.android.storage.Preferences
import io.github.thatsfguy.reticulum.android.storage.Repositories
import io.github.thatsfguy.reticulum.engine.IdentityCard
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.platform.AgnosticLoraBleTransport
import io.github.thatsfguy.reticulum.platform.AndroidCryptoProvider
import io.github.thatsfguy.reticulum.platform.BleTransport
import io.github.thatsfguy.reticulum.platform.BtClassicTransport
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.transport.ConnectionMemory
import io.github.thatsfguy.reticulum.transport.TcpInterface
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Long-running foreground service that owns the [ReticulumEngine] and the
 * active [Transport]. The Activity binds to this for state observation; the
 * service keeps running across configuration changes and Activity destroy.
 *
 * Started with one of four actions in the Intent:
 *   - [ACTION_CONNECT_BLE] + [EXTRA_BLE_ADDRESS]
 *   - [ACTION_CONNECT_BTCLASSIC] + [EXTRA_BT_CLASSIC_ADDRESS] + [EXTRA_BT_CLASSIC_NAME]
 *   - [ACTION_CONNECT_TCP] + [EXTRA_TCP_HOST] + [EXTRA_TCP_PORT]
 *   - [ACTION_DISCONNECT]
 *
 * Sends two kinds of notifications:
 *   - "reticulum_service" channel (low priority): persistent foreground note
 *   - "reticulum_messages" channel (high priority): incoming message alerts
 */
class ReticulumService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: ReticulumEngine
    private lateinit var repositories: Repositories
    private lateinit var preferences: Preferences

    /** Active transports, keyed by kind. Each entry is owned by a
     *  matching supervisor coroutine in [connectJobs] that runs the
     *  exponential-backoff reconnect loop. Modifying these is only safe
     *  from the service's main scope. */
    private val currentTransports: MutableMap<ReticulumEngine.TransportKind, Transport> = mutableMapOf()
    private val connectJobs: MutableMap<ReticulumEngine.TransportKind, Job> = mutableMapOf()
    private var eventCollectorJob: Job? = null
    private var notificationUpdateJob: Job? = null

    /** Kinds that have a live supervisor coroutine but haven't yet
     *  successfully attached a transport to [engine]. While the
     *  supervisor is in its first connect attempt or in the post-failure
     *  backoff, [engine.connections] does not contain this kind — but
     *  the user still needs a way to abort. UI watches this alongside
     *  [engine.connections] so a "Cancel" button is reachable for a
     *  Connecting-but-unreachable RNode. */
    private val _pendingKindsState = MutableStateFlow<Set<ReticulumEngine.TransportKind>>(emptySet())
    val pendingKinds: StateFlow<Set<ReticulumEngine.TransportKind>> = _pendingKindsState.asStateFlow()

    /** The agnostic-LoRa node the app is currently attached to (32-hex),
     *  learned from the register-ack/heartbeat after connect; null when not
     *  attached. Display-only (Settings) — routing is via the directory. */
    private val _agnosticLoraNodeId = MutableStateFlow<String?>(null)
    val agnosticLoraNodeId: StateFlow<String?> = _agnosticLoraNodeId.asStateFlow()

    val connection: StateFlow<ReticulumEngine.ConnectionState> get() = engine.connection
    val connections: StateFlow<List<ReticulumEngine.ConnectionState>> get() = engine.connections
    val events: Flow<ReticulumEngine.EngineEvent> get() = engine.events
    val repos: Repositories get() = repositories
    val prefs: Preferences get() = preferences

    /** Off-row attachment store (docs/ATTACHMENT-STORE.md). Owned by
     *  the service, shared with [engine]; the UI reads it to load an
     *  image / file payload from its on-row token. */
    private lateinit var attachmentStoreField: io.github.thatsfguy.reticulum.store.AttachmentStore
    val attachmentStore: io.github.thatsfguy.reticulum.store.AttachmentStore get() = attachmentStoreField

    /** Outstanding message-notification IDs keyed by contact hash, so
     *  opening a conversation can dismiss everything piled up for that
     *  contact in one go. Android's auto-grouping is OEM-dependent: on
     *  some devices a single swipe clears every notification from this
     *  app; on others the user has to swipe each one individually. We
     *  bypass that by tracking IDs and cancelling them ourselves when
     *  the conversation is opened or deleted. Access only under the
     *  object's own monitor — read from the event-collector coroutine,
     *  written from binder calls on the main thread. */
    private val messageNotificationIds: MutableMap<String, MutableSet<Int>> = mutableMapOf()

    inner class LocalBinder : Binder() { val service: ReticulumService = this@ReticulumService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        repositories = Repositories.create(applicationContext)
        preferences = Preferences(applicationContext)
        attachmentStoreField = io.github.thatsfguy.reticulum.store.AttachmentStore(
            java.io.File(applicationContext.filesDir, "attachments").absolutePath,
        )
        engine = ReticulumEngine(
            crypto = AndroidCryptoProvider(),
            identityRepo = repositories.identity,
            destinationRepo = repositories.destinations,
            messageRepo = repositories.messages,
            scope = scope,
            nowMs = { System.currentTimeMillis() },
            displayNameProvider = { preferences.getDisplayName() },
            dropUnverifiedProvider = { preferences.dropUnverified.value },
            nomadPageCache = repositories.nomadPageCache,
            // RRC storage is always wired; the experimental Rooms UI
            // and openRrcSession stay unreachable behind the
            // experimentalRrc preference until the feature ships.
            rrcRepo = repositories.rrc,
            attachmentStore = attachmentStoreField,
        )

        // Eagerly trim a bloated destinations table before the UI's
        // Flow observer subscribes. Pre-1.1.26 installs accumulated
        // rows without bound; combined with the BLOB columns
        // (publicKey, nextHop, appDataHex) a >1000-row table overflows
        // the 2 MB Android CursorWindow default and the
        // observeDestinations Flow crashes with IllegalStateException
        // at the first read. Eviction is idempotent (no-op when under
        // the cap), so the cost on a small table is negligible. Audit
        // reference: 2026-05-13 MED-2 follow-up.
        scope.launch { engine.evictDestinationsOnStartup() }

        // Orphan-GC the attachment store: delete any image / file
        // payload no message row still references — backstops a crash
        // between a conversation-delete and its file cleanup.
        // docs/ATTACHMENT-STORE.md §3.7.
        scope.launch { engine.sweepAttachmentsOnStartup() }

        // Surface incoming message events as notifications AND mirror every
        // engine event to Android logcat so live debugging via
        // `adb logcat -s ReticulumEngine` shows what the in-app
        // diagnostics view shows. Without this, the only way to read
        // the engine's diagnostic stream is to be in the app and look
        // at the Diagnostics tab — useless for adb-driven debug loops.
        eventCollectorJob = scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is ReticulumEngine.EngineEvent.Log ->
                        Log.i(LOGCAT_TAG, event.line)
                    is ReticulumEngine.EngineEvent.MessageReceived -> {
                        Log.i(LOGCAT_TAG, "msg from ${event.contactHash} verified=${event.verified}")
                        showIncomingMessageNotification(event)
                    }
                    is ReticulumEngine.EngineEvent.MessagableSeen ->
                        Log.v(LOGCAT_TAG, "lxmf ${event.hash} [${event.appName ?: "?"}] ${event.displayName}")
                    is ReticulumEngine.EngineEvent.NodeSeen ->
                        Log.v(LOGCAT_TAG, "node ${event.hash} [${event.appName ?: "?"}] ${event.displayName}")
                    is ReticulumEngine.EngineEvent.RrcActivity ->
                        Log.v(LOGCAT_TAG, "rrc ${event.hubDestHash} ${event.event::class.simpleName}")
                    is ReticulumEngine.EngineEvent.ResourceProgress -> Unit
                }
            }
        }

        // Mirror engine.connections into the foreground notification.
        // Fires when any kind transitions Connected/Disconnected so the
        // user sees "BLE + TCP" appear/disappear without us having to
        // call updateServiceNotification at every supervisor branch.
        // The supervisors still call refreshNotification(prefix=...) for
        // transient "reconnecting" text; this collector keeps the steady
        // state honest.
        notificationUpdateJob = scope.launch {
            engine.connections.collect { refreshNotification() }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification("Reticulum — listening for messages"))
        when (intent?.action) {
            ACTION_CONNECT_BLE -> {
                val address = intent.getStringExtra(EXTRA_BLE_ADDRESS)
                val name    = intent.getStringExtra(EXTRA_BLE_NAME)
                if (address.isNullOrEmpty()) stopSelf() else startBle(address, name)
            }
            ACTION_CONNECT_BTCLASSIC -> {
                val address = intent.getStringExtra(EXTRA_BT_CLASSIC_ADDRESS)
                val name    = intent.getStringExtra(EXTRA_BT_CLASSIC_NAME)
                if (address.isNullOrEmpty()) stopSelf() else startBtClassic(address, name)
            }
            ACTION_CONNECT_TCP -> {
                val host = intent.getStringExtra(EXTRA_TCP_HOST)
                val port = intent.getIntExtra(EXTRA_TCP_PORT, 0)
                if (host.isNullOrEmpty() || port <= 0) stopSelf() else startTcp(host, port)
            }
            ACTION_CONNECT_AGNOSTIC_LORA -> {
                val address = intent.getStringExtra(EXTRA_AGNOSTIC_LORA_ADDRESS)
                val name    = intent.getStringExtra(EXTRA_AGNOSTIC_LORA_NAME)
                // Optional fallback pin — identity addressing via the mesh
                // directory is the normal mode, so a missing uplink is fine.
                val uplink  = intent.getStringExtra(EXTRA_AGNOSTIC_LORA_UPLINK)
                if (address.isNullOrEmpty()) stopSelf()
                else startAgnosticLora(address, name, uplink)
            }
            ACTION_DISCONNECT -> {
                disconnectAll()
                stopSelf()
            }
            ACTION_DISCONNECT_KIND -> {
                val kindName = intent.getStringExtra(EXTRA_DISCONNECT_KIND)
                val kind = kindName?.let {
                    runCatching { ReticulumEngine.TransportKind.valueOf(it) }.getOrNull()
                }
                if (kind != null) {
                    disconnectKind(kind)
                    // Don't stopSelf — other transports may still be live.
                    if (currentTransports.isEmpty()) stopSelf()
                }
            }
            ACTION_RESTORE -> restoreLastConnection()
        }
        return START_STICKY
    }

    private fun startBle(address: String, name: String? = null) {
        if (!BlePermissions.allGranted(this)) {
            updateServiceNotification("Reticulum — BLE permissions missing")
            return
        }
        val kind = ReticulumEngine.TransportKind.Ble
        cancelConnect(kind)
        markPending(kind)
        // Persist the MAC eagerly so it survives a restart even if the
        // first connect attempt fails — same rationale as TCP / BT
        // Classic below. The name (when the picker supplies it) is a
        // display hint for the "Last:" row; the reconnect keys on the MAC.
        preferences.setLastBle(address, name)
        connectJobs[kind] = scope.launch {
            // Simple exponential backoff supervisor: keeps re-connecting on failure.
            var delayMs = 1_000L
            while (true) {
                // Declared outside the try so the catch block can close
                // the underlying transport even when cancellation arrives
                // between connect() and the currentTransports assignment
                // — without this the OS socket / GATT leaks until GC
                // finalizes the wrapper.
                var transport: BleTransport? = null
                try {
                    engine.logExternal("BLE: connecting to $address")
                    val device = BleTransport.deviceByAddress(this@ReticulumService, address)
                    transport = BleTransport(this@ReticulumService, device, scope)
                    transport.connect()
                    engine.logExternal("BLE: connected, GATT ready")

                    // Push the saved radio config (frequency, bandwidth, SF,
                    // CR, TX power) and turn the radio on. Without this the
                    // RNode sits idle and announces won't even hit the air.
                    val cfg = preferences.radioConfig.value
                    runCatching { transport.applyRadioConfig(cfg) }
                        .onSuccess {
                            engine.logExternal("RNode: radio on at ${cfg.frequencyHz / 1_000_000.0} MHz, BW ${cfg.bandwidthHz / 1000.0} kHz, SF ${cfg.spreadingFactor}, CR ${cfg.codingRate}, ${cfg.txPowerDbm} dBm")
                        }
                        .onFailure {
                            engine.logExternal("RNode: radio config failed: ${it.message}")
                        }

                    currentTransports[kind] = transport
                    engine.attach(transport, kind)
                    engine.ensureIdentity()
                    // Reached Connected — remember BLE as the
                    // auto-reconnect target for the next cold start.
                    preferences.addLastTransportKind(ConnectionMemory.KIND_BLE)
                    refreshNotification()
                    delayMs = 1_000L
                    // Wait for transport to disconnect, then loop.
                    transport.state.collect { st ->
                        if (st == io.github.thatsfguy.reticulum.transport.TransportState.Disconnected ||
                            st == io.github.thatsfguy.reticulum.transport.TransportState.Error) {
                            throw IllegalStateException("BLE transport ended: $st")
                        }
                    }
                } catch (t: Throwable) {
                    engine.logExternal("transport error (BLE): ${t::class.simpleName}: ${t.message}")
                    // Identity guard: only detach/remove if the
                    // registered transport is still THIS supervisor's
                    // — without this, a cancelled supervisor whose
                    // cleanup runs after a fresh supervisor has
                    // already taken over would yank the new transport
                    // out of the engine.
                    if (currentTransports[kind] === transport) {
                        engine.detach(kind)
                        currentTransports.remove(kind)
                    }
                    runCatching { transport?.disconnect() }
                    // Event-driven reconnect: park until the RNode is seen
                    // advertising again (fast path) or the widened fallback
                    // timer elapses — whichever first. The OS signal lets us
                    // cap the timer high without polling the radio.
                    refreshNotification(prefix = "Reticulum — BLE reconnecting (waiting for RNode)")
                    val bleWoke = BtReconnectSignals.awaitBleAdvertisement(
                        this@ReticulumService, address, timeoutMs = delayMs,
                    )
                    engine.logExternal(
                        if (bleWoke) "BLE: saw advertisement, reconnecting"
                        else "BLE: backoff elapsed, retrying",
                    )
                    delayMs = (delayMs * 2).coerceAtMost(RECONNECT_WIDE_CAP_MS)
                }
            }
        }
    }

    private fun startAgnosticLora(address: String, name: String?, uplink: String?) {
        if (!BlePermissions.allGranted(this)) {
            updateServiceNotification("Reticulum — BLE permissions missing")
            return
        }
        val kind = ReticulumEngine.TransportKind.AgnosticLora
        cancelConnect(kind)
        markPending(kind)
        _agnosticLoraNodeId.value = null // cleared until the new session learns it
        // Persist eagerly so the node survives a restart even if the first
        // connect fails — same as the other supervisors. Reconnect keys on
        // the MAC; the name is a "Last:" display hint, the uplink an
        // optional fallback pin.
        preferences.setLastAgnosticLora(address, name, uplink)
        connectJobs[kind] = scope.launch {
            // Same exponential-backoff + event-driven reconnect as the RNode
            // BLE supervisor. No radio-config push — the agnostic-LoRa node
            // drives its own SX1262; we just attach to its tunnel.
            var delayMs = 1_000L
            // Our directory id: the 16-byte lxmf.delivery destination hash.
            // Identity addressing (`register`/`resolve`/`dirdump`) replaces
            // the static uplink pin — see AgnosticLoraRouter.
            val selfDestHex = engine.ourDestHash().toHex()
            while (true) {
                // Declared outside the try so cleanup hits the local
                // reference even if cancellation lands before the
                // currentTransports assignment (see the BLE supervisor note).
                var transport: AgnosticLoraBleTransport? = null
                try {
                    engine.logExternal(
                        "AgnLoRa: connecting to $address " +
                            (if (uplink.isNullOrBlank()) "(directory addressing)" else "(fallback uplink $uplink)"),
                    )
                    val device = AgnosticLoraBleTransport.deviceByAddress(this@ReticulumService, address)
                    transport = AgnosticLoraBleTransport(
                        context = this@ReticulumService,
                        device = device,
                        scope = scope,
                        selfDestHashHex = selfDestHex,
                        uplinkNodeId = uplink,
                        crypto = AndroidCryptoProvider(),
                        logger = { line -> engine.logExternal(line) },
                        onAttachedNode = { node -> _agnosticLoraNodeId.value = node },
                    )
                    transport.connect()

                    currentTransports[kind] = transport
                    engine.attach(transport, kind)
                    engine.ensureIdentity()
                    preferences.addLastTransportKind(ConnectionMemory.KIND_AGNOSTIC_LORA)
                    refreshNotification()
                    delayMs = 1_000L
                    transport.state.collect { st ->
                        if (st == io.github.thatsfguy.reticulum.transport.TransportState.Disconnected ||
                            st == io.github.thatsfguy.reticulum.transport.TransportState.Error) {
                            throw IllegalStateException("AgnLoRa transport ended: $st")
                        }
                    }
                } catch (t: Throwable) {
                    engine.logExternal("transport error (AgnLoRa): ${t::class.simpleName}: ${t.message}")
                    if (currentTransports[kind] === transport) {
                        engine.detach(kind)
                        currentTransports.remove(kind)
                    }
                    runCatching { transport?.disconnect() }
                    // A bad node id / dest hash is a config error, not a
                    // transient — don't spin reconnecting against it.
                    if (t is IllegalArgumentException) {
                        engine.logExternal("AgnLoRa: ${t.message} — stopping (check the fallback node id in Settings)")
                        if (currentTransports[kind] === transport) currentTransports.remove(kind)
                        unmarkPending(kind)
                        refreshNotification()
                        return@launch
                    }
                    refreshNotification(prefix = "Reticulum — AgnLoRa reconnecting (waiting for node)")
                    val bleWoke = BtReconnectSignals.awaitBleAdvertisement(
                        this@ReticulumService, address, timeoutMs = delayMs,
                    )
                    engine.logExternal(
                        if (bleWoke) "AgnLoRa: saw advertisement, reconnecting"
                        else "AgnLoRa: backoff elapsed, retrying",
                    )
                    delayMs = (delayMs * 2).coerceAtMost(RECONNECT_WIDE_CAP_MS)
                }
            }
        }
    }

    private fun startBtClassic(address: String, name: String?) {
        // BLUETOOTH_CONNECT is the same runtime gate BLE uses; reuse the
        // same permissions helper rather than splitting concerns. The
        // legacy BLUETOOTH permission for pre-Android-12 is normal-protection,
        // granted at install, so it doesn't appear in the runtime list.
        if (!BlePermissions.allGranted(this)) {
            updateServiceNotification("Reticulum — Bluetooth permissions missing")
            return
        }
        val kind = ReticulumEngine.TransportKind.BtClassic
        cancelConnect(kind)
        markPending(kind)
        preferences.setLastBtClassic(address, name)
        connectJobs[kind] = scope.launch {
            // Same exponential-backoff supervisor as BLE: keeps reconnecting
            // on failure. Pairing must already be done in system Settings —
            // we don't trigger it from here.
            var delayMs = 1_000L
            while (true) {
                // See the matching BLE comment — transport is declared
                // outside the try so cleanup hits the local reference
                // even before currentTransports gets assigned.
                var transport: BtClassicTransport? = null
                try {
                    engine.logExternal("BT Classic: connecting to $address")
                    val device = BtClassicTransport.deviceByAddress(this@ReticulumService, address)
                    transport = BtClassicTransport(this@ReticulumService, device, scope)
                    transport.connect()
                    engine.logExternal("BT Classic: RFCOMM ready")

                    // Push saved radio config and turn the radio on, just like BLE.
                    val cfg = preferences.radioConfig.value
                    runCatching { transport.applyRadioConfig(cfg) }
                        .onSuccess {
                            engine.logExternal("RNode: radio on at ${cfg.frequencyHz / 1_000_000.0} MHz, BW ${cfg.bandwidthHz / 1000.0} kHz, SF ${cfg.spreadingFactor}, CR ${cfg.codingRate}, ${cfg.txPowerDbm} dBm")
                        }
                        .onFailure {
                            engine.logExternal("RNode: radio config failed: ${it.message}")
                        }

                    currentTransports[kind] = transport
                    engine.attach(transport, kind)
                    engine.ensureIdentity()
                    // Reached Connected — remember BT Classic as the
                    // auto-reconnect target for the next cold start.
                    preferences.addLastTransportKind(ConnectionMemory.KIND_BT_CLASSIC)
                    refreshNotification()
                    delayMs = 1_000L
                    transport.state.collect { st ->
                        if (st == io.github.thatsfguy.reticulum.transport.TransportState.Disconnected ||
                            st == io.github.thatsfguy.reticulum.transport.TransportState.Error) {
                            throw IllegalStateException("BT Classic transport ended: $st")
                        }
                    }
                } catch (t: Throwable) {
                    engine.logExternal("transport error (BTClassic): ${t::class.simpleName}: ${t.message}")
                    if (currentTransports[kind] === transport) {
                        engine.detach(kind)
                        currentTransports.remove(kind)
                    }
                    runCatching { transport?.disconnect() }
                    // Event-driven reconnect: park until an ACL/bond
                    // broadcast says the device is back, or the widened
                    // fallback timer elapses.
                    refreshNotification(prefix = "Reticulum — BT Classic reconnecting (waiting for RNode)")
                    val btWoke = BtReconnectSignals.awaitBtClassicAvailable(
                        this@ReticulumService, address, timeoutMs = delayMs,
                    )
                    engine.logExternal(
                        if (btWoke) "BT Classic: device available, reconnecting"
                        else "BT Classic: backoff elapsed, retrying",
                    )
                    delayMs = (delayMs * 2).coerceAtMost(RECONNECT_WIDE_CAP_MS)
                }
            }
        }
    }


    private fun startTcp(host: String, port: Int) {
        val kind = ReticulumEngine.TransportKind.Tcp
        cancelConnect(kind)
        markPending(kind)
        // Persist immediately so the host survives restart even if the
        // first connect attempt fails — otherwise the user has to retype
        // it every time they bounce the app.
        preferences.setLastTcp(host, port)
        connectJobs[kind] = scope.launch {
            // Two distinct backoffs per upstream RNS guidance:
            //   readFailBackoff — stable connect, then read loop died
            //     (NAT idle, server close, parser issue). Floor 5s,
            //     shorter ceiling, behaves like upstream's 5s linear.
            //   connectFailBackoff — couldn't even establish the socket
            //     (DNS, refused, ECONNABORTED). Slower ramp, longer
            //     ceiling. Aggressive ramps look like SYN-flood probing
            //     to MichMesh's network and trigger the abort storm.
            var readFailBackoffMs = 5_000L
            var connectFailBackoffMs = 15_000L
            var connectedAtMs = 0L

            while (true) {
                // Declared outside the try so cleanup hits the local
                // reference. Without this a cancellation that lands
                // between transport.connect() and the
                // currentTransports[kind] assignment leaks an
                // ESTABLISHED TCP socket — the supervisor relaunched a
                // new attempt, currentTransports[kind]?.disconnect()
                // saw null, and the OS kept the orphan socket open
                // until GC finalized the wrapper. todo.md investigation
                // section flagged 2 simultaneous ESTABLISHED sockets
                // to MichMesh while Settings claimed "Disconnected" —
                // this is the cleanup path that prevents the leak.
                var transport: TcpInterface? = null
                try {
                    engine.logExternal("TCP: connecting to $host:$port (TCP handshake — DNS + 3-way ACK can take 30s+ on a slow path)")
                    transport = TcpInterface(
                        host = host,
                        port = port,
                        scope = scope,
                        txLogger = { line -> engine.logExternal(line) },
                    )
                    transport.connect()
                    engine.logExternal("TCP: socket ready (keepalive on, NoDelay on)")
                    currentTransports[kind] = transport
                    engine.attach(transport, kind)
                    engine.ensureIdentity()
                    // Reached Connected — remember TCP as the
                    // auto-reconnect target for the next cold start.
                    preferences.addLastTransportKind(ConnectionMemory.KIND_TCP)
                    refreshNotification()
                    connectedAtMs = System.currentTimeMillis()
                    connectFailBackoffMs = 15_000L  // reset connect backoff after successful socket

                    transport.state.collect { st ->
                        if (st == io.github.thatsfguy.reticulum.transport.TransportState.Disconnected ||
                            st == io.github.thatsfguy.reticulum.transport.TransportState.Error) {
                            throw IllegalStateException("TCP transport ended: $st")
                        }
                    }
                } catch (t: Throwable) {
                    engine.logExternal("transport error (TCP): ${t::class.simpleName}: ${t.message}")
                    if (currentTransports[kind] === transport) {
                        engine.detach(kind)
                        currentTransports.remove(kind)
                    }
                    runCatching { transport?.disconnect() }

                    val wasReadFailure = connectedAtMs != 0L
                    val survivedSec = if (wasReadFailure) (System.currentTimeMillis() - connectedAtMs) / 1000 else 0L
                    connectedAtMs = 0L

                    // Long-lived connections that died are likely NAT/middlebox
                    // timeouts; reset the read backoff so we reconnect quickly.
                    if (wasReadFailure && survivedSec >= 60) {
                        readFailBackoffMs = 5_000L
                    }

                    val baseMs = if (wasReadFailure) readFailBackoffMs else connectFailBackoffMs
                    // ±25% jitter so multiple clients don't synchronize after
                    // a network blip and DDoS the rnsd on recovery.
                    val jitterMs = (baseMs * (0.75 + Math.random() * 0.5)).toLong()
                    refreshNotification(prefix = "Reticulum — TCP reconnecting in ${jitterMs / 1000}s")
                    delay(jitterMs)

                    if (wasReadFailure) {
                        readFailBackoffMs = (readFailBackoffMs * 2).coerceAtMost(60_000L)
                    } else {
                        connectFailBackoffMs = (connectFailBackoffMs * 2).coerceAtMost(300_000L)  // 5min cap
                    }
                }
            }
        }
    }

    /** Tear down every attached transport. Maps to the global Disconnect
     *  action — used when the user wants the service entirely down. */
    private fun disconnectAll() {
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        _pendingKindsState.value = emptySet()
        _agnosticLoraNodeId.value = null
        engine.detach(null)
        // Explicit global Disconnect — forget the auto-reconnect target
        // so a relaunch honours the user deliberately going offline.
        preferences.clearLastTransportKind()
        val toClose = currentTransports.values.toList()
        currentTransports.clear()
        scope.launch { toClose.forEach { runCatching { it.disconnect() } } }
        refreshNotification()
    }

    /** Tear down only [kind]; leave other transports alone. */
    private fun disconnectKind(kind: ReticulumEngine.TransportKind) {
        cancelConnect(kind)
        unmarkPending(kind)
        if (kind == ReticulumEngine.TransportKind.AgnosticLora) _agnosticLoraNodeId.value = null
        engine.detach(kind)
        val transport = currentTransports.remove(kind)
        if (transport != null) {
            scope.launch { runCatching { transport.disconnect() } }
        }
        // The user explicitly disconnected this transport — forget just
        // it so a relaunch doesn't bring it back, while leaving any other
        // still-connected transports to auto-reconnect as before.
        kind.memoryKind()?.let { preferences.removeLastTransportKind(it) }
        refreshNotification()
    }

    /**
     * Cold-start auto-reconnect — re-establish *every* transport the app
     * was connected to when last shut down. Triggered by the
     * [ACTION_RESTORE] intent from [restoreLastConnection] (MainActivity,
     * on launch).
     *
     * Restores all remembered kinds (e.g. TCP + agnostic-LoRa-Net at
     * once), not just the last one connected — each `start*` spins up its
     * own independent reconnect supervisor. A no-op on a warm start (a
     * transport is already live or connecting). When auto-reconnect is
     * off or nothing was saved, the just-started foreground service stops
     * itself rather than lingering for no reason.
     */
    private fun restoreLastConnection() {
        if (currentTransports.isNotEmpty() || connectJobs.isNotEmpty()) return
        val memories = preferences.resolveConnectionMemories()
        if (memories.isEmpty()) {
            engine.logExternal("restore: nothing to reconnect (auto-reconnect off or no saved transport)")
            stopSelf()
            return
        }
        for (mem in memories) {
            when (mem) {
                is ConnectionMemory.Ble -> {
                    engine.logExternal("restore: reconnecting last BLE RNode ${mem.address}")
                    startBle(mem.address, mem.name)
                }
                is ConnectionMemory.BtClassic -> {
                    engine.logExternal("restore: reconnecting last BT Classic RNode ${mem.address}")
                    startBtClassic(mem.address, mem.name)
                }
                is ConnectionMemory.Tcp -> {
                    engine.logExternal("restore: reconnecting last TCP node ${mem.host}:${mem.port}")
                    startTcp(mem.host, mem.port)
                }
                is ConnectionMemory.AgnosticLora -> {
                    engine.logExternal(
                        "restore: reconnecting last AgnLoRa node ${mem.address}" +
                            (mem.uplinkNodeId?.let { " (fallback uplink $it)" } ?: " (directory addressing)"),
                    )
                    startAgnosticLora(mem.address, mem.name, mem.uplinkNodeId)
                }
            }
        }
    }

    /** The [ConnectionMemory] kind tag for a transport kind, or null
     *  for kinds not eligible for silent auto-reconnect (USB needs a
     *  freshly-granted device permission). */
    private fun ReticulumEngine.TransportKind.memoryKind(): String? = when (this) {
        ReticulumEngine.TransportKind.Ble -> ConnectionMemory.KIND_BLE
        ReticulumEngine.TransportKind.BtClassic -> ConnectionMemory.KIND_BT_CLASSIC
        ReticulumEngine.TransportKind.Tcp -> ConnectionMemory.KIND_TCP
        ReticulumEngine.TransportKind.AgnosticLora -> ConnectionMemory.KIND_AGNOSTIC_LORA
        else -> null
    }

    private fun cancelConnect(kind: ReticulumEngine.TransportKind) {
        connectJobs.remove(kind)?.cancel()
    }

    private fun markPending(kind: ReticulumEngine.TransportKind) {
        _pendingKindsState.value = _pendingKindsState.value + kind
    }

    private fun unmarkPending(kind: ReticulumEngine.TransportKind) {
        _pendingKindsState.value = _pendingKindsState.value - kind
    }

    suspend fun sendMessage(
        destinationHash: String,
        content: String,
        imageBytes: ByteArray? = null,
        fileBytes: ByteArray? = null,
        fileName: String? = null,
        replyToMessageId: String? = null,
    ) = engine.sendMessage(
        destinationHash = destinationHash,
        content = content,
        imageBytes = imageBytes,
        fileBytes = fileBytes,
        fileName = fileName,
        replyToMessageId = replyToMessageId,
    )

    suspend fun sendReaction(
        destinationHash: String,
        targetMessageId: String,
        emoji: String,
    ) = engine.sendReaction(destinationHash, targetMessageId, emoji)

    suspend fun sendAnnounce() = engine.sendAnnounce()

    suspend fun ourDestHash(): ByteArray = engine.ourDestHash()

    suspend fun myIdentityCard(): IdentityCard.Payload = engine.myIdentityCard()

    suspend fun applyIdentityCardJson(json: String): StoredDestination =
        engine.applyIdentityCard(IdentityCard.decode(json))

    suspend fun fetchNomadPage(
        destinationHash: String,
        path: String = "/page/index.mu",
        data: Any? = null,
        identify: Boolean = false,
    ): Result<String> = engine.fetchNomadPage(destinationHash, path, data = data, identify = identify)

    suspend fun fetchNomadFile(
        destinationHash: String,
        path: String,
        identify: Boolean = false,
    ): Result<ReticulumEngine.DownloadedFile> =
        engine.fetchNomadFile(destinationHash, path, identify = identify)

    suspend fun addManualDestination(hashHex: String, label: String): StoredDestination =
        engine.addManualDestination(hashHex, label)

    /** Send a path request for [hashHex] so transit nodes refresh their
     *  forward path. Used by the Nomad browser when the user follows a
     *  cross-node link to a destination we haven't seen an announce for —
     *  no-op on success/failure (path arrival is observed via repo flow). */
    suspend fun requestPath(hashHex: String) {
        engine.requestPath(hashHex.hexToBytes())
    }

    suspend fun setFavorite(hashHex: String, favorite: Boolean) =
        engine.setFavorite(hashHex, favorite)

    suspend fun setUserLabel(hashHex: String, label: String?) =
        engine.setUserLabel(hashHex, label)

    suspend fun deleteDestinationAndMessages(hashHex: String) =
        engine.deleteDestinationAndMessages(hashHex)

    suspend fun deleteMessagesForDestination(hashHex: String) =
        engine.deleteMessagesForDestination(hashHex)

    suspend fun syncPropagation(hashHex: String): ReticulumEngine.PropagationSyncResult =
        engine.syncPropagation(hashHex)

    suspend fun syncPropagationAuto(): ReticulumEngine.PropagationSyncResult =
        engine.syncPropagationAuto()

    // ---- RRC (experimental) ---------------------------------------------
    // Thin pass-throughs to the engine's RRC session API. The repo
    // (`repos.rrc`) is reached directly by the ViewModel for hub CRUD;
    // these methods cover the parts that need a live link.

    suspend fun openRrcSession(hubDestHash: String, nick: String?): Result<Unit> =
        engine.openRrcSession(hubDestHash, nick)

    suspend fun closeRrcSession(hubDestHash: String) =
        engine.closeRrcSession(hubDestHash)

    suspend fun joinRrcRoom(hubDestHash: String, room: String, key: String?) =
        engine.joinRrcRoom(hubDestHash, room, key)

    suspend fun partRrcRoom(hubDestHash: String, room: String) =
        engine.partRrcRoom(hubDestHash, room)

    suspend fun deleteRrcRoom(hubDestHash: String, room: String) =
        engine.deleteRrcRoom(hubDestHash, room)

    suspend fun browseRrcRooms(hubDestHash: String) =
        engine.browseRrcRooms(hubDestHash)

    suspend fun setRrcHubNick(hubDestHash: String, nick: String?) =
        engine.setRrcHubNick(hubDestHash, nick)

    suspend fun sendRrcMessage(hubDestHash: String, room: String, text: String) =
        engine.sendRrcMessage(hubDestHash, room, text)

    suspend fun resetIdentity() { engine.resetIdentity() }

    suspend fun exportIdentity(passphrase: String): ByteArray =
        engine.exportIdentity(passphrase)

    suspend fun importIdentity(archive: ByteArray, passphrase: String) {
        val payload = engine.importIdentity(archive, passphrase)
        // v0x02 archives carry the user's display name; restore it to
        // local Preferences so the next announce uses the imported
        // label instead of forcing the user to retype on the new
        // device. v0x01 (legacy) archives have payload.displayName == null
        // — leave the existing local name in place. Empty string is a
        // valid "user never set a name" value and we accept it as-is.
        payload.displayName?.let { name ->
            if (name != preferences.getDisplayName()) {
                preferences.setDisplayName(name)
            }
        }
    }

    fun setDisplayName(name: String) {
        preferences.setDisplayName(name)
        scope.launch { runCatching { engine.sendAnnounce() } }
    }

    /** Push the saved RadioConfig to every attached RNode. With multi-
     *  transport, the user could have BLE+BtClassic both up — applying
     *  to all keeps them in sync. TCP transports have no radio knob and
     *  are skipped. */
    suspend fun reapplyRadioConfig() {
        val cfg = preferences.radioConfig.value
        for (t in currentTransports.values) {
            runCatching {
                when (t) {
                    is BleTransport       -> t.applyRadioConfig(cfg)
                    is BtClassicTransport -> t.applyRadioConfig(cfg)
                    else                  -> Unit
                }
            }
        }
    }

    override fun onDestroy() {
        eventCollectorJob?.cancel()
        notificationUpdateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Notifications --------------------------------------------------

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SERVICE, "Reticulum service", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Persistent indicator while the BLE/TCP connection is live." })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_MESSAGES, "Incoming messages", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "An LXMF message addressed to you was received."
            // Default lockscreen behavior for new installs: hide the
            // decrypted preview; show only the public-version "new
            // message" placeholder. Users can override per-channel in
            // System Settings → Notifications. Per-notification
            // visibility is also set in showIncomingMessageNotification
            // so existing installs get the same protection even though
            // the channel's lockscreen-visibility can't be modified
            // post-creation. Audit reference: 2026-05-13 HIGH-2.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    private fun buildServiceNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Reticulum")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_SERVICE, buildServiceNotification(text))
    }

    /** Compose the foreground-service notification text from the current
     *  set of attached transports. Multi-transport renders as e.g.
     *  "Reticulum — connected (BLE + TCP)". [prefix] overrides the
     *  composed text — used for transient "BLE reconnecting in 4s"
     *  messages from the per-kind supervisor catch blocks. */
    private fun refreshNotification(prefix: String? = null) {
        if (prefix != null) {
            updateServiceNotification(prefix)
            return
        }
        val active = engine.connections.value
            .filter { it.transport == io.github.thatsfguy.reticulum.transport.TransportState.Connected }
        val text = if (active.isEmpty()) {
            "Reticulum — listening for messages"
        } else {
            val labels = active.mapNotNull { it.kind?.let(::transportKindLabel) }
            "Reticulum — connected (${labels.joinToString(" + ")})"
        }
        updateServiceNotification(text)
    }

    private fun transportKindLabel(kind: ReticulumEngine.TransportKind): String = when (kind) {
        ReticulumEngine.TransportKind.Ble       -> "BLE"
        ReticulumEngine.TransportKind.BtClassic -> "BT Classic"
        ReticulumEngine.TransportKind.Tcp       -> "TCP"
        ReticulumEngine.TransportKind.Usb       -> "USB"
        ReticulumEngine.TransportKind.AgnosticLora -> "AgnLoRa"
    }

    private fun showIncomingMessageNotification(event: ReticulumEngine.EngineEvent.MessageReceived) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CONTACT, event.contactHash)
        }
        val pi = PendingIntent.getActivity(
            this, event.messageId.toInt(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (event.verified) "New message" else "Unverified message"
        // VISIBILITY_PRIVATE keeps the full message content off the
        // lockscreen by default — the system substitutes the
        // setPublicVersion notification when the device is locked. The
        // public version reveals only that an LXMF message arrived; the
        // sender display name and content stay behind authentication.
        // Audit reference: 2026-05-13 HIGH-2.
        val publicVersion = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Reticulum")
            .setContentText(title)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(event.content.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.content))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        val notificationId = NOTIFICATION_ID_MESSAGE_BASE + event.messageId.toInt()
        synchronized(messageNotificationIds) {
            messageNotificationIds.getOrPut(event.contactHash) { mutableSetOf() }.add(notificationId)
        }
        getSystemService(NotificationManager::class.java).notify(notificationId, n)
    }

    /** Cancel every message notification currently posted for
     *  [contactHash]. Called from the UI when the user opens the
     *  conversation or deletes it — `setAutoCancel(true)` already
     *  dismisses a tapped notification, but any sibling notifications
     *  from the same contact stay on the shade until something cancels
     *  them. */
    fun cancelMessageNotificationsFor(contactHash: String) {
        val ids = synchronized(messageNotificationIds) {
            messageNotificationIds.remove(contactHash)
        } ?: return
        val nm = getSystemService(NotificationManager::class.java)
        ids.forEach { nm.cancel(it) }
    }

    companion object {
        const val ACTION_CONNECT_BLE        = "io.github.thatsfguy.reticulum.CONNECT_BLE"
        const val ACTION_CONNECT_BTCLASSIC  = "io.github.thatsfguy.reticulum.CONNECT_BTCLASSIC"
        const val ACTION_CONNECT_TCP        = "io.github.thatsfguy.reticulum.CONNECT_TCP"
        const val ACTION_CONNECT_AGNOSTIC_LORA = "io.github.thatsfguy.reticulum.CONNECT_AGNOSTIC_LORA"
        const val ACTION_DISCONNECT         = "io.github.thatsfguy.reticulum.DISCONNECT"
        const val ACTION_DISCONNECT_KIND    = "io.github.thatsfguy.reticulum.DISCONNECT_KIND"
        const val ACTION_RESTORE            = "io.github.thatsfguy.reticulum.RESTORE"
        const val EXTRA_BLE_ADDRESS         = "ble_address"
        const val EXTRA_BLE_NAME            = "ble_name"
        const val EXTRA_BT_CLASSIC_ADDRESS  = "bt_classic_address"
        const val EXTRA_BT_CLASSIC_NAME     = "bt_classic_name"
        const val EXTRA_TCP_HOST            = "tcp_host"
        const val EXTRA_TCP_PORT            = "tcp_port"
        const val EXTRA_AGNOSTIC_LORA_ADDRESS = "agnostic_lora_address"
        const val EXTRA_AGNOSTIC_LORA_NAME    = "agnostic_lora_name"
        const val EXTRA_AGNOSTIC_LORA_UPLINK  = "agnostic_lora_uplink"
        const val EXTRA_DISCONNECT_KIND     = "disconnect_kind"
        const val EXTRA_OPEN_CONTACT        = "open_contact"

        private const val LOGCAT_TAG = "ReticulumEngine"
        // Fallback cap for event-driven BLE/BtClassic reconnect. Wider
        // than the old 60 s blind-retry cap because the OS advertisement /
        // ACL signal short-circuits the wait — so a long timer just bounds
        // the "device never came back" case without costing responsiveness.
        private const val RECONNECT_WIDE_CAP_MS = 300_000L
        private const val CHANNEL_SERVICE  = "reticulum_service"
        private const val CHANNEL_MESSAGES = "reticulum_messages"
        private const val NOTIFICATION_ID_SERVICE       = 1
        private const val NOTIFICATION_ID_MESSAGE_BASE  = 1000

        fun connectBle(context: Context, address: String, name: String? = null) {
            val i = Intent(context, ReticulumService::class.java).apply {
                action = ACTION_CONNECT_BLE
                putExtra(EXTRA_BLE_ADDRESS, address)
                if (!name.isNullOrEmpty()) putExtra(EXTRA_BLE_NAME, name)
            }
            context.startForegroundService(i)
        }

        fun connectBtClassic(context: Context, address: String, name: String? = null) {
            val i = Intent(context, ReticulumService::class.java).apply {
                action = ACTION_CONNECT_BTCLASSIC
                putExtra(EXTRA_BT_CLASSIC_ADDRESS, address)
                if (!name.isNullOrEmpty()) putExtra(EXTRA_BT_CLASSIC_NAME, name)
            }
            context.startForegroundService(i)
        }

        fun connectTcp(context: Context, host: String, port: Int) {
            val i = Intent(context, ReticulumService::class.java).apply {
                action = ACTION_CONNECT_TCP
                putExtra(EXTRA_TCP_HOST, host)
                putExtra(EXTRA_TCP_PORT, port)
            }
            context.startForegroundService(i)
        }

        fun connectAgnosticLora(context: Context, address: String, name: String?, uplink: String?) {
            val i = Intent(context, ReticulumService::class.java).apply {
                action = ACTION_CONNECT_AGNOSTIC_LORA
                putExtra(EXTRA_AGNOSTIC_LORA_ADDRESS, address)
                if (!uplink.isNullOrBlank()) putExtra(EXTRA_AGNOSTIC_LORA_UPLINK, uplink)
                if (!name.isNullOrEmpty()) putExtra(EXTRA_AGNOSTIC_LORA_NAME, name)
            }
            context.startForegroundService(i)
        }

        fun disconnect(context: Context) {
            val i = Intent(context, ReticulumService::class.java).apply { action = ACTION_DISCONNECT }
            context.startService(i)
        }

        /** Ask the service to re-establish the transport the app was
         *  last connected to. Called by MainActivity on launch. Safe to
         *  call unconditionally — the service no-ops on a warm start and
         *  stops itself when there's nothing saved to reconnect. */
        fun restoreLastConnection(context: Context) {
            val i = Intent(context, ReticulumService::class.java).apply { action = ACTION_RESTORE }
            context.startForegroundService(i)
        }

        /** Disconnect a single transport kind, leaving any other attached
         *  transports running. UI hooks this for the per-section
         *  "Disconnect BLE / BT / TCP" buttons. */
        fun disconnectKind(context: Context, kind: ReticulumEngine.TransportKind) {
            val i = Intent(context, ReticulumService::class.java).apply {
                action = ACTION_DISCONNECT_KIND
                putExtra(EXTRA_DISCONNECT_KIND, kind.name)
            }
            context.startService(i)
        }
    }
}
