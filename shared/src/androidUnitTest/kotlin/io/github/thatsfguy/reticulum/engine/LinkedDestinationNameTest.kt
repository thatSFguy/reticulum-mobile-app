package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.InMemoryDestRepo
import io.github.thatsfguy.reticulum.InMemoryIdentityRepo
import io.github.thatsfguy.reticulum.InMemoryMsgRepo
import io.github.thatsfguy.reticulum.TestVectors
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a link-discovered destination's name lands, and why it is not
 * where a manually-added one's goes.
 *
 * `addManualDestination` writes its label to `userLabel` — the user's
 * private nickname, which `effectiveDisplayName` prefers over
 * everything. Passing a provenance string like "(via cross-node link)"
 * through it pinned that string as the row's name FOREVER: the node's
 * announce arrived, `displayName` was filled in correctly, and the list
 * kept showing the placeholder because `userLabel` outranks it.
 *
 * `addLinkedDestination` exists to keep the two apart. These tests pin
 * the three properties that make it safe: it never touches `userLabel`,
 * its hint is provisional, and a real name already on the row wins.
 */
class LinkedDestinationNameTest {

    private val hash = "a4383b4658729ab8e204e89724e2b383"

    private fun TestScope.newEngine(destRepo: InMemoryDestRepo) = ReticulumEngine(
        crypto = TestVectors.crypto,
        identityRepo = InMemoryIdentityRepo(),
        destinationRepo = destRepo,
        messageRepo = InMemoryMsgRepo(),
        scope = this,
        nowMs = { 1_700_000_000_000L },
        displayNameProvider = { "Test" },
    )

    @Test fun `the link label becomes the provisional display name, not a user label`() = runTest {
        val repo = InMemoryDestRepo()
        newEngine(repo).addLinkedDestination(hash, "Amber Pages")

        val row = repo.get(hash)!!
        assertEquals("Amber Pages", row.displayName)
        assertNull(row.userLabel, "a link label is not the user's nickname and must not outrank an announce")
        assertEquals("Amber Pages", row.effectiveDisplayName)
    }

    /**
     * The hint names a row this call CREATES and never overwrites one
     * that exists — `upsertManualStub` touches only favorite / hidden /
     * userLabel on an existing row, so a name already there stands
     * whether it came from an announce or an earlier link.
     */
    @Test fun `a name already on the row is never replaced by a later hint`() = runTest {
        val repo = InMemoryDestRepo()
        val engine = newEngine(repo)
        engine.addLinkedDestination(hash, "Amber Pages")

        engine.addLinkedDestination(hash, "some other link's words for it")

        val row = repo.get(hash)!!
        assertEquals("Amber Pages", row.displayName)
        assertNull(row.userLabel)
    }

    /** A user nickname is theirs; following a link to the same node
     *  must not disturb it, and must not add one either. */
    @Test fun `a user label already on the row is left alone`() = runTest {
        val repo = InMemoryDestRepo()
        val engine = newEngine(repo)
        engine.addManualDestination(hash, "Dad's node")

        engine.addLinkedDestination(hash, "Amber Pages")

        val row = repo.get(hash)!!
        assertEquals("Dad's node", row.userLabel)
        assertEquals("Dad's node", row.effectiveDisplayName)
    }

    /**
     * Walking past a node on the way to a page is not intent to pin it.
     * Auto-favouriting these is what filled the Nomad and Nodes lists
     * with rows the user never chose, which is how the placeholder-name
     * bug came to be reported as "many pages".
     */
    @Test fun `a link-discovered node is not favourited`() = runTest {
        val repo = InMemoryDestRepo()
        newEngine(repo).addLinkedDestination(hash, "Amber Pages")

        assertEquals(false, repo.get(hash)!!.favorite)
    }

    /** …and following a link to one the user HAS favourited must not
     *  un-favourite it. The linked path only ever clears `hidden`. */
    @Test fun `an existing favourite stays favourited`() = runTest {
        val repo = InMemoryDestRepo()
        val engine = newEngine(repo)
        engine.addManualDestination(hash, "Dad's node")   // favourites it
        assertEquals(true, repo.get(hash)!!.favorite)

        engine.addLinkedDestination(hash, "Amber Pages")

        assertEquals(true, repo.get(hash)!!.favorite)
    }

    @Test fun `no hint leaves the row unnamed rather than labelled with our own prose`() = runTest {
        val repo = InMemoryDestRepo()
        newEngine(repo).addLinkedDestination(hash, "")

        val row = repo.get(hash)!!
        assertEquals("", row.displayName)
        assertNull(row.userLabel)
    }

    /** A link label is authored by whoever wrote the page — untrusted
     *  text landing in a list row, so it is bounded like an
     *  announce-extracted name. */
    @Test fun `an absurd link label is truncated`() = runTest {
        val repo = InMemoryDestRepo()
        newEngine(repo).addLinkedDestination(hash, "x".repeat(5_000))

        assertEquals(MAX_LINK_NAME_HINT, repo.get(hash)!!.displayName.length)
    }

    /**
     * Audit 2026-09-02 L1. Bounding the LENGTH was never enough: the
     * announce path has stripped control characters since the
     * 2026-07-28 M-2 fix, and this path — the same class of untrusted
     * string, landing in the same `displayName` slot — did not. A label
     * carrying newlines renders as a multi-line list row; one carrying a
     * bidi override reorders the text around it.
     *
     * Mutation check: reverting `addLinkedDestination` to
     * `nameHint.trim().take(...)` turns both of these red.
     */
    @Test fun `control characters are stripped from a link label`() = runTest {
        val repo = InMemoryDestRepo()
        newEngine(repo).addLinkedDestination(hash, "Amber\nPages\u0000\u202E")

        assertEquals("AmberPages", repo.get(hash)!!.displayName)
    }

    @Test fun `a label of nothing but control characters leaves the row unnamed`() = runTest {
        val repo = InMemoryDestRepo()
        newEngine(repo).addLinkedDestination(hash, "\n\r\u0000")

        val row = repo.get(hash)!!
        assertEquals("", row.displayName)
        assertNull(row.userLabel)
    }

    /** Spaces are the one non-alphanumeric that survives — a real page
     *  title has them, and `sanitizeDisplayName` keeps them by design. */
    /**
     * The counterpart to the strip: only the DIRECTIONAL set goes.
     * U+200D ZERO WIDTH JOINER is also a format character and holds
     * multi-part emoji together, and people put emoji in names — a
     * blanket "strip category Cf" would break them.
     */
    @Test fun `a joined emoji in a link label survives`() = runTest {
        val repo = InMemoryDestRepo()
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        newEngine(repo).addLinkedDestination(hash, "Family $family")

        assertEquals("Family $family", repo.get(hash)!!.displayName)
    }

    @Test fun `interior spaces in a link label survive`() = runTest {
        val repo = InMemoryDestRepo()
        newEngine(repo).addLinkedDestination(hash, "  Amber Pages  ")

        assertEquals("Amber Pages", repo.get(hash)!!.displayName)
    }
}
