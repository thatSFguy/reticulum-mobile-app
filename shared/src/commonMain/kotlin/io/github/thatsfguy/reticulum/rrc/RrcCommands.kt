package io.github.thatsfguy.reticulum.rrc

/**
 * Client-side slash-command layer for Reticulum Relay Chat.
 *
 * RRC is IRC-shaped: the composer is the command line. A `MSG` (or
 * `NOTICE`) whose body starts with `/` is **consumed by the hub as a
 * hub-local command** and never forwarded to the room
 * (`client-parity.md` §2 / `rrc-hub` `internal/hub/commands.go`
 * `handleCommand`). That single rule is what this file is built around:
 *
 *  - Commands the *client* must own — `/join`, `/part`, `/nick`,
 *    `/me`, `/clear` — are parsed here into typed intents so the app
 *    updates its own state (room rows, membership, the nick stamped on
 *    every envelope) instead of firing a blind string at the hub and
 *    drifting out of sync with it.
 *  - Everything else is passed through verbatim so a hub command this
 *    build has never heard of still works — the hub is authoritative
 *    for its own command set, and `/help` is its own answer.
 *  - [SPECS] mirrors the hub's table so the composer can offer inline
 *    completion and usage text without a round trip.
 *
 * The one liberty taken with pass-through is [roomFirstArg] rewriting;
 * see [parse].
 */
object RrcCommands {

    /** Where a slash command is handled. */
    enum class Scope {
        /** Parsed here; the app acts on it (and may still send a frame). */
        CLIENT,

        /** Forwarded to the hub, which answers with a NOTICE or ERROR. */
        HUB,
    }

    /**
     * One command the composer knows about.
     *
     * [roomFirstArg] marks the hub commands whose usage is
     * `/cmd <room> …` — a *mandatory* leading room name, which the hub
     * does not default from the envelope's `K_ROOM` (`cmdTopic` bails
     * with `usage:` when `parts` is short, unlike `cmdWho`, which falls
     * back to the envelope room). Those are the ones [parse] fills in.
     */
    data class Spec(
        val name: String,
        val usage: String,
        val summary: String,
        val scope: Scope,
        val aliases: List<String> = emptyList(),
        val roomFirstArg: Boolean = false,
        val serverOp: Boolean = false,
    ) {
        /** Every spelling that dispatches to this command. */
        val names: List<String> get() = listOf(name) + aliases
    }

