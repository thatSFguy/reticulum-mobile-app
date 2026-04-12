package io.github.thatsfguy.reticulum.lxmf

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.Identity

/**
 * Unpacked LXMF message.
 * Port of reference/js-reference/lxmf.js.
 */
data class LxmfMessage(
    val sourceHash: ByteArray,     // 16 bytes — sender's DESTINATION hash (not identity hash!)
    val destHash: ByteArray,       // 16 bytes — recipient's destination hash
    val signature: ByteArray,      // 64 bytes
    val timestamp: Any?,           // Number (seconds since epoch) or platform Date
    val title: String,
    val content: String,
    val fields: Map<Any?, Any?>?,
    val msgpackData: ByteArray,    // original msgpack bytes for hash verification
    val msgpackForHash: ByteArray, // re-encoded msgpack for signature variant checking
    val payloadElementCount: Int,
)

/**
 * Unpack an opportunistic LXMF message (decrypted plaintext).
 *
 * Format: source_hash(16) + signature(64) + msgpack([ts, title, content, fields])
 *
 * IMPORTANT: source_hash is the sender's DESTINATION hash, not their identity hash.
 * See CLAUDE.md "Key bugs" §3.
 *
 * TODO: Port from reference/js-reference/lxmf.js unpackMessage().
 *       Needs msgpack decoder. Must produce BOTH msgpackData (original bytes)
 *       and msgpackForHash (re-encoded) for the dual-variant signature check.
 */
suspend fun unpackMessage(plaintext: ByteArray, ourDestHash: ByteArray): LxmfMessage {
    TODO("Port from reference/js-reference/lxmf.js unpackMessage()")
}

/**
 * Unpack a link-delivered LXMF message (received over an established Link).
 *
 * Format: dest_hash(16) + source_hash(16) + signature(64) + msgpack([ts, title, content, fields])
 *
 * TODO: Port from reference/js-reference/lxmf.js unpackLinkMessage().
 */
suspend fun unpackLinkMessage(data: ByteArray): LxmfMessage {
    TODO("Port from reference/js-reference/lxmf.js unpackLinkMessage()")
}

/**
 * Pack an outgoing LXMF message for opportunistic delivery.
 *
 * TODO: Port from reference/js-reference/lxmf.js packMessage().
 *       The packed plaintext is then Token-encrypted for the recipient.
 */
suspend fun packMessage(
    identity: Identity,
    sourceDestHash: ByteArray,
    recipientDestHash: ByteArray,
    content: String,
    timestamp: Long,
    title: String = "",
): ByteArray {
    TODO("Port from reference/js-reference/lxmf.js packMessage()")
}

/**
 * Verify an LXMF message signature. Must try BOTH the stripped
 * (re-encoded) and original msgpack bytes because different encoders
 * produce different binary output for the same logical data.
 * See CLAUDE.md "Key bugs" §5.
 *
 * TODO: Port from reference/js-reference/lxmf.js verifyMessageSignature().
 */
fun verifyMessageSignature(msg: LxmfMessage, senderIdentity: Identity): Boolean {
    TODO("Port from reference/js-reference/lxmf.js verifyMessageSignature()")
}
