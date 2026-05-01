package io.github.thatsfguy.reticulum.engine

/**
 * Wire format for sharing a Reticulum LXMF identity via QR code (or any
 * cut-and-paste channel). Encoded as a small flat JSON object:
 *
 *   {"destHash":"...32hex...",
 *    "publicKey":"...128hex...",
 *    "ratchetPub":"...64hex... or omitted",
 *    "displayName":"..."}
 *
 * Reading: any unknown keys are ignored, missing required keys throw.
 *
 * We hand-roll the encoder/decoder so the shared module stays free of a
 * JSON library dependency. The format is small enough that a lenient
 * but predictable parser is cheaper than dragging kotlinx.serialization
 * through commonMain. If the format ever needs to grow, swap to
 * kotlinx-serialization-json — the call sites only see [encode]/[decode].
 */
object IdentityCard {

    data class Payload(
        val destHash: String,        // 32 hex chars
        val publicKey: String,       // 128 hex chars
        val ratchetPub: String?,     // 64 hex chars or null
        val displayName: String,
    )

    fun encode(payload: Payload): String = buildString {
        append('{')
        appendKey("destHash"); append(jsonString(payload.destHash));    append(',')
        appendKey("publicKey"); append(jsonString(payload.publicKey));  append(',')
        if (payload.ratchetPub != null) {
            appendKey("ratchetPub"); append(jsonString(payload.ratchetPub)); append(',')
        }
        appendKey("displayName"); append(jsonString(payload.displayName))
        append('}')
    }

    fun decode(text: String): Payload {
        val map = parseFlatObject(text)
        val destHash    = map["destHash"]    ?: error("destHash missing")
        val publicKey   = map["publicKey"]   ?: error("publicKey missing")
        val displayName = map["displayName"] ?: ""
        val ratchetPub  = map["ratchetPub"]
        require(destHash.length == 32 && destHash.all { it.isHexDigit() }) {
            "destHash must be 32 hex chars (got ${destHash.length})"
        }
        require(publicKey.length == 128 && publicKey.all { it.isHexDigit() }) {
            "publicKey must be 128 hex chars (got ${publicKey.length})"
        }
        if (ratchetPub != null) require(ratchetPub.length == 64 && ratchetPub.all { it.isHexDigit() }) {
            "ratchetPub must be 64 hex chars (got ${ratchetPub.length})"
        }
        return Payload(destHash.lowercase(), publicKey.lowercase(), ratchetPub?.lowercase(), displayName)
    }

    private fun StringBuilder.appendKey(name: String) {
        append('"'); append(name); append("\":")
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"'  -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    /** Parses a flat top-level JSON object whose values are all strings. Throws on anything else. */
    private fun parseFlatObject(text: String): Map<String, String> {
        var i = 0
        fun skipWs() { while (i < text.length && text[i].isWhitespace()) i++ }
        fun expect(c: Char) { skipWs(); if (i >= text.length || text[i] != c) error("Expected '$c' at $i"); i++ }
        fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                if (c == '"') { i++; return sb.toString() }
                if (c == '\\' && i + 1 < text.length) {
                    when (text[i + 1]) {
                        '"'  -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n'  -> sb.append('\n')
                        'r'  -> sb.append('\r')
                        't'  -> sb.append('\t')
                        else -> sb.append(text[i + 1])
                    }
                    i += 2; continue
                }
                sb.append(c); i++
            }
            error("Unterminated string")
        }

        val out = LinkedHashMap<String, String>()
        skipWs(); expect('{')
        skipWs()
        if (i < text.length && text[i] == '}') { i++; return out }
        while (true) {
            skipWs()
            val key = readString()
            skipWs(); expect(':')
            skipWs(); val value = readString()
            out[key] = value
            skipWs()
            if (i < text.length && text[i] == ',') { i++; continue }
            if (i < text.length && text[i] == '}') { i++; break }
            error("Expected ',' or '}' at $i")
        }
        return out
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
