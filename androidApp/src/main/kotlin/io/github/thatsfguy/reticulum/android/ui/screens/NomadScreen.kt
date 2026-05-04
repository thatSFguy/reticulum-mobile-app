package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredNomadPage

/**
 * NomadNet directory + reader. Tap a node and the page fetch fires
 * automatically — no extra button — opening a Reticulum Link and
 * requesting `/page/index.mu` (upstream NomadNet default; spec §11).
 *
 * v0.1.48 additions:
 *   - search box at the top
 *   - filter chips: All / Favorites / Cached
 *   - star icon to toggle favorite per node
 *   - dot indicator on rows that have at least one cached page
 *   - cache-first render: prior page bytes show instantly, fresh fetch
 *     runs in the background and swaps on success. "Last pulled Xm ago"
 *     under the page title; Clear cache button alongside Reload/Back.
 */
private const val DEFAULT_PAGE_PATH = "/page/index.mu"

@Composable
fun NomadScreen(viewModel: ReticulumViewModel) {
    val destinations by viewModel.allDestinations.collectAsState(initial = emptyList())
    val cachedHashes by viewModel.cachedNomadDestHashes.collectAsState(initial = emptySet())
    val filter by viewModel.nomadFilter.collectAsState()
    val search by viewModel.nomadSearch.collectAsState()

    val nomadNodes = remember(destinations, cachedHashes, filter, search) {
        val all = destinations.filter { it.appName == "nomadnetwork.node" }
        val byFilter = when (filter) {
            ReticulumViewModel.NomadFilter.All       -> all
            ReticulumViewModel.NomadFilter.Favorites -> all.filter { it.favorite }
            ReticulumViewModel.NomadFilter.Cached    -> all.filter { it.hash in cachedHashes }
        }
        val q = search.trim().lowercase()
        if (q.isEmpty()) byFilter
        else byFilter.filter { d ->
            d.displayName.lowercase().contains(q) || d.hash.lowercase().contains(q)
        }
    }

    var selected by remember { mutableStateOf<StoredDestination?>(null) }
    var pageState by remember { mutableStateOf<PageState>(PageState.Loading) }
    var cacheInfo by remember { mutableStateOf<StoredNomadPage?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    /** When set, the next fetch carries form-field POST data. The value
     *  is the msgpack-encoded `{ "field_<name>": value }` dict (built in
     *  [submitForm] below) — engine forwards it to LinkSession.request
     *  as the third element of the `[time, path_hash, body]` envelope.
     *  Cleared after the fetch completes (success or failure) so a
     *  subsequent Reload is a plain GET. */
    var pendingPostBody by remember { mutableStateOf<ByteArray?>(null) }
    /** Path override for form posts — the link's target may differ from
     *  the index path we initially loaded. */
    var pendingPostPath by remember { mutableStateOf<String?>(null) }

    val current = selected
    LaunchedEffect(current, reloadKey) {
        if (current != null) {
            val activePath = pendingPostPath ?: DEFAULT_PAGE_PATH
            val activeBody = pendingPostBody ?: ByteArray(0)
            val isPost = activeBody.isNotEmpty()
            // GETs render cache-first; POSTs always show a fresh spinner
            // (cache key is by path only — a form submission is body-
            // dependent and shouldn't be served stale).
            if (!isPost) {
                val cached = viewModel.loadCachedNomadPageNow(current.hash, activePath)
                cacheInfo = cached
                pageState = if (cached != null) PageState.Loaded(cached.source) else PageState.Loading
            } else {
                pageState = PageState.Loading
            }

            val result = viewModel.fetchNomadPageNow(current.hash, activePath, activeBody)
            // Clear the pending post so a subsequent Reload is a plain GET
            // of the original index page.
            pendingPostBody = null
            pendingPostPath = null
            pageState = result.fold(
                onSuccess = { source ->
                    if (!isPost) {
                        cacheInfo = viewModel.loadCachedNomadPageNow(current.hash, DEFAULT_PAGE_PATH)
                    }
                    PageState.Loaded(source)
                },
                onFailure = { err ->
                    val fallback = cacheInfo
                    if (fallback != null) PageState.LoadedStale(fallback.source, err.message ?: "fetch failed")
                    else PageState.Error(err.message ?: "fetch failed")
                },
            )
        }
    }

    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            NomadFilters(
                filter = filter,
                search = search,
                onFilterChange = viewModel::setNomadFilter,
                onSearchChange = viewModel::setNomadSearch,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NomadList(
                nodes = nomadNodes,
                cachedHashes = cachedHashes,
                onPick = {
                    selected = it
                    cacheInfo = null
                    pageState = PageState.Loading
                },
                onToggleFavorite = { d -> viewModel.setDestinationFavorite(d.hash, !d.favorite) },
            )
        }
    } else {
        NomadNodeView(
            node = current,
            pageState = pageState,
            cacheInfo = cacheInfo,
            onReload = { reloadKey++ },
            onClearCache = {
                viewModel.clearNomadPageCache(current.hash, DEFAULT_PAGE_PATH) {
                    cacheInfo = null
                    reloadKey++
                }
            },
            onBack = { selected = null },
            onSubmitForm = { target, fieldValues ->
                // Build the upstream-shaped POST body. Per Node.py:170:
                // server scans the data dict for keys starting with
                // "field_" / "var_" and exports them as env vars to the
                // executable page handler. Browser-side adds the prefix.
                val prefixed: Map<String, Any> = fieldValues.mapKeys { (k, _) -> "field_$k" }
                pendingPostBody = io.github.thatsfguy.reticulum.codec.MessagePack.encode(prefixed)
                pendingPostPath = target
                reloadKey++
            },
        )
    }
}

