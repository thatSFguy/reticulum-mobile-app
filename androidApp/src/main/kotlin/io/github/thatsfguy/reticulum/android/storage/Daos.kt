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

    /** True iff the identity's private keys currently live in the legacy
     *  plaintext columns (populated `encPrivKey`, empty/null `encPrivKeyEnc`)
     *  rather than the Keystore-sealed columns — i.e. this device degraded
     *  to unencrypted key storage because its Keystore refused the vault.
     *  Reactive so the warning banner clears automatically the moment a
     *  later save migrates the row into the sealed columns. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM identity WHERE id = 0 " +
            "AND LENGTH(encPrivKey) > 0 " +
            "AND (encPrivKeyEnc IS NULL OR LENGTH(encPrivKeyEnc) = 0))"
    )
    fun observeKeysStoredPlaintext(): Flow<Boolean>
}

@Dao
internal interface DestinationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DestinationEntity)

    @Query("SELECT * FROM destinations WHERE hash = :hash LIMIT 1")
    suspend fun get(hash: String): DestinationEntity?

    @Query("SELECT * FROM destinations WHERE hidden = 0 ORDER BY lastSeen DESC")
    suspend fun getAll(): List<DestinationEntity>

    // Hard LIMIT 1500 to keep the Flow's result set inside Android's
    // 2 MB default CursorWindow even if eviction hasn't run yet.
    // Empirically, a tester crashed at row 1123 on a verbose mesh:
    //
    //   FATAL EXCEPTION: java.lang.IllegalStateException:
    //   Couldn't read row 1123, col 0 from CursorWindow
    //
    // per-row size on a destination with a verbose announce
    // app_data + telemetryJson is ~1.5-2 KB, so the 2 MB window
    // exhausts somewhere between row 1000-1200. The engine's MED-2
    // eviction caps unfavorited rows at 1000; LIMIT 1500 sorts
    // favorites first so they're always in the result, plus
    // headroom for the eviction race. Pre-1.1.26 builds let this
    // table grow unbounded, so any user upgrading from those
    // versions would otherwise crash here at the first Flow read
    // on launch — see ReticulumEngine.evictDestinationsOnStartup
    // for the eager startup trim. Audit reference: 2026-05-13
    // MED-2 follow-up.
    @Query("SELECT * FROM destinations WHERE hidden = 0 ORDER BY favorite DESC, lastSeen DESC LIMIT 1000")
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

    /**
     * MED-2 announce-flood eviction. Count non-favorited, non-
     * user-labeled, non-hidden rows; if it exceeds [keepCount],
     * hard-delete the oldest (lowest lastSeen) overflow. Favorites
     * and user-renamed entries are preserved regardless of count
     * — those represent intentional user state. Hidden (soft-
     * deleted) rows already don't count toward the visible list
     * but are excluded here too so the eviction doesn't churn
     * them. Audit reference: 2026-05-13 MED-2.
     *
     * Contacts with message history are also exempt
     * (`hash NOT IN (SELECT contactHash FROM messages)`): on a busy
     * TCP/transport attachment the table churns fast, and a contact
     * you're actively conversing with — but haven't favorited — was
     * being evicted out from under an open conversation, dropping its
     * public key so the chat reverted to "(unknown sender)" and
     * couldn't send until the peer re-announced. Like favorites, these
     * are real user state. They don't count toward [keepCount], so the
     * effective table size is (favorites + labeled + message-history) +
     * up to [keepCount] announce-only rows; message-history contacts
     * are bounded by actual conversations, so this stays well under the
     * CursorWindow limit the cap was lowered to protect. Set 2026-06-24.
     */
    @Query("""
        DELETE FROM destinations
        WHERE hash IN (
            SELECT hash FROM destinations
            WHERE favorite = 0
              AND hidden = 0
              AND (userLabel IS NULL OR userLabel = '')
              AND hash NOT IN (SELECT contactHash FROM messages)
            ORDER BY lastSeen DESC
            LIMIT -1 OFFSET :keepCount
        )
    """)
    suspend fun evictUnfavoritedOldest(keepCount: Int): Int
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

/** Projection for [MessageDao.observeLastMessageTimes]. */
internal data class ConversationLastTime(val contactHash: String, val lastTs: Long)

/** Projection for [MessageDao.observeIncomingTimestamps] — one row per
 *  incoming message, keyed by sender. The ViewModel groups by
 *  [contactHash] and compares each [timestamp] to the per-contact
 *  lastRead time to compute the unread-count badge. */
