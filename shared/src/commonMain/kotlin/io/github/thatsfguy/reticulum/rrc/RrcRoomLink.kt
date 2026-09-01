package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.engine.normalizeRrcRoom

/**
 * Text form for "this room, on this hub" — `rrc-room-links.md` **v2**.
 *
 * ```
 * rrc://<32hex>[:<dest_name>]/<room>
 * ```
 *
 * This is NomadNet's grammar, not ours. NomadNet 1.2.8 (released
 * 2026-07-24) ships a full RRC client and reads this form in
 * `Browser.handle_rrc_link` (`Browser.py:426-461`), reached either from
 * the `rrc://` scheme (`:277-280`) or from an `rrc@…` micron link whose
 * shorthand `expand_shorthands` maps to `rrc.hub.session` (`:206-214`,
 * `:312-314`).
 *
 * ### Why this replaced v1
 *
 * v1 emitted `rrc@<32hex>:/room/<percent-encoded>` on the reasoning that
 * an `rrc://` scheme would be an invention in an ecosystem that already
 * had a convention. The reasoning was right and the fact was wrong: the
 * ecosystem's only RRC client had shipped `rrc://` five weeks earlier
 * and had already claimed the `rrc@` shorthand for a *different* payload
 * grammar. Run a v1 link through NomadNet's parser and the hub resolves
 * correctly while the room comes out as the literal name `room/<x>` —
 * and because a hub accepts any non-empty UTF-8 room name, that is not
 * an error anywhere. It creates and joins a junk room.
 *
 * So: one grammar, theirs.
 *
 * ### Encoding
 *
 * The room segment is **literal** — NomadNet does no percent-decoding,
 * so emitting `%20` would join a room named `%20`, which is the same
 * junk-room failure in a different costume. [build] therefore refuses to
 * emit a link for a room name that cannot survive being pasted as a
 * whitespace-delimited token (see [isLinkSafeRoom]); no link is better
 * than a link that joins the wrong room.
 *
 * Percent-*decoding* survives on the read path only, for v1 links
 * already shared — see [parsePayload].
 *
 * ### `rrc.hub.session` is not an aspect
 *
 * `expand_shorthands` maps `rrc` to the string `rrc.hub.session`, but
 * that is only NomadNet's internal dispatch label in `handle_link`. The
 * destination aspect an RRC hub actually registers is `rrc.hub`
 * (`nomadnet/RRC.py:97` → `DEFAULT_DEST_NAME = "rrc.hub"`), which is
 * what the optional `:<dest_name>` slot overrides and what this client
 * dials.
 */
object RrcRoomLink {

    /** The aspect an RRC hub registers when its config names no other
     *  (`nomadnet/RRC.py:97`; `rrc-hub.toml` `dest_name`). */
    const val DEFAULT_DEST_NAME = "rrc.hub"

    /** Canonical scheme prefix. Emitted, and matched case-sensitively on
     *  read because that is what `Browser.py:278` matches — a link this
     *  client accepts but NomadNet rejects is the divergence v2 exists
     *  to end. */
    const val URL_SCHEME = "rrc://"

    /** `rrc-room-links.md` §2.1 — exactly 32 hex characters. */
    private const val HASH_LEN = 32

    /** v1's path prefix, recognised on read only. */
    private const val LEGACY_ROOM_PREFIX = "room/"

    /** A parsed link. [room] is `""` when the link names a hub and no
     *  particular room — `rrc://<32hex>` — which is a legitimate form
     *  (upstream shows the hub, joins nothing). */
    data class Parsed(val hubDestHashHex: String, val room: String)

    /**
     * Build the canonical link for [room] on the hub at [hubDestHash],
     * or **null** when no correct link can be written.
     *
     * Null happens for three reasons, and every one of them means the
     * caller must hide the share affordance rather than emit something:
     *
     *  - [hubDestHash] is not a well-formed 32-hex destination hash.
     *    §2.1: *"A writer that does not know its own destination hash
     *    MUST emit no link rather than a partial one. A malformed link
     *    is pasted onward as though it worked."*
     *  - the normalised room name is empty.
     *  - the room name is not [isLinkSafeRoom].
     */
    fun build(hubDestHash: String, room: String): String? {
        val hash = hubDestHash.trim().lowercase()
        if (!isValidHash(hash)) return null
        val normalized = normalizeRrcRoom(room)
        if (normalized.isEmpty() || !isLinkSafeRoom(normalized)) return null
        return "$URL_SCHEME$hash/$normalized"
    }

