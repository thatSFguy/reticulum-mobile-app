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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * NomadNet directory + reader. Tap a node and the page fetch fires
 * automatically — no extra button — opening a Reticulum Link and
 * requesting `/page/index.mu` (the upstream NomadNet default; spec §11).
 * Reload + Back affordances live inside the page view; tap a node from
 * the list to revisit.
 */
private const val DEFAULT_PAGE_PATH = "/page/index.mu"

@Composable
fun NomadScreen(viewModel: ReticulumViewModel) {
    val destinations by viewModel.allDestinations.collectAsState(initial = emptyList())
    val nomadNodes = remember(destinations) {
        destinations.filter { it.appName == "nomadnetwork.node" }
    }

    var selected by remember { mutableStateOf<StoredDestination?>(null) }
    var pageState by remember { mutableStateOf<PageState>(PageState.Loading) }
    // Bumped on Reload to force the LaunchedEffect to re-fire even when
    // the same node is still selected.
    var reloadKey by remember { mutableStateOf(0) }

    val current = selected
    LaunchedEffect(current, reloadKey) {
        if (current != null) {
            pageState = PageState.Loading
            viewModel.fetchNomadPage(current.hash, DEFAULT_PAGE_PATH) { result ->
                pageState = result.fold(
                    onSuccess = { PageState.Loaded(it) },
                    onFailure = { PageState.Error(it.message ?: "fetch failed") },
                )
            }
        }
    }

    if (current == null) {
        NomadList(nomadNodes, onPick = {
            selected = it
            pageState = PageState.Loading
        })
    } else {
        NomadNodeView(
            node = current,
            pageState = pageState,
            onReload = { reloadKey++ },
            onBack = { selected = null },
        )
    }
}

private sealed class PageState {
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
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.hash }) { node ->
            val ageMs = (now - node.lastSeen).coerceAtLeast(0)
            val stale = ageMs > 30 * 60_000L  // older than 30 min → likely no return path
            Row(
                Modifier.fillMaxWidth().clickable { onPick(node) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
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
                    val meta = buildList {
                        node.rssi?.let { add("RSSI $it dBm") }
                        add("seen ${formatAge(ageMs)}")
                        if (stale) add("stale — likely unreachable")
                    }
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
private fun NomadNodeView(
    node: StoredDestination,
    pageState: PageState,
    onReload: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onReload,
                    enabled = pageState !is PageState.Loading,
                ) { Text("⟳ Reload") }
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
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (pageState) {
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
                            "path back to us yet, or the page is bigger than one MTU. Tap " +
                            "Reload to retry. The diagnostics log on Settings shows the " +
                            "LRPROOF / RESPONSE timing.",
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

            is PageState.Loaded ->
                MicronView(source = pageState.source, onLinkClick = { /* future: navigate */ })
        }
    }
}
