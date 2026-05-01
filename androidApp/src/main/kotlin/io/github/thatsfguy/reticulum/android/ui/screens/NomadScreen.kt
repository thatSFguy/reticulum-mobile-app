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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun NomadScreen(viewModel: ReticulumViewModel) {
    val destinations by viewModel.allDestinations.collectAsState(initial = emptyList())
    val nomadNodes = remember(destinations) {
        destinations.filter { it.appName == "nomadnetwork.node" }
    }

    var selected by remember { mutableStateOf<StoredDestination?>(null) }
    var pageState by remember { mutableStateOf<PageState>(PageState.Idle) }

    when (val s = selected) {
        null -> NomadList(nomadNodes, onPick = {
            selected = it
            pageState = PageState.Idle
        })
        else -> NomadNodeView(
            node = s,
            pageState = pageState,
            onLoadDemo = { pageState = PageState.Loaded(DEMO_MICRON_PAGE, isDemo = true) },
            onLoadOverLink = {
                // Real path: open a Reticulum Link to s.destHash, send a NomadNet
                // page request frame, reassemble multi-packet response, render
                // the result. Link initiator state machine is in the engine but
                // not yet driven from here — that's the next NomadNet milestone.
                pageState = PageState.Error(
                    "Link client not yet wired. The renderer below shows the demo " +
                        "page so you can validate formatting; switching the source " +
                        "from demo to live is the only thing left here."
                )
            },
            onBack = {
                if (pageState != PageState.Idle) pageState = PageState.Idle
                else selected = null
            },
        )
    }
}

private sealed class PageState {
    object Idle : PageState()
    data class Loaded(val source: String, val isDemo: Boolean) : PageState()
    data class Error(val message: String) : PageState()
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
private fun NomadNodeView(
    node: StoredDestination,
    pageState: PageState,
    onLoadDemo: () -> Unit,
    onLoadOverLink: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // Header
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
            }
            Text(
                node.displayName.ifBlank { "(unnamed NomadNet node)" },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                node.hash,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoadOverLink) { Text("Load over link") }
                OutlinedButton(onClick = onLoadDemo) { Text("Demo page") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (pageState) {
            PageState.Idle ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tap “Load over link” to fetch this node's :/page/index.mu over a Reticulum Link, " +
                            "or “Demo page” to see the in-app micron renderer working with sample content.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    node.telemetry?.takeIf { it.isNotEmpty() }?.let { tel ->
                        Spacer(Modifier.height(8.dp))
                        Text("Telemetry", style = MaterialTheme.typography.titleMedium)
                        Text(
                            tel.entries.joinToString("\n") { "  ${it.key} = ${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

            is PageState.Error ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        pageState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

            is PageState.Loaded -> {
                if (pageState.isDemo) {
                    Text(
                        "  demo content — not fetched over the network",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                }
                MicronView(source = pageState.source, onLinkClick = { /* future: navigate */ })
            }
        }
    }
}
