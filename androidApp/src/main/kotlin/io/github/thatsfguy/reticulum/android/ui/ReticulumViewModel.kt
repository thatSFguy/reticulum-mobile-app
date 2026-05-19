package io.github.thatsfguy.reticulum.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.engine.RrcEvent
import io.github.thatsfguy.reticulum.engine.RrcState
import io.github.thatsfguy.reticulum.rrc.RrcRoomListing
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.store.StoredRrcHub
import io.github.thatsfguy.reticulum.store.StoredRrcMessage
import io.github.thatsfguy.reticulum.store.StoredRrcRoom
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.hexToBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state derived from the bound [ReticulumService]. The Activity calls
 * [bind] when the binder arrives, [unbind] when it's gone.
 *
 * Every Flow exposed here is built from [_service] via flatMapLatest so a
 * service rebind (e.g. after the phone unlocks and onStart re-binds) re-
 * subscribes the UI to the live data without requiring a manual recompose.
 * That fixes the "blank Messages tab on unlock until I switch tabs"
 * symptom.
 */
class ReticulumViewModel : ViewModel() {

    private val _service = MutableStateFlow<ReticulumService?>(null)
    val service: StateFlow<ReticulumService?> = _service.asStateFlow()

    private val _selectedDestination = MutableStateFlow<String?>(null)
    val selectedDestination: StateFlow<String?> = _selectedDestination.asStateFlow()

    /** One-shot deep-link target pushed by [MainActivity] when a launch
     *  intent carries [ReticulumService.EXTRA_OPEN_CONTACT] (i.e. the
     *  user tapped an incoming-message notification). The ReticulumApp
     *  composable collects this and navigates the NavController to the
     *  Messages tab + selects the conversation. extraBufferCapacity=1
     *  keeps a notification-tap-before-collection-started from being
     *  dropped; replay=0 ensures rotation/recompose doesn't re-trigger. */
    private val _pendingOpenContact = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val pendingOpenContact: SharedFlow<String> = _pendingOpenContact.asSharedFlow()
    fun openContact(hash: String) { _pendingOpenContact.tryEmit(hash) }

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    /** When false (default), [displayedLog] keeps only message-activity
     *  lines (sent / delivered / received / our-proof-back) and drops
     *  protocol chatter. When true, every line in [logLines] passes through. */
    private val _verboseLog = MutableStateFlow(false)
    val verboseLog: StateFlow<Boolean> = _verboseLog.asStateFlow()
    fun setVerboseLog(value: Boolean) { _verboseLog.value = value }

    /** UI-facing log stream — applies [_verboseLog] filter. */
    val displayedLog: Flow<List<String>> =
        combine(_logLines, _verboseLog) { lines, verbose ->
            if (verbose) lines else lines.filter { isMessageEvent(it) }
        }

    private val _ourDestHash = MutableStateFlow<String?>(null)
    val ourDestHash: StateFlow<String?> = _ourDestHash.asStateFlow()

    private val _myCardJson = MutableStateFlow<String?>(null)
    val myCardJson: StateFlow<String?> = _myCardJson.asStateFlow()

    /** Filter applied on the Nodes tab. */
    enum class NodeFilter(val label: String) {
        /** User-saved contacts (favorited destinations). */
        Contacts("Contacts"),
        Messagable("Messagable"),
        All("All"),
        Telemetry("Telemetry / nodes"),
        /** RRC hubs only — shown only when the experimental RRC
         *  feature is enabled. */
        Rrc("RRC"),
    }
    private val _nodeFilter = MutableStateFlow(NodeFilter.Messagable)
    val nodeFilter: StateFlow<NodeFilter> = _nodeFilter.asStateFlow()

    /** Free-text search on the Nodes tab — matches displayName, appLabel,
     *  appName, or hash (case-insensitive substring). Empty = no filter. */
    private val _nodeSearch = MutableStateFlow("")
    val nodeSearch: StateFlow<String> = _nodeSearch.asStateFlow()

    // ---- Nomad-tab filters (v0.1.48) ------------------------------------

    enum class NomadFilter(val label: String) {
        All("All"),
        Favorites("Favorites"),
        Cached("Cached"),
    }
    private val _nomadFilter = MutableStateFlow(NomadFilter.All)
    val nomadFilter: StateFlow<NomadFilter> = _nomadFilter.asStateFlow()
    fun setNomadFilter(value: NomadFilter) { _nomadFilter.value = value }

    private val _nomadSearch = MutableStateFlow("")
    val nomadSearch: StateFlow<String> = _nomadSearch.asStateFlow()
    fun setNomadSearch(value: String) { _nomadSearch.value = value }

    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: Flow<ReticulumEngine.ConnectionState> =
        _service.flatMapLatest { svc ->
            svc?.connection ?: flowOf(ReticulumEngine.ConnectionState(TransportState.Disconnected, null))
        }

