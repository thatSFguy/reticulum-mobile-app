package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity")
internal data class IdentityEntity(
    @PrimaryKey val id: Int = 0,
    // ---- Pre-1.1.27 plaintext columns ----
    // Kept non-null in the entity to preserve Room compile-time
    // schema parity with older DBs being migrated up. Post-1.1.27,
    // an in-place migration overwrites these with empty arrays (the
    // sentinel for "key now lives in the encrypted columns") and
    // the engine reads exclusively from the *Enc columns below.
    // Drop these columns in a future schema version once we're
    // confident no v1.1.26-or-earlier installs roll back. Audit
    // reference: 2026-05-13 HIGH-1 follow-up.
    val encPrivKey: ByteArray,
    val sigPrivKey: ByteArray,
    val ratchetPrivKey: ByteArray?,
    // ---- v1.1.27 vault-sealed columns ----
    // AES-256-GCM sealed BLOB produced by AndroidKeystoreIdentityVault.
    // Null on pre-1.1.27 rows; populated by the engine's identity-
    // load path on first run after upgrade (see ensureIdentity in
    // ReticulumEngine).
    val encPrivKeyEnc: ByteArray? = null,
    val sigPrivKeyEnc: ByteArray? = null,
    val ratchetPrivKeyEnc: ByteArray? = null,
)

/**
 * Unified destinations table. Replaces the prior split of contacts vs. nodes.
 * UI partitioning is now query-based: Nodes tab shows everything (filtered);
 * Messages tab shows favorited + messagable.
 */
@Entity(tableName = "destinations")
internal data class DestinationEntity(
    @PrimaryKey val hash: String,
    val identityHash: String,
    val publicKey: ByteArray,
    val destHash: ByteArray,
    val nameHash: ByteArray,
    val ratchetPub: ByteArray?,
    val displayName: String,
    val appName: String?,
    val appLabel: String?,
    val telemetryJson: String?,
    val lat: Double?,
    val lon: Double?,
    val appDataHex: String,
    val lastSeen: Long,
    val rssi: Int?,
    val favorite: Boolean,
    val source: String,                 // "announce" | "manual" | "qr"
    val hidden: Boolean = false,        // soft-delete flag; cleared on next announce
    val hopCount: Int = 0,              // hops on the most recent announce (lower = closer)
    val nextHop: ByteArray? = null,     // 16-byte transport_id from the most recent HEADER_2 announce; required for §2.3 originator HEADER_1→HEADER_2 conversion when sending DATA via a transit transport
    val userLabel: String? = null,      // local-only nickname; preserved across announce overwrites
)

/**
 * Cached NomadNet page bytes, keyed by destination + path. v0.1.48:
 * the Nomad screen reads this on tap so the user sees the previous
 * version instantly while we re-fetch in the background, similar to
 * how Sideband caches pages.
 */
@Entity(
    tableName = "nomad_page_cache",
    primaryKeys = ["destHash", "path"],
)
internal data class NomadPageCacheEntity(
    val destHash: String,
    val path: String,
    val source: String,      // micron source decoded UTF-8
    val fetchedAt: Long,
    val byteSize: Int,
)

@Entity(tableName = "messages")
internal data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactHash: String,
    val direction: String,
    val content: String,
    val title: String,
    val timestamp: Long,
    val state: String?,
    val attempts: Int,
    val lastAttempt: Long,
    val lastError: String?,
    val rawPacket: ByteArray?,
    val packetHash: String?,
    val rssi: Int?,
    val hopCount: Int? = null,
    val imageBytes: ByteArray? = null,
    // ---- v1.1.33 reactions + replies ----
    // See StoredMessage kdoc in commonMain/store/Models.kt for the
    // semantics. messageId is the canonical LXMF message_id hash
    // (64-char hex). replyToMessageId targets another row's
    // messageId when this row is a reply. reactionsJson is the
    // aggregated reaction map encoded by store/ReactionsJson.kt.
    val messageId: String? = null,
    val replyToMessageId: String? = null,
    val reactionsJson: String? = null,
)
