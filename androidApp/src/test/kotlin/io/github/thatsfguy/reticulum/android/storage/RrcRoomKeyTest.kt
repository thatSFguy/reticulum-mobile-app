package io.github.thatsfguy.reticulum.android.storage

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `hubHash/room` key every per-room map is indexed by.
 *
 * Trivial-looking, and worth pinning anyway: this existed as a string
 * template repeated at seven call sites, two of which were subtly
 * mis-escaped and compiled to the LITERAL text `${hub.destHash}/$room`.
 * Neither failed loudly — the hub unread badge just always summed to
 * zero and the reply banner just never appeared. A key builder that is
 * a function can be asserted; one that is a template at each site
 * cannot.
 */
class RrcRoomKeyTest {

    private val hub = "ab".repeat(16)

    @Test fun keyJoinsHubAndRoom() {
        assertEquals("$hub/general", rrcRoomKey(hub, "general"))
    }

    @Test fun theHubPrefixMatchesEveryRoomOnThatHub() {
        val prefix = rrcHubKeyPrefix(hub)
        assertTrue(rrcRoomKey(hub, "general").startsWith(prefix))
        assertTrue(rrcRoomKey(hub, "lobby").startsWith(prefix))
    }

    @Test fun theHubPrefixDoesNotMatchAnotherHub() {
        val other = "cd".repeat(16)
        assertTrue(!rrcRoomKey(other, "general").startsWith(rrcHubKeyPrefix(hub)))
    }

    /** The literal-template bug in one assertion: a key must contain
     *  the actual values, never the text of the expression. */
    @Test fun theKeyIsInterpolatedNotLiteral() {
        val key = rrcRoomKey(hub, "general")
        assertTrue(key.contains(hub), "key lost the hub hash: $key")
        assertTrue(key.contains("general"), "key lost the room: $key")
        assertTrue(!key.contains("$"), "key contains a template marker: $key")
    }
}
