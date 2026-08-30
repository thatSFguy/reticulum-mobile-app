package io.github.thatsfguy.reticulum.rrc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RrcCommands] — what one composer line means.
 *
 * The stakes here are wire-visible: a body that starts with `/` is
 * consumed by the hub as a command and never reaches the room
 * (`client-parity.md` §2), so misreading a line either eats a message
 * or fires an unintended command.
 *
 * camelCase test names keep the iosTest Kotlin/Native compile happy.
 */
class RrcCommandsTest {

    private val rooms = setOf("general", "lobby")

    @Test fun plainTextIsChat() {
        assertEquals(RrcInput.Chat("hello room"), RrcCommands.parse("hello room"))
    }

    @Test fun blankLineIsEmpty() {
        assertEquals(RrcInput.Empty, RrcCommands.parse("   "))
    }

    @Test fun meIsAnAction() {
        assertEquals(RrcInput.Action("/me waves"), RrcCommands.parse("/me waves"))
    }

    @Test fun bareMeIsAUsageHint() {
        val out = RrcCommands.parse("/me")
        assertTrue(out is RrcInput.Notice && out.text.startsWith("usage: /me"))
    }

    /** `//text` is the IRC escape for chat that really starts with a
     *  slash — it must go out as an ACTION or the hub eats it. */
    @Test fun doubleSlashEscapesToAction() {
        assertEquals(RrcInput.Action("/etc/hosts is the file"), RrcCommands.parse("//etc/hosts is the file"))
    }

    @Test fun joinParsesRoomAndKey() {
        assertEquals(RrcInput.Join("general", null), RrcCommands.parse("/join #general"))
        assertEquals(RrcInput.Join("secret", "hunter2"), RrcCommands.parse("/join secret hunter2"))
        assertEquals(RrcInput.Join("general", null), RrcCommands.parse("/j General"))
    }

    @Test fun joinWithoutARoomIsAUsageHint() {
        val out = RrcCommands.parse("/join")
        assertTrue(out is RrcInput.Notice && out.text.startsWith("usage: /join"))
    }

    @Test fun partDefaultsToTheCurrentRoom() {
        assertEquals(RrcInput.Part("general"), RrcCommands.parse("/part", currentRoom = "general"))
        assertEquals(RrcInput.Part("lobby"), RrcCommands.parse("/leave #lobby", currentRoom = "general"))
    }

    @Test fun nickTakesTheRestOfTheLine() {
        assertEquals(RrcInput.Nick("Alice Liddell"), RrcCommands.parse("/nick Alice Liddell"))
    }

    @Test fun clearNeedsARoom() {
        assertEquals(
            RrcInput.ClearHistory("general"),
            RrcCommands.parse("/clear", currentRoom = "general"),
        )
        assertTrue(RrcCommands.parse("/clear") is RrcInput.Notice)
    }

    /** An unlisted command is still forwarded: the hub is authoritative
     *  for its own command set, and a build that has never heard of a
     *  new one must not block it. */
    @Test fun unknownCommandIsForwardedVerbatim() {
        assertEquals(
            RrcInput.HubCommand("/frobnicate the thing"),
            RrcCommands.parse("/frobnicate the thing", currentRoom = "general", knownRooms = rooms),
        )
    }

    @Test fun hubCommandWithNoRoomArgumentPassesThrough() {
        assertEquals(RrcInput.HubCommand("/list"), RrcCommands.parse("/list", currentRoom = "general"))
        // /who takes an OPTIONAL room the hub already defaults from the
        // envelope — rewriting it could only get in the way.
        assertEquals(RrcInput.HubCommand("/who"), RrcCommands.parse("/who", currentRoom = "general"))
    }

    /** `/topic <room> [text]` demands a room the hub will not infer, so
     *  the natural thing to type in a room has to be filled in. */
    @Test fun topicGetsTheCurrentRoomFilledIn() {
        assertEquals(
            RrcInput.HubCommand("/topic general Tuesday maintenance"),
            RrcCommands.parse("/topic Tuesday maintenance", currentRoom = "general", knownRooms = rooms),
        )
    }

    @Test fun topicNamingAKnownRoomIsLeftAlone() {
        assertEquals(
            RrcInput.HubCommand("/topic lobby"),
            RrcCommands.parse("/topic lobby", currentRoom = "general", knownRooms = rooms),
        )
    }

    @Test fun roomFirstCommandWithNoArgumentsGetsTheCurrentRoom() {
        assertEquals(
            RrcInput.HubCommand("/mode general"),
            RrcCommands.parse("/mode", currentRoom = "general", knownRooms = rooms),
        )
        assertEquals(
            RrcInput.HubCommand("/kick general alice"),
            RrcCommands.parse("/kick alice", currentRoom = "general", knownRooms = rooms),
        )
    }

    /** At hub level there is no room to fill in, so nothing is rewritten. */
    @Test fun noRoomContextMeansNoFillIn() {
        assertEquals(RrcInput.HubCommand("/topic lobby hi"), RrcCommands.parse("/topic lobby hi"))
    }

    @Test fun roomNamesLoseTheirSigilAndCase() {
        assertEquals("general", RrcCommands.normalizeRoom("  #General  "))
    }

    @Test fun completionsMatchNameAndAlias() {
        assertTrue(RrcCommands.completions("/j").any { it.name == "join" })
        assertTrue(RrcCommands.completions("na").any { it.name == "who" })  // alias /names
        // Operator commands are not offered until they're being typed.
        assertTrue(RrcCommands.completions("").none { it.serverOp })
        assertTrue(RrcCommands.completions("kl").any { it.name == "kline" })
    }
}
