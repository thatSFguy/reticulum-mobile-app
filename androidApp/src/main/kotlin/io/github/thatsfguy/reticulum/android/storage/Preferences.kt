package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import android.content.SharedPreferences
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

    companion object {
        private const val NAME = "reticulum_prefs"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_TCP_HOST = "tcp_host"
        private const val KEY_TCP_PORT = "tcp_port"
        private const val KEY_BT_CLASSIC_ADDRESS = "bt_classic_address"
        private const val KEY_BT_CLASSIC_NAME = "bt_classic_name"
        private const val KEY_RADIO_FREQ = "radio_freq_hz"
        private const val KEY_RADIO_BW = "radio_bw_hz"
        private const val KEY_RADIO_SF = "radio_sf"
        private const val KEY_RADIO_CR = "radio_cr"
        private const val KEY_RADIO_TXP = "radio_txp_dbm"
        private const val KEY_PROPAGATION_NODE = "propagation_node_hex"
        const val DEFAULT_DISPLAY_NAME = "Reticulum Mobile"
        // TCP default is now per-install random from [KnownTcpNodes.DEFAULTS].
        // Old constants removed — anything still importing them will fail
        // compilation, which is the point.
    }
}
