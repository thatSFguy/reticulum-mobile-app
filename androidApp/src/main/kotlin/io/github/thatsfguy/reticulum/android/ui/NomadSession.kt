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
 * The Nomad browser's navigation state, owned by the ViewModel rather
 * than by `NomadScreen`.
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
class NomadSession {
    /** Node whose page is open, or null while the directory is showing. */
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

    /** Drop back to the directory, forgetting the browsing session. */
    fun goToDirectory() {
        selected.value = null
        history.clear()
        currentPath.value = NOMAD_DEFAULT_PAGE_PATH
        pendingPostData.value = null
        currentPagePostData.value = null
        cacheInfo.value = null
        renderedKey.value = null
        pageState.value = PageState.Loading
    }
}
