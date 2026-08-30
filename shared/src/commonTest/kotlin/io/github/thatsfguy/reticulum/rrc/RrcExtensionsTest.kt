package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RRC extension keys — replies and reactions
 * (`reticulum-relay-chat/docs/rrc-extensions.md` v1, keys 64/65/66).
 *
 * The three encode assertions are the document's own §7 test vectors,
 * hex-encoded here. That makes them an EXTERNAL oracle: they were
 * written from the CBOR diagnostic notation in the spec, not produced
 * by this codebase, so a bug in our encoder cannot make them pass. Per
 * §7 an implementation that encodes canonically is byte-identical to
 * any other, which is the property being pinned.
 *
 * camelCase test names keep the iosTest Kotlin/Native compile happy.
 */
class RrcExtensionsTest {

    private val src = "6b621001912fd0bd5d6a33ae183fc56b".hexToBytes()
    private val anchor = "a41b9c33d2e05f18".hexToBytes()

    // --- §7 vectors ---------------------------------------------------

    private val replyVector =
        "a90001011402480102030405060708031b000001a04886f80004506b621001912fd0bd5d" +
            "6a33ae183fc56b05656c6f62627906717965732c2065786163746c79207468617407665761" +
            "6c64656e184048a41b9c33d2e05f18"

    private val reactVector =
        "a90001011402481112131415161718031b000001a04886fbe804506b621001912fd0bd5d" +
            "6a33ae183fc56b05656c6f6262790664f09f918d076657616c64656e184148a41b9c33d2e0" +
            "5f18"

    private val retractVector =
        "aa0001011402482122232425262728031b000001a04886ffd004506b621001912fd0bd5d" +
            "6a33ae183fc56b05656c6f6262790664f09f918d076657616c64656e184148a41b9c33d2e0" +
            "5f18184201"

    @Test fun replyEncodesToTheSpecVector() {
        val env = RrcEnvelope(
            type = Rrc.T_MSG,
            msgId = "0102030405060708".hexToBytes(),
            timestampMs = 1787923200000L,
            src = src,
            room = "lobby",
            body = "yes, exactly that",
            nick = "Walden",
            replyTo = anchor,
        )
        assertEquals(replyVector, env.encode().toHex())
    }

    @Test fun reactionEncodesToTheSpecVector() {
        val env = RrcEnvelope(
            type = Rrc.T_MSG,
            msgId = "1112131415161718".hexToBytes(),
            timestampMs = 1787923201000L,
            src = src,
            room = "lobby",
            body = "👍",
            nick = "Walden",
            reactTo = anchor,
        )
        assertEquals(reactVector, env.encode().toHex())
    }

    @Test fun retractionEncodesToTheSpecVector() {
        val env = RrcEnvelope(
            type = Rrc.T_MSG,
            msgId = "2122232425262728".hexToBytes(),
            timestampMs = 1787923202000L,
            src = src,
            room = "lobby",
            body = "👍",
            nick = "Walden",
            reactTo = anchor,
            reactOp = Rrc.REACT_OP_RETRACT,
        )
        assertEquals(retractVector, env.encode().toHex())
    }

    // --- decode -------------------------------------------------------

    @Test fun replyVectorDecodes() {
        val env = RrcEnvelope.decode(replyVector.hexToBytes())
        assertEquals(Rrc.T_MSG, env.type)
        assertEquals("yes, exactly that", env.body)
        assertEquals(anchor.toHex(), env.replyTo?.toHex())
        assertNull(env.reactTo)
    }

    @Test fun retractionVectorDecodes() {
        val env = RrcEnvelope.decode(retractVector.hexToBytes())
        assertEquals(anchor.toHex(), env.reactTo?.toHex())
        assertEquals(Rrc.REACT_OP_RETRACT, env.reactOp)
        assertNull(env.replyTo)
    }

    @Test fun extensionsSurviveARoundTrip() {
        val env = RrcEnvelope(
            type = Rrc.T_MSG, msgId = ByteArray(8) { 3 }, timestampMs = 5L,
            src = src, room = "lobby", body = "hi", replyTo = anchor,
        )
        val back = RrcEnvelope.decode(env.encode())
        assertEquals(anchor.toHex(), back.replyTo?.toHex())
    }

