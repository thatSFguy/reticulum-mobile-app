package io.github.thatsfguy.reticulum.android.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.store.StoredDestination

/**
 * NomadNet-aware view: lists every announced `nomadnetwork.node` destination
 * and (eventually) browses its micron pages over a Reticulum Link.
 *
 * Today this screen is a directory only. A full Nomad browser needs:
 *   - Initiator-side Link establishment (Link.createInitiator → LRPROOF
 *     verification → LRRTT confirmation; the responder logic is already in
 *     the engine but we don't drive an outbound link yet)
 *   - A request frame schema for `:/page/index.mu` style paths
 *   - A micron parser/renderer (formatting controls like `\B`, `\b`, `\F`,
 *     headings and links)
 *
 * That work is sized at a separate alpha follow-up. The placeholder detail
 * view here documents the gap so users see what's coming.
 */
@Composable
fun NomadScreen(viewModel: ReticulumViewModel) {
    val destinations by viewModel.allDestinations.collectAsState(initial = emptyList())
    val nomadNodes = remember(destinations) {
        destinations.filter { it.appName == "nomadnetwork.node" }
    }

    var selected by remember { mutableStateOf<StoredDestination?>(null) }

    when (val s = selected) {
        null -> NomadList(nomadNodes, onPick = { selected = it })
        else -> NomadPlaceholder(node = s, onBack = { selected = null })
    }
}

@Composable
private fun NomadList(nodes: List<StoredDestination>, onPick: (StoredDestination) -> Unit) {
    if (nodes.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No NomadNet nodes seen yet. Connect a transport that carries `nomadnetwork.node` " +
                    "announces — they'll show up here automatically.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.hash }) { node ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(node) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        node.displayName.ifBlank { node.appLabel ?: "(unnamed)" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        node.hash,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    node.rssi?.let { Text("RSSI $it dBm", style = MaterialTheme.typography.bodySmall) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun NomadPlaceholder(node: StoredDestination, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }
        Text(node.displayName.ifBlank { "(unnamed NomadNet node)" }, style = MaterialTheme.typography.titleLarge)
        Text(
            node.hash,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Browsing this node's micron pages requires the initiator-side Reticulum Link " +
                "protocol, which isn't wired into this build yet. The destination, RSSI, and any " +
                "telemetry below are real and live; tapping into the browser will work once the " +
                "Link client lands.",
            style = MaterialTheme.typography.bodyMedium,
        )
        node.telemetry?.takeIf { it.isNotEmpty() }?.let { tel ->
            Text("Telemetry", style = MaterialTheme.typography.titleMedium)
            Text(
                tel.entries.joinToString("\n") { "  ${it.key} = ${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
