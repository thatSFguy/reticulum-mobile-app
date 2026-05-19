package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import android.content.SharedPreferences
import io.github.thatsfguy.reticulum.transport.ConnectionMemory
import io.github.thatsfguy.reticulum.transport.KnownTcpNodes
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Tiny SharedPreferences wrapper for user-tunable settings that don't
 * belong in the protocol-state Room database. Right now: just the
 * display name we advertise in our announce app_data.
 *
 * Read access is synchronous (Engine reads it once per announce). For
 * the UI we expose a Flow so the Settings screen reacts when the user
 * saves a new value.
 */
class Preferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _displayName = MutableStateFlow(prefs.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME)
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    // First-launch default: pick one of [KnownTcpNodes.DEFAULTS] at
    // random and persist it, so each fresh install spreads attach load
    // across the rotation instead of all hammering the same operator
    // (origin: post-Columba load issue on RNS.MichMesh.net, 2026-05-07).
    // Once a value is persisted, subsequent launches load it as-is —
    // the rotation only acts on truly-fresh state. User can re-roll
    // explicitly via [pickAnotherTcpNode].
    private val initialTcp: Pair<String, Int> = run {
        val storedHost = prefs.getString(KEY_TCP_HOST, null)
        if (storedHost != null && storedHost.isNotBlank()) {
            storedHost to prefs.getInt(KEY_TCP_PORT, KnownTcpNodes.DEFAULTS.first().second)
        } else {
            val pick = KnownTcpNodes.pickRandom()
            prefs.edit()
                .putString(KEY_TCP_HOST, pick.first)
                .putInt(KEY_TCP_PORT, pick.second)
                .apply()
            pick
        }
    }
    private val _tcpHost = MutableStateFlow(initialTcp.first)
    val tcpHost: StateFlow<String> = _tcpHost.asStateFlow()

    private val _tcpPort = MutableStateFlow(initialTcp.second)
    val tcpPort: StateFlow<Int> = _tcpPort.asStateFlow()

    private val _btClassicAddress = MutableStateFlow(prefs.getString(KEY_BT_CLASSIC_ADDRESS, "") ?: "")
    val btClassicAddress: StateFlow<String> = _btClassicAddress.asStateFlow()

    private val _btClassicName = MutableStateFlow(prefs.getString(KEY_BT_CLASSIC_NAME, "") ?: "")
    val btClassicName: StateFlow<String> = _btClassicName.asStateFlow()

    // ---- connection-state persistence (auto-reconnect on launch) ------

    /** MAC of the last-connected BLE RNode — authoritative for a
     *  reconnect; [bleName] is only a display hint. Empty when unset.
     *  (Bluetooth Classic already had this; BLE did not.) */
    private val _bleAddress = MutableStateFlow(prefs.getString(KEY_BLE_ADDRESS, "") ?: "")
    val bleAddress: StateFlow<String> = _bleAddress.asStateFlow()

    private val _bleName = MutableStateFlow(prefs.getString(KEY_BLE_NAME, "") ?: "")
    val bleName: StateFlow<String> = _bleName.asStateFlow()

    /** Which transport last reached the Connected state — one of
     *  [ConnectionMemory.KIND_BLE] / `KIND_BT_CLASSIC` / `KIND_TCP`,
     *  or empty when the user is deliberately offline. Drives the
     *  cold-start auto-reconnect; see [resolveConnectionMemory]. */
    private val _lastTransportKind = MutableStateFlow(prefs.getString(KEY_LAST_TRANSPORT_KIND, "") ?: "")
    val lastTransportKind: StateFlow<String> = _lastTransportKind.asStateFlow()

    /** Whether the app re-establishes [lastTransportKind] on launch.
     *  On by default — the opt-out for users who want a manual cold
     *  start. */
    private val _autoReconnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RECONNECT, true))
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    fun setAutoReconnect(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()
        _autoReconnect.value = value
    }

    /** Destination hashes of RRC hubs that had a live session when the
     *  app was last running. The cold-start restore re-opens these once
     *  a transport is up; the engine's room auto-rejoin then restores
     *  each hub's joined rooms. */
    private val _liveRrcHubs = MutableStateFlow(
        prefs.getStringSet(KEY_LIVE_RRC_HUBS, emptySet())?.toSet() ?: emptySet(),
    )
    val liveRrcHubs: StateFlow<Set<String>> = _liveRrcHubs.asStateFlow()

    /** Mark an RRC hub as having a live session — called when the user
     *  opens a hub session. */
    fun addLiveRrcHub(hubHash: String) {
        val next = _liveRrcHubs.value + hubHash
        prefs.edit().putStringSet(KEY_LIVE_RRC_HUBS, next).apply()
        _liveRrcHubs.value = next
    }

    /** Forget an RRC hub's live session — called on an explicit close
     *  so a relaunch doesn't re-open a hub the user left. */
    fun removeLiveRrcHub(hubHash: String) {
        val next = _liveRrcHubs.value - hubHash
        prefs.edit().putStringSet(KEY_LIVE_RRC_HUBS, next).apply()
        _liveRrcHubs.value = next
    }

    /** Destination hashes the user pinned to the top of the Messages
     *  list. Local-only UI state, never sent on the wire — a separate
     *  concept from the contact (`favorite`) flag. */
    private val _pinnedConversations = MutableStateFlow(
        prefs.getStringSet(KEY_PINNED_CONVERSATIONS, emptySet())?.toSet() ?: emptySet(),
    )
    val pinnedConversations: StateFlow<Set<String>> = _pinnedConversations.asStateFlow()

    fun setPinnedConversation(hash: String, pinned: Boolean) {
        val next = if (pinned) _pinnedConversations.value + hash
                   else _pinnedConversations.value - hash
        prefs.edit().putStringSet(KEY_PINNED_CONVERSATIONS, next).apply()
        _pinnedConversations.value = next
    }

    private val _radioConfig = MutableStateFlow(loadRadioConfig())
    val radioConfig: StateFlow<io.github.thatsfguy.reticulum.platform.RadioConfig> = _radioConfig.asStateFlow()

    /** Hex destination hash of the propagation node the user picked
     *  for /get polling. Empty string when unset. */
    private val _propagationNode = MutableStateFlow(prefs.getString(KEY_PROPAGATION_NODE, "") ?: "")
    val propagationNode: StateFlow<String> = _propagationNode.asStateFlow()

    fun setPropagationNode(hashHex: String) {
        val normalized = hashHex.trim().lowercase()
        prefs.edit().putString(KEY_PROPAGATION_NODE, normalized).apply()
        _propagationNode.value = normalized
    }

    /** When true, inbound LXMF whose signature can't be verified
     *  against a known announce is dropped on the floor instead of
     *  saved as `state="unverified"`. Closes the first-contact
     *  display-name phishing surface — see [ReticulumEngine] init
     *  and audit reference 2026-05-13 MED-6. Default is false
     *  (preserve legacy show-as-unverified behaviour). */
    private val _dropUnverified = MutableStateFlow(prefs.getBoolean(KEY_DROP_UNVERIFIED, false))
    val dropUnverified: StateFlow<Boolean> = _dropUnverified.asStateFlow()

    fun setDropUnverified(value: Boolean) {
        prefs.edit().putBoolean(KEY_DROP_UNVERIFIED, value).apply()
        _dropUnverified.value = value
    }

    /** Experimental: enable Reticulum Relay Chat (RRC). Off by default
     *  — RRC is a new wire protocol still under development and not yet
     *  interop-verified. Gates the RRC UI and engine session so it stays
     *  invisible to ordinary users until it's ready. */
    private val _experimentalRrc = MutableStateFlow(prefs.getBoolean(KEY_EXPERIMENTAL_RRC, false))
    val experimentalRrc: StateFlow<Boolean> = _experimentalRrc.asStateFlow()

    fun setExperimentalRrc(value: Boolean) {
        prefs.edit().putBoolean(KEY_EXPERIMENTAL_RRC, value).apply()
        _experimentalRrc.value = value
    }

    /** Whether the NomadNet page browser is enabled. Off by default —
     *  an opt-in feature; when on it adds a Nomad tab to the bottom
     *  bar (docs/REDESIGN.md §6 Features). */
    private val _nomadEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOMAD_ENABLED, false))
    val nomadEnabled: StateFlow<Boolean> = _nomadEnabled.asStateFlow()

    fun setNomadEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_NOMAD_ENABLED, value).apply()
        _nomadEnabled.value = value
    }

    /** UI theme preference — "system" | "light" | "dark". Drives
     *  ReticulumTheme; "system" defers to the OS dark/light setting. */
    private val _themePreference = MutableStateFlow(
        prefs.getString(KEY_THEME, "system") ?: "system",
    )
    val themePreference: StateFlow<String> = _themePreference.asStateFlow()

    fun setThemePreference(value: String) {
        prefs.edit().putString(KEY_THEME, value).apply()
        _themePreference.value = value
    }

    /** True until the app has been launched once. Drives the first-run
     *  landing on Settings → Connect — there is nothing to do on an
     *  empty Messages list before a transport is attached. Captured at
     *  construction so a launch observes a stable value even after
     *  [markFirstLaunchDone] flips the stored flag. */
    val isFirstLaunch: Boolean = !prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    /** Consume the first launch so subsequent launches start normally. */
    fun markFirstLaunchDone() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }

    fun setRadioConfig(value: io.github.thatsfguy.reticulum.platform.RadioConfig) {
        prefs.edit()
            .putLong(KEY_RADIO_FREQ, value.frequencyHz)
            .putLong(KEY_RADIO_BW, value.bandwidthHz)
            .putInt(KEY_RADIO_SF, value.spreadingFactor)
            .putInt(KEY_RADIO_CR, value.codingRate)
            .putInt(KEY_RADIO_TXP, value.txPowerDbm)
            .apply()
        _radioConfig.value = value
    }

    private fun loadRadioConfig(): io.github.thatsfguy.reticulum.platform.RadioConfig {
        val def = io.github.thatsfguy.reticulum.platform.RadioConfig()
        return io.github.thatsfguy.reticulum.platform.RadioConfig(
            frequencyHz     = prefs.getLong(KEY_RADIO_FREQ, def.frequencyHz),
            bandwidthHz     = prefs.getLong(KEY_RADIO_BW, def.bandwidthHz),
            spreadingFactor = prefs.getInt (KEY_RADIO_SF, def.spreadingFactor),
            codingRate      = prefs.getInt (KEY_RADIO_CR, def.codingRate),
            txPowerDbm      = prefs.getInt (KEY_RADIO_TXP, def.txPowerDbm),
        )
    }

    fun getDisplayName(): String = _displayName.value

    fun setDisplayName(value: String) {
        val normalized = value.trim().ifEmpty { DEFAULT_DISPLAY_NAME }
        prefs.edit().putString(KEY_DISPLAY_NAME, normalized).apply()
        _displayName.value = normalized
    }

    /** Persist the TCP host/port a user typed so it survives restarts. */
    fun setLastTcp(host: String, port: Int) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty() || port <= 0) return
        prefs.edit()
            .putString(KEY_TCP_HOST, trimmedHost)
            .putInt(KEY_TCP_PORT, port)
            .apply()
        _tcpHost.value = trimmedHost
        _tcpPort.value = port
    }

    /** Re-roll the TCP default from [KnownTcpNodes.DEFAULTS], picking
     *  something other than the user's current entry. Used by the
     *  Settings shuffle button when the current default is down or the
     *  user just wants to spread their own attach load. */
    fun pickAnotherTcpNode() {
        val current = _tcpHost.value to _tcpPort.value
        val pick = KnownTcpNodes.pickDifferentThan(current)
        setLastTcp(pick.first, pick.second)
    }

    /** Persist the last-connected Bluetooth Classic device. The MAC is
     *  authoritative — name is just a UI hint and may be empty if the
     *  bond hadn't fetched the friendly name yet. */
    fun setLastBtClassic(address: String, name: String?) {
        val trimmedAddress = address.trim()
        if (trimmedAddress.isEmpty()) return
        val trimmedName = name?.trim().orEmpty()
        prefs.edit()
            .putString(KEY_BT_CLASSIC_ADDRESS, trimmedAddress)
            .putString(KEY_BT_CLASSIC_NAME, trimmedName)
            .apply()
        _btClassicAddress.value = trimmedAddress
        _btClassicName.value = trimmedName
    }

    /** Persist the last-connected BLE RNode. MAC is authoritative;
     *  [name] is a display hint and may be empty. */
    fun setLastBle(address: String, name: String?) {
        val trimmedAddress = address.trim()
        if (trimmedAddress.isEmpty()) return
        val trimmedName = name?.trim().orEmpty()
        prefs.edit()
            .putString(KEY_BLE_ADDRESS, trimmedAddress)
            .putString(KEY_BLE_NAME, trimmedName)
            .apply()
        _bleAddress.value = trimmedAddress
        _bleName.value = trimmedName
    }

    /** Record which transport just reached Connected, so the next cold
     *  start can restore it. Pass a `ConnectionMemory.KIND_*` value. */
    fun setLastTransportKind(kind: String) {
        prefs.edit().putString(KEY_LAST_TRANSPORT_KIND, kind).apply()
        _lastTransportKind.value = kind
    }

    /** Forget the last transport — called on an explicit user
     *  Disconnect so a relaunch honours "I went offline on purpose"
     *  and doesn't auto-reconnect. */
    fun clearLastTransportKind() {
        prefs.edit().putString(KEY_LAST_TRANSPORT_KIND, "").apply()
        _lastTransportKind.value = ""
    }

    /**
     * The transport to auto-reconnect on a cold start, or `null` to
     * come up disconnected. Folds the persisted fields through the
     * shared [ConnectionMemory.resolve] decision (honours the
     * auto-reconnect opt-out and rejects malformed params).
     */
    fun resolveConnectionMemory(): ConnectionMemory? = ConnectionMemory.resolve(
        autoReconnect = _autoReconnect.value,
        kind = _lastTransportKind.value.ifBlank { null },
        bleAddress = _bleAddress.value,
        bleName = _bleName.value,
        btClassicAddress = _btClassicAddress.value,
        btClassicName = _btClassicName.value,
        tcpHost = _tcpHost.value,
        tcpPort = _tcpPort.value,
    )

    companion object {
        private const val NAME = "reticulum_prefs"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_TCP_HOST = "tcp_host"
        private const val KEY_TCP_PORT = "tcp_port"
        private const val KEY_BT_CLASSIC_ADDRESS = "bt_classic_address"
        private const val KEY_BT_CLASSIC_NAME = "bt_classic_name"
        private const val KEY_BLE_ADDRESS = "ble_address"
        private const val KEY_BLE_NAME = "ble_name"
        private const val KEY_LAST_TRANSPORT_KIND = "last_transport_kind"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_LIVE_RRC_HUBS = "live_rrc_hubs"
        private const val KEY_RADIO_FREQ = "radio_freq_hz"
        private const val KEY_RADIO_BW = "radio_bw_hz"
        private const val KEY_RADIO_SF = "radio_sf"
        private const val KEY_RADIO_CR = "radio_cr"
        private const val KEY_RADIO_TXP = "radio_txp_dbm"
        private const val KEY_PROPAGATION_NODE = "propagation_node_hex"
        private const val KEY_DROP_UNVERIFIED = "drop_unverified_messages"
        private const val KEY_EXPERIMENTAL_RRC = "experimental_rrc"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val KEY_NOMAD_ENABLED = "nomad_enabled"
        private const val KEY_PINNED_CONVERSATIONS = "pinned_conversations"
        private const val KEY_THEME = "theme_preference"
        const val DEFAULT_DISPLAY_NAME = "Reticulum Mobile"
        // TCP default is now per-install random from [KnownTcpNodes.DEFAULTS].
        // Old constants removed — anything still importing them will fail
        // compilation, which is the point.
    }
}
