package io.github.thatsfguy.reticulum.android.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.reticulum.android.platform.ImageCompress
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            onToggleFavorite = { hash, fav -> viewModel.toggleFavorite(hash, fav) },
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
    onToggleFavorite: (String, Boolean) -> Unit,
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
                ThreadRow(dest, onPick, onRequestDelete, onToggleFavorite)
            }
        }
        if (inbox.isNotEmpty()) {
            item("inbox_header") { SectionHeader("Inbox") }
            items(inbox, key = { "inbox-${it.hash}" }) { dest ->
                ThreadRow(dest, onPick, onRequestDelete, onToggleFavorite)
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
    onToggleFavorite: (String, Boolean) -> Unit,
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
        IconButton(onClick = { onToggleFavorite(dest.hash, !dest.favorite) }) {
            Icon(
                Icons.Default.Star,
                contentDescription = if (dest.favorite) "Unfavorite" else "Favorite",
                tint = if (dest.favorite)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
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
    // LocalSoftwareKeyboardController is the Compose primitive for
    // imperative keyboard dismissal — equivalent to UIKit's
    // resignFirstResponder. Used by the Send button below so tapping
    // Send minimises the IME the way iMessage / WhatsApp do, matching
    // the iOS app shipped in v1.0.13.
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // Image-attach state. `pendingImage` is the already-compressed JPEG
    // bytes (≤ 20 KB) ready to ship as LXMF field 6 alongside the next
    // Send; `compressing` shows a spinner during the decode + ladder
    // walk; `imageError` surfaces a user-visible refusal when even the
    // smallest ladder step can't fit. All three are conversation-local
    // and cleared when the user switches threads (the `remember` block
    // is keyed on the Composable identity, which recomposes per dest).
    var pendingImage by remember { mutableStateOf<ByteArray?>(null) }
    var compressing by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // PickVisualMedia (Android 13 photo picker, polyfilled on older
    // versions by Google Play Services) — read-only, scoped to the
    // chosen image, NO storage permission required. The launcher
    // returns a Uri we then push through ImageCompress on a background
    // dispatcher before stashing the bytes in `pendingImage`.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        compressing = true
        imageError = null
        scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                ImageCompress.compressForLxmf(uri, context.contentResolver)
            }
            compressing = false
            if (bytes == null) {
                pendingImage = null
                imageError = "Image too large to send (max 20 KB after compression)."
            } else {
                pendingImage = bytes
                imageError = null
            }
        }
    }

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
            // Reaction-only outbound rows (direction = "outgoing-reaction")
            // exist for delivery-state tracking only — they're already
            // applied to the target row's reactionsJson by sendReaction.
            // Filter them out of the bubble feed so the user doesn't
            // see a phantom empty bubble for every reaction they sent.
            val bubbles = messages.filter { it.direction != "outgoing-reaction" }
            items(bubbles, key = { it.id }) { msg ->
                MessageBubble(
                    msg = msg,
                    onReact = { emoji ->
                        val targetMsgId = msg.messageId
                        if (targetMsgId != null) {
                            viewModel.sendReaction(dest.hash, targetMsgId, emoji)
                        }
                    },
                )
            }
        }

        // Preview chip for the pending image (or compress-error
        // message) — sits between the LazyColumn and the input Row so
        // the user can confirm what they're about to attach + dismiss
        // it with the × button before pressing Send.
        ImageAttachmentRow(
            pendingImage = pendingImage,
            compressing = compressing,
            imageError = imageError,
            onClear = {
                pendingImage = null
                imageError = null
            },
        )

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Attach-image (+) IconButton. Material core ships only a
            // small icon set — Add is the closest visual match for an
            // "attach" affordance without pulling in icons-extended.
            IconButton(
                onClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !compressing,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Attach image",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message ${dest.effectiveDisplayName.ifBlank { "" }}".trim()) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            // Send is enabled when there's a draft OR a pending image
            // (image-only sends are valid LXMF — title and content
            // can both be empty if `fields[6]` carries the payload).
            val canSend = (draft.isNotBlank() || pendingImage != null) && !compressing
            IconButton(
                onClick = {
                    if (!canSend) return@IconButton
                    viewModel.sendMessage(draft.trim(), pendingImage)
                    draft = ""
                    pendingImage = null
                    imageError = null
                    // Send was the user's "I'm done typing" cue —
                    // collapse the IME so they're back to the
                    // conversation view, matching iMessage and the
                    // iOS counterpart shipped in v1.0.13.
                    keyboardController?.hide()
                },
                enabled = canSend,
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (canSend)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }
        }
    }
}

