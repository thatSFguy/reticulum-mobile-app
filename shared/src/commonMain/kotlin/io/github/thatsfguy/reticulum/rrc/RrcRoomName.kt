package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.engine.normalizeRrcRoom

/**
 * House rule for room names **this client creates**.
 *
 * ## This is a local policy, not a protocol rule
 *
 * RRC room names are arbitrary UTF-8 and the hub accepts them:
 * `rrc-room-links.md` §2.2 says outright that "spaces, punctuation and
 * non-Latin scripts are all legal", which is exactly why a room link
 * percent-encodes its segment. So this MUST be applied only where a
 * name is being *typed to create a room*, and never on:
 *
 *  - **inbound names** — the hub fans out whatever exists, and a room
 *    called `off topic` created by another client has to render and
 *    receive normally;
 *  - **the room browser** — `/list` returns names as they are, and a
 *    room we refuse to display is a room the user cannot join;
 *  - **room links** — `rrc@<hash>:/room/off%20topic` addresses a real
 *    room somebody else made.
 *
 * Applying it in any of those places would turn a cosmetic preference
 * into an interop bug: rooms that exist, that other clients are sitting
 * in, silently unreachable from here.
 *
 * ## The rule
 *
 * After the ordinary normalisation (trim, strip a leading `#`, trim,
 * lower-case) a creatable name is letters, digits, `-` and `_`. Letters
 * are Unicode letters, not ASCII: there is no reason to refuse
 * `日本語` or `café` when the objection is to whitespace and
 * punctuation, and the room-link encoder handles them either way.
 */
object RrcRoomName {

    /**
     * The hub's own advertised default (`RrcLimits.maxRoomNameBytes`).
     * Measured in UTF-8 bytes, because that is what the limit counts.
     */
    const val MAX_BYTES = 64

    /**
     * Why [raw] cannot be used to create a room, or **null** when it is
     * fine. The string is written to be shown directly under the input.
     */
    fun problem(raw: String): String? {
        val name = normalizeRrcRoom(raw)
        if (name.isEmpty()) return "Enter a room name."
        if (name.encodeToByteArray().size > MAX_BYTES) {
            return "Too long — room names are limited to $MAX_BYTES bytes."
        }
        if (name.any { it.isWhitespace() }) {
            return "No spaces — use a hyphen or underscore instead."
        }
        val bad = name.firstOrNull { !isAllowed(it) }
        if (bad != null) {
            return "\"$bad\" isn't allowed — use letters, numbers, hyphens and underscores."
        }
        return null
    }

    /** Convenience for call sites that only need a yes/no. */
    fun isCreatable(raw: String): Boolean = problem(raw) == null

    private fun isAllowed(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_'
}
