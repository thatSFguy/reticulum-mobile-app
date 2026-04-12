package io.github.thatsfguy.reticulum.announce

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.protocol.*

/**
 * Parsed announce payload. Port of reference/js-reference/announce.js.
 *
 * Layout: public_key(64) + name_hash(10) + random_hash(10) +
 *         [ratchet(32) if context_flag] + signature(64) + app_data
 */
data class ParsedAnnounce(
    val publicKey: ByteArray,   // 64 bytes
    val nameHash: ByteArray,    // 10 bytes
    val randomHash: ByteArray,  // 10 bytes
    val ratchet: ByteArray?,    // 32 bytes or null
    val signature: ByteArray,   // 64 bytes
    val appData: ByteArray,
    val identityHash: ByteArray, // 16 bytes, derived from publicKey
    val destHash: ByteArray,     // 16 bytes, from the packet header
)

/**
 * Parse an announce's payload (after Reticulum header).
 *
 * TODO: Port from reference/js-reference/announce.js parseAnnounce().
 *       Needs CryptoProvider for truncatedHash (identity hash computation).
 */
suspend fun parseAnnounce(
    payload: ByteArray,
    contextFlag: Int,
    destHashFromHeader: ByteArray,
    crypto: CryptoProvider,
): ParsedAnnounce? {
    TODO("Port from reference/js-reference/announce.js parseAnnounce()")
}

/**
 * Validate an announce's Ed25519 signature.
 *
 * signed_data = dest_hash + public_key + name_hash + random_hash + [ratchet] + app_data
 * Signing key = publicKey[32:64] (the Ed25519 half)
 *
 * TODO: Port from reference/js-reference/announce.js validateAnnounce().
 */
fun validateAnnounce(announce: ParsedAnnounce, destHashFromHeader: ByteArray, crypto: CryptoProvider): Boolean {
    TODO("Port from reference/js-reference/announce.js validateAnnounce()")
}

/**
 * Build an announce for our identity.
 *
 * TODO: Port from reference/js-reference/announce.js buildAnnounce().
 */
suspend fun buildAnnounce(
    identity: Identity,
    crypto: CryptoProvider,
    appName: String = "lxmf.delivery",
    appData: ByteArray = ByteArray(0),
    ratchetPub: ByteArray? = null,
): Triple<ByteArray, ByteArray, Boolean> { // (destHash, payload, hasRatchet)
    TODO("Port from reference/js-reference/announce.js buildAnnounce()")
}

/**
 * Extract display name from announce app_data. LXMF/Sideband
 * announces pack app_data as msgpack [display_name_bytes, stamp_cost].
 * Try msgpack decode first, fall back to UTF-8.
 *
 * TODO: Port from reference/js-reference/announce.js extractDisplayName().
 *       Needs a msgpack decoder library.
 */
fun extractDisplayName(appData: ByteArray): String? {
    TODO("Port from reference/js-reference/announce.js extractDisplayName()")
}
