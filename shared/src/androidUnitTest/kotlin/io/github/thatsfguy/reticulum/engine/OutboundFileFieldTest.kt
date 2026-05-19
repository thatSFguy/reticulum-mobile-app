package io.github.thatsfguy.reticulum.engine

import io.github.thatsfguy.reticulum.codec.MessagePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire-format pin for [fileAttachmentField] — the OUTBOUND builder for
 * LXMF `FIELD_FILE_ATTACHMENTS` (key 5). Per SPEC §5.9.7 the value is a
 * **list of `[filename, file_bytes]` pairs**, NOT a flat pair (that flat
 * shape is `FIELD_IMAGE` key 6). Getting this wrong silently breaks
 * decode on every spec-compliant peer (Sideband / Columba), so this
 * round-trips what we send through [extractFileAttachments] — the
 * already-tested receive decoder — and through the real msgpack codec.
 */
class OutboundFileFieldTest {

    @Test fun `fileAttachmentField round-trips through the receive decoder`() {
        val bytes = ByteArray(2048) { (it * 13).toByte() }
        val field = fileAttachmentField(LxmfFileAttachment("report.pdf", bytes))

        // Through the wire codec, exactly as packMessage would encode it.
        val wire = MessagePack.encode(field)
        @Suppress("UNCHECKED_CAST")
        val decoded = MessagePack.decode(wire) as Map<Any?, Any?>

        val files = extractFileAttachments(decoded)
        assertEquals(1, files.size, "exactly one attachment expected")
        assertEquals("report.pdf", files[0].name)
        assertTrue(bytes.contentEquals(files[0].bytes), "file bytes drifted on the wire")
    }

    @Test fun `image field and file field coexist in one fields map`() {
        val img = ByteArray(512) { it.toByte() }
        val file = ByteArray(700) { (it + 1).toByte() }
        // The exact merge tryDeliverOverLink performs: image (key 6,
        // flat [ext, bytes]) + file (key 5, list-of-pairs).
        val merged: Map<Any?, Any?> =
            mapOf<Any?, Any?>(6 to listOf("jpg", img)) + fileAttachmentField(
                LxmfFileAttachment("a.bin", file),
            )

        @Suppress("UNCHECKED_CAST")
        val decoded = MessagePack.decode(MessagePack.encode(merged)) as Map<Any?, Any?>

        val (extractedImg, _) = extractImageField(decoded)
        assertNotNull(extractedImg, "image field must survive alongside a file field")
        assertTrue(img.contentEquals(extractedImg))

        val files = extractFileAttachments(decoded)
        assertEquals(1, files.size)
        assertTrue(file.contentEquals(files[0].bytes))
    }

    @Test fun `a path-laden filename is sanitised before it reaches the wire`() {
        // sendMessage runs the sender-supplied name through
        // sanitizeAttachmentName before constructing the
        // LxmfFileAttachment — mirror that here and confirm the
        // on-wire name is the bare base name.
        val field = fileAttachmentField(
            LxmfFileAttachment(sanitizeAttachmentName("../../etc/passwd"), byteArrayOf(1)),
        )
        @Suppress("UNCHECKED_CAST")
        val decoded = MessagePack.decode(MessagePack.encode(field)) as Map<Any?, Any?>
        assertEquals("passwd", extractFileAttachments(decoded).single().name)
    }
}
