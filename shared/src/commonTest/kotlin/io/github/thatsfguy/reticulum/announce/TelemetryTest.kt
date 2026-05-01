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
}
