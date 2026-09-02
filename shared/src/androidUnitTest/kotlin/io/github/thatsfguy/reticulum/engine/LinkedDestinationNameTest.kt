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
}
