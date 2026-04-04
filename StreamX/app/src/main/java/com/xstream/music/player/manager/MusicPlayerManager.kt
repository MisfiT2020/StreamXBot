package com.xstream.music.player.manager

import com.xstream.music.player.service.*
import com.xstream.music.player.manager.*
import com.xstream.music.ui.components.*
import com.xstream.music.realtime.websocket.*
import com.xstream.music.core.preferences.*
import com.xstream.music.core.cache.*
import com.xstream.music.core.utils.*
import com.xstream.music.data.model.*
import com.xstream.music.data.api.*
import com.xstream.music.R
import android.content.Context
import android.os.SystemClock
import timber.log.Timber
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import com.metrolist.innertube.YouTube

import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.preferences.PlaybackPreferences
import androidx.media3.datasource.TransferListener
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.core.utils.PlaybackException
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.JamPlayback
import com.xstream.music.data.model.Song
import com.xstream.music.realtime.websocket.JamWebSocketManager
import com.xstream.music.realtime.websocket.PresenceWebSocketManager
import okhttp3.OkHttpClient

@UnstableApi
class MusicPlayerManager(private val context: Context) : ViewModel() {

    val queue = mutableStateListOf<Song>()
    var currentIndex by mutableStateOf(0)

    val currentSong = mutableStateOf<Song?>(null)
    val isPlaying = mutableStateOf(false)
    val isLoading = mutableStateOf(false)
    val currentPosition = mutableStateOf(0L)
    val duration = mutableStateOf(0L)
    val repeatMode = mutableIntStateOf(Player.REPEAT_MODE_OFF)
    val shuffleMode = mutableStateOf(false)
    
    var jamId by mutableStateOf<String?>(null)
    private var jamHostUserId: Long? = null
    private var jamSyncJob: Job? = null
    var isLocallyPaused by mutableStateOf(false)
    
    val isHost: Boolean
        get() {
            val uid = AuthPreferences.getUser(context)?.id ?: return false
            return uid == jamHostUserId
        }
    
    
    private var initialSyncDone = false
    private var lastSyncedQueueIds: List<String> = emptyList()
    private var lastSyncedTrackId: String = ""
    private var isHandlingEnd = false
    private var positionUpdateJob: Job? = null
    private var lastSyncedStartedAt: Double = 0.0
    private var lastSyncedPositionSec: Double = 0.0
    private var lastSyncedIsPlaying: Boolean = false
    private var lastSyncedServerTimeSec: Double? = null
    private var lastJamSyncReceivedAtMs: Long = 0L
    private var lastSyncedDurationMs: Long? = null
    private var playbackRecoveryJob: Job? = null
    private var loadingStateJob: Job? = null
    private val playbackRetryCounts = mutableMapOf<String, Int>()
    @Volatile
    private var suppressJamMediaCommandUntilMs: Long = 0L
    @Volatile
    private var pendingLoadingState = false
    
    private val songCache = mutableMapOf<String, Song>()
    private val failedSongIds = mutableSetOf<String>()
    
    @Volatile
    var lastSeekTimeMs: Long = 0

    val player: ExoPlayer
        get() = getPlayer(context)

