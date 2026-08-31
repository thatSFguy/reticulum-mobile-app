package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.thatsfguy.reticulum.android.storage.UnreadTally
import io.github.thatsfguy.reticulum.android.storage.rrcHubKeyPrefix
import io.github.thatsfguy.reticulum.android.storage.rrcRoomKey
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.android.ui.UnreadPill
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel.RrcHubState
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel.RrcRoomMeta
import io.github.thatsfguy.reticulum.rrc.RrcCommands
import io.github.thatsfguy.reticulum.rrc.RrcMember
import io.github.thatsfguy.reticulum.rrc.RrcMentions
import io.github.thatsfguy.reticulum.rrc.RrcRoomListing
import io.github.thatsfguy.reticulum.engine.RrcState
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredRrcHub
import io.github.thatsfguy.reticulum.store.StoredRrcMessage
import io.github.thatsfguy.reticulum.store.ReactionsJson
import io.github.thatsfguy.reticulum.store.StoredRrcRoom
import io.github.thatsfguy.reticulum.util.shortHash
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Reticulum Relay Chat (RRC) screen. Three nested views:
 *
 *  - hub list      — known hubs + add / delete
 *  - hub detail    — connect to a hub, see / join its rooms
 *  - room chat     — the conversation, with the composer as a command line
 *
 * **Navigation state lives in the ViewModel, not here.** A bottom-nav
 * tap swaps the NavHost destination, which takes this whole screen out
 * of composition — anything `remember`ed inside it (which hub is open,
 * which room, what the user had typed) dies with it. That is what used
 * to throw the user out of a room, and silently discard a half-written
 * message, on a stray tab tap. Selection now comes from
 * [ReticulumViewModel.rrcSelectedHub] / `rrcSelectedRoom` and drafts
 * from `rrcDraftFor`, both of which outlive the screen; the system Back
 * button walks the same three levels via [BackHandler].
 *
 * The whole tab is gated by the `experimentalRrc` preference in
 * MainActivity, so this screen is only reachable when the user has
 * opted into the experimental feature.
 */
@Composable
fun RoomsScreen(viewModel: ReticulumViewModel) {
    val hubs by viewModel.rrcHubs.collectAsState(initial = emptyList())
    val hubStates by viewModel.rrcHubStates.collectAsState()
    val discovered by viewModel.discoverableRrcHubs.collectAsState(initial = emptyList())
    val selectedHub by viewModel.rrcSelectedHub.collectAsState()
    val selectedRoom by viewModel.rrcSelectedRoom.collectAsState()
    val unread by viewModel.rrcUnread.collectAsState(initial = emptyMap())

    // A deleted hub (or one cleared out from under us) drops the user
    // back to the list rather than rendering a detail view for nothing.
    val hub = hubs.firstOrNull { it.destHash == selectedHub }
    LaunchedEffect(selectedHub, hubs.size) {
        // Guarded on a non-empty list: the hub Flow's first emission is
        // the empty initial value, and clearing on that would throw a
        // notification deep-link straight back to the hub list before
        // the row it named had loaded. An empty list needs no clearing
        // anyway — the view below already falls back to the list.
        if (selectedHub != null && hubs.isNotEmpty() &&
            hubs.none { it.destHash == selectedHub }
        ) {
            viewModel.selectRrcHub(null)
        }
    }

    when {
        hub == null ->
            HubListView(
                hubs = hubs,
                hubStates = hubStates,
                discovered = discovered,
                unread = unread,
                onPick = { viewModel.selectRrcHub(it) },
                onAdd = viewModel::addRrcHub,
                onAddDiscovered = { dest ->
                    viewModel.addRrcHub(dest.hash, dest.effectiveDisplayName, null)
                },
                onDelete = viewModel::deleteRrcHub,
            )

        selectedRoom != null -> {
            BackHandler { viewModel.selectRrcRoom(null) }
            RoomChatView(
                viewModel = viewModel,
                hub = hub,
                room = selectedRoom!!,
                state = hubStates[hub.destHash],
                onBack = { viewModel.selectRrcRoom(null) },
            )
        }

        else -> {
            BackHandler { viewModel.selectRrcHub(null) }
            HubDetailView(
                viewModel = viewModel,
                hub = hub,
                state = hubStates[hub.destHash],
                unread = unread,
                onBack = { viewModel.selectRrcHub(null) },
                onOpenRoom = { viewModel.selectRrcRoom(it) },
            )
        }
    }
}

// ---- hub list ----------------------------------------------------------

