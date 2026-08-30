package io.github.thatsfguy.reticulum.rrc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RrcMentions] — the two forms the hub itself resolves
 * (`client-parity.md` §8): `@nick`, and `@` plus 6+ hex characters of
 * an identity hash.
 *
 * camelCase test names keep the iosTest Kotlin/Native compile happy.
 */
class RrcMentionsTest {

    private val hash = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test fun nickMentionMatchesCaseInsensitively() {
        assertTrue(RrcMentions.namesUs("hey @Alice can you look?", "alice", hash))
    }

    @Test fun trailingPunctuationIsNotPartOfTheNick() {
        assertTrue(RrcMentions.namesUs("@alice, are you there?", "alice", hash))
        assertTrue(RrcMentions.namesUs("ping @alice.", "alice", hash))
    }

    @Test fun aDifferentNickIsNotUs() {
        assertFalse(RrcMentions.namesUs("@alicia hello", "alice", hash))
        assertFalse(RrcMentions.namesUs("email alice@example.org", "bob", hash))
    }

    @Test fun hashPrefixMatchesFromSixCharacters() {
        assertTrue(RrcMentions.namesUs("@a1b2c3 ping", null, hash))
        assertTrue(RrcMentions.namesUs("@A1B2C3D4 ping", null, hash))
    }

    /** Five characters is below the hub's threshold and far too likely
     *  to collide — the point of the hash form is certainty. */
    @Test fun shortHashPrefixDoesNotMatch() {
        assertFalse(RrcMentions.namesUs("@a1b2c ping", null, hash))
    }

    @Test fun aDifferentHashPrefixIsNotUs() {
        assertFalse(RrcMentions.namesUs("@ffffff ping", null, hash))
    }

    @Test fun textWithoutAnAtSignIsNeverAMention() {
        assertFalse(RrcMentions.namesUs("alice, are you there?", "alice", hash))
    }

    @Test fun noNickStillMatchesOnHash() {
        assertTrue(RrcMentions.namesUs("@a1b2c3d4 hi", null, hash))
        assertFalse(RrcMentions.namesUs("@alice hi", null, hash))
    }
}
