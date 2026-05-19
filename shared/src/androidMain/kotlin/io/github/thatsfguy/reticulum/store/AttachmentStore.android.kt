package io.github.thatsfguy.reticulum.store

import java.io.File
import java.util.UUID

/**
 * Android [AttachmentStore] — one flat directory of token-named files
 * under [baseDir] (the caller passes `context.filesDir/attachments`,
 * app-private internal storage).
 */
actual class AttachmentStore actual constructor(baseDir: String) {
    private val dir = File(baseDir).apply { mkdirs() }

    actual suspend fun put(bytes: ByteArray): String {
        val token = newToken()
        File(dir, token).writeBytes(bytes)
        return token
    }

    actual suspend fun load(token: String): ByteArray? {
        if (!token.isSafeToken()) return null
        val f = File(dir, token)
        return if (f.isFile) f.readBytes() else null
    }

    actual fun pathFor(token: String): String? {
        if (!token.isSafeToken()) return null
        val f = File(dir, token)
        return if (f.isFile) f.absolutePath else null
    }

    actual suspend fun delete(token: String) {
        if (token.isSafeToken()) File(dir, token).delete()
    }

    actual suspend fun sweep(liveTokens: Set<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.name !in liveTokens) f.delete()
        }
    }
}

/** A token is a bare 32-char hex string. */
private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

/**
 * Reject anything that isn't a [newToken]-shaped 32-hex string before
 * it reaches the filesystem — a corrupt DB value must not be able to
 * escape [AttachmentStore]'s base directory via `..` or a separator.
 */
private fun String.isSafeToken(): Boolean =
    length == 32 && all { it in '0'..'9' || it in 'a'..'f' }
