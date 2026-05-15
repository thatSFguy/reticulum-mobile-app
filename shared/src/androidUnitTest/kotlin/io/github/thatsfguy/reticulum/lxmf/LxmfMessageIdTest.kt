package io.github.thatsfguy.reticulum.lxmf

import io.github.thatsfguy.reticulum.TestVectors
import io.github.thatsfguy.reticulum.codec.MessagePack
import io.github.thatsfguy.reticulum.protocol.SIGLENGTH
import io.github.thatsfguy.reticulum.protocol.TRUNCATED_HASHLENGTH
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the inbound LXMF `message_id` material per SPEC §5.5 / §5.7.1.
 *
 * `message_id = SHA256(dest_hash(16) || source_hash(16) || msgpack_payload)`.
 *
 * The `msgpack_payload` half is path-dependent:
 *
 *  - **Un-stamped** (4-element array) — `msgpack_payload` is the
 *    payload bytes EXACTLY AS RECEIVED. It must NOT be a
 *    decode→re-encode round-trip: a sender whose msgpack encoder
 *    drifts from ours (non-minimal int widths, str-vs-bin, float
 *    width — SPEC §5.6.1) produces wire bytes our encoder would not
 *    reproduce, so re-encoding yields a different id than every
 *    spec-compliant peer (and fwdsvc, which keys its rewrite cache on
 *    the id computed over received bytes).
 *
 *  - **Stamped** (5-element array) — the optional stamp element [4]
 *    is dropped and the first 4 elements are re-packed canonically
 *    (SPEC §5.7.1). You cannot slice a 4-element array out of a
 *    5-element one (the array header byte differs), so a canonical
 *    re-pack is mandatory here.
 *
 * Regression context: the engine previously hashed `msgpackForHash`
 * (the §5.6 stripped-signature re-encode) for the message_id on all
 * inbound paths. That silently worked for trivial-`fields` messages
 * whose re-encode happened to match the wire, and diverged for any
 * message carrying a non-trivial `fields` map (reply / reaction
 * markers) — fwdsvc reaction rewrites missed because the ids didn't
 * match. Fix: `msgpackForId`, computed raw for un-stamped.
 */
class LxmfMessageIdTest {

    private val crypto = TestVectors.crypto

    /** Fixmap of `{16: "x"}` with the integer key 16 deliberately
     *  packed as a non-minimal `uint8` (0xCC 0x10) instead of the
     *  canonical positive-fixint (0x10). Stands in for any sender
     *  whose encoder drifts from ours — our re-encode normalises it,
     *  so raw != re-encode. */
    private val nonCanonicalFields = byteArrayOf(
        0x81.toByte(),                     // fixmap, 1 entry
        0xCC.toByte(), 0x10,               // key: uint8 16 (non-minimal)
        0xA1.toByte(), 'x'.code.toByte(),  // value: fixstr "x"
    )

    /** The same map our canonical encoder produces — fixint key. */
    private val canonicalFields: Map<Any?, Any?> = mapOf(16L to "x")

    private fun fixedPrefix(): ByteArray =
        MessagePack.encode(1_700_000_000.0) +                 // timestamp float64
            MessagePack.encode(ByteArray(0)) +                // title  bin8 empty
            MessagePack.encode("hi".encodeToByteArray())      // content bin8

    private fun plaintextFor(payload: ByteArray): ByteArray =
        ByteArray(TRUNCATED_HASHLENGTH) { 2 } +               // source_hash
            ByteArray(SIGLENGTH) { 3 } +                      // signature
            payload

    @Test
    fun `un-stamped message_id hashes the raw wire payload, not a re-encode`() = runTest {
        val destHash = ByteArray(TRUNCATED_HASHLENGTH) { 1 }
        // fixarray of 4: timestamp, title, content, fields
        val rawPayload = byteArrayOf(0x94.toByte()) + fixedPrefix() + nonCanonicalFields

        val msg = unpackMessage(plaintextFor(rawPayload), destHash, crypto)

        // Fixture sanity: the re-encode must actually drift, otherwise
        // this test proves nothing.
        assertFalse(
            msg.msgpackData.contentEquals(msg.msgpackForHash),
            "fixture must exercise encoder drift — re-encode should differ from raw",
        )

        assertTrue(
            rawPayload.contentEquals(msg.msgpackForId),
            "un-stamped: message_id material must be the raw wire payload",
        )

        val id = LxmfStamp.computeMessageId(destHash, msg.sourceHash, msg.msgpackForId, crypto)
        val expected = crypto.sha256(destHash + msg.sourceHash + rawPayload)
        assertTrue(
            id.contentEquals(expected),
            "message_id must be SHA256(destHash || sourceHash || rawWirePayload)",
        )
    }

    @Test
    fun `stamped message_id re-packs the first 4 elements canonically`() = runTest {
        val destHash = ByteArray(TRUNCATED_HASHLENGTH) { 1 }
        val stamp = MessagePack.encode(ByteArray(LxmfStamp.STAMP_SIZE) { 9 })
        // fixarray of 5: timestamp, title, content, fields, stamp
        val rawPayload = byteArrayOf(0x95.toByte()) + fixedPrefix() + nonCanonicalFields + stamp

        val msg = unpackMessage(plaintextFor(rawPayload), destHash, crypto)

        // Cannot slice a 4-element array out of the 5-element wire form;
        // the id material is the canonical re-pack of the first 4.
        val expectedRepack = MessagePack.encode(
            listOf(1_700_000_000.0, ByteArray(0), "hi".encodeToByteArray(), canonicalFields),
        )
        assertTrue(
            expectedRepack.contentEquals(msg.msgpackForId),
            "stamped: message_id material must be the canonical 4-element re-pack",
        )

        val id = LxmfStamp.computeMessageId(destHash, msg.sourceHash, msg.msgpackForId, crypto)
        val expected = crypto.sha256(destHash + msg.sourceHash + expectedRepack)
        assertTrue(id.contentEquals(expected), "stamped message_id must hash the canonical re-pack")
    }
}
