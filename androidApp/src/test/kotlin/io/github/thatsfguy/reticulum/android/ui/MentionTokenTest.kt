package io.github.thatsfguy.reticulum.android.ui

import io.github.thatsfguy.reticulum.android.ui.screens.mentionTokenAt
import io.github.thatsfguy.reticulum.android.ui.screens.replaceMentionToken
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `@`-completion token handling for the room composer. The rule that
 * matters is that only the token being typed at the end of the line is
 * a candidate — completing an `@` from the middle of a finished
 * sentence would rewrite text the user has moved on from.
 */
class MentionTokenTest {

    @Test fun theTrailingMentionIsTheToken() {
        assertEquals("al", mentionTokenAt("hey @al"))
        assertEquals("", mentionTokenAt("hey @"))
        assertEquals("alice", mentionTokenAt("@alice"))
    }

    @Test fun noAtSignMeansNoToken() {
        assertNull(mentionTokenAt("hey there"))
        assertNull(mentionTokenAt(""))
    }

    /** A finished mention is not still being typed. */
    @Test fun aCompletedMentionIsNotATokenAnyMore() {
        assertNull(mentionTokenAt("hey @alice "))
        assertNull(mentionTokenAt("hey @alice how are you"))
    }

    /** An email address is not a mention. */
    @Test fun anAtInsideAWordIsNotAMention() {
        assertNull(mentionTokenAt("mail user@example"))
        assertNull(mentionTokenAt("user@host"))
    }

    @Test fun completionReplacesOnlyTheTrailingToken() {
        assertEquals("hey @alice ", replaceMentionToken("hey @al", "alice"))
        assertEquals("@alice ", replaceMentionToken("@", "alice"))
        // Earlier text, including an earlier mention, is untouched.
        assertEquals("@bob hi @alice ", replaceMentionToken("@bob hi @al", "alice"))
    }

    @Test fun completingWithNoTokenLeavesTheDraftAlone() {
        assertEquals("plain text", replaceMentionToken("plain text", "alice"))
    }
}
