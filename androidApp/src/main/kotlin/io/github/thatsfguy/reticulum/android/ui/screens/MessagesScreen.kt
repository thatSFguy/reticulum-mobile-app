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
    val selectedHash by viewModel.selectedDestination.collectAsState()
    val selected = favorites.firstOrNull { it.hash == selectedHash }

    if (selected == null) {
        FavoritesList(favorites) { hash -> viewModel.selectDestination(hash) }
    } else {
        ConversationView(viewModel, selected, onBack = { viewModel.selectDestination(null) })
    }
}

@Composable
private fun FavoritesList(favorites: List<StoredDestination>, onPick: (String) -> Unit) {
    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No favorites yet — star a messagable destination on the Nodes tab to bring it here.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(favorites, key = { it.hash }) { dest ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(dest.hash) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(dest.displayName.ifBlank { dest.hash.take(2) })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(dest.displayName.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        dest.hash,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ConversationView(viewModel: ReticulumViewModel, dest: StoredDestination, onBack: () -> Unit) {
    val messages by viewModel.messagesForSelected.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onBack).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("← ", style = MaterialTheme.typography.titleMedium)
            Avatar(dest.displayName.ifBlank { dest.hash.take(2) })
            Spacer(Modifier.width(12.dp))
            Column {
                Text(dest.displayName.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                Text(dest.hash, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                placeholder = { Text("Message ${dest.displayName.ifBlank { "" }}".trim()) },
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
