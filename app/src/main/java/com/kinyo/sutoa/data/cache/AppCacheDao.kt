package com.kinyo.sutoa.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AppEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AppEntity)

    @Query("SELECT * FROM apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPkg(packageName: String): AppEntity?

    /** Simple LIKE search across name / package / summary. */
    @Query(
        """
        SELECT * FROM apps
        WHERE name LIKE '%' || :query || '%'
           OR packageName LIKE '%' || :query || '%'
           OR summary LIKE '%' || :query || '%'
        ORDER BY lastUpdated DESC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 50): List<AppEntity>

    @Query("SELECT COUNT(*) FROM apps WHERE cachedAt > :since")
    suspend fun countFresh(since: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM apps WHERE cachedAt > :since)")
    suspend fun hasFreshData(since: Long): Boolean

    /** All cached rows whose source is still fresh — used by the update checker. */
    @Query("SELECT * FROM apps ORDER BY source ASC, name ASC")
    suspend fun getAll(): List<AppEntity>

    @Query("SELECT * FROM apps ORDER BY source ASC, name ASC")
    fun observeAll(): Flow<List<AppEntity>>

    @Query("DELETE FROM apps WHERE cachedAt < :before")
    suspend fun deleteStale(before: Long): Int
}
