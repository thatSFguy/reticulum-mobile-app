package io.github.thatsfguy.reticulum.announce

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        // broadcast (captured 2026-05-04). msgpack:
        //   `[ 0, { 0: "BackboneInterface",
        //            2: "10.0.0.1",
        //            3: 49000.0,
        //            5: 500.0 } ]`
        // Hand-encoded so the test doesn't depend on the codec's
        // map-write byte ordering.
        val packet = byteArrayOf(
            0x92.toByte(),                                // fixarray of 2
            0x00,                                          // 0
            0x84.toByte(),                                // fixmap of 4
            0x00, 0xb1.toByte(),                          // key 0 → fixstr len=17
                'B'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte(),
                'k'.code.toByte(), 'b'.code.toByte(), 'o'.code.toByte(),
                'n'.code.toByte(), 'e'.code.toByte(), 'I'.code.toByte(),
                'n'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(),
                'r'.code.toByte(), 'f'.code.toByte(), 'a'.code.toByte(),
                'c'.code.toByte(), 'e'.code.toByte(),
            0x02, 0xa8.toByte(),                          // key 2 → fixstr len=8
                '1'.code.toByte(), '0'.code.toByte(), '.'.code.toByte(),
                '0'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(),
                '.'.code.toByte(), '1'.code.toByte(),
            0x03, 0xcb.toByte(),                          // key 3 → float64
                0x40, 0xe7.toByte(), 0xea.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,  // 49000.0
            0x05, 0xcb.toByte(),                          // key 5 → float64
                0x40, 0x7f.toByte(), 0x40, 0x00, 0x00, 0x00, 0x00, 0x00,           // 500.0
        )
        val parsed = parseTelemetryBytes(packet)
        assertEquals("BackboneInterface", parsed["interfaceType"])
        assertEquals("10.0.0.1",          parsed["address"])
        assertEquals("49000",             parsed["bandwidthBps"])
        assertEquals("500",               parsed["mtu"])
    }

    @Test fun bytesParserHandlesBareMapWithoutEnvelope() {
        // `{ 0: "RNodeInterface" }` — no [hop_count, …] wrapper.
        val packet = byteArrayOf(
            0x81.toByte(),
            0x00, 0xae.toByte(),
                'R'.code.toByte(), 'N'.code.toByte(), 'o'.code.toByte(),
                'd'.code.toByte(), 'e'.code.toByte(), 'I'.code.toByte(),
                'n'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(),
                'r'.code.toByte(), 'f'.code.toByte(), 'a'.code.toByte(),
                'c'.code.toByte(), 'e'.code.toByte(),
        )
        val parsed = parseTelemetryBytes(packet)
        assertEquals("RNodeInterface", parsed["interfaceType"])
    }

    @Test fun bytesParserUnknownIntKeysFallThroughAsFieldN() {
        // `{ 99: "unknown_field_value" }` — code 99 not in TELEMETRY_CODE_NAMES.
        val packet = byteArrayOf(
            0x81.toByte(),
            0x63,                                          // 99
            0xb3.toByte(),                                 // fixstr len=19
                'u'.code.toByte(), 'n'.code.toByte(), 'k'.code.toByte(),
                'n'.code.toByte(), 'o'.code.toByte(), 'w'.code.toByte(),
                'n'.code.toByte(), '_'.code.toByte(), 'f'.code.toByte(),
                'i'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(),
                'd'.code.toByte(), '_'.code.toByte(), 'v'.code.toByte(),
                'a'.code.toByte(), 'l'.code.toByte(), 'u'.code.toByte(),
                'e'.code.toByte(),
        )
        val parsed = parseTelemetryBytes(packet)
        assertEquals("unknown_field_value", parsed["field_99"])
    }

    @Test fun bytesParserEmptyInputReturnsEmpty() {
        assertEquals(emptyMap(), parseTelemetryBytes(ByteArray(0)))
    }
}
