package io.github.thatsfguy.reticulum.store

/**
 * On-device store for LXMF attachment payloads — received (and sent)
 * `FIELD_IMAGE` / `FIELD_FILE_ATTACHMENTS` bytes.
 *
 * Bytes live as app-private files under a base directory, **not** as
 * BLOB columns on the `messages` row. A multi-MB blob on a row blows
 * past Android's 2 MB `CursorWindow` per-row limit and crashes the
 * whole conversation query — which is why the legacy in-row
 * `imageBytes` / `attachmentBytes` columns force tiny (256–512 KB)
 * receive caps. With the bytes off-row the row stays small and the
 * size ceiling is governed only by transport + decode. The DB row
 * keeps only the opaque [String] token returned by [put].
 *
 * See `docs/ATTACHMENT-STORE.md`. Construct one per app process with
 * an absolute [baseDir]; the directory is created on first use.
 *
 * `expect`/`actual` because the byte path is platform file I/O —
 * `java.io.File` on Android, `NSFileManager` on iOS.
 */
expect class AttachmentStore(baseDir: String) {
    /** Persist [bytes] to a fresh file; returns its opaque token. */
    suspend fun put(bytes: ByteArray): String

    /** Bytes for [token], or null when the file is missing/unreadable. */
    suspend fun load(token: String): ByteArray?

    /**
     * Absolute filesystem path for [token], or null when the file is
     * missing. Lets a platform image decoder read the file directly
     * (downsampled) instead of pulling the whole payload through the
     * KMP↔Swift / KMP↔JVM bridge.
     */
    fun pathFor(token: String): String?

    /** Delete the file for [token]; no-op when it is already gone. */
    suspend fun delete(token: String)

    /**
     * Orphan GC — delete every stored file whose token is **not** in
     * [liveTokens]. Run at startup against the set of tokens still
     * referenced by a message row, so a crash between row-delete and
     * file-delete can't leak storage forever.
     */
    suspend fun sweep(liveTokens: Set<String>)
}
