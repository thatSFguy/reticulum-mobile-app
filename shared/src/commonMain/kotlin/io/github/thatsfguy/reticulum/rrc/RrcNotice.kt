package io.github.thatsfguy.reticulum.rrc

/**
 * Structured form of a hub NOTICE.
 *
 * The RRC hub broadcasts room-state changes as plain-text NOTICEs in
 * fixed formats (`reticulum-relay-chat/docs/client-parity.md` §3, §4).
 * [RrcNotices.classify] recognises the structured shapes so the client
 * can surface a room's topic / modes as proper UI state, keep a history
 * replay out of unread counts (§7), and treat a mention alert as a
 * mention (§8) — instead of only showing the raw NOTICE text.
 *
 * Matching is deliberately conservative — anything that doesn't fit a
 * known shape exactly degrades to [Plain], so a hub wording change can
 * only ever cost the structured surfacing, never lose the NOTICE (the
 * raw text is still shown regardless).
 */
sealed interface RrcNotice {
    /** `topic for <room> is now: <topic>` — [topic] is null when cleared. */
    data class Topic(val room: String, val topic: String?) : RrcNotice

    /** `mode for <room> is now: <modes>` — [modes] is "" when `(none)`. */
    data class Mode(val room: String, val modes: String) : RrcNotice

    /**
     * `room <r>: <registered|unregistered>; mode=<modes>; topic=<topic>`
     * — the room-info line the joiner receives right after JOINED (§4).
     */
    data class RoomInfo(
        val room: String,
        val registered: Boolean,
        val modes: String,
        val topic: String?,
    ) : RrcNotice

    /**
     * The reply to a `/list` command — the hub's registered, non-private
     * rooms (§2). [rooms] is empty when the hub has none registered.
     */
    data class RoomList(val rooms: List<RrcRoomListing>) : RrcNotice

    /**
     * The opening bracket of a room-history replay (`client-parity.md`
     * §7) — `--- 3 messages from earlier ---`, or
     * `--- 3 messages from the last 2h ---` when the replay was asked
     * for with a window. The envelopes that follow are the *originals*
     * (same `K_ID`, `K_TS`, `K_SRC`), so a client that recognises the
     * bracket can keep them out of unread counts and notifications.
     */
    data class HistoryStart(val count: Int) : RrcNotice

    /** The closing bracket of a history replay — `--- end of history ---`. */
    object HistoryEnd : RrcNotice

    /**
     * The hub telling us we were named in a room we are not in (§8):
     * `you were mentioned in #<room> by <who>: <text>`.
     */
    data class Mentioned(val room: String, val text: String) : RrcNotice

    /** `--- N mention(s) while you were away ---`, followed by one
     *  NOTICE per held mention (§8). */
    data class HeldMentions(val count: Int) : RrcNotice

    /**
     * The reply to `/who` — `members in <room>: <a>, <b>` (§2), where
     * each entry is `nick (hashprefix)`, a bare identity hash when the
     * member has set no nick, or `(unidentified)`; any of them may
     * carry a trailing ` [away]`.
     *
     * Parsed so the composer can offer `@`-completion from the people
     * actually in the room, rather than only those who have spoken
     * recently. [members] is empty for a room with nobody in it.
     */
    data class Who(val room: String, val members: List<RrcMember>) : RrcNotice

    /** An informational NOTICE carrying no structured room state. */
    object Plain : RrcNotice
}

/**
 * One entry of a `/who` reply. [nick] is null when the member has set
 * none — RRC nicknames are advisory and not unique, which is why
 * [hashPrefix] is carried alongside and is what `@`-completion falls
 * back to (an `@` plus 6+ hex characters is the exact form).
 */
data class RrcMember(
    val nick: String?,
    val hashPrefix: String,
    val away: Boolean = false,
)

/** One entry in a `/list` reply — a registered public room. */
data class RrcRoomListing(val name: String, val topic: String?)

/** Classifier for hub NOTICE text — see [RrcNotice]. */
object RrcNotices {

    /**
     * A room name lifted out of a NOTICE, normalised the way the hub
     * normalises one (`normalizeRoomName`): trim, strip leading `#`,
     * trim, lower-case.
     *
     * The hub's own wording is inconsistent about the sigil — a topic
     * broadcast names the room bare, a mention alert writes `#room` —
     * and the UI keys its per-room state on the normalised name. A
     * parsed name that keeps a `#` therefore lands in a bucket nothing
     * reads: the topic bar silently stays empty. Normalise at the
     * boundary, once.
     */
    private fun noticeRoom(raw: String): String =
        raw.trim().trimStart('#').trim().lowercase()

    private const val IS_NOW = " is now: "
    private const val MENTIONED_IN = "you were mentioned in "
    private const val MEMBERS_IN = "members in "
    private const val AWAY_SUFFIX = " [away]"

    /** `--- 3 messages from earlier ---` / `--- 1 message from the last 2h ---`. */
    private val HISTORY_START = Regex("""--- (\d+) messages? from .+ ---""")

    /** `--- 2 mention(s) while you were away ---`. */
    private val HELD_MENTIONS = Regex("""--- (\d+) mention\(s\) while you were away ---""")

    fun classify(text: String): RrcNotice =
        topicOf(text) ?: modeOf(text) ?: roomInfoOf(text) ?: roomListOf(text)
            ?: historyOf(text) ?: mentionOf(text) ?: whoOf(text) ?: RrcNotice.Plain

