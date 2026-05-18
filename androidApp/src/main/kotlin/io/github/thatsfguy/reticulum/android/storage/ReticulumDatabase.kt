package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        IdentityEntity::class,
        DestinationEntity::class,
        MessageEntity::class,
        NomadPageCacheEntity::class,
        RrcHubEntity::class,
        RrcRoomEntity::class,
        RrcMessageEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
internal abstract class ReticulumDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun destinationDao(): DestinationDao
    abstract fun messageDao(): MessageDao
    abstract fun nomadPageCacheDao(): NomadPageCacheDao
    abstract fun rrcDao(): RrcDao

    companion object {
        @Volatile private var INSTANCE: ReticulumDatabase? = null

        /**
         * v0.1.83: add `userLabel` (nullable TEXT) to destinations.
         * First non-destructive migration in this codebase — preserves
         * the user's contact list and message history across the
         * upgrade. Existing rows get NULL for userLabel and continue to
         * render their announce-derived [displayName] until the user
         * sets a nickname.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE destinations ADD COLUMN userLabel TEXT")
            }
        }

        /**
         * v0.1.85: per-message hop count. Stored alongside `rssi` so the
         * chat view can render "RSSI -85 dBm · 2 hops" on each incoming
         * bubble. Backfilled NULL for messages received before this
         * migration; UI hides the hop chip when null.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN hopCount INTEGER")
            }
        }

        /**
         * v1.1.15: image attachments. Adds a BLOB column for the
         * compressed JPEG bytes from LXMF `FIELD_IMAGE` (integer
         * msgpack key 6). Backfilled NULL for messages from before
         * the picker shipped; the bubble renderer hides the image
         * block when null. Sender-side ceiling is 20 KB (Phase 2
         * ladder); receiver enforces a 32 KB defensive cap before
         * persisting.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageBytes BLOB")
            }
        }

        /**
         * v1.1.27: Android Keystore-wrapped identity keys. Three
         * new nullable BLOB columns hold the AES-256-GCM sealed
         * bytes; the engine's identity-load path detects rows
         * with null *Enc columns (pre-1.1.27 installs) and runs
         * an in-place migration on first run after upgrade —
         * encrypts the plaintext into the new columns, then zeros
         * the plaintext columns out. Schema-level drop of the
         * plaintext columns is deferred to a future version so
         * users have a rollback path if the Keystore work needs
         * to be reverted. Audit reference: 2026-05-13 HIGH-1
         * follow-up.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE identity ADD COLUMN encPrivKeyEnc BLOB")
                db.execSQL("ALTER TABLE identity ADD COLUMN sigPrivKeyEnc BLOB")
                db.execSQL("ALTER TABLE identity ADD COLUMN ratchetPrivKeyEnc BLOB")
            }
        }

        /**
         * v1.1.33: tap-back reactions + reply-to support. Adds three
         * nullable columns to the messages table:
         *   - messageId: canonical LXMF message_id hex (32-byte
         *     SHA-256, see LxmfStamp.computeMessageId), set on both
         *     inbound and outbound rows so reactions and replies can
         *     target this row across devices.
         *   - replyToMessageId: when this row is a reply (LXMF field
         *     16 sub-key "reply_to"), the target row's messageId.
         *   - reactionsJson: aggregated reactions, JSON shape
         *     `{"👍":["sender_hex_16",...]}`, decoded via
         *     store/ReactionsJson.kt.
         * Indices on the two id columns so the receive-side lookup
         * by messageId and the reply-preview render's lookup are
         * O(log n) on a busy mesh. Pre-1.1.33 rows get null
         * messageId — reactions/replies that target them are
         * silently dropped (matches Columba's behavior; future
         * versions could buffer pending reactions keyed by the
         * outstanding target id).
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN messageId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_messageId ON messages(messageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_replyToMessageId ON messages(replyToMessageId)")
            }
        }

        /**
         * v1.1.35: idempotent index-create. Closes the upgrade-crash
         * path on v1.1.33-fresh installs:
         *
         *   1. v1.1.33's MIGRATION_10_11 created columns + indices, but
         *      its @Entity didn't declare the indices, so Room's
         *      strict validator threw post-migration → users wiped
         *      data → fresh-install on v1.1.33 → Room created table
         *      from @Entity (no indices declared) → DB stored at v11
         *      with NO indices.
         *   2. v1.1.34 added the indices to @Entity. Fresh-install
         *      worked. But upgrade from v1.1.33-fresh tripped Room's
         *      identity-hash check on open: the schema hash baked
         *      into room_master_table by v1.1.33 didn't match the
         *      hash v1.1.34 derived from the (now-with-indices)
         *      @Entity. Crash before any migration could run.
         *   3. Bumping the version to v12 means Room expects a
         *      migration path from v11. This migration creates the
         *      indices `IF NOT EXISTS` — no-op for users who came
         *      via MIGRATION_10_11 (indices already on disk), works
         *      for v1.1.33-fresh users (missing indices, get them
         *      now). After migration, Room writes the v12 identity
         *      hash to room_master_table; subsequent opens match.
         *
         * The only path NOT covered: v1.1.33 installs that NEVER
         * launched successfully because their v10→v11 migration
         * crashed. Those rolled back; on v1.1.35 they enter the
         * normal v10→v11→v12 chain. Fine.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_messageId ON messages(messageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_replyToMessageId ON messages(replyToMessageId)")
            }
        }

        /**
         * v1.1.38 — relay-aware routing for tap-back reactions and
         * swipe-replies in fwdsvc-hosted groups. Inbound LXMFs that
         * arrived over a link whose initiator LINKIDENTIFY'd as a peer
         * different from the LXMF body's `source_hash` are tagged with
         * `arrivedViaDest = <link peer destHash>`. sendReaction /
         * sendExistingMessage then route through that destination so
         * the reaction / reply reaches the relay's fanout instead of
         * egressing direct to the original sender. Existing rows get
         * NULL → fall back to legacy direct routing, so 1:1 chats and
         * any conversation predating LINKIDENTIFY support behave
         * exactly as today. Audit reference: 2026-05-14 routing fix
         * (fwdsvc agent verified reactions never reached the relay
         * because the LXMF egressed direct to BlueP).
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN arrivedViaDest TEXT")
            }
        }

        /**
         * v1.1.42 — Reticulum Relay Chat (RRC) storage. Adds three
         * `rrc_*` tables: hubs, rooms, and room message history. RRC
         * is gated by the off-by-default `experimentalRrc` preference,
         * so existing installs gain three empty tables and nothing
         * else changes — the LXMF messages / destinations tables are
         * untouched. CREATE TABLE statements match Room's entity-
         * derived schema (see Entities.kt RrcHubEntity / RrcRoomEntity
         * / RrcMessageEntity); `IF NOT EXISTS` keeps the migration
         * idempotent against any partially-applied state.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rrc_hub` (" +
                        "`destHash` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`nick` TEXT, " +
                        "`lastConnectedAt` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`destHash`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rrc_room` (" +
                        "`hubHash` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`joined` INTEGER NOT NULL, " +
                        "`lastActivityAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`hubHash`, `name`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rrc_message` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`hubHash` TEXT NOT NULL, " +
                        "`room` TEXT NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`senderIdHash` TEXT NOT NULL, " +
                        "`nick` TEXT, " +
                        "`text` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`msgId` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_rrc_message_hub_room` " +
                        "ON `rrc_message` (`hubHash`, `room`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_rrc_message_hub_msgId` " +
                        "ON `rrc_message` (`hubHash`, `msgId`)"
                )
            }
        }

        /**
         * v1.1.57: LXMF file attachments. Adds `attachmentName` (TEXT)
         * and `attachmentBytes` (BLOB) to the messages table for a
         * received `FIELD_FILE_ATTACHMENTS` (LXMF key 5) file — see
         * SPEC §5.9.7. Backfilled NULL for pre-upgrade rows; the bubble
         * renderer hides the attachment chip when null. Receiver caps
         * a persisted attachment at 256 KB (`INBOUND_FILE_MAX_BYTES`).
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentBytes BLOB")
            }
        }

        fun get(context: Context): ReticulumDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReticulumDatabase::class.java,
                    "reticulum.db",
                )
                    .addMigrations(
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                    )
                    // Pre-v6 alpha installs are still wiped on schema
                    // mismatch. From v6 forward we add real migrations
                    // so users keep their starred favorites and message
                    // history across upgrades.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
