package io.github.thatsfguy.reticulum.rrc

import io.github.thatsfguy.reticulum.nomad.LinkTarget
import io.github.thatsfguy.reticulum.nomad.parseLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `rrc-room-links.md` **v2** — NomadNet's RRC link grammar.
 *
 * The cases here are transcribed from `Browser.handle_rrc_link`
 * (NomadNet 1.2.8, `Browser.py:426-461`) so this suite fails if we drift
 * from the client that actually reads these links. The v1 grammar this
 * replaced is exercised too: those links are already in the wild and
 * must keep working, even though nothing emits them any more.
 *
 * camelCase test names keep the Kotlin/Native compile happy.
 */
class RrcRoomLinkTest {

    private val hash = "a4383b4658729ab8e204e89724e2b383"

    // ---- writing ------------------------------------------------------

    @Test fun buildsTheCanonicalForm() {
        assertEquals("rrc://$hash/general", RrcRoomLink.build(hash, "general"))
    }

    /** The sigil is display decoration, and a link must address a room
     *  JOIN can reach — so it is normalised away before emission, the
     *  same `lstrip("#")` + `lower()` upstream applies on read. */
    @Test fun theSigilIsStrippedBeforeEmitting() {
        assertEquals("rrc://$hash/general", RrcRoomLink.build(hash, "#General"))
    }

    /**
     * The room segment is literal. NomadNet does no percent-decoding, so
     * an encoded name would join a room whose name contains a literal
     * `%20` — the same wrong-room failure v2 exists to end.
     */
    @Test fun theRoomSegmentIsNotPercentEncoded() {
        assertEquals("rrc://$hash/a:b@c", RrcRoomLink.build(hash, "a:b@c"))
        assertEquals("rrc://$hash/café", RrcRoomLink.build(hash, "café"))
        assertEquals("rrc://$hash/100%real", RrcRoomLink.build(hash, "100%real"))
    }

    /**
     * A link ends at the first whitespace when it is pasted into running
     * text, so a name containing a space cannot be written down without
     * silently addressing a shorter, different name. No link is better
     * than a wrong one — the caller hides the share affordance.
     *
     * This is the one thing v1 could express that v2 cannot; it is the
     * price of the room segment being literal, which is what makes every
     * other name work in NomadNet.
     */
    @Test fun aRoomNameWithWhitespaceEmitsNoLink() {
        assertNull(RrcRoomLink.build(hash, "off topic"))
        assertNull(RrcRoomLink.build(hash, "a\tb"))
        assertTrue(RrcRoomLink.isLinkSafeRoom("off-topic"))
        assertTrue(!RrcRoomLink.isLinkSafeRoom("off topic"))
    }

    /**
     * "A writer that does not know its own destination hash MUST emit no
     * link rather than a partial one. A malformed link is pasted onward
     * as though it worked."
     */
    @Test fun aWriterWithoutAValidHashEmitsNoLink() {
        assertNull(RrcRoomLink.build("", "general"))
        assertNull(RrcRoomLink.build("deadbeef", "general"))
        assertNull(RrcRoomLink.build("$hash:extra", "general"))
        assertNull(RrcRoomLink.build("0x$hash", "general"))
        assertNull(RrcRoomLink.buildHub("deadbeef"))
    }

    @Test fun anEmptyRoomEmitsNoLink() {
        assertNull(RrcRoomLink.build(hash, ""))
        assertNull(RrcRoomLink.build(hash, "#"))
    }

    @Test fun buildsAHubOnlyLink() {
        assertEquals("rrc://$hash", RrcRoomLink.buildHub(hash))
    }

    // ---- reading: the NomadNet grammar --------------------------------

    @Test fun parsesTheUrlForm() {
        assertEquals(LinkTarget.RrcRoom(hash, "ops"), parseLinkTarget("rrc://$hash/ops"))
    }

    /** `expand_shorthands` routes all three spellings to the same
     *  handler (`Browser.py:206-214`, `:312-314`). */
    @Test fun parsesEveryShorthandSpelling() {
        for (aspect in listOf("rrc", "rrc.hub", "rrc.hub.session")) {
            assertEquals(
                LinkTarget.RrcRoom(hash, "ops"),
                parseLinkTarget("$aspect@$hash/ops"),
                "aspect '$aspect'",
            )
        }
    }

    /** `Browser.py:431-432` strips one leading slash before splitting. */
    @Test fun oneLeadingSlashIsTolerated() {
        assertEquals(LinkTarget.RrcRoom(hash, "ops"), parseLinkTarget("rrc:///$hash/ops"))
    }

    /** The `:<dest_name>` slot names the hub's aspect. Spelling out the
     *  default is accepted; it addresses the same destination. */
    @Test fun theDefaultDestNameIsAccepted() {
        assertEquals(
            LinkTarget.RrcRoom(hash, "ops"),
            parseLinkTarget("rrc://$hash:rrc.hub/ops"),
        )
    }

    /**
     * A hub on a non-default aspect is rejected rather than silently
     * dialled on `rrc.hub`. This client hardcodes the aspect, so
     * honouring the slot would connect to a different destination than
     * the link names — the failure the whole v2 change is about.
     */
    @Test fun aNonDefaultDestNameIsRejected() {
        assertTrue(parseLinkTarget("rrc://$hash:my.hub/ops") is LinkTarget.Unknown)
    }

