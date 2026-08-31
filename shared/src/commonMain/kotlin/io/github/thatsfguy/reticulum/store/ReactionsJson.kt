package io.github.thatsfguy.reticulum.store

/**
 * Encode / decode the [StoredMessage.reactionsJson] column.
 *
 * Wire shape stored in the DB:
 *
 * ```
 * {"👍":["a4383b4658729ab8e204e89724e2b383","..."],"❤️":["..."]}
 * ```
 *
 * - **Outer object**: keys are unicode emoji strings, values are
 *   arrays of sender identity-hash hex strings (16 bytes →
 *   32 hex chars).
 * - **Aggregated locally** by [MessageRepository.applyReaction]: when
 *   an inbound LXMF reaction arrives (FIELD_REACTION 0x40, SPEC §5.9.8),
 *   the reactor identity is appended to the matching emoji's array if
 *   not already present.
 * - We use JSON rather than msgpack here because it's only ever used
 *   inside the local DB (never on the wire) and avoids pulling msgpack
 *   into the store layer.
 *
 * The encoder uses simple character-by-character escaping for
 * `"` and `\` and assumes the input is well-formed UTF-16 (Kotlin
 * `String`s always are). It does NOT escape forward slashes, control
 * characters below ` `, or perform Unicode normalisation — the
 * inputs are pure emoji + hex digits, neither of which contains any
 * of those, and the parser is tolerant of either form anyway.
 *
 * The decoder is a hand-rolled JSON state-machine because the
 * commonMain layer has no JSON dependency and pulling kotlinx-
 * serialization just for this column would be overkill. Limited to
 * the exact wire shape above; rejects anything else with `null`.
 */
object ReactionsJson {

    fun encode(reactions: Map<String, List<String>>): String {
        if (reactions.isEmpty()) return "{}"
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((emoji, senders) in reactions) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(jsonEscape(emoji)).append('"').append(':').append('[')
            var firstSender = true
            for (sender in senders) {
                if (!firstSender) sb.append(',')
                firstSender = false
                sb.append('"').append(jsonEscape(sender)).append('"')
            }
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    }

    fun decode(json: String?): Map<String, List<String>> {
        if (json.isNullOrBlank() || json == "{}") return emptyMap()
        return runCatching { parseObject(json) }.getOrDefault(emptyMap())
    }

    /** Idempotent append. Returns `true` if [senderHex] was newly
     *  added, `false` if it was already present (so the caller can
     *  skip a redundant DB write). */
    fun applyReaction(
        currentJson: String?,
        emoji: String,
        senderHex: String,
    ): Pair<String, Boolean> {
        // Audit 2026-07-28 L4: `emoji` is attacker-controlled (LXMF
        // FIELD_REACTION 0x40). Reject implausible values before using it as
        // a JSON object key — an over-long string bloats the reactionsJson
        // column, and a control char produces JSON that fails to re-parse
        // (decode → emptyMap), silently wiping every reaction on the message.
        // A genuine reaction is one short grapheme.
        if (!isPlausibleReactionEmoji(emoji)) return Pair(currentJson ?: "{}", false)
        val current = decode(currentJson).toMutableMap()
        val list = current[emoji] ?: emptyList()
        if (senderHex in list) {
            // Already present — return the same JSON so the caller
            // can detect the no-op via the (json, false) tuple.
            return Pair(currentJson ?: "{}", false)
        }
        // SECURITY (audit 2026-08-31 F2): per-emoji validation bounds
        // one reaction; nothing bounded how MANY. Both axes are remote-
        // controlled — on the RRC path K_SRC is whatever the hub says,
        // so a hostile hub can mint unlimited distinct reactors as well
        // as unlimited distinct emoji, and grow this single column
        // without limit. Refuse past the caps instead: a full slot set
        // is a no-op, exactly like a duplicate.
        if (emoji !in current && current.size >= MAX_DISTINCT_REACTIONS) {
            return Pair(currentJson ?: "{}", false)
        }
        if (list.size >= MAX_REACTORS_PER_EMOJI) {
            return Pair(currentJson ?: "{}", false)
        }
        current[emoji] = list + senderHex
        return Pair(encode(current), true)
    }

    /**
     * Ceiling on distinct emoji aggregated onto one message, and on
     * reactors per emoji. Together with [MAX_REACTION_EMOJI_CHARS] they
     * bound the column: 16 × 64 × (64 + 34) ≈ 100 KB worst case, and a
     * realistic row is two orders of magnitude under that. Both are far
     * above any genuine conversation — a room with 64 people all
     * reacting with the same emoji to the same message is already
     * beyond what RRC rooms hold.
     */
    private const val MAX_DISTINCT_REACTIONS = 16
    private const val MAX_REACTORS_PER_EMOJI = 64

    /** Reaction emojis are one grapheme; ZWJ/flag sequences push a few
     *  codepoints, so 64 chars is generous. Audit L4. */
    private const val MAX_REACTION_EMOJI_CHARS = 64