    /** Full set of attached-transport states. Settings UI iterates this
     *  to render per-section connected/disconnected indicators and the
     *  "Connected: BLE + TCP" multi-transport status line. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionStates: Flow<List<ReticulumEngine.ConnectionState>> =
        _service.flatMapLatest { svc ->
            svc?.connections ?: flowOf(emptyList())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val displayName: Flow<String> =
        _service.flatMapLatest { svc -> svc?.prefs?.displayName ?: flowOf("Reticulum Mobile") }

    /** Live stream of all destinations, sorted favorites-first then most-recent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val allDestinations: Flow<List<StoredDestination>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeDestinations() ?: flowOf(emptyList()) }

    /** Filter applied — drives the Nodes tab list. Combines the kind
     *  chip, the favorites star toggle, and the search text. */
    val filteredDestinations: Flow<List<StoredDestination>> =
        combine(allDestinations, _nodeFilter, _nodeSearch) { rows, filter, search ->
            val byKind = when (filter) {
                NodeFilter.Contacts   -> rows.filter { it.favorite }
                NodeFilter.All        -> rows
                NodeFilter.Messagable -> rows.filter { it.isMessagable || it.publicKey.isEmpty() && it.appName == null }
                    // Include manual stubs (no public key yet, no appName) so they appear while waiting for an announce.
                NodeFilter.Telemetry  -> rows.filter { it.appName != "lxmf.delivery" }
                NodeFilter.Rrc        -> rows.filter { it.appName == "rrc.hub" }
            }
            val q = search.trim()
            if (q.isEmpty()) byKind else {
                val needle = q.lowercase()
                byKind.filter { dest ->
                    dest.effectiveDisplayName.lowercase().contains(needle) ||
                    dest.displayName.lowercase().contains(needle) ||
                        (dest.appLabel?.lowercase()?.contains(needle) == true) ||
                        (dest.appName?.lowercase()?.contains(needle) == true) ||
                        dest.hash.lowercase().contains(needle)
                }
            }
        }

    /** Favorites that we can actually message — drives the Messages tab list.
     *
     *  Mirrors the favorite-star availability rule on the Nodes tab
     *  (`appName == "lxmf.delivery" || publicKey.isEmpty()`): once a row
     *  is favoritable there, it should appear here. The `publicKey.isEmpty()`
     *  branch keeps manual stubs visible while we wait for an announce —
     *  the conversation view is reachable but `sendMessage` will fail
     *  with "Unknown destination" until the public key arrives.
     *
     *  Once a non-LXMF announce arrives `publicKey.size == 64` and the
     *  empty-pubkey branch no longer matches, so favorited
     *  `nomadnetwork.node` rows (starrable from the Nomad tab in v0.1.52)
     *  drop out automatically — the v0.1.69 fix that prompted the
     *  strict-`isMessagable` filter.
     */
    val favorites: Flow<List<StoredDestination>> =
        allDestinations.map { rows ->
            rows.filter { it.favorite && (it.isMessagable || it.publicKey.isEmpty()) }
        }

