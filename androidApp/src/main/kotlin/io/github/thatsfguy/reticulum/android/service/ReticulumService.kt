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
import io.github.thatsfguy.reticulum.android.platform.BlePermissions
import io.github.thatsfguy.reticulum.android.storage.Preferences
import io.github.thatsfguy.reticulum.android.storage.Repositories
import io.github.thatsfguy.reticulum.engine.IdentityCard
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.platform.AndroidCryptoProvider
import io.github.thatsfguy.reticulum.platform.BleTransport
import io.github.thatsfguy.reticulum.platform.BtClassicTransport
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.transport.TcpInterface
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.hexToBytes
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

    val connection: StateFlow<ReticulumEngine.ConnectionState> get() = engine.connection
    val connections: StateFlow<List<ReticulumEngine.ConnectionState>> get() = engine.connections
    val events: Flow<ReticulumEngine.EngineEvent> get() = engine.events
    val repos: Repositories get() = repositories
    val prefs: Preferences get() = preferences

    inner class LocalBinder : Binder() { val service: ReticulumService = this@ReticulumService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        repositories = Repositories.create(applicationContext)
        preferences = Preferences(applicationContext)
        engine = ReticulumEngine(
            crypto = AndroidCryptoProvider(),
            identityRepo = repositories.identity,
            destinationRepo = repositories.destinations,
            messageRepo = repositories.messages,
            scope = scope,
            nowMs = { System.currentTimeMillis() },
            displayNameProvider = { preferences.getDisplayName() },
            nomadPageCache = repositories.nomadPageCache,
        )

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
                if (address.isNullOrEmpty()) stopSelf() else startBle(address)
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
        }
        return START_STICKY
    }

    private fun startBle(address: String) {
        if (!BlePermissions.allGranted(this)) {
            updateServiceNotification("Reticulum — BLE permissions missing")
            return
        }
        val kind = ReticulumEngine.TransportKind.Ble
        cancelConnect(kind)
        markPending(kind)
        connectJobs[kind] = scope.launch {
            // Simple exponential backoff supervisor: keeps re-connecting on failure.
            var delayMs = 1_000L
            while (true) {
                try {
                    engine.logExternal("BLE: connecting to $address")
                    val device = BleTransport.deviceByAddress(this@ReticulumService, address)
                    val transport = BleTransport(this@ReticulumService, device, scope)
                    transport.connect()
                    engine.logExternal("BLE: connected, GATT ready")

                    // Push the saved radio config (frequency, bandwidth, SF,
                    // CR, TX power) and turn the radio on. Without this the
                    // RNode sits idle and announces won't even hit the air.
                    val cfg = preferences.radioConfig.value
                    runCatching { transport.applyRadioConfig(cfg) }
                        .onSuccess {
                            engine.logExternal("RNode: radio on at ${cfg.frequencyHz / 1_000_000.0} MHz, BW ${cfg.bandwidthHz / 1000} kHz, SF ${cfg.spreadingFactor}, CR ${cfg.codingRate}, ${cfg.txPowerDbm} dBm")
                        }
                        .onFailure {
                            engine.logExternal("RNode: radio config failed: ${it.message}")
                        }

                    currentTransports[kind] = transport
                    engine.attach(transport, kind)
                    engine.ensureIdentity()
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
                    engine.detach(kind)
                    runCatching { currentTransports[kind]?.disconnect() }
                    currentTransports.remove(kind)
                    refreshNotification(prefix = "Reticulum — BLE reconnecting in ${delayMs / 1000}s")
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(60_000L)
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
                try {
                    engine.logExternal("BT Classic: connecting to $address")
                    val device = BtClassicTransport.deviceByAddress(this@ReticulumService, address)
                    val transport = BtClassicTransport(this@ReticulumService, device, scope)
                    transport.connect()
                    engine.logExternal("BT Classic: RFCOMM ready")

                    // Push saved radio config and turn the radio on, just like BLE.
                    val cfg = preferences.radioConfig.value
                    runCatching { transport.applyRadioConfig(cfg) }
                        .onSuccess {
                            engine.logExternal("RNode: radio on at ${cfg.frequencyHz / 1_000_000.0} MHz, BW ${cfg.bandwidthHz / 1000} kHz, SF ${cfg.spreadingFactor}, CR ${cfg.codingRate}, ${cfg.txPowerDbm} dBm")
                        }
                        .onFailure {
                            engine.logExternal("RNode: radio config failed: ${it.message}")
                        }

                    currentTransports[kind] = transport
                    engine.attach(transport, kind)
                    engine.ensureIdentity()
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
                    engine.detach(kind)
                    runCatching { currentTransports[kind]?.disconnect() }
                    currentTransports.remove(kind)
                    refreshNotification(prefix = "Reticulum — BT Classic reconnecting in ${delayMs / 1000}s")
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(60_000L)
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
                try {
                    engine.logExternal("TCP: connecting to $host:$port (TCP handshake — DNS + 3-way ACK can take 30s+ on a slow path)")
                    val transport = TcpInterface(
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
                    engine.detach(kind)
                    runCatching { currentTransports[kind]?.disconnect() }
                    currentTransports.remove(kind)

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
        engine.detach(null)
        val toClose = currentTransports.values.toList()
        currentTransports.clear()
        scope.launch { toClose.forEach { runCatching { it.disconnect() } } }
        refreshNotification()
    }

    /** Tear down only [kind]; leave other transports alone. */
    private fun disconnectKind(kind: ReticulumEngine.TransportKind) {
        cancelConnect(kind)
        unmarkPending(kind)
        engine.detach(kind)
        val transport = currentTransports.remove(kind)
        if (transport != null) {
            scope.launch { runCatching { transport.disconnect() } }
        }
        refreshNotification()
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
    ) = engine.sendMessage(destinationHash, content, imageBytes = imageBytes)

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
        ).apply { description = "An LXMF message addressed to you was received." })
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (event.verified) "New message" else "Unverified message")
            .setContentText(event.content.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.content))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_MESSAGE_BASE + event.messageId.toInt(), n)
    }

    companion object {
        const val ACTION_CONNECT_BLE        = "io.github.thatsfguy.reticulum.CONNECT_BLE"
        const val ACTION_CONNECT_BTCLASSIC  = "io.github.thatsfguy.reticulum.CONNECT_BTCLASSIC"
        const val ACTION_CONNECT_TCP        = "io.github.thatsfguy.reticulum.CONNECT_TCP"
        const val ACTION_DISCONNECT         = "io.github.thatsfguy.reticulum.DISCONNECT"
        const val ACTION_DISCONNECT_KIND    = "io.github.thatsfguy.reticulum.DISCONNECT_KIND"
        const val EXTRA_BLE_ADDRESS         = "ble_address"
        const val EXTRA_BT_CLASSIC_ADDRESS  = "bt_classic_address"
        const val EXTRA_BT_CLASSIC_NAME     = "bt_classic_name"
        const val EXTRA_TCP_HOST            = "tcp_host"
        const val EXTRA_TCP_PORT            = "tcp_port"
        const val EXTRA_DISCONNECT_KIND     = "disconnect_kind"
        const val EXTRA_OPEN_CONTACT        = "open_contact"

        private const val LOGCAT_TAG = "ReticulumEngine"
        private const val CHANNEL_SERVICE  = "reticulum_service"
        private const val CHANNEL_MESSAGES = "reticulum_messages"
        private const val NOTIFICATION_ID_SERVICE       = 1
        private const val NOTIFICATION_ID_MESSAGE_BASE  = 1000

        fun connectBle(context: Context, address: String) {
            val i = Intent(context, ReticulumService::class.java).apply {
                action = ACTION_CONNECT_BLE
                putExtra(EXTRA_BLE_ADDRESS, address)
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

        fun disconnect(context: Context) {
            val i = Intent(context, ReticulumService::class.java).apply { action = ACTION_DISCONNECT }
            context.startService(i)
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
