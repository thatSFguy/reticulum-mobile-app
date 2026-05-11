package io.github.thatsfguy.reticulum.android.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface IdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: IdentityEntity)

    @Query("SELECT * FROM identity WHERE id = 0 LIMIT 1")
    suspend fun load(): IdentityEntity?
}

@Dao
internal interface DestinationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DestinationEntity)

    @Query("SELECT * FROM destinations WHERE hash = :hash LIMIT 1")
    suspend fun get(hash: String): DestinationEntity?

    @Query("SELECT * FROM destinations WHERE hidden = 0 ORDER BY lastSeen DESC")
    suspend fun getAll(): List<DestinationEntity>

    @Query("SELECT * FROM destinations WHERE hidden = 0 ORDER BY favorite DESC, lastSeen DESC")
    fun observeAll(): Flow<List<DestinationEntity>>

    @Query("UPDATE destinations SET favorite = :favorite, hidden = 0 WHERE hash = :hash")
    suspend fun setFavorite(hash: String, favorite: Boolean)

    @Query("UPDATE destinations SET userLabel = :label WHERE hash = :hash")
    suspend fun setUserLabel(hash: String, label: String?)

    @Query("UPDATE destinations SET hidden = 1 WHERE hash = :hash")
    suspend fun hide(hash: String)

    @Query("DELETE FROM destinations WHERE hash = :hash")
    suspend fun hardDelete(hash: String)

    @Query("DELETE FROM destinations")
    suspend fun deleteAll()
}

@Dao
internal interface NomadPageCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NomadPageCacheEntity)

    @Query("SELECT * FROM nomad_page_cache WHERE destHash = :destHash AND path = :path LIMIT 1")
    suspend fun get(destHash: String, path: String): NomadPageCacheEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM nomad_page_cache WHERE destHash = :destHash LIMIT 1)")
    suspend fun anyForDest(destHash: String): Boolean

    /** Set of destHashes that have at least one cached page — drives the Nomad
     *  list "cached" indicator and the Cached filter chip. */
    @Query("SELECT DISTINCT destHash FROM nomad_page_cache")
    fun observeCachedDestHashes(): Flow<List<String>>

    @Query("DELETE FROM nomad_page_cache WHERE destHash = :destHash AND path = :path")
    suspend fun delete(destHash: String, path: String)

    @Query("DELETE FROM nomad_page_cache WHERE destHash = :destHash")
    suspend fun deleteAllForDest(destHash: String)

    @Query("DELETE FROM nomad_page_cache")
    suspend fun deleteAll()
}

@Dao
internal interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    // id ASC tie-break is load-bearing: two messages saved within the same
    // millisecond would otherwise flip order between consecutive observe*
    // emissions, surfacing as "the bubbles reorder themselves" on rapid
    // bursts (e.g. /users reply landing alongside an outgoing drain after
    // a transport reconnect).
    @Query("SELECT * FROM messages WHERE contactHash = :contactHash ORDER BY timestamp ASC, id ASC")
    suspend fun getForContact(contactHash: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE contactHash = :contactHash ORDER BY timestamp ASC, id ASC")
    fun observeForContact(contactHash: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC, id ASC")
    suspend fun getAll(): List<MessageEntity>

    /** Distinct contactHash values that have at least one incoming
     *  message. Used to build the Messages-tab Inbox section so
     *  senders we haven't favorited yet are still reachable. */
    @Query("SELECT DISTINCT contactHash FROM messages WHERE direction = 'incoming'")
    fun observeIncomingContactHashes(): Flow<List<String>>

    @Query("""
        UPDATE messages
        SET state       = COALESCE(:state,       state),
            attempts    = COALESCE(:attempts,    attempts),
            lastAttempt = COALESCE(:lastAttempt, lastAttempt),
            lastError   = COALESCE(:lastError,   lastError),
            packetHash  = COALESCE(:packetHash,  packetHash)
        WHERE id = :id
    """)
    suspend fun updateState(
        id: Long,
        state: String?,
        attempts: Int?,
        lastAttempt: Long?,
        lastError: String?,
        packetHash: String?,
    )

    @Query("DELETE FROM messages WHERE contactHash = :contactHash")
    suspend fun deleteForContact(contactHash: String)

    /** Find an outgoing message by its truncated packet hash (hex).
     *  Used to match incoming PROOF packets to the message they
     *  acknowledge, so we can mark it delivered. */
    @Query("SELECT * FROM messages WHERE packetHash = :hash AND direction = 'outgoing' LIMIT 1")
    suspend fun getOutgoingByPacketHash(hash: String): MessageEntity?
}
