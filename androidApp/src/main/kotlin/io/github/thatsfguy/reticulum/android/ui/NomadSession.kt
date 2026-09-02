package io.github.thatsfguy.reticulum.android.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredNomadPage

/** Path a node serves as its front page (`Browser.py:73` DEFAULT_PATH). */
const val NOMAD_DEFAULT_PAGE_PATH = "/page/index.mu"

/** What the Nomad page area is currently showing. */
sealed class PageState {
    object Loading : PageState()
    data class Loaded(val source: String) : PageState()
    /** Fresh fetch failed but we have cached content — show cache + a notice. */
    data class LoadedStale(val source: String, val staleReason: String) : PageState()
    data class Error(val message: String) : PageState()
}

/**
 * One entry on the Nomad in-page Back stack. Carries the POST data that
 * produced the page so Back can replay a form submit rather than
 * reverting to the empty form (v1.2.15).
 */
data class NomadHistoryEntry(
    val dest: StoredDestination,
    val path: String,
    val postData: Map<String, String>?,
)

/**
 * One tab of the Nomad browser: everything needed to render and
 * navigate a single browsing session. Owned by the ViewModel (via
 * [NomadSession]) rather than by `NomadScreen`.
 *
 * ## Why this is not `remember`ed in the screen
 *
 * It was, until 2026-09-02. `NomadScreen` is a NavHost destination, and
 * the NavHost disposes a destination's composition when you switch
 * tabs. `saveState = true` preserves `rememberSaveable`, NOT plain
 * `remember` — so glancing at Messages and coming back silently threw
 * away the open page AND its whole Back history, dropping the user on
 * the node directory.
 *
 * What made it read as a glitch rather than a design was that the
 * screen *half* remembered: `nomadSearch` and `nomadFilter` already
 * lived in the ViewModel as StateFlows, so the search box and the
 * filter chip came back exactly as they were, over an empty list, with
 * the page gone. Same defect the Rooms tab had in 1.2.104 — nav state
 * `remember`ed inside a screen the NavHost destroys — fixed there the
 * same way.
 *
 * ## Why Compose state and not StateFlow
 *
 * The rest of `ReticulumViewModel` is `MutableStateFlow`, and this
 * deliberately is not. These fields are read and written from one
 * composable in about thirty places; as StateFlows every write becomes
 * a setter call and every read a `collectAsState`, which is a large
 * mechanical diff across navigation code whose correctness is subtle
 * (see the de-keying comments on [history] and on the fetch effect).
 * Holding `MutableState` here lets the screen keep `var x by
 * session.x` and leaves those call sites untouched, so the hoist moves
 * the state's OWNER without touching its logic. The Compose types stay
 * in this UI-layer class; the ViewModel only holds a reference.
 */
class NomadTab(val id: Long) {
    /** Node whose page is open, or null while this tab shows the
     *  directory. A tab is one or the other; the directory is a tab
     *  STATE, not a separate screen, which is what makes "new tab" and
     *  "go home" the same gesture. */
    val selected = mutableStateOf<StoredDestination?>(null)
    val pageState = mutableStateOf<PageState>(PageState.Loading)
    val cacheInfo = mutableStateOf<StoredNomadPage?>(null)
    /** Bumped to force a re-fetch of the same (dest, path). */
    val reloadKey = mutableStateOf(0)
    val currentPath = mutableStateOf(NOMAD_DEFAULT_PAGE_PATH)

    /** When set, the next fetch carries form-field POST data. */
    val pendingPostData = mutableStateOf<Map<String, String>?>(null)
    /** POST data that produced the page currently rendered (null = GET). */
    val currentPagePostData = mutableStateOf<Map<String, String>?>(null)

    /**
     * In-page Back stack, per `Browser.py:907-936`. Deliberately NOT
     * keyed on the selected node: a cross-node hop changes `selected`,
     * and wiping the stack there sent Back straight out to the
     * directory (v1.2.16). It is cleared explicitly when the user picks
     * a fresh node from the directory.
     */
    val history = mutableStateListOf<NomadHistoryEntry>()

    /**
     * `hash|path|reloadKey` of the page [pageState] is currently
     * showing, or null when nothing has rendered yet.
     *
     * This is what stops a tab swap costing a round trip. A
     * `LaunchedEffect` re-runs when its composable is recreated even
     * though its keys are unchanged, so without this, returning to the
     * tab would re-fetch every time — and for a page reached by
     * submitting a form, `pendingPostData` has already been consumed,
     * so the re-fetch would be a plain GET that lands on the empty form
     * instead of the results. That is precisely the bug v1.2.15 fixed
     * for Back, re-created by walking away and coming back.
     */
    val renderedKey = mutableStateOf<String?>(null)

