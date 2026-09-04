package io.github.thatsfguy.reticulum.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.android.storage.IncomingUnread
import io.github.thatsfguy.reticulum.android.storage.UnreadTally
import io.github.thatsfguy.reticulum.android.storage.rrcRoomKey
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.engine.RrcEvent
import io.github.thatsfguy.reticulum.engine.RrcState
import io.github.thatsfguy.reticulum.rrc.RrcMember
import io.github.thatsfguy.reticulum.rrc.RrcRoomListing
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.rrc.RrcRoomLink
import io.github.thatsfguy.reticulum.store.StoredRrcHub
import io.github.thatsfguy.reticulum.store.StoredRrcMessage
import io.github.thatsfguy.reticulum.store.StoredRrcRoom
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.hexToBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.receiveAsFlow
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
     *  Messages tab + selects the conversation.
     *
     *  Backed by a Channel rather than a SharedFlow because cold-start
     *  ordering puts the publish (handleDeepLink in onCreate) BEFORE the
     *  subscribe (LaunchedEffect after the first composition). A
     *  MutableSharedFlow with replay=0 silently drops that emission —
     *  late subscribers never see buffered values regardless of
     *  extraBufferCapacity. A Channel queues every send and hands it to
     *  the first collector to subscribe, fixing the "notification opens
     *  the app but not the conversation" cold-start bug. */
    private val _pendingOpenContact = Channel<String>(capacity = Channel.UNLIMITED)
    val pendingOpenContact: Flow<String> = _pendingOpenContact.receiveAsFlow()
    fun openContact(hash: String) { _pendingOpenContact.trySend(hash) }

    /** A file Uri picked via MainActivity's Activity-level file-pick
     *  launcher. The conversation composer collects this and reads the
     *  bytes — routing through the ViewModel (which outlives the Activity)
     *  keeps the result alive across the recreation the file picker can
     *  trigger, which the Compose-remembered launcher did not survive. */
    private val _pickedFileUri = Channel<android.net.Uri?>(capacity = Channel.CONFLATED)
    val pickedFileUri: Flow<android.net.Uri?> = _pickedFileUri.receiveAsFlow()
    fun onFilePicked(uri: android.net.Uri?) { _pickedFileUri.trySend(uri) }

    /** Bytes staged for the next "save attachment" SAF write, carried
     *  across the CreateDocument interaction by the ViewModel (which
     *  outlives the Activity) so an Activity recreation during the picker
     *  can't lose them — the 0-byte-save bug. Set by MainActivity.saveFile,
     *  consumed by its CreateDocument result. */
    private var stagedSaveBytes: ByteArray? = null
    fun stageSave(bytes: ByteArray) { stagedSaveBytes = bytes }
    fun takeStagedSave(): ByteArray? { val b = stagedSaveBytes; stagedSaveBytes = null; return b }

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    /** Outbound Resource (image / file attachment) delivery progress
     *  keyed by [io.github.thatsfguy.reticulum.store.StoredMessage.id].
     *  Updated from [ReticulumEngine.EngineEvent.ResourceProgress] —
     *  percent monotonic 0..100 (dropped from the map at 100 = confirmed
     *  delivery; last partial value survives a timeout), plus first-
     *  time-served bytes / advertised total so the bubble renders
     *  `↑ 47% · 215 B/s · ~1.3 min` while a row is still in
     *  `sending`/`queued`. */
    private val _outboundResourceProgress = MutableStateFlow<Map<Long, TransferProgress>>(emptyMap())
    val outboundResourceProgress: StateFlow<Map<Long, TransferProgress>> = _outboundResourceProgress.asStateFlow()

    /** Inbound Resource transfer progress keyed by the sending contact's
     *  destination-hash hex ([ReticulumEngine.EngineEvent.InboundResourceProgress]
     *  `contactHash`). Lets the conversation show "receiving… N%" while
     *  a LoRa image lands (minutes of dead air otherwise). Entries drop
     *  at 100% — the assembled message reaches the inbox via the normal
     *  path moments later. Events with a null contactHash (peer never
     *  LINKIDENTIFY'd) are unattributable to a conversation and skipped. */
    private val _inboundResourceProgress = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val inboundResourceProgress: StateFlow<Map<String, TransferProgress>> = _inboundResourceProgress.asStateFlow()

    // Surfaces QR-import rejections (SPEC §4.5 destHash↔publicKey binding
    // failures from `applyIdentityCard`) to the Nodes screen as a modal
    // alert — without this, a forged-card refusal only landed in
    // `_logLines`, so the user thought the scan silently succeeded.
    // NodesScreen observes this; the AlertDialog calls `clearQrImportError`
    // on dismiss.
    private val _lastQrImportError = MutableStateFlow<String?>(null)
    val lastQrImportError: StateFlow<String?> = _lastQrImportError.asStateFlow()
    fun clearQrImportError() { _lastQrImportError.value = null }

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
    /**
     * The Nomad browser's navigation state. Owned here rather than
     * `remember`ed in `NomadScreen`, because the NavHost disposes that
     * screen's composition on a tab swap and plain `remember` does not
     * survive it — see [NomadSession] for the full account.
     */
    val nomadSession = NomadSession()

    /** Tear down the cached NomadNet link to [hashHex] — called when a
     *  browser tab holding it is closed. */
    fun closeNomadLink(hashHex: String) {
        val svc = _service.value ?: return
        viewModelScope.launch { runCatching { svc.closeNomadLink(hashHex) } }
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

    /** True while this device's identity private keys are stored UNENCRYPTED
     *  (the Keystore vault refused to seal on this device). Drives the
     *  Settings security-warning banner; emits false again automatically once
     *  the row migrates into the Keystore-sealed columns. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val keysStoredPlaintext: Flow<Boolean> =
        _service.flatMapLatest { svc -> svc?.repos?.observeKeysStoredPlaintext() ?: flowOf(false) }

    /** Live stream of all destinations, sorted favorites-first then most-recent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val allDestinations: Flow<List<StoredDestination>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeDestinations() ?: flowOf(emptyList()) }

    /** Destinations we've received a message from, resolved from their
     *  preserved rows even when they've fallen out of [allDestinations]'s
     *  top-1000 recency window. Merged into the inbox + conversation list so a
     *  conversation partner never shows as "(unknown sender)" on a busy mesh. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val incomingSenderDestinations: Flow<List<StoredDestination>> =
        _service.flatMapLatest { svc ->
            svc?.repos?.observeIncomingSenderDestinations() ?: flowOf(emptyList())
        }

    /** Every announced `rrc.hub` destination, added or not — asked of
     *  the database directly rather than filtered out of
     *  [allDestinations].
     *
     *  That Flow is the 2500 most recently seen rows, which on a busy
     *  mesh is a window of TIME (~44 new rows a minute when measured
     *  2026-08-30, so the old 1000-row form covered 22 minutes). Hubs
     *  announce about hourly, so filtering it showed a hub for a few
     *  minutes after each announce and then dropped it — the "it popped
     *  in ~2 hours later" report. Hubs are a bounded set (29 against
     *  2677 destinations) and are exempt from eviction, so they can be
     *  queried whole. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val announcedRrcHubs: Flow<List<StoredDestination>> =
        _service.flatMapLatest { svc ->
            svc?.repos?.observeDestinationsByAppName("rrc.hub") ?: flowOf(emptyList())
        }

    /** Filter applied — drives the Nodes tab list. Combines the kind
     *  chip, the favorites star toggle, and the search text. */
    val filteredDestinations: Flow<List<StoredDestination>> =
        combine(allDestinations, announcedRrcHubs, _nodeFilter, _nodeSearch) { rows, hubs, filter, search ->
            val byKind = when (filter) {
                NodeFilter.Contacts   -> rows.filter { it.favorite }
                NodeFilter.All        -> rows
                NodeFilter.Messagable -> rows.filter { it.isMessagable || it.publicKey.isEmpty() && it.appName == null }
                    // Include manual stubs (no public key yet, no appName) so they appear while waiting for an announce.
                NodeFilter.Telemetry  -> rows.filter { it.appName != "lxmf.delivery" }
                // Uncapped, from its own query — filtering `rows` would
                // inherit that Flow's recency window and hide hubs that
                // announce less often than the mesh churns. See
                // [announcedRrcHubs].
                NodeFilter.Rrc        -> hubs
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
                // Message-senders resolved from their preserved rows even when
                // they've dropped out of the top-1000 recency window, so their
                // name never degrades to "(unknown sender)" on a busy mesh.
                svc.repos.observeIncomingSenderDestinations(),
            ) { incomingHashes, allDests, senderDests ->
                // senderDests fill any gaps left by observeDestinations' cap;
                // same rows where they overlap, so last-wins is harmless.
                val destByHash = (allDests + senderDests).associateBy { it.hash }
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

    /** Per-contact incoming messages, reduced to (id, timestamp).
     *  Joined with the read markers below to derive [unreadCounts]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val incomingUnreadRows: Flow<Map<String, List<IncomingUnread>>> =
        _service.flatMapLatest { svc ->
            svc?.repos?.observeIncomingUnreadRows() ?: flowOf(emptyMap())
        }

    /** Per-contact read marker — the highest incoming `messages.id` the
     *  user has seen. The current generation of the marker; see
     *  [unreadCounts] for why it replaced the timestamp one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lastReadMessageIds: Flow<Map<String, Long>> =
        _service.flatMapLatest { svc ->
            svc?.prefs?.lastReadMessageIds ?: flowOf(emptyMap())
        }

    /** Per-contact "last read" wall-clock ms — updated whenever the
     *  user opens a conversation (see [selectDestination]). Sourced
     *  from the [Preferences] StateFlow so a mark-read fans out to the
     *  badge instantly without round-tripping through the DB. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lastReadTimes: Flow<Map<String, Long>> =
        _service.flatMapLatest { svc ->
            svc?.prefs?.lastReadTimes ?: flowOf(emptyMap())
        }

    /** Conversations whose unread badge has earned red: a starred
     *  contact, or one the user pinned to the top of the list. A DM is
     *  addressed to you by definition, so counting every DM as a
     *  mention would make red the normal state and drain it of meaning;
     *  this is the narrower "somebody you chose to care about".
     *
     *  Pins are read from the service rather than the [pinnedConversations]
     *  property below: property initialisers run in declaration order,
     *  and this one is needed by [unreadCounts] above it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val importantConversations: Flow<Set<String>> =
        combine(
            allDestinations,
            _service.flatMapLatest { svc -> svc?.prefs?.pinnedConversations ?: flowOf(emptySet()) },
        ) { dests, pinned ->
            dests.filter { it.favorite }.mapTo(HashSet(pinned)) { it.hash }
        }

    /**
     * contactHash → what is waiting in that conversation. Drives the
     * badge on each Messages-list row and, summed, the Messages tab.
     * Entries with nothing unread are omitted so the UI can use a
     * presence check instead of scanning for 0.
     *
     * Counted by **row id**, matching the RRC rooms: `messages.id` is a
     * local monotonic sequence, whereas the original implementation
     * compared each message's timestamp — the *sender's* clock — with
     * the moment we opened the conversation. A peer whose clock ran
     * fast could therefore leave a message that stayed unread no matter
     * how many times it was read.
     *
     * A conversation with no id marker yet falls back to the legacy
     * timestamp marker rather than counting from zero, so upgrading
     * does not invent an unread backlog out of already-read history.
     * The fallback ends for a conversation the first time it is opened.
     */
    val unreadCounts: Flow<Map<String, UnreadTally>> =
        combine(
            incomingUnreadRows,
            lastReadMessageIds,
            lastReadTimes,
            importantConversations,
        ) { incoming, readIds, readTimes, important ->
            computeUnreadTallies(incoming, readIds, readTimes, important)
        }

    /** Everything unread across every conversation — the Messages tab
     *  badge, red when any of it is from a contact or pinned thread. */
    val unreadTotal: Flow<UnreadTally> =
        unreadCounts.map { counts -> counts.values.fold(UnreadTally()) { acc, u -> acc + u } }

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
            incomingSenderDestinations,
        ) { dests, times, pinned, search, senderDests ->
            // senderDests fill gaps left by allDestinations' top-1000 cap so a
            // conversation partner outside the recency window keeps their name.
            val byHash = (dests + senderDests).associateBy { it.hash }
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
        // Build the service-independent attachment store once, from the
        // application context. Must point at the SAME directory the
        // service's store writes to (see ReticulumService.onCreate).
        if (standaloneAttachmentStore == null) {
            standaloneAttachmentStore = io.github.thatsfguy.reticulum.store.AttachmentStore(
                java.io.File(service.applicationContext.filesDir, "attachments").absolutePath,
            )
        }
        refreshOurIdentity(service)
        // A rebind (screen back on, service restarted) has to re-tell
        // the service which room, if any, the user is looking at.
        syncOpenRrcRoom()
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
                    is ReticulumEngine.EngineEvent.ResourceProgress -> {
                        _outboundResourceProgress.update { current ->
                            // Drop on completion (>=100) so the bubble
                            // falls back to plain glyph rendering.
                            if (ev.percent >= 100) return@update current - ev.messageId
                            // Monotonic across link-establish retries:
                            // tryDeliverOverLink loops up to N attempts,
                            // each fresh sendResource emits a 0% then
                            // ramps. A reset-to-0 in the UI would be
                            // misleading visual regression even though
                            // the engine is honestly starting over —
                            // keep the highest-seen percent AND byte
                            // count until either the send hits 100
                            // (clears the row) or the engine reports
                            // terminal failure (state glyph changes to
                            // ✗ and the bubble stops rendering this).
                            // startedAtMs pins to the first event that
                            // carried bytes so rate = bytes / elapsed.
                            val existing = current[ev.messageId]
                            val merged = TransferProgress(
                                percent = maxOf(ev.percent, existing?.percent ?: 0),
                                bytesTransferred = maxOf(ev.bytesSent, existing?.bytesTransferred ?: 0L),
                                totalBytes = if (ev.totalBytes > 0) ev.totalBytes else existing?.totalBytes ?: 0L,
                                startedAtMs = existing?.startedAtMs?.takeIf { it > 0L }
                                    ?: if (ev.bytesSent > 0) System.currentTimeMillis() else 0L,
                            )
                            if (merged == existing) current
                            else current + (ev.messageId to merged)
                        }
                    }
                    is ReticulumEngine.EngineEvent.InboundResourceProgress -> {
                        val key = ev.contactHash
                        if (key != null) _inboundResourceProgress.update { current ->
                            if (ev.percent >= 100) return@update current - key
                            val existing = current[key]
                            // A byte count BELOW what we've seen means a
                            // fresh transfer (or next multi-segment leg)
                            // started — restart the rate clock instead
                            // of averaging across two transfers.
                            val fresh = existing == null || ev.bytesReceived < existing.bytesTransferred
                            current + (key to TransferProgress(
                                percent = ev.percent,
                                bytesTransferred = ev.bytesReceived,
                                totalBytes = ev.totalBytes,
                                startedAtMs = if (fresh) {
                                    if (ev.bytesReceived > 0) System.currentTimeMillis() else 0L
                                } else {
                                    existing.startedAtMs.takeIf { it > 0L }
                                        ?: if (ev.bytesReceived > 0) System.currentTimeMillis() else 0L
                                },
                            ))
                        }
                    }
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

    /** Our RNS identity hash, hex — the `K_SRC` RRC attributes messages
     *  and reactions to, so the UI can tell which reaction chips are
     *  ours. Empty until the service is bound. */
    private val _ourIdentityHash = MutableStateFlow("")
    val ourIdentityHash: StateFlow<String> = _ourIdentityHash.asStateFlow()

    private fun refreshOurIdentity(service: ReticulumService) {
        viewModelScope.launch {
            runCatching { service.ourDestHash() }
                .onSuccess { _ourDestHash.value = it.toHexLower() }
            runCatching { service.ourIdentityHash() }
                .onSuccess { _ourIdentityHash.value = it.toHexLower() }
                .onFailure { _logLines.update { lines -> (lines + "dest hash unavailable: ${it.message}").takeLast(500) } }
            runCatching { io.github.thatsfguy.reticulum.engine.IdentityCard.encode(service.myIdentityCard()) }
                .onSuccess { _myCardJson.value = it }
                .onFailure { _logLines.update { lines -> (lines + "my card unavailable: ${it.message}").takeLast(500) } }
        }
    }

    fun unbind() { _service.value = null }

    fun clearLog() { _logLines.value = emptyList() }

    // Per-conversation unsent draft text (issue #23). Held in the
    // ViewModel so it survives leaving the conversation, switching tabs,
    // and backgrounding the app — instead of being lost with the
    // ConversationView's local state. Keyed by destination hash.
    private val drafts = mutableMapOf<String, String>()
    fun draftFor(hash: String): String = drafts[hash] ?: ""
    fun setDraft(hash: String, text: String) {
        if (text.isEmpty()) drafts.remove(hash) else drafts[hash] = text
    }

    fun selectDestination(hash: String?) {
        _selectedDestination.value = hash
        if (hash != null) {
            // Mark read up to the newest message that exists right now.
            // Staying read afterwards is the conversation view's job
            // (setConversationOnScreen / markConversationRead) — doing
            // it only here is what used to raise an unread pill on the
            // conversation the user was sitting in.
            markConversationRead(hash)
            // Dismiss any system notifications still posted for this
            // contact. Some OEM skins don't auto-group our message
            // notifications, so without this the user has to swipe each
            // one individually after opening the conversation.
            _service.value?.cancelMessageNotificationsFor(hash)
        }
    }

    /** The conversation a composed ConversationView is showing. */
    private var conversationOnScreen: String? = null

    /** Called by ConversationView as it enters / leaves composition. */
    fun setConversationOnScreen(hash: String?) {
        conversationOnScreen = hash
        if (hash == null) return
        _service.value?.cancelMessageNotificationsFor(hash)
        markConversationRead(hash)
    }

    /**
     * Mark [hash] read up to its newest incoming message. A no-op while
     * the UI is not in front of the user — a conversation left open in
     * a pocket must still accumulate unreads, which is the same rule
     * the RRC rooms follow.
     */
    fun markConversationRead(hash: String) {
        if (!uiVisible) return
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                val newest = svc.repos.newestIncomingId(hash)
                if (newest != null) svc.prefs.setLastReadMessageId(hash, newest)
            }
        }
    }

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
            svc.cancelMessageNotificationsFor(hash)
        }
    }

    fun deleteMessagesForDestination(hash: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.deleteMessagesForDestination(hash) }
                .onFailure { _logLines.update { lines -> (lines + "clear fail: ${it.message}").takeLast(500) } }
            svc.cancelMessageNotificationsFor(hash)
        }
    }

    /** Delete a single message locally (issue #23). Local-only — does not
     *  unsend or notify the peer. */
    fun deleteMessage(id: Long) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.repos.messages.deleteById(id) }
                .onFailure { _logLines.update { lines -> (lines + "delete fail: ${it.message}").takeLast(500) } }
        }
    }

    /** Live stream of currently-known lxmf.propagation destinations,
     *  so the Settings picker can show them. Filters off the `hidden`
     *  flag automatically (handled by the underlying observe).
     *
     *  Asked of the database directly, for the same reason
     *  [announcedRrcHubs] is: [allDestinations] is the 2500 most recently
     *  seen rows, which on a busy mesh is a window of TIME (~44 new rows a
     *  minute measured 2026-08-30), and propagation nodes announce on the
     *  order of an hour. Filtering the window therefore showed a node for
     *  a few minutes after each announce and then dropped it — reported
     *  2026-09-04 as a newly-seen, closer node never appearing in the
     *  picker. Propagation nodes are exempt from the main eviction and
     *  bounded per aspect, so they can be queried whole. The auto-picker
     *  was never affected: it ranks `destinationRepo.getAll()`, which is
     *  uncapped. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val propagationNodes: Flow<List<StoredDestination>> =
        _service.flatMapLatest { svc ->
            svc?.repos?.observeDestinationsByAppName("lxmf.propagation") ?: flowOf(emptyList())
        }

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
            // Honor the Settings → Connection → Propagation picker:
            // when the user nailed down a specific node, talk to that
            // one only. Empty pref falls back to the hop-ranked auto
            // cascade. A stale pick surfaces the engine's "Unknown
            // propagation node" error rather than silently swapping
            // strategies, so the user notices and re-picks.
            val preferred = svc.prefs.propagationNode.value
            val result = runCatching {
                if (preferred.isNotEmpty()) svc.syncPropagation(preferred) else svc.syncPropagationAuto()
            }
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

    /** Fire-and-forget [ReticulumEngine.addLinkedDestination] — a stub
     *  named provisionally by whatever pointed us at it, leaving the
     *  user's own label alone. */
    fun addLinkedDestination(hashHex: String, nameHint: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.addLinkedDestination(hashHex, nameHint) }
                .onFailure { _logLines.update { l -> (l + "linked add fail: ${it.message}").takeLast(500) } }
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
    suspend fun resolveOrPrepareDestination(
        hashHex: String,
        /** The link's own visible label, when the caller has one. It
         *  becomes the row's provisional name — the page author already
         *  wrote down what this node is, and it reads better in the
         *  Nomad list than a hash. Empty is fine; the row then shows as
         *  unnamed until an announce arrives. */
        nameHint: String = "",
    ): StoredDestination? {
        val svc = _service.value ?: return null
        val existing = runCatching { svc.repos.destinations.get(hashHex) }.getOrNull()
        if (existing != null && existing.publicKey.size == 64) return existing
        // NOT addManualDestination: its label lands in `userLabel`, which
        // outranks the announced name forever. See addLinkedDestination.
        val stub = runCatching { svc.addLinkedDestination(hashHex, nameHint) }
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
                .onFailure {
                    val msg = it.message ?: "QR import failed"
                    _logLines.update { lines -> (lines + "qr apply fail: $msg").takeLast(500) }
                    _lastQrImportError.value = msg
                }
        }
    }

    /** Service-independent [io.github.thatsfguy.reticulum.store.AttachmentStore]
     *  built once from the application context's `filesDir/attachments`
     *  — the same flat directory the service writes inbound attachments
     *  to. Cached at first [bind] and kept across [unbind] so reading a
     *  stored attachment never depends on the foreground service being
     *  bound *right now*. Without this, tapping "save" on a previously
     *  received file during an unbind window (rotation, service rebind,
     *  notification-launched activity) resolved a null store → null bytes
     *  → a 0-byte file left on the phone. The store is a stateless
     *  filesystem wrapper, so a context-built twin is interchangeable
     *  with the service's. */
    private var standaloneAttachmentStore: io.github.thatsfguy.reticulum.store.AttachmentStore? = null

    /** Off-row attachment store. The conversation bubble reads it to
     *  decode an image / load a file payload from its on-row token
     *  (docs/ATTACHMENT-STORE.md). Prefers the live service's store but
     *  falls back to [standaloneAttachmentStore] so it stays non-null
     *  through service unbind windows. */
    val attachmentStore: io.github.thatsfguy.reticulum.store.AttachmentStore?
        get() = _service.value?.attachmentStore ?: standaloneAttachmentStore

    fun sendMessage(
        content: String,
        imageBytes: ByteArray? = null,
        fileBytes: ByteArray? = null,
        fileName: String? = null,
        replyToMessageId: String? = null,
    ) {
        val svc = _service.value ?: return
        val destHash = _selectedDestination.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.sendMessage(destHash, content, imageBytes, fileBytes, fileName, replyToMessageId)
            }.onFailure { _logLines.update { lines -> (lines + "send fail: ${it.message}").takeLast(500) } }
        }
    }

    /** Send a recorded voice clip (Opus/OGG, LXMF FIELD_AUDIO) to the
     *  currently selected conversation. */
    fun sendVoiceClip(bytes: ByteArray) {
        val svc = _service.value ?: return
        val destHash = _selectedDestination.value ?: return
        viewModelScope.launch {
            runCatching { svc.sendVoiceClip(destHash, bytes) }
                .onFailure { _logLines.update { lines -> (lines + "voice send fail: ${it.message}").takeLast(500) } }
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

    /** Fire an announce on demand (Settings → Send announce, issue #31).
     *  Suspends so the button can show a spinner while it's in flight and
     *  surface an honest result: which transports it went out on, or that
     *  nothing was sent because no transport is connected. */
    suspend fun announce(): String {
        val svc = _service.value ?: return "Not connected — announce not sent"
        return runCatching { svc.sendAnnounce() }
            .fold(
                onSuccess = { kinds ->
                    if (kinds.isEmpty()) "Not connected — announce not sent"
                    else "Announce sent → ${kinds.joinToString(", ") { it.name }}"
                },
                onFailure = {
                    _logLines.update { lines -> (lines + "announce fail: ${it.message}").takeLast(500) }
                    "Announce failed: ${it.message}"
                },
            )
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
     * Export the identity as a raw (unencrypted) RNS `to_file()` blob for
     * interop with rnsd / Sideband / NomadNet (#33). No KDF — it's a plain
     * 64-byte copy. Callers MUST warn the user it's unencrypted.
     */
    suspend fun exportRnsIdentity(): Result<ByteArray> {
        val svc = _service.value
            ?: return Result.failure(IllegalStateException("Service not bound"))
        return runCatching { svc.exportRnsIdentity() }
            .onFailure { _logLines.update { lines -> (lines + "rns export fail: ${it.message}").takeLast(500) } }
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
     * Replace the device's identity with a raw RNS-format identity — the
     * 64-byte `X25519||Ed25519` private blob written by upstream RNS's
     * `Identity.to_file()` (rnsd / Sideband / NomadNet), issue #33. No
     * passphrase — the RNS file is plaintext. Same engine teardown as
     * [importIdentityArchive]; refresh the displayed hash afterwards.
     */
    suspend fun importRnsIdentity(bytes: ByteArray): Result<Unit> {
        val svc = _service.value
            ?: return Result.failure(IllegalStateException("Service not bound"))
        return withContext(Dispatchers.Default) {
            runCatching {
                svc.importRnsIdentity(bytes)
                refreshOurIdentity(svc)
            }
        }.onFailure { _logLines.update { lines -> (lines + "rns import fail: ${it.message}").takeLast(500) } }
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

    /** A room's topic, mode string and member roster, surfaced from the
     *  hub's structured NOTICEs and its JOINED / PARTED member lists. */
    data class RrcRoomMeta(
        val topic: String? = null,
        /** Mode string like `+int`, or "" when the room has no modes. */
        val modes: String = "",
        /** Member identity hashes, lower-case hex. Empty means the hub
         *  does not send member lists — never "the room is empty". */
        val members: List<String> = emptyList(),
        /** Roster WITH nicknames, from the room's last `/who` reply —
         *  the only source of nicks for `@`-completion, since a JOINED
         *  member list carries hashes only. Empty until `/who` is run. */
        val roster: List<RrcMember> = emptyList(),
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

    /** Announced `rrc.hub` destinations the user has NOT added yet —
     *  drives the "discovered hubs" one-tap add in the Rooms tab so the
     *  discovery path (a hub that has announced shows up in Nodes) is
     *  reachable from Rooms too, instead of forcing a 32-hex paste. */
    val discoverableRrcHubs: Flow<List<StoredDestination>> =
        combine(announcedRrcHubs, rrcHubs) { dests, added ->
            val have = added.mapTo(HashSet()) { it.destHash }
            dests.filter { it.hash !in have }
        }

    /** Rooms known for [hubHash]. Wrap the call in `remember(hubHash)` at
     *  the call site so a recomposition doesn't re-subscribe needlessly. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rrcRooms(hubHash: String): Flow<List<StoredRrcRoom>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeRrcRooms(hubHash) ?: flowOf(emptyList()) }

    /** Message history for one room. `remember(hubHash, room)` at the call site. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rrcMessages(hubHash: String, room: String): Flow<List<StoredRrcMessage>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeRrcMessages(hubHash, room) ?: flowOf(emptyList()) }

    // ---- Rooms-tab navigation + composer state ------------------------
    // Held HERE and not in RoomsScreen, for the same reason the Messages
    // tab holds its selected conversation and drafts in the ViewModel:
    // a bottom-nav tap takes the Rooms screen out of composition, and
    // anything remembered inside it dies with it. Before this, changing
    // tabs threw the user out of the room they were reading and silently
    // discarded whatever they had typed.

    private val _rrcSelectedHub = MutableStateFlow<String?>(null)

    /** Hub whose detail / room view is open, or null for the hub list. */
    val rrcSelectedHub: StateFlow<String?> = _rrcSelectedHub.asStateFlow()

    private val _rrcSelectedRoom = MutableStateFlow<String?>(null)

    /** Room open inside [rrcSelectedHub], or null for the hub detail. */
    val rrcSelectedRoom: StateFlow<String?> = _rrcSelectedRoom.asStateFlow()

    /** Open a hub's detail view (null returns to the hub list). Always
     *  leaves any open room — the room belongs to the previous hub. */
    fun selectRrcHub(hubHash: String?) {
        if (_rrcSelectedHub.value != hubHash) selectRrcRoom(null)
        _rrcSelectedHub.value = hubHash
    }

    /** Open [room] in the selected hub, or null to go back to its room
     *  list. */
    fun selectRrcRoom(room: String?) {
        val previous = _rrcSelectedRoom.value
        _rrcSelectedRoom.value = room
        val svc = _service.value ?: return
        val hub = _rrcSelectedHub.value ?: return
        // Leaving: whatever landed while the room was open is read.
        if (room == null && previous != null) {
            viewModelScope.launch { runCatching { svc.repos.markRrcRoomRead(hub, previous) } }
        }
    }

    // Notification suppression turns on TWO things being true: a room
    // chat is composed, and the UI is actually in front of the user.
    // The composition alone isn't enough — it survives the Activity
    // stopping, so a phone in a pocket with a room still open would
    // silently swallow every message in it.

    /** True while the Activity is started; set by MainActivity. */
    private var uiVisible: Boolean = true

    /** The room a composed RoomChatView is showing, `hub to room`. */
    private var rrcRoomOnScreen: Pair<String, String>? = null

    /** MainActivity's onStart / onStop. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        syncOpenRrcRoom()
        // Coming back to something still on screen: catch its read
        // marker up to whatever arrived while the phone was away.
        if (visible) {
            rrcRoomOnScreen?.let { (hub, room) -> markRrcRoomRead(hub, room) }
            conversationOnScreen?.let { markConversationRead(it) }
        }
    }

    /** Called by RoomChatView as it enters / leaves composition. */
    fun setRrcRoomOnScreen(hubHash: String?, room: String?) {
        rrcRoomOnScreen = if (hubHash != null && room != null) hubHash to room else null
        syncOpenRrcRoom()
        val svc = _service.value ?: return
        if (hubHash != null && room != null) {
            svc.cancelRrcNotificationsFor(hubHash, room)
            markRrcRoomRead(hubHash, room)
        }
    }

    private fun syncOpenRrcRoom() {
        _service.value?.setOpenRrcRoom(if (uiVisible) rrcRoomOnScreen else null)
    }

    /** Mark [room] read up to its newest message. A no-op while the UI
     *  is not in front of the user — messages that arrive with the app
     *  in the background are exactly the ones the badge is for. */
    fun markRrcRoomRead(hubHash: String, room: String) {
        if (!uiVisible) return
        val svc = _service.value ?: return
        viewModelScope.launch { runCatching { svc.repos.markRrcRoomRead(hubHash, room) } }
    }

    // Per-room unsent draft, same contract as the LXMF conversation
    // drafts above: survives leaving the room, switching tabs and
    // backgrounding. Keyed "hubHash/room".
    //
    // Observable rather than a plain map (which is what the LXMF side
    // uses) because the composer is not the only writer: a send the hub
    // refuses puts the text back, and that has to reach a composer that
    // is already on screen.
    private val _rrcDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val rrcDrafts: StateFlow<Map<String, String>> = _rrcDrafts.asStateFlow()

    fun rrcDraftFor(hubHash: String, room: String): String =
        _rrcDrafts.value[rrcRoomKey(hubHash, room)] ?: ""

    fun setRrcDraft(hubHash: String, room: String, text: String) {
        val key = rrcRoomKey(hubHash, room)
        _rrcDrafts.update { if (text.isEmpty()) it - key else it + (key to text) }
    }

    // ---- replies + reactions (rrc-extensions.md) ---------------------

    /** Message being replied to, per room, as `hubHash/room` → the
     *  target's `K_ID` hex. In the ViewModel for the same reason drafts
     *  are: a stray tab tap must not silently discard it. */
    private val _rrcReplyTargets = MutableStateFlow<Map<String, String>>(emptyMap())
    val rrcReplyTargets: StateFlow<Map<String, String>> = _rrcReplyTargets.asStateFlow()

    fun setRrcReplyTarget(hubHash: String, room: String, msgId: String?) {
        val key = rrcRoomKey(hubHash, room)
        _rrcReplyTargets.update { if (msgId == null) it - key else it + (key to msgId) }
    }

    /**
     * React to [targetMsgId] in [room], or remove that reaction when
     * [retract] is set. Applied locally by the engine as well as sent,
     * so the chip updates without waiting for the hub's fan-out to come
     * back over a slow link.
     */
    fun sendRrcReaction(
        hubHash: String,
        room: String,
        targetMsgId: String,
        emoji: String,
        retract: Boolean = false,
    ) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.sendRrcReaction(hubHash, room, targetMsgId, emoji, retract) }
                .onFailure { rrcNotice(hubHash, "reaction failed: ${it.message}") }
        }
    }

    /** Unread tally per room, keyed `hubHash/room`; rooms with nothing
     *  unread are absent. Drives the room- and hub-list badges. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rrcUnread: Flow<Map<String, UnreadTally>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeUnreadTally() ?: flowOf(emptyMap()) }

    /** Everything unread across every hub and room — the bottom-nav
     *  badge, which goes red only when some of it names us. */
    val rrcUnreadTotal: Flow<UnreadTally> =
        rrcUnread.map { counts -> counts.values.fold(UnreadTally()) { acc, u -> acc + u } }

    /** Set a room's notification mode ([StoredRrcRoom.NOTIFY_ALL],
     *  `NOTIFY_MENTIONS`, `NOTIFY_NONE`). */
    fun setRrcRoomNotifyMode(hubHash: String, room: String, mode: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.repos.setRrcRoomNotifyMode(hubHash, room, mode) }
                .onFailure { rrcNotice(hubHash, "notification setting failed: ${it.message}") }
        }
    }

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
                // Same state, no reconnect: this is how a ViewModel that
                // was thrown away and rebuilt (Activity recreated while
                // the service kept its links) learns what the engine
                // already has open.
                is RrcEvent.SessionResumed -> cur.copy(
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
                is RrcEvent.RoomMembers -> cur.copy(
                    roomMeta = cur.roomMeta + (e.room to
                        (cur.roomMeta[e.room] ?: RrcRoomMeta()).copy(members = e.members)),
                )
                is RrcEvent.RoomRoster -> cur.copy(
                    roomMeta = cur.roomMeta + (e.room to
                        (cur.roomMeta[e.room] ?: RrcRoomMeta()).copy(roster = e.members)),
                )
                // Joined/Parted membership, RoomMessage history,
                // RoomSystemMessage `/`-command lines and reactions
                // (folded onto their target row) are persisted by the
                // engine and observed via the repo Flows.
                is RrcEvent.Joined, is RrcEvent.Parted,
                is RrcEvent.RoomMessage, is RrcEvent.RoomSystemMessage,
                is RrcEvent.RoomReaction -> cur
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

    /** In-flight [openRrcSession] jobs, keyed by hub hash, so a connect
     *  that is taking too long can actually be abandoned — see
     *  [closeRrcSession]. A connect can sit on a hop-scaled link timeout
     *  plus a WELCOME timeout, and the user watching the spinner needs a
     *  way out that isn't force-stopping the app. */
    private val rrcConnectJobs = mutableMapOf<String, Job>()

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
        val job = viewModelScope.launch {
            val nick = runCatching { svc.repos.rrc.getHub(hubHash)?.nick }.getOrNull()
            svc.openRrcSession(hubHash, nick).onSuccess {
                // Success now means welcomed — the engine waits for the
                // hub's WELCOME before returning. Resolve the spinner
                // from the return value as well as from the event,
                // because the event may have been emitted while the
                // Activity was stopped and nobody was collecting.
                _rrcHubStates.update { map ->
                    val cur = map[hubHash] ?: RrcHubState()
                    map + (hubHash to cur.copy(connecting = false, state = RrcState.WELCOMED))
                }
            }.onFailure { err ->
                // A cancellation is the user's own Cancel: it has already
                // cleared `connecting`, and "job was cancelled" is not a
                // notice worth showing them.
                if (err is kotlinx.coroutines.CancellationException) return@onFailure
                _rrcHubStates.update { map ->
                    val cur = map[hubHash] ?: RrcHubState()
                    map + (hubHash to cur.copy(connecting = false, lastNotice = err.message ?: "connect failed"))
                }
            }
        }
        rrcConnectJobs[hubHash] = job
        job.invokeOnCompletion { if (rrcConnectJobs[hubHash] === job) rrcConnectJobs.remove(hubHash) }
    }

    /** Tear down the live session for [hubHash], or abandon a connect
     *  that hasn't finished. Both are the same user intent ("stop"), and
     *  both must leave `connecting` false: this is the only escape from
     *  a hub that is spinning, so it can never depend on the engine
     *  having a session to close. */
    fun closeRrcSession(hubHash: String) {
        val svc = _service.value ?: return
        // Explicit close — forget the hub so a relaunch doesn't re-open it.
        svc.prefs.removeLiveRrcHub(hubHash)
        rrcConnectJobs.remove(hubHash)?.cancel()
        _rrcHubStates.update { map ->
            val cur = map[hubHash] ?: RrcHubState()
            map + (hubHash to cur.copy(connecting = false, state = RrcState.CLOSED))
        }
        viewModelScope.launch { runCatching { svc.closeRrcSession(hubHash) } }
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

    /**
     * Submit one composer line — chat, an action, or a `/`-command; the
     * engine decides which (see `RrcCommands`).
     *
     * A refusal (no session, over the hub's body limit, rate limited)
     * gives the text back as the room's draft and says why *in the
     * room*, where the user is looking, rather than dropping the
     * message and flashing a banner over a different screen.
     */
    fun sendRrcMessage(hubHash: String, room: String, text: String) {
        val svc = _service.value ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val replyTo = _rrcReplyTargets.value[rrcRoomKey(hubHash, room)]
        // Cleared up front so the composer's reply banner closes with
        // the send; a failure below restores the draft, and re-aiming
        // the reply is one tap.
        if (replyTo != null) setRrcReplyTarget(hubHash, room, null)
        viewModelScope.launch {
            runCatching { svc.sendRrcMessage(hubHash, room, body, replyTo) }
                .onFailure { err ->
                    setRrcDraft(hubHash, room, text)
                    val saved = runCatching {
                        svc.repos.rrc.saveMessage(
                            StoredRrcMessage(
                                hubHash = hubHash,
                                room = room,
                                direction = "error",
                                senderIdHash = "",
                                text = "couldn't send: ${err.message ?: "unknown error"}",
                                timestamp = System.currentTimeMillis(),
                            ),
                        )
                    }
                    // No room row to write into (the failure was the
                    // room itself) — fall back to the hub banner.
                    if (saved.isFailure) rrcNotice(hubHash, "send failed: ${err.message}")
                }
        }
    }

    /**
     * Open [room] on [hubHash] from outside the Rooms tab — an RRC
     * notification tap. Selects hub + room, then asks the UI to switch
     * tabs; [pendingShowRooms] is what MainActivity navigates on.
     */
    fun openRrcRoom(hubHash: String, room: String) {
        selectRrcHub(hubHash)
        selectRrcRoom(room)
        _pendingShowRooms.tryEmit(Unit)
    }

    /**
     * The shareable text form of [room] on [hubDestHash], or null when
     * the hub hash is not a well-formed destination.
     *
     * Null is a real answer the caller must honour by hiding the share
     * affordance: `rrc-room-links.md` §2.1 says a writer that does not
     * know its own destination hash MUST emit no link rather than a
     * partial one, because "a malformed link is pasted onward as though
     * it worked".
     */
    fun roomShareLink(hubDestHash: String, room: String): String? =
        RrcRoomLink.build(hubDestHash, room)

    /**
     * Send a room link to one contact as an ordinary LXMF direct
     * message.
     *
     * Deliberately a plain text body and not a new field or message
     * type: the format is text precisely so a client that has never
     * heard of it still shows something a person can read and copy
     * (`rrc-room-links.md` §3), and inventing a wire field here would
     * throw that away.
     */
    fun shareRoomLinkTo(hubDestHash: String, room: String, contactHash: String) {
        val svc = _service.value ?: return
        val link = roomShareLink(hubDestHash, room) ?: return
        viewModelScope.launch {
            runCatching { svc.sendMessage(contactHash, link, null, null, null, null) }
                .onFailure {
                    _logLines.update { l -> (l + "room share failed: ${it.message}").takeLast(500) }
                }
        }
    }

    /**
     * Add a hub named by a bare `rrc://<hash>` link and show the Rooms
     * tab. §3: "A link with no path names a hub only" — so this adds
     * the hub and gets out of the way rather than guessing a room.
     */
    fun addRrcHubFromLink(hubDestHash: String) {
        val svc = _service.value ?: return
        val hash = hubDestHash.trim().lowercase()
        if (hash.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                if (svc.repos.rrc.getHub(hash) == null) {
                    svc.repos.rrc.upsertHub(
                        StoredRrcHub(
                            destHash = hash,
                            displayName = hash.take(8),
                            nick = null,
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }.onFailure {
                _logLines.update { l -> (l + "add hub from link failed: ${it.message}").takeLast(500) }
                return@launch
            }
            selectRrcHub(hash)
            _pendingShowRooms.tryEmit(Unit)
        }
    }

    /**
     * Open a room named by an `rrc://<hash>/<room>` link
     * (`rrc-room-links.md` §3: "connect to the hub at desthash if not
     * already connected, then JOIN the room").
     *
     * The hub is very often one we have never seen — that is the entire
     * point of sharing a link — so the row is created if missing, using
     * the same hash-prefix placeholder [addRrcHub] uses. The real name
     * arrives with WELCOME and `RrcPersistence` repairs the row then;
     * inventing one here would leave a made-up label looking
     * authoritative.
     *
     * The room row is written with `joined = true` BEFORE any network
     * work, which is what makes this work when the hub is not connected
     * yet: `joinRrcRoom` requires a live session and throws without
     * one, but the engine re-JOINs every room persisted as joined on
     * each WELCOME. So recording the intent and opening the session is
     * sufficient, and joining directly is just the fast path for a hub
     * already connected. Both are idempotent.
     */
    fun openRrcRoomFromLink(hubHash: String, room: String) {
        val svc = _service.value ?: return
        val hash = hubHash.trim().lowercase()
        val name = room.trim()
        if (hash.isEmpty() || name.isEmpty()) return
        // A link to a hub this device has never connected to goes
        // through a confirmation first (audit 2026-09-02 M3). The
        // reasoning in MessageLinks.kt — "both stay on the mesh, so a
        // tap costs nothing a peer could observe" — holds for a page
        // fetch and does not hold here. The observer IS the attacker who
        // wrote the link, the connection is attributable to them, and
        // unlike a page fetch it PERSISTS: the room row is written with
        // `joined = true`, which the WELCOME auto-rejoin acts on at
        // every launch from then on, until the user finds the hub list
        // and deletes it. A durable beacon should not be a stray tap.
        //
        // A hub already in the list is not re-confirmed — the user has
        // already made that decision, and re-asking on every shared link
        // to a hub they use daily is the kind of prompt people learn to
        // dismiss without reading.
        viewModelScope.launch {
            val known = runCatching { svc.repos.rrc.getHub(hash) }.getOrNull() != null
            if (!known) {
                _pendingRoomLink.value = PendingRoomLink(hash, name)
                return@launch
            }
            joinRoomFromLink(hash, name)
        }
    }

    /**
     * A tapped room link naming a hub this device has never connected
     * to, parked awaiting the user's answer. Held in the ViewModel
     * rather than in a screen so every surface that renders message
     * text gets the same gate — the same reason `linkify` takes its
     * callbacks without defaults.
     */
    data class PendingRoomLink(val hubHash: String, val room: String)

    private val _pendingRoomLink = MutableStateFlow<PendingRoomLink?>(null)
    val pendingRoomLink: StateFlow<PendingRoomLink?> = _pendingRoomLink.asStateFlow()

    /** User accepted the new-hub join from [pendingRoomLink]. */
    fun confirmPendingRoomLink() {
        val pending = _pendingRoomLink.value ?: return
        _pendingRoomLink.value = null
        joinRoomFromLink(pending.hubHash, pending.room)
    }

    /** User declined, or dismissed the dialog. Nothing is written. */
    fun dismissPendingRoomLink() {
        _pendingRoomLink.value = null
    }

    /** The actual join. Reached directly for a known hub, and via
     *  [confirmPendingRoomLink] for one the user just accepted. */
    private fun joinRoomFromLink(hash: String, name: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                if (svc.repos.rrc.getHub(hash) == null) {
                    svc.repos.rrc.upsertHub(
                        StoredRrcHub(
                            destHash = hash,
                            displayName = hash.take(8),
                            nick = null,
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                // Read the existing row before writing it. A bare
                // `StoredRrcRoom(hubHash, name, joined = true)` takes
                // the defaults for everything else, so tapping a link
                // to a room you are ALREADY in resets
                // `lastReadMessageId` to 0 — which means "never read",
                // i.e. the invented unread backlog the v19→v20
                // migration was written to repair in 1.2.105, re-created
                // by a link tap. `notifyMode` matters the same way: a
                // link to a room you muted must not unmute it.
                val existing = svc.repos.rrc.getRoomsForHub(hash).firstOrNull { it.name == name }
                svc.repos.rrc.upsertRoom(
                    existing?.copy(joined = true)
                        ?: StoredRrcRoom(hubHash = hash, name = name, joined = true),
                )
            }.onFailure {
                rrcNotice(hash, "could not open room from link: ${it.message}")
                return@launch
            }

            selectRrcHub(hash)
            selectRrcRoom(name)
            _pendingShowRooms.tryEmit(Unit)

            // Fast path when the session is already up; otherwise the
            // WELCOME auto-rejoin picks the room up from the row above.
            runCatching { svc.joinRrcRoom(hash, name, null) }
                .onFailure { openRrcSession(hash) }
        }
    }

    /** One-shot signal that the UI should switch to the Rooms tab.
     *  Emitted by [addRrcHubFromNode] when the user promotes a hub
     *  discovered on the Nodes tab; [MainActivity] collects it and
     *  navigates the NavController. extraBufferCapacity=1 keeps a
     *  tap-before-collection from being dropped. */
    private val _pendingShowRooms = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val pendingShowRooms: SharedFlow<Unit> = _pendingShowRooms.asSharedFlow()

    /** Deep-link target carried by [pendingShowNomadPage] — what
     *  hash + path the NomadScreen should land on. Emitted when the
     *  user taps a `<destHash>:/path` link inside an LXMF message. */
    data class NomadDeepLink(val hash: String, val path: String)

    /** Mirror of [pendingShowRooms] for the Nomad tab. Fired by
     *  [openNomadPageFromLink] when the LXMF linkifier detects and
     *  taps a cross-node link. MainActivity switches to the Nomad
     *  tab; NomadScreen observes via [pendingNomadSelection] to know
     *  which destination + path to open. */
    private val _pendingShowNomadPage = MutableSharedFlow<NomadDeepLink>(replay = 0, extraBufferCapacity = 1)
    val pendingShowNomadPage: SharedFlow<NomadDeepLink> = _pendingShowNomadPage.asSharedFlow()

    /** The most recently requested Nomad deep-link target. NomadScreen
     *  observes this on appear / on change and updates its
     *  `selected` / `currentPath` state when it changes. Null until
     *  the first link tap. Replay=1 so a tab switch that runs the
     *  NomadScreen composer right after the emit still picks it up. */
    private val _pendingNomadSelection = MutableStateFlow<NomadDeepLink?>(null)
    val pendingNomadSelection: StateFlow<NomadDeepLink?> = _pendingNomadSelection.asStateFlow()

    /** Called by the LXMF linkifier (MessagesScreen.linkify) on a
     *  user tap. Ensures the destination is in the local store
     *  (manual-stub + path-discovery for hashes we've never seen),
     *  then emits the deep-link + queues the selection for
     *  NomadScreen to consume on tab switch. */
    fun openNomadPageFromLink(hash: String, path: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val existing = runCatching {
                svc.repos.destinations.get(hash)
            }.getOrNull()
            if (existing == null) {
                runCatching {
                    svc.addLinkedDestination(hashHex = hash, nameHint = "")
                }.onFailure {
                    _logLines.update { l -> (l + "nomad link add-manual fail: ${it.message}").takeLast(500) }
                }
            }
            val target = NomadDeepLink(hash = hash, path = path)
            _pendingNomadSelection.value = target
            _pendingShowNomadPage.tryEmit(target)
        }
    }

    /** Clear the queued Nomad selection once NomadScreen has
     *  consumed it. Without this a tab switch back to Nomad would
     *  re-open the same deep-linked page. */
    fun consumePendingNomadSelection() {
        _pendingNomadSelection.value = null
    }

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
            // Land on the promoted hub's detail view, not the hub list —
            // the user picked a specific hub, so open that one.
            selectRrcHub(dest.hash)
            _pendingShowRooms.tryEmit(Unit)
        }
    }

    /** Delete a hub, its rooms, and its message history; closes any live session. */
    fun deleteRrcHub(hubHash: String) {
        val svc = _service.value ?: return
        // Forget the hub BEFORE tearing anything down, or it comes back.
        //
        // `liveRrcHubs` is the cold-start restore set: scheduleRrcRestore
        // re-opens every hub in it on the next launch, and openRrcSession
        // recreates a missing `rrc_hubs` row as it connects. So deleting
        // a hub that had a live session removed the row and left the
        // preference pointing at it -- and the next app start put it
        // straight back. Reported as "every re-install I have to delete
        // hubs again"; it actually happened on every cold start, and
        // sideloading a new APK preserves both the DB and the prefs, so
        // an update looked like the trigger.
        //
        // The slip is a name collision: this called `svc.closeRrcSession`
        // (the SERVICE method, engine teardown only), while the method
        // that forgets the hub is this class's own `closeRrcSession`.
        // Same name, two objects, and delete picked the one that does
        // not persist anything.
        svc.prefs.removeLiveRrcHub(hubHash)
        viewModelScope.launch {
            runCatching { svc.closeRrcSession(hubHash) }
            runCatching { svc.repos.rrc.deleteHub(hubHash) }
                .also { if (_rrcSelectedHub.value == hubHash) selectRrcHub(null) }
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

/**
 * One in-flight Resource transfer's UX state, both directions
 * ([ReticulumViewModel.outboundResourceProgress] keyed by message id,
 * [ReticulumViewModel.inboundResourceProgress] keyed by contact hash).
 * [startedAtMs] is the wall clock when the first byte-carrying event
 * arrived (0 = no bytes yet); the renderer derives
 * `rate = bytesTransferred / elapsed` and
 * `eta = (totalBytes - bytesTransferred) / rate` at composition time —
 * every progress event recomposes, so the readout stays fresh without
 * a ticker. At LoRa speeds the rate is the health signal (≈200 B/s at
 * SF9 = channel doing its best; sagging = transport trouble) — percent
 * alone can't show that.
 */
data class TransferProgress(
    val percent: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val startedAtMs: Long,
)

/**
 * Per-conversation unread tally — the pure half of
 * [ReticulumViewModel.unreadCounts], lifted out so it can be tested
 * without a bound service.
 *
 * [readIds] is the current read marker (highest incoming `messages.id`
 * seen). [readTimes] is the legacy timestamp marker, consulted ONLY for
 * a conversation with no id marker yet, so upgrading doesn't invent an
 * unread backlog out of already-read history. A conversation in
 * [important] — a starred contact or a pinned thread — has its unread
 * counted as mentions too, which is what turns its badge red.
 *
 * Conversations with nothing unread are omitted.
 */
internal fun computeUnreadTallies(
    incoming: Map<String, List<IncomingUnread>>,
    readIds: Map<String, Long>,
    readTimes: Map<String, Long>,
    important: Set<String>,
): Map<String, UnreadTally> = buildMap {
    for ((hash, rows) in incoming) {
        val readId = readIds[hash]
        val n = if (readId != null) {
            rows.count { it.id > readId }
        } else {
            val cutoff = readTimes[hash] ?: 0L
            rows.count { it.timestamp > cutoff }
        }
        if (n > 0) {
            put(hash, UnreadTally(total = n, mentions = if (hash in important) n else 0))
        }
    }
}
