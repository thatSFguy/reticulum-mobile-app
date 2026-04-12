package io.github.thatsfguy.reticulum.store

/**
 * Data models for persistence. Port of reference/js-reference/store.js.
 * These are platform-independent; the actual storage backend (Room on
 * Android, SQLDelight on iOS) implements the Repository interfaces.
 */

data class StoredIdentity(
    val encPrivKey: ByteArray,
    val sigPrivKey: ByteArray,
    val ratchetPrivKey: ByteArray?,
)

data class StoredContact(
    val hash: String,              // destination hash hex (primary key)
    val identityHash: String,
    val publicKey: ByteArray,      // 64 bytes
    val destHash: ByteArray,       // 16 bytes
    val nameHash: ByteArray,       // 10 bytes
    val ratchetPub: ByteArray?,    // 32 bytes or null
    val displayName: String,
    val lastSeen: Long,
    val rssi: Int?,
)

data class StoredMessage(
    val id: Long = 0,              // auto-increment
    val contactHash: String,
    val direction: String,         // "incoming" or "outgoing"
    val content: String,
    val title: String = "",
    val timestamp: Long,
    val state: String? = null,     // pending, sending, sent, delivered, failed
    val attempts: Int = 0,
    val lastAttempt: Long = 0,
    val lastError: String? = null,
    val rawPacket: ByteArray? = null,  // for retry queue
    val packetHash: String? = null,    // for delivery receipt matching
    val rssi: Int? = null,
)

data class StoredNode(
    val hash: String,              // destination hash hex (primary key)
    val identityHash: String,
    val nameHash: ByteArray,
    val appName: String?,          // from known-destinations lookup
    val appLabel: String?,
    val displayName: String,
    val telemetry: Map<String, String>?, // parsed key=value pairs
    val lat: Double?,
    val lon: Double?,
    val appDataHex: String,
    val lastSeen: Long,
    val rssi: Int?,
)

/** Repository interfaces — implemented per-platform. */

interface IdentityRepository {
    suspend fun save(identity: StoredIdentity)
    suspend fun load(): StoredIdentity?
}

interface ContactRepository {
    suspend fun save(contact: StoredContact)
    suspend fun get(hash: String): StoredContact?
    suspend fun getAll(): List<StoredContact>
    suspend fun delete(hash: String)
}

interface MessageRepository {
    suspend fun save(message: StoredMessage): Long  // returns auto-id
    suspend fun getById(id: Long): StoredMessage?
    suspend fun getForContact(contactHash: String): List<StoredMessage>
    suspend fun getAll(): List<StoredMessage>
    suspend fun update(id: Long, updates: Map<String, Any?>)
    suspend fun deleteForContact(contactHash: String)
}

interface NodeRepository {
    suspend fun save(node: StoredNode)
    suspend fun getAll(): List<StoredNode>
    suspend fun delete(hash: String)
    suspend fun deleteAll()
}
