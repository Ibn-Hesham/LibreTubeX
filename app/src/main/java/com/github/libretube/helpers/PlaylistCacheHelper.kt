package com.github.libretube.helpers

import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.Playlist
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.toPlaylistCache
import com.github.libretube.db.obj.toPlaylistCacheItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PlaylistCacheHelper {
    /**
     * Store the given (first) page of the playlist in the local cache and fetch the
     * remaining pages in the background, so that the playlist can be opened when
     * there is no internet connection available.
     */
    suspend fun updateCache(playlistId: String, firstPage: Playlist) = withContext(Dispatchers.IO) {
        val dao = DatabaseHolder.Database.playlistCacheDao()
        dao.deleteCacheItems(playlistId)
        dao.insertCache(firstPage.toPlaylistCache(playlistId))
        dao.insertItems(
            firstPage.relatedStreams.mapIndexed { index, item ->
                item.toPlaylistCacheItem(playlistId, index)
            }
        )

        var cachedCount = firstPage.relatedStreams.size
        var nextPage = firstPage.nextpage
        while (nextPage != null) {
            val page = runCatching {
                MediaServiceRepository.instance.getPlaylistNextPage(playlistId, nextPage)
            }.getOrNull() ?: break

            dao.insertItems(
                page.relatedStreams.mapIndexed { index, item ->
                    item.toPlaylistCacheItem(playlistId, cachedCount + index)
                }
            )
            cachedCount += page.relatedStreams.size
            nextPage = page.nextpage
        }
    }

    suspend fun deleteCache(playlistId: String) = withContext(Dispatchers.IO) {
        DatabaseHolder.Database.playlistCacheDao().deleteCache(playlistId)
    }
}
