package io.github.thatsfguy.reticulum.store

/**
 * Data models for persistence. Platform-independent; storage backend
 * (Room on Android, SQLDelight on iOS) implements the Repository interfaces.
 */

data class StoredIdentity(
    val encPrivKey: ByteArray,
    val sigPrivKey: ByteArray,
    val ratchetPrivKey: ByteArray?,
)

/**
 * Every announced (or manually-added) Reticulum destination lives here.
 * lxmf.delivery destinations can be messaged once their public key is known;
 * other destinations (telemetry beacons, transport broadcasts, etc.) are
 * just observed.
 */
data class StoredDestination(
    val hash: String,                     // destination hash hex — primary key
    val identityHash: String,             // hex; empty until a public key is known
    val publicKey: ByteArray,             // 64 bytes (X25519 || Ed25519); empty for manual stubs
    val destHash: ByteArray,              // 16 bytes — same as hash, decoded
    val nameHash: ByteArray,              // 10 bytes; empty for manual stubs until announce
    val ratchetPub: ByteArray?,           // 32 bytes; null if announce had no ratchet
    val displayName: String,
    val appName: String?,                 // looked up from name_hash (e.g. "lxmf.delivery")
    val appLabel: String?,                // human label for appName
    val telemetry: Map<String, String>?,  // parsed key=value pairs (RLR)
    val lat: Double?,
    val lon: Double?,
    val appDataHex: String,
    val lastSeen: Long,
    val rssi: Int?,
    val favorite: Boolean,                // user-starred → promoted to Messages tab
    val source: String,                   // "announce" | "manual" | "qr"
    val hidden: Boolean = false,          // soft-delete: kept in DB but filtered from lists; auto-cleared on re-announce
    val hopCount: Int = 0,                // hops byte from the most recent announce (0 = directly attached, higher = further)
) {
    /** A destination is messagable if we have its public key and it's an LXMF delivery dest. */
    val isMessagable: Boolean
        get() = publicKey.size == 64 && appName == "lxmf.delivery"
}

data class StoredMessage(
    val id: Long = 0,
    val contactHash: String,           // destHash hex of the conversation partner
    val direction: String,             // "incoming" or "outgoing"
    val content: String,
    val title: String = "",
    val timestamp: Long,
    val state: String? = null,
    val attempts: Int = 0,
    val lastAttempt: Long = 0,
    val lastError: String? = null,
    val rawPacket: ByteArray? = null,
    val packetHash: String? = null,
    val rssi: Int? = null,
)

interface IdentityRepository {
    suspend fun save(identity: StoredIdentity)
    suspend fun load(): StoredIdentity?
}

/**
 * Unified repository for every observed/manual destination. Replaces the
 * old separate Contact/Node repositories — partition is by query, not by
 * table. Implementation must MERGE on conflict (preserve favorite flag and
 * source field on inbound announces) rather than wholesale replace.
 */
interface DestinationRepository {
    /** Upsert an announce-derived row, merging with any existing record. */
    suspend fun upsertFromAnnounce(record: StoredDestination)

    /** Insert a manual stub if no row exists; no-op if one already does. */
    suspend fun upsertManualStub(record: StoredDestination)

    suspend fun get(hash: String): StoredDestination?
    suspend fun getAll(): List<StoredDestination>
    suspend fun setFavorite(hash: String, favorite: Boolean)
    suspend fun delete(hash: String)
    suspend fun deleteAll()
}

interface MessageRepository {
    suspend fun save(message: StoredMessage): Long
    suspend fun getById(id: Long): StoredMessage?
    suspend fun getForContact(contactHash: String): List<StoredMessage>
    suspend fun getAll(): List<StoredMessage>
    suspend fun getOutgoingByPacketHash(hash: String): StoredMessage?
    suspend fun updateState(
        id: Long,
        state: String? = null,
        attempts: Int? = null,
        lastAttempt: Long? = null,
        lastError: String? = null,
        packetHash: String? = null,
    )
    suspend fun deleteForContact(contactHash: String)
}