    /** Senders we've received at least one incoming message from but
     *  haven't favorited. Drives the Messages-tab Inbox section. For
     *  truly unknown senders (no destination row yet — e.g. arrived
     *  via path-request flow before the announce came back) we
     *  synthesize a stub StoredDestination from the hash so the UI
     *  has something to show. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val inbox: Flow<List<StoredDestination>> =
        _service.flatMapLatest { svc ->
            if (svc == null) flowOf(emptyList())
            else kotlinx.coroutines.flow.combine(
                svc.repos.observeIncomingContactHashes(),
                svc.repos.observeDestinations(),
            ) { incomingHashes, allDests ->
                val destByHash = allDests.associateBy { it.hash }
                val favHashes = allDests.filter { it.favorite }.map { it.hash }.toSet()
                incomingHashes
                    .filter { it !in favHashes }
                    .map { hash ->
                        destByHash[hash] ?: StoredDestination(
                            hash = hash,
                            identityHash = "",
                            publicKey = ByteArray(0),
                            destHash = runCatching { hash.hexToBytes() }.getOrDefault(ByteArray(16)),
                            nameHash = ByteArray(0),
                            ratchetPub = null,
                            displayName = "(unknown sender)",
                            appName = null,
                            appLabel = null,
                            telemetry = null,
                            lat = null,
                            lon = null,
                            appDataHex = "",
                            lastSeen = 0,
                            rssi = null,
                            favorite = false,
                            source = "inbox",
                        )
                    }
            }
        }

    // ---- Messages-tab conversation list (recency sort + pins) ----------

    private val _messageSearch = MutableStateFlow("")
    val messageSearch: StateFlow<String> = _messageSearch.asStateFlow()
    fun setMessageSearch(query: String) { _messageSearch.value = query }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val lastMessageTimes: Flow<Map<String, Long>> =
        _service.flatMapLatest { svc ->
            svc?.repos?.observeLastMessageTimes() ?: flowOf(emptyMap())
        }

    /** Destination hashes pinned to the top of the Messages list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pinnedConversations: Flow<Set<String>> =
        _service.flatMapLatest { svc ->
            svc?.prefs?.pinnedConversations ?: flowOf(emptySet())
        }

    fun setPinned(hash: String, pinned: Boolean) {
        _service.value?.prefs?.setPinnedConversation(hash, pinned)
    }

    private fun stubDestination(hash: String): StoredDestination = StoredDestination(
        hash = hash,
        identityHash = "",
        publicKey = ByteArray(0),
        destHash = runCatching { hash.hexToBytes() }.getOrDefault(ByteArray(16)),
        nameHash = ByteArray(0),
        ratchetPub = null,
        displayName = "(unknown sender)",
        appName = null,
        appLabel = null,
        telemetry = null,
        lat = null,
        lon = null,
        appDataHex = "",
        lastSeen = 0,
        rssi = null,
        favorite = false,
        source = "inbox",
    )

    /** The Messages-tab conversation list: every destination with a
     *  message or the contact flag, recency-sorted with pinned on top,
     *  filtered by [messageSearch]. */
    val conversations: Flow<List<StoredDestination>> =
        combine(
            allDestinations, lastMessageTimes, pinnedConversations, _messageSearch,
        ) { dests, times, pinned, search ->
            val byHash = dests.associateBy { it.hash }
            val convHashes = (times.keys + dests.filter {
                it.favorite && (it.isMessagable || it.publicKey.isEmpty())
            }.map { it.hash }).distinct()
            val rows = convHashes.map { hash -> byHash[hash] ?: stubDestination(hash) }
            val q = search.trim().lowercase()
            val filtered = if (q.isEmpty()) rows else rows.filter {
                it.effectiveDisplayName.lowercase().contains(q) ||
                    it.hash.lowercase().contains(q)
            }
            filtered.sortedWith(
                compareByDescending<StoredDestination> { it.hash in pinned }
                    .thenByDescending { times[it.hash] ?: it.lastSeen },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesForSelected: Flow<List<StoredMessage>> =
        combine(_service, _selectedDestination) { svc, hash -> svc to hash }
            .flatMapLatest { (svc, hash) ->
                if (svc != null && hash != null) svc.repos.observeMessagesForContact(hash) else flowOf(emptyList())
            }

    /** Active event-collector job. Cancelled and replaced on every
     *  [bind] so Activity re-bindings (rotation, service restart,
     *  lifecycle bounce) don't stack collectors. Without this, every
     *  log event after N rebinds shows up N times in the diagnostics
     *  log. */
    private var eventsJob: kotlinx.coroutines.Job? = null

    fun bind(service: ReticulumService) {
        _service.value = service
        refreshOurIdentity(service)
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            service.events.collect { ev ->
                when (ev) {
                    is ReticulumEngine.EngineEvent.Log ->
                        _logLines.update { (it + ev.line).takeLast(500) }
                    is ReticulumEngine.EngineEvent.MessageReceived ->
                        _logLines.update { (it + "msg from ${ev.contactHash} verified=${ev.verified}").takeLast(500) }
                    // MessagableSeen / NodeSeen: drop from log entirely.
                    // Per-announce noise; the destination shows up on Nodes
                    // anyway, and the verbose log can be turned on if the
                    // user wants to inspect raw protocol events.
                    is ReticulumEngine.EngineEvent.MessagableSeen,
                    is ReticulumEngine.EngineEvent.NodeSeen -> Unit
                    // RRC activity drives the experimental Rooms screen's
                    // per-hub session state; it stays out of the
                    // diagnostics log (the engine logs it to logcat).
                    is ReticulumEngine.EngineEvent.RrcActivity -> handleRrcActivity(ev)
                }
            }
        }
        scheduleRrcRestore(service)
    }

    /** Tracks the once-per-app-session RRC restore so a transport
     *  drop/reconnect doesn't re-trigger it. */
    private var rrcHubsRestored = false

    /**
     * Cold-start RRC restore: once a transport is up, re-open every
     * hub that had a live session before the app was shut down. The
     * engine's existing room auto-rejoin then restores each hub's
     * joined rooms. Fires once per app session; gated on the
     * experimental RRC feature being enabled.
     */
    private fun scheduleRrcRestore(svc: ReticulumService) {
        if (rrcHubsRestored || !svc.prefs.experimentalRrc.value) return
        val hubs = svc.prefs.liveRrcHubs.value
        if (hubs.isEmpty()) {
            rrcHubsRestored = true
            return
        }
        viewModelScope.launch {
            // An RRC session needs a live link — wait for any transport
            // to reach Connected before opening hub sessions.
            svc.connections.first { conns ->
                conns.any { it.transport == TransportState.Connected }
            }
            if (rrcHubsRestored) return@launch
            rrcHubsRestored = true
            hubs.forEach { openRrcSession(it) }
        }
    }

    private fun refreshOurIdentity(service: ReticulumService) {
        viewModelScope.launch {
            runCatching { service.ourDestHash() }
                .onSuccess { _ourDestHash.value = it.toHexLower() }
                .onFailure { _logLines.update { lines -> (lines + "dest hash unavailable: ${it.message}").takeLast(500) } }
            runCatching { io.github.thatsfguy.reticulum.engine.IdentityCard.encode(service.myIdentityCard()) }
                .onSuccess { _myCardJson.value = it }
                .onFailure { _logLines.update { lines -> (lines + "my card unavailable: ${it.message}").takeLast(500) } }
        }
    }

    fun unbind() { _service.value = null }

    fun clearLog() { _logLines.value = emptyList() }

    fun selectDestination(hash: String?) { _selectedDestination.value = hash }

    fun setNodeFilter(filter: NodeFilter) { _nodeFilter.value = filter }

    fun setNodeSearch(query: String) { _nodeSearch.value = query }

    fun toggleFavorite(hash: String, favorite: Boolean) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.setFavorite(hash, favorite) }
                .onFailure { _logLines.update { lines -> (lines + "favorite fail: ${it.message}").takeLast(500) } }
        }
    }

    /** Set or clear the user's local nickname for [hash]. Empty/blank
     *  clears it and the row falls back to its announced display name. */
    fun setUserLabel(hash: String, label: String?) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.setUserLabel(hash, label) }
                .onFailure { _logLines.update { lines -> (lines + "rename fail: ${it.message}").takeLast(500) } }
        }
    }

    fun deleteDestinationAndMessages(hash: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.deleteDestinationAndMessages(hash) }
                .onFailure { _logLines.update { lines -> (lines + "delete fail: ${it.message}").takeLast(500) } }
            // Pull the user back out of the now-deleted conversation.
            if (_selectedDestination.value == hash) _selectedDestination.value = null
        }
    }

    fun deleteMessagesForDestination(hash: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.deleteMessagesForDestination(hash) }
                .onFailure { _logLines.update { lines -> (lines + "clear fail: ${it.message}").takeLast(500) } }
        }
    }

    /** Live stream of currently-known lxmf.propagation destinations,
     *  so the Settings picker can show them. Filters off the `hidden`
     *  flag automatically (handled by the underlying observe). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val propagationNodes: Flow<List<StoredDestination>> =
        allDestinations.map { rows -> rows.filter { it.appName == "lxmf.propagation" } }

    @OptIn(ExperimentalCoroutinesApi::class)
    val preferredPropagationNode: Flow<String> =
        _service.flatMapLatest { svc -> svc?.prefs?.propagationNode ?: flowOf("") }

    fun setPropagationNode(hashHex: String) {
        val svc = _service.value ?: return
        svc.prefs.setPropagationNode(hashHex)
    }

    fun syncPropagation(hashHex: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            _logLines.update { (it + "propagation: sync starting…").takeLast(500) }
            val res = runCatching { svc.syncPropagation(hashHex) }.getOrElse {
                _logLines.update { lines -> (lines + "propagation sync fail: ${it.message}").takeLast(500) }
                return@launch
            }
            val summary = buildString {
                append("propagation: ${res.tidsAdvertised} queued, ${res.messagesStored} stored")
                if (res.resourceDeferred) append(" — resource too large")
                res.errorMessage?.let { append(" — error: $it") }
            }
            _logLines.update { (it + summary).takeLast(500) }
        }
    }

    /** Auto-rank propagation nodes by (hopCount asc, lastSeen desc) and
     *  try them in order until one succeeds. The user no longer needs to
     *  pick a node manually since on a busy network the names/hashes are
     *  meaningless and the operator data isn't in the announce. */
    private val _propagationSyncing = MutableStateFlow(false)
    /** True while a propagation sync runs — drives the spinner on the
     *  Messages search bar. */
    val propagationSyncing: StateFlow<Boolean> = _propagationSyncing.asStateFlow()

    private val _propagationSyncResult = MutableStateFlow<String?>(null)
    /** Short result of the last propagation sync; auto-clears. */
    val propagationSyncResult: StateFlow<String?> = _propagationSyncResult.asStateFlow()

    private var syncResultClearJob: kotlinx.coroutines.Job? = null

    fun syncPropagationAuto() {
        val svc = _service.value ?: return
        if (_propagationSyncing.value) return  // ignore re-taps mid-sync
        viewModelScope.launch {
            _propagationSyncing.value = true
            _propagationSyncResult.value = null
            syncResultClearJob?.cancel()
            // Progress + the final tally are also emitted as engine
            // EngineEvent.Log lines, so iOS shows identical text.
            val result = runCatching { svc.syncPropagationAuto() }
            _propagationSyncing.value = false
            _propagationSyncResult.value = result.fold(
                onSuccess = { res ->
                    when {
                        res.errorMessage != null -> "Sync failed: ${res.errorMessage}"
                        res.messagesStored > 0 ->
                            "Synced — ${res.messagesStored} new message" +
                                if (res.messagesStored == 1) "" else "s"
                        else -> "Synced — nothing new"
                    }
                },
                onFailure = {
                    _logLines.update { lines -> (lines + "propagation sync fail: ${it.message}").takeLast(500) }
                    "Sync failed"
                },
            )
            syncResultClearJob = viewModelScope.launch {
                delay(6000)
                _propagationSyncResult.value = null
            }
        }
    }

    fun addManualDestination(hashHex: String, label: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.addManualDestination(hashHex, label) }
                .onFailure { _logLines.update { lines -> (lines + "manual add fail: ${it.message}").takeLast(500) } }
        }
    }

    /**
     * Resolve [hashHex] to a [StoredDestination]. If the announce-derived
     * record is already in the repo (publicKey populated), return it. If
     * we know nothing about this hash yet, insert a manual stub + kick a
     * path request (fire-and-forget) and return the stub. The caller can
     * then drive [fetchNomadPageNow] which will internally re-prime the
     * path inside [io.github.thatsfguy.reticulum.engine.ReticulumEngine.fetchNomadPage].
     *
     * Used by the Nomad browser's cross-node link follow path
     * (v0.1.56) — without it a `<32hex>:/page/foo.mu` link to a hash
     * we've never seen an announce from would fail with "Unknown
     * destination" instead of attempting to discover it.
     */
    suspend fun resolveOrPrepareDestination(hashHex: String): StoredDestination? {
        val svc = _service.value ?: return null
        val existing = runCatching { svc.repos.destinations.get(hashHex) }.getOrNull()
        if (existing != null && existing.publicKey.size == 64) return existing
        val stub = runCatching { svc.addManualDestination(hashHex, "(via cross-node link)") }
            .onFailure { _logLines.update { lines -> (lines + "manual add fail: ${it.message}").takeLast(500) } }
            .getOrNull() ?: return null
        // Fire-and-forget path request — fetchNomadPage will re-prime
        // before LINKREQ anyway, but this lets the path reply arrive
        // while the user is still tapping through the UI.
        runCatching { svc.requestPath(hashHex) }
        return stub
    }

    fun applyScannedQr(json: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.applyIdentityCardJson(json) }
                .onFailure { _logLines.update { lines -> (lines + "qr apply fail: ${it.message}").takeLast(500) } }
        }
    }

    fun sendMessage(
        content: String,
        imageBytes: ByteArray? = null,
        replyToMessageId: String? = null,
    ) {
        val svc = _service.value ?: return
        val destHash = _selectedDestination.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.sendMessage(destHash, content, imageBytes, replyToMessageId)
            }.onFailure { _logLines.update { lines -> (lines + "send fail: ${it.message}").takeLast(500) } }
        }
    }

    fun sendReaction(destinationHash: String, targetMessageId: String, emoji: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.sendReaction(destinationHash, targetMessageId, emoji) }
                .onFailure {
                    _logLines.update { lines ->
                        (lines + "reaction fail: ${it.message}").takeLast(500)
                    }
                }
        }
    }

    fun announce() {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.sendAnnounce() }
                .onFailure { _logLines.update { lines -> (lines + "announce fail: ${it.message}").takeLast(500) } }
        }
    }

    fun setDisplayName(name: String) {
        val svc = _service.value ?: return
        svc.setDisplayName(name)
        // Refresh card JSON so the QR re-renders with the new name.
        refreshOurIdentity(svc)
    }

    fun resetIdentity() {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.resetIdentity()
                refreshOurIdentity(svc)
            }.onFailure {
                _logLines.update { lines -> (lines + "reset fail: ${it.message}").takeLast(500) }
            }
        }
    }

    /**
     * Export the device's identity into a passphrase-encrypted archive
     * blob. Suspending so callers (the file-save SAF launcher) can
     * directly receive the bytes; failures (empty passphrase, missing
     * service) come back as a [Result.failure].
     */
    suspend fun exportIdentityArchive(passphrase: String): Result<ByteArray> {
        val svc = _service.value
            ?: return Result.failure(IllegalStateException("Service not bound"))
        // The archive KDF is deliberately slow and CPU-bound — run it off
        // the main thread so the UI stays responsive (no freeze / ANR).
        return withContext(Dispatchers.Default) {
            runCatching { svc.exportIdentity(passphrase) }
        }.onFailure { _logLines.update { lines -> (lines + "export fail: ${it.message}").takeLast(500) } }
    }

    /**
     * Replace the device's identity with one decrypted from [bytes]
     * using [passphrase]. Tears down active link sessions inside the
     * engine; callers should refresh the displayed identity hash
     * afterwards via [refreshOurIdentity] (the engine emits a log line
     * either way). Returns failure on wrong passphrase or malformed
     * archive — engine state is unchanged in that case.
     */
    suspend fun importIdentityArchive(bytes: ByteArray, passphrase: String): Result<Unit> {
        val svc = _service.value
            ?: return Result.failure(IllegalStateException("Service not bound"))
        // KDF decryption is slow + CPU-bound — keep it off the main thread.
        return withContext(Dispatchers.Default) {
            runCatching {
                svc.importIdentity(bytes, passphrase)
                refreshOurIdentity(svc)
            }
        }.onFailure { _logLines.update { lines -> (lines + "import fail: ${it.message}").takeLast(500) } }
    }

    /**
     * Initiate a NomadNet page fetch and forward the result via [onResult].
     * Performed off the UI thread; the callback is invoked on the
     * viewModelScope coroutine.
     */
    fun fetchNomadPage(
        destinationHash: String,
        path: String = "/page/index.mu",
        onResult: (Result<String>) -> Unit,
    ) {
        val svc = _service.value
        if (svc == null) {
            onResult(Result.failure(IllegalStateException("service not bound")))
            return
        }
        viewModelScope.launch {
            val result = runCatching { svc.fetchNomadPage(destinationHash, path) }
                .getOrElse { Result.failure(it) }
            onResult(result)
        }
    }

    /** Suspend variant — for callers that drive fetches from a
     *  [LaunchedEffect] so rapid tab/node switches cancel the in-flight
     *  fetch cleanly. [data] is the optional structured request payload
     *  for NomadNet POSTs (`Map<String, String>` of `field_…` / `var_…`
     *  entries) or `null` for a plain GET. The engine msgpack-encodes
     *  the whole envelope once — callers must NOT pre-encode. */
    suspend fun fetchNomadPageNow(
        destinationHash: String,
        path: String = "/page/index.mu",
        data: Any? = null,
        identify: Boolean = false,
    ): Result<String> {
        val svc = _service.value
            ?: return Result.failure(IllegalStateException("service not bound"))
        return runCatching { svc.fetchNomadPage(destinationHash, path, data, identify) }
            .getOrElse { Result.failure(it) }
    }

    /** Suspending /file/ download. Returns the file bytes + the
     *  server-supplied filename (extracted from the §10.2 step 1
     *  metadata prefix). Caller routes the bytes to Android's SAF
     *  via ActivityResultContracts.CreateDocument. */
    suspend fun fetchNomadFileNow(
        destinationHash: String,
        path: String,
        identify: Boolean = false,
    ): Result<io.github.thatsfguy.reticulum.engine.ReticulumEngine.DownloadedFile> {
        val svc = _service.value
            ?: return Result.failure(IllegalStateException("service not bound"))
        return runCatching { svc.fetchNomadFile(destinationHash, path, identify) }
            .getOrElse { Result.failure(it) }
    }

    // ---- Nomad page cache (v0.1.48) -------------------------------------

    /** destHashes that have at least one cached page — drives the
     *  Nomad-list "cached" indicator dot and the Cached filter chip. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cachedNomadDestHashes: Flow<Set<String>> =
        _service.flatMapLatest { svc ->
            svc?.repos?.observeCachedNomadDestHashes()?.map { it.toSet() } ?: flowOf(emptySet())
        }

    /** Suspend variant — call from inside a LaunchedEffect so cache loads
     *  are bound to the current selection's coroutine and cancel cleanly
     *  on rapid taps. Returns null if service isn't bound or cache misses. */
    suspend fun loadCachedNomadPageNow(
        destinationHash: String,
        path: String,
    ): io.github.thatsfguy.reticulum.store.StoredNomadPage? {
        val svc = _service.value ?: return null
        return runCatching { svc.repos.nomadPageCache.get(destinationHash, path) }.getOrNull()
    }

    fun clearNomadPageCache(destinationHash: String, path: String, onDone: () -> Unit = {}) {
        val svc = _service.value ?: run { onDone(); return }
        viewModelScope.launch {
            runCatching { svc.repos.nomadPageCache.clear(destinationHash, path) }
            onDone()
        }
    }

    /** Toggle favorite for a NomadNet node — same flag as the Nodes tab. */
    fun setDestinationFavorite(hash: String, favorite: Boolean) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.repos.destinations.setFavorite(hash, favorite) }
        }
    }

    // ---- Reticulum Relay Chat (RRC) — experimental ----------------------
    // The Rooms screen reads hub / room / message history from the repo
    // Flows below and drives the live link through the action methods.
    // [rrcHubStates] is the volatile per-hub session state, rebuilt from
    // the [EngineEvent.RrcActivity] stream — it is NOT persisted.

    /** Volatile UI state for one open (or attempted) RRC hub session. */
    data class RrcHubState(
        /** True between an [openRrcSession] call and WELCOME / failure. */
        val connecting: Boolean = false,
        /** Protocol lifecycle of the live session; null = no session. */
        val state: RrcState? = null,
        /** Hub display name from WELCOME, once it arrives. */
        val hubName: String? = null,
        /** Hub-advertised max message body bytes — compose-box validation. */
        val maxMsgBodyBytes: Int? = null,
        /** Most recent hub ERROR / NOTICE text, for a transient banner. */
        val lastNotice: String? = null,
        /** Per-room topic / modes, parsed from the hub's structured
         *  NOTICEs (§3 / §4). Volatile — the hub re-announces on JOIN. */
        val roomMeta: Map<String, RrcRoomMeta> = emptyMap(),
        /** Most recent `/list` result — null until a browse-rooms
         *  request's reply lands; drives the browse-rooms dialog. */
        val availableRooms: List<RrcRoomListing>? = null,
    ) {
        val welcomed: Boolean get() = state == RrcState.WELCOMED
    }

    /** A room's topic + mode string, surfaced from hub NOTICEs. */
    data class RrcRoomMeta(
        val topic: String? = null,
        /** Mode string like `+int`, or "" when the room has no modes. */
        val modes: String = "",
    )

    private val _rrcHubStates = MutableStateFlow<Map<String, RrcHubState>>(emptyMap())
    /** Per-hub live session state, keyed by hub destination hash. */
    val rrcHubStates: StateFlow<Map<String, RrcHubState>> = _rrcHubStates.asStateFlow()

    /** True when the experimental RRC feature is enabled in Settings. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val experimentalRrc: Flow<Boolean> =
        _service.flatMapLatest { svc -> svc?.prefs?.experimentalRrc ?: flowOf(false) }

    /** Whether the NomadNet browser is enabled — gates the Nomad tab. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nomadEnabled: Flow<Boolean> =
        _service.flatMapLatest { svc -> svc?.prefs?.nomadEnabled ?: flowOf(false) }

    /** UI theme preference ("system" | "light" | "dark") — drives the
     *  ReticulumTheme wrapper in MainActivity. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val themePreference: Flow<String> =
        _service.flatMapLatest { svc -> svc?.prefs?.themePreference ?: flowOf("system") }

    /** All known RRC hubs, most-recently-connected first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rrcHubs: Flow<List<StoredRrcHub>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeRrcHubs() ?: flowOf(emptyList()) }

    /** Rooms known for [hubHash]. Wrap the call in `remember(hubHash)` at
     *  the call site so a recomposition doesn't re-subscribe needlessly. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rrcRooms(hubHash: String): Flow<List<StoredRrcRoom>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeRrcRooms(hubHash) ?: flowOf(emptyList()) }

    /** Message history for one room. `remember(hubHash, room)` at the call site. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rrcMessages(hubHash: String, room: String): Flow<List<StoredRrcMessage>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeRrcMessages(hubHash, room) ?: flowOf(emptyList()) }

    /** Fold one RrcActivity event into [_rrcHubStates]. */
    private fun handleRrcActivity(ev: ReticulumEngine.EngineEvent.RrcActivity) {
        val hub = ev.hubDestHash
        _rrcHubStates.update { map ->
            val cur = map[hub] ?: RrcHubState()
            val next = when (val e = ev.event) {
                is RrcEvent.StateChanged -> cur.copy(
                    state = e.state,
                    // Any non-CONNECTING transition resolves the spinner.
                    connecting = cur.connecting && e.state == RrcState.CONNECTING,
                )
                is RrcEvent.Welcomed -> cur.copy(
                    state = RrcState.WELCOMED,
                    connecting = false,
                    hubName = e.hubName,
                    maxMsgBodyBytes = e.limits.maxMsgBodyBytes,
                )
                is RrcEvent.HubError -> cur.copy(
                    lastNotice = "Error${e.room?.let { " in $it" } ?: ""}: ${e.text}",
                )
                is RrcEvent.Notice -> cur.copy(lastNotice = e.text)
                is RrcEvent.RoomTopic -> cur.copy(
                    roomMeta = cur.roomMeta + (e.room to
                        (cur.roomMeta[e.room] ?: RrcRoomMeta()).copy(topic = e.topic)),
                )
                is RrcEvent.RoomModes -> cur.copy(
                    roomMeta = cur.roomMeta + (e.room to
                        (cur.roomMeta[e.room] ?: RrcRoomMeta()).copy(modes = e.modes)),
                )
                is RrcEvent.RoomList -> cur.copy(availableRooms = e.rooms)
                // Joined/Parted membership, RoomMessage history and
                // RoomSystemMessage `/`-command lines are persisted by the
                // engine and observed via the repo Flows.
                is RrcEvent.Joined, is RrcEvent.Parted,
                is RrcEvent.RoomMessage, is RrcEvent.RoomSystemMessage -> cur
            }
            map + (hub to next)
        }
    }

    private fun rrcNotice(hubHash: String, text: String) {
        _rrcHubStates.update { map ->
            val cur = map[hubHash] ?: RrcHubState()
            map + (hubHash to cur.copy(lastNotice = text))
        }
    }

    /** Clear the transient notice banner for [hubHash]. */
    fun clearRrcNotice(hubHash: String) {
        _rrcHubStates.update { map ->
            val cur = map[hubHash] ?: return@update map
            map + (hubHash to cur.copy(lastNotice = null))
        }
    }

    /** Add (or update) a hub row. Connecting happens later via [openRrcSession]. */
    fun addRrcHub(destHash: String, displayName: String, nick: String?) {
        val svc = _service.value ?: return
        val hash = destHash.trim().lowercase()
        if (hash.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                svc.repos.rrc.upsertHub(
                    StoredRrcHub(
                        destHash = hash,
                        displayName = displayName.trim().ifBlank { hash.take(8) },
                        nick = nick?.trim()?.ifBlank { null },
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { _logLines.update { l -> (l + "rrc add hub fail: ${it.message}").takeLast(500) } }
        }
    }

    /** Open a live RRC session to [hubHash]. Progress is reflected in
     *  [rrcHubStates]; a connect failure lands in that hub's `lastNotice`. */
    fun openRrcSession(hubHash: String) {
        val svc = _service.value ?: return
        // Remember this hub has a live session so a cold start re-opens
        // it once a transport is back up.
        svc.prefs.addLiveRrcHub(hubHash)
        _rrcHubStates.update { map ->
            val cur = map[hubHash] ?: RrcHubState()
            map + (hubHash to cur.copy(connecting = true, lastNotice = null))
        }
        viewModelScope.launch {
            val nick = runCatching { svc.repos.rrc.getHub(hubHash)?.nick }.getOrNull()
            svc.openRrcSession(hubHash, nick).onFailure { err ->
                _rrcHubStates.update { map ->
                    val cur = map[hubHash] ?: RrcHubState()
                    map + (hubHash to cur.copy(connecting = false, lastNotice = err.message ?: "connect failed"))
                }
            }
        }
    }

    /** Tear down the live session for [hubHash]. */
    fun closeRrcSession(hubHash: String) {
        val svc = _service.value ?: return
        // Explicit close — forget the hub so a relaunch doesn't re-open it.
        svc.prefs.removeLiveRrcHub(hubHash)
        viewModelScope.launch {
            runCatching { svc.closeRrcSession(hubHash) }
            _rrcHubStates.update { map ->
                val cur = map[hubHash] ?: RrcHubState()
                map + (hubHash to cur.copy(connecting = false, state = RrcState.CLOSED))
            }
        }
    }

    fun joinRrcRoom(hubHash: String, room: String, key: String? = null) {
        val svc = _service.value ?: return
        val name = room.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            runCatching { svc.joinRrcRoom(hubHash, name, key?.trim()?.ifBlank { null }) }
                .onFailure { rrcNotice(hubHash, "join failed: ${it.message}") }
        }
    }

    fun partRrcRoom(hubHash: String, room: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.partRrcRoom(hubHash, room) }
                .onFailure { rrcNotice(hubHash, "leave failed: ${it.message}") }
        }
    }

    /** Remove [room] from local storage (row + cached messages). Parts
     *  it on the hub first when a session is live. Housekeeping — works
     *  whether or not the hub is connected. */
    fun deleteRrcRoom(hubHash: String, room: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.deleteRrcRoom(hubHash, room) }
                .onFailure { rrcNotice(hubHash, "remove failed: ${it.message}") }
        }
    }

    /** Send `/list` to the hub; the reply populates
     *  [RrcHubState.availableRooms], which drives the browse-rooms dialog. */
    fun browseRrcRooms(hubHash: String) {
        val svc = _service.value ?: return
        // Clear any stale result so the dialog shows a spinner until the
        // fresh /list reply lands.
        _rrcHubStates.update { map ->
            val cur = map[hubHash] ?: RrcHubState()
            map + (hubHash to cur.copy(availableRooms = null))
        }
        viewModelScope.launch {
            runCatching { svc.browseRrcRooms(hubHash) }
                .onFailure { rrcNotice(hubHash, "room list failed: ${it.message}") }
        }
    }

    /** Change the stored RRC nick (username) for [hubHash]. Persisted
     *  immediately; takes effect on the next connect to the hub. */
    fun setRrcHubNick(hubHash: String, nick: String?) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.setRrcHubNick(hubHash, nick?.trim()?.ifBlank { null }) }
                .onFailure { rrcNotice(hubHash, "nick change failed: ${it.message}") }
        }
    }

    fun sendRrcMessage(hubHash: String, room: String, text: String) {
        val svc = _service.value ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            runCatching { svc.sendRrcMessage(hubHash, room, body) }
                .onFailure { rrcNotice(hubHash, "send failed: ${it.message}") }
        }
    }

    /** One-shot signal that the UI should switch to the Rooms tab.
     *  Emitted by [addRrcHubFromNode] when the user promotes a hub
     *  discovered on the Nodes tab; [MainActivity] collects it and
     *  navigates the NavController. extraBufferCapacity=1 keeps a
     *  tap-before-collection from being dropped. */
    private val _pendingShowRooms = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val pendingShowRooms: SharedFlow<Unit> = _pendingShowRooms.asSharedFlow()

    /** Promote a destination discovered on the Nodes tab (an `rrc.hub`
     *  announce) into the RRC hub list, then ask the UI to open the
     *  Rooms tab. An existing hub row is left untouched so a
     *  user-set nick / addedAt is not clobbered. */
    fun addRrcHubFromNode(dest: StoredDestination) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val existing = runCatching { svc.repos.rrc.getHub(dest.hash) }.getOrNull()
            if (existing == null) {
                runCatching {
                    svc.repos.rrc.upsertHub(
                        StoredRrcHub(
                            destHash = dest.hash,
                            displayName = dest.effectiveDisplayName.ifBlank { dest.hash.take(8) },
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                }.onFailure { _logLines.update { l -> (l + "rrc add hub fail: ${it.message}").takeLast(500) } }
            }
            _pendingShowRooms.tryEmit(Unit)
        }
    }

    /** Delete a hub, its rooms, and its message history; closes any live session. */
    fun deleteRrcHub(hubHash: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.closeRrcSession(hubHash) }
            runCatching { svc.repos.rrc.deleteHub(hubHash) }
                .onFailure { _logLines.update { l -> (l + "rrc delete hub fail: ${it.message}").takeLast(500) } }
            _rrcHubStates.update { it - hubHash }
        }
    }
}

