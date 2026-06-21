package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [extractAudioField] — decode of the LXMF `FIELD_AUDIO` (integer key 7)
 * value, whose wire shape is pinned in SPEC §5.9.3: `[mode_byte(int),
 * audio_bytes(bytes)]`.
 */
class ExtractAudioFieldTest {

    private fun clip(mode: Any?, bytes: ByteArray): List<Any?> = listOf(mode, bytes)

    @Test
    fun missingFieldYieldsNull() {
        assertNull(extractAudioField(emptyMap()))
        // An unrelated field present, but not key 7.
        assertNull(extractAudioField(mapOf(6 to byteArrayOf(1, 2))))
    }

    @Test
    fun decodesModeAndBytes() {
        val data = byteArrayOf(10, 20, 30)
        val r = extractAudioField(mapOf(7 to clip(AudioMode.OPUS_OGG, data)))!!
        assertEquals(AudioMode.OPUS_OGG, r.mode)
        assertTrue(data.contentEquals(r.bytes))
    }

    @Test
    fun keyMatchesAnyNumericWidth() {
        // msgpack decoders surface the integer key as Int / Long / Short.
        val data = byteArrayOf(1)
        for (key in listOf<Any>(7, 7L, 7.toShort())) {
            val r = extractAudioField(mapOf(key to clip(AudioMode.OPUS_PTT, data)))
            assertEquals(AudioMode.OPUS_PTT, r?.mode, "key $key (${key::class.simpleName}) must match")
        }
    }

    @Test
    fun modeMayArriveAsAnyNumericWidth() {
        // The mode byte is an int on the wire but a decoder may surface it
        // as Long / Short — toInt() must normalise it.
        val data = byteArrayOf(9)
        for (mode in listOf<Any>(0x13, 0x13L, 0x13.toShort())) {
            val r = extractAudioField(mapOf(7 to clip(mode, data)))
            assertEquals(AudioMode.OPUS_PTT, r?.mode, "mode $mode (${mode::class.simpleName})")
        }
    }

    @Test
    fun codec2ModeDecodes() {
        val r = extractAudioField(mapOf(7 to clip(AudioMode.CODEC2_3200, byteArrayOf(1, 2))))!!
        assertEquals(AudioMode.CODEC2_3200, r.mode)
        assertTrue(AudioMode.isCodec2(r.mode))
        assertTrue(!AudioMode.isOpus(r.mode))
    }

    @Test
    fun oversizeClipIsDropped() {
        val tooBig = ByteArray(INBOUND_ATTACHMENT_MAX_BYTES + 1)
        assertNull(extractAudioField(mapOf(7 to clip(AudioMode.OPUS_OGG, tooBig))))
    }

    @Test
    fun malformedValuesYieldNull() {
        // Not a list.
        assertNull(extractAudioField(mapOf(7 to byteArrayOf(1, 2))))
        // Missing bytes slot.
        assertNull(extractAudioField(mapOf(7 to listOf(AudioMode.OPUS_OGG))))
        // Bytes slot wrong type.
        assertNull(extractAudioField(mapOf(7 to listOf(AudioMode.OPUS_OGG, "not-bytes"))))
        // Mode slot wrong type.
        assertNull(extractAudioField(mapOf(7 to listOf("not-a-mode", byteArrayOf(1)))))
    }

    @Test
    fun familyHelpersClassifyTheLadder() {
        // Codec2 family 0x01..0x09, Opus family 0x10..0x19.
        for (m in 0x01..0x09) assertTrue(AudioMode.isCodec2(m), "0x${m.toString(16)} is Codec2")
        for (m in 0x10..0x19) assertTrue(AudioMode.isOpus(m), "0x${m.toString(16)} is Opus")
        // Custom is neither.
        assertTrue(!AudioMode.isOpus(AudioMode.CUSTOM) && !AudioMode.isCodec2(AudioMode.CUSTOM))
    }
}
