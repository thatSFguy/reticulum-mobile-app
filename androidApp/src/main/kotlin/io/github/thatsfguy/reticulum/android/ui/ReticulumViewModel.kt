package io.github.thatsfguy.reticulum.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.android.storage.Repositories
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.store.StoredContact
import io.github.thatsfguy.reticulum.store.StoredMessage
import io.github.thatsfguy.reticulum.store.StoredNode
import io.github.thatsfguy.reticulum.transport.TransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds UI state derived from the bound [ReticulumService]. The Activity
 * passes the service in via [bind] when the binder arrives, and clears it
 * via [unbind] on disconnect.
 */
class ReticulumViewModel : ViewModel() {

    private val _service = MutableStateFlow<ReticulumService?>(null)
    val service: StateFlow<ReticulumService?> = _service

    private val _selectedContact = MutableStateFlow<String?>(null)
    val selectedContact: StateFlow<String?> = _selectedContact.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: Flow<ReticulumEngine.ConnectionState> =
        _service.flatMapLatest { svc -> svc?.connection ?: flowOf(ReticulumEngine.ConnectionState(TransportState.Disconnected, null)) }

    val contacts: Flow<List<StoredContact>>
        get() = repos()?.observeContacts() ?: flowOf(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesForSelected: Flow<List<StoredMessage>> =
        _selectedContact.flatMapLatest { hash ->
            val r = repos()
            if (hash != null && r != null) r.observeMessagesForContact(hash) else flowOf(emptyList())
        }

    val nodes: Flow<List<StoredNode>>
        get() = repos()?.observeNodes() ?: flowOf(emptyList())

    private val _ourDestHash = MutableStateFlow<String?>(null)
    val ourDestHash: StateFlow<String?> = _ourDestHash.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val displayName: Flow<String> =
        _service.flatMapLatest { svc -> svc?.prefs?.displayName ?: flowOf("Reticulum Mobile") }

    fun bind(service: ReticulumService) {
        _service.value = service
        refreshOurDestHash(service)
        viewModelScope.launch {
            service.events.collect { ev ->
                when (ev) {
                    is ReticulumEngine.EngineEvent.Log ->
                        _logLines.update { (it + ev.line).takeLast(500) }
                    is ReticulumEngine.EngineEvent.ContactSeen ->
                        _logLines.update { (it + "contact ${ev.hash} ${ev.displayName}").takeLast(500) }
                    is ReticulumEngine.EngineEvent.NodeSeen ->
                        _logLines.update { (it + "node ${ev.hash} ${ev.displayName}").takeLast(500) }
                    is ReticulumEngine.EngineEvent.MessageReceived ->
                        _logLines.update { (it + "msg from ${ev.contactHash} verified=${ev.verified}").takeLast(500) }
                }
            }
        }
    }

    private fun refreshOurDestHash(service: ReticulumService) {
        viewModelScope.launch {
            runCatching { service.ourDestHash() }
                .onSuccess { _ourDestHash.value = it.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } }
                .onFailure { _logLines.update { lines -> (lines + "dest hash unavailable: ${it.message}").takeLast(500) } }
        }
    }

    fun unbind() { _service.value = null }

    fun selectContact(hash: String?) { _selectedContact.value = hash }

    fun sendMessage(content: String) {
        val svc = _service.value ?: return
        val contactHash = _selectedContact.value ?: return
        viewModelScope.launch {
            runCatching { svc.sendMessage(contactHash, content) }
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
    }

    fun resetIdentity() {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.resetIdentity()
                refreshOurDestHash(svc)
            }.onFailure {
                _logLines.update { lines -> (lines + "reset fail: ${it.message}").takeLast(500) }
            }
        }
    }

    private fun repos(): Repositories? = _service.value?.repos
}
