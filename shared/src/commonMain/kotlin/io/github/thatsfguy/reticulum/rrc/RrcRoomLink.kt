package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.engine.normalizeRrcRoom

/**
 * Text form for "this room, on this hub" — `rrc-room-links.md` v1.
 *
 * RRC has no way to write down where a room is: room names are not
 * unique across hubs, and "come to #ops" carries no hub. This is the
 * format that fixes that, and it is deliberately **not a new format** —
 * it is NomadNet's existing link grammar (`SPEC §11.6.3`, parsed by
 * `Browser.py`'s `expand_shorthands`) with one more shorthand:
 *
 * ```
 * rrc@<32hex>:/room/<percent-encoded name>
 * ```
 *
 * where `rrc` expands to `rrc.hub`. A client that has never heard of it
 * renders the link as plain text that a person can still select and
 * copy, which is why the format is text and not a message type (§3).
 *
 * Parsing lives in [io.github.thatsfguy.reticulum.nomad.parseLinkTarget]
 * alongside the rest of the grammar; this file owns the *encoding*
 * rules, which are the part with teeth.
 */
object RrcRoomLink {

    /** `rrc-room-links.md` §2.1 — exactly 32 hex characters. */
    private const val HASH_LEN = 32

    /**
     * §2.2 unreserved set. Everything else is percent-encoded, which is
     * **stricter than a typical URL path encoder**: `:` and `@` are
     * structural in this grammar, and a link is a whitespace-delimited
     * token somebody pastes out of a message body, so anything that
     * could split or re-anchor the token has to be escaped.
     */
    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '_' || c == '.' || c == '~'

    /**
     * Build the canonical link for [room] on the hub at [hubDestHash],
     * or **null** when [hubDestHash] is not a well-formed 32-hex
     * destination hash.
     *
     * Returning null rather than a best-effort string is the spec's
     * §2.1 rule and it matters: "A writer that does not know its own
     * destination hash MUST emit no link rather than a partial one. A
     * malformed link is pasted onward as though it worked." Callers
     * must hide the share affordance when this returns null.
     *
     * The room name is normalised before encoding, so a link always
     * addresses a room `JOIN` can actually reach (§2.2).
     */
    fun build(hubDestHash: String, room: String): String? {
        val hash = hubDestHash.trim().lowercase()
        if (!isValidHash(hash)) return null
        val normalized = normalizeRrcRoom(room)
        if (normalized.isEmpty()) return null
        return "rrc@$hash:/room/${encodeSegment(normalized)}"
    }

    /** True when [s] is exactly 32 hex characters — no separators, no
     *  `0x` prefix, no short forms. §2.1 is explicit that a forgiving
     *  reader creates aliases for one destination and risks cache
     *  poisoning, so this is deliberately unforgiving. */
    fun isValidHash(s: String): Boolean =
        s.length == HASH_LEN && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Percent-encode over UTF-8 bytes, escaping everything outside the
     *  unreserved set (§2.2). */
    fun encodeSegment(s: String): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 0x80 && isUnreserved(c)) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
        }
        return sb.toString()
    }

    /**
     * Decode a percent-encoded segment back to text, or null when the
     * input is malformed (a stray `%`, a short or non-hex escape).
     *
     * Invalid input is rejected rather than passed through: a segment
     * that does not decode is not a room name we can join, and quietly
     * treating `%zz` as literal text would make two different links
     * address the same room.
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
                // A raw character outside the unreserved set means the
                // writer did not encode per §2.2. Accept it on read —
                // readers are lenient, writers are strict — but never
                // accept a control character.
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

    private val HEX = "0123456789ABCDEF"
}
