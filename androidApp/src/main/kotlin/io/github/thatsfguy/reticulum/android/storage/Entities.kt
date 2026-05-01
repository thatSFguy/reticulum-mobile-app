package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity")
internal data class IdentityEntity(
    @PrimaryKey val id: Int = 0,        // singleton row
    val encPrivKey: ByteArray,
    val sigPrivKey: ByteArray,
    val ratchetPrivKey: ByteArray?,
)

@Entity(tableName = "contacts")
internal data class ContactEntity(
    @PrimaryKey val hash: String,        // destHash hex
    val identityHash: String,
    val publicKey: ByteArray,
    val destHash: ByteArray,
    val nameHash: ByteArray,
    val ratchetPub: ByteArray?,
    val displayName: String,
    val lastSeen: Long,
    val rssi: Int?,
)

@Entity(tableName = "messages")
internal data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactHash: String,
    val direction: String,               // "incoming" | "outgoing"
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

@Entity(tableName = "nodes")
internal data class NodeEntity(
    @PrimaryKey val hash: String,        // destHash hex
    val identityHash: String,
    val nameHash: ByteArray,
    val appName: String?,
    val appLabel: String?,
    val displayName: String,
    val telemetryJson: String?,
    val lat: Double?,
    val lon: Double?,
    val appDataHex: String,
    val lastSeen: Long,
    val rssi: Int?,
)
