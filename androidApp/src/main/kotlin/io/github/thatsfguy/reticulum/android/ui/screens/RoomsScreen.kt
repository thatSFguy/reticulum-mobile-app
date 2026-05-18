package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel.RrcHubState
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel.RrcRoomMeta
import io.github.thatsfguy.reticulum.rrc.RrcRoomListing
import io.github.thatsfguy.reticulum.engine.RrcState
import io.github.thatsfguy.reticulum.store.StoredRrcHub
import io.github.thatsfguy.reticulum.store.StoredRrcMessage
import io.github.thatsfguy.reticulum.store.StoredRrcRoom
import io.github.thatsfguy.reticulum.util.shortHash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Experimental Reticulum Relay Chat (RRC) screen. Three nested views,
 * navigated with plain local state (no NavController):
 *
 *  - hub list      — known hubs + add / delete
 *  - hub detail    — connect to a hub, see / join its rooms
 *  - room chat     — message history + compose box
 *
 * The whole tab is gated by the `experimentalRrc` preference in
 * MainActivity, so this screen is only reachable when the user has
 * opted into the experimental feature.
 */
@Composable
fun RoomsScreen(viewModel: ReticulumViewModel) {
    val hubs by viewModel.rrcHubs.collectAsState(initial = emptyList())
    val hubStates by viewModel.rrcHubStates.collectAsState()

    var selectedHub by remember { mutableStateOf<String?>(null) }
    var selectedRoom by remember { mutableStateOf<String?>(null) }

    // A deleted hub (or one cleared out from under us) drops the user
    // back to the list rather than rendering a detail view for nothing.
    val hub = hubs.firstOrNull { it.destHash == selectedHub }
    if (selectedHub != null && hub == null) {
        selectedHub = null
        selectedRoom = null
    }

    when {
        hub == null ->
            HubListView(
                hubs = hubs,
                hubStates = hubStates,
                onPick = { selectedHub = it; selectedRoom = null },
                onAdd = viewModel::addRrcHub,
                onDelete = viewModel::deleteRrcHub,
            )

        selectedRoom != null ->
            RoomChatView(
                viewModel = viewModel,
                hub = hub,
                room = selectedRoom!!,
                state = hubStates[hub.destHash],
                onBack = { selectedRoom = null },
            )

        else ->
            HubDetailView(
                viewModel = viewModel,
                hub = hub,
                state = hubStates[hub.destHash],
                onBack = { selectedHub = null },
                onOpenRoom = { selectedRoom = it },
            )
    }
}

// ---- hub list ----------------------------------------------------------

@Composable
private fun HubListView(
    hubs: List<StoredRrcHub>,
    hubStates: Map<String, RrcHubState>,
    onPick: (String) -> Unit,
    onAdd: (String, String, String?) -> Unit,
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

        if (hubs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No RRC hubs yet. Add a hub by its 32-character destination hash to start " +
                            "chatting in rooms.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = { showAdd = true }) { Text("Add a hub") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(hubs, key = { it.destHash }) { h ->
                    HubRow(
                        hub = h,
                        state = hubStates[h.destHash],
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
            onDismiss = { showAdd = false },
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

@Composable
private fun HubRow(
    hub: StoredRrcHub,
    state: RrcHubState?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state)
            Spacer(Modifier.width(12.dp))
            Column {
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
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete hub",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddHubDialog(
    onDismiss: () -> Unit,
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

@Composable
private fun RoomRow(
    room: StoredRrcRoom,
    welcomed: Boolean,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(14.dp),
        ) {
            Text("#${room.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                if (room.joined) "Joined" else "Not joined",
                style = MaterialTheme.typography.labelSmall,
                color = if (room.joined)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (welcomed) {
            if (room.joined) {
                TextButton(onClick = onLeave) { Text("Leave") }
            } else {
                TextButton(onClick = onJoin) { Text("Join") }
            }
        }
        // Remove the room from local storage — housekeeping, works
        // whether or not the hub is connected.
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove room",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- room chat ---------------------------------------------------------

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
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    // Keep the newest message in view as history grows.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        DetailHeader(
            title = "#$room",
            subtitle = state?.hubName ?: hub.displayName,
            state = state,
            onBack = onBack,
        )
        NoticeBanner(state?.lastNotice, onDismiss = { viewModel.clearRrcNotice(hub.destHash) })
        RoomTopicBar(state?.roomMeta?.get(room))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No messages in #$room yet.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message #$room") },
                enabled = state?.welcomed == true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = state?.welcomed == true && draft.isNotBlank(),
                onClick = {
                    viewModel.sendRrcMessage(hub.destHash, room, draft)
                    draft = ""
                },
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
        if (state?.welcomed != true) {
            Text(
                "Not connected — reconnect to the hub to send messages.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: StoredRrcMessage) {
    // A `system` row is a /-command the user ran, or the hub's reply to
    // one — rendered as a centred italic line, not a chat bubble.
    if (msg.direction == "system") {
        Text(
            msg.text,
            style = MaterialTheme.typography.labelMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        )
        return
    }
    val outgoing = msg.direction == "outgoing"
    val bubbleColor = if (outgoing)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (outgoing)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                buildString {
                    append(msg.nick ?: msg.senderIdHash.take(8))
                    append("  ")
                    append(formatRrcTime(msg.timestamp))
                },
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
            )
            Text(msg.text, color = textColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
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

private val rrcTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

private fun formatRrcTime(ts: Long): String =
    if (ts <= 0L) "" else rrcTimeFormat.format(Date(ts))