    /**
     * Per-tab, and per-tab is the point: identifying reveals the user's
     * long-term identity hash to the node operator (SPEC §11.6.6). One
     * toggle shared across tabs would silently identify you on a node
     * you had deliberately opened anonymously, the moment you turned it
     * on somewhere else. It still resets to OFF whenever this tab
     * changes node, and a NEW tab always starts anonymous.
     */
    val identifyOnFetch = mutableStateOf(false)

    /**
     * Point this tab at [dest] (or null for the directory), resetting
     * the identify opt-in when the node actually changes.
     *
     * A `LaunchedEffect` keyed on the selected hash cannot do this job
     * once tabs exist: it also fires when you SWITCH to a tab whose node
     * differs, which would silently clear an opt-in the user had made in
     * that tab. Resetting at the point of change is the only place that
     * can tell "this tab moved" from "a different tab is showing".
     */
    fun selectNode(dest: StoredDestination?) {
        if (dest?.hash != selected.value?.hash) identifyOnFetch.value = false
        selected.value = dest
    }

    /** What to call this tab in the switcher. */
    fun label(): String {
        val node = selected.value ?: return "Nodes"
        val name = node.effectiveDisplayName.ifBlank { node.hash.take(8) }
        return name
    }

    /** Drop back to the directory, forgetting this tab's browsing state. */
    fun goToDirectory() {
        selected.value = null
        history.clear()
        currentPath.value = NOMAD_DEFAULT_PAGE_PATH
        pendingPostData.value = null
        currentPagePostData.value = null
        cacheInfo.value = null
        renderedKey.value = null
        pageState.value = PageState.Loading
        identifyOnFetch.value = false
    }
}

/**
 * The set of open tabs and which one is showing.
 *
 * Modelled on a phone browser rather than a desktop one: no persistent
 * tab strip eating vertical space on every page, just a counter in the
 * toolbar that opens a full-screen switcher. A strip would cost a row
 * forever to save one tap occasionally.
 *
 * ## Why the cap
 *
 * [MAX_TABS] is not a memory limit — a tab is a handful of strings. It
 * is an airtime limit. A tab parked on a node keeps that node's
 * Reticulum link cached in the engine, keepalives and all, because
 * reusing it is what makes intra-node navigation cost one LRPROOF
 * instead of one per page. Over LoRa, links held open for pages nobody
 * is reading are the expensive kind of idle. Closing a tab tears its
 * link down explicitly (`ReticulumEngine.closeNomadLink`); the cap
 * bounds what can accumulate before then.
 *
 * A time-based reaper — drop a background tab's link after some minutes
 * idle, re-establish on return — would be better than a cap, and is the
 * obvious next step. It is engine work with its own failure modes, so
 * this ships with the cap first.
 */
class NomadSession {
    val tabs = mutableStateListOf<NomadTab>()
    /** Index into [tabs]. Kept in range by every mutator here. */
    val activeIndex = mutableStateOf(0)

    private var nextId = 1L

    init { newTab() }

    val active: NomadTab
        get() {
            if (tabs.isEmpty()) newTab()
            val i = activeIndex.value.coerceIn(0, tabs.lastIndex)
            if (i != activeIndex.value) activeIndex.value = i
            return tabs[i]
        }

    val canOpenMore: Boolean get() = tabs.size < MAX_TABS

    /** Open a new tab showing the directory and switch to it. Returns
     *  the existing active tab unchanged when the cap is reached. */
    fun newTab(): NomadTab {
        if (tabs.size >= MAX_TABS) return active
        val tab = NomadTab(nextId++)
        tabs.add(tab)
        activeIndex.value = tabs.lastIndex
        return tab
    }

    fun switchTo(index: Int) {
        if (index in tabs.indices) activeIndex.value = index
    }

    /**
     * Close [id] and return the destination hash whose link should be
     * torn down, or null when there is nothing to tear down (the tab
     * was showing the directory, or another open tab is still on that
     * same node).
     *
     * Never leaves zero tabs: closing the last one leaves a fresh
     * directory tab, so the browser always has somewhere to be.
     */
    fun closeTab(id: Long): String? {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return null
        val closing = tabs.removeAt(index)
        val hash = closing.selected.value?.hash
        if (tabs.isEmpty()) {
            newTab()
        } else if (activeIndex.value >= tabs.size) {
            activeIndex.value = tabs.lastIndex
        } else if (activeIndex.value > index) {
            activeIndex.value -= 1
        }
        // Another tab still reading that node needs the link.
        if (hash != null && tabs.any { it.selected.value?.hash == hash }) return null
        return hash
    }
}

/** See [NomadSession] for why this is an airtime limit, not a memory one. */
const val MAX_TABS = 8