private fun ByteArray.toHexLower(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * Predicate for the message-only diagnostics view. Keeps lines that are
 * directly tied to a sent / received / acknowledged LXMF message, plus
 * connection-state and explicit error lines that the user wants to know
 * about. Drops protocol chatter (announces, dedups, throttles, path
 * requests, mismatched proofs, etc.).
 */
private fun isMessageEvent(line: String): Boolean {
    // Allowlist substrings — match anywhere in the line.
    val keep = listOf(
        "msg #",            // every progressive state on a send (path?, sending, retry, ✓/✗)
        "→ encrypting",     // ratchet / long-term key choice + peer freshness
        "→ data ",          // outgoing packet bytes (paired with msg #N line)
        "✓ delivered",      // their proof arrived
        "→ proof for",      // we acked their incoming
        "msg from ",        // we received and stored
        "→ LRPROOF",        // peer opened a link to us
        "link → ",          // we opened a link
        "propagation:",     // /get sync events from ViewModel
        "[prop ",           // engine-side propagation log
        "send fail",
        "delete",
        "clear messages",
        "identity reset",
        "decrypt fail",
        "LINKREQUEST rejected",
        "LRPROOF rejected",
        "manual destination",
        "destination from QR",
        // Transport lifecycle — small volume, high value when something
        // is broken. Without these the user can't tell why a send is
        // hanging vs why the connection itself is sick.
        "TCP:",
        "BLE:",
        "RNode:",
        "transport error",
    )
    return keep.any { line.contains(it) }
}