    init {
        activeManagerRef = WeakReference(this)
        restorePlaybackState()
        player.addListener(object : Player.Listener {
            fun updateSongAudioMetadata(song: Song, sampleRate: Int?, bitrateKbps: Int?) {
                val normalizedSampleRate = sampleRate?.takeIf { it > 0 }
                val normalizedBitrate = bitrateKbps?.takeIf { it > 0 }
                if (normalizedSampleRate == null && normalizedBitrate == null) return

                val updatedSong = song.copy(
                    samplingRateHz = normalizedSampleRate ?: song.samplingRateHz?.takeIf { it > 0 },
                    bitrateKbps = normalizedBitrate ?: song.bitrateKbps?.takeIf { it > 0 }
                )

                if (updatedSong == song) return

                currentSong.value = updatedSong
                val index = queue.indexOfFirst { it.id == song.id }
                if (index >= 0) {
                    queue[index] = updatedSong
                }
                Timber.d("Updated song metadata: sampleRate=$normalizedSampleRate, bitrate=$normalizedBitrate")
            }

            override fun onRepeatModeChanged(playerRepeatMode: Int) {
                repeatMode.intValue = playerRepeatMode
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleMode.value = shuffleModeEnabled
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                if (jamId == null) {
                    isPlaying.value = playing
                }
                currentSong.value?.id?.let { trackId ->
                    val pos = (if (player.currentPosition != C.TIME_UNSET) player.currentPosition else 0L) / 1000.0
                    PresenceWebSocketManager.sendListeningUpdate(
                        trackId = trackId,
                        isPlaying = playing,
                        positionSec = pos,
                        jamId = jamId
                    )
                }
                if (!playing && jamId == null) {
                    savePlaybackState()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                setPlayerLoading(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    clearPlaybackFailureState()
                    val playerDuration = player.duration
                    if (duration.value == 0L && playerDuration > 0 && playerDuration != C.TIME_UNSET) {
                        duration.value = playerDuration
                    }

                    currentSong.value?.let { song ->
                        if (song.id?.startsWith("yt_") == true) {
                            val videoId = song.id.removePrefix("yt_")
                            getFormatMetadata(videoId)?.let { (sampleRate, bitrate) ->
                                updateSongAudioMetadata(song, sampleRate, bitrate)
                            }
                        }
                    }

                    if (jamId != null && lastSyncedTrackId.isNotBlank() && player.currentMediaItem?.mediaId == lastSyncedTrackId) {
                        val playbackSnapshot = JamPlayback(
                            trackId = lastSyncedTrackId,
                            durationSec = lastSyncedDurationMs?.div(1000)?.toInt()?.takeIf { it > 0 },
                            positionSec = lastSyncedPositionSec,
                            startedAt = lastSyncedStartedAt,
                            isPlaying = lastSyncedIsPlaying
                        )
                        val targetPos = resolveJamPositionMs(
                            playback = playbackSnapshot,
                            referenceServerTimeSec = lastSyncedServerTimeSec,
                            syncReceivedAtMs = lastJamSyncReceivedAtMs,
                            durationMs = lastSyncedDurationMs
                        )
                        val currentPlayerPos = player.currentPosition.coerceAtLeast(0L)
                        val drift = abs(currentPlayerPos - targetPos)
                        if (!isLocallyPaused && drift > 500L) {
                            Timber.i("Jam Sync: correcting ready-state drift to $targetPos ms (drift=$drift)")
                            player.seekTo(targetPos.coerceAtLeast(0))
                        }
                        currentPosition.value = player.currentPosition.coerceAtLeast(0L)
                    }
                }
                
                if (state == Player.STATE_ENDED) {
                    handleJamPlaybackEnded()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (jamId == null) {
                    if (mediaItem != null) {
                        currentIndex = player.currentMediaItemIndex
                        updateCurrentSong()
                        savePlaybackState()
                    } else if (queue.isEmpty()) {
                        currentIndex = 0
                        updateCurrentSong()
                        savePlaybackState()
                    }
                }
                clearPlaybackFailureState()
                currentSong.value?.id?.let { trackId ->
                    val pos = (if (player.currentPosition != C.TIME_UNSET) player.currentPosition else 0L) / 1000.0
                    PresenceWebSocketManager.sendListeningUpdate(
                        trackId = trackId,
                        isPlaying = player.isPlaying,
                        positionSec = pos,
                        jamId = jamId
                    )
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val song = currentSong.value ?: return

                val audioFormat = tracks.groups.firstNotNullOfOrNull { group ->
                    if (group.type != C.TRACK_TYPE_AUDIO) return@firstNotNullOfOrNull null

                    (0 until group.length)
                        .firstOrNull { group.isTrackSelected(it) }
                        ?.let(group::getTrackFormat)
                } ?: return

                val sampleRate = audioFormat.sampleRate.takeIf { it != Format.NO_VALUE && it > 0 }
                val bitrateKbps = audioFormat.bitrate
                    .takeIf { it != Format.NO_VALUE && it > 0 }
                    ?.div(1000)

                if (song.id?.startsWith("yt_") == true) {
                    cacheFormatMetadata(song.id.removePrefix("yt_"), sampleRate, bitrateKbps)
                }

                updateSongAudioMetadata(song, sampleRate, bitrateKbps)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                Timber.d("onPositionDiscontinuity: reason=$reason, oldPos=${oldPosition.positionMs}, newPos=${newPosition.positionMs}")
                if (jamId == null && (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) {
                    savePlaybackState()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val currentMediaItem = player.currentMediaItem
                val streamUrl = currentMediaItem?.localConfiguration?.uri?.toString() ?: "unknown"
                Timber.e(error, "ExoPlayer Error: ${error.message}")
                Timber.e("Failed stream URL: $streamUrl")
                Timber.e("Track ID: ${currentSong.value?.id}, Title: ${currentSong.value?.title}")

                clearPlayerLoading()
                isPlaying.value = false
                cancelPlaybackRecovery()

                val trackId = currentSong.value?.id ?: "queue_index_$currentIndex"
                clearCachedMediaResource(trackId)
                if (trackId.startsWith("yt_") || trackId.startsWith("sc_")) {
                    clearCachedResolvedStreamUrl(trackId)
                }
                val retryCount = (playbackRetryCounts[trackId] ?: 0) + 1
                playbackRetryCounts[trackId] = retryCount

                if (jamId == null && queue.isNotEmpty() && retryCount <= 1) {
                    playbackRecoveryJob = viewModelScope.launch {
                        delay(1200)
                        if ((currentSong.value?.id ?: "queue_index_$currentIndex") == trackId &&
                            !player.isPlaying &&
                            player.playbackState != Player.STATE_BUFFERING
                        ) {
                            Timber.i("Attempting bounded player recovery for $trackId")
                            player.prepare()
                            player.play()
                        }
                    }
                    return
                }

                failedSongIds.add(trackId)
                val failedTitle = currentSong.value?.title ?: "track"
                viewModelScope.launch {
                    if (jamId == null && player.hasNextMediaItem() && currentIndex < queue.lastIndex) {
                        Timber.w("Skipping failed track after $retryCount attempts: $trackId")
                        android.widget.Toast.makeText(
                            context,
                            "Skipped unavailable track",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        playbackRetryCounts.remove(trackId)
                        player.seekToNext()
                        if (player.playbackState == Player.STATE_IDLE) {
                            player.prepare()
                        }
                        player.play()
                    } else {
                        Timber.w("Stopping playback after failure: $trackId")
                        stop()
                        clearPlayerLoading()
                        isPlaying.value = false
                        currentPosition.value = 0L
                        duration.value = 0L
                        android.widget.Toast.makeText(
                            context,
                            "Unable to play $failedTitle",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
        
        viewModelScope.launch {
            var lastSaveTime = System.currentTimeMillis()
            while (true) {
                if (jamId == null && player.isPlaying) {
                    val pos = player.currentPosition
                    if (pos >= 0 && pos != C.TIME_UNSET) {
                        currentPosition.value = pos
                    }
                    
                    
                    if (System.currentTimeMillis() - lastSaveTime > 10000) {
                        savePlaybackState()
                        lastSaveTime = System.currentTimeMillis()
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun savePlaybackState() {
        if (jamId != null) return
        val pos = player.currentPosition
        if (pos != C.TIME_UNSET) {
            PlaybackPreferences.savePlaybackState(context, queue.toList(), currentIndex, pos)
        }
    }

    private fun cancelPlaybackRecovery() {
        playbackRecoveryJob?.cancel()
        playbackRecoveryJob = null
    }

    private fun setPlayerLoading(loading: Boolean, immediate: Boolean = false) {
        pendingLoadingState = loading
        if (immediate) {
            loadingStateJob?.cancel()
            loadingStateJob = null
            if (isLoading.value != loading) {
                isLoading.value = loading
            }
            return
        }
        if (isLoading.value == loading) {
            loadingStateJob?.cancel()
            loadingStateJob = null
            return
        }
        loadingStateJob?.cancel()
        loadingStateJob = viewModelScope.launch {
            delay(if (loading) 220L else 140L)
            if (pendingLoadingState == loading) {
                isLoading.value = loading
            }
        }
    }

    private fun clearPlayerLoading() {
        setPlayerLoading(false, immediate = true)
    }

    private fun clearPlaybackFailureState(trackId: String? = currentSong.value?.id) {
        trackId?.let(playbackRetryCounts::remove)
        cancelPlaybackRecovery()
    }

    private fun restorePlaybackState() {
        val savedQueue = PlaybackPreferences.getSavedQueue(context)
        if (savedQueue.isNotEmpty()) {
            val savedIndex = PlaybackPreferences.getSavedIndex(context)
            val savedPosition = PlaybackPreferences.getSavedPosition(context)
            
            queue.addAll(savedQueue)
            currentIndex = savedIndex.coerceIn(0, queue.size - 1)
            updateCurrentSong()
            currentPosition.value = savedPosition 
            
            viewModelScope.launch {
                val apiBaseUrl = ApiPreferences.getApiUrl(context)
                val token = AuthPreferences.getUser(context)?.token
                
                player.stop()
                player.clearMediaItems()
                val queuedSongs = queue.toList()
                val mediaItems = queuedSongs.mapIndexed { index, song ->
                    buildMediaItem(
                        song = song,
                        apiBaseUrl = apiBaseUrl,
                        token = token,
                        preResolveStreamUrl = index == currentIndex
                    )
                }
                player.setMediaItems(mediaItems, currentIndex, savedPosition)
                player.prepare()
                player.playWhenReady = false 
                prefetchAdjacentSpecialStreams(queuedSongs, currentIndex, apiBaseUrl, token)
            }
        }
    }

    private fun updateCurrentSong(resetPosition: Boolean = true, overridePositionMs: Long? = null) {
        val song = queue.getOrNull(currentIndex)
        currentSong.value = song
        when {
            overridePositionMs != null -> currentPosition.value = overridePositionMs.coerceAtLeast(0L)
            resetPosition -> currentPosition.value = 0L
        }
        if (song != null) {
             Timber.d("Now playing track: ${song.title} - Thumbnail: ${song.coverUrl}")
             song.durationSec?.let { duration.value = it * 1000L } ?: run { duration.value = 0L }
        }
    }

    fun setQueueFromLatest(
        latestSongs: List<Song>, 
        startIndex: Int, 
        apiBaseUrl: String, 
        token: String? = null,
        initialPosMs: Long = 0,
        playWhenReady: Boolean = true
    ) {
        val id = jamId
        if (id != null) {
            android.widget.Toast.makeText(context, "Jam is going on", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        cancelPlaybackRecovery()
        playbackRetryCounts.clear()
        queue.clear()
        queue.addAll(latestSongs)
        currentIndex = startIndex
        updateCurrentSong()

        val currentSongId = latestSongs.getOrNull(startIndex)?.id
        val isFetchingStream = currentSongId?.startsWith("yt_") == true || currentSongId?.startsWith("sc_") == true
        if (isFetchingStream && latestSongs.getOrNull(startIndex)?.localPath == null) {
            
            setPlayerLoading(true)
        }

        viewModelScope.launch {
            // Stop the current track immediately when switching to any new track
            player.stop()
            player.clearMediaItems()
            
            val mediaItems = latestSongs.mapIndexed { index, song ->
                buildMediaItem(
                    song = song,
                    apiBaseUrl = apiBaseUrl,
                    token = token,
                    preResolveStreamUrl = index == startIndex
                )
            }
            
            player.setMediaItems(mediaItems, startIndex, initialPosMs)
            player.prepare()
            player.playWhenReady = playWhenReady
            prefetchAdjacentSpecialStreams(latestSongs, startIndex, apiBaseUrl, token)
        }
    }

    fun playNext(song: Song? = null) {
        if (song != null) {
            val id = jamId
            if (id != null) {
                song.id?.let { enqueueJamTrack(it, playNext = true) }
                return
            }

            val insertIndex = if (queue.isEmpty()) 0 else currentIndex + 1
            if (insertIndex <= queue.size) {
                queue.add(insertIndex, song)
                if (queue.size == 1) {
                    currentIndex = 0
                    updateCurrentSong()
                }
                val apiBaseUrl = ApiPreferences.getApiUrl(context)
                val token = AuthPreferences.getUser(context)?.token
                viewModelScope.launch {
                    val mediaItem = buildMediaItem(
                        song = song,
                        apiBaseUrl = apiBaseUrl,
                        token = token,
                        preResolveStreamUrl = true
                    )
                    withContext(Dispatchers.Main) {
                        player.addMediaItem(insertIndex, mediaItem)
                        if (queue.size == 1) {
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
            return
        }
        
        val id = jamId
        if (id != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val apiBaseUrl = ApiPreferences.getApiUrl(context)
                val token = AuthPreferences.getUser(context)?.token
                jamNext(apiBaseUrl, id, context, token)
            }
            return
        }
        if (player.hasNextMediaItem()) {
            player.seekToNext()
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
    }

    override fun onCleared() {
        if (activeManagerRef?.get() === this) {
            activeManagerRef = null
        }
        super.onCleared()
    }

    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return

        val id = jamId
        if (id != null) {
            enqueueJamTracks(songs, playNext = true)
            return
        }

        val songsToQueue = songs.filter { it.id != null || it.localPath != null }
        if (songsToQueue.isEmpty()) return

        val wasQueueEmpty = queue.isEmpty() || player.mediaItemCount == 0
        val insertIndex = if (queue.isEmpty()) 0 else currentIndex + 1
        queue.addAll(insertIndex, songsToQueue)
        if (wasQueueEmpty) {
            currentIndex = 0
            updateCurrentSong()
        }

        val apiBaseUrl = ApiPreferences.getApiUrl(context)
        val token = AuthPreferences.getUser(context)?.token
        viewModelScope.launch {
            val mediaItems = songsToQueue.mapIndexed { index, song ->
                buildMediaItem(
                    song = song,
                    apiBaseUrl = apiBaseUrl,
                    token = token,
                    preResolveStreamUrl = index == 0
                )
            }
            withContext(Dispatchers.Main) {
                if (wasQueueEmpty) {
                    player.setMediaItems(mediaItems, 0, 0L)
                    player.prepare()
                    player.play()
                } else {
                    player.addMediaItems(insertIndex, mediaItems)
                }
            }
            prefetchAdjacentSpecialStreams(songsToQueue, 0, apiBaseUrl, token)
        }
    }

    fun playPrevious() {
        val id = jamId
        if (id != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val apiBaseUrl = ApiPreferences.getApiUrl(context)
                val token = AuthPreferences.getUser(context)?.token
                jamPrevious(apiBaseUrl, id, context, token)
            }
            return
        }
        if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        } else {
             player.seekTo(0)
             if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
    }

    fun skipToQueueItem(index: Int) {
        if (index in queue.indices) {
            player.seekTo(index, 0)
            player.play()
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int, isHost: Boolean = false) {
        val id = jamId
        if (id != null) {
             val fullQueueIds = queue.toList().map { it.id ?: "" }.toMutableList()
             if (fromIndex in fullQueueIds.indices && toIndex in fullQueueIds.indices) {
                 val item = fullQueueIds.removeAt(fromIndex)
                 fullQueueIds.add(toIndex, item)
                 val jamQueueIds = if (fullQueueIds.isNotEmpty()) fullQueueIds.drop(1) else emptyList()
                 viewModelScope.launch {
                     val apiBaseUrl = ApiPreferences.getApiUrl(context)
                     val token = AuthPreferences.getUser(context)?.token
                     jamReorderQueue(apiBaseUrl, id, jamQueueIds, context, token)
                 }
             }
             return
        }
        
        if (fromIndex in queue.indices && toIndex in queue.indices) {
            val item = queue.removeAt(fromIndex)
            queue.add(toIndex, item)
            player.moveMediaItem(fromIndex, toIndex)
            if (currentIndex == fromIndex) {
                currentIndex = toIndex
            } else if (currentIndex in (toIndex..fromIndex)) {
                 currentIndex++
            } else if (currentIndex in (fromIndex..toIndex)) {
                 currentIndex--
            }
        }
    }

    fun playSong(
        song: Song, 
        apiBaseUrl: String, 
        token: String? = null,
        initialPosMs: Long = 0,
        playWhenReady: Boolean = true
    ) {
        val id = jamId
        if (id != null) {
            android.widget.Toast.makeText(context, "Jam is going on", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        setQueueFromLatest(listOf(song), 0, apiBaseUrl, token, initialPosMs, playWhenReady)
    }
    
    private suspend fun buildMediaItem(
        song: Song,
        apiBaseUrl: String,
        token: String? = null,
        preResolveStreamUrl: Boolean = false
    ): MediaItem {
        val streamUrl = resolvePlaybackStreamUrl(
            context = context,
            song = song,
            apiBaseUrl = apiBaseUrl,
            token = token,
            preResolveSpecialStreamUrl = preResolveStreamUrl
        )
        
        Timber.d("Building media item for track: ${song.title} - Stream URL: $streamUrl")
        Timber.d("Track ID: ${song.id}, Has token: ${token != null}")
        
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album ?: "")
            .setArtworkUri(song.coverUrl?.let { android.net.Uri.parse(it) })
            .build()

        val builder = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(mediaMetadata)
            .setMediaId(song.id ?: "")

        resolveMimeType(song.type, streamUrl)?.let(builder::setMimeType)
        if (streamUrl.contains(".m3u8") || streamUrl.startsWith("scstream:")) {
            builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }

        if (!streamUrl.contains(".m3u8")) {
            builder.setCustomCacheKey(song.id)
        }

        return builder.build()    }

    private fun prefetchAdjacentSpecialStreams(
        songs: List<Song>,
        startIndex: Int,
        apiBaseUrl: String,
        token: String?
    ) {
        val previewSongs = songs
            .drop((startIndex + 1).coerceAtMost(songs.size))
            .filter { it.id?.startsWith("yt_") == true || it.id?.startsWith("sc_") == true }
            .take(2)

        if (previewSongs.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            previewSongs.forEach { song ->
                runCatching {
                    resolvePlaybackStreamUrl(
                        context = context,
                        song = song,
                        apiBaseUrl = apiBaseUrl,
                        token = token,
                        preResolveSpecialStreamUrl = true
                    )
                }
            }
        }
    }

    private fun resolveMimeType(typeHint: String?, streamUrl: String): String? {
        val normalizedType = typeHint
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: streamUrl
                .substringAfterLast('.', "")
                .lowercase()
                .takeIf {
                    it.isNotBlank() &&
                        !streamUrl.startsWith("ytstream:") &&
                        !streamUrl.startsWith("scstream:")
                }

        return when (normalizedType) {
            null, "", "youtube", "soundcloud" -> null
            "flac", "audio/flac", "audio/x-flac", "audio/xflac" -> MimeTypes.AUDIO_FLAC
            "m4a", "mp4", "audio/m4a", "audio/mp4" -> MimeTypes.AUDIO_MP4
            "alac", "audio/alac" -> MimeTypes.AUDIO_ALAC
            "aac", "mp4a", "mp4a-latm", "audio/aac", "audio/mp4a-latm" -> MimeTypes.AUDIO_AAC
            "mp3", "audio/mpeg" -> MimeTypes.AUDIO_MPEG
            "wav", "audio/wav" -> MimeTypes.AUDIO_WAV
            "ogg", "audio/ogg" -> MimeTypes.AUDIO_OGG
            "opus", "audio/opus" -> MimeTypes.AUDIO_OPUS
            "webm", "audio/webm" -> MimeTypes.AUDIO_WEBM
            else -> {
                if (normalizedType.startsWith("audio/")) normalizedType else "audio/$normalizedType"
            }
        }
    }

    fun togglePlayPause(isHost: Boolean = false) {
        val id = jamId
        if (id != null) {
            if (isHost) {
                viewModelScope.launch(Dispatchers.IO) {
                    if (isPlaying.value) {
                        dispatchJamTransportCommand("pause")
                    } else {
                        dispatchJamTransportCommand("play")
                    }
                }
            } else {
                toggleLocalPlayPause()
            }
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }
    
    fun toggleRepeatMode() {
        val current = player.repeatMode
        val next = when (current) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = next
        repeatMode.intValue = next
    }

    fun toggleShuffleMode() {
        val next = !player.shuffleModeEnabled
        player.shuffleModeEnabled = next
        shuffleMode.value = next
    }
    
    fun seekTo(positionMs: Long) {
        val id = jamId
        if (id != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val apiBaseUrl = ApiPreferences.getApiUrl(context)
                val token = AuthPreferences.getUser(context)?.token
                jamSeek(apiBaseUrl, id, positionMs / 1000.0, context, token)
            }
            return
        }
        player.seekTo(positionMs)
        currentPosition.value = positionMs
    }

    fun stop() {
        clearPlaybackFailureState()
        player.stop()
        currentSong.value = null
    }

    fun clearPlaybackState() {
        PlaybackPreferences.clearPlaybackState(context)
        clearPlaybackFailureState()
        playbackRetryCounts.clear()
        player.stop()
        player.clearMediaItems()
        queue.clear()
        currentIndex = 0
        currentSong.value = null
        isPlaying.value = false
        clearPlayerLoading()
        currentPosition.value = 0L
        duration.value = 0L
    }

    fun sendManualUpdate() {
        currentSong.value?.id?.let { trackId ->
            val pos = (if (player.currentPosition != C.TIME_UNSET) player.currentPosition else 0L) / 1000.0
            PresenceWebSocketManager.sendListeningUpdate(
                trackId = trackId,
                isPlaying = player.isPlaying,
                positionSec = pos,
                jamId = jamId
            )
        }
    }
    
    fun addToQueue(song: Song) {
        queue.add(song)
    }

    fun addToJamQueue(trackId: String) {
        enqueueJamTrack(trackId, playNext = false)
    }

    private fun enqueueJamTrack(trackId: String, playNext: Boolean) {
        val id = jamId ?: return
        if (trackId.isBlank()) return

        val jamQueueSize = queue.drop(1).count { !it.id.isNullOrBlank() }
        viewModelScope.launch {
            val apiBaseUrl = ApiPreferences.getApiUrl(context)
            val token = AuthPreferences.getUser(context)?.token
            if (playNext) {
                val jamQueueIds = queue.drop(1).mapNotNull { it.id }.toMutableList()
                if (jamQueueIds.remove(trackId)) {
                    jamQueueIds.add(0, trackId)
                    jamReorderQueue(apiBaseUrl, id, jamQueueIds, context, token)
                } else {
                    jamAddQueue(apiBaseUrl, id, trackId, position = 0, context = context, token = token)
                }
            } else {
                jamAddQueue(
                    apiBaseUrl,
                    id,
                    trackId,
                    position = jamQueueSize,
                    context = context,
                    token = token
                )
            }
        }
    }

    fun playJamTrack(trackId: String) {
        val id = jamId ?: return
        val currentQueue = queue.toList()
        if (currentQueue.size <= 1) return 
        val index = currentQueue.indexOfFirst { it.id == trackId }
        if (index <= 0) return
        
        viewModelScope.launch {
            val apiBaseUrl = ApiPreferences.getApiUrl(context)
            val token = AuthPreferences.getUser(context)?.token
            val jamQueueIds = currentQueue.drop(1).mapNotNull { it.id }.toMutableList()
            if (jamQueueIds.remove(trackId)) {
                jamQueueIds.add(0, trackId)
                if (jamReorderQueue(apiBaseUrl, id, jamQueueIds, context, token)) {
                    jamNext(apiBaseUrl, id, context, token)
                }
            }
        }
    }

    fun toggleLocalPlayPause() {
        setLocalJamPauseState(paused = !isLocallyPaused, syncPlayer = true)
    }

    fun handleMediaControllerJamCommand(@Player.Command playerCommand: Int) {
        val id = jamId ?: return
        if (playerCommand != Player.COMMAND_PLAY_PAUSE || isJamMediaCommandSuppressed()) return

        if (isHost) {
            val jamIsPlaying = JamWebSocketManager.jamState.value?.playback?.isPlaying ?: lastSyncedIsPlaying
            suppressJamMediaCommands()
            viewModelScope.launch(Dispatchers.IO) {
                if (jamIsPlaying) {
                    dispatchJamTransportCommand("pause", jamIdOverride = id)
                } else {
                    dispatchJamTransportCommand("play", jamIdOverride = id)
                }
            }
        } else {
            val shouldPauseLocally = player.playWhenReady
            setLocalJamPauseState(paused = shouldPauseLocally, syncPlayer = false)
        }
    }

    fun handleJamPlaybackEnded() {
        val id = jamId ?: return
        if (!isHost || isHandlingEnd) return

        val hasQueuedTracks = JamWebSocketManager.jamState.value
            ?.queue
            ?.any { it.isNotBlank() }
            ?: queue.drop(1).any { !it.id.isNullOrBlank() }

        isHandlingEnd = true
        viewModelScope.launch(Dispatchers.IO) {
            ensureJamRealtimeConnection()
            val apiBaseUrl = ApiPreferences.getApiUrl(context)
            val token = AuthPreferences.getUser(context)?.token
            val handled = if (hasQueuedTracks) {
                Timber.d("Track ended in Jam, host advancing to next track")
                jamNext(apiBaseUrl, id, context, token)
            } else {
                Timber.d("End of jam queue reached, pausing playback")
                dispatchJamTransportCommand("pause", apiBaseUrl, token, id)
            }
            if (handled) {
                refreshJamStateFromServer(apiBaseUrl, token, id)
            } else {
                isHandlingEnd = false
            }
        }
    }

    fun ensureJamRealtimeConnection() {
        val id = jamId ?: return
        val token = AuthPreferences.getUser(context)?.token ?: return
        val apiBaseUrl = ApiPreferences.getApiUrl(context)
        if (apiBaseUrl.isBlank() || JamWebSocketManager.isConnected()) return
        val initialState = JamWebSocketManager.jamState.value?.takeIf { it.id == id }
        JamWebSocketManager.connect(apiBaseUrl, id, token, initialState)
    }

    suspend fun refreshJamStateFromServer(
        apiBaseUrl: String = ApiPreferences.getApiUrl(context),
        token: String? = AuthPreferences.getUser(context)?.token,
        jamIdOverride: String? = null
    ): Jam? {
        val id = jamIdOverride ?: jamId ?: return null
        if (apiBaseUrl.isBlank()) return null
        val response = fetchJam(apiBaseUrl, id, context, token)
        val jam = response.jam ?: return null
        val syncReceivedAtMs = System.currentTimeMillis()
        jamId = jam.id
        jamHostUserId = jam.hostUserId

        val playback = jam.playback
        val trackId = playback.trackId
        if (trackId.isNotBlank()) {
            val targetIds = listOf(trackId) + jam.queue
            val missingIds = targetIds.filter { !songCache.containsKey(it) && !failedSongIds.contains(it) }.distinct()
            if (missingIds.isNotEmpty()) {
                missingIds.forEach { missingId ->
                    val song = withContext(Dispatchers.IO) { fetchSong(apiBaseUrl, missingId, context, token) }
                    if (song != null) songCache[missingId] = song else failedSongIds.add(missingId)
                }
            }

            syncLocalQueueModel(targetIds)
            val jamDurationMs = songCache[trackId]?.durationSec?.times(1000L)
                ?: playback.durationSec?.times(1000L)
            val targetPos = resolveJamPositionMs(
                playback = playback,
                referenceServerTimeSec = jam.serverTime,
                syncReceivedAtMs = syncReceivedAtMs,
                durationMs = jamDurationMs
            )

            withContext(Dispatchers.Main) {
                suppressJamMediaCommands()
                val currentJamSong = queue.firstOrNull()
                if (currentJamSong != null) {
                    val mediaItem = buildMediaItem(currentJamSong, apiBaseUrl, token)
                    val currentMediaId = player.currentMediaItem?.mediaId
                    val needsReset =
                        currentMediaId != trackId ||
                            player.currentMediaItemIndex != 0 ||
                            player.mediaItemCount != 1 ||
                            player.playbackState == Player.STATE_ENDED ||
                            player.playbackState == Player.STATE_IDLE

                    if (needsReset) {
                        player.setMediaItem(mediaItem, targetPos.coerceAtLeast(0L))
                        player.prepare()
                    } else {
                        val drift = abs(player.currentPosition - targetPos)
                        if (drift > 1500L) {
                            player.seekTo(targetPos.coerceAtLeast(0L))
                        }
                    }

                    player.playWhenReady = playback.isPlaying && !isLocallyPaused
                    if (playback.isPlaying && !isLocallyPaused) {
                        if (!player.isPlaying) {
                            player.play()
                        }
                    } else if (player.isPlaying) {
                        player.pause()
                    }

                    currentPosition.value = targetPos.coerceAtLeast(0L)
                    isPlaying.value = playback.isPlaying && !isLocallyPaused
                    updateCurrentSong(resetPosition = false, overridePositionMs = targetPos)
                }
            }

            if (trackId != lastSyncedTrackId) {
                isHandlingEnd = false
            }
            lastSyncedQueueIds = targetIds
            lastSyncedTrackId = trackId
            lastSyncedStartedAt = playback.startedAt
            lastSyncedPositionSec = playback.positionSec
            lastSyncedIsPlaying = playback.isPlaying
            lastSyncedServerTimeSec = jam.serverTime
            lastJamSyncReceivedAtMs = syncReceivedAtMs
            lastSyncedDurationMs = jamDurationMs
            initialSyncDone = true
            startPositionUpdateLoop(playback, jam.serverTime, syncReceivedAtMs, jamDurationMs)
        }

        JamWebSocketManager.updateState(jam)
        if (!JamWebSocketManager.isConnected() && !token.isNullOrBlank()) {
            JamWebSocketManager.connect(apiBaseUrl, id, token, jam)
        }
        return jam
    }

    private fun suppressJamMediaCommands(durationMs: Long = 1500L) {
        suppressJamMediaCommandUntilMs = SystemClock.elapsedRealtime() + durationMs
    }

    private fun isJamMediaCommandSuppressed(): Boolean {
        return SystemClock.elapsedRealtime() < suppressJamMediaCommandUntilMs
    }

    private fun setLocalJamPauseState(paused: Boolean, syncPlayer: Boolean) {
        suppressJamMediaCommands()
        isLocallyPaused = paused
        if (paused) {
            if (syncPlayer) {
                if (player.isPlaying) player.pause()
                player.playWhenReady = false
            }
            isPlaying.value = false
            return
        }

        val shouldResume = lastSyncedIsPlaying
        if (syncPlayer) {
            if (shouldResume && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.playWhenReady = shouldResume
            if (shouldResume) {
                if (!player.isPlaying) {
                    player.play()
                }
            } else if (player.isPlaying) {
                player.pause()
            }
        }
        isPlaying.value = shouldResume
    }

    private suspend fun dispatchJamTransportCommand(
        action: String,
        apiBaseUrlOverride: String? = null,
        tokenOverride: String? = null,
        jamIdOverride: String? = null
    ): Boolean {
        val id = jamIdOverride ?: jamId ?: return false
        val apiBaseUrl = apiBaseUrlOverride ?: ApiPreferences.getApiUrl(context)
        val token = tokenOverride ?: AuthPreferences.getUser(context)?.token
        if (apiBaseUrl.isBlank()) return false

        ensureJamRealtimeConnection()

        return when (action) {
            "play" -> JamWebSocketManager.sendAction("play") || jamPlay(apiBaseUrl, id, context, token)
            "pause" -> JamWebSocketManager.sendAction("pause") || jamPause(apiBaseUrl, id, context, token)
            else -> false
        }
    }

    fun enableJamSync(apiBaseUrl: String, token: String) {
        if (jamSyncJob?.isActive == true) return
        Timber.d("Enabling Jam Sync")
        failedSongIds.clear()
        initialSyncDone = false
        lastSyncedQueueIds = emptyList()
        lastSyncedTrackId = ""
        
        jamSyncJob = viewModelScope.launch {
            JamWebSocketManager.jamState.collect { jam ->
                if (jam == null) return@collect
                
                val syncReceivedAtMs = System.currentTimeMillis()
                jamId = jam.id
                jamHostUserId = jam.hostUserId
                val playback = jam.playback
                val trackId = playback.trackId
                if (trackId.isBlank()) return@collect
                
                val targetIds = listOf(trackId) + jam.queue
                
                
                if (targetIds != lastSyncedQueueIds) {
                    val missingIds = targetIds.filter { !songCache.containsKey(it) && !failedSongIds.contains(it) }.distinct()
                    if (missingIds.isNotEmpty()) {
                        
                        missingIds.forEach { id ->
                            val song = withContext(Dispatchers.IO) { fetchSong(apiBaseUrl, id, context, token) }
                            if (song != null) songCache[id] = song
                            else failedSongIds.add(id)
                        }
                    }
                    syncLocalQueueModel(targetIds)
                    lastSyncedQueueIds = targetIds
                }
                
                val jamDurationMs = songCache[trackId]?.durationSec?.times(1000L)
                    ?: playback.durationSec?.times(1000L)
                val jamServerTimeSec = jam.serverTime

                val getTargetPosMs = {
                    resolveJamPositionMs(
                        playback = playback,
                        referenceServerTimeSec = jamServerTimeSec,
                        syncReceivedAtMs = syncReceivedAtMs,
                        durationMs = jamDurationMs
                    )
                }

                
                val isDifferentTrack = trackId != lastSyncedTrackId
                val playbackParamsChanged = playback.startedAt != lastSyncedStartedAt || 
                                           playback.positionSec != lastSyncedPositionSec || 
                                           playback.isPlaying != lastSyncedIsPlaying

                if (isDifferentTrack || !initialSyncDone) {
                    Timber.i("Jam Sync: New track or initial. Track: $trackId, Initial: ${!initialSyncDone}")
                    isHandlingEnd = false
                    val targetPos = getTargetPosMs()
                    
                    withContext(Dispatchers.Main) {
                        suppressJamMediaCommands()
                        val currentMediaId = player.currentMediaItem?.mediaId
                        if (currentMediaId == trackId) {
                            Timber.d("Jam Sync: Player already on correct track.")
                            syncJamCurrentTrackOnly(apiBaseUrl, token)
                            val drift = abs(player.currentPosition - targetPos)
                            val seekThresholdMs = if (playback.isPlaying) 1500L else 250L
                            if (drift > seekThresholdMs) {
                                player.seekTo(targetPos.coerceAtLeast(0))
                            }
                        } else {
                            Timber.d("Jam Sync: Switching ExoPlayer track to $trackId at $targetPos")
                            val currentJamSong = queue.firstOrNull()
                            if (currentJamSong != null) {
                                val mediaItem = buildMediaItem(currentJamSong, apiBaseUrl, token)
                                player.setMediaItem(mediaItem, targetPos.coerceAtLeast(0))
                                player.prepare()
                            }
                        }
                        player.playWhenReady = playback.isPlaying && !isLocallyPaused
                        if (!playback.isPlaying) {
                            player.pause()
                        }
                        currentPosition.value = targetPos.coerceAtLeast(0)
                        isPlaying.value = playback.isPlaying && !isLocallyPaused
                        updateCurrentSong(resetPosition = false, overridePositionMs = targetPos)
                    }
                    lastSyncedTrackId = trackId
                    initialSyncDone = true
                } else if (playbackParamsChanged) {
                    Timber.d("Jam Sync: Playback params changed.")
                    val targetPos = getTargetPosMs()
                    withContext(Dispatchers.Main) {
                        suppressJamMediaCommands()
                        if (!isLocallyPaused) {
                            player.playWhenReady = playback.isPlaying
                            isPlaying.value = playback.isPlaying
                            if (playback.isPlaying) {
                                if (player.playbackState == Player.STATE_IDLE) {
                                    player.prepare()
                                }
                                if (!player.isPlaying) {
                                    player.play()
                                }
                            } else if (player.isPlaying) {
                                player.pause()
                            }
                            
                            val drift = abs(player.currentPosition - targetPos)
                            val seekThresholdMs = if (playback.isPlaying) 1500L else 250L
                            if (playback.positionSec != lastSyncedPositionSec || playback.startedAt != lastSyncedStartedAt || drift > seekThresholdMs) {
                                Timber.i("Jam Sync: Seeking to $targetPos (Host moved or drift)")
                                player.seekTo(targetPos.coerceAtLeast(0))
                            }
                        }
                        currentPosition.value = targetPos.coerceAtLeast(0)
                    }
                } else {
                    
                    
                    withContext(Dispatchers.Main) {
                        syncExoPlayerQueueOnly(apiBaseUrl, token)
                    }
                }
                
                if (isDifferentTrack || playbackParamsChanged || !initialSyncDone) {
                    lastSyncedStartedAt = playback.startedAt
                    lastSyncedPositionSec = playback.positionSec
                    lastSyncedIsPlaying = playback.isPlaying
                    lastSyncedServerTimeSec = jamServerTimeSec
                    lastJamSyncReceivedAtMs = syncReceivedAtMs
                    lastSyncedDurationMs = jamDurationMs

                    startPositionUpdateLoop(playback, jamServerTimeSec, syncReceivedAtMs, jamDurationMs)
                }
            }
        }
    }

    private fun enqueueJamTracks(songs: List<Song>, playNext: Boolean) {
        val id = jamId ?: return
        val requestedTrackIds = songs.mapNotNull { it.id?.takeIf(String::isNotBlank) }.distinct()
        if (requestedTrackIds.isEmpty()) return

        viewModelScope.launch {
            val apiBaseUrl = ApiPreferences.getApiUrl(context)
            val token = AuthPreferences.getUser(context)?.token
            val ensuredQueueIds = queue
                .drop(1)
                .mapNotNull { it.id?.takeIf(String::isNotBlank) }
                .toMutableList()

            requestedTrackIds.forEach { trackId ->
                if (!ensuredQueueIds.contains(trackId)) {
                    val added = jamAddQueue(
                        apiBaseUrl,
                        id,
                        trackId,
                        position = ensuredQueueIds.size,
                        context = context,
                        token = token
                    )
                    if (added) {
                        ensuredQueueIds.add(trackId)
                    }
                }
            }

            if (!playNext) return@launch

            val promotedTrackIds = requestedTrackIds.filter { ensuredQueueIds.contains(it) }
            if (promotedTrackIds.isEmpty()) return@launch

            val promotedSet = promotedTrackIds.toSet()
            val reorderedQueueIds = promotedTrackIds + ensuredQueueIds.filterNot { promotedSet.contains(it) }
            if (reorderedQueueIds != ensuredQueueIds) {
                jamReorderQueue(apiBaseUrl, id, reorderedQueueIds, context, token)
            }
        }
    }

    private fun resolveJamPositionMs(
        playback: JamPlayback,
        referenceServerTimeSec: Double?,
        syncReceivedAtMs: Long,
        durationMs: Long? = null
    ): Long {
        val basePositionMs = (playback.positionSec * 1000).toLong().coerceAtLeast(0L)
        val syncReferenceSec = referenceServerTimeSec ?: (syncReceivedAtMs / 1000.0)
        val localElapsedSec = (System.currentTimeMillis() - syncReceivedAtMs).coerceAtLeast(0L) / 1000.0

        val livePositionMs = if (playback.isPlaying) {
            val liveReferenceSec = syncReferenceSec + localElapsedSec
            val resolvedPositionSec = if (playback.startedAt > 0.0) {
                playback.positionSec + (liveReferenceSec - playback.startedAt).coerceAtLeast(0.0)
            } else {
                playback.positionSec + localElapsedSec
            }
            (resolvedPositionSec * 1000.0).toLong().coerceAtLeast(0L)
        } else {
            basePositionMs
        }
        return durationMs?.takeIf { it > 0 }?.let { livePositionMs.coerceAtMost(it) } ?: livePositionMs
    }

    private fun syncLocalQueueModel(targetIds: List<String>) {
        val newSongs = targetIds.mapNotNull { songCache[it] }
        if (newSongs.isEmpty()) return
        
        
        if (queue.isNotEmpty() && newSongs.isNotEmpty() && queue[0].id == newSongs[0].id) {
            while (queue.size > newSongs.size) queue.removeAt(queue.size - 1)
            for (i in 1 until newSongs.size) {
                if (i < queue.size) { if (queue[i].id != newSongs[i].id) queue[i] = newSongs[i] }
                else queue.add(newSongs[i])
            }
        } else {
            queue.clear()
            queue.addAll(newSongs)
            currentIndex = 0
            updateCurrentSong(resetPosition = jamId == null)
        }
    }

    private fun syncExoPlayerQueueOnly(apiBaseUrl: String, token: String?) {
        if (jamId == null || queue.isEmpty()) return
        
        viewModelScope.launch {
            val currentJamSong = queue.firstOrNull() ?: return@launch
            val targetMediaItem = buildMediaItem(currentJamSong, apiBaseUrl, token)
            
            withContext(Dispatchers.Main) {
                with(player) {
                    val currentMediaId = currentMediaItem?.mediaId
                    val needsReset =
                        mediaItemCount != 1 ||
                        currentMediaItemIndex != 0 ||
                        currentMediaId != targetMediaItem.mediaId

                    if (!needsReset) return@withContext

                    Timber.d("Jam Sync: Aligning ExoPlayer to current jam track only")
                    val preservedPosition = if (currentMediaId == targetMediaItem.mediaId) {
                        currentPosition.coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    setMediaItem(targetMediaItem, preservedPosition)
                    prepare()
                }
            }
        }
    }

    private suspend fun syncJamCurrentTrackOnly(apiBaseUrl: String, token: String?) {
        val currentJamSong = queue.firstOrNull() ?: return
        val targetMediaItem = buildMediaItem(currentJamSong, apiBaseUrl, token)
        withContext(Dispatchers.Main) {
            val currentMediaId = player.currentMediaItem?.mediaId
            val needsReset =
                player.mediaItemCount != 1 ||
                player.currentMediaItemIndex != 0 ||
                currentMediaId != targetMediaItem.mediaId
            if (!needsReset) return@withContext

            val preservedPosition = if (currentMediaId == targetMediaItem.mediaId) {
                player.currentPosition.coerceAtLeast(0L)
            } else {
                0L
            }
            Timber.d("Jam Sync: Trimming local player queue to current jam track")
            player.setMediaItem(targetMediaItem, preservedPosition)
            player.prepare()
        }
    }

    private fun startPositionUpdateLoop(
        playback: JamPlayback,
        referenceServerTimeSec: Double?,
        syncReceivedAtMs: Long,
        durationMs: Long? = null
    ) {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (true) {
                val liveTargetPosMs = resolveJamPositionMs(
                    playback = playback,
                    referenceServerTimeSec = referenceServerTimeSec,
                    syncReceivedAtMs = syncReceivedAtMs,
                    durationMs = durationMs
                )
                withContext(Dispatchers.Main) {
                    val isCurrentJamItemLoaded = player.currentMediaItem?.mediaId == playback.trackId &&
                        player.playbackState != Player.STATE_IDLE &&
                        player.playbackState != Player.STATE_ENDED

                    if (isCurrentJamItemLoaded) {
                        val actualPlayerPosition = player.currentPosition.coerceAtLeast(0L)
                        currentPosition.value = actualPlayerPosition

                        if (!isLocallyPaused && playback.isPlaying && player.playbackState == Player.STATE_READY) {
                            val drift = abs(actualPlayerPosition - liveTargetPosMs)
                            if (drift > 1500L) {
                                Timber.i("Jam Sync: correcting live drift to $liveTargetPosMs ms (drift=$drift)")
                                player.seekTo(liveTargetPosMs.coerceAtLeast(0))
                                currentPosition.value = liveTargetPosMs.coerceAtLeast(0)
                            }
                        }
                    } else {
                        currentPosition.value = liveTargetPosMs.coerceAtLeast(0)
                    }
                    if (isLocallyPaused) {
                        isPlaying.value = false
                        if (player.isPlaying) player.pause()
                    } else {
                        isPlaying.value = playback.isPlaying
                        if (playback.isPlaying != player.playWhenReady) player.playWhenReady = playback.isPlaying
                    }
                }
                delay(1000)
            }
        }
    }
    
    fun disableJamSync() {
        jamSyncJob?.cancel()
        jamSyncJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        jamId = null
        isLocallyPaused = false
        initialSyncDone = false
        lastSyncedQueueIds = emptyList()
        lastSyncedTrackId = ""
        lastSyncedStartedAt = 0.0
        lastSyncedPositionSec = 0.0
        lastSyncedIsPlaying = false
        lastSyncedServerTimeSec = null
        lastJamSyncReceivedAtMs = 0L
        lastSyncedDurationMs = null
    }

    companion object {
        private var sharedPlayer: ExoPlayer? = null
        private var simpleCache: SimpleCache? = null
        private var activeManagerRef: WeakReference<MusicPlayerManager>? = null
        
        private val formatMetadataCache = mutableMapOf<String, Pair<Int?, Int?>>() // videoId -> (sampleRate, bitrate)

        fun getActiveManager(): MusicPlayerManager? = activeManagerRef?.get()
        
        fun cacheFormatMetadata(videoId: String, sampleRate: Int?, bitrate: Int?) {
            formatMetadataCache[videoId] = Pair(
                sampleRate?.takeIf { it > 0 },
                bitrate?.takeIf { it > 0 }
            )
        }
        
        fun getFormatMetadata(videoId: String): Pair<Int?, Int?>? {
            return formatMetadataCache[videoId]
        }

        private data class CachedResolvedStreamUrl(
            val url: String,
            val requestHeaders: Map<String, String> = emptyMap(),
            val cachedAtMs: Long
        )

        private const val RESOLVED_STREAM_URL_TTL_MS = 10 * 60 * 1000L
        private const val PLAYBACK_HTTP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private val resolvedStreamUrlCache = mutableMapOf<String, CachedResolvedStreamUrl>()

        private fun shouldUsePlaybackProxy(host: String?): Boolean {
            val normalizedHost = host?.lowercase()?.trim().orEmpty()
            if (normalizedHost.isBlank()) return false

            return normalizedHost == "youtube.com" ||
                normalizedHost.endsWith(".youtube.com") ||
                normalizedHost.endsWith(".googlevideo.com") ||
                normalizedHost.endsWith(".ytimg.com") ||
                normalizedHost.endsWith(".googleusercontent.com")
        }

        private fun createPlaybackProxySelector(): ProxySelector {
            val playbackProxy = YouTube.proxy

            return object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    if (playbackProxy == null || !shouldUsePlaybackProxy(uri.host)) {
                        return listOf(Proxy.NO_PROXY)
                    }
                    return listOf(playbackProxy)
                }

                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                    Timber.w(ioe, "Playback proxy connection failed for $uri")
                }
            }
        }

        private fun createPlaybackHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .proxySelector(createPlaybackProxySelector())
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    val originalRequest = chain.request()
                    val requestBuilder = originalRequest.newBuilder()

                    if (originalRequest.header("User-Agent").isNullOrBlank()) {
                        requestBuilder.header("User-Agent", PLAYBACK_HTTP_USER_AGENT)
                    }

                    if (originalRequest.url.host.contains("ngrok")) {
                        requestBuilder.header("ngrok-skip-browser-warning", "true")
                    }

                    YouTube.proxyAuth
                        ?.takeIf {
                            shouldUsePlaybackProxy(originalRequest.url.host) &&
                                it.isNotBlank() &&
                                originalRequest.header("Proxy-Authorization").isNullOrBlank()
                        }
                        ?.let { requestBuilder.header("Proxy-Authorization", it) }

                    chain.proceed(requestBuilder.build())
                }
                .build()
        }

        private fun shouldUseCachedPlaybackSource(dataSpec: DataSpec): Boolean {
            val cacheKey = dataSpec.key.orEmpty()
            if (cacheKey.startsWith("yt_") || cacheKey.startsWith("sc_")) {
                return true
            }

            return when (dataSpec.uri.scheme?.lowercase()) {
                "ytstream", "scstream" -> true
                else -> false
            }
        }

        private class RoutingPlaybackDataSource(
            private val directDataSource: DataSource,
            private val cachedDataSource: DataSource
        ) : DataSource {
            private var selectedDataSource: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) {
                directDataSource.addTransferListener(transferListener)
                cachedDataSource.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val delegate = if (shouldUseCachedPlaybackSource(dataSpec)) {
                    cachedDataSource
                } else {
                    directDataSource
                }
                selectedDataSource = delegate
                return delegate.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val delegate = selectedDataSource
                    ?: throw IllegalStateException("RoutingPlaybackDataSource not opened")
                return delegate.read(buffer, offset, length)
            }

            override fun getUri(): android.net.Uri? = selectedDataSource?.uri

            override fun getResponseHeaders(): Map<String, List<String>> =
                selectedDataSource?.responseHeaders ?: emptyMap()

            override fun close() {
                val delegate = selectedDataSource
                selectedDataSource = null
                delegate?.close()
            }
        }

        private class RoutingPlaybackDataSourceFactory(
            private val directFactory: DataSource.Factory,
            private val cachedFactory: DataSource.Factory
        ) : DataSource.Factory {
            override fun createDataSource(): DataSource {
                return RoutingPlaybackDataSource(
                    directDataSource = directFactory.createDataSource(),
                    cachedDataSource = cachedFactory.createDataSource()
                )
            }
        }

        private fun invalidStreamUrl(cacheKey: String): String = "invalid://$cacheKey"

        @Synchronized
        private fun getCachedResolvedStream(cacheKey: String): CachedResolvedStreamUrl? {
            val cached = resolvedStreamUrlCache[cacheKey] ?: return null
            if (SystemClock.elapsedRealtime() - cached.cachedAtMs > RESOLVED_STREAM_URL_TTL_MS) {
                resolvedStreamUrlCache.remove(cacheKey)
                return null
            }
            return cached
        }

        @Synchronized
        private fun getCachedResolvedStreamUrl(cacheKey: String): String? {
            return getCachedResolvedStream(cacheKey)?.url
        }

        @Synchronized
        private fun putCachedResolvedStreamUrl(
            cacheKey: String,
            url: String,
            requestHeaders: Map<String, String> = emptyMap()
        ) {
            resolvedStreamUrlCache[cacheKey] = CachedResolvedStreamUrl(
                url = url,
                requestHeaders = requestHeaders,
                cachedAtMs = SystemClock.elapsedRealtime()
            )
        }

        @Synchronized
        private fun clearCachedResolvedStreamUrl(cacheKey: String) {
            resolvedStreamUrlCache.remove(cacheKey)
        }

        @Synchronized
        private fun clearCachedMediaResource(cacheKey: String) {
            if (cacheKey.isBlank()) return
            runCatching { simpleCache?.removeResource(cacheKey) }
                .onFailure { Timber.w(it, "Failed to clear cached media resource for $cacheKey") }
        }

        suspend fun resolvePlaybackStreamUrl(
            context: Context,
            song: Song,
            apiBaseUrl: String,
            token: String?,
            preResolveSpecialStreamUrl: Boolean = false
        ): String {
            val localSong = song.id?.let { DownloadHelper.getDownloadedSongById(context, it) }
            if (song.localPath != null) return song.localPath
            if (localSong?.localPath != null) return localSong.localPath

            val songId = song.id
            if (songId?.startsWith("yt_") == true) {
                val cacheKey = songId
                if (preResolveSpecialStreamUrl && getCachedResolvedStream(cacheKey) == null) {
                    val resolved = getYouTubePlaybackData(songId.removePrefix("yt_"), context)
                        ?.takeIf { it.streamUrl.isNotBlank() }
                    if (resolved != null) {
                        putCachedResolvedStreamUrl(
                            cacheKey = cacheKey,
                            url = resolved.streamUrl,
                            requestHeaders = resolved.requestHeaders
                        )
                    }
                }
                return "ytstream:${songId.removePrefix("yt_")}"
            }

            if (songId?.startsWith("sc_") == true) {
                val cacheKey = songId
                if (preResolveSpecialStreamUrl && getCachedResolvedStreamUrl(cacheKey) == null) {
                    val resolved = getSoundcloudStreamUrl(apiBaseUrl, songId)
                        ?.takeIf { it.isNotBlank() }
                    if (resolved != null) {
                        putCachedResolvedStreamUrl(cacheKey, resolved)
                    }
                }
                return "scstream:${songId.removePrefix("sc_")}"
            }

            return if (token != null) {
                "$apiBaseUrl/tracks/${song.id}/stream?token=$token"
            } else {
                "$apiBaseUrl/tracks/${song.id}/stream"
            }
        }

        @UnstableApi
        @Synchronized
        private fun getCache(context: Context): SimpleCache {
            if (simpleCache == null) {
                val cacheDir = java.io.File(context.cacheDir, "media_cache")
                val evictor = LeastRecentlyUsedCacheEvictor(200 * 1024 * 1024) 
                val databaseProvider = StandaloneDatabaseProvider(context)
                simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
            }
            return simpleCache!!
        }

        @UnstableApi
        fun getPlayer(context: Context): ExoPlayer {
            if (sharedPlayer == null) {
                val extractorsFactory = DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true)
                    .setConstantBitrateSeekingAlwaysEnabled(true)

                val isAggressive = DataCache.isAggressiveStreamingEnabled(context)
                val streamingMode = DataCache.getStreamingMode(context)

                val minBufferMs: Int
                val maxBufferMs: Int
                val bufferForPlaybackMs = 1500
                val bufferForPlaybackAfterRebufferMs = 3000
                val backBufferDurationMs: Int

                if (isAggressive) {
                    minBufferMs = 3600000 
                    maxBufferMs = 3600000 
                    backBufferDurationMs = 3600000
                } else if (streamingMode == "Saver") {
                    minBufferMs = 15000
                    maxBufferMs = 30000
                    backBufferDurationMs = 15000
                } else {
                    
                    minBufferMs = 50000
                    maxBufferMs = 100000
                    backBufferDurationMs = 30000
                }
                
                val loadControlBuilder = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        minBufferMs, 
                        maxBufferMs, 
                        bufferForPlaybackMs, 
                        bufferForPlaybackAfterRebufferMs 
                    )
                    .setBackBuffer(backBufferDurationMs, true) 
                
                if (isAggressive) {
                    
                    loadControlBuilder.setTargetBufferBytes(100 * 1024 * 1024)
                }

                val loadControl = loadControlBuilder.build()

                
                val renderersFactory = DefaultRenderersFactory(context.applicationContext)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)

                val okHttpDataSourceFactory = OkHttpDataSource.Factory(createPlaybackHttpClient())

                val upstreamFactory = DefaultDataSource.Factory(context.applicationContext, okHttpDataSourceFactory)

                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(getCache(context))
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                val routingDataSourceFactory = RoutingPlaybackDataSourceFactory(
                    directFactory = upstreamFactory,
                    cachedFactory = cacheDataSourceFactory
                )

                val resolvingDataSourceFactory = ResolvingDataSource.Factory(
                    routingDataSourceFactory,
                    object : ResolvingDataSource.Resolver {
                        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                            val uri = dataSpec.uri
                            val scheme = uri.scheme
                            if (scheme == "ytstream") {
                                val videoId = uri.schemeSpecificPart
                                val cacheKey = "yt_$videoId"
                                val resolvedStream = getCachedResolvedStream(cacheKey) ?: runBlocking(Dispatchers.IO) {
                                    getYouTubePlaybackData(videoId, context)
                                        ?.takeIf { it.streamUrl.isNotBlank() }
                                        ?.also {
                                            putCachedResolvedStreamUrl(
                                                cacheKey = cacheKey,
                                                url = it.streamUrl,
                                                requestHeaders = it.requestHeaders
                                            )
                                        }
                                    getCachedResolvedStream(cacheKey)
                                } ?: CachedResolvedStreamUrl(
                                    url = invalidStreamUrl(cacheKey),
                                    cachedAtMs = SystemClock.elapsedRealtime()
                                )
                                val resolvedDataSpec = dataSpec.withUri(android.net.Uri.parse(resolvedStream.url))
                                val ytRequestHeaders = resolvedStream.requestHeaders.toMutableMap()
                                return if (ytRequestHeaders.isEmpty()) {
                                    resolvedDataSpec
                                } else {
                                    resolvedDataSpec.withAdditionalHeaders(ytRequestHeaders)
                                }
                            } else if (scheme == "scstream") {
                                val trackId = uri.schemeSpecificPart
                                val cacheKey = "sc_$trackId"
                                val streamUrl = getCachedResolvedStreamUrl(cacheKey) ?: runBlocking(Dispatchers.IO) {
                                    getSoundcloudStreamUrl(ApiPreferences.getApiUrl(context), cacheKey)
                                        ?.takeIf { it.isNotBlank() }
                                        ?.also { putCachedResolvedStreamUrl(cacheKey, it) }
                                } ?: invalidStreamUrl(cacheKey)
                                return dataSpec.withUri(android.net.Uri.parse(streamUrl))
                            }
                            return dataSpec
                        }
                    }
                )

                
                val mediaSourceFactory = DefaultMediaSourceFactory(context.applicationContext, extractorsFactory)
                    .setDataSourceFactory(resolvingDataSourceFactory)

                sharedPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setLoadControl(loadControl)
                    .build()
                    .apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                        setHandleAudioBecomingNoisy(true)
                        addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger("ExoPlayerDetails"))
                    }
            }
            return sharedPlayer!!
        }
        fun releasePlayer() {
            sharedPlayer?.release()
            sharedPlayer = null
        }
    }
}
