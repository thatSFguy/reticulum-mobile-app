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
internal interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ContactEntity)

    @Query("SELECT * FROM contacts WHERE hash = :hash LIMIT 1")
    suspend fun get(hash: String): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY lastSeen DESC")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("DELETE FROM contacts WHERE hash = :hash")
    suspend fun delete(hash: String)
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

@Dao
internal interface NodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NodeEntity)

    @Query("SELECT * FROM nodes ORDER BY lastSeen DESC")
    suspend fun getAll(): List<NodeEntity>

    @Query("SELECT * FROM nodes ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<NodeEntity>>

    @Query("DELETE FROM nodes WHERE hash = :hash")
    suspend fun delete(hash: String)

    @Query("DELETE FROM nodes")
    suspend fun deleteAll()
}
