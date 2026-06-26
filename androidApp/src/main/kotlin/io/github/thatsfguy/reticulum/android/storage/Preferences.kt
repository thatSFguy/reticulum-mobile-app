package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import android.content.SharedPreferences
import io.github.thatsfguy.reticulum.transport.ConnectionMemory
import io.github.thatsfguy.reticulum.transport.KnownTcpNodes
import io.github.thatsfguy.reticulum.transport.SavedNode
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

    /** Last-connected agnostic-LoRa-Net node over BLE. [agnosticLoraAddress]
     *  is the BLE MAC; [agnosticLoraName] the `ALN-…` advertised name (a
     *  friendly name or first-8-hex label — a display hint, not the node id);
     *  [agnosticLoraUplink] the mesh node id outbound packets are tunnelled
     *  toward (the BLE equivalent of a TCP host:port). All three are needed
     *  to rebuild the transport on reconnect. */
    private val _agnosticLoraAddress = MutableStateFlow(prefs.getString(KEY_AGNOSTIC_LORA_ADDRESS, "") ?: "")
    val agnosticLoraAddress: StateFlow<String> = _agnosticLoraAddress.asStateFlow()

    private val _agnosticLoraName = MutableStateFlow(prefs.getString(KEY_AGNOSTIC_LORA_NAME, "") ?: "")
    val agnosticLoraName: StateFlow<String> = _agnosticLoraName.asStateFlow()

    private val _agnosticLoraUplink = MutableStateFlow(prefs.getString(KEY_AGNOSTIC_LORA_UPLINK, "") ?: "")
    val agnosticLoraUplink: StateFlow<String> = _agnosticLoraUplink.asStateFlow()

    /** Which transports last reached the Connected state — a set of
     *  [ConnectionMemory.KIND_BLE] / `KIND_BT_CLASSIC` / `KIND_TCP` /
     *  `KIND_AGNOSTIC_LORA`, empty when the user is deliberately offline.
     *  A *set* (not a single value) so simultaneous attachments — e.g.
     *  TCP + agnostic-LoRa-Net at once — all come back on a cold start
     *  instead of only the last one connected. Drives the cold-start
     *  auto-reconnect; see [resolveConnectionMemories]. */
    private val _lastTransportKinds = MutableStateFlow(loadLastTransportKinds())
    val lastTransportKinds: StateFlow<Set<String>> = _lastTransportKinds.asStateFlow()

    /** Migrate the legacy single-string [KEY_LAST_TRANSPORT_KIND] into the
     *  set-valued [KEY_LAST_TRANSPORT_KINDS] on first run of this version.
     *  Reading the new key directly would throw `ClassCastException` on an
     *  install that still holds the old String value, so the old key is
     *  consumed (and cleared) before the set key is trusted. */
    private fun loadLastTransportKinds(): Set<String> {
        prefs.getStringSet(KEY_LAST_TRANSPORT_KINDS, null)?.let { return it.toSet() }
        val legacy = prefs.getString(KEY_LAST_TRANSPORT_KIND, "")?.ifBlank { null }
        val migrated = legacy?.let { setOf(it) } ?: emptySet()
        prefs.edit()
            .putStringSet(KEY_LAST_TRANSPORT_KINDS, migrated)
            .remove(KEY_LAST_TRANSPORT_KIND)
            .apply()
        return migrated
    }

    /** Whether the app re-establishes [lastTransportKinds] on launch.
     *  On by default — the opt-out for users who want a manual cold
     *  start. */
    private val _autoReconnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RECONNECT, true))
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    fun setAutoReconnect(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()
        _autoReconnect.value = value
    }

    /** User-saved nodes for the multi-node picker (Phase 4), most-recent
     *  first. Populated as the user connects; the "Saved nodes" UI lists
     *  them for one-tap (re)connect and forget. Stored as one encoded
     *  [SavedNode] per line under [KEY_SAVED_NODES]. */
    private val _savedNodes = MutableStateFlow(loadSavedNodes())
    val savedNodes: StateFlow<List<SavedNode>> = _savedNodes.asStateFlow()

    private fun loadSavedNodes(): List<SavedNode> {
        val raw = prefs.getString(KEY_SAVED_NODES, null)
        if (raw != null) {
            return raw.split('\n').mapNotNull { SavedNode.decode(it) }
        }
        // First run on this version: migrate the legacy single-last BLE /
        // Bluetooth-Classic entries into the list. The TCP host is a
        // first-launch default (not a user-connected node), so it's left
        // out — TCP entries are added when the user actually connects.
        // _lastTransportKinds is initialised before this field, and its
        // loader has already migrated/cleared the legacy single-kind key,
        // so read the remembered set rather than the consumed old key.
        val lastKinds = _lastTransportKinds.value
        val migrated = buildList {
            prefs.getString(KEY_BLE_ADDRESS, "")?.takeIf { it.isNotBlank() }?.let {
                add(SavedNode(ConnectionMemory.KIND_BLE, it, null, prefs.getString(KEY_BLE_NAME, "")?.ifBlank { null }))
            }
            prefs.getString(KEY_BT_CLASSIC_ADDRESS, "")?.takeIf { it.isNotBlank() }?.let {
                add(SavedNode(ConnectionMemory.KIND_BT_CLASSIC, it, null, prefs.getString(KEY_BT_CLASSIC_NAME, "")?.ifBlank { null }))
            }
        }.sortedByDescending { it.kind in lastKinds }
        persistSavedNodesRaw(migrated)
        return migrated
    }

    private fun persistSavedNodesRaw(list: List<SavedNode>) {
        prefs.edit().putString(KEY_SAVED_NODES, list.joinToString("\n") { it.encode() }).apply()
    }

    /** Upsert [node] to the front of the saved list (most-recent-first),
     *  de-duplicating by [SavedNode.key]. */
    fun addSavedNode(node: SavedNode) {
        val updated = listOf(node) + _savedNodes.value.filterNot { it.key == node.key }
        persistSavedNodesRaw(updated)
        _savedNodes.value = updated
    }

    /** Forget a saved node by [SavedNode.key]. Also clears the cold-start
     *  auto-reconnect target if it pointed at this node, so "forget"
     *  really means it won't silently come back next launch. */
    fun removeSavedNode(key: String) {
        val removed = _savedNodes.value.firstOrNull { it.key == key }
        val updated = _savedNodes.value.filterNot { it.key == key }
        persistSavedNodesRaw(updated)
        _savedNodes.value = updated
        if (removed != null && removed.kind in _lastTransportKinds.value && matchesLastAddress(removed)) {
            removeLastTransportKind(removed.kind)
        }
    }

    private fun matchesLastAddress(node: SavedNode): Boolean = when (node.kind) {
        ConnectionMemory.KIND_BLE -> _bleAddress.value == node.address
        ConnectionMemory.KIND_BT_CLASSIC -> _btClassicAddress.value == node.address
        ConnectionMemory.KIND_TCP -> _tcpHost.value == node.address && _tcpPort.value == node.port
        else -> false
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

    /** Per-contact "last read" timestamp (ms epoch). Anything newer
     *  than this in the messages table counts as unread for that
     *  conversation, driving the badge on the Messages list. Stored as
     *  a single flat-text SharedPreferences entry of the form
     *  `hash:ts;hash:ts;...` — both because SharedPreferences has no
     *  Map type and because the working set (typically ≤ 100 entries)
     *  doesn't justify pulling in JSON. */
    private val _lastReadTimes = MutableStateFlow(loadLastReadTimes())
    val lastReadTimes: StateFlow<Map<String, Long>> = _lastReadTimes.asStateFlow()

    private fun loadLastReadTimes(): Map<String, Long> {
        val raw = prefs.getString(KEY_LAST_READ_TIMES, null) ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val idx = entry.indexOf(':')
            if (idx <= 0 || idx >= entry.length - 1) return@mapNotNull null
            val hash = entry.substring(0, idx)
            val ts = entry.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
            hash to ts
        }.toMap()
    }

    private fun storeLastReadTimes(map: Map<String, Long>) {
        val encoded = map.entries.joinToString(";") { (h, t) -> "$h:$t" }
        prefs.edit().putString(KEY_LAST_READ_TIMES, encoded).apply()
    }

    /** Mark every incoming message from [hash] up to [timestamp] as
     *  read. Called when the user opens the conversation; subsequent
     *  arrivals (with timestamp > [timestamp]) flip the badge back on. */
    fun setLastRead(hash: String, timestamp: Long) {
        val current = _lastReadTimes.value
        val existing = current[hash] ?: 0L
        if (timestamp <= existing) return
        val next = current + (hash to timestamp)
        storeLastReadTimes(next)
        _lastReadTimes.value = next
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

    /** Whether the agnostic-LoRa-Net (ALN) BLE transport is exposed in
     *  Settings → Connection. Off by default — the ALN integration isn't
     *  released yet, so its connect UI stays hidden behind this opt-in
     *  toggle in the Optional features section. */
    private val _agnosticLoraEnabled = MutableStateFlow(prefs.getBoolean(KEY_AGNOSTIC_LORA_ENABLED, false))
    val agnosticLoraEnabled: StateFlow<Boolean> = _agnosticLoraEnabled.asStateFlow()

    fun setAgnosticLoraEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AGNOSTIC_LORA_ENABLED, value).apply()
        _agnosticLoraEnabled.value = value
    }

    /** Per-transport enable switches. A disabled transport is never
     *  started: its scanner / socket / GATT never opens and its wire
     *  parser never sees a byte — so the runtime attack surface is only
     *  the transports this install actually uses. The three core
     *  transports default ON (no behaviour change for existing installs);
     *  USB defaults OFF until its driver ships (issue #41). ALN keeps its
     *  own [agnosticLoraEnabled] toggle above. Turn off the ones you don't
     *  use in Settings → Connection → Transports. */
    private val _bleEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLE_ENABLED, true))
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()
    fun setBleEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BLE_ENABLED, value).apply()
        _bleEnabled.value = value
    }

    private val _btClassicEnabled = MutableStateFlow(prefs.getBoolean(KEY_BT_CLASSIC_ENABLED, true))
    val btClassicEnabled: StateFlow<Boolean> = _btClassicEnabled.asStateFlow()
    fun setBtClassicEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BT_CLASSIC_ENABLED, value).apply()
        _btClassicEnabled.value = value
    }

    private val _tcpEnabled = MutableStateFlow(prefs.getBoolean(KEY_TCP_ENABLED, true))
    val tcpEnabled: StateFlow<Boolean> = _tcpEnabled.asStateFlow()
    fun setTcpEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TCP_ENABLED, value).apply()
        _tcpEnabled.value = value
    }

    private val _usbEnabled = MutableStateFlow(prefs.getBoolean(KEY_USB_ENABLED, false))
    val usbEnabled: StateFlow<Boolean> = _usbEnabled.asStateFlow()
    fun setUsbEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_USB_ENABLED, value).apply()
        _usbEnabled.value = value
    }

    /** UI theme preference — "system" | "light" | "dark" | "black".
     *  Drives ReticulumTheme; "system" defers to the OS dark/light
     *  setting, "black" is the OLED/true-black dark variant. */
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
        addSavedNode(SavedNode(ConnectionMemory.KIND_BT_CLASSIC, trimmedAddress, null, trimmedName.ifBlank { null }))
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
        addSavedNode(SavedNode(ConnectionMemory.KIND_BLE, trimmedAddress, null, trimmedName.ifBlank { null }))
    }

    /** Persist the last-connected agnostic-LoRa-Net node. MAC is
     *  authoritative; [name] is a display hint; [uplink] is the *optional*
     *  static fallback node (identity addressing via the mesh directory is
     *  the normal mode). No-ops on a blank MAC. */
    fun setLastAgnosticLora(address: String, name: String?, uplink: String?) {
        val trimmedAddress = address.trim()
        val trimmedUplink = uplink?.trim().orEmpty()
        if (trimmedAddress.isEmpty()) return
        val trimmedName = name?.trim().orEmpty()
        prefs.edit()
            .putString(KEY_AGNOSTIC_LORA_ADDRESS, trimmedAddress)
            .putString(KEY_AGNOSTIC_LORA_NAME, trimmedName)
            .putString(KEY_AGNOSTIC_LORA_UPLINK, trimmedUplink)
            .apply()
        _agnosticLoraAddress.value = trimmedAddress
        _agnosticLoraName.value = trimmedName
        _agnosticLoraUplink.value = trimmedUplink
    }

    /** Record that [kind] just reached Connected, so the next cold start
     *  can restore it. Additive — other already-remembered kinds stay,
     *  so simultaneous attachments all come back. Pass a
     *  `ConnectionMemory.KIND_*` value. */
    fun addLastTransportKind(kind: String) {
        if (kind in _lastTransportKinds.value) return
        val next = _lastTransportKinds.value + kind
        prefs.edit().putStringSet(KEY_LAST_TRANSPORT_KINDS, next).apply()
        _lastTransportKinds.value = next
    }

    /** Forget a single transport [kind] — called when the user
     *  disconnects just that transport, leaving any others to still
     *  auto-reconnect next launch. */
    fun removeLastTransportKind(kind: String) {
        if (kind !in _lastTransportKinds.value) return
        val next = _lastTransportKinds.value - kind
        prefs.edit().putStringSet(KEY_LAST_TRANSPORT_KINDS, next).apply()
        _lastTransportKinds.value = next
    }

    /** Forget every remembered transport — called on an explicit global
     *  Disconnect so a relaunch honours "I went offline on purpose" and
     *  doesn't auto-reconnect anything. */
    fun clearLastTransportKind() {
        prefs.edit().putStringSet(KEY_LAST_TRANSPORT_KINDS, emptySet()).apply()
        _lastTransportKinds.value = emptySet()
    }

    /**
     * The transports to auto-reconnect on a cold start — one entry per
     * remembered kind, empty to come up disconnected. Folds the persisted
     * fields through the shared [ConnectionMemory.resolveAll] decision
     * (honours the auto-reconnect opt-out and drops malformed params).
     */
    fun resolveConnectionMemories(): List<ConnectionMemory> = ConnectionMemory.resolveAll(
        autoReconnect = _autoReconnect.value,
        kinds = _lastTransportKinds.value,
        bleAddress = _bleAddress.value,
        bleName = _bleName.value,
        btClassicAddress = _btClassicAddress.value,
        btClassicName = _btClassicName.value,
        tcpHost = _tcpHost.value,
        tcpPort = _tcpPort.value,
        agnosticLoraAddress = _agnosticLoraAddress.value,
        agnosticLoraName = _agnosticLoraName.value,
        agnosticLoraUplink = _agnosticLoraUplink.value,
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
        private const val KEY_AGNOSTIC_LORA_ADDRESS = "agnostic_lora_address"
        private const val KEY_AGNOSTIC_LORA_NAME = "agnostic_lora_name"
        private const val KEY_AGNOSTIC_LORA_UPLINK = "agnostic_lora_uplink"
        // Legacy single-kind key, migrated into KEY_LAST_TRANSPORT_KINDS
        // by loadLastTransportKinds() and then removed. Kept only so the
        // migration can read the old value on upgrade.
        private const val KEY_LAST_TRANSPORT_KIND = "last_transport_kind"
        private const val KEY_LAST_TRANSPORT_KINDS = "last_transport_kinds"
        private const val KEY_SAVED_NODES = "saved_nodes"
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
        private const val KEY_AGNOSTIC_LORA_ENABLED = "agnostic_lora_enabled"
        private const val KEY_BLE_ENABLED = "ble_enabled"
        private const val KEY_BT_CLASSIC_ENABLED = "bt_classic_enabled"
        private const val KEY_TCP_ENABLED = "tcp_enabled"
        private const val KEY_USB_ENABLED = "usb_enabled"
        private const val KEY_PINNED_CONVERSATIONS = "pinned_conversations"
        private const val KEY_LAST_READ_TIMES = "last_read_times_per_contact"
        private const val KEY_THEME = "theme_preference"
        const val DEFAULT_DISPLAY_NAME = "Reticulum Mobile"
        // TCP default is now per-install random from [KnownTcpNodes.DEFAULTS].
        // Old constants removed — anything still importing them will fail
        // compilation, which is the point.
    }
}
