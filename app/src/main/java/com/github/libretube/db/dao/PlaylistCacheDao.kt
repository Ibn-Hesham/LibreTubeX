package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.libretube.db.obj.PlaylistCache
import com.github.libretube.db.obj.PlaylistCacheItem
import com.github.libretube.db.obj.PlaylistCacheWithItems

@Dao
interface PlaylistCacheDao {
    @Transaction
    @Query("SELECT * FROM playlistCache WHERE playlistId = :playlistId")
    suspend fun getCacheWithItems(playlistId: String): PlaylistCacheWithItems?

    @Query("SELECT * FROM playlistCache WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getCache(playlistId: String): PlaylistCache?

    @Query("SELECT COUNT(*) FROM playlistCacheItem WHERE playlistId = :playlistId")
    suspend fun getItemCount(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(playlistCache: PlaylistCache)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PlaylistCacheItem>)

    @Query("DELETE FROM playlistCache WHERE playlistId = :playlistId")
    suspend fun deleteCacheHeader(playlistId: String)

    @Query("DELETE FROM playlistCacheItem WHERE playlistId = :playlistId")
    suspend fun deleteCacheItems(playlistId: String)

    @Transaction
    suspend fun deleteCache(playlistId: String) {
        deleteCacheItems(playlistId)
        deleteCacheHeader(playlistId)
    }
}
