package org.openbased.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LibraryEntity::class, MediaEntity::class, SyncStateEntity::class, DownloadEntity::class,
        PendingProgressEntity::class,
    ],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun downloads(): DownloadDao
    abstract fun progress(): ProgressDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "openbased.db").build()
    }
}
