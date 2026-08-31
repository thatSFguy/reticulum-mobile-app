package io.github.thatsfguy.reticulum.rrc

/**
 * Does a room message name *us*?
 *
 * The hub does its own mention resolution and will tell a client that
 * was elsewhere or offline (`client-parity.md` §8). This is the other
 * half: for a message that arrives by ordinary fan-out — we are in the
 * room, so the hub deliberately says nothing extra — the client decides
 * whether to highlight the line and raise a notification.
 *
 * The two forms are the hub's (§8), and are matched the same way it
 * matches them:
 *
 *  - `@nick` — advisory and not unique, so a nick match is a *hint*,
 *    which is all a highlight needs to be.
 *  - `@<hashprefix>` — `@` plus 6 or more hex characters of an identity
 *    hash. Exact, and the way to be certain.
 */
object RrcMentions {

    /** Shortest hash prefix the hub accepts after `@` (§8). */
    private const val MIN_HASH_PREFIX = 6

    /**
     * The mention being typed at the end of [draft], without its `@`,
     * or null when the caret is not in one.
     *
     * Only the trailing token counts: completing an `@` from the middle
     * of a finished sentence would rewrite text the user has moved on
     * from. A token must also START a word — `user@example` is an
     * address, not a mention.
     */
    fun tokenAt(draft: String): String? {
        val at = draft.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !draft[at - 1].isWhitespace()) return null
        val token = draft.substring(at + 1)
        if (token.any { it.isWhitespace() }) return null
        return token
    }

    /** Replace the trailing `@token` in [draft] with `@[name] `. */
    fun replaceToken(draft: String, name: String): String {
        val at = draft.lastIndexOf('@')
        if (at < 0) return draft
        return draft.take(at) + "@" + name + " "
    }

    /**
     * True when [text] names the identity [identityHashHex] (full hex,
     * lower-case) or the nickname [nick].
     *
     * A token is read as the run of mention-legal characters after an
     * `@`; trailing punctuation ("@alice, are you there?") is therefore
     * not part of the nick.
     */
    fun namesUs(text: String, nick: String?, identityHashHex: String): Boolean {
        if (text.isEmpty()) return false
        val wanted = nick?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val hash = identityHashHex.lowercase()
        var i = text.indexOf('@')
        while (i >= 0) {
            val token = tokenAt(text, i + 1)
            if (token.isNotEmpty()) {
                val lower = token.lowercase()
                if (wanted != null && lower == wanted) return true
                if (lower.length >= MIN_HASH_PREFIX && hash.isNotEmpty() &&
                    lower.all { it.isHexDigit() } && hash.startsWith(lower)
                ) {
                    return true
                }
            }
            i = text.indexOf('@', i + 1)
        }
        return false
    }

    /** The mention token starting at [from] — letters, digits, and the
     *  punctuation nicknames conventionally use. */
    private fun tokenAt(text: String, from: Int): String {
        var end = from
        while (end < text.length && text[end].isMentionChar()) end++
        return text.substring(from, end)
    }

    private fun Char.isMentionChar(): Boolean =
        isLetterOrDigit() || this == '_' || this == '-' || this == '[' || this == ']' ||
            this == '\\' || this == '^' || this == '{' || this == '}' || this == '|'

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