    /**
     * The command table, mirroring `rrc-hub`
     * `internal/hub/commands_help.go` plus the five the client owns.
     *
     * This is a *completion* aid, not an authority: an unlisted `/foo`
     * is still sent to the hub, and the hub's own `/help` is what tells
     * the user what that hub actually supports (it is permission-aware;
     * this table cannot be).
     */
    val SPECS: List<Spec> = listOf(
        // ---- client-owned -------------------------------------------
        Spec(
            "me", "/me <action>", "send an action — “* alice waves”",
            Scope.CLIENT,
        ),
        Spec(
            "join", "/join <room> [key]", "join a room (key only for +k rooms)",
            Scope.CLIENT, aliases = listOf("j"),
        ),
        Spec(
            "part", "/part [room]", "leave a room",
            Scope.CLIENT, aliases = listOf("leave"),
        ),
        Spec(
            "nick", "/nick <name>", "change the name shown next to your messages",
            Scope.CLIENT,
        ),
        Spec(
            "clear", "/clear", "clear this room's history on this device only",
            Scope.CLIENT,
        ),

        // ---- hub, everyone ------------------------------------------
        Spec("help", "/help [command]", "list the hub's commands, or explain one", Scope.HUB),
        Spec("version", "/version", "hub version, uptime, and features", Scope.HUB),
        Spec("motd", "/motd", "re-read the hub's greeting", Scope.HUB),
        Spec("link", "/link [room]", "a link to this room you can paste anywhere", Scope.HUB),
        Spec("list", "/list", "registered public rooms", Scope.HUB),
        Spec("who", "/who [room]", "who is in a room right now", Scope.HUB, aliases = listOf("names")),
        Spec("whoami", "/whoami", "your identity and the nick the hub filed for you", Scope.HUB),
        Spec("seen", "/seen <nick|hashprefix>", "when the hub last heard from someone", Scope.HUB),
        Spec("away", "/away [reason]", "mark yourself away — mentions get sent to you", Scope.HUB),
        Spec("back", "/back", "clear /away", Scope.HUB),
        Spec("history", "/history [room] [count]", "replay recent messages", Scope.HUB),
        Spec("notify", "/notify [status|on|off|address|test]", "mention notifications", Scope.HUB),
        Spec("mentions", "/mentions [clear]", "mentions the hub is holding for you", Scope.HUB),
        Spec("topic", "/topic <room> [topic]", "read or set a room's topic", Scope.HUB, roomFirstArg = true),
        Spec("register", "/register <room>", "keep a room alive after everyone leaves", Scope.HUB, roomFirstArg = true),
        Spec("unregister", "/unregister <room>", "stop keeping a room", Scope.HUB, roomFirstArg = true),

        // ---- hub, room operators ------------------------------------
        Spec("mode", "/mode <room> [+|-flags]", "read or change room modes", Scope.HUB, roomFirstArg = true),
        Spec("kick", "/kick <room> <nick|hashprefix>", "remove someone from a room", Scope.HUB, roomFirstArg = true),
        Spec(
            "op", "/op <room> <nick|hashprefix>", "grant or remove room privileges",
            Scope.HUB, aliases = listOf("deop", "voice", "devoice"), roomFirstArg = true,
        ),
        Spec("ban", "/ban <room> add|del|list [target]", "per-room ban list", Scope.HUB, roomFirstArg = true),
        Spec("invite", "/invite <room> add|del|list [target]", "per-room invite list", Scope.HUB, roomFirstArg = true),

        // ---- hub, server operators ----------------------------------
        Spec("stats", "/stats", "hub counters and limits", Scope.HUB, serverOp = true),
        Spec("kline", "/kline add|del|list [target]", "hub-wide bans", Scope.HUB, serverOp = true),
        Spec("reload", "/reload", "re-read trust, klines and the room registry", Scope.HUB, serverOp = true),
    )

    private val byName: Map<String, Spec> =
        SPECS.flatMap { spec -> spec.names.map { it to spec } }.toMap()

    fun spec(name: String): Spec? = byName[name.removePrefix("/").lowercase()]

    /**
     * Specs whose name or an alias starts with [prefix] (with or
     * without the leading `/`), for the composer's inline completion.
     * A blank prefix lists everything; server-operator commands are
     * only offered once the user has typed enough to mean them.
     */
    fun completions(prefix: String): List<Spec> {
        val p = prefix.removePrefix("/").lowercase()
        return SPECS.filter { spec ->
            (!spec.serverOp || p.isNotEmpty()) && spec.names.any { it.startsWith(p) }
        }
    }

    /**
     * A room name as the wire wants it: no `#` sigil (that is display
     * decoration a client adds — `rrc-room-links.md` §2.2), trimmed,
     * and lower-cased so a name resolves identically against the
     * case-normalising Python hub and the case-sensitive Go one (see
     * `normalizeRrcRoom`).
     */
    fun normalizeRoom(name: String): String =
        name.trim().removePrefix("#").trim().lowercase()

