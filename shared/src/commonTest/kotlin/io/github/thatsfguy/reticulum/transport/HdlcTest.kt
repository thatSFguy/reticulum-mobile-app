package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HdlcTest {

    @Test fun escapesFlagAndEsc() {
        val data = byteArrayOf(0x7E, 0x7D, 0x00)
        val frame = buildHdlcFrame(data)
        // FLAG 7D 5E 7D 5D 00 FLAG
        val expected = byteArrayOf(0x7E, 0x7D, 0x5E, 0x7D, 0x5D, 0x00, 0x7E)
        assertContentEquals(expected, frame)
    }

    @Test fun parserRoundTrip() {
        val out = mutableListOf<ByteArray>()
        val parser = HdlcParser { out += it }
        val data = byteArrayOf(0x7E, 0x7D, 0x42, 0x00, 0xFF.toByte())
        parser.feed(buildHdlcFrame(data))
        assertEquals(1, out.size)
        assertContentEquals(data, out[0])
    }

    @Test fun parserHandlesSplitChunks() {
        val out = mutableListOf<ByteArray>()
        val parser = HdlcParser { out += it }
        val frame = buildHdlcFrame(byteArrayOf(1, 2, 0x7E, 3, 0x7D))
        for (b in frame) parser.feed(byteArrayOf(b))
        assertEquals(1, out.size)
        assertContentEquals(byteArrayOf(1, 2, 0x7E, 3, 0x7D), out[0])
    }

    @Test fun parserDropsEmptyFramesBetweenRealOnes() {
        val out = mutableListOf<ByteArray>()
        val parser = HdlcParser { out += it }
        // Two FLAGs back-to-back are a keepalive; should not emit an empty frame.
        parser.feed(byteArrayOf(0x7E, 0x7E))
        parser.feed(buildHdlcFrame(byteArrayOf(0x42)))
        assertEquals(1, out.size)
        assertContentEquals(byteArrayOf(0x42), out[0])
    }

    @Test fun parserDiscardsRunawayFrameAndResyncs() {
        // DoS guard: a peer opens a frame (FLAG) then streams >64 KB
        // without ever sending the closing FLAG. The parser MUST cap the
        // in-flight buffer (no unbounded growth → OOM), MUST NOT emit the
        // runaway, and MUST resync on the next FLAG so a following
        // well-formed frame still parses. Pins maxFrameBytes in Hdlc.kt.
        val out = mutableListOf<ByteArray>()
        val parser = HdlcParser { out += it }
        parser.feed(byteArrayOf(0x7E))                  // open frame
        parser.feed(ByteArray(70 * 1024) { 0x41 })      // 70 KB, never closed
        assertEquals(0, out.size)                       // runaway not emitted
        parser.feed(buildHdlcFrame(byteArrayOf(0x42)))  // valid frame after resync
        assertEquals(1, out.size)
        assertContentEquals(byteArrayOf(0x42), out[0])
    }
}
