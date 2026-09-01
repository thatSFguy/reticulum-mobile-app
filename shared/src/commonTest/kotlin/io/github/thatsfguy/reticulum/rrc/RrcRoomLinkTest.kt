package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.nomad.LinkTarget
import io.github.thatsfguy.reticulum.nomad.parseLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `rrc-room-links.md` v1 — the text form for "this room, on this hub".
 *
 * camelCase test names keep the Kotlin/Native compile happy.
 */
class RrcRoomLinkTest {

    private val hash = "a4383b4658729ab8e204e89724e2b383"

    // ---- writing (§2) -------------------------------------------------

    @Test fun buildsTheCanonicalForm() {
        assertEquals("rrc@$hash:/room/general", RrcRoomLink.build(hash, "general"))
    }

    /** §2.2: the sigil is display decoration, and a link must address a
     *  room JOIN can reach — so it is normalised away before encoding. */
    @Test fun theSigilIsStrippedBeforeEncoding() {
        assertEquals("rrc@$hash:/room/general", RrcRoomLink.build(hash, "#General"))
    }

    /**
     * §2.2: escape everything outside the unreserved set — stricter
     * than a typical URL path encoder, because a link is a
     * whitespace-delimited token pasted out of a message body and `:`
     * and `@` are structural here.
     */
    @Test fun everythingOutsideUnreservedIsEncoded() {
        val link = RrcRoomLink.build(hash, "off topic")!!
        assertTrue(link.endsWith("/room/off%20topic"), link)
        // ':' and '@' must not survive raw — they would re-anchor the token.
        val tricky = RrcRoomLink.build(hash, "a:b@c")!!
        assertTrue(tricky.endsWith("/room/a%3Ab%40c"), tricky)
    }

    /** Non-Latin room names are legal RRC; encoding is over UTF-8 bytes. */
    @Test fun nonLatinNamesEncodeOverUtf8() {
        val link = RrcRoomLink.build(hash, "café")!!
        assertTrue(link.endsWith("/room/caf%C3%A9"), link)
    }

    /**
     * §2.1: "A writer that does not know its own destination hash MUST
     * emit no link rather than a partial one. A malformed link is
     * pasted onward as though it worked."
     */
    @Test fun aWriterWithoutAValidHashEmitsNoLink() {
        assertNull(RrcRoomLink.build("", "general"))
        assertNull(RrcRoomLink.build("deadbeef", "general"))
        assertNull(RrcRoomLink.build("$hash:extra", "general"))
        assertNull(RrcRoomLink.build("0x$hash", "general"))
    }

    @Test fun anEmptyRoomEmitsNoLink() {
        assertNull(RrcRoomLink.build(hash, ""))
        assertNull(RrcRoomLink.build(hash, "#"))
    }

    // ---- reading (§2, §3) ---------------------------------------------

    @Test fun parsesTheCanonicalForm() {
        val t = parseLinkTarget("rrc@$hash:/room/general")
        assertEquals(LinkTarget.RrcRoom(hash, "general"), t)
    }

    @Test fun parsesTheLongAspectForm() {
        assertEquals(
            LinkTarget.RrcRoom(hash, "general"),
            parseLinkTarget("rrc.hub@$hash:/room/general"),
        )
    }

    /** §3: "A link with no path names a hub only." */
    @Test fun aLinkWithNoPathIsAHub() {
        assertEquals(LinkTarget.RrcHub(hash), parseLinkTarget("rrc@$hash"))
    }

    @Test fun theHashIsLowerCasedOnRead() {
        val t = parseLinkTarget("rrc@${hash.uppercase()}:/room/general")
        assertEquals(LinkTarget.RrcRoom(hash, "general"), t)
    }

    /** Decode, THEN normalise — a link must not reach a room JOIN can't. */
    @Test fun theSegmentIsDecodedThenNormalised() {
        assertEquals(
            LinkTarget.RrcRoom(hash, "off topic"),
            parseLinkTarget("rrc@$hash:/room/off%20topic"),
        )
        assertEquals(
            LinkTarget.RrcRoom(hash, "general"),
            parseLinkTarget("rrc@$hash:/room/%23General"),
        )
        assertEquals(
            LinkTarget.RrcRoom(hash, "café"),
            parseLinkTarget("rrc@$hash:/room/caf%C3%A9"),
        )
    }

    /** §2.3: reject a path we don't recognise rather than guess, so a
     *  later `/user/` or `/invite/` target stays unambiguous. */
    @Test fun anUnrecognisedPathIsRejected() {
        assertTrue(parseLinkTarget("rrc@$hash:/page/index.mu") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc@$hash:/user/bob") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc@$hash:/room/") is LinkTarget.Unknown)
    }

    /** §2.1: a forgiving hash reader creates aliases for one
     *  destination and risks cache poisoning. */
    @Test fun aMalformedHashIsRejected() {
        assertTrue(parseLinkTarget("rrc@deadbeef:/room/general") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc@dead:beef:/room/general") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc@:/room/general") is LinkTarget.Unknown)
    }

    @Test fun aMalformedEscapeIsRejected() {
        assertTrue(parseLinkTarget("rrc@$hash:/room/a%zz") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc@$hash:/room/a%") is LinkTarget.Unknown)
    }

    /** The existing grammar must be untouched by the new shorthand. */
    @Test fun theOtherShorthandsStillWork() {
        assertTrue(parseLinkTarget("nnn@$hash") is LinkTarget.CrossNode)
        assertTrue(parseLinkTarget("lxmf@$hash") is LinkTarget.Lxmf)
        assertTrue(parseLinkTarget("/page/index.mu") is LinkTarget.SameNode)
        assertTrue(parseLinkTarget("rrcx@$hash") is LinkTarget.Unknown)
    }

    // ---- round trip ---------------------------------------------------

    @Test fun everyRoomNameRoundTrips() {
        for (name in listOf(
            "general", "off topic", "café", "日本語", "a:b@c", "under_score",
            "dash-dash", "dot.dot", "tilde~", "100% real", "a/b",
        )) {
            val link = RrcRoomLink.build(hash, name)!!
            val parsed = parseLinkTarget(link)
            assertEquals(
                LinkTarget.RrcRoom(hash, normalizeForTest(name)), parsed,
                "round trip failed for '$name' via '$link'",
            )
        }
    }

    /** Mirror of `normalizeRrcRoom`, which is internal to the engine. */
    private fun normalizeForTest(s: String) =
        s.trim().trimStart('#').trim().lowercase()
}