    /** Room normalisation on read is upstream's: `strip`, `lstrip("#")`,
     *  `lower()` (`Browser.py:445-446`). */
    @Test fun theRoomIsNormalisedOnRead() {
        assertEquals(LinkTarget.RrcRoom(hash, "ops"), parseLinkTarget("rrc://$hash/#Ops"))
        assertEquals(LinkTarget.RrcRoom(hash, "ops"), parseLinkTarget("rrc://$hash/OPS"))
    }

    @Test fun aLinkWithNoRoomIsAHub() {
        assertEquals(LinkTarget.RrcHub(hash), parseLinkTarget("rrc://$hash"))
        assertEquals(LinkTarget.RrcHub(hash), parseLinkTarget("rrc@$hash"))
    }

    @Test fun theHashIsLowerCasedOnRead() {
        assertEquals(
            LinkTarget.RrcRoom(hash, "ops"),
            parseLinkTarget("rrc://${hash.uppercase()}/ops"),
        )
    }

    /**
     * There is no path namespace in this grammar: everything after the
     * first `/` is the room name, so `:/page/index.mu` names a room
     * called `page/index.mu`. v1 reserved `/room/` so a later `/user/`
     * or `/invite/` target could be added unambiguously; adopting
     * upstream's grammar gives that up, and this test pins the loss so
     * it is a decision rather than a surprise.
     */
    @Test fun thereIsNoPathNamespace() {
        assertEquals(
            LinkTarget.RrcRoom(hash, "page/index.mu"),
            parseLinkTarget("rrc://$hash:/page/index.mu"),
        )
        assertEquals(LinkTarget.RrcRoom(hash, "a/b"), parseLinkTarget("rrc://$hash/a/b"))
    }

    /** A forgiving hash reader creates aliases for one destination and
     *  risks cache poisoning (`SPEC §11.6.3`), so this stays stricter
     *  than upstream's bare `bytes.fromhex` length check. */
    @Test fun aMalformedHashIsRejected() {
        assertTrue(parseLinkTarget("rrc://deadbeef/ops") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc://dead:beef:/room/general") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc@:/room/general") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc://") is LinkTarget.Unknown)
    }

    /** Matched exactly as `Browser.py:278` matches it. A link this
     *  client accepts and NomadNet rejects is the divergence v2 ends. */
    @Test fun theSchemeIsCaseSensitive() {
        assertTrue(parseLinkTarget("RRC://$hash/ops") is LinkTarget.Unknown)
    }

    // ---- reading: v1 links already in the wild ------------------------

    /**
     * A v1 link parses as an empty `dest_name` (its trailing colon) plus
     * a room of `room/<segment>` — a shape v2 cannot otherwise produce,
     * since a v2 link has either no colon or a non-empty `dest_name`.
     * That is what makes the shim unambiguous.
     */
    @Test fun v1LinksStillParse() {
        assertEquals(
            LinkTarget.RrcRoom(hash, "general"),
            parseLinkTarget("rrc@$hash:/room/general"),
        )
        assertEquals(
            LinkTarget.RrcRoom(hash, "general"),
            parseLinkTarget("rrc.hub@$hash:/room/%23General"),
        )
    }

    /** v1 percent-encoded its segment, which is how it could carry a
     *  name v2 refuses to write. Reading one still works. */
    @Test fun v1PercentEncodingIsStillDecoded() {
        assertEquals(
            LinkTarget.RrcRoom(hash, "off topic"),
            parseLinkTarget("rrc@$hash:/room/off%20topic"),
        )
        assertEquals(
            LinkTarget.RrcRoom(hash, "café"),
            parseLinkTarget("rrc@$hash:/room/caf%C3%A9"),
        )
    }

    @Test fun aMalformedV1EscapeIsRejected() {
        assertTrue(parseLinkTarget("rrc@$hash:/room/a%zz") is LinkTarget.Unknown)
        assertTrue(parseLinkTarget("rrc@$hash:/room/a%") is LinkTarget.Unknown)
    }

    /** A v1 link with an empty segment names no room; treat it as the
     *  hub rather than an error — it is a link to somewhere real. */
    @Test fun anEmptyV1SegmentIsAHub() {
        assertEquals(LinkTarget.RrcHub(hash), parseLinkTarget("rrc@$hash:/room/"))
    }

    // ---- the rest of the grammar is untouched -------------------------

    @Test fun theOtherShorthandsStillWork() {
        assertTrue(parseLinkTarget("nnn@$hash") is LinkTarget.CrossNode)
        assertTrue(parseLinkTarget("lxmf@$hash") is LinkTarget.Lxmf)
        assertTrue(parseLinkTarget("/page/index.mu") is LinkTarget.SameNode)
        assertTrue(parseLinkTarget("rrcx@$hash") is LinkTarget.Unknown)
    }

    // ---- round trip ---------------------------------------------------

    @Test fun everyLinkSafeRoomNameRoundTrips() {
        for (name in listOf(
            "general", "café", "日本語", "a:b@c", "under_score",
            "dash-dash", "dot.dot", "tilde~", "100%real", "a/b",
        )) {
            val link = RrcRoomLink.build(hash, name)!!
            assertEquals(
                LinkTarget.RrcRoom(hash, normalizeForTest(name)), parseLinkTarget(link),
                "round trip failed for '$name' via '$link'",
            )
        }
    }

    /** Mirror of `normalizeRrcRoom`, which is internal to the engine. */
    private fun normalizeForTest(s: String) =
        s.trim().trimStart('#').trim().lowercase()
}