    /** Build a link naming a hub with no room — `rrc://<32hex>`. Null on
     *  a malformed hash, for the same reason [build] is. */
    fun buildHub(hubDestHash: String): String? {
        val hash = hubDestHash.trim().lowercase()
        if (!isValidHash(hash)) return null
        return "$URL_SCHEME$hash"
    }

    /** True when [s] is exactly 32 hex characters — no separators, no
     *  `0x` prefix, no short forms. `SPEC §11.6.3` is explicit that a
     *  forgiving reader creates aliases for one destination and risks
     *  cache poisoning, so this is deliberately unforgiving. */
    fun isValidHash(s: String): Boolean =
        s.length == HASH_LEN && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * Can this room name be written into a link that survives being
     * pasted into a message body?
     *
     * The room segment is literal, and a link in running text ends at
     * the first whitespace, so a name containing a space would be
     * truncated on paste into a link to a *different*, shorter room
     * name. Control characters are refused for the usual reasons.
     * Everything else — `:`, `@`, `/`, `%`, punctuation, non-Latin
     * scripts — round-trips, because the payload is split on its first
     * `/` and everything after it is the name.
     */
    fun isLinkSafeRoom(room: String): Boolean {
        if (room.isEmpty()) return false
        return room.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
    }

    /**
     * Parse the payload of an RRC link — everything after `rrc://` or
     * after the `rrc@` / `rrc.hub@` / `rrc.hub.session@` shorthand.
     * Returns null when this is not a link we can act on.
     *
     * Transcribes `Browser.handle_rrc_link` (`Browser.py:430-446`):
     *
     * ```python
     * rest = link_target.strip()
     * if rest.startswith("/"): rest = rest[1:]
     * hub_part, _, room = rest.partition("/")
     * hex_part, _, dest = hub_part.partition(":")
     * ```
     *
     * with two deliberate departures, both narrowing:
     *
     *  - **A non-default `dest_name` is rejected.** Upstream dials the
     *    named aspect; this client hardcodes `rrc.hub` everywhere, so
     *    honouring the slot would mean dialling the wrong destination
     *    while showing the user the right link. Rejecting is the honest
     *    answer until the engine can take an aspect.
     *  - **The hash must be exactly 32 hex characters.** Upstream only
     *    requires `bytes.fromhex` to yield 16 bytes, which also accepts
     *    an odd-spaced or upper-case variant; §2.1's aliasing argument
     *    applies to us regardless of what upstream tolerates.
     *
     * The v1 shim is the one piece of ours that stays. A v1 link parses
     * here as an *empty* `dest_name` (the trailing colon) plus a room of
     * `room/<segment>` — a shape the v2 grammar cannot otherwise
     * produce, since a v2 link either has no colon at all or a non-empty
     * `dest_name`. That makes the two unambiguous, so links already
     * shared keep working.
     */
    fun parsePayload(payload: String): Parsed? {
        var rest = payload.trim()
        if (rest.isEmpty()) return null
        // Upstream tolerates one leading slash (`rrc:///<hex>/room`).
        if (rest.startsWith("/")) rest = rest.substring(1)

        val slash = rest.indexOf('/')
        val hubPart = if (slash < 0) rest else rest.substring(0, slash)
        var roomPart = if (slash < 0) "" else rest.substring(slash + 1)

        val colon = hubPart.indexOf(':')
        val hexPart = if (colon < 0) hubPart else hubPart.substring(0, colon)
        val destName = if (colon < 0) "" else hubPart.substring(colon + 1).trim()

        if (!isValidHash(hexPart)) return null
        val hash = hexPart.lowercase()

        val isLegacyV1 = colon >= 0 && destName.isEmpty() && roomPart.startsWith(LEGACY_ROOM_PREFIX)
        if (isLegacyV1) {
            roomPart = decodeSegment(roomPart.substring(LEGACY_ROOM_PREFIX.length)) ?: return null
        } else if (destName.isNotEmpty() && !destName.equals(DEFAULT_DEST_NAME, ignoreCase = true)) {
            return null
        }

        return Parsed(hash, normalizeRrcRoom(roomPart))
    }

    /**
     * Percent-decode a v1 link's room segment, or null when the input is
     * malformed (a stray `%`, a short or non-hex escape).
     *
     * Read-path only — v2 emits literal room names. Invalid input is
     * rejected rather than passed through: a segment that does not
     * decode is not a room name we can join, and quietly treating `%zz`
     * as literal text would make two different links address one room.
     */
    fun decodeSegment(s: String): String? {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = hexVal(s[i + 1])
                val lo = hexVal(s[i + 2])
                if (hi < 0 || lo < 0) return null
                out.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                if (c.code < 0x20 || c.code == 0x7F) return null
                for (b in c.toString().encodeToByteArray()) out.add(b)
                i++
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