/** Signal-style tap-back palette. Six emoji is the sweet spot —
 *  enough breadth that the user usually finds the right one without
 *  drilling into a full picker, but small enough to render inline
 *  on a narrow phone without scrolling. Order is by rough usage
 *  frequency: thumb / heart for affirmation, laugh / surprise / sad
 *  for reactions to content, hands as a generic acknowledgement. */
internal val REACTION_PALETTE: List<String> =
    listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: StoredMessage,
    onReact: (emoji: String) -> Unit,
) {
    val outgoing = msg.direction == "outgoing"
    // MED-6 affordance: an "unverified" incoming bubble means the
    // signature on the LXMF body couldn't be matched against any
    // known announce yet. An attacker can craft this from an
    // attacker-chosen display name on first contact. Tint the
    // bubble background amber and prepend a warning row so the user
    // can't mistake it for a vouched-for message. Audit reference:
    // 2026-05-13 MED-6.
    val unverified = !outgoing && msg.state == "unverified"
    val bg = when {
        outgoing -> MaterialTheme.colorScheme.primary
        // Amber tint over the surface — 0x22 alpha keeps the text
        // readable while making the bubble visibly distinct from
        // verified-sender messages. ARGB order: alpha in the top
        // byte, so 0x22FFB300 = ~13% opacity amber.
        unverified -> androidx.compose.ui.graphics.Color(0x22FFB300).compositeOver(
            MaterialTheme.colorScheme.surface
        )
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val align = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart

    // Decoded bitmap cached behind the row's identity. `remember(msg.id)`
    // means a new row's bytes decode once; the same row scrolling in
    // and out of the viewport reuses the cached Bitmap instead of
    // re-decoding ~10 KB of JPEG every recomposition.
    val imageBitmap = msg.imageBytes?.let { bytes ->
        remember(msg.id) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }
    var showZoom by remember(msg.id) { mutableStateOf(false) }
    // Long-press-to-react: a Popup anchored to the bubble Box.
    // Gated on msg.messageId != null because reactions need a
    // target id, and pre-1.1.33 rows don't carry one. Without
    // a target, the long-press is a no-op (no popup shown).
    var showReactionPicker by remember(msg.id) { mutableStateOf(false) }
    val canReact = msg.messageId != null

    // Decode the reactions JSON once per change; same `remember` key
    // pattern as the image decode above.
    val reactions = remember(msg.reactionsJson) {
        io.github.thatsfguy.reticulum.store.ReactionsJson.decode(msg.reactionsJson)
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .clip(RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (outgoing) 14.dp else 4.dp,
                    bottomEnd   = if (outgoing) 4.dp else 14.dp,
                ))
                .background(bg)
                .let { mod ->
                    if (unverified) mod.border(
                        width = 1.dp,
                        color = androidx.compose.ui.graphics.Color(0xFFFFB300),
                        shape = RoundedCornerShape(
                            topStart = 14.dp, topEnd = 14.dp,
                            bottomStart = 4.dp, bottomEnd = 14.dp,
                        ),
                    ) else mod
                }
                .let { mod ->
                    if (canReact) mod.combinedClickable(
                        onClick = {},
                        onLongClick = { showReactionPicker = true },
                    ) else mod
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .wrapContentSize(),
        ) {
            if (unverified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⚠ Unverified sender",
                        color = androidx.compose.ui.graphics.Color(0xFFFFB300),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            if (msg.title.isNotEmpty()) {
                Text(msg.title, style = MaterialTheme.typography.labelMedium, color = fg)
                Spacer(Modifier.height(2.dp))
            }
            // Image renders ABOVE the text content — matches iMessage /
            // WhatsApp layout (caption-below-image). Image-only messages
            // (no text content) still get the bubble background; the
            // empty Text below collapses cleanly because content == "".
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap.asImageBitmap(),
                    contentDescription = "Attached image",
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showZoom = true },
                )
                if (msg.content.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (msg.content.isNotEmpty()) {
                Text(linkify(msg.content, fg), color = fg)
            }
            // Partial-delivery indicator. The engine writes the
            // IMAGE_DROPPED_MARKER prefix to lastError when an
            // image-bearing send had to fall back to opportunistic
            // (which strips images). The PROOF eventually flips state
            // to "delivered" but leaves lastError untouched, so this
            // condition holds for the lifetime of the row. The local
            // image bitmap stays rendered above — the sender DID try
            // to send it; we just couldn't get it to the recipient.
            val imageDropped = outgoing
                && msg.state == "delivered"
                && msg.lastError?.startsWith("image dropped — ") == true
            if (imageDropped) {
                Spacer(Modifier.height(4.dp))
                // Amber 700 — readable against both primary-tinted
                // outgoing bubbles (this warning only fires on
                // outgoing) AND surface-tinted ones (other themes /
                // future surface-only outgoing styling). Material 3
                // doesn't expose amber natively; inlining the hex is
                // simpler than registering a custom theme token for
                // a single one-line warning.
                Text(
                    "⚠ Image not delivered — link unreachable, text only",
                    color = androidx.compose.ui.graphics.Color(0xFFFFB300),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
            // Aggregated reactions, rendered as `👍 2` chips below
            // the time row. Empty `reactions` collapses cleanly —
            // no spacing artifact for messages without reactions.
            if (reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for ((emoji, senders) in reactions) {
                        Row(
                            modifier = Modifier
                                .background(
                                    fg.copy(alpha = 0.1f),
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(emoji, style = MaterialTheme.typography.bodySmall)
                            if (senders.size > 1) {
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    senders.size.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = fg.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
        // Reaction picker popup. Anchored to the bubble's Box;
        // dismisses on outside-tap. Renders the six-emoji palette
        // in a horizontal row matching Signal's tap-back affordance.
        if (showReactionPicker && canReact) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, -120),
                onDismissRequest = { showReactionPicker = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(24.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(24.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (emoji in REACTION_PALETTE) {
                        Text(
                            text = emoji,
                            modifier = Modifier
                                .clickable {
                                    showReactionPicker = false
                                    onReact(emoji)
                                }
                                .padding(8.dp),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
        }
    }

    // Full-screen zoom dialog. Image bitmap is reused from above
    // (already decoded) so opening is instant — no re-decode hitch.
    if (showZoom && imageBitmap != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showZoom = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
                    .clickable { showZoom = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = imageBitmap.asImageBitmap(),
                    contentDescription = "Attached image (full size)",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/**
 * Renders one of three states above the compose Row:
 *   - [compressing] = true → a small spinner + "Compressing…" label
 *   - [pendingImage] != null → a thumbnail of the compressed JPEG,
 *     the rendered byte size, and an × button to discard
 *   - [imageError] != null → a red error caption (e.g. "Image too
 *     large to send"). The row collapses entirely when none apply.
 */
@Composable
private fun ImageAttachmentRow(
    pendingImage: ByteArray?,
    compressing: Boolean,
    imageError: String?,
    onClear: () -> Unit,
) {
    when {
        compressing -> {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Compressing image…", style = MaterialTheme.typography.bodySmall)
            }
        }
        pendingImage != null -> {
            // Decode the in-memory JPEG to a Bitmap once per byte-array
            // identity. `remember(pendingImage)` keys on the array ref;
            // a new pick replaces the bytes and re-decodes.
            val thumb = remember(pendingImage) {
                BitmapFactory.decodeByteArray(pendingImage, 0, pendingImage.size)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = "Pending image attachment",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    "${pendingImage.size / 1024} KB image attached",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Remove image",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        imageError != null -> {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    imageError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Dismiss error",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    "queued"      -> "🕒"
    "pending"     -> "⏳"
    "sending"     -> "↑"
    "sent"        -> "✓"
    "delivered"   -> "✓✓"
    "failed"      -> "✗"
    else          -> ""
}

// Conservative URL regex — requires an explicit http:// or https://
// scheme so we don't auto-link bare domain text the user typed
// without intent. Trailing punctuation that's almost never part of a
// URL is trimmed by the caller (sentence-end period, comma, paren).
private val URL_PATTERN = Regex(
    """https?://[^\s<>"'\]]+""",
    RegexOption.IGNORE_CASE,
)

/** Trim trailing punctuation that almost certainly isn't part of the
 *  URL ("see https://example.com." → URL ends before the period). */
private fun trimTrailingPunctuation(url: String): String {
    var end = url.length
    while (end > 0 && url[end - 1] in ".,;:!?)]}>") end--
    return url.substring(0, end)
}

/** Wrap http(s) substrings in [LinkAnnotation.Url] so Compose's Text
 *  renders them tappable. The link style underlines + tints the URL
 *  span using the bubble foreground at full alpha; the surrounding
 *  text inherits the caller's color via the outer Text. */
private fun linkify(content: String, fg: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in URL_PATTERN.findAll(content)) {
        if (match.range.first > cursor) {
            append(content.substring(cursor, match.range.first))
        }
        val rawUrl = match.value
        val cleanUrl = trimTrailingPunctuation(rawUrl)
        withLink(LinkAnnotation.Url(cleanUrl)) {
            withStyle(
                SpanStyle(
                    color = fg,
                    textDecoration = TextDecoration.Underline,
                )
            ) {
                append(cleanUrl)
            }
        }
        // Anything we trimmed (trailing punctuation) belongs in the
        // plain run, not in the link.
        if (cleanUrl.length < rawUrl.length) {
            append(rawUrl.substring(cleanUrl.length))
        }
        cursor = match.range.last + 1
    }
    if (cursor < content.length) append(content.substring(cursor))
}
