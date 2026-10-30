package com.github.libretube.helpers

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.WatchHistoryEntry
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toID

/**
 * Stores which videos the user dismissed from which section of the app.
 *
 * Dismissals from the feed (subscriptions and home featured) last forever.
 * A dismissal from continue watching is only remembered until the watch position
 * of the video changed again, as the user apparently resumed watching it then.
 *
 * Both lists are stored as plain strings so that they are included in backups
 * without any additional handling there.
 */
object DismissHelper {
    private const val ENTRY_SEPARATOR = ","
    private const val VALUE_SEPARATOR = "="
    private const val NO_POSITION = -1L

    private fun getEntries(key: String): List<String> {
        return PreferenceHelper.getString(key, "")
            .split(ENTRY_SEPARATOR)
            .filter { it.isNotBlank() }
    }

    private fun putEntries(key: String, entries: List<String>) {
        PreferenceHelper.putString(key, entries.joinToString(ENTRY_SEPARATOR))
    }

    fun getDismissedFeedVideoIds(): Set<String> {
        return getEntries(PreferenceKeys.DISMISSED_FROM_FEED).toSet()
    }

    fun dismissFromFeed(videoId: String) {
        val dismissedVideos = getEntries(PreferenceKeys.DISMISSED_FROM_FEED)
        if (videoId in dismissedVideos) return

        putEntries(PreferenceKeys.DISMISSED_FROM_FEED, dismissedVideos + videoId)
    }

    fun undoFeedDismissal(videoId: String) {
        putEntries(
            PreferenceKeys.DISMISSED_FROM_FEED,
            getEntries(PreferenceKeys.DISMISSED_FROM_FEED) - videoId
        )
    }

    fun filterDismissedFromFeed(streams: List<StreamItem>): List<StreamItem> {
        val dismissedVideoIds = getDismissedFeedVideoIds()
        if (dismissedVideoIds.isEmpty()) return streams

        return streams.filter { it.url.orEmpty().toID() !in dismissedVideoIds }
    }

    private fun getContinueWatchingDismissals(): Map<String, Long> {
        return getEntries(PreferenceKeys.DISMISSED_FROM_CONTINUE_WATCHING)
            .mapNotNull { entry ->
                val parts = entry.split(VALUE_SEPARATOR, limit = 2)
                if (parts.size != 2) return@mapNotNull null

                parts[0] to (parts[1].toLongOrNull() ?: NO_POSITION)
            }
            .toMap()
    }

    private fun putContinueWatchingDismissals(dismissals: Map<String, Long>) {
        putEntries(
            PreferenceKeys.DISMISSED_FROM_CONTINUE_WATCHING,
            dismissals.map { "${it.key}$VALUE_SEPARATOR${it.value}" }
        )
    }

    fun dismissFromContinueWatching(videoId: String, positionMillis: Long?) {
        val dismissals = getContinueWatchingDismissals().toMutableMap()
        dismissals[videoId] = positionMillis ?: NO_POSITION
        putContinueWatchingDismissals(dismissals)
    }

    fun undoContinueWatchingDismissal(videoId: String) {
        val dismissals = getContinueWatchingDismissals().toMutableMap()
        dismissals.remove(videoId)
        putContinueWatchingDismissals(dismissals)
    }

    /**
     * Filters the watch history for continue watching. Dismissals whose watch
     * position changed in the meantime are forgotten, the video returns to the
     * section then.
     */
    fun filterContinueWatching(history: List<WatchHistoryEntry>): List<WatchHistoryEntry> {
        val dismissals = getContinueWatchingDismissals()
        if (dismissals.isEmpty()) return history

        val staleVideoIds = mutableSetOf<String>()
        val filteredHistory = history.filter { entry ->
            val videoId = entry.video.url.orEmpty().toID()
            val dismissalPosition = dismissals[videoId] ?: return@filter true

            val positionChanged =
                dismissalPosition != (entry.metadata.positionMillis ?: NO_POSITION)
            if (positionChanged) staleVideoIds.add(videoId)

            positionChanged
        }

        if (staleVideoIds.isNotEmpty()) {
            putContinueWatchingDismissals(dismissals - staleVideoIds)
        }

        return filteredHistory
    }
}
