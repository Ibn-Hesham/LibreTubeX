package com.github.libretube.db.obj

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ProxyHelper

/**
 * Locally cached copy of a bookmarked playlist, so that it can still be opened
 * when there is no internet connection available.
 */
@Entity(tableName = "playlistCache")
data class PlaylistCache(
    @PrimaryKey val playlistId: String,
    val name: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val uploader: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null
)

@Entity(tableName = "playlistCacheItem", primaryKeys = ["playlistId", "position"])
data class PlaylistCacheItem(
    val playlistId: String,
    val position: Int,
    val videoId: String,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val uploadedDate: String? = null,
    val duration: Long? = null,
    val views: Long? = null,
    val uploaded: Long = 0,
    val isShort: Boolean = false
)

data class PlaylistCacheWithItems(
    @Embedded val playlistCache: PlaylistCache,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "playlistId"
    )
    val items: List<PlaylistCacheItem>
) {
    fun toPlaylist(): Playlist {
        val streams = items.sortedBy { it.position }.map { it.toStreamItem() }
        return Playlist(
            name = playlistCache.name,
            description = playlistCache.description,
            thumbnailUrl = playlistCache.thumbnailUrl,
            uploader = playlistCache.uploader,
            uploaderUrl = playlistCache.uploaderUrl,
            uploaderAvatar = playlistCache.uploaderAvatar,
            videos = streams.size,
            relatedStreams = streams
        )
    }
}

fun Playlist.toPlaylistCache(playlistId: String) = PlaylistCache(
    playlistId = playlistId,
    name = name,
    description = description,
    thumbnailUrl = thumbnailUrl?.let { ProxyHelper.unwrapUrl(it) },
    uploader = uploader,
    uploaderUrl = uploaderUrl,
    uploaderAvatar = uploaderAvatar?.let { ProxyHelper.unwrapUrl(it) }
)

fun StreamItem.toPlaylistCacheItem(playlistId: String, position: Int) = PlaylistCacheItem(
    playlistId = playlistId,
    position = position,
    videoId = url!!.toID(),
    title = title,
    thumbnailUrl = thumbnail?.let { ProxyHelper.unwrapUrl(it) },
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    uploaderAvatar = uploaderAvatar?.let { ProxyHelper.unwrapUrl(it) },
    uploadedDate = uploadedDate,
    duration = duration,
    views = views,
    uploaded = uploaded,
    isShort = isShort
)

fun PlaylistCacheItem.toStreamItem() = StreamItem(
    url = videoId,
    title = title,
    thumbnail = thumbnailUrl,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    uploaderAvatar = uploaderAvatar,
    uploadedDate = uploadedDate,
    duration = duration,
    views = views,
    uploaded = uploaded,
    isShort = isShort
)
