package io.github.thatsfguy.reticulum.announce

import io.github.thatsfguy.reticulum.codec.Cbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `rrc.hub` announce names, both encodings.
 *
 * app_data is CBOR from the `rrcd` reference hub and bare UTF-8 from the
 * `reticulum-relay-chat` Go hub, so the parser tries CBOR and falls
 * back. The fallback only works if a failed CBOR attempt actually
 * FAILS — and arbitrary text is far more likely to begin with a
 * plausible CBOR head than to be a whole CBOR item.
 *
 * The byte vectors below are the real `appDataHex` values captured off
 * a live mesh on 2026-08-31, not constructed examples: at that moment
 * the app was listing MichMesh's hub as "ichmesh RRC H".
 */
class RrcHubNameTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    // ---- bare UTF-8 (the Go hub) --------------------------------------

    /**
     * The regression. `M` = 0x4d = major type 2 (byte string) length 13,
     * so a first-item-only decode ate the name's own first letter as a
     * header and returned the next 13 bytes.
     */
    @Test fun aNameStartingWithMSurvivesTheCborAttempt() {
        val appData = hex("4d6963686d6573682052524320487562")   // "Michmesh RRC Hub"
        assertEquals("Michmesh RRC Hub", extractRrcHubName(appData))
    }

    /** Same shape, different letter: `N` = 0x4e -> byte string len 14. */
    @Test fun aNameStartingWithNSurvivesToo() {
        val appData = hex("4e656465726c616e647365204b616e616c656e")  // "Nederlandse Kanalen"
        assertEquals("Nederlandse Kanalen", extractRrcHubName(appData))
    }

    /**
     * This one always worked, and that is exactly why the bug hid: `t`
     * = 0x74 = text string length 20, which overruns the 9 available
     * bytes, so the CBOR attempt threw and the fallback ran.
     */
    @Test fun aNameThatNeverMisparsedStillWorks() {
        val appData = hex("746861745346677579")                  // "thatSFguy"
        assertEquals("thatSFguy", extractRrcHubName(appData))
    }

    /**
     * Every first byte that forms a CBOR string head, swept. Each name
     * is long enough for the implied length to fit, which is the
     * condition that turns a plausible head into a silent misparse.
     */
    @Test fun everyDangerousFirstCharacterIsHandled() {
        val tail = " Relay Chat Hub For Everyone"          // long enough for any len 0-23
        for (code in (0x40..0x57) + (0x60..0x77)) {
            val name = code.toChar() + tail
            val decoded = extractRrcHubName(name.encodeToByteArray())
            assertEquals(name, decoded, "name starting 0x${code.toString(16)} was mangled")
        }
    }

    // ---- CBOR (the rrcd reference hub) --------------------------------

    /** The other encoding must keep working: the name is the "hub" key. */
    @Test fun aCborMapStillYieldsTheHubName() {
        val appData = Cbor.encode(
            linkedMapOf<Any?, Any?>("proto" to "rrc", "v" to 1L, "hub" to "Colorado Mesh"),
        )
        assertEquals("Colorado Mesh", extractRrcHubName(appData))
    }

    /** A bare CBOR text string is also a valid encoding of the name. */
    @Test fun aCborTextStringYieldsTheName() {
        assertEquals("Nordic RRC", extractRrcHubName(Cbor.encode("Nordic RRC")))
    }

    @Test fun emptyAppDataYieldsNothing() {
        assertNull(extractRrcHubName(ByteArray(0)))
    }

    // ---- the decoder guarantee itself ---------------------------------

    /**
     * `decode` deliberately stops at the first item; `decodeComplete`
     * is the one that refuses leftovers. Pinning both keeps a future
     * "simplification" from collapsing them.
     */
    @Test fun decodeStopsAtTheFirstItemButDecodeCompleteRefusesLeftovers() {
        val bare = "Michmesh RRC Hub".encodeToByteArray()
        // The old behaviour, still available and still wrong for this input.
        assertEquals("ichmesh RRC H", (Cbor.decode(bare) as ByteArray).decodeToString())
        assertFailsWith<IllegalArgumentException> { Cbor.decodeComplete(bare) }
    }

    /** A whole, well-formed item is accepted unchanged. */
    @Test fun decodeCompleteAcceptsAnExactItem() {
        val encoded = Cbor.encode(linkedMapOf<Any?, Any?>(1L to "x"))
        assertEquals(mapOf(1L to "x"), Cbor.decodeComplete(encoded))
    }
}
