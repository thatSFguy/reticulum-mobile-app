package io.github.thatsfguy.reticulum.announce

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
 * Extract a (lat, lon) pair from a parsed telemetry map. Returns null if
 * either value is missing or unparseable.
 */
fun extractCoordinates(telemetry: Map<String, String>): Pair<Double, Double>? {
    val lat = telemetry["lat"]?.toDoubleOrNull() ?: return null
    val lon = telemetry["lon"]?.toDoubleOrNull() ?: return null
    return lat to lon
}
