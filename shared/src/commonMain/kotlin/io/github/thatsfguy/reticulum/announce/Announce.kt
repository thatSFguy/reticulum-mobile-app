package io.github.thatsfguy.reticulum.announce

import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.crypto.computeDestinationHash
import io.github.thatsfguy.reticulum.crypto.computeNameHash
import io.github.thatsfguy.reticulum.protocol.*

/**
 * Parsed announce payload. Port of reference/js-reference/announce.js.
 *
 * Layout: public_key(64) + name_hash(10) + random_hash(10) +
 *         [ratchet(32) if context_flag] + signature(64) + app_data
 */
data class ParsedAnnounce(
    val publicKey: ByteArray,    // 64 bytes
    val nameHash: ByteArray,     // 10 bytes
    val randomHash: ByteArray,   // 10 bytes
    val ratchet: ByteArray?,     // 32 bytes or null
    val signature: ByteArray,    // 64 bytes
    val appData: ByteArray,
    val identityHash: ByteArray, // 16 bytes, derived from publicKey
    val destHash: ByteArray,     // 16 bytes, from the packet header
)

/**
 * Parse an announce's payload (after Reticulum header).
 *
 * Returns null if the payload is too short to be a valid announce.
 */
suspend fun parseAnnounce(
    payload: ByteArray,
    contextFlag: Int,
    destHashFromHeader: ByteArray,
    crypto: CryptoProvider,
): ParsedAnnounce? {
    val minSize = KEYSIZE + NAME_HASH_LENGTH + NAME_HASH_LENGTH + SIGLENGTH
    if (payload.size < minSize) return null

    var offset = 0
    val publicKey  = payload.copyOfRange(offset, offset + KEYSIZE);          offset += KEYSIZE
    val nameHash   = payload.copyOfRange(offset, offset + NAME_HASH_LENGTH); offset += NAME_HASH_LENGTH
    val randomHash = payload.copyOfRange(offset, offset + NAME_HASH_LENGTH); offset += NAME_HASH_LENGTH

    var ratchet: ByteArray? = null
    if (contextFlag != 0) {
        if (payload.size < offset + 32 + SIGLENGTH) return null
        ratchet = payload.copyOfRange(offset, offset + 32); offset += 32
    }

    if (payload.size < offset + SIGLENGTH) return null
    val signature = payload.copyOfRange(offset, offset + SIGLENGTH); offset += SIGLENGTH
    val appData   = payload.copyOfRange(offset, payload.size)

    val identityHash = crypto.truncatedHash(publicKey, TRUNCATED_HASHLENGTH)

    return ParsedAnnounce(
        publicKey = publicKey,
        nameHash = nameHash,
        randomHash = randomHash,
        ratchet = ratchet,
        signature = signature,
        appData = appData,
        identityHash = identityHash,
        destHash = destHashFromHeader,
    )
}

/**
 * Validate an announce's Ed25519 signature.
 *
 * signed_data = dest_hash + public_key + name_hash + random_hash + [ratchet] + app_data
 * Signing key = publicKey[32..64] (Ed25519 half).
 */
fun validateAnnounce(announce: ParsedAnnounce, crypto: CryptoProvider): Boolean {
    val parts = ArrayList<ByteArray>(6)
    parts.add(announce.destHash)
    parts.add(announce.publicKey)
    parts.add(announce.nameHash)
    parts.add(announce.randomHash)
    announce.ratchet?.let { parts.add(it) }
    parts.add(announce.appData)
    val signedData = concatBytes(parts)
    val sigPub = announce.publicKey.copyOfRange(32, 64)
    return crypto.ed25519Verify(announce.signature, signedData, sigPub)
}

/**
 * Build an announce for our identity.
 *
 * Returns (destHash, payload, hasRatchet). The caller wraps `payload` in a
 * Reticulum PACKET_ANNOUNCE addressed to `destHash`. If `hasRatchet` is true,
 * the caller MUST set the packet header's contextFlag bit so receivers know
 * to parse the extra 32 bytes.
 */
suspend fun buildAnnounce(
    identity: Identity,
    crypto: CryptoProvider,
    appName: String = "lxmf.delivery",
    appData: ByteArray = ByteArray(0),
    ratchetPub: ByteArray? = null,
): Triple<ByteArray, ByteArray, Boolean> {
    val identityHash = identity.hash ?: error("Identity not initialised")
    val nameHash = computeNameHash(crypto, appName)
    val destHash = computeDestinationHash(crypto, appName, identityHash)

    val randomHash = crypto.randomBytes(10)

    val signedParts = ArrayList<ByteArray>(6)
    signedParts.add(destHash)
    signedParts.add(identity.publicKey)
    signedParts.add(nameHash)
    signedParts.add(randomHash)
    if (ratchetPub != null) signedParts.add(ratchetPub)
    signedParts.add(appData)
    val signedData = concatBytes(signedParts)
    val signature = identity.sign(signedData)

    val payloadParts = ArrayList<ByteArray>(6)
    payloadParts.add(identity.publicKey)
    payloadParts.add(nameHash)
    payloadParts.add(randomHash)
    if (ratchetPub != null) payloadParts.add(ratchetPub)
    payloadParts.add(signature)
    payloadParts.add(appData)
    val payload = concatBytes(payloadParts)

    return Triple(destHash, payload, ratchetPub != null)
}

/**
 * Extract display name from announce app_data.
 *
 * LXMF/Sideband announces pack app_data as msgpack `[display_name_bytes,
 * stamp_cost]` or sometimes as a raw UTF-8 string. Try msgpack first, fall
 * back to UTF-8.
 */
fun extractDisplayName(appData: ByteArray): String? {
    if (appData.isEmpty()) return null

    runCatching {
        val decoded = MessagePack.decode(appData)
        when (decoded) {
            is List<*> -> {
                val first = decoded.firstOrNull()
                when (first) {
                    is ByteArray -> return first.decodeToString()
                    is String    -> return first
                    else         -> Unit
                }
            }
            is String    -> return decoded
            is ByteArray -> return decoded.decodeToString()
            else         -> Unit
        }
    }

    return runCatching { appData.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

/**
 * Pick the right display name when an announce arrives for a destination
 * we may already know. The priority is:
 *
 *   1. A real name extracted from THIS announce's app_data
 *   2. The display name we already had on the existing row (don't clobber
 *      a "ratdeck1" that came in with full app_data the first time, just
 *      because a later announce arrived from a relay that stripped or
 *      truncated app_data)
 *   3. The KnownDestinations label for this name_hash (e.g. "LXMF delivery")
 *      — the lowest-quality fallback, only used when we know nothing else
 *   4. Empty string
 *
 * Bug pre-2026-05: the engine wrote
 *   `extractDisplayName(...) ?: knownLabel ?: ""`
 * which jumped to the KnownDestinations label whenever the new announce
 * had no extractable name, OVERWRITING a perfectly good existing name.
 * Symptom: Ratdeck contact briefly shows as "LXMF delivery" after a
 * minimal re-announce, then flips back when a full app_data announce
 * arrives.
 */
fun resolveDisplayName(
    extracted: String?,
    existing: String?,
    knownLabel: String?,
): String {
    if (!extracted.isNullOrBlank()) return extracted
    if (!existing.isNullOrBlank())  return existing
    if (!knownLabel.isNullOrBlank()) return knownLabel
    return ""
}

internal fun concatBytes(arrays: List<ByteArray>): ByteArray {
    val total = arrays.sumOf { it.size }
    val out = ByteArray(total)
    var pos = 0
    for (a in arrays) { a.copyInto(out, pos); pos += a.size }
    return out
}
