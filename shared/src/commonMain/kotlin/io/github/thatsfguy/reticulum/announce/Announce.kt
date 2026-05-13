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
 * Validate an announce's Ed25519 signature AND its destination-hash
 * consistency.
 *
 * Two distinct checks, both required:
 *  1. Ed25519 signature over signed_data verifies under the announced
 *     public key. signed_data = dest_hash + public_key + name_hash +
 *     random_hash + [ratchet] + app_data. Signing key = publicKey[32..64]
 *     (Ed25519 half).
 *  2. The on-wire dest_hash equals SHA-256(name_hash || identity_hash)[:16]
 *     where identity_hash = SHA-256(public_key)[:16]. Without this an
 *     attacker can craft an announce that claims any victim's dest_hash
 *     paired with the attacker's own keypair, sign it correctly under the
 *     attacker's key (so check #1 passes), and have us upsert the
 *     attacker's public key over the victim's row. Subsequent
 *     opportunistic LXMF we send to that dest_hash would then be
 *     encrypted to the attacker, not the legitimate owner. Upstream
 *     RNS Identity.validate_announce performs this consistency check;
 *     we missed it pre-v0.1.82.
 */
suspend fun validateAnnounce(announce: ParsedAnnounce, crypto: CryptoProvider): Boolean {
    // Cheap consistency check first — no signature verification needed
    // to reject a forged dest_hash, so we can short-circuit on a forged
    // announce without spending the Ed25519 verify cycles.
    val expectedDestHash = crypto.truncatedHash(
        announce.nameHash + announce.identityHash,
        TRUNCATED_HASHLENGTH,
    )
    if (!expectedDestHash.contentEquals(announce.destHash)) return false

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
    nowSeconds: Long,
): Triple<ByteArray, ByteArray, Boolean> {
    val identityHash = identity.hash ?: error("Identity not initialised")
    val nameHash = computeNameHash(crypto, appName)
    val destHash = computeDestinationHash(crypto, appName, identityHash)

    // 5 random bytes + 5-byte big-endian Unix-seconds timestamp.
    // RNS uses the timestamp portion as the path-merge tiebreaker —
    // emitting fully-random bytes here causes upstream's path table to
    // churn unpredictably (see buildRandomHash docs).
    val randomHash = buildRandomHash(crypto.randomBytes(5), nowSeconds)

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
 * Extract the recipient's `stamp_cost` from announce app_data per
 * SPEC §5.7.4. LXMF announces pack app_data as msgpack
 * `[display_name_bytes, stamp_cost]`; `stamp_cost` is an integer
 * in [1, 254] when the recipient requires a stamp, or null /
 * absent / 0 when no stamp is required.
 *
 * Returns null when:
 *   - app_data isn't valid msgpack
 *   - the decoded value isn't a list of length ≥ 2
 *   - element [1] is null / nil / 0 (= "no stamp required")
 *   - element [1] is outside the 1..254 valid range
 *
 * Returns the cost otherwise. msgpack decoders surface integer
 * values as `Int`, `Long`, or `Short` depending on encode width;
 * accept any `Number` and coerce.
 */
fun extractStampCost(appData: ByteArray): Int? {
    if (appData.isEmpty()) return null

    val decoded = runCatching { MessagePack.decode(appData) }.getOrNull() ?: return null
    val list = decoded as? List<*> ?: return null
    if (list.size < 2) return null
    val raw = list[1] as? Number ?: return null
    val cost = raw.toInt()
    // Upstream LXMRouter treats 0 / null identically to "no
    // requirement"; cap at 254 per the inventory description.
    return if (cost in 1..254) cost else null
}

/**
 * Build the 10-byte `random_hash` field of an announce.
 *
 * The name "random_hash" is misleading: only the first 5 bytes are
 * random. The trailing 5 bytes carry the emission **Unix timestamp in
 * seconds** as a big-endian unsigned 40-bit integer. Upstream RNS uses
 * those 5 trailing bytes to decide which of two announces from the
 * same destination is "newer" — see `RNS/Destination.py:282` and the
 * path-table merge logic at `RNS/Transport.py:1700-1745`.
 *
 * Implementations that emit 10 fully-random bytes appear to upstream
 * RNS as having an essentially random emission timestamp — sometimes
 * a far-future time (causing a fresh announce to "lose" against later
 * legitimate announces because RNS thinks our latest is older), and
 * sometimes a far-past time. Either way the path table churns
 * unpredictably and peers' attempts to message back race against
 * mis-ordered cache decisions.
 *
 * Verified against RNS 1.2.0 by `reticulum-specifications/SPEC.md` §4
 * which cites the exact upstream construction.
 *
 * @param random5Bytes 5 bytes of cryptographic randomness
 * @param unixSeconds emission time as Unix seconds; will be encoded big-endian uint40
 * @return 10 bytes: random5Bytes(5) || timestamp_be(5)
 */
fun buildRandomHash(random5Bytes: ByteArray, unixSeconds: Long): ByteArray {
    require(random5Bytes.size == 5) { "random5Bytes must be 5 bytes, got ${random5Bytes.size}" }
    require(unixSeconds in 0..0xFF_FF_FF_FF_FFL) {
        "unixSeconds must fit in unsigned 40 bits, got $unixSeconds"
    }
    val out = ByteArray(10)
    random5Bytes.copyInto(out, destinationOffset = 0)
    out[5] = ((unixSeconds shr 32) and 0xFF).toByte()
    out[6] = ((unixSeconds shr 24) and 0xFF).toByte()
    out[7] = ((unixSeconds shr 16) and 0xFF).toByte()
    out[8] = ((unixSeconds shr 8)  and 0xFF).toByte()
    out[9] = ( unixSeconds         and 0xFF).toByte()
    return out
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