internal data class IncomingTimestampRow(val contactHash: String, val timestamp: Long)

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

    /** Last-message timestamp per conversation — drives the recency
     *  sort on the Messages tab. */
    @Query("SELECT contactHash, MAX(timestamp) AS lastTs FROM messages GROUP BY contactHash")
    fun observeLastMessageTimes(): Flow<List<ConversationLastTime>>

    /** Distinct contactHash values that have at least one incoming
     *  message. Used to build the Messages-tab Inbox section so
     *  senders we haven't favorited yet are still reachable. */
    @Query("SELECT DISTINCT contactHash FROM messages WHERE direction = 'incoming'")
    fun observeIncomingContactHashes(): Flow<List<String>>

    /** Timestamps of every incoming message, per contact — joined with
     *  the per-contact lastRead times in [Preferences] to derive the
     *  unread-count badge on the Messages list. Returns only the two
     *  columns the count math needs so the flow stays cheap even with
     *  thousands of rows. */
    @Query("SELECT contactHash, timestamp FROM messages WHERE direction = 'incoming'")
    fun observeIncomingTimestamps(): Flow<List<IncomingTimestampRow>>

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

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Find an outgoing message by its packet-hash hex prefix.
     *  Used to match incoming PROOF packets to the message they
     *  acknowledge. v1.1.22+ stores the FULL 32-byte hash (64 hex
     *  chars) so PROOF Ed25519 sig verification (SPEC §6.5.5) can run
     *  against the recipient's long-term pub. Pre-v1.1.22 rows stored
     *  the 16-byte truncated form (32 hex chars). The PROOF's
     *  dest_hash is always the 16-byte truncated form, so prefix LIKE
     *  matches both shapes without a schema migration. */
    @Query("SELECT * FROM messages WHERE packetHash LIKE :hash || '%' AND direction = 'outgoing' LIMIT 1")
    suspend fun getOutgoingByPacketHash(hash: String): MessageEntity?

    /** Lookup by LXMF [messageId]. Used by the reaction-dispatch
     *  path to find the target of an inbound reaction, and by the
     *  reply-preview render to find the quoted message. */
    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): MessageEntity?

    /** Write back the canonical LXMF message_id once the engine has
     *  computed it (post-pack on outbound, post-unpack on inbound).
     *  Separate UPDATE so we don't re-write all columns when only
     *  the id needs setting. */
    @Query("UPDATE messages SET messageId = :messageId WHERE id = :rowId")
    suspend fun setMessageId(rowId: Long, messageId: String)

    /** Overwrite the aggregated reactions JSON on the target row.
     *  Caller (Repository) reads → merges → writes back as a single
     *  high-level operation. Idempotency is enforced at the JSON
     *  layer (ReactionsJson.applyReaction returns false when the
     *  sender is already present). */
    @Query("UPDATE messages SET reactionsJson = :json WHERE id = :rowId")
    suspend fun setReactionsJson(rowId: Long, json: String?)
}

/**
 * Reticulum Relay Chat storage — hubs, rooms, and room message
 * history. One DAO spans the three `rrc_*` tables since they form a
 * single feature; cascade deletes are issued explicitly by the
 * repository (no Room foreign keys, matching the rest of this schema).
 */
@Dao
internal interface RrcDao {

    // ---- hubs ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHub(row: RrcHubEntity)

    @Query("SELECT * FROM rrc_hub WHERE destHash = :destHash LIMIT 1")
    suspend fun getHub(destHash: String): RrcHubEntity?

    @Query("SELECT * FROM rrc_hub ORDER BY lastConnectedAt DESC, addedAt DESC")
    suspend fun getAllHubs(): List<RrcHubEntity>

    @Query("SELECT * FROM rrc_hub ORDER BY lastConnectedAt DESC, addedAt DESC")
    fun observeHubs(): Flow<List<RrcHubEntity>>

    @Query("UPDATE rrc_hub SET lastConnectedAt = :whenMs WHERE destHash = :destHash")
    suspend fun setHubLastConnected(destHash: String, whenMs: Long)

    @Query("DELETE FROM rrc_hub WHERE destHash = :destHash")
    suspend fun deleteHub(destHash: String)

    // ---- rooms --------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoom(row: RrcRoomEntity)

    @Query("SELECT * FROM rrc_room WHERE hubHash = :hubHash ORDER BY lastActivityAt DESC, name ASC")
    suspend fun getRoomsForHub(hubHash: String): List<RrcRoomEntity>

    @Query("SELECT * FROM rrc_room WHERE hubHash = :hubHash ORDER BY lastActivityAt DESC, name ASC")
    fun observeRoomsForHub(hubHash: String): Flow<List<RrcRoomEntity>>

    @Query("UPDATE rrc_room SET joined = :joined WHERE hubHash = :hubHash AND name = :name")
    suspend fun setRoomJoined(hubHash: String, name: String, joined: Boolean)

    /** Forward-only clock bump — an out-of-order older timestamp is
     *  rejected by the `lastActivityAt < :activityMs` guard. */
    @Query("""
        UPDATE rrc_room SET lastActivityAt = :activityMs
        WHERE hubHash = :hubHash AND name = :name AND lastActivityAt < :activityMs
    """)
    suspend fun touchRoom(hubHash: String, name: String, activityMs: Long)

    @Query("DELETE FROM rrc_room WHERE hubHash = :hubHash AND name = :name")
    suspend fun deleteRoom(hubHash: String, name: String)

    @Query("DELETE FROM rrc_room WHERE hubHash = :hubHash")
    suspend fun deleteRoomsForHub(hubHash: String)

    // ---- messages -----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(row: RrcMessageEntity): Long

    // ORDER BY id ASC — arrival order, NOT timestamp. Unlike LXMF, an
    // RRC room is multi-party and the hub forwards each sender's own
    // K_TS unchanged (rrcd session.go handleMsg rewrites K_SRC/K_NICK
    // only). Sorting by those mutually-skewed clocks scrambles the
    // room; the autoincrement id is the hub's single fan-out order.
    @Query("SELECT * FROM rrc_message WHERE hubHash = :hubHash AND room = :room ORDER BY id ASC")
    suspend fun getMessages(hubHash: String, room: String): List<RrcMessageEntity>

    @Query("SELECT * FROM rrc_message WHERE hubHash = :hubHash AND room = :room ORDER BY id ASC")
    fun observeMessages(hubHash: String, room: String): Flow<List<RrcMessageEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM rrc_message WHERE hubHash = :hubHash AND msgId = :msgId LIMIT 1)")
    suspend fun hasMessageId(hubHash: String, msgId: String): Boolean

    @Query("DELETE FROM rrc_message WHERE hubHash = :hubHash AND room = :room")
    suspend fun deleteMessagesForRoom(hubHash: String, room: String)

    @Query("DELETE FROM rrc_message WHERE hubHash = :hubHash")
    suspend fun deleteMessagesForHub(hubHash: String)
}
