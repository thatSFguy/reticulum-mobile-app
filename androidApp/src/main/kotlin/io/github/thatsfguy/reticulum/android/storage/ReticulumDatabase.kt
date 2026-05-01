package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        IdentityEntity::class,
        ContactEntity::class,
        MessageEntity::class,
        NodeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class ReticulumDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun nodeDao(): NodeDao

    companion object {
        @Volatile private var INSTANCE: ReticulumDatabase? = null

        fun get(context: Context): ReticulumDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReticulumDatabase::class.java,
                    "reticulum.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
