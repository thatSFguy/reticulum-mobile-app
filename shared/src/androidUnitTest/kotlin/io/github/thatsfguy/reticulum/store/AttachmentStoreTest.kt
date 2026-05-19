package io.github.thatsfguy.reticulum.store

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Round-trip + safety pins for the Android [AttachmentStore] actual —
 * the off-row file store that replaces the CursorWindow-capped
 * `imageBytes` / `attachmentBytes` BLOB columns (docs/ATTACHMENT-STORE.md).
 */
class AttachmentStoreTest {

    private val dir = File(
        System.getProperty("java.io.tmpdir"),
        "attach-store-test-${System.nanoTime()}",
    )
    private val store = AttachmentStore(dir.path)

    @AfterTest fun cleanup() {
        dir.deleteRecursively()
    }

    @Test fun `put then load round-trips the bytes`() = runBlocking {
        val payload = ByteArray(50_000) { (it * 31).toByte() }
        val token = store.put(payload)
        val back = store.load(token)
        assertNotNull(back)
        assertTrue(back.contentEquals(payload))
    }

    @Test fun `payloads far past the old in-row caps are stored`() = runBlocking {
        // The whole point of the store: a 4 MB attachment that the
        // 512 KB INBOUND_IMAGE_MAX_BYTES / 2 MB CursorWindow could
        // never hold as a row BLOB round-trips fine as a file.
        val payload = ByteArray(4 * 1024 * 1024) { it.toByte() }
        val token = store.put(payload)
        assertEquals(payload.size, store.load(token)?.size)
    }

    @Test fun `each put gets a distinct 32-hex token`() = runBlocking {
        val a = store.put(byteArrayOf(1))
        val b = store.put(byteArrayOf(2))
        assertTrue(a != b)
        for (t in listOf(a, b)) {
            assertEquals(32, t.length)
            assertTrue(t.all { it in '0'..'9' || it in 'a'..'f' }, "token must be 32-hex: $t")
        }
    }

    @Test fun `pathFor returns a readable path for a stored token`() = runBlocking {
        val payload = ByteArray(2048) { it.toByte() }
        val token = store.put(payload)
        val path = store.pathFor(token)
        assertNotNull(path)
        assertTrue(File(path).readBytes().contentEquals(payload))
    }

    @Test fun `load and pathFor return null for an unknown token`() = runBlocking {
        val unknown = "0".repeat(32)
        assertNull(store.load(unknown))
        assertNull(store.pathFor(unknown))
    }

    @Test fun `delete removes the file`() = runBlocking {
        val token = store.put(byteArrayOf(9, 8, 7))
        store.delete(token)
        assertNull(store.load(token))
        assertNull(store.pathFor(token))
    }

    @Test fun `sweep deletes orphans and keeps live tokens`() = runBlocking {
        val keep = store.put(byteArrayOf(1, 1, 1))
        val orphan = store.put(byteArrayOf(2, 2, 2))
        store.sweep(liveTokens = setOf(keep))
        assertNotNull(store.load(keep))
        assertNull(store.load(orphan))
    }

    @Test fun `a path-traversal token never escapes the base directory`() = runBlocking {
        // A corrupt DB value must not reach the filesystem. The token
        // isn't 32-hex, so load / pathFor / delete all refuse it.
        val evil = "../../../../etc/passwd"
        assertNull(store.load(evil))
        assertNull(store.pathFor(evil))
        store.delete(evil)  // must not throw, must not touch anything
        assertFalse(File(dir.parentFile, "passwd").exists())
    }
}
