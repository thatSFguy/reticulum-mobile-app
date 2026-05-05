package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.nomad.LinkTarget
import io.github.thatsfguy.reticulum.nomad.parseLinkTarget
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredNomadPage
import kotlinx.coroutines.launch

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
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

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
            d.effectiveDisplayName.lowercase().contains(q) ||
                d.displayName.lowercase().contains(q) ||
                d.hash.lowercase().contains(q)
        }
    }

    var selected by remember { mutableStateOf<StoredDestination?>(null) }
    var pageState by remember { mutableStateOf<PageState>(PageState.Loading) }
    var cacheInfo by remember { mutableStateOf<StoredNomadPage?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    /** Currently-displayed path. Starts at DEFAULT_PAGE_PATH on each new
     *  node selection and is updated when the user taps a same-node link
     *  (e.g. `/page/group.mu`). Reload re-fetches whatever path is
     *  current — back-out-and-pick-the-node-again resets to the index. */
    var currentPath by remember(selected) { mutableStateOf(DEFAULT_PAGE_PATH) }
    /** When set, the next fetch carries form-field POST data. The map
     *  is `{ "field_<name>": "<value>", ... }` — engine forwards it as
     *  envelope element [2] (msgpack map). Upstream Node.py:109-111
     *  reads keys starting with `field_` / `var_` into env vars for
     *  the executable page handler. Cleared after the fetch completes
     *  so a subsequent Reload is a plain GET of `currentPath`. */
    var pendingPostData by remember { mutableStateOf<Map<String, String>?>(null) }
    /** v0.1.64: per-session opt-in to send a LINKIDENTIFY packet on
     *  the link before the REQUEST. Default off (privacy — see
     *  SPEC.md §11.6.6). User flips this from the node-view header
     *  when fetching ALLOW_LIST pages (operator-restricted areas,
     *  member-only chatrooms). Resets when the user backs out to
     *  the node list. */
    var identifyOnFetch by remember(selected) { mutableStateOf(false) }
    /** v0.1.65: navigation history per Browser.py:907-936. Each entry
     *  is `(destHash, path)`; pushed when the user follows a link;
     *  popped on Back. Without it Back has only two states (current
     *  page → index → node list); with it, multi-step Back is
     *  available across same-node nav AND cross-node nav.
     *
     *  Reset when the user picks a fresh node from the directory.
     *  Form-submit POSTs are NOT pushed — back-after-submit re-fetches
     *  the previous page as a fresh GET, never re-submits the form. */
    val historyStack = remember(selected) { mutableStateListOf<Pair<StoredDestination, String>>() }

    // v0.1.71: re-read the StoredDestination from the live `destinations`
    // flow on every recomposition rather than holding the snapshot the
    // user picked. Without this, toggling favorite (or any field) via
    // viewModel.setDestinationFavorite updates the repo but `selected`
    // still points at the OLD object — the star icon stays drawn at its
    // pre-toggle state until the user backs out and re-selects the node.
    val current = selected?.let { sel -> destinations.firstOrNull { it.hash == sel.hash } ?: sel }
    // v0.1.72: key on hash, NOT the StoredDestination object. The
    // v0.1.71 favorite-fix made `current` a fresh derivation from the
    // destinations flow on every recomposition. StoredDestination is a
    // data class but several fields are ByteArray (publicKey, destHash,
    // nameHash, ratchetPub, nextHop) — Kotlin data-class equals() uses
    // reference equality on arrays, so each flow re-emission produces an
    // "unequal" object even when bytes are identical, retriggering this
    // LaunchedEffect every time any destination field changes (RSSI,
    // lastSeen). The fix is to key on stable scalars: the hash string
    // identifies the node, and reloadKey already exists for explicit
    // refetches.
    LaunchedEffect(current?.hash, currentPath, reloadKey) {
        if (current != null) {
            val activeData = pendingPostData
            val isPost = activeData != null
            // GETs render cache-first; POSTs always show a fresh spinner
            // (cache key is by path only — a form submission is body-
            // dependent and shouldn't be served stale).
            if (!isPost) {
                val cached = viewModel.loadCachedNomadPageNow(current.hash, currentPath)
                cacheInfo = cached
                pageState = if (cached != null) PageState.Loaded(cached.source) else PageState.Loading
            } else {
                pageState = PageState.Loading
            }

            val result = viewModel.fetchNomadPageNow(
                current.hash, currentPath, activeData, identify = identifyOnFetch,
            )
            pendingPostData = null
            pageState = result.fold(
                onSuccess = { source ->
                    if (!isPost) {
                        cacheInfo = viewModel.loadCachedNomadPageNow(current.hash, currentPath)
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
            currentPath = currentPath,
            pageState = pageState,
            cacheInfo = cacheInfo,
            identifyOnFetch = identifyOnFetch,
            onToggleIdentify = {
                identifyOnFetch = !identifyOnFetch
                reloadKey++
            },
            onReload = { reloadKey++ },
            onClearCache = {
                viewModel.clearNomadPageCache(current.hash, currentPath) {
                    cacheInfo = null
                    reloadKey++
                }
            },
            onBack = {
                // v0.1.65: history-aware Back. If the stack has entries,
                // pop and navigate to the previous (destHash, path);
                // otherwise drop to the node list. Walking the stack
                // gives multi-step Back across same-node + cross-node nav.
                val popped = if (historyStack.isNotEmpty()) historyStack.removeAt(historyStack.lastIndex) else null
                if (popped != null) {
                    if (popped.first.hash != current.hash) {
                        cacheInfo = null
                        pageState = PageState.Loading
                        selected = popped.first
                    }
                    currentPath = popped.second
                } else {
                    selected = null
                }
            },
            onToggleFavorite = { viewModel.setDestinationFavorite(current.hash, !current.favorite) },
            onLinkClick = { target ->
                // v0.1.56: dispatch via parseLinkTarget — covers same-node,
                // cross-node `<hex>:/path`, bare hash, `nnn@<hex>` shorthand,
                // and `lxmf@<hex>` (out-of-scope for browser, surfaced as
                // a help message). Mirrors upstream Browser.py:184-259.
                when (val tgt = parseLinkTarget(target)) {
                    is LinkTarget.SameNode -> {
                        // v0.1.65: push current (destHash, path) onto the
                        // history stack BEFORE navigating so Back can
                        // walk back through visited pages.
                        historyStack += current to currentPath
                        currentPath = tgt.path
                    }
                    is LinkTarget.CrossNode -> {
                        // Resolve the target destination — uses the announce-
                        // populated record if we have one, otherwise inserts
                        // a manual stub + kicks a path request. Then swap
                        // selection so the LaunchedEffect re-fires for the
                        // new (destHash, path) tuple.
                        coroutineScope.launch {
                            val dest = viewModel.resolveOrPrepareDestination(tgt.destHashHex)
                            if (dest != null) {
                                historyStack += current to currentPath
                                cacheInfo = null
                                pageState = PageState.Loading
                                selected = dest
                                currentPath = tgt.path
                            } else {
                                pageState = PageState.Error(
                                    "Could not resolve destination ${tgt.destHashHex.take(8)}… " +
                                        "(service not bound or invalid hash)"
                                )
                            }
                        }
                    }
                    is LinkTarget.Lxmf -> {
                        // Resolve / create the destination stub so it shows
                        // up in the Messages tab list, then route through
                        // the same `pendingOpenContact` deep-link signal a
                        // notification tap uses (v0.1.84). The Activity
                        // collector switches the NavController to Messages
                        // and selects the conversation in one step.
                        coroutineScope.launch {
                            val dest = viewModel.resolveOrPrepareDestination(tgt.destHashHex)
                            if (dest != null) {
                                viewModel.toggleFavorite(tgt.destHashHex, true)
                                viewModel.openContact(tgt.destHashHex)
                            } else {
                                pageState = PageState.Error(
                                    "Could not resolve LXMF target ${tgt.destHashHex.take(8)}… " +
                                        "(service not bound or invalid hash)"
                                )
                            }
                        }
                    }
                    is LinkTarget.Unknown -> {
                        pageState = PageState.Error("Unrecognized link: $target")
                    }
                }
            },
            onSubmitForm = { target, prefixedData ->
                // v0.1.61: MicronView's buildSubmitData already adds the
                // `field_` / `var_` prefixes per Browser.py:198-241 and
                // omits unchecked checkboxes per :226-241. We forward
                // the dict verbatim — engine msgpack-encodes the envelope
                // once (pre-encoding here would land as msgpack bin in
                // slot [2] and silently break form submission, see
                // v0.1.53 fix).
                pendingPostData = prefixedData
                if (target.startsWith("/")) currentPath = target
                reloadKey++
            },
            fetchPartial = { url, fields ->
                // v0.1.67: partials fetch from the CURRENT node, not
                // any other destination. The link reuse cache from
                // v0.1.66 means this doesn't pay a fresh LRPROOF for
                // each partial — they share the active link with the
                // host page. fields are passed as a `var_<key>=value`
                // dict so the server's partial handler can use them.
                val varData: Map<String, String>? = fields.takeIf { it.isNotEmpty() }
                    ?.mapNotNull { entry ->
                        val eq = entry.indexOf('=')
                        if (eq > 0) "var_${entry.substring(0, eq)}" to entry.substring(eq + 1)
                        else null
                    }?.toMap()
                viewModel.fetchNomadPageNow(
                    current.hash, url, data = varData, identify = identifyOnFetch,
                ).getOrNull()
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
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (search.isNotEmpty()) {
                { IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                } }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
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
                            node.effectiveDisplayName.ifBlank { node.appLabel ?: "(unnamed)" },
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
    currentPath: String,
    pageState: PageState,
    cacheInfo: StoredNomadPage?,
    identifyOnFetch: Boolean = false,
    onToggleIdentify: () -> Unit = {},
    onReload: () -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit = {},
    onLinkClick: (target: String) -> Unit = {},
    onSubmitForm: (target: String, fields: Map<String, String>) -> Unit = { _, _ -> },
    fetchPartial: suspend (String, List<String>) -> String? = { _, _ -> null },
) {
    Column(Modifier.fillMaxSize()) {
        // v0.1.79: icon nav bar in place of word-button row. Icons are
        // spread evenly across the width with a small caption under each
        // so the meaning isn't ambiguous. Lock toggles the v0.1.64 opt-in
        // LINKIDENTIFY (sends CTX_LINKIDENTIFY before the REQUEST so
        // ALLOW_LIST pages can authenticate us); default off because
        // identifying pins our identity hash to the node operator.
        NomadNavBar(
            favorite = node.favorite,
            identifyOnFetch = identifyOnFetch,
            canClearCache = cacheInfo != null,
            canReload = pageState !is PageState.Loading,
            onBack = onBack,
            onReload = onReload,
            onToggleFavorite = onToggleFavorite,
            onToggleIdentify = onToggleIdentify,
            onClearCache = onClearCache,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                node.effectiveDisplayName.ifBlank { "(unnamed NomadNet node)" },
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
                        "Establishing link and requesting $currentPath …",
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
                    onLinkClick = onLinkClick,
                    onLinkClickWithFields = onSubmitForm,
                    fetchPartial = fetchPartial,
                )

            is PageState.LoadedStale ->
                MicronView(
                    source = pageState.source,
                    onLinkClick = onLinkClick,
                    onLinkClickWithFields = onSubmitForm,
                    fetchPartial = fetchPartial,
                )
        }
    }
}

@Composable
private fun NomadNavBar(
    favorite: Boolean,
    identifyOnFetch: Boolean,
    canClearCache: Boolean,
    canReload: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleIdentify: () -> Unit,
    onClearCache: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavBarButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            label = "Back",
            tint = muted,
            onClick = onBack,
        )
        NavBarButton(
            icon = Icons.Default.Refresh,
            label = "Reload",
            tint = if (canReload) muted else muted.copy(alpha = 0.4f),
            enabled = canReload,
            onClick = onReload,
        )
        NavBarButton(
            icon = Icons.Default.Star,
            label = if (favorite) "Favorited" else "Favorite",
            tint = if (favorite) accent else muted.copy(alpha = 0.5f),
            onClick = onToggleFavorite,
        )
        NavBarButton(
            icon = Icons.Default.Lock,
            label = if (identifyOnFetch) "Identified" else "Anonymous",
            tint = if (identifyOnFetch) accent else muted,
            onClick = onToggleIdentify,
        )
        NavBarButton(
            icon = Icons.Default.Delete,
            label = "Clear cache",
            tint = if (canClearCache) muted else muted.copy(alpha = 0.4f),
            enabled = canClearCache,
            onClick = onClearCache,
        )
    }
}

@Composable
private fun NavBarButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
