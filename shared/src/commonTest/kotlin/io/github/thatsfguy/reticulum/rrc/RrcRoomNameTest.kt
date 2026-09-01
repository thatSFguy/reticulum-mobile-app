package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.nomad.LinkTarget
import io.github.thatsfguy.reticulum.nomad.parseLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** camelCase test names keep the Kotlin/Native compile happy. */
class RrcRoomNameTest {

    @Test fun plainNamesAreFine() {
        for (n in listOf("general", "ops", "room2", "dev-team", "dev_team", "a")) {
            assertNull(RrcRoomName.problem(n), "'$n' should be creatable")
        }
    }

    /** The sigil is display decoration, stripped before the check. */
    @Test fun aLeadingHashIsFine() {
        assertNull(RrcRoomName.problem("#general"))
        assertNull(RrcRoomName.problem("  #General  "))
    }

    @Test fun whitespaceIsRejectedWithASpecificReason() {
        val p = RrcRoomName.problem("off topic")
        assertNotNull(p)
        assertTrue(p.contains("space", ignoreCase = true), p)
        assertNotNull(RrcRoomName.problem("tab\there"))
    }

    @Test fun punctuationAndSymbolsAreRejected() {
        for (n in listOf("ops!", "a.b", "a/b", "a:b", "a@b", "100%", "c++", "a,b", "#a#b", "🎉")) {
            assertNotNull(RrcRoomName.problem(n), "'$n' should be rejected")
        }
    }

    @Test fun hyphensAndUnderscoresAreExplicitlyAllowed() {
        assertNull(RrcRoomName.problem("a-b_c"))
        assertNull(RrcRoomName.problem("-_-"))
    }

    /** The objection is whitespace and punctuation, not non-Latin text. */
    @Test fun nonLatinLettersAndDigitsAreAllowed() {
        assertNull(RrcRoomName.problem("日本語"))
        assertNull(RrcRoomName.problem("café"))
        assertNull(RrcRoomName.problem("привет"))
    }

    @Test fun emptyIsRejected() {
        assertNotNull(RrcRoomName.problem(""))
        assertNotNull(RrcRoomName.problem("   "))
        assertNotNull(RrcRoomName.problem("#"))
    }

    @Test fun overLongIsRejectedInBytesNotChars() {
        assertNull(RrcRoomName.problem("a".repeat(RrcRoomName.MAX_BYTES)))
        assertNotNull(RrcRoomName.problem("a".repeat(RrcRoomName.MAX_BYTES + 1)))
        // 3 bytes per char in UTF-8, so 22 chars is fine and 22+ tips over.
        assertNotNull(RrcRoomName.problem("日".repeat(RrcRoomName.MAX_BYTES / 3 + 1)))
    }

    /**
     * The rule is for the CREATE path only. A name we would refuse to
     * create must still be reachable when it already exists — via a
     * room link, or the `/list` browser — or a cosmetic preference
     * becomes an interop bug.
     */
    @Test fun aRefusedNameIsStillReachableByLink() {
        val hash = "a4383b4658729ab8e204e89724e2b383"
        assertNotNull(RrcRoomName.problem("off topic"))
        assertEquals(
            LinkTarget.RrcRoom(hash, "off topic"),
            parseLinkTarget("rrc@$hash:/room/off%20topic"),
        )
    }

    /** And a link we WRITE for such a room still round-trips — sharing
     *  a room someone else made is not creating one. */
    /**
     * A name this client refuses to *create* is still a real room
     * somebody else made, and must stay shareable — the house rule is
     * local policy, not a link constraint.
     *
     * Whitespace is the exception, and it is a link-grammar limit rather
     * than a policy one: v2 emits the room segment literally (NomadNet
     * does no percent-decoding), and a link in running text ends at the
     * first space. Such a room gets no link at all rather than one that
     * joins a shorter, different name.
     */
    @Test fun aRefusedNameStillProducesAValidShareLink() {
        val hash = "a4383b4658729ab8e204e89724e2b383"
        val link = RrcRoomLink.build(hash, "a:b@c")
        assertNotNull(link)
        assertEquals(LinkTarget.RrcRoom(hash, "a:b@c"), parseLinkTarget(link))
    }

    @Test fun aNameWithWhitespaceCannotBeSharedAtAll() {
        val hash = "a4383b4658729ab8e204e89724e2b383"
        assertTrue(!RrcRoomName.isCreatable("off topic"))
        assertNull(RrcRoomLink.build(hash, "off topic"))
    }

    @Test fun isCreatableMatchesProblem() {
        assertTrue(RrcRoomName.isCreatable("general"))
        assertTrue(!RrcRoomName.isCreatable("off topic"))
    }
}
