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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import io.github.thatsfguy.reticulum.util.shortHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MessagesScreen(viewModel: ReticulumViewModel) {
    val conversations by viewModel.conversations.collectAsState(initial = emptyList())
    val pinned by viewModel.pinnedConversations.collectAsState(initial = emptySet())
    val search by viewModel.messageSearch.collectAsState(initial = "")
    val allDestinations by viewModel.allDestinations.collectAsState(initial = emptyList())
    val selectedHash by viewModel.selectedDestination.collectAsState()
    // Fall back to the global destinations list when the selected hash
    // isn't in the conversation list — e.g. the user just tapped a row
    // on the Nodes tab to start a chat with a peer they've never
    // messaged. The override is conversation-view only.
    val selected = selectedHash?.let { hash ->
        conversations.firstOrNull { it.hash == hash }
            ?: allDestinations.firstOrNull { it.hash == hash }
    }
    var pendingNodeDelete by remember { mutableStateOf<StoredDestination?>(null) }
    var detailDest by remember { mutableStateOf<StoredDestination?>(null) }
    var pendingRename by remember { mutableStateOf<StoredDestination?>(null) }

    if (selected == null) {
        ThreadsList(
            conversations = conversations,
            pinned = pinned,
            search = search,
            onSearch = { viewModel.setMessageSearch(it) },
            onSync = { viewModel.syncPropagationAuto() },
            onPick = { hash -> viewModel.selectDestination(hash) },
            onShowDetail = { dest -> detailDest = dest },
        )
    } else {
        ConversationView(viewModel, selected, onBack = { viewModel.selectDestination(null) })
    }

    // Long-pressing a thread row opens the shared detail sheet.
    detailDest?.let { dest ->
        DestinationDetailSheet(
            dest = dest,
            onDismiss = { detailDest = null },
            onMessage = { hash -> viewModel.selectDestination(hash) },
            onOpenAsRrcHub = null,
            onRename = { d -> pendingRename = d },
            onToggleFavorite = { hash, fav -> viewModel.toggleFavorite(hash, fav) },
            onDelete = { d -> pendingNodeDelete = d },
            pinned = dest.hash in pinned,
            onTogglePin = { hash, p -> viewModel.setPinned(hash, p) },
        )
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

    pendingRename?.let { dest ->
        var draft by remember(dest.hash) { mutableStateOf(dest.userLabel ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Set a private nickname") },
            text = {
                Column {
                    Text(
                        "Stored on this device only — never sent on the wire.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Announced name: ${dest.displayName.ifBlank { "(none)" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        label = { Text("Nickname") },
                    )
                    Text(
                        "Leave empty to clear the nickname.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val h = dest.hash
                    val label = draft.trim().ifBlank { null }
                    pendingRename = null
                    viewModel.setUserLabel(h, label)
                }) { Text("Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingRename = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ThreadsList(
    conversations: List<StoredDestination>,
    pinned: Set<String>,
    search: String,
    onSearch: (String) -> Unit,
    onSync: () -> Unit,
    onPick: (String) -> Unit,
    onShowDetail: (StoredDestination) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                placeholder = { Text("Search conversations") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (search.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearch("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            )
            // Pull queued messages from a propagation node (auto-picks
            // the best one). Was buried in Settings → Connection.
            IconButton(onClick = onSync) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Sync from propagation node",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (conversations.isEmpty()) {
            if (search.isNotBlank()) {
                EmptyState(Icons.Default.Search, "No conversations match \"$search\".")
            } else {
                EmptyState(
                    Icons.Default.Email,
                    "No conversations yet — open a node on the Nodes tab and tap Message.",
                )
            }
        } else {
            // Pinned conversations on top, then the recency-sorted rest.
            val pinnedRows = conversations.filter { it.hash in pinned }
            val rest = conversations.filter { it.hash !in pinned }
            LazyColumn(Modifier.fillMaxSize()) {
                if (pinnedRows.isNotEmpty()) {
                    item("pinned_header") { SectionHeader("Pinned") }
                    items(pinnedRows, key = { "p-${it.hash}" }) { dest ->
                        ThreadRow(dest, onPick, onShowDetail)
                    }
                    if (rest.isNotEmpty()) {
                        item("recent_header") { SectionHeader("Recent") }
                    }
                }
                items(rest, key = { "r-${it.hash}" }) { dest ->
                    ThreadRow(dest, onPick, onShowDetail)
                }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    dest: StoredDestination,
    onPick: (String) -> Unit,
    onShowDetail: (StoredDestination) -> Unit,
) {
    // Tap opens the conversation; long-press opens the shared
    // destination detail sheet (contact / rename / delete actions) —
    // no more inline star + trash crowding the row (REDESIGN.md §6).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onPick(dest.hash) },
                onLongClick = { onShowDetail(dest) },
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(dest.effectiveDisplayName.ifBlank { dest.hash.take(2) })
        Spacer(Modifier.width(12.dp))
        Column {
            Text(dest.effectiveDisplayName.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
            Text(
                shortHash(dest.hash),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    // Reply-to state — populated by swiping right on a bubble.
    // The composer area renders a "Replying to <name>: <preview>"
    // banner above the input field, with an X to cancel. The next
    // Send packages the reply with field 16 = {"reply_to": id}.
    // Audit reference: 2026-05-13 reactions + replies feature.
    var replyingTo by remember(dest.hash) { mutableStateOf<StoredMessage?>(null) }
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
                            "storage. The destination itself stays in your contacts/inbox " +
                            "(long-press it on the threads list to delete the destination too).",
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

        // Reaction-only outbound rows (direction = "outgoing-reaction")
        // exist for delivery-state tracking only — they're already
        // applied to the target row's reactionsJson by sendReaction.
        // Filter them out of the bubble feed so the user doesn't see
        // a phantom empty bubble for every reaction they sent.
        val bubbles = messages.filter { it.direction != "outgoing-reaction" }
        // Quick-lookup map for reply previews. Recomputed when
        // `messages` changes; cheap on the ~hundreds-of-rows scale a
        // conversation lives at. Lifted above LazyColumn because
        // `remember` needs a @Composable context and the
        // `LazyListScope` block isn't one.
        val byMessageId = remember(messages) {
            messages.mapNotNull { m -> m.messageId?.let { it to m } }.toMap()
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(bubbles, key = { it.id }) { msg ->
                MessageBubble(
                    msg = msg,
                    quotedMessage = msg.replyToMessageId?.let { byMessageId[it] },
                    quotedSenderLabel = { other ->
                        // Best-effort label for the quoted message:
                        // "You" for outgoing rows, otherwise the
                        // contact's display name. Falls back to "Peer"
                        // when neither is available.
                        when (other.direction) {
                            "outgoing" -> "You"
                            else -> dest.effectiveDisplayName.ifBlank { "Peer" }
                        }
                    },
                    onReact = { emoji ->
                        val targetMsgId = msg.messageId
                        if (targetMsgId != null) {
                            viewModel.sendReaction(dest.hash, targetMsgId, emoji)
                        }
                    },
                    onSwipeReply = { replyingTo = msg },
                )
            }
        }

        // Reply banner — appears when the user swiped-right on a
        // bubble. Shows the target's sender + content preview, with
        // an X to cancel. The next Send packages the reply with
        // field 16 = {"reply_to": id} per Sideband / Columba
        // convention.
        replyingTo?.let { target ->
            val targetLabel = if (target.direction == "outgoing") "You"
                              else dest.effectiveDisplayName.ifBlank { "Peer" }
            val preview = if (target.content.isNotEmpty()) target.content.take(80)
                          else if (target.imageBytes != null) "📷 Image"
                          else if (target.attachmentBytes != null) "📎 ${target.attachmentName ?: "File"}"
                          else "(empty)"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Replying to $targetLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { replyingTo = null }) {
                    Icon(Icons.Default.Clear, contentDescription = "Cancel reply")
                }
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
                    viewModel.sendMessage(
                        draft.trim(),
                        pendingImage,
                        replyToMessageId = replyingTo?.messageId,
                    )
                    draft = ""
                    pendingImage = null
                    imageError = null
                    replyingTo = null
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

/** Compact human size for a file-attachment chip — "938 B" / "204 KB". */
private fun fileSizeLabel(bytes: Int): String =
    if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: StoredMessage,
    /** The locally-found target of this row's reply, if it's a
     *  reply and the target exists. Used to render the small
     *  quote-preview block at the top of the bubble. Null when
     *  this isn't a reply OR when the target hasn't arrived
     *  locally (in which case we render a "Replying to message
     *  …" fallback rather than the full preview). */
    quotedMessage: StoredMessage? = null,
    /** Label for the quoted message's sender — "You" for outgoing
     *  rows, the contact's display name for incoming. */
    quotedSenderLabel: (StoredMessage) -> String = { "" },
    onReact: (emoji: String) -> Unit,
    /** Invoked when the user swipes right past the threshold —
     *  the conversation view stores `msg` as the reply target
     *  and the composer banner appears above the text input. */
    onSwipeReply: () -> Unit = {},
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
    // re-decoding ~10 KB of JPEG every recomposition. decodeOriented
    // honours any EXIF Orientation tag so portrait/landscape shots
    // render the way they were taken.
    val imageBitmap = msg.imageBytes?.let { bytes ->
        remember(msg.id) { ImageCompress.decodeOriented(bytes) }
    }
    var showZoom by remember(msg.id) { mutableStateOf(false) }
    // Long-press opens an actions popup: Copy (for any text-bearing
    // bubble) plus the tap-back reaction palette. Reactions are gated on:
    //   - msg.messageId != null (reactions need a target id; pre-
    //     1.1.33 rows don't carry one)
    //   - !outgoing (don't let the user react to their own
    //     messages — every reaction costs an LXMF round-trip, and
    //     self-reactions are a UX foot-gun without a clear use case)
    var showActions by remember(msg.id) { mutableStateOf(false) }
    val canReact = msg.messageId != null && !outgoing
    val canCopy = msg.content.isNotEmpty()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // Decode the reactions JSON once per change; same `remember` key
    // pattern as the image decode above.
    val reactions = remember(msg.reactionsJson) {
        io.github.thatsfguy.reticulum.store.ReactionsJson.decode(msg.reactionsJson)
    }

    // Swipe-right-to-reply gesture state. Threshold + visual
    // pull animation. Accumulates horizontal drag; on release, if
    // we crossed the threshold AND there's a target msg_id to
    // reply to, fire onSwipeReply. Pre-1.1.33 rows (null
    // messageId) can't be replied to, so we suppress the gesture
    // for them — keeps the bubble visually stable.
    val canReply = msg.messageId != null
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thresholdPx = with(density) { 64.dp.toPx() }
    var dragOffsetX by remember(msg.id) { androidx.compose.runtime.mutableStateOf(0f) }
    val animatedOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = dragOffsetX,
        label = "swipe-reply-offset-${msg.id}",
    )

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(animatedOffset.toInt(), 0) }
                .let { mod ->
                    if (canReply) {
                        // Modifier.draggable consumes horizontal drags and
                        // surfaces them via a state callback — higher-
                        // level than pointerInput + detectHorizontalDrag-
                        // Gestures, with built-in interaction-source
                        // bookkeeping. We accumulate the rightward-only
                        // drag into dragOffsetX (visual pull) and check
                        // the threshold on stop.
                        val dragState = rememberDraggableState { delta ->
                            if (delta > 0f || dragOffsetX > 0f) {
                                dragOffsetX = (dragOffsetX + delta)
                                    .coerceIn(0f, thresholdPx * 1.5f)
                            }
                        }
                        mod.draggable(
                            state = dragState,
                            orientation = Orientation.Horizontal,
                            onDragStopped = {
                                if (dragOffsetX >= thresholdPx) onSwipeReply()
                                dragOffsetX = 0f
                            },
                        )
                    } else mod
                }
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
                    if (canReact || canCopy) mod.combinedClickable(
                        onClick = {},
                        onLongClick = { showActions = true },
                    ) else mod
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .wrapContentSize(),
        ) {
            // Reply-preview block at the top of a reply bubble.
            // When we have the target locally, show the sender label
            // + truncated content. When we don't (target never
            // arrived), show a faded "Replying to a message…"
            // fallback so the user knows it WAS a reply even
            // without context. Audit reference: 2026-05-13.
            if (msg.replyToMessageId != null) {
                val quotedText = quotedMessage?.let {
                    val label = quotedSenderLabel(it)
                    val preview = if (it.content.isNotEmpty()) it.content.take(80)
                                  else if (it.imageBytes != null) "📷 Image"
                                  else if (it.attachmentBytes != null) "📎 ${it.attachmentName ?: "File"}"
                                  else "(empty)"
                    "$label: $preview"
                } ?: "Replying to a message…"
                Column(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .background(
                            fg.copy(alpha = 0.08f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        quotedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = fg.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
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
            // LXMF file attachment (FIELD_FILE_ATTACHMENTS, SPEC §5.9.7)
            // — a tappable chip. Tapping opens the system document
            // picker (SAF) so the user explicitly chooses where the
            // file lands; the bytes are never auto-opened or
            // auto-saved. The file name was sanitised on receive
            // (engine/sanitizeAttachmentName).
            val attachBytes = msg.attachmentBytes
            if (attachBytes != null) {
                val attachName = msg.attachmentName ?: "attachment"
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val saveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use { it.write(attachBytes) }
                        }
                    }
                }
                if (msg.content.isNotEmpty() || imageBitmap != null) {
                    Spacer(Modifier.height(6.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(fg.copy(alpha = 0.10f))
                        .clickable { saveLauncher.launch(attachName) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("📎", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            attachName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = fg,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "${fileSizeLabel(attachBytes.size)} · tap to save",
                            style = MaterialTheme.typography.labelSmall,
                            color = fg.copy(alpha = 0.7f),
                        )
                    }
                }
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
        // Long-press actions popup. Anchored to the bubble's Box;
        // dismisses on outside-tap. Shows a Copy action for any bubble
        // carrying text, plus the six-emoji tap-back palette for
        // reactable (incoming) messages.
        if (showActions && (canReact || canCopy)) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, -120),
                onDismissRequest = { showActions = false },
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
                    if (canCopy) {
                        Text(
                            text = "Copy",
                            modifier = Modifier
                                .clickable {
                                    showActions = false
                                    clipboard.setText(AnnotatedString(msg.content))
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (canReact) {
                        for (emoji in REACTION_PALETTE) {
                            Text(
                                text = emoji,
                                modifier = Modifier
                                    .clickable {
                                        showActions = false
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
                // fillMaxSize + ContentScale.Fit grows the image to the
                // largest size that fits the screen while preserving its
                // aspect ratio — a tall portrait shot fills the height, a
                // wide landscape one fills the width, neither is cropped
                // or stretched.
                Image(
                    bitmap = imageBitmap.asImageBitmap(),
                    contentDescription = "Attached image (full size)",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
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
