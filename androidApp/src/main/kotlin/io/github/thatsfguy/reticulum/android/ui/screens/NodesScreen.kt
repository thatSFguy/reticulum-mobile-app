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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun NodesScreen(viewModel: ReticulumViewModel) {
    val filter by viewModel.nodeFilter.collectAsState()
    val rows by viewModel.filteredDestinations.collectAsState(initial = emptyList())
    val located = remember(rows) { rows.filter { it.lat != null && it.lon != null } }

    var showManualDialog by remember { mutableStateOf(false) }

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

    Column(Modifier.fillMaxSize()) {
        // Filter chips + add buttons
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReticulumViewModel.NodeFilter.values().forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick  = { viewModel.setNodeFilter(f) },
                        label    = { Text(f.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    qrLauncher.launch(ScanOptions().apply {
                        setPrompt("Scan a Reticulum identity QR")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    })
                }) { Text("Scan QR") }
                OutlinedButton(onClick = { showManualDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add by hash")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (located.isNotEmpty()) {
            MapBlock(located, modifier = Modifier.fillMaxWidth().height(240.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                val msg = when (filter) {
                    ReticulumViewModel.NodeFilter.Messagable -> "No messagable destinations seen yet — connect a transport or scan someone's QR."
                    ReticulumViewModel.NodeFilter.All        -> "No destinations seen yet — connect a transport on Settings."
                    ReticulumViewModel.NodeFilter.Telemetry  -> "No non-LXMF nodes seen yet."
                    ReticulumViewModel.NodeFilter.Favorites  -> "No favorites yet — tap the star on a destination to bring it here."
                }
                Text(msg, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
        } else {
            DestinationList(rows) { hash, fav -> viewModel.toggleFavorite(hash, fav) }
        }
    }

    if (showManualDialog) {
        ManualAddDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { hash, label ->
                showManualDialog = false
                viewModel.addManualDestination(hash, label)
            },
        )
    }
}

@Composable
private fun DestinationList(
    rows: List<StoredDestination>,
    onToggleFavorite: (hash: String, favorite: Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.hash }) { row ->
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.displayName.ifBlank { row.appLabel ?: "(unnamed)" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${row.appName ?: "unknown"} · ${row.hash}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val meta = buildList {
                        row.rssi?.let { add("RSSI ${it} dBm") }
                        if (row.source != "announce") add("source=${row.source}")
                        if (!row.isMessagable && row.appName == "lxmf.delivery") add("waiting for announce")
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    row.telemetry?.takeIf { it.isNotEmpty() }?.let { tel ->
                        Text(
                            tel.entries.joinToString("  ") { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (row.appName == "lxmf.delivery" || row.publicKey.isEmpty()) {
                    IconButton(onClick = { onToggleFavorite(row.hash, !row.favorite) }) {
                        if (row.favorite) {
                            Icon(Icons.Default.Star, contentDescription = "Unfavorite",
                                tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Outlined.StarOutline, contentDescription = "Favorite")
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ManualAddDialog(onDismiss: () -> Unit, onConfirm: (hash: String, label: String) -> Unit) {
    var hash by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val cleaned = remember(hash) { hash.lowercase().filter { it != ':' && it != ' ' && it != '-' } }
    val valid = cleaned.length == 32 && cleaned.all { it in '0'..'9' || it in 'a'..'f' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add destination by hash") },
        text = {
            Column {
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(cleaned, label) }, enabled = valid) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                    title = node.displayName.ifBlank { node.appLabel ?: node.hash }
                    snippet = node.hash
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(marker)
            }
            map.invalidate()
        },
    )
}
