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
    var pagePath by remember { mutableStateOf(":/page/index.mu") }

    when (val s = selected) {
        null -> NomadList(nomadNodes, onPick = {
            selected = it
            pageState = PageState.Idle
        })
        else -> NomadNodeView(
            node = s,
            pageState = pageState,
            pagePath = pagePath,
            onPagePathChange = { pagePath = it },
            onLoadDemo = { pageState = PageState.Loaded(DEMO_MICRON_PAGE, isDemo = true) },
            onLoadOverLink = {
                pageState = PageState.Loading(pagePath)
                viewModel.fetchNomadPage(s.hash, pagePath) { result ->
                    pageState = result.fold(
                        onSuccess = { PageState.Loaded(it, isDemo = false) },
                        onFailure = { PageState.Error(it.message ?: "fetch failed") },
                    )
                }
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
    data class Loading(val path: String) : PageState()
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
    pagePath: String,
    onPagePathChange: (String) -> Unit,
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
            androidx.compose.material3.OutlinedTextField(
                value = pagePath,
                onValueChange = onPagePathChange,
                label = { Text("Path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLoadOverLink,
                    enabled = pageState !is PageState.Loading,
                ) {
                    Text(if (pageState is PageState.Loading) "Loading…" else "Load over link")
                }
                OutlinedButton(onClick = onLoadDemo) { Text("Demo page") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (pageState) {
            PageState.Idle ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "“Load over link” opens a Reticulum Link to this node and fetches the path " +
                            "above as a NomadNet REQUEST. Pages that fit in one packet (≈400 bytes of " +
                            "micron) round-trip; larger pages need Reticulum Resource fragmentation, " +
                            "which is on the follow-up list.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
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

            is PageState.Loading ->
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Text(
                        "Establishing link and requesting ${pageState.path}…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            is PageState.Error ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fetch failed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        pageState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Check the diagnostics log on Settings for the LRPROOF / RESPONSE timing. " +
                            "Most failures here are timeouts (the node is too far / not running) or " +
                            "the page being larger than one MTU.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
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
