package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity")
internal data class IdentityEntity(
    @PrimaryKey val id: Int = 0,
    val encPrivKey: ByteArray,
    val sigPrivKey: ByteArray,
    val ratchetPrivKey: ByteArray?,
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
)
