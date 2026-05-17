package io.github.thatsfguy.reticulum.rrc

/**
 * Structured form of a hub NOTICE.
 *
 * The RRC hub broadcasts room-state changes as plain-text NOTICEs in
 * fixed formats (`reticulum-relay-chat/docs/client-parity.md` §3, §4).
 * [RrcNotices.classify] recognises the three structured shapes so the
 * client can surface a room's topic / modes as proper UI state instead
 * of only showing the raw NOTICE banner text.
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

    /** An informational NOTICE carrying no structured room state. */
    object Plain : RrcNotice
}

/** Classifier for hub NOTICE text — see [RrcNotice]. */
object RrcNotices {

    private const val IS_NOW = " is now: "

    fun classify(text: String): RrcNotice =
        topicOf(text) ?: modeOf(text) ?: roomInfoOf(text) ?: RrcNotice.Plain

    private fun topicOf(t: String): RrcNotice.Topic? {
        if (!t.startsWith("topic for ") || !t.contains(IS_NOW)) return null
        val room = t.removePrefix("topic for ").substringBefore(IS_NOW)
        if (room.isEmpty()) return null
        val value = t.substringAfter(IS_NOW)
        return RrcNotice.Topic(room, if (value == "(cleared)") null else value)
    }

    private fun modeOf(t: String): RrcNotice.Mode? {
        if (!t.startsWith("mode for ") || !t.contains(IS_NOW)) return null
        val room = t.removePrefix("mode for ").substringBefore(IS_NOW)
        if (room.isEmpty()) return null
        val value = t.substringAfter(IS_NOW)
        return RrcNotice.Mode(room, if (value == "(none)") "" else value)
    }

    private fun roomInfoOf(t: String): RrcNotice.RoomInfo? {
        if (!t.startsWith("room ")) return null
        val room = t.removePrefix("room ").substringBefore(": ", missingDelimiterValue = "")
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
