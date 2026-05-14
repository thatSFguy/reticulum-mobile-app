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
    ],
    version = 11,
    exportSchema = true,
)
internal abstract class ReticulumDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun destinationDao(): DestinationDao
    abstract fun messageDao(): MessageDao
    abstract fun nomadPageCacheDao(): NomadPageCacheDao

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

        fun get(context: Context): ReticulumDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReticulumDatabase::class.java,
                    "reticulum.db",
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
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
