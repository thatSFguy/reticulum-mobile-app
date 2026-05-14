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
 *   an inbound LXMF reaction arrives (field 16
 *   `{"reaction_to":...,"emoji":...,"sender":...}`), the sender is
 *   appended to the matching emoji's array if not already present.
 * - **Matches Columba** — see `MessageMapper.kt:103-122` in
 *   `torlando-tech/columba`. We use JSON rather than msgpack here
 *   because it's only ever used inside the local DB (never on the
 *   wire) and avoids pulling msgpack into the store layer.
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
        val current = decode(currentJson).toMutableMap()
        val list = current[emoji] ?: emptyList()
        if (senderHex in list) {
            // Already present — return the same JSON so the caller
            // can detect the no-op via the (json, false) tuple.
            return Pair(currentJson ?: "{}", false)
        }
        current[emoji] = list + senderHex
        return Pair(encode(current), true)
    }

    private fun jsonEscape(s: String): String {
        if (s.indexOf('"') < 0 && s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"', '\\' -> { sb.append('\\').append(c) }
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
                val esc = s[i + 1]
                sb.append(if (esc == '"' || esc == '\\') esc else esc)
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
