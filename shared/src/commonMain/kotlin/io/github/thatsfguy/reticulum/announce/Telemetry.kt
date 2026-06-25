package io.github.thatsfguy.reticulum.announce

import io.github.thatsfguy.reticulum.codec.MessagePack

/**
 * Parse the semicolon-delimited key=value telemetry blob that
 * RLR-style nodes ship in their announce app_data.
 *
 * Example: `bat=3867;lat=43.16;lon=-83.5;alt=275;temp=22.4`
 *
 * Returns an empty map on parse failure or empty input. Trims whitespace
 * around each key and value. Duplicate keys: last wins.
 */
fun parseTelemetry(text: String): Map<String, String> {
    if (text.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (chunk in text.split(';')) {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) continue
        val eq = trimmed.indexOf('=')
        if (eq <= 0) continue
        val k = trimmed.substring(0, eq).trim()
        val v = trimmed.substring(eq + 1).trim()
        if (k.isNotEmpty()) out[k] = v
    }
    return out
}

/**
 * Top-level telemetry parser. Tries both common formats:
 *
 *   1. Legacy text `bat=3867;lat=43.16;…` (RLR / repeater firmware).
 *   2. msgpack-encoded `[hop_count, {int_key: value}]` or bare
 *      `{int_key: value}` map. Used by Reticulum's internal
 *      `rnstransport.broadcasts` aspect (BackboneInterface,
 *      RNodeInterface, TCPInterface gossip), and by some custom apps.
 *      Decoded keys are emitted as their named field where known
 *      (`interfaceType`, `address`, `bandwidth`, `mtu`, etc.) and as
 *      `field_N` otherwise. Decoded values stringify based on type
 *      (numbers → toString, ByteArray → hex truncated to 32 chars).
 *
 * Returns empty map on parse failure, empty input, or unrecognized
 * structure. The msgpack path runs only if the text path produced
 * nothing — avoids double-parsing legitimate text telemetry that
 * happens to start with a printable char that's also a valid msgpack
 * type tag.
 */
fun parseTelemetryBytes(bytes: ByteArray): Map<String, String> {
    if (bytes.isEmpty()) return emptyMap()
    // 1. Try as text first — but ONLY when the bytes are genuinely a
    //    clean UTF-8 string. decodeToString() defaults to *lossy*
    //    decoding: malformed sequences silently become U+FFFD ('�').
    //    A binary msgpack broadcast (e.g. one carrying a `bin` peerInfo
    //    blob) that happens to contain a '=' byte would otherwise be
    //    accepted as "text" and surface as a row full of replacement
    //    characters — the "boxes with question marks" of issue #38.
    //    throwOnInvalidSequence=true rejects non-UTF-8 outright; the
    //    [looksLikeText] guard additionally rejects valid-UTF-8-by-chance
    //    binary that carries control / replacement characters.
    val asText = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    if (asText != null && looksLikeText(asText)) {
        val text = parseTelemetry(asText)
        if (text.isNotEmpty()) return text
    }
    // 2. Fall back to msgpack — extract a map of int → value, name
    //    well-known codes, stringify everything else.
    return runCatching { parseMsgpackTelemetry(bytes) }.getOrDefault(emptyMap())
}

/**
 * True when [s] reads as printable telemetry text rather than a binary
 * blob that merely decoded as valid UTF-8. Real telemetry is printable
 * `key=value;…`; any C0/C1 control character (other than ordinary
 * whitespace), a DEL, or a Unicode replacement char means we're looking
 * at binary, not text. See issue #38.
 */
private fun looksLikeText(s: String): Boolean {
    if (s.isEmpty()) return false
    return s.none { c ->
        c == '�' ||
            c.code == 0x7F ||
            (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r')
    }
}

/**
 * Decode a msgpack blob to a `{name: stringified_value}` map.
 *
 * Accepts either a top-level map OR a `[any, map]` 2-tuple (RNS
 * transport-broadcast wraps the map in a `[hop_count, map]` envelope).
 * Numeric keys are mapped to friendly names via [TELEMETRY_CODE_NAMES]
 * where the code is recognized.
 */
private fun parseMsgpackTelemetry(bytes: ByteArray): Map<String, String> {
    val decoded = MessagePack.decode(bytes) ?: return emptyMap()
    val payloadMap: Map<*, *> = when (decoded) {
        is Map<*, *> -> decoded
        is List<*>   -> {
            // Find the first map element (the wrapper convention is
            // `[hop_count, {map}]`, but some senders put metadata first).
            decoded.firstOrNull { it is Map<*, *> } as? Map<*, *>
                ?: return emptyMap()
        }
        else -> return emptyMap()
    }
    val out = LinkedHashMap<String, String>()
    for ((rawKey, rawVal) in payloadMap) {
        val keyName = when (rawKey) {
            is Number -> TELEMETRY_CODE_NAMES[rawKey.toInt()] ?: "field_${rawKey.toInt()}"
            is String -> rawKey
            else -> continue
        }
        out[keyName] = stringifyValue(rawVal)
    }
    return out
}

/**
 * Common int-keyed codes seen in `rnstransport.broadcasts` announces.
 * Reverse-engineered from samples on RNS.MichMesh.net 2026-05-04 — not
 * authoritative; bump as more codes are observed. Unknown codes pass
 * through as `field_N`.
 */
private val TELEMETRY_CODE_NAMES: Map<Int, String> = mapOf(
    0 to "interfaceType",   // "BackboneInterface" / "RNodeInterface" / "TCPInterface"
    1 to "interfaceId",     // 16-byte ID (hex)
    2 to "address",         // IP / hostname string
    3 to "bandwidthBps",    // double
    4 to "txRate",          // double (observed but format unconfirmed)
    5 to "mtu",             // double, bytes
    6 to "peerInfo",        // signed peer descriptor blob
)

private fun stringifyValue(value: Any?): String = when (value) {
    null            -> ""
    is String       -> value
    is Boolean      -> value.toString()
    is Number       -> {
        // Trim "1234.0" → "1234" for whole-number doubles to keep the
        // displayed telemetry readable.
        val s = value.toString()
        if (s.endsWith(".0")) s.dropLast(2) else s
    }
    is ByteArray    -> {
        val hex = value.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        if (hex.length > 32) hex.take(32) + "…" else hex
    }
    is List<*>      -> value.joinToString(", ", "[", "]") { stringifyValue(it) }
    is Map<*, *>    -> value.entries.joinToString(", ", "{", "}") { (k, v) -> "$k=${stringifyValue(v)}" }
    else            -> value.toString()
}

/**
 * Extract a (lat, lon) pair from a parsed telemetry map. Returns null if
 * either value is missing or unparseable.
 */
fun extractCoordinates(telemetry: Map<String, String>): Pair<Double, Double>? {
    val lat = telemetry["lat"]?.toDoubleOrNull() ?: return null
    val lon = telemetry["lon"]?.toDoubleOrNull() ?: return null
    return lat to lon
}