    /**
     * Idempotent removal — the mirror of [applyReaction]. Returns
     * `true` if [senderHex] was actually holding [emoji] (so the caller
     * can skip a redundant DB write), `false` if it was not.
     *
     * Apply and retract are separate idempotent operations rather than
     * one toggle on purpose: Reticulum is a lossy mesh, a message can
     * arrive twice, and a duplicated toggle would flip twice and land
     * in the wrong state (`rrc-extensions.md` §2).
     */
    fun removeReaction(
        currentJson: String?,
        emoji: String,
        senderHex: String,
    ): Pair<String, Boolean> {
        val current = decode(currentJson).toMutableMap()
        val list = current[emoji] ?: return Pair(currentJson ?: "{}", false)
        if (senderHex !in list) return Pair(currentJson ?: "{}", false)
        val remaining = list - senderHex
        // Drop the emoji entirely once nobody holds it, so an empty
        // list can't linger and render as a zero-count chip.
        if (remaining.isEmpty()) current.remove(emoji) else current[emoji] = remaining
        return Pair(encode(current), true)
    }

    private fun isPlausibleReactionEmoji(s: String): Boolean {
        if (s.isEmpty() || s.length > MAX_REACTION_EMOJI_CHARS) return false
        // No C0 controls (newlines/tabs/etc.) or DEL — they'd break the
        // hand-rolled JSON and carry no display value.
        return s.none { it.code < 0x20 || it.code == 0x7F }
    }

    private fun jsonEscape(s: String): String {
        var needsEscape = false
        for (c in s) if (c == '"' || c == '\\' || c.code < 0x20) { needsEscape = true; break }
        if (!needsEscape) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '"' || c == '\\' -> sb.append('\\').append(c)
                // Escape C0 control chars as \uXXXX so the output stays valid
                // JSON even if an unvalidated string slips through (L4).
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Bare-bones JSON object parser. Throws on malformed input;
     *  caller wraps in runCatching to fall back to `emptyMap()`. */
    private fun parseObject(s: String): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        var i = skipWs(s, 0)
        require(s[i] == '{') { "expected '{' at $i" }
        i++
        i = skipWs(s, i)
        if (i < s.length && s[i] == '}') return result
        while (i < s.length) {
            i = skipWs(s, i)
            val (key, keyEnd) = parseString(s, i)
            i = skipWs(s, keyEnd)
            require(s[i] == ':') { "expected ':' at $i" }
            i = skipWs(s, i + 1)
            val (list, arrEnd) = parseArray(s, i)
            result[key] = list
            i = skipWs(s, arrEnd)
            if (i < s.length && s[i] == ',') {
                i = skipWs(s, i + 1)
                continue
            }
            require(s[i] == '}') { "expected '}' at $i" }
            return result
        }
        return result
    }

    private fun parseArray(s: String, start: Int): Pair<List<String>, Int> {
        require(s[start] == '[') { "expected '[' at $start" }
        var i = skipWs(s, start + 1)
        if (i < s.length && s[i] == ']') return Pair(emptyList(), i + 1)
        val list = mutableListOf<String>()
        while (i < s.length) {
            i = skipWs(s, i)
            val (value, valueEnd) = parseString(s, i)
            list.add(value)
            i = skipWs(s, valueEnd)
            if (i < s.length && s[i] == ',') {
                i = skipWs(s, i + 1)
                continue
            }
            require(s[i] == ']') { "expected ']' at $i" }
            return Pair(list, i + 1)
        }
        return Pair(list, i)
    }

    private fun parseString(s: String, start: Int): Pair<String, Int> {
        require(s[start] == '"') { "expected '\"' at $start" }
        var i = start + 1
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                // `\uXXXX` is the only escape [jsonEscape] emits besides
                // `\"` / `\\`, and decoding it as the literal character
                // `u` (which both arms of the old ternary did) meant the
                // encoder's own output did not survive a round trip.
                // Unreachable while the C0 filter holds, but a dead
                // escape hatch is worse than no escape hatch: the guard
                // in front of it is the only thing making it dead.
                // Audit reference: 2026-08-31 F6.
                val esc = s[i + 1]
                if (esc == 'u' && i + 5 < s.length) {
                    val hex = s.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                        continue
                    }
                }
                sb.append(
                    when (esc) {
                        'n'  -> '\n'
                        'r'  -> '\r'
                        't'  -> '\t'
                        'b'  -> '\b'
                        'f'  -> '\u000c'
                        else -> esc   // `"`, `\\`, `/`, and anything else verbatim
                    },
                )
                i += 2
                continue
            }
            if (c == '"') return Pair(sb.toString(), i + 1)
            sb.append(c)
            i++
        }
        error("unterminated string from $start")
    }

    private fun skipWs(s: String, start: Int): Int {
        var i = start
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        return i
    }
}
