package io.github.thatsfguy.reticulum.announce

/**
 * Lookup table for well-known Reticulum destination name_hashes.
 * Pre-computed as SHA-256(name)[:10] → hex string.
 *
 * Port of reference/js-reference/known-destinations.js.
 */
data class KnownDestination(val name: String, val label: String)

val KNOWN_DESTINATIONS: Map<String, KnownDestination> = mapOf(
    "6ec60bc318e2c0f0d908" to KnownDestination("lxmf.delivery",                   "LXMF delivery"),
    "e03a09b77ac21b22258e" to KnownDestination("lxmf.propagation",                "LXMF propagation node"),
    "213e6311bcec54ab4fde" to KnownDestination("nomadnetwork.node",               "NomadNet node"),
    "0ad8bff9ff75737c058e" to KnownDestination("nomadnetwork.gossip",             "NomadNet gossip"),
    "28f44518c0b20af50215" to KnownDestination("nomadnetwork.gossip.conversation", "NomadNet gossip channel"),
    "9efb9c771eeb5ae90ea6" to KnownDestination("rnstransport.broadcasts",         "RNS transport broadcast"),
    "4848a053c16415bed6c8" to KnownDestination("rnstransport.remote.management",  "RNS remote management"),
    "3eea23374d2a3aedf2cc" to KnownDestination("rlr.telemetry",                   "RLR telemetry beacon"),
)

/** Look up a name_hash. Accepts hex string or raw ByteArray. */
fun lookupDestination(nameHash: ByteArray): KnownDestination? {
    val hex = nameHash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return KNOWN_DESTINATIONS[hex.take(20)]
}
