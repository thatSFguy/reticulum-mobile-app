package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.store.StoredNode
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun NodesScreen(viewModel: ReticulumViewModel) {
    val nodes by viewModel.nodes.collectAsState(initial = emptyList())
    val located = remember(nodes) { nodes.filter { it.lat != null && it.lon != null } }

    Column(Modifier.fillMaxSize()) {
        if (located.isNotEmpty()) {
            MapBlock(located, modifier = Modifier.fillMaxWidth().height(280.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No nodes seen yet — non-LXMF announces (telemetry, transport broadcasts, etc.) appear here.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        } else {
            NodeList(nodes)
        }
    }
}

@Composable
private fun NodeList(nodes: List<StoredNode>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.hash }) { node ->
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    node.displayName.ifBlank { node.appLabel ?: "(unnamed)" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${node.appName ?: "unknown"} · ${node.hash}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (node.rssi != null) {
                    Text("RSSI ${node.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
                node.telemetry?.takeIf { it.isNotEmpty() }?.let { tel ->
                    Text(
                        tel.entries.joinToString("  ") { "${it.key}=${it.value}" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun MapBlock(located: List<StoredNode>, modifier: Modifier) {
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
