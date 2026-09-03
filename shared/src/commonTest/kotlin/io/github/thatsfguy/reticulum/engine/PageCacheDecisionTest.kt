package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #52. A page that opts out of caching used to leave whatever row
 * the cache already held, so the browser kept showing "Last pulled 8h
 * ago" beside content it had just re-fetched — the page rendered fresh,
 * the timestamp was stale, and "Clear cache" was offered for a page we
 * were not caching.
 */
class PageCacheDecisionTest {

    private fun decide(isPost: Boolean = false, ttl: Int? = null, size: Int = 1_000) =
        ReticulumEngine.pageCacheDecision(isPost, ttl, size)

    @Test fun plainGetIsStored() {
        assertEquals(ReticulumEngine.PageCacheAction.Store, decide())
    }

    @Test fun serverOptOutDropsTheExistingRow() {
        val d = decide(ttl = 0)
        assertTrue(d is ReticulumEngine.PageCacheAction.Drop, "expected Drop, got $d")
        assertTrue("#!c=0" in d.why, "reason should name the header: ${d.why}")
    }

    @Test fun aPositiveTtlStillCaches() {
        // Only a literal 0 means "do not cache" — `#!c=300` is a hint we
        // do not act on yet, not an opt-out.
        assertEquals(ReticulumEngine.PageCacheAction.Store, decide(ttl = 300))
    }

    @Test fun oversizePageDropsTheExistingRow() {
        val d = decide(size = MAX_CACHED_PAGE_BYTES + 1)
        assertTrue(d is ReticulumEngine.PageCacheAction.Drop, "expected Drop, got $d")
        assertTrue("cap" in d.why, "reason should name the cap: ${d.why}")
    }

    @Test fun exactlyAtTheCapIsStillStored() {
        assertEquals(ReticulumEngine.PageCacheAction.Store, decide(size = MAX_CACHED_PAGE_BYTES))
    }

    @Test fun postResponsesNeitherWriteNorDrop() {
        // A form result was never cached, so there is nothing of its own
        // to drop — and dropping the GET row for the same path would
        // throw away a good cache entry.
        assertEquals(ReticulumEngine.PageCacheAction.Ignore, decide(isPost = true))
        assertEquals(ReticulumEngine.PageCacheAction.Ignore, decide(isPost = true, ttl = 0))
        assertEquals(
            ReticulumEngine.PageCacheAction.Ignore,
            decide(isPost = true, size = MAX_CACHED_PAGE_BYTES + 1),
        )
    }
}
