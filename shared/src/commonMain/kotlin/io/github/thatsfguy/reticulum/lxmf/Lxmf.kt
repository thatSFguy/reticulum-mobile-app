package io.github.thatsfguy.reticulum.lxmf

import io.github.thatsfguy.reticulum.announce.concatBytes
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity
import io.github.thatsfguy.reticulum.protocol.SIGLENGTH
import io.github.thatsfguy.reticulum.protocol.TRUNCATED_HASHLENGTH

/**
 * LXMF message pack/unpack. Port of reference/js-reference/lxmf.js.
 *
 * On-wire formats:
 *   Opportunistic (single-packet, decrypted plaintext):
 *     source_hash(16) + signature(64) + msgpack([ts, title, content, fields])
 *
 *   Link-delivered:
 *     dest_hash(16) + source_hash(16) + signature(64) + msgpack([...])
 *
 * IMPORTANT: source_hash is the sender's DESTINATION hash, not their identity
 * hash. See CLAUDE.md "Key bugs" §3.
 *
 * Signature semantics match upstream LXMF:
 *   hashed_part   = dest_hash + source_hash + msgpack_payload
 *   message_hash  = SHA-256(hashed_part)
 *   signed_data   = hashed_part + message_hash
 *   signature     = Ed25519(sender.sigPriv, signed_data)
 *
 * The sender may emit msgpack bytes that differ from what our encoder
 * produces for the same logical data (different numeric widths, etc.). To
 * handle this we keep BOTH a re-encoded "stripped" view and the raw
 * on-wire bytes; verifyMessageSignature() tries both. CLAUDE.md "Key
 * bugs" §5.
 */
data class LxmfMessage(
    val sourceHash: ByteArray,    // 16 bytes — sender's DESTINATION hash
    val destHash: ByteArray,      // 16 bytes — recipient's destination hash
    val signature: ByteArray,     // 64 bytes
    val timestamp: Double,        // seconds since epoch (may be small if sender clockless)
    val title: String,
    val content: String,
    val fields: Map<Any?, Any?>,
    val stamp: Any?,              // optional 5th payload element
    val msgpackData: ByteArray,   // raw on-wire msgpack bytes
    val msgpackForHash: ByteArray, // re-encoded msgpack (for §5.6 stripped-signature variant)
    val msgpackForId: ByteArray,  // message_id material per §5.5/§5.7.1 (see unpackMessage)
    val payloadElementCount: Int,
)

private const val DESTINATION_LENGTH = TRUNCATED_HASHLENGTH

/**
 * Unpack an opportunistic LXMF message (decrypted plaintext).
 *
 * @param plaintext source_hash(16) + signature(64) + msgpack(...)
 * @param ourDestHash recipient's destination hash (this client's, from RNS header)
 */
suspend fun unpackMessage(
    plaintext: ByteArray,
    ourDestHash: ByteArray,
    crypto: CryptoProvider,
): LxmfMessage {
    require(plaintext.size >= TRUNCATED_HASHLENGTH + SIGLENGTH + 1) { "LXMF message too short" }

    val sourceHash  = plaintext.copyOfRange(0, TRUNCATED_HASHLENGTH)
    val signature   = plaintext.copyOfRange(TRUNCATED_HASHLENGTH, TRUNCATED_HASHLENGTH + SIGLENGTH)
    val msgpackData = plaintext.copyOfRange(TRUNCATED_HASHLENGTH + SIGLENGTH, plaintext.size)

    val payload = MessagePack.decode(msgpackData)
    require(payload is List<*> && payload.size >= 4) { "Invalid LXMF payload structure" }

    val timestamp = (payload[0] as? Number)?.toDouble() ?: 0.0
    val title     = decodeField(payload[1])
    val content   = decodeField(payload[2])
    @Suppress("UNCHECKED_CAST")
    val fields    = (payload[3] as? Map<Any?, Any?>) ?: emptyMap()
    val stamp     = if (payload.size > 4) payload[4] else null

    // Re-encode the first 4 elements for the "stripped" hash variant. The
    // sender excludes the optional stamp from the signed data, so we always
    // hash without it. We pass the ORIGINAL payload[1] / payload[2] (which
    // are ByteArrays per the LXMF wire format) so the re-encoded msgpack
    // is bin-typed, not str-typed.
    val strippedMsgpack = MessagePack.encode(listOf(timestamp, payload[1], payload[2], fields))

    // message_id material per SPEC §5.5 / §5.7.1:
    //   message_id = SHA256(dest_hash || source_hash || msgpack_payload)
    // For an un-stamped message (4 elements) `msgpack_payload` is the
    // payload EXACTLY AS RECEIVED — re-encoding it would diverge from
    // every spec-compliant peer (and fwdsvc's rewrite-cache key) the
    // moment the sender's msgpack encoder drifts from ours (§5.6.1).
    // For a stamped message (5 elements) you cannot slice a 4-element
    // array out of the wire bytes — §5.7.1 mandates dropping element
    // [4] and re-packing the first 4 canonically, which is exactly
    // `strippedMsgpack`. NOT to be confused with `msgpackForHash`,
    // which serves the §5.6 dual-variant signature check.
    val msgpackForId = if (payload.size == 4) msgpackData else strippedMsgpack

    return LxmfMessage(
        sourceHash = sourceHash,
        destHash = ourDestHash,
        signature = signature,
        timestamp = timestamp,
        title = title,
        content = content,
        fields = fields,
        stamp = stamp,
        msgpackData = msgpackData,
        msgpackForHash = strippedMsgpack,
        msgpackForId = msgpackForId,
        payloadElementCount = payload.size,
    )
}

