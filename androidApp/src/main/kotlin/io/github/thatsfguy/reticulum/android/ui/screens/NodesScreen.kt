package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.thatsfguy.reticulum.android.platform.PortraitCaptureActivity
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.util.avatarColors
import io.github.thatsfguy.reticulum.util.shortHash

/** Which pane the Nodes tab shows. `Graph` is the former standalone
 *  bottom-nav tab, folded in here to free a nav slot. */
private enum class NodesPane { Nodes, Graph }

@Composable
fun NodesScreen(viewModel: ReticulumViewModel) {
    val filter by viewModel.nodeFilter.collectAsState()
    val search by viewModel.nodeSearch.collectAsState()
    val rows by viewModel.filteredDestinations.collectAsState(initial = emptyList())
    // Drives the per-row "open in Relay Chat" action on rrc.hub rows;
    // hidden entirely when the experimental RRC feature is off.
    val rrcEnabled by viewModel.experimentalRrc.collectAsState(initial = false)

    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StoredDestination?>(null) }
    var deleteTarget by remember { mutableStateOf<StoredDestination?>(null) }
    var pane by remember { mutableStateOf(NodesPane.Nodes) }

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (!text.isNullOrBlank()) {
            // Try as IdentityCard JSON first; fall back to bare hex destination hash.
            val trimmed = text.trim()
            if (trimmed.startsWith("{")) {
                viewModel.applyScannedQr(trimmed)
            } else {
                // Bare hash → manual stub
                viewModel.addManualDestination(trimmed, label = "(QR scan)")
            }
        }
    }

    fun launchScan() {
        qrLauncher.launch(ScanOptions().apply {
            setPrompt("Scan a Reticulum identity QR")
            setBeepEnabled(false)
            // Force portrait via a manifest-locked capture activity.
            // setOrientationLocked(true) only re-locks the stock
            // CaptureActivity to whatever orientation the device was in
            // at launch — held in landscape, the scanner opened
            // sideways. PortraitCaptureActivity pins
            // screenOrientation=portrait in the manifest instead.
            setCaptureActivity(PortraitCaptureActivity::class.java)
            setOrientationLocked(false)
        })
    }

    Column(Modifier.fillMaxSize()) {
        // ── Header: one row — Nodes ⇄ Graph toggle + a search icon
        // (the field expands on tap) + an overflow menu for the rare
        // add / scan actions. Was three stacked rows; see
        // docs/REDESIGN.md §6 "Nodes header declutter".
        var searchActive by remember { mutableStateOf(search.isNotEmpty()) }
        var overflowOpen by remember { mutableStateOf(false) }
        var addMenuOpen by remember { mutableStateOf(false) }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = pane == NodesPane.Nodes,
                    onClick = { pane = NodesPane.Nodes },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Nodes") }
                SegmentedButton(
                    selected = pane == NodesPane.Graph,
                    onClick = { pane = NodesPane.Graph },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Graph") }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                searchActive = !searchActive
                if (!searchActive) viewModel.setNodeSearch("")
            }) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = if (searchActive) "Hide search" else "Search",
                    tint = if (searchActive || search.isNotEmpty())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // "+" — the platform-standard "add" affordance, split out
            // from the overflow menu (kebab) so add actions are
            // discoverable without a tap-explore. Tester request
            // (2026-05-21): "separate the filter (3 dots) from the
            // add functionality, by adding a + ,since that seems to
            // be the standard to add something."
            Box {
                IconButton(onClick = { addMenuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add destination")
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Add by hash") },
                        onClick = { addMenuOpen = false; showAddDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Scan QR code") },
                        onClick = { addMenuOpen = false; launchScan() },
                    )
                }
            }
            // Kebab — filter only, post split.
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Filter")
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    MenuSectionLabel("Filter")
                    ReticulumViewModel.NodeFilter.values()
                        .filter { it != ReticulumViewModel.NodeFilter.Rrc || rrcEnabled }
                        .forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.label) },
                                onClick = { viewModel.setNodeFilter(f); overflowOpen = false },
                                trailingIcon = if (filter == f) {
                                    { Icon(Icons.Default.Check, contentDescription = "Active") }
                                } else null,
                            )
                        }
                }
            }
        }

        if (pane == NodesPane.Graph) {
            GraphScreen(viewModel)
            return@Column
        }

        // Search field — only while expanded (the icon toggles it).
        if (searchActive) {
            // Auto-focus the field the moment it appears so a single tap on
            // the Search icon both reveals it AND puts the cursor + keyboard
            // up. Without this the field showed unfocused and needed a second
            // tap (issue #44). The FocusRequester + LaunchedEffect live
            // inside the `if` so a fresh focus request fires each time the
            // field is re-revealed; requestFocus is guarded because it throws
            // if the node isn't attached yet on a fast recompose.
            val searchFocus = remember { FocusRequester() }
            OutlinedTextField(
                value = search,
                onValueChange = { viewModel.setNodeSearch(it) },
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (search.isNotEmpty()) {
                    { IconButton(onClick = { viewModel.setNodeSearch("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(searchFocus),
            )
            LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (rows.isEmpty()) {
            val (emptyIcon, emptyMsg) = when {
                search.isNotBlank() ->
                    Icons.Default.Search to "Nothing matches \"$search\"."
                filter == ReticulumViewModel.NodeFilter.Contacts ->
                    Icons.Default.Person to "No contacts yet — open a node and tap Add to Contacts."
                filter == ReticulumViewModel.NodeFilter.Messagable ->
                    Icons.Default.Person to "No messagable destinations seen yet — connect a transport or scan someone's QR."
                filter == ReticulumViewModel.NodeFilter.Rrc ->
                    Icons.AutoMirrored.Filled.List to "No RRC hubs seen yet — hubs announce on the rrc.hub aspect."
                filter == ReticulumViewModel.NodeFilter.Telemetry ->
                    Icons.Default.Place to "No non-LXMF nodes seen yet."
                else /* All */ ->
                    Icons.Default.Place to "No destinations seen yet — connect a transport in Settings."
            }
            EmptyState(emptyIcon, emptyMsg)
        } else {
            DestinationList(
                rows = rows,
                onToggleFavorite = { hash, fav -> viewModel.toggleFavorite(hash, fav) },
                onRequestRename = { renameTarget = it },
                onRequestDelete = { deleteTarget = it },
                onOpenConversation = { hash -> viewModel.openContact(hash) },
                onOpenAsRrcHub = if (rrcEnabled) {
                    { dest -> viewModel.addRrcHubFromNode(dest) }
                } else null,
            )
        }
    }

    if (showAddDialog) {
        AddDestinationDialog(
            onDismiss = { showAddDialog = false },
            onScanQr = {
                showAddDialog = false
                launchScan()
            },
            onConfirmManual = { hash, label ->
                showAddDialog = false
                viewModel.addManualDestination(hash, label)
            },
        )
    }

    renameTarget?.let { target ->
        RenameContactDialog(
            target = target,
            onDismiss = { renameTarget = null },
            onSave = { label ->
                viewModel.setUserLabel(target.hash, label)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this destination?") },
            text = {
                Text(
                    "Removes ${target.effectiveDisplayName.ifBlank { "(unnamed)" }} from local storage along with " +
                        "all message history. If they announce again later they'll reappear in Nodes " +
                        "(without prior history).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val hash = target.hash
                    deleteTarget = null
                    viewModel.deleteDestinationAndMessages(hash)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    // SPEC §4.5 destHash↔publicKey binding refusal from
    // engine.applyIdentityCard (v1.2.18). Pre-v1.2.19 the rejection
    // only landed in _logLines (hidden behind the verboseLog toggle),
    // so a forged-card refusal looked identical to silent success.
    val qrError by viewModel.lastQrImportError.collectAsState()
    qrError?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearQrImportError() },
            title = { Text("QR import rejected") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearQrImportError() }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun DestinationList(
    rows: List<StoredDestination>,
    onToggleFavorite: (hash: String, favorite: Boolean) -> Unit,
    onRequestRename: (StoredDestination) -> Unit,
    onRequestDelete: (StoredDestination) -> Unit,
    onOpenConversation: (hash: String) -> Unit,
    /** Non-null only when the experimental RRC feature is on; shows an
     *  "open in Relay Chat" action on `rrc.hub` rows. */
    onOpenAsRrcHub: ((StoredDestination) -> Unit)?,
) {
    // Row tapped → which destination's detail sheet to show (null = none).
    var detailRow by remember { mutableStateOf<StoredDestination?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.hash }) { row ->
            // Tapping any row opens the shared destination detail sheet
            // — the full hash, QR, and the message / rename / contact /
            // delete actions all live there. See docs/REDESIGN.md §6.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { detailRow = row }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NodeAvatar(appName = row.appName, seed = row.hash)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.effectiveDisplayName.ifBlank { row.appLabel ?: "(unnamed)" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${row.appName ?: "unknown"} · ${shortHash(row.hash)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // v0.1.70: same metadata cluster the Nomad-tab list shows
                    // — hops, RSSI, last-heard age. Predictive "stale /
                    // likely unreachable" and "far / link may be slow" flags
                    // were dropped: with per-network announce cadences they
                    // produced misleading false positives, so we just report
                    // the facts and leave the line neutral-coloured.
                    val now = System.currentTimeMillis()
                    val ageMs = (now - row.lastSeen).coerceAtLeast(0)
                    val meta = buildList {
                        if (row.hopCount > 0) {
                            add("${row.hopCount} hop${if (row.hopCount != 1) "s" else ""}")
                        }
                        row.rssi?.let { add("RSSI $it dBm") }
                        if (row.lastSeen > 0) add("seen ${formatAge(ageMs)}")
                        if (row.source != "announce") add("source=${row.source}")
                        if (!row.isMessagable && row.appName == "lxmf.delivery") add("waiting for announce")
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            meta.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    row.telemetry?.takeIf { it.isNotEmpty() }?.let { tel ->
                        Text(
                            tel.entries.joinToString("  ") { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                // The row is tap-to-open-detail-sheet; a trailing
                // chevron signals that affordance. All the per-row
                // actions moved into the sheet (docs/REDESIGN.md §6).
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    detailRow?.let { selected ->
        DestinationDetailSheet(
            dest = selected,
            onDismiss = { detailRow = null },
            onMessage = onOpenConversation,
            onOpenAsRrcHub = onOpenAsRrcHub,
            onRename = onRequestRename,
            onToggleFavorite = onToggleFavorite,
            onDelete = onRequestDelete,
        )
    }
}

/**
 * Set or clear the local nickname for [target]. The text field
 * starts pre-filled with the existing userLabel; submitting an empty
 * value clears it and the row falls back to its announced display
 * name. Both shown side by side in the dialog so the user knows
 * exactly what they're overriding.
 */
@Composable
private fun RenameContactDialog(
    target: StoredDestination,
    onDismiss: () -> Unit,
    onSave: (label: String) -> Unit,
) {
    var draft by remember(target.hash) { mutableStateOf(target.userLabel ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a private nickname") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Stored locally on this device only. Never sent on the wire.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (target.displayName.isNotBlank()) {
                    Text(
                        "Announced name: ${target.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    target.hash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Leave empty to clear the nickname.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddDestinationDialog(
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    onConfirmManual: (hash: String, label: String) -> Unit,
) {
    var mode by remember { mutableStateOf("menu") }   // "menu" | "manual"
    var hash by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val cleaned = remember(hash) { hash.lowercase().filter { it != ':' && it != ' ' && it != '-' } }
    val valid = cleaned.length == 32 && cleaned.all { it in '0'..'9' || it in 'a'..'f' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (mode == "manual") "Enter destination hash"
                else "Add destination",
            )
        },
        text = {
            when (mode) {
                "manual" -> Column {
                    OutlinedTextField(
                        value = hash, onValueChange = { hash = it },
                        label = { Text("Destination hash (32 hex)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = label, onValueChange = { label = it },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Manual entries can't be messaged until an announce arrives carrying the public key. " +
                            "They appear in the Nodes list with a 'waiting for announce' note.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Column {
                    AddOptionRow(
                        title = "Scan QR code",
                        subtitle = "Use the camera to read someone's identity card or destination hash.",
                        onClick = onScanQr,
                    )
                    Spacer(Modifier.height(8.dp))
                    AddOptionRow(
                        title = "Enter hash manually",
                        subtitle = "Paste or type a 32-hex destination hash with an optional label.",
                        onClick = { mode = "manual" },
                    )
                }
            }
        },
        confirmButton = {
            if (mode == "manual") {
                TextButton(
                    onClick = { onConfirmManual(cleaned, label) },
                    enabled = valid,
                ) { Text("Add") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (mode == "manual") mode = "menu" else onDismiss()
            }) {
                Text(if (mode == "manual") "Back" else "Cancel")
            }
        },
    )
}

@Composable
private fun AddOptionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** Round type avatar shown at the head of each Nodes row — a person
 *  for messagable (lxmf.delivery) destinations, distinct glyphs for
 *  the other node kinds. Background is hash-derived (Meshtastic-
 *  parity, see commonMain/util/AvatarColors.kt) so each node gets a
 *  visually distinct chip instead of all rows sharing the theme's
 *  primary container. */
@Composable
private fun NodeAvatar(appName: String?, seed: String) {
    val icon = when (appName) {
        "lxmf.delivery"     -> Icons.Default.Person
        "rrc.hub"           -> Icons.AutoMirrored.Filled.List
        "nomadnetwork.node" -> Icons.Default.Info
        else                -> Icons.Default.Place
    }
    val avatarColors = remember(seed) { avatarColors(seed) }
    val bg = Color(avatarColors.backgroundArgb)
    val tint = if (avatarColors.useDarkText) Color.Black else Color.White
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun formatAge(ms: Long): String = when {
    ms < 60_000L            -> "${ms / 1000}s ago"
    ms < 60 * 60_000L       -> "${ms / 60_000L}m ago"
    ms < 24 * 60 * 60_000L  -> "${ms / (60 * 60_000L)}h ago"
    else                    -> "${ms / (24 * 60 * 60_000L)}d ago"
}

