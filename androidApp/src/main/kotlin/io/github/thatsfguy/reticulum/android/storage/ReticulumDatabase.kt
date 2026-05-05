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
    version = 8,
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

        fun get(context: Context): ReticulumDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReticulumDatabase::class.java,
                    "reticulum.db",
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
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
