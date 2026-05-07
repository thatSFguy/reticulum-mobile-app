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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredMessage

@Composable
fun MessagesScreen(viewModel: ReticulumViewModel) {
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    val inbox by viewModel.inbox.collectAsState(initial = emptyList())
    val allDestinations by viewModel.allDestinations.collectAsState(initial = emptyList())
    val selectedHash by viewModel.selectedDestination.collectAsState()
    // Fall back to the global destinations list when the selected hash
    // is neither a favorite nor an inbox sender — e.g. the user just
    // tapped a row on the Nodes tab to start a conversation with a
    // peer they haven't favorited yet. The threads list still shows
    // only favorites + inbox; the override is conversation-view only.
    val selected = selectedHash?.let { hash ->
        (favorites + inbox).firstOrNull { it.hash == hash }
            ?: allDestinations.firstOrNull { it.hash == hash }
    }
    var pendingNodeDelete by remember { mutableStateOf<StoredDestination?>(null) }

    if (selected == null) {
        ThreadsList(
            favorites = favorites,
            inbox = inbox,
            onPick = { hash -> viewModel.selectDestination(hash) },
            onRequestDelete = { dest -> pendingNodeDelete = dest },
        )
    } else {
        ConversationView(viewModel, selected, onBack = { viewModel.selectDestination(null) })
    }

    pendingNodeDelete?.let { dest ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingNodeDelete = null },
            title = { Text("Delete this destination?") },
            text = {
                Text(
                    "Removes ${dest.effectiveDisplayName.ifBlank { "(unnamed)" }} from local storage along with " +
                        "all message history. If they announce again later they'll reappear in Nodes " +
                        "(without prior history).",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val hash = dest.hash
                    pendingNodeDelete = null
                    viewModel.deleteDestinationAndMessages(hash)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingNodeDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ThreadsList(
    favorites: List<StoredDestination>,
    inbox: List<StoredDestination>,
    onPick: (String) -> Unit,
    onRequestDelete: (StoredDestination) -> Unit,
) {
    if (favorites.isEmpty() && inbox.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No conversations yet — star a messagable destination on the Nodes tab to bring it here, " +
                    "or wait for someone to message you.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (favorites.isNotEmpty()) {
            item("favorites_header") { SectionHeader("Favorites") }
            items(favorites, key = { "fav-${it.hash}" }) { dest ->
                ThreadRow(dest, onPick, onRequestDelete)
            }
        }
        if (inbox.isNotEmpty()) {
            item("inbox_header") { SectionHeader("Inbox") }
            items(inbox, key = { "inbox-${it.hash}" }) { dest ->
                ThreadRow(dest, onPick, onRequestDelete)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ThreadRow(
    dest: StoredDestination,
    onPick: (String) -> Unit,
    onRequestDelete: (StoredDestination) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onPick(dest.hash) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(dest.effectiveDisplayName.ifBlank { dest.hash.take(2) })
            Spacer(Modifier.width(12.dp))
            Column {
                Text(dest.effectiveDisplayName.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    dest.hash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onRequestDelete(dest) }) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete destination",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ConversationView(viewModel: ReticulumViewModel, dest: StoredDestination, onBack: () -> Unit) {
    val messages by viewModel.messagesForSelected.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    // imePadding() at the column level + windowSoftInputMode=adjustResize
    // in the manifest makes the keyboard shrink the LazyColumn instead of
    // pushing the header off-screen. With weight(1f) on the list, the
    // header and compose row stay anchored to top/bottom respectively
    // and only the messages area shrinks.
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).clickable(onClick = onBack),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("← ", style = MaterialTheme.typography.titleMedium)
                Avatar(dest.effectiveDisplayName.ifBlank { dest.hash.take(2) })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(dest.effectiveDisplayName.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                    Text(dest.hash, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            androidx.compose.material3.TextButton(
                onClick = { showDeleteConfirm = true },
                enabled = messages.isNotEmpty(),
            ) {
                Text(
                    "Clear",
                    color = if (messages.isEmpty())
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.error,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (showDeleteConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Clear conversation?") },
                text = {
                    Text(
                        "Removes ${messages.size} message(s) with " +
                            "${dest.effectiveDisplayName.ifBlank { "this destination" }} from local " +
                            "storage. The destination itself stays in your favorites/inbox " +
                            "(use the trash icon on the threads list to delete the destination too).",
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteMessagesForDestination(dest.hash)
                    }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message ${dest.effectiveDisplayName.ifBlank { "" }}".trim()) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (draft.isNotBlank()) {
                    viewModel.sendMessage(draft.trim())
                    draft = ""
                }
            }) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: StoredMessage) {
    val outgoing = msg.direction == "outgoing"
    val bg = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val align = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .clip(RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (outgoing) 14.dp else 4.dp,
                    bottomEnd   = if (outgoing) 4.dp else 14.dp,
                ))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .wrapContentSize(),
        ) {
            if (msg.title.isNotEmpty()) {
                Text(msg.title, style = MaterialTheme.typography.labelMedium, color = fg)
                Spacer(Modifier.height(2.dp))
            }
            Text(msg.content, color = fg)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(msg.timestamp), style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
                if (outgoing) {
                    Spacer(Modifier.width(6.dp))
                    Text(stateGlyph(msg.state), style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
                }
                // Per-message link metadata on incoming bubbles. RSSI is
                // null when the message arrived via TCP (rnsd doesn't
                // carry radio metrics) or before v0.1.85 (column was
                // populated only opportunistically). hopCount is null
                // for messages saved before the v8 migration.
                if (!outgoing && (msg.rssi != null || msg.hopCount != null)) {
                    val parts = buildList {
                        msg.rssi?.let { add("$it dBm") }
                        msg.hopCount?.let {
                            add("$it hop${if (it != 1) "s" else ""}")
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "· " + parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = fg.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(label: String) {
    val initials = label.take(2).uppercase()
    Box(
        Modifier
            .size(34.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatTime(epochMs: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
}

private fun stateGlyph(state: String?): String = when (state) {
    "pending"     -> "⏳"
    "sending"     -> "↑"
    "sent"        -> "✓"
    "delivered"   -> "✓✓"
    "failed"      -> "✗"
    else          -> ""
}
