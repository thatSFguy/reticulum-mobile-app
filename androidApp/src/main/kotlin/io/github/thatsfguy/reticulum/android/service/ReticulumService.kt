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
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.transport.TcpInterface
import io.github.thatsfguy.reticulum.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Long-running foreground service that owns the [ReticulumEngine] and the
 * active [Transport]. The Activity binds to this for state observation; the
 * service keeps running across configuration changes and Activity destroy.
 *
 * Started with one of three actions in the Intent:
 *   - [ACTION_CONNECT_BLE] + [EXTRA_BLE_ADDRESS]
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
    private var currentTransport: Transport? = null
    private var connectJob: Job? = null
    private var eventCollectorJob: Job? = null

    val connection: StateFlow<ReticulumEngine.ConnectionState> get() = engine.connection
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
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification("Reticulum — listening for messages"))
        when (intent?.action) {
            ACTION_CONNECT_BLE -> {
                val address = intent.getStringExtra(EXTRA_BLE_ADDRESS)
                if (address.isNullOrEmpty()) stopSelf() else startBle(address)
            }
            ACTION_CONNECT_TCP -> {
                val host = intent.getStringExtra(EXTRA_TCP_HOST)
                val port = intent.getIntExtra(EXTRA_TCP_PORT, 0)
                if (host.isNullOrEmpty() || port <= 0) stopSelf() else startTcp(host, port)
            }
            ACTION_DISCONNECT -> {
                disconnect()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startBle(address: String) {
        if (!BlePermissions.allGranted(this)) {
            updateServiceNotification("Reticulum — BLE permissions missing")
            return
        }
        cancelConnect()
        connectJob = scope.launch {
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

                    currentTransport = transport
                    engine.attach(transport, ReticulumEngine.TransportKind.Ble)
                    engine.ensureIdentity()
                    // engine.attach() spins a reannounceJob whose first
                    // iteration fires immediately. We used to also call
                    // engine.sendAnnounceIfDue() here, but the two raced
                    // past the throttle gate and produced duplicate
                    // on-connect announces.
                    updateServiceNotification("Reticulum — connected (BLE)")
                    delayMs = 1_000L
                    // Wait for transport to disconnect, then loop.
                    transport.state.collect { st ->
                        if (st == io.github.thatsfguy.reticulum.transport.TransportState.Disconnected ||
                            st == io.github.thatsfguy.reticulum.transport.TransportState.Error) {
                            throw IllegalStateException("BLE transport ended: $st")
                        }
                    }
                } catch (t: Throwable) {
                    engine.logExternal("transport error: ${t::class.simpleName}: ${t.message}")
                    engine.detach()
                    runCatching { currentTransport?.disconnect() }
                    currentTransport = null
                    updateServiceNotification("Reticulum — reconnecting in ${delayMs / 1000}s")
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    private fun startTcp(host: String, port: Int) {
        cancelConnect()
        // Persist immediately so the host survives restart even if the
        // first connect attempt fails — otherwise the user has to retype
        // it every time they bounce the app.
        preferences.setLastTcp(host, port)
        connectJob = scope.launch {
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
                    currentTransport = transport
                    engine.attach(transport, ReticulumEngine.TransportKind.Tcp)
                    engine.ensureIdentity()
                    // engine.attach() spins a reannounceJob whose first
                    // iteration fires immediately. We used to also call
                    // engine.sendAnnounceIfDue() here, but the two raced
                    // past the throttle gate and produced duplicate
                    // on-connect announces.
                    updateServiceNotification("Reticulum — connected ($host:$port)")
                    connectedAtMs = System.currentTimeMillis()
                    connectFailBackoffMs = 15_000L  // reset connect backoff after successful socket

                    transport.state.collect { st ->
                        if (st == io.github.thatsfguy.reticulum.transport.TransportState.Disconnected ||
                            st == io.github.thatsfguy.reticulum.transport.TransportState.Error) {
                            throw IllegalStateException("TCP transport ended: $st")
                        }
                    }
                } catch (t: Throwable) {
                    engine.logExternal("transport error: ${t::class.simpleName}: ${t.message}")
                    engine.detach()
                    runCatching { currentTransport?.disconnect() }
                    currentTransport = null

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
                    updateServiceNotification("Reticulum — reconnecting in ${jitterMs / 1000}s")
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

    private fun disconnect() {
        cancelConnect()
        engine.detach()
        scope.launch { runCatching { currentTransport?.disconnect() } }
        currentTransport = null
    }

    private fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
    }

    suspend fun sendMessage(destinationHash: String, content: String) =
        engine.sendMessage(destinationHash, content)

    suspend fun sendAnnounce() = engine.sendAnnounce()

    suspend fun ourDestHash(): ByteArray = engine.ourDestHash()

    suspend fun myIdentityCard(): IdentityCard.Payload = engine.myIdentityCard()

    suspend fun applyIdentityCardJson(json: String): StoredDestination =
        engine.applyIdentityCard(IdentityCard.decode(json))

    suspend fun fetchNomadPage(
        destinationHash: String,
        path: String = "/page/index.mu",
        body: ByteArray = ByteArray(0),
    ): Result<String> = engine.fetchNomadPage(destinationHash, path, body = body)

    suspend fun addManualDestination(hashHex: String, label: String): StoredDestination =
        engine.addManualDestination(hashHex, label)

    suspend fun setFavorite(hashHex: String, favorite: Boolean) =
        engine.setFavorite(hashHex, favorite)

    suspend fun deleteDestinationAndMessages(hashHex: String) =
        engine.deleteDestinationAndMessages(hashHex)

    suspend fun deleteMessagesForDestination(hashHex: String) =
        engine.deleteMessagesForDestination(hashHex)

    suspend fun syncPropagation(hashHex: String): ReticulumEngine.PropagationSyncResult =
        engine.syncPropagation(hashHex)

    suspend fun syncPropagationAuto(): ReticulumEngine.PropagationSyncResult =
        engine.syncPropagationAuto()

    suspend fun resetIdentity() { engine.resetIdentity() }

    fun setDisplayName(name: String) {
        preferences.setDisplayName(name)
        scope.launch { runCatching { engine.sendAnnounce() } }
    }

    /** Push the saved RadioConfig to the BLE-attached RNode. No-op when
     *  the active transport isn't BLE (TCP transports have no radio
     *  knob — those settings are properties of the remote rnsd). */
    suspend fun reapplyRadioConfig() {
        val ble = currentTransport as? BleTransport ?: return
        ble.applyRadioConfig(preferences.radioConfig.value)
    }

    override fun onDestroy() {
        eventCollectorJob?.cancel()
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
        const val ACTION_CONNECT_BLE = "io.github.thatsfguy.reticulum.CONNECT_BLE"
        const val ACTION_CONNECT_TCP = "io.github.thatsfguy.reticulum.CONNECT_TCP"
        const val ACTION_DISCONNECT  = "io.github.thatsfguy.reticulum.DISCONNECT"
        const val EXTRA_BLE_ADDRESS  = "ble_address"
        const val EXTRA_TCP_HOST     = "tcp_host"
        const val EXTRA_TCP_PORT     = "tcp_port"
        const val EXTRA_OPEN_CONTACT = "open_contact"

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
    }
}
