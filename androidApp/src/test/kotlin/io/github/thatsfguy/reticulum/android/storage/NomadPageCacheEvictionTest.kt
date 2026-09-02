package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.thatsfguy.reticulum.engine.MAX_CACHED_PAGES
import io.github.thatsfguy.reticulum.engine.MAX_CACHED_PAGE_BYTES
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit 2026-09-02 M2: the NomadNet page cache had no bound of any kind.
 *
 * No row cap, no byte cap, and no eviction path — the DAO exposed only
 * upsert / get / delete / deleteAll and nothing called a pruner. Every
 * page fetched on a plain GET was kept forever, holding the whole
 * document, with the row size limited only by the Resource ceiling
 * (2 MB per segment, 8 MB multi-segment). It was the last
 * attacker-influenced table left unbounded after `MAX_DESTINATIONS`
 * capped the destinations one, and by bytes the more dangerous of the
 * two.
 *
 * Two failure modes, and these pin the fix for both:
 *
 *  - unbounded growth, which on this app is not cosmetic — Room's
 *    `onCorruption` DELETES the database, which is how the 2026-07-28
 *    incident cost a user their identity and messages;
 *  - a row over Android's 2 MB CursorWindow throwing on read-back,
 *    swallowed by the caller's `runCatching`, so the page silently never
 *    renders from cache again while still occupying the table.
 */
@RunWith(RobolectricTestRunner::class)
class NomadPageCacheEvictionTest {

    private lateinit var db: ReticulumDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ReticulumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() { db.close() }

    private fun dao() = db.nomadPageCacheDao()

    private suspend fun put(path: String, fetchedAt: Long, source: String = "page") {
        dao().upsert(
            NomadPageCacheEntity(
                destHash = "deadbeef0123456789abcdef01234567",
                path = path,
                source = source,
                fetchedAt = fetchedAt,
                byteSize = source.length,
            ),
        )
    }

    private suspend fun get(path: String) =
        dao().get("deadbeef0123456789abcdef01234567", path)

    // ---- the pruner --------------------------------------------------

    @Test fun `eviction keeps the newest rows and drops the rest`() = runTest {
        repeat(20) { i -> put("/page/p$i.mu", fetchedAt = 1_000L + i) }

        val deleted = dao().evictOldest(keepCount = 5)

        assertEquals(15, deleted)
        // p15..p19 are the five newest.
        for (i in 15..19) assertNotNull(get("/page/p$i.mu"), "p$i must survive")
        for (i in 0..14) assertNull(get("/page/p$i.mu"), "p$i must be gone")
    }

    @Test fun `eviction is a no-op below the cap`() = runTest {
        repeat(3) { i -> put("/page/p$i.mu", fetchedAt = 1_000L + i) }

        assertEquals(0, dao().evictOldest(keepCount = 5))
        for (i in 0..2) assertNotNull(get("/page/p$i.mu"))
    }

    @Test fun `eviction on an empty table deletes nothing`() = runTest {
        assertEquals(0, dao().evictOldest(keepCount = MAX_CACHED_PAGES))
    }

    /**
     * Rows written inside the same millisecond must still have a total
     * order, or the LIMIT subquery can pick a different set on each run
     * and churn rows it already decided to keep. The `rowid` tiebreak in
     * the query is what provides it; without it this test is flaky
     * rather than red, which is worse.
     */
    @Test fun `rows sharing a timestamp still evict deterministically`() = runTest {
        repeat(10) { i -> put("/page/p$i.mu", fetchedAt = 5_000L) }

        assertEquals(6, dao().evictOldest(keepCount = 4))
        val survivors = (0..9).count { get("/page/p$it.mu") != null }
        assertEquals(4, survivors)

        // Running it again must be stable — no further deletions, and
        // the same rows still standing.
        assertEquals(0, dao().evictOldest(keepCount = 4))
        assertEquals(4, (0..9).count { get("/page/p$it.mu") != null })
    }

    /** A re-fetch refreshes `fetchedAt`, so a page the user keeps
     *  visiting must not be the one evicted. */
    @Test fun `a re-fetched page survives eviction`() = runTest {
        repeat(10) { i -> put("/page/p$i.mu", fetchedAt = 1_000L + i) }
        put("/page/p0.mu", fetchedAt = 9_999L)  // revisited, now newest

        dao().evictOldest(keepCount = 3)

        assertNotNull(get("/page/p0.mu"), "the revisited page must survive")
    }

    // ---- the caps themselves -----------------------------------------

    /**
     * The byte cap has to sit far enough below Android's 2 MB
     * CursorWindow that a cached row can always be read back, and far
     * enough above a real page that nothing legitimate is refused. The
     * largest `.mu` page observed on a live mesh is single-digit KB.
     */
    @Test fun `the page byte cap stays inside the CursorWindow budget`() {
        assertTrue(
            MAX_CACHED_PAGE_BYTES < 2 * 1024 * 1024 / 4,
            "a cached page must be readable back through a 2 MB CursorWindow with room to spare",
        )
        assertTrue(MAX_CACHED_PAGE_BYTES >= 64 * 1024, "must not refuse a real page")
    }

    @Test fun `the worst-case cache size stays bounded`() {
        val worstCaseBytes = MAX_CACHED_PAGES.toLong() * MAX_CACHED_PAGE_BYTES
        assertTrue(
            worstCaseBytes <= 256L * 1024 * 1024,
            "row cap x byte cap must bound the table: was $worstCaseBytes B",
        )
    }
}
