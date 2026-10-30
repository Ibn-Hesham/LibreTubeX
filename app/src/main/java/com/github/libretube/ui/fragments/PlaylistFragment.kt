package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentPlaylistBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.toPlaylistCacheItem
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.setOnDismissListener
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.helpers.PlaylistCacheHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.parcelable.PlayerData

import com.github.libretube.repo.LocalUserDataRepository
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.adapters.PlaylistAdapter
import com.github.libretube.ui.adapters.PlaylistItem
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.addOnBottomReachedListener
import com.github.libretube.ui.models.PlaylistViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.PlaylistOptionsBottomSheet
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PlaylistFragment : DynamicLayoutManagerFragment(R.layout.fragment_playlist) {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<PlaylistFragmentArgs>()

    // general playlist information
    private lateinit var playlistId: String
    private var playlistName: String? = null
    private var playlistType = PlaylistType.PUBLIC

    // runtime variables
    private var playlistFeed = mutableListOf<StreamItem>()
    private var playlistAdapter: PlaylistAdapter? = null
    private var nextPage: String? = null
    private var isLoading = true
    private var isBookmarked = false

    // whether the playlist is shown from the local cache, e.g. due to no internet
    private var showingCachedPlaylist = false

    // whether the playlist is one of the user's local playlists, whose videos can
    // be reordered by dragging them around
    private var isLocalPlaylist = false
    private var isReorderMode = false
    private var reorderHandlerAttached = false

    // view models
    private val playlistViewModel: PlaylistViewModel by activityViewModels()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.PLAYLIST_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.PLAYLIST_SORT_ORDER, value)
            field = value
        }
    private val sortOptions by lazy { resources.getStringArray(R.array.playlistSortOptions) }
    private var recyclerViewState: Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = args.playlistId
        playlistType = args.playlistType
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.playlistRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlaylistBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.playlistProgress.isVisible = true

        lifecycleScope.launch(Dispatchers.IO) {
            // if the request fails, e.g. due to no internet connection, treat the
            // playlist as not bookmarked instead of crashing
            isBookmarked = runCatching {
                UserDataRepositoryHelper.userDataRepository.getPlaylistBookmark(playlistId) != null
            }.getOrDefault(false)

            withContext(Dispatchers.Main) {
                updateBookmarkRes()
            }
        }

        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.playlistRecView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
            }
        })

        fetchPlaylist()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateBookmarkRes() {
        binding.bookmark.setIconResource(
            if (isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined
        )
    }

    private fun fetchPlaylist() {
        lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    PlaylistsHelper.getPlaylist(playlistId, playlistType)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())

                // if the playlist couldn't be fetched, e.g. due to no internet connection,
                // fall back to the locally cached copy of the bookmarked playlist
                val cachedPlaylist = withContext(Dispatchers.IO) { loadCachedPlaylist() }
                val binding = _binding ?: return@launch
                binding.playlistProgress.isGone = true
                if (cachedPlaylist == null) {
                    binding.nothingHere.isVisible = true
                    return@launch
                }
                showPlaylist(cachedPlaylist, fromCache = true)
                return@launch
            }
            showPlaylist(response, fromCache = false)
        }
    }

    /**
     * Load the locally cached copy of the playlist, if there is one. Thumbnails of
     * downloaded videos and of a downloaded playlist are preferred over the online
     * ones, as the latter can't be loaded without an internet connection.
     */
    private suspend fun loadCachedPlaylist(): Playlist? {
        val cacheWithItems = Database.playlistCacheDao().getCacheWithItems(playlistId)
            ?: return null
        val playlist = cacheWithItems.toPlaylist()

        val videoIds = playlist.relatedStreams.mapNotNull { it.url?.toID() }
        val downloads = Database.downloadDao().getDownloadsByIds(videoIds)
            .associateBy { it.videoId }
        playlist.relatedStreams = playlist.relatedStreams.map { streamItem ->
            downloads[streamItem.url?.toID()]
                ?.thumbnailPath
                ?.let { streamItem.copy(thumbnail = it.toUri().toString()) }
                ?: streamItem
        }

        // if the playlist is downloaded as a playlist, use its locally stored cover
        val hasPlaylistDownload = Database.downloadDao()
            .getVideoIdsFromPlaylist(playlistId).isNotEmpty()
        if (hasPlaylistDownload) {
            val playlistDownload = runCatching {
                Database.downloadDao().getDownloadPlaylistById(playlistId)
            }.getOrNull()?.downloadPlaylist
            playlistDownload?.thumbnailPath?.let { localCover ->
                return playlist.copy(thumbnailUrl = localCover.toUri().toString())
            }
        }
        return playlist
    }

    private fun showPlaylist(response: Playlist, fromCache: Boolean) {
        lifecycleScope.launch {
            val binding = _binding ?: return@launch

            showingCachedPlaylist = fromCache
            playlistFeed = response.relatedStreams.toMutableList()
            nextPage = response.nextpage
            playlistName = response.name
            isLoading = false

            setPlaylistThumbnail(response.thumbnailUrl)

            binding.playlistProgress.isGone = true
            binding.playlistAppBar.isVisible = true
            binding.playlistRecView.isVisible = true
            binding.playlistName.text = response.name

            binding.playlistInfo.text = getChannelAndVideoString(response, response.videos)
            binding.playlistInfo.setOnClickListener {
                NavigationHelper.navigateChannel(requireContext(), response.uploaderUrl)
            }

            binding.playlistDescription.text = response.description?.parseAsHtml()
            // hide playlist description text view if not provided
            binding.playlistDescription.isGone = response.description.orEmpty().isBlank()

            playlistAdapter = PlaylistAdapter(playlistId) { streamItem ->
                startVideoItemPlayback(streamItem)
            }
            binding.playlistRecView.adapter = playlistAdapter

            // listen for playlist items to become deleted
            playlistAdapter!!.registerAdapterDataObserver(object :
                RecyclerView.AdapterDataObserver() {
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    if (positionStart == 0) {
                        ImageHelper.loadImage(
                            playlistFeed.firstOrNull()?.thumbnail.orEmpty(),
                            binding.thumbnail
                        )
                    }

                    binding.playlistInfo.text =
                        getChannelAndVideoString(response, playlistFeed.size)
                }
            })

            binding.playlistRecView.addOnBottomReachedListener {
                if (isLoading) return@addOnBottomReachedListener

                // append more playlists to the recycler view
                if (playlistType == PlaylistType.PUBLIC) {
                    fetchNextPage()
                }
            }

            // listener for swiping to the left or right
            if (playlistType != PlaylistType.PUBLIC) {
                binding.playlistRecView.setOnDismissListener { position ->
                    if (isReorderMode) {
                        // removing videos is disabled while reordering
                        playlistAdapter?.notifyItemChanged(position)
                        return@setOnDismissListener
                    }
                    removeFromPlaylist(position)
                }
            }

            showPlaylistVideos()

            playlistViewModel.searchQuery.observe(viewLifecycleOwner) {
                showPlaylistVideos()
            }

            // show playlist options
            binding.optionsMenu.setOnClickListener {
                val sheet = PlaylistOptionsBottomSheet()
                sheet.arguments = bundleOf(
                    IntentData.playlistId to playlistId,
                    IntentData.playlistName to playlistName.orEmpty(),
                    IntentData.playlistType to playlistType,
                    IntentData.playlistThumbnail to response.thumbnailUrl,
                )

                val fragmentManager = (context as BaseActivity).supportFragmentManager
                fragmentManager.setFragmentResultListener(
                    PlaylistOptionsBottomSheet.PLAYLIST_OPTIONS_REQUEST_KEY,
                    (context as BaseActivity)
                ) { _, resultBundle ->
                    val newPlaylistDescription =
                        resultBundle.getString(IntentData.playlistDescription)
                    val newPlaylistName =
                        resultBundle.getString(IntentData.playlistName)
                    val isPlaylistToBeDeleted =
                        resultBundle.getBoolean(IntentData.playlistTask)
                    val isReorderRequested =
                        resultBundle.getBoolean(IntentData.playlistReorder)

                    newPlaylistDescription?.let {
                        binding.playlistDescription.text = it
                        response.description = it
                    }

                    newPlaylistName?.let {
                        binding.playlistName.text = it
                        playlistName = it
                    }

                    if (isReorderRequested) {
                        enterReorderMode()
                    }

                    if (isPlaylistToBeDeleted) {
                        findNavController().popBackStack()
                        return@setFragmentResultListener
                    }
                }

                sheet.show(fragmentManager)
            }

            if (playlistFeed.isEmpty()) {
                binding.nothingHere.isVisible = true
                binding.playAll.isGone = true
            } else {
                binding.playAll.setOnClickListener {
                    startVideoItemPlayback(getSortedVideos().first().item)
                }
            }

            if (playlistType == PlaylistType.PUBLIC) {
                binding.bookmark.setOnClickListener {
                    isBookmarked = !isBookmarked
                    updateBookmarkRes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            if (!isBookmarked) {
                                UserDataRepositoryHelper.userDataRepository
                                    .deletePlaylistBookmark(playlistId)
                                PlaylistCacheHelper.deleteCache(playlistId)
                            } else {
                                val bookmark = response.toPlaylistBookmark(playlistId)
                                UserDataRepositoryHelper.userDataRepository
                                    .createPlaylistBookmark(bookmark)
                                // cache the playlist, so that it can be opened when offline
                                PlaylistCacheHelper.updateCache(playlistId, response)
                            }
                        }.onFailure { Log.e(TAG(), it.toString()) }
                    }
                }
            } else {
                // private playlist, means shuffle is possible because all videos are received at once
                isLocalPlaylist =
                    UserDataRepositoryHelper.userDataRepository is LocalUserDataRepository

                setShuffleButton()

                if (isLocalPlaylist && !reorderHandlerAttached) {
                    reorderHandlerAttached = true
                    attachPlaylistReorderHandler()
                }

                if (playlistFeed.isEmpty()) {
                    binding.sortBTN.isGone = true
                } else {
                    binding.sortBTN.isVisible = true
                    binding.sortBTN.setOnClickListener {
                        BaseBottomSheet().apply {
                            setSimpleItems(sortOptions.toList()) { index ->
                                selectedSortOrder = index
                                binding.sortBTN.text = sortOptions[index]
                                showPlaylistVideos()
                            }
                        }.show(childFragmentManager)
                    }
                }

                binding.sortBTN.text = sortOptions[selectedSortOrder]

            }

            // don't try to update the bookmark or the cache when showing the cached
            // playlist, e.g. because there is no internet connection
            if (!showingCachedPlaylist) {
                updatePlaylistBookmark(response)
            }
        }
    }

    private fun navigateVideo(streamItem: StreamItem) {
        NavigationHelper.navigateVideo(
            requireContext(),
            playerData = PlayerData(
                streamItem.url!!.toID(),
                playlistId = playlistId,
                keepQueue = true
            )
        )
    }

    private fun startVideoItemPlayback(streamItem: StreamItem) {
        if (playlistFeed.isEmpty()) return
        // don't navigate to the player while the playlist videos are being reordered
        if (isReorderMode) return

        // without an internet connection, only the downloaded videos can be played
        val videoId = streamItem.url?.toID()
        if (videoId != null && !NetworkHelper.isNetworkAvailable(requireContext())) {
            playDownloadedVersion(videoId)
            return
        }

        val sortedStreams = getSortedVideos()
        PlayingQueue.setStreams(sortedStreams.map { it.item })

        navigateVideo(streamItem)
    }

    /**
     * Play the downloaded version of the video offline, using the downloads of this
     * playlist as the playing queue if there are any.
     */
    private fun playDownloadedVersion(videoId: String) {
        lifecycleScope.launch {
            val (videoDownloaded, playlistDownloaded) = withContext(Dispatchers.IO) {
                Database.downloadDao().exists(videoId) to
                    Database.downloadDao().getVideoIdsFromPlaylist(playlistId).isNotEmpty()
            }
            if (!videoDownloaded) {
                context?.toastFromMainDispatcher(R.string.video_not_downloaded_offline)
                return@launch
            }

            NavigationHelper.navigateVideo(
                requireContext(),
                PlayerData(
                    videoId = videoId,
                    playlistId = playlistId.takeIf { playlistDownloaded },
                    downloadTab = if (playlistDownloaded) DownloadTab.PLAYLIST else DownloadTab.VIDEO,
                    isOffline = true
                )
            )
        }
    }

    /**
     * If the playlist is bookmarked, update its content if modified by the uploader
     */
    private suspend fun updatePlaylistBookmark(playlist: Playlist) {
        if (!isBookmarked) return
        withContext(Dispatchers.IO) {
            // update the playlist thumbnail and title if bookmarked
            val playlistBookmark =
                UserDataRepositoryHelper.userDataRepository.getPlaylistBookmark(playlistId)
                    ?: return@withContext
            if (playlistBookmark.thumbnailUrl != playlist.thumbnailUrl ||
                playlistBookmark.playlistName != playlist.name ||
                playlistBookmark.videos != playlist.videos
            ) {
                val bookmark = playlist.toPlaylistBookmark(playlistBookmark.playlistId)
                UserDataRepositoryHelper.userDataRepository.createPlaylistBookmark(bookmark)
                // the content of the playlist changed, refresh the offline cache
                PlaylistCacheHelper.updateCache(playlistId, playlist)
            } else if (Database.playlistCacheDao().getCache(playlistId) == null) {
                // bookmarked, but not cached yet, e.g. because it was bookmarked on an other device
                PlaylistCacheHelper.updateCache(playlistId, playlist)
            }
        }
    }

    private fun setShuffleButton() {
        if (playlistFeed.isEmpty()) {
            binding.bookmark.isGone = true
            return
        }
        binding.bookmark.isVisible = true
        binding.bookmark.setIconResource(R.drawable.ic_shuffle)
        binding.bookmark.text = getString(R.string.shuffle)
        binding.bookmark.setOnClickListener {
            val queue = playlistFeed.shuffled()
            PlayingQueue.setStreams(queue)

            navigateVideo(queue.firstOrNull() ?: return@setOnClickListener)
        }
    }

    /**
     * Let the videos of the local playlist be reordered by dragging them around.
     * Long pressing videos normally opens their options, thus the drag support is
     * only active while the reorder mode is enabled.
     */
    private fun attachPlaylistReorderHandler() {
        val dragCallback = object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return if (isReorderMode) {
                    makeFlag(
                        ItemTouchHelper.ACTION_STATE_DRAG,
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    )
                } else {
                    0
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!isReorderMode) return false
                val adapter = playlistAdapter ?: return false
                val from = viewHolder.absoluteAdapterPosition
                val to = target.absoluteAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                // reordering is only supported for the full, unfiltered playlist
                if (adapter.currentList.size != playlistFeed.size) return false

                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                // the video got dropped, persist the new order of the playlist
                val adapter = playlistAdapter ?: return
                if (isReorderMode) persistPlaylistOrder(adapter.currentList)
            }
        }
        ItemTouchHelper(dragCallback).attachToRecyclerView(binding.playlistRecView)
    }

    private fun enterReorderMode() {
        if (!isLocalPlaylist || playlistFeed.isEmpty() || isReorderMode) return

        isReorderMode = true
        // long pressing a video now starts dragging it instead of opening its options
        playlistAdapter?.longClicksEnabled = false
        // reordering applies to the natural playlist order, not to a sorted or filtered view
        if (selectedSortOrder != 0) {
            selectedSortOrder = 0
            binding.sortBTN.text = sortOptions[selectedSortOrder]
        }
        playlistViewModel.setQuery(null)
        binding.sortBTN.isGone = true

        binding.bookmark.isVisible = true
        binding.bookmark.setIconResource(R.drawable.ic_done)
        binding.bookmark.text = getString(R.string.done)
        binding.bookmark.setOnClickListener { exitReorderMode() }

        showPlaylistVideos()
    }

    private fun exitReorderMode() {
        if (!isReorderMode) return

        isReorderMode = false
        playlistAdapter?.longClicksEnabled = true
        binding.sortBTN.isVisible = playlistFeed.isNotEmpty()
        setShuffleButton()

        showPlaylistVideos()
    }

    /**
     * Store the new order of the local playlist after its videos got dragged around.
     */
    private fun persistPlaylistOrder(videos: List<PlaylistItem>) {
        if (videos.size != playlistFeed.size) return

        playlistFeed = videos.map { it.item }.toMutableList()
        // refreshes the adapter list, e.g. with the updated original item indices
        showPlaylistVideos()

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = Database.localPlaylistsDao()
            val playlistItems = dao.getPlaylistVideos(playlistId)
            val itemsByVideoId = playlistItems.associateBy { it.videoId }
            val reorderedItems = playlistFeed.mapNotNull { streamItem ->
                itemsByVideoId[streamItem.url?.toID()]
            }
            if (reorderedItems.size != playlistItems.size) return@launch

            dao.reorderPlaylistVideos(playlistId, reorderedItems)

            // keep the playlist thumbnail in sync with the new first video
            dao.getAll().firstOrNull { it.playlist.id.toString() == playlistId }
                ?.playlist?.let { playlist ->
                    reorderedItems.firstOrNull()?.thumbnailUrl?.let { newThumbnail ->
                        if (playlist.thumbnailUrl != newThumbnail) {
                            playlist.thumbnailUrl = newThumbnail
                            dao.updatePlaylist(playlist)
                        }
                    }
                }
        }
    }

    private fun getSortedVideos(): List<PlaylistItem> {
        // in addition to sorting, we need to make sure that the original index of the item
        // is still known. We solve this by wrapping the StreamItems into PlaylistItems that contain
        // an additional index attribute.
        val items = playlistFeed.mapIndexed { index, item -> PlaylistItem(item, index) }

        return when {
            selectedSortOrder in listOf(0, 1) || playlistType == PlaylistType.PUBLIC -> {
                items
            }

            selectedSortOrder in listOf(2, 3) -> {
                items.sortedBy { it.item.duration }
            }

            selectedSortOrder in listOf(4, 5) -> {
                items.sortedBy { it.item.title }
            }

            else -> throw IllegalArgumentException()
        }.let {
            if (selectedSortOrder % 2 == 0) it else it.reversed()
        }
    }

    private fun showPlaylistVideos() {
        var videos = getSortedVideos()

        val query = playlistViewModel.searchQuery.value
        if (!query.isNullOrEmpty()) {
            videos = videos.filter {
                it.item.title.orEmpty()
                    .contains(query, ignoreCase = true) || it.item.uploaderName.orEmpty()
                    .contains(query, ignoreCase = true)
            }
        }

        playlistAdapter?.submitList(videos)

        updatePlaylistDuration(videos)
    }

    @SuppressLint("StringFormatInvalid")
    private fun removeFromPlaylist(sortedFeedPosition: Int) {
        val playlistAdapter = playlistAdapter ?: return

        val (video, originalPlaylistPosition) = playlistAdapter.currentList[sortedFeedPosition]

        val updatedList = playlistAdapter.currentList.toMutableList()
        updatedList.removeAt(sortedFeedPosition)
        val fixedList = fixItemIndices(updatedList, originalPlaylistPosition, -1)
        playlistAdapter.submitList(fixedList)

        // try to remove the video from the playlist and show an undo snackbar if successful
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PlaylistsHelper.removeFromPlaylist(
                    playlistId,
                    video.url!!.toID(),
                    originalPlaylistPosition
                )

                val shortTitle = TextUtils.limitTextToLength(video.title.orEmpty(), 50)
                val snackBarText = getString(
                    R.string.successfully_removed_from_playlist,
                    shortTitle
                )

                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, snackBarText, Snackbar.LENGTH_LONG)
                        .setTextMaxLines(3)
                        .setAction(R.string.undo) {
                            reAddToPlaylist(
                                video,
                                sortedFeedPosition,
                                originalPlaylistPosition
                            )
                        }
                        .show()
                    updateInfo(updatedList)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    private fun updateInfo(updatedList: List<PlaylistItem>) {
        val playlistCount = updatedList.size
        binding.playlistInfo.text = getChannelAndVideoString(
            Playlist(name = playlistName, videos = playlistCount),
            playlistCount
        )
        updatePlaylistDuration(updatedList)
        setPlaylistThumbnail(updatedList.firstOrNull()?.item?.thumbnail)
    }

    private fun reAddToPlaylist(
        streamItem: StreamItem,
        sortedFeedPosition: Int,
        originalFeedPosition: Int
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PlaylistsHelper.addToPlaylist(playlistId, streamItem)

                val playlistAdapter = playlistAdapter ?: return@launch
                val updatedList = playlistAdapter.currentList.toMutableList()
                updatedList.add(sortedFeedPosition, PlaylistItem(streamItem, originalFeedPosition))
                val fixedList = fixItemIndices(updatedList, originalFeedPosition, +1)

                withContext(Dispatchers.Main) {
                    playlistAdapter.submitList(fixedList)
                    updateInfo(fixedList)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                context?.toastFromMainDispatcher(R.string.unknown_error)
            }
        }
    }

    /**
     * After removing or adding a video to a playlist, the original positions of all videos
     * after the removed/added one change by one.
     *
     * E.g., if you remove the video at index 7, all videos after it move one to the left (8 -> 7, 9 -> 8, ...).
     * In this example, the offset would be 1.
     *
     * I.e., this method adds the given offset to all videos with an originalPlaylistIndex > modifiedPosition.
     */
    private fun fixItemIndices(
        items: List<PlaylistItem>,
        modifiedPosition: Int,
        offset: Int
    ): List<PlaylistItem> {
        return items.map {
            if (it.originalPlaylistIndex > modifiedPosition) {
                it.copy(originalPlaylistIndex = it.originalPlaylistIndex + offset)
            } else {
                it
            }
        }
    }

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    private fun getChannelAndVideoString(playlist: Playlist, count: Int): String {
        if (count < 0) return playlist.uploader.orEmpty()
        if (playlist.uploader == null) return getString(R.string.videoCount, count)

        return getString(R.string.uploaderAndVideoCount, playlist.uploader, count)
    }

    private fun fetchNextPage() {
        if (nextPage == null || isLoading) return
        isLoading = true

        lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    // load locally stored playlists with the auth api
                    MediaServiceRepository.instance.getPlaylistNextPage(playlistId, nextPage!!)
                }
            } catch (e: Exception) {
                context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
                Log.e(TAG(), e.toString())
                return@launch
            }

            nextPage = response.nextpage
            val currentList = playlistAdapter?.currentList.orEmpty()
            val newList = currentList + response.relatedStreams.mapIndexed { index, item ->
                PlaylistItem(item, currentList.size + index)
            }
            playlistAdapter?.submitList(newList)
            updatePlaylistDuration(newList)
            isLoading = false

            // if the playlist is bookmarked, extend the offline cache by the new page
            if (isBookmarked && response.relatedStreams.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val positionOffset = Database.playlistCacheDao().getItemCount(playlistId)
                    Database.playlistCacheDao().insertItems(
                        response.relatedStreams.mapIndexed { index, item ->
                            item.toPlaylistCacheItem(playlistId, positionOffset + index)
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlaylistDuration(updatedList: List<PlaylistItem>) {
        val totalDuration = updatedList.sumOf { it.item.duration ?: 0 }
        binding.playlistDuration.text = DateUtils.formatElapsedTime(totalDuration) +
                if (nextPage != null) "+" else ""
    }

    // Update the Cover/Thumbnail of the playlist if the first video was removed
    private fun setPlaylistThumbnail(thumbnailUrl: String?) {
        if (!thumbnailUrl.isNullOrEmpty()) {
            ImageHelper.loadImage(thumbnailUrl, binding.thumbnail)
        } else {
            binding.thumbnail.setImageResource(R.drawable.ic_empty_playlist)
            binding.thumbnail.setPadding(64f.dpToPx())
            binding.thumbnail.setBackgroundColor(com.google.android.material.R.attr.colorSurface)
        }
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.playlistRecView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }
}
