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

    @Query("SELECT * FROM destinations ORDER BY lastSeen DESC")
    suspend fun getAll(): List<DestinationEntity>

    @Query("SELECT * FROM destinations ORDER BY favorite DESC, lastSeen DESC")
    fun observeAll(): Flow<List<DestinationEntity>>

    @Query("UPDATE destinations SET favorite = :favorite WHERE hash = :hash")
    suspend fun setFavorite(hash: String, favorite: Boolean)

    @Query("DELETE FROM destinations WHERE hash = :hash")
    suspend fun delete(hash: String)

    @Query("DELETE FROM destinations")
    suspend fun deleteAll()
}

@Dao
internal interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE contactHash = :contactHash ORDER BY timestamp ASC")
    suspend fun getForContact(contactHash: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE contactHash = :contactHash ORDER BY timestamp ASC")
    fun observeForContact(contactHash: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<MessageEntity>

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
}