/**
 * Unpack a link-delivered LXMF message (received decrypted from a Link).
 *
 * Format: dest_hash(16) + source_hash(16) + signature(64) + msgpack(...)
 */
suspend fun unpackLinkMessage(data: ByteArray, crypto: CryptoProvider): LxmfMessage {
    require(data.size >= 2 * TRUNCATED_HASHLENGTH + SIGLENGTH + 1) { "LXMF link message too short" }
    val destHash = data.copyOfRange(0, TRUNCATED_HASHLENGTH)
    val inner    = data.copyOfRange(TRUNCATED_HASHLENGTH, data.size)
    return unpackMessage(inner, destHash, crypto)
}

/**
 * Pack an outbound opportunistic LXMF message.
 *
 * Returns the plaintext bytes (source_hash + signature + msgpack) ready to
 * be Token-encrypted to the recipient's encryption pub key.
 */
suspend fun packMessage(
    sourceIdentity: Identity,
    destHash: ByteArray,
    sourceHash: ByteArray,
    title: String,
    content: String,
    timestampSeconds: Double,
    fields: Map<Any?, Any?> = emptyMap(),
    crypto: CryptoProvider,
    /** Optional LXMF stamp per SPEC §5.7. When non-null, this is the
     *  32-byte PoW value the caller computed via [LxmfStamp.findStamp];
     *  it's appended as element [4] of the wire msgpack array. The
     *  Ed25519 signature is still computed over the 4-element
     *  "stripped" variant (§5.6) so the stamp can be added/removed
     *  without invalidating the sig. */
    stamp: ByteArray? = null,
): ByteArray {
    val titleBytes   = title.encodeToByteArray()
    val contentBytes = content.encodeToByteArray()
    // Sign over the 4-element form per §5.6. The wire form below may
    // additionally carry a 5th element (stamp) — receivers strip it
    // before verifying.
    val signableMsgpack = MessagePack.encode(listOf(timestampSeconds, titleBytes, contentBytes, fields))

    val hashedPart  = concatBytes(listOf(destHash, sourceHash, signableMsgpack))
    val messageHash = crypto.sha256(hashedPart)
    val signedData  = concatBytes(listOf(hashedPart, messageHash))
    val signature   = sourceIdentity.sign(signedData)

    val wireMsgpack = if (stamp != null) {
        MessagePack.encode(listOf(timestampSeconds, titleBytes, contentBytes, fields, stamp))
    } else {
        signableMsgpack
    }
    return concatBytes(listOf(sourceHash, signature, wireMsgpack))
}

/**
 * Pack an outbound link-delivered LXMF message.
 *
 * Wire format: dest_hash(16) || source_hash(16) || signature(64) || msgpack(...).
 * Signature semantics are identical to the opportunistic form (the
 * sender signs SHA-256(dest_hash || source_hash || msgpack || hashed_part_hash)),
 * so we just compose: prepend dest_hash to packMessage's output.
 *
 * Returns the plaintext bytes ready to be Token-encrypted with the
 * link's session key (link.derivedKey).
 */
suspend fun packLinkMessage(
    sourceIdentity: Identity,
    destHash: ByteArray,
    sourceHash: ByteArray,
    title: String,
    content: String,
    timestampSeconds: Double,
    fields: Map<Any?, Any?> = emptyMap(),
    crypto: CryptoProvider,
    /** Optional LXMF stamp per SPEC §5.7 — see [packMessage]. */
    stamp: ByteArray? = null,
): ByteArray {
    val opportunisticBody = packMessage(
        sourceIdentity   = sourceIdentity,
        destHash         = destHash,
        sourceHash       = sourceHash,
        title            = title,
        content          = content,
        timestampSeconds = timestampSeconds,
        fields           = fields,
        crypto           = crypto,
        stamp            = stamp,
    )
    return concatBytes(listOf(destHash, opportunisticBody))
}

/**
 * Verify an LXMF message signature using the sender's public key.
 *
 * Tries the stripped (re-encoded msgpack) variant first, then falls back to
 * the raw on-wire bytes. Returns the matched variant or null if neither
 * passes.
 */
suspend fun verifyMessageSignature(
    msg: LxmfMessage,
    senderIdentity: Identity,
    crypto: CryptoProvider,
): SignatureVariant? {
    val strippedHashedPart = concatBytes(listOf(msg.destHash, msg.sourceHash, msg.msgpackForHash))
    val strippedHash = crypto.sha256(strippedHashedPart)
    val strippedSigned = concatBytes(listOf(strippedHashedPart, strippedHash))
    if (senderIdentity.verify(msg.signature, strippedSigned)) return SignatureVariant.STRIPPED

    val originalHashedPart = concatBytes(listOf(msg.destHash, msg.sourceHash, msg.msgpackData))
    val originalHash = crypto.sha256(originalHashedPart)
    val originalSigned = concatBytes(listOf(originalHashedPart, originalHash))
    if (senderIdentity.verify(msg.signature, originalSigned)) return SignatureVariant.ORIGINAL

    return null
}

enum class SignatureVariant { STRIPPED, ORIGINAL }

private fun decodeField(v: Any?): String = when (v) {
    null         -> ""
    is ByteArray -> v.decodeToString()
    is String    -> v
    else         -> v.toString()
}
