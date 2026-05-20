package io.github.thatsfguy.reticulum.announce

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.transport.hexToBytes

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
    // RRC hub — SHA-256("rrc.hub")[:10]. Lets the Nodes list label a
    // Reticulum Relay Chat hub instead of showing it as unrecognized,
    // and is the hook the Rooms tab uses to offer "add as RRC hub".
    "ac9fd3a81e4036f86e1d" to KnownDestination("rrc.hub",                         "RRC hub"),
)

/** Look up a name_hash. Accepts hex string or raw ByteArray. */
fun lookupDestination(nameHash: ByteArray): KnownDestination? {
    val hex = nameHash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return KNOWN_DESTINATIONS[hex.take(20)]
}

/** Convenience accessor used by the engine. */
object KnownDestinations {
    fun byNameHashHex(hex: String): KnownDestination? = KNOWN_DESTINATIONS[hex.lowercase().take(20)]
    fun byNameHash(bytes: ByteArray): KnownDestination? = lookupDestination(bytes)
}

/**
 * Reverse lookup: given a destination hash and the owner's 64-byte
 * public key, identify which well-known service this destination is for.
 *
 * A Reticulum destination_hash is `SHA-256(name_hash || identity_hash)[:16]`,
 * where `identity_hash = SHA-256(public_key)[:16]` and `name_hash`
 * varies per service (`lxmf.delivery` vs `rrc.hub` vs `nomadnetwork.node`
 * etc.). The destination hash alone doesn't reveal the service type, but
 * given the public key from a QR card we can recompute every candidate
 * `SHA-256(known_name_hash || identity_hash)[:16]` and check which one
 * matches the destination hash.
 *
 * This lets the QR-scan path label an imported destination correctly at
 * scan time — `rrc.hub`, `nomadnetwork.node`, etc. — instead of defaulting
 * to `lxmf.delivery` and waiting for the destination's own announce to
 * arrive a minute later to fix the classification. Cheap (KNOWN_DESTINATIONS
 * has ~9 entries, SHA-256 is microseconds on every platform).
 *
 * Returns the matched [KnownDestination], or null if nothing matches (in
 * which case the caller falls back to its default — typically
 * `lxmf.delivery` for contact-style QR cards).
 */
suspend fun inferServiceType(
    destHash: ByteArray,
    publicKey: ByteArray,
    crypto: CryptoProvider,
): KnownDestination? {
    require(destHash.size == 16) { "destHash must be 16 bytes" }
    require(publicKey.size == 64) { "publicKey must be 64 bytes" }
    val identityHash = crypto.truncatedHash(publicKey, 16)
    for ((nameHashHex, known) in KNOWN_DESTINATIONS) {
        val nameHash = nameHashHex.hexToBytes()
        val computed = crypto.truncatedHash(nameHash + identityHash, 16)
        if (computed.contentEquals(destHash)) return known
    }
    return null
}
