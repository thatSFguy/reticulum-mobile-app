package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

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
    version = 21,
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

        /** Test-only: close and forget the singleton so a test can drive
         *  [get] through fresh-open / wipe / restore scenarios. */
        internal fun closeInstanceForTest() {
            runCatching { INSTANCE?.close() }
            INSTANCE = null
        }

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

        /**
         * v1.2.4: attachment-store token references. Adds four
         * nullable columns to the messages table —
         * `imageToken` / `imageSize` / `attachmentToken` /
         * `attachmentSize` — so attachment payloads can live as
         * app-private files in `AttachmentStore` keyed by an opaque
         * token, instead of as multi-MB BLOBs on the row (a blob past
         * Android's 2 MB CursorWindow per-row limit crashes the whole
         * conversation query). See docs/ATTACHMENT-STORE.md §3.2–3.3.
         *
         * Purely additive — the legacy `imageBytes` / `attachmentBytes`
         * BLOB columns are left in place so the bubble renderer can
         * dual-read pre-upgrade rows; no data is moved (Room migrations
         * can't do file I/O). Backfilled NULL for existing rows.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageToken TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN imageSize INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentToken TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentSize INTEGER")
            }
        }

        /**
         * v17: LXMF `FIELD_AUDIO` (key 7) audio clips. Adds a single
         * nullable `audioMode` column (the `AudioMode.*` codec byte) that
         * marks a row as a playable clip; the clip bytes reuse the existing
         * attachment-store columns. Purely additive, NULL for existing rows.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN audioMode INTEGER")
            }
        }

        /**
         * v18: ratchet continuity across restarts (SPEC §7.4). Adds the
         * rotated-out ratchet privkey (plaintext + vault-sealed column
         * pair, mirroring the current ratchet's columns) and the
         * wall-clock ms of the last rotation to the identity row.
         * Before this, both lived only in engine memory: every cold
         * start reset the rotation clock to 0, rotated on the first
         * announce, and two restarts inside the 30-min window discarded
         * a ratchet peers were still encrypting to — silent inbound
         * message loss until the fresh announce propagated (seconds on
         * TCP, potentially never on a quiet RF mesh). Backfilled
         * NULL / 0 for existing rows, which reproduces the legacy
         * behavior exactly once and then persists from the next
         * rotation onward.
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE identity ADD COLUMN previousRatchetPrivKey BLOB")
                db.execSQL("ALTER TABLE identity ADD COLUMN previousRatchetPrivKeyEnc BLOB")
                db.execSQL("ALTER TABLE identity ADD COLUMN lastRatchetRotationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v19: RRC group-chat state. Three additive columns:
         *
         *  - `rrc_room.lastReadMessageId` — highest `rrc_message.id`
         *    the user has seen in that room, which is what unread
         *    counts are derived from. Row id and not timestamp: a
         *    room's timestamps come from every member's own clock,
         *    and only the hub's fan-out order is agreed by all of them.
         *  - `rrc_room.notifyMode` — `all` / `mentions` / `none`, per
         *    room, so one busy room doesn't cost the user the rest.
         *  - `rrc_message.mention` — the line names us (`@nick` or
         *    `@hashprefix`, `client-parity.md` §8), which drives the
         *    highlight and the mentions-only notification mode.
         *
         * The read marker is BACKFILLED to each room's newest message:
         * a column default of 0 would mean "nothing in this room has
         * ever been read", so the upgrade itself would flag every
         * message the user had already seen as new. History that
         * predates the feature is read history.
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE rrc_room ADD COLUMN lastReadMessageId " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE rrc_room ADD COLUMN notifyMode TEXT NOT NULL DEFAULT 'all'",
                )
                db.execSQL(
                    "ALTER TABLE rrc_message ADD COLUMN mention INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(BACKFILL_RRC_READ_MARKERS)
            }
        }

        /**
         * v20: repair the read markers on installs that already took v19
         * (versionCode 10304), where the new column defaulted to 0 and
         * every message already in every room came back flagged as new.
         *
         * Schema-identical to v19 — this migration exists only to carry
         * the data fix, which is why it needs a version bump at all.
         * Scoped to rooms whose marker is still 0 so a room the user has
         * opened since upgrading keeps the marker they earned, and a
         * genuinely never-read room is caught up rather than left
         * permanently shouting.
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("$BACKFILL_RRC_READ_MARKERS WHERE lastReadMessageId = 0")
            }
        }

        /** Set every room's read marker to its newest stored message —
         *  i.e. "everything already here has been seen". Callers may
         *  append a WHERE clause. Internal so the test asserts the
         *  statement the migration actually runs, not a copy of it. */
        internal const val BACKFILL_RRC_READ_MARKERS =
            "UPDATE rrc_room SET lastReadMessageId = (" +
                "SELECT COALESCE(MAX(m.id), 0) FROM rrc_message m " +
                "WHERE m.hubHash = rrc_room.hubHash AND m.room = rrc_room.name)"

        /**
         * v21: RRC replies and reactions (`rrc-extensions.md` v1, the
         * envelope keys 64/65/66). Two additive columns on rrc_message:
         *
         *  - `replyToMsgId` — the `K_ID` this message answers. A reply
         *    whose target we don't hold still renders as an ordinary
         *    message, so this is decoration and NULL is fine.
         *  - `reactionsJson` — reactions aggregated onto this message,
         *    emoji → the identity hashes holding it. Nothing aggregates
         *    on the wire or in the hub; each reaction arrives as its own
         *    message and is folded into this column.
         *
         * Both NULL on existing rows, which is exactly "no reply anchor,
         * no reactions".
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rrc_message ADD COLUMN replyToMsgId TEXT")
                db.execSQL("ALTER TABLE rrc_message ADD COLUMN reactionsJson TEXT")
            }
        }

        /**
         * Zero freed pages so secret bytes that get overwritten or
         * emptied — the identity plaintext-fallback columns and every
         * plaintext->sealed migration (see IdentityRepoImpl.save) — do
         * NOT linger as recoverable cleartext in the DB file or WAL.
         * SQLite's secure_delete is OFF by default; without it a
         * superseded row image survives in freed B-tree pages / the
         * -wal file indefinitely. Audit reference: 2026-07-28 M4.
         */
        internal fun secureDeleteCallback(appContext: Context) = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // CRITICAL: this runs on EVERY database open, so nothing here
                // may throw — a failure would crash-loop the app at launch.
                // `PRAGMA secure_delete = ON` and `PRAGMA wal_checkpoint(...)`
                // both ECHO a result row, and Android's execSQL rejects any
                // result-returning statement ("Queries can be performed using
                // ... query or rawQuery only"), so they MUST go through
                // query(); everything is additionally wrapped in runCatching.
                // (Regression fixed here after 10298 crashed on open.)
                //
                // secure_delete applies to every future delete/overwrite on
                // this connection (and each pooled WAL connection as it opens),
                // zeroing freed pages so superseded secret bytes don't linger.
                runCatching { db.query("PRAGMA secure_delete = ON").close() }
                // One-time purge of cleartext that predates this fix (rows the
                // old catch-all save downgraded to plaintext): checkpoint the
                // WAL into the main file, then VACUUM to drop freed pages. The
                // "done" flag is set FIRST so a failure can never become a
                // retry/crash loop, and the work is best-effort (VACUUM can't
                // run inside a transaction, etc.).
                val prefs = appContext.getSharedPreferences(
                    "reticulum_db_maint", Context.MODE_PRIVATE,
                )
                if (!prefs.getBoolean("secure_delete_vacuum_done", false)) {
                    prefs.edit().putBoolean("secure_delete_vacuum_done", true).apply()
                    runCatching {
                        db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                        db.execSQL("VACUUM")
                    }
                }
            }
        }

        const val DB_NAME = "reticulum.db"
        private const val BACKUP_NAME = "reticulum.db.bak"

        private fun buildRoom(appContext: Context): ReticulumDatabase =
            Room.databaseBuilder(appContext, ReticulumDatabase::class.java, DB_NAME)
                .addCallback(secureDeleteCallback(appContext))
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
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                // Pre-v6 alpha installs are still wiped on schema mismatch.
                // From v6 forward we add real migrations so users keep their
                // starred favorites and message history across upgrades.
                // NOTE: deliberately NO all-versions fallbackToDestructiveMigration
                // — a v6+ schema mismatch MUST throw (fail closed), never wipe.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                .build()

        /**
         * DATA-LOSS SAFETY NET (2026-07-28 incident: a crash-loop corrupted
         * the DB, and androidx.sqlite's onCorruption handler DELETED
         * reticulum.db, so the app came up with a brand-new empty identity and
         * every message/favorite/pin was gone).
         *
         * We keep a "last-known-good" copy at [BACKUP_NAME], refreshed on every
         * healthy open, and transparently restore it whenever the live DB comes
         * up WITHOUT an identity but a healthy backup exists. This is
         * mechanism-independent: it doesn't matter whether the DB was deleted by
         * the corruption handler, a recovery path, or a future bug — if the data
         * vanished and we have a snapshot, it comes back.
         *
         * The two hard invariants that keep this from EVER destroying data:
         *   - We only OVERWRITE the backup when the live DB actually has an
         *     identity (so an empty / fresh / just-wiped DB can never clobber a
         *     good backup).
         *   - We only RESTORE over the live DB when the live DB has NO identity
         *     (so a healthy live DB is never overwritten by a stale backup).
         * The restore is attempted at most once per open. Every step is wrapped
         * so a backup/restore hiccup can never crash DB bring-up.
         */
        fun get(context: Context): ReticulumDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    // (1) Between-sessions wipe: the DB file is gone or empty on
                    //     disk but a backup exists — restore before opening.
                    restoreDbFileIfMissing(appCtx)

                    var db = buildRoom(appCtx)
                    // Force the open (runs migrations + onOpen). This is also
                    // where a corrupt DB gets deleted+recreated-empty by the
                    // platform, which is why we re-check for an identity AFTER.
                    var hasId = runCatching { hasIdentityRow(db) }.getOrDefault(false)

                    // (2) During-open wipe: the DB opened but holds no identity,
                    //     yet we have a backup with real data → the DB was wiped
                    //     during open. Restore from the backup and reopen once.
                    if (!hasId && backupHasData(appCtx)) {
                        Log.w(
                            "ReticulumEngine",
                            "reticulum.db has no identity but a healthy backup exists — " +
                                "the database was wiped; restoring from last-known-good backup.",
                        )
                        runCatching { db.close() }
                        forceRestoreDbFromBackup(appCtx)
                        db = buildRoom(appCtx)
                        hasId = runCatching { hasIdentityRow(db) }.getOrDefault(false)
                    }

                    // (3) Healthy → refresh the last-known-good snapshot.
                    if (hasId) snapshotBackup(appCtx, db)

                    INSTANCE = db
                    db
                }
            }
        }

        /** Files SQLite may keep alongside the main DB. */
        private fun dbSiblings(appContext: Context): Triple<File, File, File> {
            val main = appContext.getDatabasePath(DB_NAME)
            val dir = main.parentFile
            return Triple(main, File(dir, "$DB_NAME-wal"), File(dir, "$DB_NAME-shm"))
        }

        private fun backupFile(appContext: Context): File =
            File(appContext.getDatabasePath(DB_NAME).parentFile, BACKUP_NAME)

        /** A backup that plausibly holds a real database (SQLite header is 100
         *  bytes; a schema-only DB is a few KB). */
        private fun backupHasData(appContext: Context): Boolean {
            val bak = backupFile(appContext)
            return bak.exists() && bak.length() > 4096L
        }

        /** True iff the identity row exists with a private key in either the
         *  sealed or the legacy-plaintext column. Forces the DB open. */
        private fun hasIdentityRow(db: ReticulumDatabase): Boolean =
            db.openHelper.writableDatabase.query(
                "SELECT EXISTS(SELECT 1 FROM identity WHERE id = 0 AND " +
                    "(LENGTH(encPrivKey) > 0 OR LENGTH(encPrivKeyEnc) > 0))",
            ).use { c -> c.moveToFirst() && c.getInt(0) == 1 }

        /** (1) Restore only when the live main file is missing or empty on disk.
         *  A present, non-empty file is never touched here — the AFTER-open
         *  identity check (step 2) covers the "opened but wiped" case. */
        private fun restoreDbFileIfMissing(appContext: Context) {
            runCatching {
                val (main, _, _) = dbSiblings(appContext)
                if ((main.exists() && main.length() > 0L) || !backupHasData(appContext)) return
                forceRestoreDbFromBackup(appContext)
                Log.w("ReticulumEngine", "reticulum.db was missing/empty on open — restored from backup.")
            }.onFailure { Log.e("ReticulumEngine", "DB pre-open restore failed: ${it.message}", it) }
        }

        /** Overwrite the live DB with the backup. Clears stale WAL/SHM so the
         *  restored main file is authoritative. Caller guarantees this is safe
         *  (live DB has no identity, backup has data). */
        private fun forceRestoreDbFromBackup(appContext: Context) {
            val (main, wal, shm) = dbSiblings(appContext)
            val bak = backupFile(appContext)
            main.parentFile?.mkdirs()
            wal.delete(); shm.delete()
            bak.copyTo(main, overwrite = true)
        }

        /** (3) Snapshot the live DB to the backup. Only called when the DB has
         *  an identity, so a good backup can never be replaced by an empty one.
         *  Checkpoints the WAL into the main file first so the file copy is
         *  complete, and writes via a temp + atomic rename so the backup is
         *  never a torn/half-written file. */
        private fun snapshotBackup(appContext: Context, db: ReticulumDatabase) {
            runCatching {
                runCatching {
                    db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                }
                val (main, _, _) = dbSiblings(appContext)
                if (!main.exists() || main.length() <= 4096L) return  // nothing solid to snapshot
                val bak = backupFile(appContext)
                val tmp = File(bak.parentFile, "$BACKUP_NAME.tmp")
                main.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(bak)) {
                    // renameTo can fail if the target exists on some FS — replace.
                    bak.delete()
                    if (!tmp.renameTo(bak)) { tmp.copyTo(bak, overwrite = true); tmp.delete() }
                }
            }.onFailure { Log.w("ReticulumEngine", "DB backup snapshot skipped: ${it.message}") }
        }
    }
}
