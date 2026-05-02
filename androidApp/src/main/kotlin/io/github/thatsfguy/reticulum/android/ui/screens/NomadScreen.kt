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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
 * NomadNet directory + reader. Tap a node, tap "Load page" to open a
 * Reticulum Link to it and fetch `:/page/index.mu`. The path is fixed
 * for now (mirroring the simpler MeshChat-style UI) — until we have a
 * link/list of pages to navigate, an editable URL bar wasn't earning
 * its real estate.
 */
private const val DEFAULT_PAGE_PATH = ":/page/index.mu"

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
            onLoadPage = {
                pageState = PageState.Loading
                viewModel.fetchNomadPage(s.hash, DEFAULT_PAGE_PATH) { result ->
                    pageState = result.fold(
                        onSuccess = { PageState.Loaded(it) },
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
    object Loading : PageState()
    data class Loaded(val source: String) : PageState()
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
    onLoadPage: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
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
            Button(onClick = onLoadPage, enabled = pageState !is PageState.Loading) {
                Text(if (pageState is PageState.Loading) "Loading…" else "Load page")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (pageState) {
            PageState.Idle ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tap “Load page” to open a Reticulum Link to this node and fetch its " +
                            "$DEFAULT_PAGE_PATH page. The first attempt after connecting may " +
                            "take a moment while our announce propagates and the responder " +
                            "learns a path back to us.",
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

            PageState.Loading ->
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Establishing link and requesting $DEFAULT_PAGE_PATH …",
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
                        "Most failures here are timeouts: the responder either doesn't have a " +
                            "path back to us yet, or the page is bigger than one MTU. The " +
                            "diagnostics log on Settings shows the LRPROOF / RESPONSE timing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

            is PageState.Loaded ->
                MicronView(source = pageState.source, onLinkClick = { /* future: navigate */ })
        }
    }
}
