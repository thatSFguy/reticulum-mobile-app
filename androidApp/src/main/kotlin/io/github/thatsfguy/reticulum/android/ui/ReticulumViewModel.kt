package io.github.thatsfguy.reticulum.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.transport.TransportState
import io.github.thatsfguy.reticulum.transport.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _ourDestHash = MutableStateFlow<String?>(null)
    val ourDestHash: StateFlow<String?> = _ourDestHash.asStateFlow()

    private val _myCardJson = MutableStateFlow<String?>(null)
    val myCardJson: StateFlow<String?> = _myCardJson.asStateFlow()

    /** Filter applied on the Nodes tab. */
    enum class NodeFilter(val label: String) {
        Messagable("Messagable"),
        All("All"),
        Telemetry("Telemetry / nodes"),
        Favorites("Favorites"),
    }
    private val _nodeFilter = MutableStateFlow(NodeFilter.Messagable)
    val nodeFilter: StateFlow<NodeFilter> = _nodeFilter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: Flow<ReticulumEngine.ConnectionState> =
        _service.flatMapLatest { svc ->
            svc?.connection ?: flowOf(ReticulumEngine.ConnectionState(TransportState.Disconnected, null))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val displayName: Flow<String> =
        _service.flatMapLatest { svc -> svc?.prefs?.displayName ?: flowOf("Reticulum Mobile") }

    /** Live stream of all destinations, sorted favorites-first then most-recent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val allDestinations: Flow<List<StoredDestination>> =
        _service.flatMapLatest { svc -> svc?.repos?.observeDestinations() ?: flowOf(emptyList()) }

    /** Filter applied — drives the Nodes tab list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredDestinations: Flow<List<StoredDestination>> =
        combine(allDestinations, _nodeFilter) { destinations, filter ->
            when (filter) {
                NodeFilter.All        -> destinations
                NodeFilter.Messagable -> destinations.filter { it.isMessagable || it.publicKey.isEmpty() && it.appName == null }
                    // Include manual stubs (no public key yet, no appName) so they appear while waiting for an announce.
                NodeFilter.Telemetry  -> destinations.filter { it.appName != "lxmf.delivery" }
                NodeFilter.Favorites  -> destinations.filter { it.favorite }
            }
        }

    /** Favorites that we can actually message — drives the Messages tab list. */
    val favorites: Flow<List<StoredDestination>> =
        allDestinations.map { rows -> rows.filter { it.favorite && (it.isMessagable || it.publicKey.size == 64) } }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesForSelected: Flow<List<StoredMessage>> =
        combine(_service, _selectedDestination) { svc, hash -> svc to hash }
            .flatMapLatest { (svc, hash) ->
                if (svc != null && hash != null) svc.repos.observeMessagesForContact(hash) else flowOf(emptyList())
            }

    fun bind(service: ReticulumService) {
        _service.value = service
        refreshOurIdentity(service)
        viewModelScope.launch {
            service.events.collect { ev ->
                when (ev) {
                    is ReticulumEngine.EngineEvent.Log ->
                        _logLines.update { (it + ev.line).takeLast(500) }
                    is ReticulumEngine.EngineEvent.MessagableSeen ->
                        _logLines.update { (it + "lxmf ${ev.hash} [${ev.appName ?: "?"}] ${ev.displayName}").takeLast(500) }
                    is ReticulumEngine.EngineEvent.NodeSeen ->
                        _logLines.update { (it + "node ${ev.hash} [${ev.appName ?: "?"}] ${ev.displayName}").takeLast(500) }
                    is ReticulumEngine.EngineEvent.MessageReceived ->
                        _logLines.update { (it + "msg from ${ev.contactHash} verified=${ev.verified}").takeLast(500) }
                }
            }
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

    fun toggleFavorite(hash: String, favorite: Boolean) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.setFavorite(hash, favorite) }
                .onFailure { _logLines.update { lines -> (lines + "favorite fail: ${it.message}").takeLast(500) } }
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

    fun addManualDestination(hashHex: String, label: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.addManualDestination(hashHex, label) }
                .onFailure { _logLines.update { lines -> (lines + "manual add fail: ${it.message}").takeLast(500) } }
        }
    }

    fun applyScannedQr(json: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching { svc.applyIdentityCardJson(json) }
                .onFailure { _logLines.update { lines -> (lines + "qr apply fail: ${it.message}").takeLast(500) } }
        }
    }

    fun sendMessage(content: String) {
        val svc = _service.value ?: return
        val destHash = _selectedDestination.value ?: return
        viewModelScope.launch {
            runCatching { svc.sendMessage(destHash, content) }
                .onFailure { _logLines.update { lines -> (lines + "send fail: ${it.message}").takeLast(500) } }
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
     * Initiate a NomadNet page fetch and forward the result via [onResult].
     * Performed off the UI thread; the callback is invoked on the
     * viewModelScope coroutine.
     */
    fun fetchNomadPage(
        destinationHash: String,
        path: String = ":/page/index.mu",
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
}

private fun ByteArray.toHexLower(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
