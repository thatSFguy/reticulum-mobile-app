package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [audioField] — outbound LXMF `FIELD_AUDIO` (key 7) builder. Wire shape
 * is the flat `[mode_byte(int), audio_bytes]` (SPEC §5.9.3). Verified by
 * round-tripping through [extractAudioField].
 */
class OutboundAudioFieldTest {

    @Test
    fun emitsFlatModeAndBytesUnderKey7() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val field = audioField(LxmfAudio(AudioMode.OPUS_OGG, bytes))
        // Single entry, integer key 7.
        assertEquals(setOf<Any?>(7), field.keys)
        val value = field[7] as List<*>
        // Flat [mode, bytes] — NOT a list of pairs (that's key 5).
        assertEquals(2, value.size)
        assertEquals(AudioMode.OPUS_OGG, value[0])
        assertTrue((value[1] as ByteArray).contentEquals(bytes))
    }

    @Test
    fun roundTripsThroughExtract() {
        val bytes = ByteArray(64) { it.toByte() }
        val decoded = extractAudioField(audioField(LxmfAudio(AudioMode.CODEC2_3200, bytes)))!!
        assertEquals(AudioMode.CODEC2_3200, decoded.mode)
        assertTrue(decoded.bytes.contentEquals(bytes))
    }

    @Test
    fun notConfusedWithFileOrImageShape() {
        // The flat audio shape must not parse as a file attachment (key 5,
        // list-of-pairs) and vice-versa — they share neither key nor shape.
        val field = audioField(LxmfAudio(AudioMode.OPUS_PTT, byteArrayOf(9)))
        assertTrue(extractFileAttachments(field).isEmpty())
    }
}
