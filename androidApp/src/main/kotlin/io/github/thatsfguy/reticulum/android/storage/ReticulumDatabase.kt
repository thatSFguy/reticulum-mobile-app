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
    version = 7,
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

        fun get(context: Context): ReticulumDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReticulumDatabase::class.java,
                    "reticulum.db",
                )
                    .addMigrations(MIGRATION_6_7)
                    // Pre-v6 alpha installs are still wiped on schema
                    // mismatch. From v6 forward we add real migrations
                    // (starting with MIGRATION_6_7) so users keep their
                    // starred favorites and message history.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