@Composable
private fun HubListView(
    hubs: List<StoredRrcHub>,
    hubStates: Map<String, RrcHubState>,
    discovered: List<StoredDestination>,
    unread: Map<String, UnreadTally>,
    onPick: (String) -> Unit,
    onAdd: (String, String, String?) -> Unit,
    onAddDiscovered: (StoredDestination) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<StoredRrcHub?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Relay Chat hubs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add hub")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (hubs.isEmpty() && discovered.isEmpty()) {
            EmptyState(
                Icons.AutoMirrored.Filled.List,
                "No RRC hubs yet. When a hub announces on the network it'll "
                    + "appear here to add in one tap — or add one now by its "
                    + "destination hash.",
                actionLabel = "Add by hash",
                onAction = { showAdd = true },
            )
        } else if (hubs.isEmpty()) {
            // Discovery-first empty state: no hubs added yet, but some have
            // announced — let the user add one in a tap instead of pasting
            // a 32-hex hash.
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Discovered on the network",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(discovered, key = { it.hash }) { d ->
                        DiscoveredHubRow(dest = d, onAdd = { onAddDiscovered(d) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(hubs, key = { it.destHash }) { h ->
                    HubRow(
                        hub = h,
                        state = hubStates[h.destHash],
                        // Everything unread anywhere on this hub — the
                        // hub row is the only place it shows before the
                        // user drills in, and it goes red if any room
                        // under it holds a mention.
                        unread = unread.entries
                            .filter { it.key.startsWith(rrcHubKeyPrefix(h.destHash)) }
                            .fold(UnreadTally()) { acc, e -> acc + e.value },
                        onClick = { onPick(h.destHash) },
                        onDelete = { pendingDelete = h },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (showAdd) {
        AddHubDialog(
            discovered = discovered,
            onDismiss = { showAdd = false },
            onPickDiscovered = { dest ->
                onAddDiscovered(dest)
                showAdd = false
            },
            onConfirm = { hash, name, nick ->
                onAdd(hash, name, nick)
                showAdd = false
            },
        )
    }

    pendingDelete?.let { h ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this hub?") },
            text = {
                Text(
                    "Removes ${h.displayName.ifBlank { "(unnamed)" }} along with all joined rooms and " +
                        "their message history. Any live session is closed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(h.destHash)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubRow(
    hub: StoredRrcHub,
    state: RrcHubState?,
    unread: UnreadTally,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // Tap opens the hub; long-press deletes it (→ confirm dialog) —
    // no inline trash icon, consistent with the other list rows.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDelete)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state?.hubName ?: hub.displayName.ifBlank { "(unnamed hub)" },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                shortHash(hub.destHash),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                statusLabel(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UnreadPill(unread)
    }
}

/** A hub that has announced (appName `rrc.hub`) but isn't in the user's
 *  hub list yet. Tapping adds it — the discovery path that replaces a
 *  curated default hub (see docs/ROADMAP.md, Phase 1). */
@Composable
private fun DiscoveredHubRow(dest: StoredDestination, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                dest.effectiveDisplayName.ifBlank { "(unnamed hub)" },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                shortHash(dest.hash),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAdd) { Text("Add") }
    }
}

@Composable
private fun AddHubDialog(
    discovered: List<StoredDestination>,
    onDismiss: () -> Unit,
    onPickDiscovered: (StoredDestination) -> Unit,
    onConfirm: (hash: String, name: String, nick: String?) -> Unit,
) {
    var hash by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    // A destination hash is 16 bytes — 32 hex characters.
    val hashOk = hash.trim().let { it.length == 32 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add RRC hub") },
        text = {
            Column {
                // Discovery-first: any hub that has announced is one tap to
                // add. The manual hash field below stays as the escape hatch
                // for a hub that hasn't announced to us yet.
                if (discovered.isNotEmpty()) {
                    Text(
                        "Discovered on the network",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    discovered.take(4).forEach { d ->
                        DiscoveredHubRow(dest = d, onAdd = { onPickDiscovered(d) })
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    Text(
                        "Or add by hash",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = hash,
                    onValueChange = { hash = it },
                    label = { Text("Hub destination hash") },
                    singleLine = true,
                    isError = hash.isNotEmpty() && !hashOk,
                    supportingText = {
                        Text(
                            if (hash.isNotEmpty() && !hashOk) "Must be 32 hex characters"
                            else "The hub must have announced before you can connect",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    label = { Text("Your nick (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = hashOk,
                onClick = { onConfirm(hash.trim().lowercase(), name, nick.ifBlank { null }) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- hub detail --------------------------------------------------------

@Composable
private fun HubDetailView(
    viewModel: ReticulumViewModel,
    hub: StoredRrcHub,
    state: RrcHubState?,
    unread: Map<String, UnreadTally>,
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
) {
    val rooms by remember(hub.destHash) { viewModel.rrcRooms(hub.destHash) }
        .collectAsState(initial = emptyList())
    var joinName by remember { mutableStateOf("") }
    var showBrowse by remember { mutableStateOf(false) }
    var showEditNick by remember { mutableStateOf(false) }
    var pendingRoomDelete by remember { mutableStateOf<StoredRrcRoom?>(null) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = state?.hubName ?: hub.displayName.ifBlank { "(unnamed hub)" },
            subtitle = statusLabel(state),
            state = state,
            onBack = onBack,
        )

        // Connect / disconnect control.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state)
            Spacer(Modifier.width(8.dp))
            Text(
                shortHash(hub.destHash),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state?.welcomed == true) {
                OutlinedButton(onClick = { viewModel.closeRrcSession(hub.destHash) }) { Text("Disconnect") }
            } else {
                Button(
                    enabled = state?.connecting != true,
                    onClick = { viewModel.openRrcSession(hub.destHash) },
                ) {
                    if (state?.connecting == true) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Connect")
                    }
                }
            }
        }

        // Your RRC username on this hub. Editable here; a change applies
        // on the next connect (the hub stamps nick from the HELLO).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Your nick: ${hub.nick ?: "(not set)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showEditNick = true }) { Text("Edit") }
        }

        NoticeBanner(state?.lastNotice, onDismiss = { viewModel.clearRrcNotice(hub.destHash) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Join-a-room input — only usable once WELCOME has landed.
        if (state?.welcomed == true) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = joinName,
                    onValueChange = { joinName = it },
                    label = { Text("Room name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = joinName.isNotBlank(),
                    onClick = {
                        viewModel.joinRrcRoom(hub.destHash, joinName)
                        joinName = ""
                    },
                ) { Text("Join") }
            }
            // Discovery — ask the hub (`/list`) what public rooms exist.
            TextButton(
                onClick = {
                    viewModel.browseRrcRooms(hub.destHash)
                    showBrowse = true
                },
                modifier = Modifier.padding(horizontal = 10.dp),
            ) { Text("Browse available rooms") }
        }

        if (rooms.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (state?.welcomed == true)
                        "Connected. Join a room above to start chatting."
                    else
                        "Connect to the hub to see and join rooms.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rooms, key = { "${it.hubHash}/${it.name}" }) { room ->
                    RoomRow(
                        room = room,
                        welcomed = state?.welcomed == true,
                        unread = unread[rrcRoomKey(room.hubHash, room.name)] ?: UnreadTally(),
                        topic = state?.roomMeta?.get(room.name)?.topic,
                        onOpen = { onOpenRoom(room.name) },
                        onJoin = { viewModel.joinRrcRoom(hub.destHash, room.name) },
                        onLeave = { viewModel.partRrcRoom(hub.destHash, room.name) },
                        onRemove = { pendingRoomDelete = room },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (showBrowse) {
        RoomBrowserDialog(
            rooms = state?.availableRooms,
            joinedNames = rooms.filter { it.joined }.map { it.name }.toSet(),
            onJoin = { name -> viewModel.joinRrcRoom(hub.destHash, name) },
            onDismiss = { showBrowse = false },
        )
    }

    if (showEditNick) {
        EditNickDialog(
            current = hub.nick,
            onDismiss = { showEditNick = false },
            onSave = { newNick ->
                viewModel.setRrcHubNick(hub.destHash, newNick)
                showEditNick = false
            },
        )
    }

    pendingRoomDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingRoomDelete = null },
            title = { Text("Remove this room?") },
            text = {
                Text(
                    "Removes #${r.name} and its message history from this device. " +
                        "If you're a member, you'll also leave it on the hub.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRrcRoom(hub.destHash, r.name)
                    pendingRoomDelete = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRoomDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Set / change your RRC nick (username) for a hub. The nick is stored
 * on the hub row and read by the engine when it next connects, so a
 * change takes effect on the next connect — the hub stamps it from the
 * HELLO. An empty value sends your messages unnamed.
 */
@Composable
private fun EditNickDialog(
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var draft by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your nick on this hub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Nick") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The name shown next to your messages. Leave empty to " +
                        "send unnamed. A change takes effect the next time " +
                        "you connect to this hub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.trim().ifBlank { null }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The "Browse available rooms" dialog — shows the hub's `/list` reply.
 * [rooms] is null while the reply is still in flight (spinner), empty
 * when the hub has no public rooms, or the registered-room list. A row
 * already in [joinedNames] shows "Joined" instead of a Join button;
 * tapping Join leaves the dialog open so several rooms can be joined.
 */
@Composable
private fun RoomBrowserDialog(
    rooms: List<RrcRoomListing>?,
    joinedNames: Set<String>,
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Available rooms") },
        text = {
            when {
                rooms == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Asking the hub…")
                }
                rooms.isEmpty() -> Text(
                    "No public rooms are registered on this hub. You can still " +
                        "join a room directly by name.",
                )
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(rooms, key = { it.name }) { room ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("#${room.name}", style = MaterialTheme.typography.titleSmall)
                                room.topic?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (room.name in joinedNames) {
                                Text(
                                    "Joined",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                TextButton(onClick = { onJoin(room.name) }) { Text("Join") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomRow(
    room: StoredRrcRoom,
    welcomed: Boolean,
    unread: UnreadTally,
    topic: String?,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onRemove: () -> Unit,
) {
    // Tap opens the room; long-press removes it (→ confirm dialog).
    // Join / Leave stays inline — it's the row's primary action.
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = onOpen, onLongClick = onRemove)
                .padding(14.dp),
        ) {
            Text(
                "#${room.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread.total > 0) FontWeight.Bold else FontWeight.Normal,
            )
            // The topic is the one line that says what a room is for —
            // worth the row space when the hub has told us one.
            if (!topic.isNullOrBlank()) {
                Text(
                    topic,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                when {
                    room.joined && room.notifyMode == StoredRrcRoom.NOTIFY_NONE -> "Joined · muted"
                    room.joined && room.notifyMode == StoredRrcRoom.NOTIFY_MENTIONS ->
                        "Joined · mentions only"
                    room.joined -> "Joined"
                    else -> "Not joined"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (room.joined)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UnreadPill(unread)
        if (welcomed) {
            if (room.joined) {
                TextButton(onClick = onLeave) { Text("Leave") }
            } else {
                TextButton(onClick = onJoin) { Text("Join") }
            }
        }
    }
}

// ---- room chat ---------------------------------------------------------

/**
 * One room's conversation.
 *
 * The composer is a command line, not a text field: RRC is IRC-shaped,
 * and the hub consumes any message body that starts with `/`
 * (`client-parity.md` §2). Typing `/` opens a completion list built from
 * [RrcCommands.SPECS]; the line itself is parsed in the engine, which
 * owns the client-side commands (`/join`, `/part`, `/nick`, `/me`,
 * `/clear`) and forwards the rest. Both the echo of the command and the
 * hub's answer come back as system lines *in this timeline* — the whole
 * exchange stays where the user typed it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomChatView(
    viewModel: ReticulumViewModel,
    hub: StoredRrcHub,
    room: String,
    state: RrcHubState?,
    onBack: () -> Unit,
) {
    val messages by remember(hub.destHash, room) { viewModel.rrcMessages(hub.destHash, room) }
        .collectAsState(initial = emptyList())
    val rooms by remember(hub.destHash) { viewModel.rrcRooms(hub.destHash) }
        .collectAsState(initial = emptyList())
    val roomRow = rooms.firstOrNull { it.name == room }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val meta = state?.roomMeta?.get(room)

    // The draft lives in the ViewModel, not here: it has to survive a
    // stray bottom-nav tap (which takes this screen out of composition)
    // and a rotation, and a message the hub refuses is handed back to
    // it rather than lost.
    val drafts by viewModel.rrcDrafts.collectAsState()
    val draft = drafts[rrcRoomKey(hub.destHash, room)] ?: ""

    // The composer holds a TextFieldValue, not a String, so this code
    // owns the CARET as well as the text.
    //
    // A String-valued text field keeps the previous selection offset
    // across a programmatic change, so completing "hey @al" to
    // "hey @alice " left the caret at offset 7 — in the middle of the
    // name it had just inserted. Every insertion below therefore states
    // where the caret goes, which for an append is the end.
    var composer by remember(hub.destHash, room) {
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    // Re-seed when the draft changes from outside this field — a send
    // that the hub refused hands the text back, and switching rooms
    // loads that room's own draft.
    LaunchedEffect(draft) {
        if (draft != composer.text) composer = TextFieldValue(draft, TextRange(draft.length))
    }

    /** Replace the composer's text and park the caret at the end. */
    fun setComposer(text: String) {
        composer = TextFieldValue(text, TextRange(text.length))
        viewModel.setRrcDraft(hub.destHash, room, text)
    }
    var showMembers by remember { mutableStateOf(false) }
    var pendingClear by remember { mutableStateOf(false) }

    // The "new messages" line is pinned to what was unread when the room
    // was opened — reading the live marker would erase the line the
    // instant the room is marked read, which is immediately.
    var unreadAfterId by remember(hub.destHash, room) { mutableStateOf<Long?>(null) }
    LaunchedEffect(hub.destHash, room, roomRow != null) {
        if (unreadAfterId == null && roomRow != null) unreadAfterId = roomRow.lastReadMessageId
    }

    // Being composed is what makes this room "on screen": it clears the
    // room's notifications and stops new ones, but only while the UI is
    // also in front of the user (the ViewModel ANDs in the Activity's
    // started state — a composition survives the screen going off).
    DisposableEffect(hub.destHash, room) {
        viewModel.setRrcRoomOnScreen(hub.destHash, room)
        onDispose { viewModel.setRrcRoomOnScreen(null, null) }
    }

    // Everything on screen is read.
    LaunchedEffect(hub.destHash, room, messages.size) {
        if (messages.isNotEmpty()) viewModel.markRrcRoomRead(hub.destHash, room)
    }

    val rows = remember(messages, unreadAfterId) { buildRoomRows(messages, unreadAfterId) }

    // Reply anchors resolve within THIS room only — a K_ID is 8
    // sender-chosen random bytes with no uniqueness guarantee, so a
    // wider lookup could point a reply at an unrelated message
    // (`rrc-extensions.md` §5).
    val byMsgId = remember(messages) {
        messages.mapNotNull { m -> m.msgId?.let { it to m } }.toMap()
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // Last nick seen per identity — what resolves a reactor to a name.
    val nickByHash = remember(messages) { nicksByHash(messages) }
    val ourIdentity by viewModel.ourIdentityHash.collectAsState(initial = "")
    val ourIdentityHex = ourIdentity

    // Keyboard handling is STRUCTURAL, not a scroll effect — the same
    // fix the direct-message view landed for issue #30 after three
    // scroll-based attempts kept re-breaking. The list below runs
    // `reverseLayout = true` over `rows.asReversed()`, so index 0 is the
    // newest line and renders at the BOTTOM: the newest bubble's bottom
    // edge is pinned to the viewport bottom by the layout itself, and
    // stays pinned when the keyboard opens (the manifest's adjustResize
    // shrinks the viewport from the bottom) and when a row grows.
    //
    // Do NOT reach for WindowInsets.ime / imePadding logic here: this
    // app is not edge-to-edge, so the window resize consumes the IME
    // inset before Compose sees it and the inset reads 0 — that is what
    // made the v1.2.60 attempt a silent no-op.
    //
    // With reverseLayout, "at the bottom" is a LOW first-visible index.
    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 2 }
    }

    // reverseLayout pins content that is already laid out, but a freshly
    // prepended newest row lands just past the anchor — off-screen
    // behind the composer — so nudge to it, and only when the user is
    // already near the bottom (reading history must not be yanked).
    val newestRowKey = rows.lastOrNull()?.key
    LaunchedEffect(newestRowKey) {
        if (newestRowKey != null && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    // No opening scroll: the layout already opens on the newest line,
    // and there is no correct way to open ON the unread marker here —
    // scrollToItem aligns to the layout's start, which under
    // reverseLayout is the BOTTOM edge, so landing on the marker would
    // push the unread messages themselves off-screen beneath it. The
    // marker stays in the timeline as a divider to scroll up to.

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        setComposer("")
        viewModel.sendRrcMessage(hub.destHash, room, text)
        scope.launch { listState.animateScrollToItem(0) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        RoomHeader(
            room = room,
            hubLabel = state?.hubName ?: hub.displayName,
            state = state,
            memberCount = meta?.members?.size ?: 0,
            joined = roomRow?.joined == true,
            notifyMode = roomRow?.notifyMode ?: StoredRrcRoom.NOTIFY_ALL,
            onBack = onBack,
            onShowMembers = { showMembers = true },
            onNotifyMode = { viewModel.setRrcRoomNotifyMode(hub.destHash, room, it) },
            onLeave = { viewModel.partRrcRoom(hub.destHash, room) },
            onJoin = { viewModel.joinRrcRoom(hub.destHash, room) },
            onClear = { pendingClear = true },
        )
        RoomTopicBar(meta)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No messages in #$room yet. Type a message, or /help for "
                            + "what this hub understands.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Anchors the newest line to the bottom of the
                    // viewport by layout, so the keyboard opening (which
                    // shrinks the viewport under adjustResize) can't
                    // hide it. Feeding the reversed list keeps the
                    // visual order — index 0 renders at the bottom.
                    reverseLayout = true,
                ) {
                    items(rows.asReversed(), key = { it.key }) { row ->
                        when (row) {
                            is RoomRowItem.DaySeparator -> DaySeparator(row.label)
                            is RoomRowItem.UnreadMarker -> UnreadMarker()
                            is RoomRowItem.Line -> RoomLine(
                                msg = row.msg,
                                grouped = row.grouped,
                                quoted = row.msg.replyToMsgId?.let { byMsgId[it] },
                                ourIdentityHex = ourIdentityHex,
                                nicks = nickByHash,
                                onReply = {
                                    row.msg.msgId?.let {
                                        viewModel.setRrcReplyTarget(hub.destHash, room, it)
                                    }
                                },
                                onReact = { emoji ->
                                    val target = row.msg.msgId ?: return@RoomLine
                                    // Tapping a chip we're already in
                                    // retracts; anything else applies.
                                    val holders = ReactionsJson
                                        .decode(row.msg.reactionsJson)[emoji].orEmpty()
                                    viewModel.sendRrcReaction(
                                        hub.destHash, room, target, emoji,
                                        retract = ourIdentityHex.isNotEmpty() &&
                                            ourIdentityHex in holders,
                                    )
                                },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(row.msg.text))
                                },
                            )
                        }
                    }
                }
            }
            // Back-to-the-present affordance, so leaving the bottom is a
            // decision the user can undo in one tap.
            if (!atBottom && rows.isNotEmpty()) {
                TextButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Latest")
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Inline completion for a `/`-command that is still just a verb.
        // Deliberately local knowledge (RrcCommands.SPECS) rather than a
        // round trip: it has to work before WELCOME and over a link
        // where a round trip costs seconds. `/help` remains the hub's
        // own, authoritative answer.
        val verbPrefix = draft.trimStart().takeIf {
            it.startsWith("/") && !it.startsWith("//") && !it.contains(' ')
        }
        if (verbPrefix != null) {
            CommandPalette(
                prefix = verbPrefix,
                onPick = { name -> setComposer("/$name ") },
            )
        }

        // `@`-completion. Candidates come from the room's /who roster
        // (the only source of NICKS — a JOINED member list carries
        // identity hashes only) merged with everyone who has spoken
        // here, so it is useful before /who has ever been run.
        val mentionPrefix = RrcMentions.tokenAt(draft)
        if (verbPrefix == null && mentionPrefix != null) {
            MentionPalette(
                prefix = mentionPrefix,
                roster = meta?.roster.orEmpty(),
                seenNicks = remember(messages) { nicksByHash(messages).values.toSet() },
                onPick = { name -> setComposer(RrcMentions.replaceToken(draft, name)) },
                onRunWho = { viewModel.sendRrcMessage(hub.destHash, room, "/who") },
            )
        }

        // What we're replying to, with a way out. Held in the ViewModel
        // so a stray tab tap doesn't silently drop the anchor.
        val replyTargets by viewModel.rrcReplyTargets.collectAsState()
        val replyTargetId = replyTargets[rrcRoomKey(hub.destHash, room)]
        if (replyTargetId != null) {
            val target = byMsgId[replyTargetId]
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Replying to " +
                            (target?.nick?.takeIf { it.isNotBlank() }
                                ?: target?.let { shortSender(it) } ?: "a message"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (target != null) {
                        Text(
                            target.text.take(100),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = { viewModel.setRrcReplyTarget(hub.destHash, room, null) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Cancel reply")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = composer,
                onValueChange = {
                    composer = it
                    viewModel.setRrcDraft(hub.destHash, room, it.text)
                },
                placeholder = { Text("Message #$room  ·  / for commands") },
                // Typeable even with the hub down: the draft is kept, so
                // writing while offline and sending on reconnect works.
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = state?.welcomed == true && draft.isNotBlank(),
                onClick = { submit() },
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = if (verbPrefix != null) "Run command" else "Send",
                )
            }
        }
        if (state?.welcomed != true) {
            // Not just a warning — the fix, one tap away. The draft is
            // kept either way, so reconnecting never costs the message.
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Not connected — keep typing, your draft is saved.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = state?.connecting != true,
                    onClick = { viewModel.openRrcSession(hub.destHash) },
                ) { Text(if (state?.connecting == true) "Connecting…" else "Reconnect") }
            }
        }
    }

    if (showMembers) {
        MembersSheet(
            room = room,
            members = meta?.members.orEmpty(),
            nicks = remember(messages) { nicksByHash(messages) },
            onDismiss = { showMembers = false },
        )
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("Clear this room's history?") },
            text = {
                Text(
                    "Deletes #$room's messages from this device. The room itself, "
                        + "your membership, and anyone else's copy are untouched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Routed through the composer path so it takes the
                    // same code path as typing /clear.
                    viewModel.sendRrcMessage(hub.destHash, room, "/clear")
                    pendingClear = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingClear = false }) { Text("Cancel") } },
        )
    }
}

/** Room title bar: status, member count, and the per-room menu. */
@Composable
private fun RoomHeader(
    room: String,
    hubLabel: String,
    state: RrcHubState?,
    memberCount: Int,
    joined: Boolean,
    notifyMode: String,
    onBack: () -> Unit,
    onShowMembers: () -> Unit,
    onNotifyMode: (String) -> Unit,
    onLeave: () -> Unit,
    onJoin: () -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text("#$room", style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(hubLabel)
                    // A member count only when the hub actually sends
                    // rosters — silence means "not told", not "empty".
                    if (memberCount > 0) append("  ·  $memberCount here")
                    if (notifyMode == StoredRrcRoom.NOTIFY_MENTIONS) append("  ·  mentions only")
                    if (notifyMode == StoredRrcRoom.NOTIFY_NONE) append("  ·  muted")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusDot(state)
        if (memberCount > 0) {
            IconButton(onClick = onShowMembers) {
                Icon(Icons.Default.Person, contentDescription = "Members")
            }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Room menu")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Notify: all messages") },
                leadingIcon = {
                    RadioButton(selected = notifyMode == StoredRrcRoom.NOTIFY_ALL, onClick = null)
                },
                onClick = { onNotifyMode(StoredRrcRoom.NOTIFY_ALL); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Notify: mentions only") },
                leadingIcon = {
                    RadioButton(
                        selected = notifyMode == StoredRrcRoom.NOTIFY_MENTIONS,
                        onClick = null,
                    )
                },
                onClick = { onNotifyMode(StoredRrcRoom.NOTIFY_MENTIONS); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Mute this room") },
                leadingIcon = {
                    RadioButton(selected = notifyMode == StoredRrcRoom.NOTIFY_NONE, onClick = null)
                },
                onClick = { onNotifyMode(StoredRrcRoom.NOTIFY_NONE); menuOpen = false },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (memberCount > 0) {
                DropdownMenuItem(
                    text = { Text("Members") },
                    onClick = { onShowMembers(); menuOpen = false },
                )
            }
            if (joined) {
                // Even with the local row saying joined, the hub can
                // have forgotten our membership across a session bounce
                // (restart, kline, a link timeout we didn't notice).
                // The engine re-JOINs every joined room on WELCOME, but
                // when that misses, the only visible action was the
                // destructive one — so "messages going out, nothing
                // coming back" left the user with Leave as the only
                // move. Re-JOINing is idempotent and loses nothing.
                DropdownMenuItem(
                    text = { Text("Rejoin room") },
                    onClick = { onJoin(); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text("Leave room") },
                    onClick = { onLeave(); menuOpen = false },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Join room") },
                    onClick = { onJoin(); menuOpen = false },
                )
            }
            DropdownMenuItem(
                text = { Text("Clear history on this device") },
                onClick = { onClear(); menuOpen = false },
            )
        }
    }
}

/** Slash-command completion, shown while the composer holds a bare verb. */
@Composable
private fun CommandPalette(prefix: String, onPick: (String) -> Unit) {
    val matches = remember(prefix) { RrcCommands.completions(prefix).take(6) }
    if (matches.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp),
    ) {
        matches.forEach { spec ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(spec.name) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    spec.usage,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 120.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    spec.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * `@`-completion for a mention.
 *
 * Two forms name somebody in RRC (`client-parity.md` §8): `@nick`,
 * which is advisory and can be ambiguous, and `@` plus 6+ hex
 * characters of an identity hash, which is exact. Both are offered —
 * the hash form is what the roster falls back to for a member who has
 * set no nick, and it is the one to use when you must be certain.
 *
 * When the room has no roster yet, the list offers to fetch one rather
 * than firing `/who` on its own: the reply costs a round trip and
 * prints a line in the room, and neither should happen because somebody
 * typed a character.
 */
@Composable
private fun MentionPalette(
    prefix: String,
    roster: List<RrcMember>,
    seenNicks: Set<String>,
    onPick: (String) -> Unit,
    onRunWho: () -> Unit,
) {
    val candidates = remember(prefix, roster, seenNicks) {
        val fromRoster = roster.map { it.nick?.takeIf { n -> n.isNotBlank() } ?: it.hashPrefix }
        (fromRoster + seenNicks)
            .distinct()
            .filter { it.isNotEmpty() && it.startsWith(prefix, ignoreCase = true) }
            .sorted()
            .take(6)
    }
    if (candidates.isEmpty() && roster.isNotEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp),
    ) {
        candidates.forEach { name ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(name) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "@$name",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (roster.isEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onRunWho() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    if (candidates.isEmpty()) "Ask the hub who is here (/who)"
                    else "More names — ask the hub (/who)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Who is in the room. Identity hashes are what the hub sends; a nick is
 *  attached when one has been seen on a message from that identity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersSheet(
    room: String,
    members: List<String>,
    nicks: Map<String, String>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "In #$room  ·  ${members.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(members, key = { it }) { hash ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            nicks[hash] ?: "(no nick seen)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            shortHash(hash),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "RRC nicknames are advisory and not unique — the hash is the "
                    + "identity. Name someone with @nick, or @ plus 6+ characters "
                    + "of their hash to be certain.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
            )
        }
    }
}

/** A date heading between two days' messages. */
@Composable
private fun DaySeparator(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    )
}

/** The "everything below this arrived while you were away" line. */
@Composable
private fun UnreadMarker() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text(
            "  New messages  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * One line of the timeline. Four shapes, because RRC has four:
 * a chat bubble, an action (`/me`), a hub system line, and a hub error.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomLine(
    msg: StoredRrcMessage,
    grouped: Boolean,
    quoted: StoredRrcMessage? = null,
    ourIdentityHex: String = "",
    nicks: Map<String, String> = emptyMap(),
    onReply: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onCopy: () -> Unit = {},
) {
    when (msg.direction) {
        "system", "error" -> {
            SystemLine(msg)
            return
        }
    }
    val action = msg.text.startsWith("/me ") || msg.text == "/me"
    if (action) {
        // "* alice waves" — an action is about the room, not addressed
        // to it, so it gets no bubble and no side.
        Text(
            "* ${msg.nick ?: shortSender(msg)} ${msg.text.removePrefix("/me").trim()}",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
        )
        return
    }
    val outgoing = msg.direction == "outgoing"
    val bubbleColor = when {
        msg.mention -> MaterialTheme.colorScheme.tertiaryContainer
        outgoing -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        msg.mention -> MaterialTheme.colorScheme.onTertiaryContainer
        outgoing -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var menuOpen by remember(msg.id) { mutableStateOf(false) }
    var showPicker by remember(msg.id) { mutableStateOf(false) }
    var showReactors by remember(msg.id) { mutableStateOf(false) }
    val reactions = remember(msg.reactionsJson) { ReactionsJson.decode(msg.reactionsJson) }
    // Reactions need a target the hub can address: our own envelope id.
    // A row saved before this shipped has none, so it can be replied to
    // and copied but not reacted to.
    val canAnchor = !msg.msgId.isNullOrEmpty()
    // Not your own messages — the same rule the direct-message bubbles
    // follow. Every reaction is a message on a shared mesh, and a
    // self-reaction is a UX foot-gun with no clear use case. Reactions
    // OTHERS left on your message still render; you just can't add to
    // them.
    val canReact = canAnchor && !outgoing

    Row(
        Modifier.fillMaxWidth().padding(
            start = 10.dp, end = 10.dp,
            top = if (grouped) 1.dp else 4.dp, bottom = 1.dp,
        ),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(bubbleColor)
                    .then(
                        if (msg.mention)
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.tertiary,
                                RoundedCornerShape(12.dp),
                            )
                        else Modifier,
                    )
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                // A run of messages from one person shows one header, so the
                // eye follows the conversation instead of the metadata.
                if (!grouped) {
                    Text(
                        buildString {
                            append(msg.nick?.takeIf { it.isNotBlank() } ?: shortSender(msg))
                            append("  ")
                            append(formatRrcClock(msg.timestamp))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                }
                // The quoted line this replies to. A reply whose target
                // we don't hold still renders — as an ordinary message,
                // which is what `rrc-extensions.md` §3 asks for.
                if (quoted != null) {
                    QuotedLine(quoted, textColor)
                } else if (msg.replyToMsgId != null) {
                    Text(
                        "↩ replying to an earlier message",
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = textColor.copy(alpha = 0.6f),
                    )
                }
                Text(msg.text, color = textColor, style = MaterialTheme.typography.bodyMedium)
            }

            // Aggregated reaction chips. Tapping one toggles OUR entry:
            // apply if we're not in it, retract if we are — the two
            // idempotent operations the wire format defines, never a
            // blind toggle.
            if (reactions.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for ((emoji, senders) in reactions) {
                        val mine = ourIdentityHex.isNotEmpty() && ourIdentityHex in senders
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (mine) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .combinedClickable(
                                    onClick = { if (canReact) onReact(emoji) },
                                    onLongClick = { showReactors = true },
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(emoji, style = MaterialTheme.typography.labelMedium)
                            if (senders.size > 1) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "${senders.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (canReact) {
                    // Signal-style tap-back palette, shared with the
                    // direct-message bubbles.
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        REACTION_PALETTE.forEach { emoji ->
                            Text(
                                emoji,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .clickable { menuOpen = false; onReact(emoji) }
                                    .padding(horizontal = 6.dp),
                            )
                        }
                        Text(
                            "+",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { menuOpen = false; showPicker = true }
                                .padding(horizontal = 6.dp),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (canAnchor) {
                    DropdownMenuItem(
                        text = { Text("Reply") },
                        onClick = { menuOpen = false; onReply() },
                    )
                }
                if (reactions.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Who reacted") },
                        onClick = { menuOpen = false; showReactors = true },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Copy text") },
                    onClick = { menuOpen = false; onCopy() },
                )
            }
        }
    }

    if (showReactors) {
        ReactorsDialog(
            reactions = reactions,
            nicks = nicks,
            ourIdentityHex = ourIdentityHex,
            onDismiss = { showReactors = false },
        )
    }

    // The full system emoji grid, same component the direct-message
    // bubbles use. Anything picked flows into the same onReact path.
    if (showPicker) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showPicker = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 360.dp),
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        androidx.emoji2.emojipicker.EmojiPickerView(ctx).apply {
                            setOnEmojiPickedListener { picked ->
                                showPicker = false
                                onReact(picked.emoji)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Who reacted, per emoji.
 *
 * RRC can answer this precisely, which is worth saying out loud: a
 * reaction's `K_SRC` is rewritten by the hub to the *link-verified*
 * identity before fan-out, so attribution is exactly as trustworthy as
 * message authorship (`rrc-extensions.md` §3) — a stronger guarantee
 * than most chat systems give it, and stronger than the LXMF side,
 * where a re-originating relay had to have attribution restored.
 *
 * A nick is shown when one has been seen from that identity, but the
 * hash is the identity: nicknames are advisory and not unique.
 */
@Composable
private fun ReactorsDialog(
    reactions: Map<String, List<String>>,
    nicks: Map<String, String>,
    ourIdentityHex: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reactions") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                for ((emoji, senders) in reactions) {
                    item(key = "e-$emoji") {
                        Text(
                            "$emoji  ${senders.size}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(senders, key = { "$emoji-$it" }) { hash ->
                        Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                            Text(
                                when {
                                    hash == ourIdentityHex -> "You"
                                    else -> nicks[hash] ?: "(no nick seen)"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                shortHash(hash),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The message a reply is answering, shown inside the reply's bubble. */
@Composable
private fun QuotedLine(quoted: StoredRrcMessage, textColor: Color) {
    Row(
        Modifier.padding(bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = 16.dp)
                .background(textColor.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                quoted.nick?.takeIf { it.isNotBlank() } ?: shortSender(quoted),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.8f),
            )
            Text(
                quoted.text.take(120),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f),
                maxLines = 2,
            )
        }
    }
}

/** A hub NOTICE / ERROR, or a `/`-command the user ran — centred, and
 *  monospaced when it is a multi-line dump (`/help`, `/who`, `/stats`
 *  are column-aligned by the hub and unreadable in a proportional
 *  font). */
@Composable
private fun SystemLine(msg: StoredRrcMessage) {
    val isError = msg.direction == "error"
    val multiline = msg.text.contains('\n')
    val color =
        if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    if (multiline) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                msg.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                modifier = Modifier.padding(10.dp),
            )
        }
        return
    }
    Text(
        msg.text,
        style = MaterialTheme.typography.labelMedium,
        fontStyle = FontStyle.Italic,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

// ---- timeline model ----------------------------------------------------

/** One rendered row: a message, a day heading, or the unread marker. */
internal sealed interface RoomRowItem {
    val key: String

    /** [afterId] is the row it precedes — the key has to be unique, and
     *  the label is not: RRC timestamps come from every member's own
     *  clock, so a skewed sender can walk the calendar day backwards
     *  and produce the same label twice in one transcript. */
    data class DaySeparator(val label: String, val afterId: Long) : RoomRowItem {
        override val key get() = "d:$afterId"
    }

    data object UnreadMarker : RoomRowItem {
        override val key get() = "unread"
    }

    /** [grouped] hides the sender/time header — this line continues a
     *  run from the same sender. */
    data class Line(val msg: StoredRrcMessage, val grouped: Boolean) : RoomRowItem {
        override val key get() = "m:${msg.id}"
    }
}

/**
 * Interleave day headings and the unread marker into [messages], and
 * decide which lines continue a run from the same sender.
 *
 * Messages are already in the hub's fan-out order (row id); timestamps
 * come from each sender's own clock and are used only for display and
 * for the day headings, which is why a heading is emitted on a *change*
 * of calendar day rather than by sorting on time.
 */
internal fun buildRoomRows(
    messages: List<StoredRrcMessage>,
    unreadAfterId: Long?,
): List<RoomRowItem> {
    val rows = ArrayList<RoomRowItem>(messages.size + 8)
    var lastDay: String? = null
    var markerPlaced = unreadAfterId == null
    var prev: StoredRrcMessage? = null
    for (msg in messages) {
        val day = dayKey(msg.timestamp)
        if (day != null && day != lastDay) {
            rows.add(RoomRowItem.DaySeparator(formatRrcDay(msg.timestamp), msg.id))
            lastDay = day
            prev = null
        }
        if (!markerPlaced && msg.id > (unreadAfterId ?: 0L) && msg.direction == "incoming") {
            rows.add(RoomRowItem.UnreadMarker)
            markerPlaced = true
            prev = null
        }
        val p = prev
        val grouped = p != null &&
            p.direction == msg.direction &&
            p.senderIdHash == msg.senderIdHash &&
            p.nick == msg.nick &&
            !msg.text.startsWith("/me") &&
            msg.direction != "system" && msg.direction != "error" &&
            kotlin.math.abs(msg.timestamp - p.timestamp) < GROUPING_WINDOW_MS
        rows.add(RoomRowItem.Line(msg, grouped))
        prev = msg
    }
    return rows
}

/** Consecutive messages from one sender inside this window share a header. */
private const val GROUPING_WINDOW_MS = 5 * 60 * 1000L

/**
 * Sender label for a message with no nick — the hash prefix the hub
 * itself uses when it has no nick to show.
 *
 * **Do not resolve this against the contacts table.** RRC is
 * hub-mediated: `K_SRC` is rewritten by the hub before fan-out, so a
 * compromised hub can attribute any room message to any identity. Today
 * that only ever renders as a hub-supplied nick or a bare hash prefix,
 * which is honest about how much it is worth. The moment a room line
 * starts wearing a name resolved from our own signature-verified
 * address book, a hostile hub can make a stranger's words appear to
 * come from a trusted contact. Audit reference: 2026-08-31 F8
 * (accepted, on the condition that this stays true).
 */
private fun shortSender(msg: StoredRrcMessage): String =
    msg.senderIdHash.take(8).ifEmpty { "(unknown)" }

/** Last nick seen per sender hash, for the member roster. */
private fun nicksByHash(messages: List<StoredRrcMessage>): Map<String, String> {
    val out = HashMap<String, String>()
    for (m in messages) {
        val nick = m.nick?.takeIf { it.isNotBlank() } ?: continue
        if (m.senderIdHash.isNotEmpty()) out[m.senderIdHash] = nick
    }
    return out
}

// ---- shared bits -------------------------------------------------------

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    state: RrcHubState?,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusDot(state)
    }
}

@Composable
private fun RoomTopicBar(meta: RrcRoomMeta?) {
    val topic = meta?.topic
    val modes = meta?.modes.orEmpty()
    // Nothing structured known for this room — keep the chat flush to
    // the header rather than showing an empty bar.
    if (topic == null && modes.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = topic ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (modes.isNotEmpty()) {
            Text(
                modes,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeBanner(notice: String?, onDismiss: () -> Unit) {
    if (notice == null) return
    // Informational, not an error — a hub WELCOME / MOTD. Uses the
    // neutral secondary-container role so it never reads as a failure
    // (red is reserved for genuine errors). See docs/REDESIGN.md §1.
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Clear,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun StatusDot(state: RrcHubState?) {
    val color = when {
        state?.welcomed == true -> Color(0xFF1D9E75)
        state?.connecting == true -> Color(0xFFE0A33A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

private fun statusLabel(state: RrcHubState?): String = when {
    state == null -> "Offline"
    state.welcomed -> "Connected"
    state.connecting -> "Connecting…"
    state.state == RrcState.CLOSED -> "Disconnected"
    else -> "Offline"
}

// Time on a chat line is the clock only — the day is carried by the
// separator above it, which is both less repetition and the shape every
// other chat app has trained people to read.
private val rrcClockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val rrcDayFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

private fun formatRrcClock(ts: Long): String =
    if (ts <= 0L) "" else rrcClockFormat.format(Date(ts))

/** Day heading for a message's timestamp — "Today" / "Yesterday" and
 *  the weekday + date before that. */
private fun formatRrcDay(ts: Long): String {
    if (ts <= 0L) return ""
    val today = dayKey(System.currentTimeMillis())
    val yesterday = dayKey(System.currentTimeMillis() - 86_400_000L)
    return when (dayKey(ts)) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> rrcDayFormat.format(Date(ts))
    }
}

/**
 * Calendar-day identity of [ts] in the device's own time zone, or null
 * for a timestamp we won't head a day with.
 *
 * A clockless LoRa sender puts seconds-since-boot in `K_TS` (see
 * CLAUDE.md — "clockless sender timestamps"), which lands in 1970 and
 * would otherwise open the room with a heading fifty years out of date.
 * Those lines join whatever day they arrive under.
 */
private fun dayKey(ts: Long): String? {
    if (ts < CLOCK_SANITY_FLOOR_MS) return null
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
}

/** 2020-01-01 — anything before this is a sender with no real clock. */
private const val CLOCK_SANITY_FLOOR_MS = 1_577_836_800_000L
