package io.github.thatsfguy.reticulum.rrc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `@`-completion token handling for the room composer, shared by both
 * platforms. The rule that matters is that only the token being typed
 * at the end of the line is a candidate — completing an `@` from the
 * middle of a finished sentence would rewrite text the user has moved
 * on from.
 *
 * In commonTest rather than either app: one implementation, tested
 * once, and the Kotlin/Native run covers the iOS side of it too.
 * camelCase test names keep that compile happy.
 */
class RrcMentionInputTest {

    @Test fun theTrailingMentionIsTheToken() {
        assertEquals("al", RrcMentions.tokenAt("hey @al"))
        assertEquals("", RrcMentions.tokenAt("hey @"))
        assertEquals("alice", RrcMentions.tokenAt("@alice"))
    }

    @Test fun noAtSignMeansNoToken() {
        assertNull(RrcMentions.tokenAt("hey there"))
        assertNull(RrcMentions.tokenAt(""))
    }

    /** A finished mention is not still being typed. */
    @Test fun aCompletedMentionIsNotATokenAnyMore() {
        assertNull(RrcMentions.tokenAt("hey @alice "))
        assertNull(RrcMentions.tokenAt("hey @alice how are you"))
    }

    /** An email address is not a mention. */
    @Test fun anAtInsideAWordIsNotAMention() {
        assertNull(RrcMentions.tokenAt("mail user@example"))
        assertNull(RrcMentions.tokenAt("user@host"))
    }

    @Test fun completionReplacesOnlyTheTrailingToken() {
        assertEquals("hey @alice ", RrcMentions.replaceToken("hey @al", "alice"))
        assertEquals("@alice ", RrcMentions.replaceToken("@", "alice"))
        // Earlier text, including an earlier mention, is untouched.
        assertEquals("@bob hi @alice ", RrcMentions.replaceToken("@bob hi @al", "alice"))
    }

    @Test fun completingWithNoTokenLeavesTheDraftAlone() {
        assertEquals("plain text", RrcMentions.replaceToken("plain text", "alice"))
    }
}
