package io.github.thatsfguy.reticulum.announce

import io.github.thatsfguy.reticulum.codec.MessagePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryTest {

    @Test fun parsesTypicalRlrBlob() {
        val parsed = parseTelemetry("bat=3867;lat=43.16;lon=-83.5;alt=275")
        assertEquals("3867", parsed["bat"])
        assertEquals("43.16", parsed["lat"])
        assertEquals("-83.5", parsed["lon"])
        assertEquals("275", parsed["alt"])
        val coords = extractCoordinates(parsed)
        assertEquals(43.16 to -83.5, coords)
    }

    @Test fun toleratesWhitespaceAndEmptySegments() {
        val parsed = parseTelemetry(" foo = bar ;; baz=qux; ")
        assertEquals("bar", parsed["foo"])
        assertEquals("qux", parsed["baz"])
    }

    @Test fun dropsMalformedEntries() {
        val parsed = parseTelemetry("nokv;justeq=;goodkey=goodval;=novalue")
        assertEquals(2, parsed.size)
        assertEquals("", parsed["justeq"])
        assertEquals("goodval", parsed["goodkey"])
    }

    @Test fun missingCoordinatesReturnsNull() {
        assertNull(extractCoordinates(parseTelemetry("bat=3867")))
        assertNull(extractCoordinates(parseTelemetry("lat=oops;lon=42.0")))
    }

    // v0.1.51 msgpack-aware bytes parser. Covers both formats so the
    // engine can hand it raw appData and get a clean key→string map
    // regardless of which sender we're looking at.

    @Test fun bytesParserPrefersTextWhenItParses() {
        val text = "bat=3867;lat=43.16;lon=-83.5"
        val parsed = parseTelemetryBytes(text.encodeToByteArray())
        assertEquals("3867", parsed["bat"])
        assertEquals("43.16", parsed["lat"])
        assertEquals("-83.5", parsed["lon"])
    }

    @Test fun bytesParserDecodesMsgpackInterfaceBroadcast() {
        // Same shape as a real RNS.MichMesh.net BackboneInterface
        // broadcast (captured 2026-05-04): `[hop_count, {int → value}]`.
        // Built via the codec rather than hand-rolling bytes — reliable
        // float64 / fixmap ordering and easy to extend.
        val packet = MessagePack.encode(listOf(
            0,
            mapOf(
                0 to "BackboneInterface",
                2 to "10.0.0.1",
                3 to 49152.0,   // bandwidth bps
                5 to 500.0,     // mtu
            ),
        ))
        val parsed = parseTelemetryBytes(packet)
        assertEquals("BackboneInterface", parsed["interfaceType"])
        assertEquals("10.0.0.1",          parsed["address"])
        assertEquals("49152",             parsed["bandwidthBps"])
        assertEquals("500",               parsed["mtu"])
    }

    @Test fun bytesParserHandlesBareMapWithoutEnvelope() {
        // `{ 0: "RNodeInterface" }` — no [hop_count, …] wrapper.
        val packet = MessagePack.encode(mapOf(0 to "RNodeInterface"))
        val parsed = parseTelemetryBytes(packet)
        assertEquals("RNodeInterface", parsed["interfaceType"])
    }

    @Test fun bytesParserUnknownIntKeysFallThroughAsFieldN() {
        val packet = MessagePack.encode(mapOf(99 to "unknown_field_value"))
        val parsed = parseTelemetryBytes(packet)
        assertEquals("unknown_field_value", parsed["field_99"])
    }

    @Test fun bytesParserStringifiesByteArraysAsTruncatedHex() {
        val longBytes = ByteArray(40) { (it + 1).toByte() }
        val packet = MessagePack.encode(mapOf(1 to longBytes))   // 1 = interfaceId
        val parsed = parseTelemetryBytes(packet)
        val hex = parsed["interfaceId"]
        assertNull(parsed["field_1"], "should rename code 1 to interfaceId")
        assertTrue(hex != null && hex.endsWith("…"), "long ByteArray should truncate with ellipsis: $hex")
    }

    @Test fun bytesParserEmptyInputReturnsEmpty() {
        assertEquals(emptyMap(), parseTelemetryBytes(ByteArray(0)))
    }
}