    /** An envelope with neither key is exactly what it was before this
     *  extension existed — no stray keys, byte-identical to a plain MSG. */
    @Test fun anOrdinaryMessageGainsNoExtensionKeys() {
        val plain = RrcEnvelope(
            type = Rrc.T_MSG, msgId = ByteArray(8), timestampMs = 1L,
            src = src, room = "lobby", body = "hi",
        )
        val decoded = RrcEnvelope.decode(plain.encode())
        assertNull(decoded.replyTo)
        assertNull(decoded.reactTo)
        assertNull(decoded.reactOp)
    }

    // --- the rules the spec states -----------------------------------

    /** §2: "A message carrying both is malformed; a receiver SHOULD
     *  treat it as a reply and ignore the reaction." */
    @Test fun aMessageCarryingBothAnchorsIsReadAsAReply() {
        val both = RrcEnvelope(
            type = Rrc.T_MSG, msgId = ByteArray(8) { 1 }, timestampMs = 1L,
            src = src, room = "lobby", body = "?",
            replyTo = anchor, reactTo = ByteArray(8) { 9 },
        )
        // Built with both, but decoding applies the rule.
        val back = RrcEnvelope.decode(both.encode())
        assertEquals(anchor.toHex(), back.replyTo?.toHex())
        assertNull(back.reactTo)
    }

    /** A malformed anchor must not cost the message: extensions are
     *  optional decoration, and rejecting the envelope would let a peer
     *  suppress chat by attaching a bad one. */
    @Test fun aMalformedAnchorIsDroppedNotFatal() {
        val m = linkedMapOf<Any?, Any?>(
            Rrc.K_V to 1, Rrc.K_T to Rrc.T_MSG, Rrc.K_ID to ByteArray(8),
            Rrc.K_TS to 1L, Rrc.K_SRC to src, Rrc.K_ROOM to "lobby",
            Rrc.K_BODY to "still a message",
            Rrc.K_REPLY_TO to "not bytes",
        )
        val env = RrcEnvelope.fromMap(m)
        assertEquals("still a message", env.body)
        assertNull(env.replyTo)
    }

    @Test fun anOversizedAnchorIsDropped() {
        val m = linkedMapOf<Any?, Any?>(
            Rrc.K_V to 1, Rrc.K_T to Rrc.T_MSG, Rrc.K_ID to ByteArray(8),
            Rrc.K_TS to 1L, Rrc.K_SRC to src, Rrc.K_ROOM to "lobby",
            Rrc.K_BODY to "hi", Rrc.K_REACT_TO to ByteArray(65),
        )
        assertNull(RrcEnvelope.fromMap(m).reactTo)
    }

    /** Only the two defined operations exist; anything else is not a
     *  retract, so it must not be read as one. */
    @Test fun anUnknownReactOpIsIgnored() {
        val m = linkedMapOf<Any?, Any?>(
            Rrc.K_V to 1, Rrc.K_T to Rrc.T_MSG, Rrc.K_ID to ByteArray(8),
            Rrc.K_TS to 1L, Rrc.K_SRC to src, Rrc.K_ROOM to "lobby",
            Rrc.K_BODY to "👍", Rrc.K_REACT_TO to anchor,
            Rrc.K_REACT_OP to 99,
        )
        assertNull(RrcEnvelope.fromMap(m).reactOp)
    }

    /** §1: keys 8..63 are reserved for a future RRC core and a hub drops
     *  them, so we must never emit one — a client that did would be
     *  pre-empting a key the spec has not assigned. */
    @Test fun theExtensionKeysAreAboveTheReservedRange() {
        assertTrue(Rrc.K_REPLY_TO >= 64)
        assertTrue(Rrc.K_REACT_TO >= 64)
        assertTrue(Rrc.K_REACT_OP >= 64)
    }

    /** §5: the hub caps the total encoded size of extension keys and
     *  REJECTS rather than truncates past it, so anything we send has
     *  to fit or the whole message is refused. */
    @Test fun ourExtensionsFitTheHubsSizeCap() {
        val maxed = RrcEnvelope(
            type = Rrc.T_MSG, msgId = ByteArray(8), timestampMs = 1L,
            src = src, room = "lobby", body = "👍",
            reactTo = ByteArray(8), reactOp = Rrc.REACT_OP_RETRACT,
        )
        val plain = RrcEnvelope(
            type = Rrc.T_MSG, msgId = ByteArray(8), timestampMs = 1L,
            src = src, room = "lobby", body = "👍",
        )
        val extBytes = maxed.encode().size - plain.encode().size
        assertTrue(extBytes <= Rrc.MAX_EXT_BYTES, "extensions cost $extBytes bytes")
    }
}
