package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [extractFileAttachments] — decode of the LXMF `FIELD_FILE_ATTACHMENTS`
 * (integer key 5) value, whose wire shape is pinned in SPEC §5.9.7:
 * a list of `[filename, file_bytes]` pairs.
 */
class ExtractFileAttachmentsTest {

    private fun attachment(name: Any?, bytes: ByteArray): List<Any?> = listOf(name, bytes)

    @Test
    fun missingFieldYieldsEmptyList() {
        assertTrue(extractFileAttachments(emptyMap()).isEmpty())
        // An unrelated field present, but not key 5.
        assertTrue(extractFileAttachments(mapOf(6 to byteArrayOf(1, 2))).isEmpty())
    }

    @Test
    fun decodesNameAndBytes() {
        val data = byteArrayOf(10, 20, 30)
        val result = extractFileAttachments(mapOf(5 to listOf(attachment("notes.txt", data))))
        assertEquals(1, result.size)
        assertEquals("notes.txt", result[0].name)
        assertTrue(data.contentEquals(result[0].bytes))
    }

    @Test
    fun keyMatchesAnyNumericWidth() {
        // msgpack decoders surface the integer key as Int / Long / Short.
        val data = byteArrayOf(1)
        for (key in listOf<Any>(5, 5L, 5.toShort())) {
            val r = extractFileAttachments(mapOf(key to listOf(attachment("f", data))))
            assertEquals(1, r.size, "key $key (${key::class.simpleName}) must match")
        }
    }

    @Test
    fun filenameMayArriveAsBinBytes() {
        // Some msgpack encoders emit the filename as `bin`, not `str`.
        val r = extractFileAttachments(
            mapOf(5 to listOf(attachment("photo.png".encodeToByteArray(), byteArrayOf(9)))),
        )
        assertEquals("photo.png", r.single().name)
    }

    @Test
    fun multipleAttachmentsAllDecode() {
        val r = extractFileAttachments(
            mapOf(
                5 to listOf(
                    attachment("a.txt", byteArrayOf(1)),
                    attachment("b.txt", byteArrayOf(2, 2)),
                ),
            ),
        )
        assertEquals(listOf("a.txt", "b.txt"), r.map { it.name })
    }

    @Test
    fun filenamePathTraversalIsStripped() {
        // The filename is sender-controlled — a path must never survive.
        val data = byteArrayOf(1)
        assertEquals(
            "passwd",
            extractFileAttachments(mapOf(5 to listOf(attachment("../../etc/passwd", data))))
                .single().name,
        )
        assertEquals(
            "evil.sh",
            extractFileAttachments(mapOf(5 to listOf(attachment("..\\..\\evil.sh", data))))
                .single().name,
        )
    }

    @Test
    fun emptyOrDotOnlyFilenameFallsBack() {
        val data = byteArrayOf(1)
        assertEquals(
            "attachment",
            extractFileAttachments(mapOf(5 to listOf(attachment("..", data)))).single().name,
        )
        assertEquals(
            "attachment",
            extractFileAttachments(mapOf(5 to listOf(attachment("", data)))).single().name,
        )
    }

    @Test
    fun oversizeAttachmentIsDropped() {
        val tooBig = ByteArray(INBOUND_ATTACHMENT_MAX_BYTES + 1)
        val ok = byteArrayOf(1, 2)
        val r = extractFileAttachments(
            mapOf(5 to listOf(attachment("big.bin", tooBig), attachment("ok.txt", ok))),
        )
        // Oversize one dropped, the in-cap one kept.
        assertEquals(listOf("ok.txt"), r.map { it.name })
    }

    @Test
    fun malformedEntriesAreSkipped() {
        val r = extractFileAttachments(
            mapOf(
                5 to listOf(
                    "not-a-pair",                       // not a list
                    listOf("only-name"),                // no bytes
                    listOf("name", "bytes-as-string"),  // bytes wrong type
                    attachment("good.txt", byteArrayOf(7)),
                ),
            ),
        )
        assertEquals(listOf("good.txt"), r.map { it.name })
    }
}
