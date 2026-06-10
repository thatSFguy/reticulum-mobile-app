package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [NusDemux] — text/frame demux for the agnostic-LoRa-Net NUS stream.
 * Pinned to the reference implementation's `_read_loop` semantics
 * (`AgnosticLoraInterface.py`), per playbook §5: assertions are against
 * the reference behavior, not our own round-trip.
 */
class NusDemuxTest {

    private class Sink {
        val frames = ArrayList<ByteArray>()
        val lines = ArrayList<String>()
        val demux = NusDemux(onFrame = frames::add, onTextLine = lines::add)
    }

    @Test
    fun plainTextLinesEmitOnLf() {
        val s = Sink()
        s.demux.feed("loc AABB 9828F51B\r\nregistered ok\n".encodeToByteArray())
        assertEquals(listOf("loc AABB 9828F51B", "registered ok"), s.lines)
        assertTrue(s.frames.isEmpty())
    }

    @Test
    fun frameBetweenFlagsDecodes() {
        val s = Sink()
        val body = byteArrayOf(0x01, 0x04, 0x1B, 0xF5.toByte(), 0x28, 0x98.toByte(), 0x42)
        s.demux.feed(buildHdlcFrame(body))
        assertEquals(1, s.frames.size)
        assertContentEquals(body, s.frames[0])
    }

    @Test
    fun escapedBytesUnescapeInsideFrame() {
        val s = Sink()
        // 0x7E and 0x7D in the body must round-trip through 0x7D escapes.
        val body = byteArrayOf(0x01, 0x04, 0x7E, 0x7D, 0x00, 0x00, 0x55)
        s.demux.feed(buildHdlcFrame(body))
        assertContentEquals(body, s.frames.single())
    }

    @Test
    fun textThenFrameThenTextInterleave() {
        val s = Sink()
        val body = byteArrayOf(0x01, 0x04, 1, 2, 3, 4, 9)
        val stream = "[dir] 1 binding(s):\n".encodeToByteArray() +
            buildHdlcFrame(body) +
            "  AABB -> D97EEC3A  ttl=595s\n".encodeToByteArray()
        s.demux.feed(stream)
        assertEquals(listOf("[dir] 1 binding(s):", "  AABB -> D97EEC3A  ttl=595s"), s.lines)
        assertContentEquals(body, s.frames.single())
    }

    @Test
    fun frameBoundaryClearsPartialTextLine() {
        // Reference: "a frame boundary is never part of a line" — text
        // accumulated before a FLAG is discarded, not glued to the text
        // that follows the frame.
        val s = Sink()
        val stream = "partial".encodeToByteArray() +
            buildHdlcFrame(byteArrayOf(0x01, 0x00, 7)) +
            "whole line\n".encodeToByteArray()
        s.demux.feed(stream)
        assertEquals(listOf("whole line"), s.lines)
    }

    @Test
    fun chunkBoundariesDoNotMatter() {
        val s = Sink()
        val body = ByteArray(60) { it.toByte() }
        val wire = "loc X 11223344\n".encodeToByteArray() + buildHdlcFrame(body)
        // Feed one byte at a time — BLE notifications split arbitrarily.
        for (b in wire) s.demux.feed(byteArrayOf(b))
        assertEquals(listOf("loc X 11223344"), s.lines)
        assertContentEquals(body, s.frames.single())
    }

    @Test
    fun emptyFrameIsKeepaliveNotAFrame() {
        val s = Sink()
        s.demux.feed(byteArrayOf(0x7E, 0x7E))
        assertTrue(s.frames.isEmpty())
        // and the demux is back out-of-frame: text still parses
        s.demux.feed("hb\n".encodeToByteArray())
        assertEquals(listOf("hb"), s.lines)
    }

    @Test
    fun blankLinesAreDropped() {
        val s = Sink()
        s.demux.feed("\n\r\n  \nreal\n".encodeToByteArray())
        assertEquals(listOf("real"), s.lines)
    }
}
