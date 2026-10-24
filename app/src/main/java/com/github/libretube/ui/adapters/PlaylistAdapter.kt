package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.VideoRowBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.setFormattedDuration
import com.github.libretube.ui.extensions.setWatchProgressLength
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet.Companion.VIDEO_OPTIONS_SHEET_REQUEST_KEY
import com.github.libretube.ui.viewholders.PlaylistViewHolder
import com.github.libretube.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistItem(
    val item: StreamItem,
    /**
     * The original index of the playlist item before sorting the feed.
     */
    val originalPlaylistIndex: Int,
)

/**
 * The playlist videos are held synchronously (instead of in an async ListAdapter
 * differ), so that the list always matches what is displayed. This is required
 * for reordering the videos by dragging, which moves items around one by one
 * while the user might also scroll the list at the same time.
 */
class PlaylistAdapter(
    private val playlistId: String,
    private val onVideoClick: (StreamItem) -> Unit
) : RecyclerView.Adapter<PlaylistViewHolder>() {
    /**
     * Whether long pressing a video opens its options. Disabled while the videos
     * of the playlist are being reordered, as long press then starts a drag.
     */
    var longClicksEnabled = true

    private val videos = mutableListOf<PlaylistItem>()

    val currentList: List<PlaylistItem>
        get() = videos

    override fun getItemCount(): Int = videos.size

    fun submitList(newVideos: List<PlaylistItem>) {
        val diffResult = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                // the index is not relevant for whether the playlist videos are the same
                // hence only compare the videos themselves
                override fun getOldListSize(): Int = videos.size
                override fun getNewListSize(): Int = newVideos.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    videos[oldPos].item == newVideos[newPos].item

                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    videos[oldPos].item == newVideos[newPos].item
            }
        )
        videos.clear()
        videos.addAll(newVideos)
        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * Move a video to a new position, e.g. while it's dragged around.
     */
    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        videos.add(to, videos.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = VideoRowBinding.inflate(layoutInflater, parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val (streamItem, _) = videos[position]
        val videoId = streamItem.url!!.toID()

        holder.binding.apply {
            videoTitle.text = streamItem.title
            videoInfo.text = TextUtils.formatViewsString(root.context, streamItem.views ?: -1, streamItem.uploaded)
            videoInfo.maxLines = 2

            // piped does not load channel avatars for playlist views
            channelImageContainer.isGone = true
            channelName.text = streamItem.uploaderName

            ImageHelper.loadImage(streamItem.thumbnail, thumbnail)
            thumbnailDuration.setFormattedDuration(streamItem.duration ?: -1, streamItem.isShort, streamItem.uploaded)

            root.setOnClickListener {
                onVideoClick(streamItem)
            }

            val activity = (root.context as BaseActivity)
            val fragmentManager = activity.supportFragmentManager
            root.setOnLongClickListener {
                if (!longClicksEnabled) return@setOnLongClickListener true

                fragmentManager.setFragmentResultListener(
                    VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    notifyItemChanged(position)
                }
                VideoOptionsBottomSheet().apply {
                    arguments = bundleOf(
                        IntentData.streamItem to streamItem,
                        IntentData.playlistId to playlistId
                    )
                }
                    .show(fragmentManager, VideoOptionsBottomSheet::class.java.name)
                true
            }

            if (!streamItem.uploaderUrl.isNullOrBlank()) {
                channelContainer.setOnClickListener {
                    NavigationHelper.navigateChannel(root.context, streamItem.uploaderUrl)
                }
            }

            streamItem.duration?.let { watchProgress.setWatchProgressLength(videoId, it) }

            CoroutineScope(Dispatchers.IO).launch {
                val isDownloaded =
                    DatabaseHolder.Database.downloadDao().exists(videoId)

                withContext(Dispatchers.Main) {
                    downloadBadge.isVisible = isDownloaded
                }
            }
        }
    }
}
