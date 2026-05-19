package io.github.thatsfguy.reticulum.store

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * iOS [AttachmentStore] — one flat directory of token-named files
 * under [baseDir] (the caller passes an Application Support
 * subdirectory: app-private, and not purged the way Caches can be).
 */
@OptIn(ExperimentalForeignApi::class)
actual class AttachmentStore actual constructor(private val baseDir: String) {
    private val fm = NSFileManager.defaultManager

    init {
        fm.createDirectoryAtPath(
            baseDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    private fun pathOf(token: String): String = "$baseDir/$token"

    actual suspend fun put(bytes: ByteArray): String {
        val token = newToken()
        bytes.toNSData().writeToFile(pathOf(token), atomically = true)
        return token
    }

    actual suspend fun load(token: String): ByteArray? {
        if (!token.isSafeToken()) return null
        val data = NSData.dataWithContentsOfFile(pathOf(token)) ?: return null
        return data.toByteArray()
    }

    actual fun pathFor(token: String): String? {
        if (!token.isSafeToken()) return null
        val p = pathOf(token)
        return if (fm.fileExistsAtPath(p)) p else null
    }

    actual suspend fun delete(token: String) {
        if (token.isSafeToken()) fm.removeItemAtPath(pathOf(token), error = null)
    }

    actual suspend fun sweep(liveTokens: Set<String>) {
        @Suppress("UNCHECKED_CAST")
        val names = fm.contentsOfDirectoryAtPath(baseDir, error = null) as? List<String>
            ?: return
        for (name in names) {
            if (name !in liveTokens) fm.removeItemAtPath(pathOf(name), error = null)
        }
    }
}

/**
 * Build the iOS [AttachmentStore] rooted at
 * `<Application Support>/attachments`. Application Support (not
 * Caches, which the OS can purge) keeps attachment payloads durable;
 * the directory is marked excluded-from-backup so multi-MB images
 * don't bloat the user's iCloud / iTunes backup. See
 * docs/ATTACHMENT-STORE.md §3.1.
 */
@OptIn(ExperimentalForeignApi::class)
fun createIosAttachmentStore(): AttachmentStore {
    val fm = NSFileManager.defaultManager
    val support = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory, NSUserDomainMask, true,
    ).firstOrNull() as? String
        ?: error("attachment store: cannot locate Application Support directory")
    val baseDir = "$support/attachments"
    fm.createDirectoryAtPath(
        baseDir, withIntermediateDirectories = true, attributes = null, error = null,
    )
    // A Kotlin Boolean is bridged to NSNumber automatically when
    // passed where Objective-C expects an `id` — no explicit
    // NSNumber wrapper needed (and `NSNumber.numberWithBool` is not
    // exposed by the Kotlin/Native Foundation bindings).
    NSURL.fileURLWithPath(baseDir, isDirectory = true)
        .setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    return AttachmentStore(baseDir)
}

/** A token is a bare 32-char hex string. */
private fun newToken(): String = NSUUID().UUIDString().replace("-", "").lowercase()

/**
 * Reject anything that isn't a [newToken]-shaped 32-hex string before
 * it reaches the filesystem — a corrupt DB value must not be able to
 * escape [AttachmentStore]'s base directory via `..` or a separator.
 */
private fun String.isSafeToken(): Boolean =
    length == 32 && all { it in '0'..'9' || it in 'a'..'f' }

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    return ByteArray(len).also { out ->
        out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
