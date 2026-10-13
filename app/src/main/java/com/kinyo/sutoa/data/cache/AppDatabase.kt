package com.kinyo.sutoa.data.cache

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AppEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appCacheDao(): AppCacheDao

    companion object {
        const val NAME = "sutoa.db"
    }
}