    /** `members in <room>: nick (hash), bare-hash [away], …` (§2). */
    private fun whoOf(t: String): RrcNotice.Who? {
        if (!t.startsWith(MEMBERS_IN)) return null
        val rest = t.removePrefix(MEMBERS_IN)
        val room = noticeRoom(rest.substringBefore(": ", missingDelimiterValue = ""))
        if (room.isEmpty()) return null
        val listPart = rest.substringAfter(": ", missingDelimiterValue = "").trim()
        if (listPart.isEmpty() || listPart == "(none)") {
            return RrcNotice.Who(room, emptyList())
        }
        val members = listPart.split(", ").mapNotNull { entry -> memberOf(entry.trim()) }
        return RrcNotice.Who(room, members)
    }

    private fun memberOf(entry: String): RrcMember? {
        if (entry.isEmpty()) return null
        val away = entry.endsWith(AWAY_SUFFIX)
        val core = (if (away) entry.removeSuffix(AWAY_SUFFIX) else entry).trim()
        if (core.isEmpty() || core == "(unidentified)") return null
        // `nick (hashprefix)` — the shape the hub uses when a nick is set.
        val open = core.indexOf(" (")
        if (open > 0 && core.endsWith(")")) {
            val nick = core.take(open).trim()
            val hash = core.substring(open + 2, core.length - 1).trim()
            if (nick.isNotEmpty() && hash.isNotEmpty()) return RrcMember(nick, hash, away)
        }
        // Otherwise a bare identity hash — no nick to complete on, but
        // still a member, and `@<hashprefix>` names them exactly.
        return RrcMember(null, core, away)
    }

    /** `--- N message[s] from … ---` / `--- end of history ---` (§7). */
    private fun historyOf(t: String): RrcNotice? {
        if (t == "--- end of history ---") return RrcNotice.HistoryEnd
        val m = HISTORY_START.matchEntire(t) ?: return null
        return RrcNotice.HistoryStart(m.groupValues[1].toIntOrNull() ?: 0)
    }

    /** The two mention shapes from §8. */
    private fun mentionOf(t: String): RrcNotice? {
        HELD_MENTIONS.matchEntire(t)?.let {
            return RrcNotice.HeldMentions(it.groupValues[1].toIntOrNull() ?: 0)
        }
        if (!t.startsWith(MENTIONED_IN)) return null
        val rest = t.removePrefix(MENTIONED_IN)
        val room = noticeRoom(rest.substringBefore(" by ", missingDelimiterValue = ""))
        if (room.isEmpty()) return null
        return RrcNotice.Mentioned(room, t)
    }

    /**
     * Parse a `/list` reply. The hub formats it as a header line
     * `Registered public rooms:` followed by one indented line per
     * room — `  <name>` or `  <name> - <topic>` — or the single line
     * `No public rooms registered` when there are none.
     */
    private fun roomListOf(t: String): RrcNotice.RoomList? {
        if (t == "No public rooms registered") return RrcNotice.RoomList(emptyList())
        if (!t.startsWith("Registered public rooms:")) return null
        val rooms = t.lineSequence()
            .drop(1) // header line
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val dash = line.indexOf(" - ")
                if (dash >= 0) {
                    RrcRoomListing(line.take(dash).trim(), line.substring(dash + 3).trim())
                } else {
                    RrcRoomListing(line, null)
                }
            }
            .toList()
        return RrcNotice.RoomList(rooms)
    }

    private fun topicOf(t: String): RrcNotice.Topic? {
        if (!t.startsWith("topic for ") || !t.contains(IS_NOW)) return null
        val room = noticeRoom(t.removePrefix("topic for ").substringBefore(IS_NOW))
        if (room.isEmpty()) return null
        val value = t.substringAfter(IS_NOW)
        return RrcNotice.Topic(room, if (value == "(cleared)") null else value)
    }

    private fun modeOf(t: String): RrcNotice.Mode? {
        if (!t.startsWith("mode for ") || !t.contains(IS_NOW)) return null
        val room = noticeRoom(t.removePrefix("mode for ").substringBefore(IS_NOW))
        if (room.isEmpty()) return null
        val value = t.substringAfter(IS_NOW)
        return RrcNotice.Mode(room, if (value == "(none)") "" else value)
    }

    private fun roomInfoOf(t: String): RrcNotice.RoomInfo? {
        if (!t.startsWith("room ")) return null
        val room = noticeRoom(
            t.removePrefix("room ").substringBefore(": ", missingDelimiterValue = ""),
        )
        if (room.isEmpty()) return null
        val rest = t.substringAfter(": ", missingDelimiterValue = "")
        val registration = rest.substringBefore(";", missingDelimiterValue = "").trim()
        if (registration != "registered" && registration != "unregistered") return null
        if (!rest.contains("mode=") || !rest.contains("topic=")) return null
        val modes = rest.substringAfter("mode=").substringBefore(";").trim()
        val topic = rest.substringAfter("topic=").trim()
        return RrcNotice.RoomInfo(
            room = room,
            registered = registration == "registered",
            modes = if (modes == "(none)") "" else modes,
            topic = if (topic == "(none)" || topic == "(cleared)") null else topic,
        )
    }
}