@Composable
private fun NomadFilters(
    filter: ReticulumViewModel.NomadFilter,
    search: String,
    onFilterChange: (ReticulumViewModel.NomadFilter) -> Unit,
    onSearchChange: (String) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            singleLine = true,
            placeholder = { Text("Search nodes…") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReticulumViewModel.NomadFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                    label = { Text(f.label) },
                )
            }
        }
    }
}

private sealed class PageState {
    object Loading : PageState()
    data class Loaded(val source: String) : PageState()
    /** Fresh fetch failed but we have cached content — show cache + a notice. */
    data class LoadedStale(val source: String, val staleReason: String) : PageState()
    data class Error(val message: String) : PageState()
}

@Composable
private fun NomadList(
    nodes: List<StoredDestination>,
    cachedHashes: Set<String>,
    onPick: (StoredDestination) -> Unit,
    onToggleFavorite: (StoredDestination) -> Unit,
) {
    if (nodes.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No NomadNet nodes match. Check filters / search, or wait — " +
                    "they show up here automatically as `nomadnetwork.node` " +
                    "announces arrive.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.hash }) { node ->
            val ageMs = (now - node.lastSeen).coerceAtLeast(0)
            val stale = ageMs > 30 * 60_000L
            val farAway = node.hopCount >= 4
            val cached = node.hash in cachedHashes
            Row(
                Modifier.fillMaxWidth().clickable { onPick(node) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (cached) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            node.displayName.ifBlank { node.appLabel ?: "(unnamed)" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        node.hash,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val meta = buildList {
                        if (node.hopCount > 0) add("${node.hopCount} hop${if (node.hopCount != 1) "s" else ""}")
                        node.rssi?.let { add("RSSI $it dBm") }
                        add("seen ${formatAge(ageMs)}")
                        if (cached) add("cached")
                        if (stale) add("stale — likely unreachable")
                        else if (farAway) add("far — link may be slow")
                    }
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            stale    -> MaterialTheme.colorScheme.error
                            farAway  -> MaterialTheme.colorScheme.tertiary
                            else     -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { onToggleFavorite(node) }) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = if (node.favorite) "Unfavorite" else "Favorite",
                        tint = if (node.favorite) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
    cacheInfo: StoredNomadPage?,
    onReload: () -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
    onSubmitForm: (target: String, fields: Map<String, String>) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.weight(1f))
                if (cacheInfo != null) {
                    TextButton(onClick = onClearCache) { Text("✕ Clear cache") }
                }
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
            cacheInfo?.let { ci ->
                val age = formatAge((System.currentTimeMillis() - ci.fetchedAt).coerceAtLeast(0))
                Text(
                    "Last pulled $age (${ci.byteSize} B)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pageState is PageState.LoadedStale) {
                Text(
                    "Showing cached version — fresh fetch failed: ${pageState.staleReason}",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
                        "Tap Reload to retry. Settings → diagnostics log shows " +
                            "LRPROOF / RESPONSE timing if you want to dig in.",
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
                MicronView(
                    source = pageState.source,
                    onLinkClick = { /* future: navigate to plain link */ },
                    onLinkClickWithFields = onSubmitForm,
                )

            is PageState.LoadedStale ->
                MicronView(
                    source = pageState.source,
                    onLinkClick = { /* future: navigate to plain link */ },
                    onLinkClickWithFields = onSubmitForm,
                )
        }
    }
}
