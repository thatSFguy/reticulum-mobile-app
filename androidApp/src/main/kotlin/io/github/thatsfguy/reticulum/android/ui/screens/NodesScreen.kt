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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.store.StoredDestination
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Which pane the Nodes tab shows. `Graph` is the former standalone
 *  bottom-nav tab, folded in here to free a nav slot. */
private enum class NodesPane { Nodes, Graph }

@Composable
fun NodesScreen(viewModel: ReticulumViewModel) {
    val filter by viewModel.nodeFilter.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val search by viewModel.nodeSearch.collectAsState()
    val rows by viewModel.filteredDestinations.collectAsState(initial = emptyList())
    // Drives the per-row "open in Relay Chat" action on rrc.hub rows;
    // hidden entirely when the experimental RRC feature is off.
    val rrcEnabled by viewModel.experimentalRrc.collectAsState(initial = false)
    val located = remember(rows) { rows.filter { it.lat != null && it.lon != null } }

    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StoredDestination?>(null) }
    var deleteTarget by remember { mutableStateOf<StoredDestination?>(null) }
    var showMap by remember { mutableStateOf(false) }
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
            setOrientationLocked(false)
        })
    }

    Column(Modifier.fillMaxSize()) {
        // Nodes ⇄ Graph pane switch. Graph absorbed the former standalone
        // "Graph" bottom-nav tab (removed to free a slot for RRC); the two
        // are just different visualizations of the same destination set.
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
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

        if (pane == NodesPane.Graph) {
            GraphScreen(viewModel)
            return@Column
        }

        // Top action row: search field, favorites star toggle, add (+).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.setFavoritesOnly(!favoritesOnly) }) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (favoritesOnly) "Show all" else "Show favorites only",
                    tint = if (favoritesOnly)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add destination",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Kind filter chips on a second row.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReticulumViewModel.NodeFilter.values().forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick  = { viewModel.setNodeFilter(f) },
                    label    = { Text(f.label) },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // v0.1.70: map is opt-in via "Map (N)" button + opens in a
        // fullscreen Dialog with a Close affordance. Pre-fix the inline
        // 240dp map captured pan/zoom gestures (multiTouchControls) and
        // marker InfoWindow popups had no close — felt like a floating
        // panel that wouldn't go away. Now: tap to open, X to close.
        if (located.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showMap = true }) {
                    Text("📍 Map (${located.size} located)")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                val msg = when {
                    search.isNotBlank() -> "Nothing matches \"$search\"."
                    favoritesOnly -> "No favorites yet — tap the star on a destination row to bring it here."
                    filter == ReticulumViewModel.NodeFilter.Messagable -> "No messagable destinations seen yet — connect a transport or scan someone's QR."
                    filter == ReticulumViewModel.NodeFilter.All        -> "No destinations seen yet — connect a transport on Settings."
                    else /* Telemetry */                                -> "No non-LXMF nodes seen yet."
                }
                Text(msg, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
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

    if (showMap) {
        FullscreenMapDialog(located = located, onClose = { showMap = false })
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
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.hash }) { row ->
            // Tapping the name area on a messagable row jumps straight
            // to a conversation with that peer in the Messages tab —
            // no need to favorite first. Same predicate as the favorite
            // button below: lxmf.delivery destinations and manual stubs
            // (publicKey not yet populated by an announce). Non-messagable
            // rows (rlr.telemetry, nomadnetwork.node) stay non-clickable
            // because a chat conversation isn't meaningful for them.
            val isMessagableLike = row.appName == "lxmf.delivery" || row.publicKey.isEmpty()
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .then(
                            if (isMessagableLike)
                                Modifier.clickable { onOpenConversation(row.hash) }
                            else Modifier
                        )
                ) {
                    Text(
                        row.effectiveDisplayName.ifBlank { row.appLabel ?: "(unnamed)" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${row.appName ?: "unknown"} · ${row.hash}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // v0.1.70: same metadata cluster the Nomad-tab list shows
                    // — hops, RSSI, last-heard age, freshness flags. Was a
                    // sparser line that lacked the routing/age info users
                    // need to gauge whether a node is reachable.
                    val now = System.currentTimeMillis()
                    val ageMs = (now - row.lastSeen).coerceAtLeast(0)
                    val stale = row.lastSeen > 0 && ageMs > 30 * 60_000L
                    val farAway = row.hopCount >= 4
                    val meta = buildList {
                        if (row.hopCount > 0) {
                            add("${row.hopCount} hop${if (row.hopCount != 1) "s" else ""}")
                        }
                        row.rssi?.let { add("RSSI $it dBm") }
                        if (row.lastSeen > 0) add("seen ${formatAge(ageMs)}")
                        if (row.source != "announce") add("source=${row.source}")
                        if (!row.isMessagable && row.appName == "lxmf.delivery") add("waiting for announce")
                        if (stale)        add("stale — likely unreachable")
                        else if (farAway) add("far — link may be slow")
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            meta.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                stale   -> MaterialTheme.colorScheme.error
                                farAway -> MaterialTheme.colorScheme.tertiary
                                else    -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
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
                // Discovered RRC hub → one-tap promote into the Rooms
                // tab. Only present when the experimental flag is on.
                if (onOpenAsRrcHub != null && row.appName == "rrc.hub") {
                    IconButton(onClick = { onOpenAsRrcHub(row) }) {
                        Icon(
                            Icons.Default.MailOutline,
                            contentDescription = "Open in Relay Chat",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = { onRequestRename(row) }) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = if (row.userLabel.isNullOrBlank())
                            "Add a private nickname"
                        else
                            "Edit nickname",
                        tint = if (!row.userLabel.isNullOrBlank())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                if (row.appName == "lxmf.delivery" || row.publicKey.isEmpty()) {
                    // (The explicit envelope IconButton that lived
                    // here in 1.1.25 was removed in 1.1.30 — the
                    // row's name area is already tap-to-open-
                    // conversation, and the extra icon was crowding
                    // the rename + favorite buttons on phones with
                    // narrower rows.)
                    IconButton(onClick = { onToggleFavorite(row.hash, !row.favorite) }) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = if (row.favorite) "Unfavorite" else "Favorite",
                            tint = if (row.favorite)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }
                IconButton(onClick = { onRequestDelete(row) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete destination",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
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

/**
 * v0.1.70: Fullscreen map modal with an explicit close button. Replaces
 * the always-on inline 240dp MapBlock that captured pan/zoom gestures
 * (multiTouchControls) and had no way to dismiss marker InfoWindows.
 * Opens via the "Map (N located)" pill on the Nodes tab.
 */
@Composable
private fun FullscreenMapDialog(
    located: List<StoredDestination>,
    onClose: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Map · ${located.size} located",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Clear, contentDescription = "Close map")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MapBlock(located, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun formatAge(ms: Long): String = when {
    ms < 60_000L            -> "${ms / 1000}s ago"
    ms < 60 * 60_000L       -> "${ms / 60_000L}m ago"
    ms < 24 * 60 * 60_000L  -> "${ms / (60 * 60_000L)}h ago"
    else                    -> "${ms / (24 * 60 * 60_000L)}d ago"
}

@Composable
private fun MapBlock(located: List<StoredDestination>, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(8.0)
                if (located.isNotEmpty()) {
                    val first = located.first()
                    controller.setCenter(GeoPoint(first.lat!!, first.lon!!))
                }
            }
        },
        update = { map ->
            map.overlays.removeAll { it is Marker }
            for (node in located) {
                val marker = Marker(map).apply {
                    position = GeoPoint(node.lat!!, node.lon!!)
                    title = node.effectiveDisplayName.ifBlank { node.appLabel ?: node.hash }
                    snippet = node.hash
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    // v0.1.70: tap on the marker toggles its info window;
                    // the default behavior LEFT info windows open with no
                    // close affordance once you tapped a different marker.
                    // Override so each tap toggles, and tap-on-map closes all.
                    setOnMarkerClickListener { m, mv ->
                        if (m.isInfoWindowShown) m.closeInfoWindow()
                        else { org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(mv); m.showInfoWindow() }
                        true
                    }
                }
                map.overlays.add(marker)
            }
            map.invalidate()
        },
    )
}