    /**
     * Parse one composer line.
     *
     * [currentRoom] is the room the line was typed in (null at hub
     * level, where there is no room context). [knownRooms] is every
     * room name the client currently knows about — joined rooms plus
     * whatever the last `/list` returned — and is used for exactly one
     * thing: deciding whether the first argument of a [Spec.roomFirstArg]
     * command already names a room.
     *
     * ### Room-argument fill-in
     *
     * The hub requires an explicit room for `/topic`, `/mode`, `/kick`
     * and friends, which makes the natural thing to type in a room —
     * `/topic Tuesday maintenance window` — silently address a room
     * called "Tuesday". When the line is typed *inside* a room and its
     * first argument is not a room we know of, the current room is
     * inserted:
     *
     *     (in #ops)  /topic Tuesday maintenance  →  /topic ops Tuesday maintenance
     *     (in #ops)  /mode                       →  /mode ops
     *     (in #ops)  /topic lobby                →  /topic lobby        (unchanged)
     *
     * The rewrite is deliberately confined to those commands: `/who`,
     * `/history` and `/link` take an *optional* room that the hub
     * already defaults from the envelope, and rewriting them could only
     * ever get in the way.
     */
    fun parse(
        line: String,
        currentRoom: String? = null,
        knownRooms: Set<String> = emptySet(),
    ): RrcInput {
        val text = line.trim()
        if (text.isEmpty()) return RrcInput.Empty
        if (!text.startsWith("/")) return RrcInput.Chat(text)

        // `//text` is the IRC escape for a message that really does
        // start with a slash. It has to go out as an ACTION (type 22):
        // the hub scans MSG bodies for a leading `/` and would eat it,
        // and ACTION is explicitly not command-dispatched (§2).
        if (text.startsWith("//")) return RrcInput.Action(text.substring(1))

        val verb = text.drop(1).substringBefore(' ').lowercase()
        val rest = text.substringAfter(' ', missingDelimiterValue = "").trim()
        val spec = byName[verb]

        // /me is an ACTION carrying the whole typed line — receivers get
        // the "/me " prefix verbatim and render it as an action.
        if (verb == "me") {
            if (rest.isEmpty()) return usage("me")
            return RrcInput.Action(text)
        }

        if (spec != null && spec.scope == Scope.CLIENT) {
            return when (verb) {
                "join", "j" -> {
                    val room = normalizeRoom(rest.substringBefore(' '))
                    if (room.isEmpty()) usage("join")
                    else RrcInput.Join(room, rest.substringAfter(' ', "").trim().ifBlank { null })
                }
                "part", "leave" -> {
                    val room = normalizeRoom(rest).ifEmpty { currentRoom ?: "" }
                    if (room.isEmpty()) usage("part") else RrcInput.Part(room)
                }
                "nick" ->
                    if (rest.isEmpty()) usage("nick") else RrcInput.Nick(rest)
                "clear" ->
                    if (currentRoom == null) RrcInput.Notice("/clear only works inside a room")
                    else RrcInput.ClearHistory(currentRoom)
                else -> RrcInput.HubCommand(text)
            }
        }

        // Pass-through. Fill the room argument in where the hub demands
        // one and the user, sitting in a room, reasonably left it out.
        if (spec != null && spec.roomFirstArg && currentRoom != null) {
            val firstArg = rest.substringBefore(' ')
            val namesARoom = firstArg.isNotEmpty() &&
                normalizeRoom(firstArg) in knownRooms.map { normalizeRoom(it) }.toSet()
            if (!namesARoom) {
                val filled = "/$verb $currentRoom" + if (rest.isEmpty()) "" else " $rest"
                return RrcInput.HubCommand(filled)
            }
        }
        return RrcInput.HubCommand(text)
    }

    private fun usage(name: String): RrcInput.Notice =
        RrcInput.Notice("usage: " + (byName[name]?.usage ?: "/$name"))
}

/** What one composer line means. Produced by [RrcCommands.parse]. */
sealed interface RrcInput {

    /** Ordinary chat — send as `MSG`. */
    data class Chat(val text: String) : RrcInput

    /** Send as `ACTION` (type 22): a `/me`, or a message that has to
     *  keep a leading slash the hub would otherwise consume. */
    data class Action(val text: String) : RrcInput

    /** Join [room], creating its local row — not the hub's `/join`. */
    data class Join(val room: String, val key: String?) : RrcInput

    /** Leave [room] and clear its local `joined` flag. */
    data class Part(val room: String) : RrcInput

    /** Change the nick stamped on our envelopes, from now on. */
    data class Nick(val nick: String) : RrcInput

    /** Delete [room]'s locally stored history. Never touches the hub. */
    data class ClearHistory(val room: String) : RrcInput

    /** Forward [text] to the hub as a command; it replies NOTICE/ERROR. */
    data class HubCommand(val text: String) : RrcInput

    /** Nothing to send — show [text] inline as a system line (a usage
     *  hint, or a client-side refusal). */
    data class Notice(val text: String) : RrcInput

    /** Blank line. */
    data object Empty : RrcInput
}
