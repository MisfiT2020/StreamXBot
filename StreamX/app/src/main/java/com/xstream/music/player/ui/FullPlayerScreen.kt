package com.xstream.music.player.ui

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
import timber.log.Timber
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.runtime.collectAsState
import kotlin.math.absoluteValue
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.cache.ImageMemoryCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.Song
import com.xstream.music.player.manager.MusicPlayerManager
import com.xstream.music.realtime.websocket.JamWebSocketManager
import com.xstream.music.ui.components.AddToPlaylistBottomSheet
import com.xstream.music.ui.theme.SFProDisplayFontFamily
import androidx.core.view.WindowCompat
import androidx.core.graphics.ColorUtils

data class LyricLine(
    val timeMs: Long,
    val text: String
)

private const val FULL_PLAYER_COVER_REQUEST_SIZE_PX = 1024

fun parseLyrics(lyricsText: String): List<LyricLine> {
    val lines = lyricsText.lines()
    val lyricLines = mutableListOf<LyricLine>()
    
    for (line in lines) {
        val regex = """\[(\d{2}):(\d{2})\.(\d{2})\]\s*(.*)""".toRegex()
        val match = regex.find(line)
        
        if (match != null) {
            val (minutes, seconds, centiseconds, text) = match.destructured
            val timeMs = (minutes.toLong() * 60 * 1000) + 
                        (seconds.toLong() * 1000) + 
                        (centiseconds.toLong() * 10)
            
            if (text.isNotBlank()) {
                lyricLines.add(LyricLine(timeMs, text))
            }
        }
    }
    
    return lyricLines
}

private fun isTrackDownloaded(trackId: String?, downloadedIds: Set<String>): Boolean {
    val resolvedTrackId = trackId?.takeIf { it.isNotBlank() } ?: return false
    val normalized = resolvedTrackId.removePrefix("yt_")
    return downloadedIds.contains(resolvedTrackId) ||
        downloadedIds.contains(normalized) ||
        downloadedIds.contains("yt_$normalized")
}

private fun fullPlayerCoverCacheKey(url: String): String {
    if (url.isBlank()) return url
    val normalizedUrl = if (url.startsWith("file://")) url else url.substringBefore('?')
    return "${normalizedUrl}_full_$FULL_PLAYER_COVER_REQUEST_SIZE_PX"
}

private fun decodeSampledBitmapFromFileForFullPlayer(path: String, reqSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSizeForFullPlayer(bounds, reqSizePx, reqSizePx)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(path, options)
}

private fun decodeSampledBitmapFromByteArrayForFullPlayer(data: ByteArray, reqSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSizeForFullPlayer(bounds, reqSizePx, reqSizePx)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}

private fun calculateInSampleSizeForFullPlayer(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
            halfHeight = halfHeight.coerceAtLeast(1)
            halfWidth = halfWidth.coerceAtLeast(1)
        }
    }

    return inSampleSize.coerceAtLeast(1)
}

private suspend fun loadCoverBitmapForFullPlayer(url: String, cacheKey: String): Bitmap? = withContext(Dispatchers.IO) {
    ImageMemoryCache.get(cacheKey)?.let { return@withContext it }

    runCatching {
        if (url.startsWith("file://")) {
            decodeSampledBitmapFromFileForFullPlayer(
                path = url.removePrefix("file://"),
                reqSizePx = FULL_PLAYER_COVER_REQUEST_SIZE_PX
            )
        } else {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { stream ->
                    decodeSampledBitmapFromByteArrayForFullPlayer(
                        data = stream.readBytes(),
                        reqSizePx = FULL_PLAYER_COVER_REQUEST_SIZE_PX
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }.getOrNull()?.also { bitmap ->
        ImageMemoryCache.put(cacheKey, bitmap)
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPauseClick: () -> Unit,
    onClose: () -> Unit,
    onLyricsClick: () -> Unit,
    modifier: Modifier = Modifier,
    apiBaseUrl: String = "",
    queue: List<Song> = emptyList(),
    currentIndex: Int = -1,
    onQueueItemClick: (Int) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (Song) -> Unit = {}
) {
    if (song == null) return

    val context = LocalContext.current
    val view = LocalView.current
    val playerManager: MusicPlayerManager = viewModel()
    

    val favoriteIds by DataCache.favoriteIds.collectAsState()
    val downloadedIds by DownloadHelper.downloadedIds.collectAsState()
    val isFavorite = song.id != null && favoriteIds.contains(song.id)
    val isDownloaded = remember(song.id, downloadedIds) {
        isTrackDownloaded(song.id, downloadedIds)
    }
    val userState = remember { mutableStateOf(AuthPreferences.getUser(context)) }
    val userToken = AuthPreferences.getEffectiveToken(context)
    val scope = rememberCoroutineScope()
    val disableExoPlayerAnimation = remember { DataCache.isExoPlayerAnimationDisabled(context) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showPlayerActionMenu by rememberSaveable { mutableStateOf(false) }

    val onFavoriteToggle: () -> Unit = {
        scope.launch {
            toggleFavorite(apiBaseUrl, song, context, userToken)
        }
    }
    val currentPositionState = playerManager.currentPosition
    val jamId = playerManager.jamId
    
    val jamState by JamWebSocketManager.jamState.collectAsState()
    
    LaunchedEffect(jamState, userState.value) {
        val hostId = jamState?.hostUserId
        val userId = userState.value?.id
        Timber.d("Host Check: jamHost=$hostId, userId=$userId, isJamActive=${jamId != null}")
    }

    val isHost = jamState?.hostUserId == userState.value?.id
    val isJamActive = jamId != null
    
    val currentTrackIndex by rememberUpdatedState(currentIndex)
    val onTrackClick by rememberUpdatedState(onQueueItemClick)
    
    val pagerState = rememberPagerState(initialPage = currentIndex.coerceAtLeast(0)) { queue.size }
    
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < queue.size && pagerState.currentPage != currentIndex) {
            pagerState.animateScrollToPage(currentIndex)
        }
    }
    
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, isScrolling) ->
                if (!isScrolling && page != currentTrackIndex && page >= 0 && page < queue.size) {
                    onTrackClick(page)
                }
            }
    }

    val isStaticBackgroundCover = remember { DataCache.isStaticBackgroundCoverEnabled(context) }
    val isAmoledBlack = remember { DataCache.isAmoledBlackEnabled(context) }
    val staticColor = if (isAmoledBlack) Color.Black else Color(0xFF121212)
    
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showNextInfo by rememberSaveable { mutableStateOf(false) }
    val thumbnailAlpha = remember { Animatable(1f) }
    
    LaunchedEffect(song.id, disableExoPlayerAnimation) {
        // Keep cover art fully visible when opening the expanded player and on track changes,
        // especially when expanded-player animations are disabled.
        thumbnailAlpha.snapTo(1f)
    }
    var lyricsText by remember { mutableStateOf<String?>(null) }
    var isLoadingLyrics by remember { mutableStateOf(false) }
    var lyricsOffsetMs by rememberSaveable(song.id) { mutableStateOf(0L) }
    var lyricsProviderMode by rememberSaveable { mutableStateOf(PlaybackPreferences.getLyricsProviderMode(context)) }
    var lyricsRefreshTrigger by remember { mutableStateOf(0) }
    val lastSongId = remember { mutableStateOf<String?>(null) }
    
    val lyricLines = remember(lyricsText) {
        lyricsText?.let { parseLyrics(it) } ?: emptyList()
    }
    
    val currentLyricPositionMs by remember(lyricsOffsetMs) {
        derivedStateOf {
            (currentPositionState.value - lyricsOffsetMs).coerceAtLeast(0L)
        }
    }

    val currentLyricIndex by remember(lyricLines, lyricsOffsetMs) {
        derivedStateOf {
            val pos = currentLyricPositionMs
            
            if (lyricLines.isNotEmpty() && pos < lyricLines.first().timeMs) {
                -1
            } else {
                lyricLines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(-1)
            }
        }
    }
    
    val listState = rememberLazyListState()
    
    var isLyricsFullscreen by rememberSaveable { mutableStateOf(false) }
    var isNextInfoFullscreen by rememberSaveable { mutableStateOf(false) }
    val lyricsPanelVisible = showLyrics || isLyricsFullscreen
    var pendingLyricsTrackReset by remember { mutableStateOf(false) }
    var lastVisibleLyricsSongId by remember { mutableStateOf(song.id) }
    
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    
    LaunchedEffect(song.id, lyricsRefreshTrigger) {
        if (song.id != null) {
            val isForceRefresh = lastSongId.value == song.id && lyricsRefreshTrigger > 0
            lastSongId.value = song.id
            
            lyricsText = null 
            isLoadingLyrics = true
            
            if (isForceRefresh) {
                kotlinx.coroutines.delay(500)
            }
            
            lyricsText = fetchTrackLyrics(
                apiBaseUrl, 
                song.id, 
                context = context, 
                songTitle = song.title, 
                songArtist = song.artist, 
                durationSec = song.durationSec ?: -1,
                forceRefresh = isForceRefresh
            )
            isLoadingLyrics = false
        }
    }
    
    
    LaunchedEffect(showNextInfo) {
        if (!showNextInfo) {
            isNextInfoFullscreen = false
        }
    }

    LaunchedEffect(isLyricsFullscreen) {
        if (isLyricsFullscreen && !showLyrics) {
            showLyrics = true
            showNextInfo = false
        }
    }

    LaunchedEffect(song.id) {
        val trackChanged = lastVisibleLyricsSongId != null && lastVisibleLyricsSongId != song.id
        lastVisibleLyricsSongId = song.id

        if (trackChanged) {
            pendingLyricsTrackReset = true
            if (lyricsPanelVisible) {
                runCatching { listState.scrollToItem(0) }
            }
        }
    }

    LaunchedEffect(song.id, lyricsText, lyricsPanelVisible, pendingLyricsTrackReset) {
        if (pendingLyricsTrackReset && lyricsPanelVisible && lyricLines.isNotEmpty()) {
            runCatching { listState.scrollToItem(0) }
        }
    }

    LaunchedEffect(pendingLyricsTrackReset, currentPositionState.value) {
        if (pendingLyricsTrackReset && currentPositionState.value <= 1_500L) {
            pendingLyricsTrackReset = false
        }
    }

    LaunchedEffect(song.id, pendingLyricsTrackReset) {
        if (pendingLyricsTrackReset) {
            delay(1_200L)
            pendingLyricsTrackReset = false
        }
    }
    
    
    LaunchedEffect(listState, lyricsPanelVisible, showNextInfo, pendingLyricsTrackReset) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset
        
        snapshotFlow { Triple(isDragged, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (isUserDragging, index, offset) ->
                val hasMoved = index != previousIndex || offset != previousScrollOffset

                if (isUserDragging && hasMoved && !pendingLyricsTrackReset) {
                    val isScrollingDown = index > previousIndex || (index == previousIndex && offset > previousScrollOffset)
                    val isScrollingUp = index < previousIndex || (index == previousIndex && offset < previousScrollOffset)
                    
                    if (isScrollingDown) {
                        if (lyricsPanelVisible) isLyricsFullscreen = true
                        if (showNextInfo) isNextInfoFullscreen = true
                    } else if (isScrollingUp) {
                        if (lyricsPanelVisible) isLyricsFullscreen = false
                        if (showNextInfo) isNextInfoFullscreen = false
                    }
                }
                previousIndex = index
                previousScrollOffset = offset
            }
    }
    
    LaunchedEffect(currentLyricIndex, lyricsPanelVisible, pendingLyricsTrackReset) {
        
        if (lyricsPanelVisible && lyricLines.isNotEmpty() && !isLyricsFullscreen && !isDragged && !pendingLyricsTrackReset) {
            val targetIndex = if (currentLyricIndex == -1) 0 else currentLyricIndex + 1 
            
            
            if (currentLyricIndex >= -1) {
                listState.animateScrollToItem(
                    index = targetIndex,
                    scrollOffset = -200
                )
            }
        }
    }
    
    val durationState = playerManager.duration
    
    
    var showQualityDialog by remember { mutableStateOf(false) }
    
    
    val targetScale = if (lyricsPanelVisible || showNextInfo) 1f else if (isPlaying) 1f else 0.9f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "albumScale"
    )
    val overlayEnter = if (disableExoPlayerAnimation) {
        androidx.compose.animation.EnterTransition.None
    } else {
        fadeIn(tween(500))
    }
    val overlayExit = if (disableExoPlayerAnimation) {
        androidx.compose.animation.ExitTransition.None
    } else {
        fadeOut(tween(500))
    }
    val controlsEnter = if (disableExoPlayerAnimation) {
        androidx.compose.animation.EnterTransition.None
    } else {
        fadeIn() + expandVertically(expandFrom = Alignment.Top) + slideInVertically(initialOffsetY = { it })
    }
    val controlsExit = if (disableExoPlayerAnimation) {
        androidx.compose.animation.ExitTransition.None
    } else {
        fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically(targetOffsetY = { it })
    }

    
    
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {  }
            .pointerInput(lyricsPanelVisible, showNextInfo) {
                if (!lyricsPanelVisible && !showNextInfo) {
                    var accumulatedDrag = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { accumulatedDrag = 0f },
                        onDragCancel = { accumulatedDrag = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            accumulatedDrag += dragAmount
                            if (accumulatedDrag > 150) { 
                                onClose()
                                accumulatedDrag = 0f
                            } else if (accumulatedDrag < 0) {
                                accumulatedDrag = 0f
                            }
                        }
                    )
                }
            }
    ) {
        
        if (showQualityDialog) {
            val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
            AudioQualityDialog(
                song = song,
                isAmoled = isAmoled,
                onDismiss = { showQualityDialog = false }
            )
        }

        val mainCoverUrl = song.coverUrl?.trim().orEmpty()
        val mainCoverCacheKey = remember(mainCoverUrl) { fullPlayerCoverCacheKey(mainCoverUrl) }
        val bitmapState = remember(mainCoverCacheKey) { 
            mutableStateOf(ImageMemoryCache.getFromMemory(mainCoverCacheKey)) 
        }
        
        LaunchedEffect(mainCoverUrl, mainCoverCacheKey) {
            val url = mainCoverUrl
            if (url.isNotBlank()) {
                Timber.d("FullPlayerScreen: Loading main cover art: $url")
                val cached = withContext(Dispatchers.IO) { ImageMemoryCache.get(mainCoverCacheKey) }
                if (cached != null) {
                    Timber.d("FullPlayerScreen: Loaded main cover art from cache: $url")
                    bitmapState.value = cached
                } else {
                    Timber.w("FullPlayerScreen: Main cover art NOT in cache, fetching: $url")
                    bitmapState.value = loadCoverBitmapForFullPlayer(url, mainCoverCacheKey)
                }
            }
        }

        val animatedBackgroundColor by animateColorAsState(
            targetValue = if (isStaticBackgroundCover || bitmapState.value == null) {
                staticColor
            } else {
                song.color.copy(alpha = 0.6f)
            },
            animationSpec = tween(500),
            label = "backgroundColor"
        )

        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedBackgroundColor)
        )
        
        if (!isStaticBackgroundCover) {
            bitmapState.value?.let { bitmap ->
                val imageBitmap = bitmap.asImageBitmap()
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(70.dp)
                ) {
                    
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(1.8f)
                            .offset(x = (-80).dp, y = (-60).dp),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopStart,
                        alpha = 0.7f
                    )
                    
                    
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(1.6f)
                            .offset(x = 60.dp, y = (-20).dp),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopEnd,
                        alpha = 0.5f
                    )
                    
                    
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(2.0f)
                            .offset(y = 100.dp),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        alpha = 0.4f
                    )
                }
                
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.1f),
                                    Color.Black.copy(alpha = 0.3f),
                                    Color.Black.copy(alpha = 0.7f)
                                ),
                                startY = 0f
                            )
                        )
                )
            }
        }
        
        if (showAddToPlaylist && song.id != null) {
            AddToPlaylistBottomSheet(
                onDismissRequest = { showAddToPlaylist = false },
                apiBaseUrl = apiBaseUrl,
                trackId = song.id,
                useYouTubePlaylists = song.id.startsWith("yt_"),
                onPlaylistSelected = { success ->
                    if (success) {
                        android.widget.Toast.makeText(context, "Added to playlist", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        PlayerActionBottomSheet(
            expanded = showPlayerActionMenu,
            onDismissRequest = { showPlayerActionMenu = false },
            isFavorite = isFavorite,
            song = song,
            onFavoriteToggle = onFavoriteToggle,
            onAddToPlaylistClick = { showAddToPlaylist = true },
            onPlayNextClick = { playerManager.playNext(song) },
            onDownloadClick = { DownloadHelper.downloadTrack(context, song, apiBaseUrl) },
            isDownloaded = isDownloaded,
            onRemoveDownloadClick = {
                song.id?.let { trackId ->
                    DownloadHelper.removeDownloadedSong(context, trackId)
                }
            },
            onAlbumClick = song.browsableAlbumId?.let { albumId ->
                { onAlbumClick(albumId) }
            },
            onArtistClick = song.primaryArtistName?.let {
                { onArtistClick(song) }
            },
            showLyricsSettings = lyricsPanelVisible,
            lyricsOffsetMs = lyricsOffsetMs,
            onLyricsOffsetChange = { lyricsOffsetMs = it },
            lyricsProviderMode = lyricsProviderMode,
            onLyricsProviderModeChange = { providerMode ->
                lyricsProviderMode = providerMode
                PlaybackPreferences.setLyricsProviderMode(context, providerMode)
                lyricsRefreshTrigger++
            },
            onLyricsRefresh = {
                lyricsRefreshTrigger++
            }
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(id = R.drawable.collapsealbum),
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val maxWidth = maxWidth
                val maxHeight = maxHeight
                
                
                val lyricsImageSize = 84.dp
                val lyricsX = 0.dp
                val lyricsY = 0.dp
                
                
                val trackInfoHeight = 60.dp
                val playerImageSize = maxWidth.coerceAtMost(maxHeight - trackInfoHeight - 16.dp)
                
                val playerX = (maxWidth - playerImageSize) / 2
                
                val playerY = (maxHeight - trackInfoHeight - playerImageSize) / 2
                
                
                val imageSize by animateDpAsState(
                    targetValue = if (lyricsPanelVisible || showNextInfo) lyricsImageSize else playerImageSize,
                    animationSpec = if (disableExoPlayerAnimation) {
                        snap()
                    } else {
                        tween(500, easing = FastOutSlowInEasing)
                    },
                    label = "imageSize"
                )

                val offsetX by animateDpAsState(
                    targetValue = if (lyricsPanelVisible || showNextInfo) lyricsX else playerX,
                    animationSpec = if (disableExoPlayerAnimation) {
                        snap()
                    } else {
                        tween(500, easing = FastOutSlowInEasing)
                    },
                    label = "offsetX"
                )

                val offsetY by animateDpAsState(
                    targetValue = if (lyricsPanelVisible || showNextInfo) lyricsY else playerY,
                    animationSpec = if (disableExoPlayerAnimation) {
                        snap()
                    } else {
                        tween(500, easing = FastOutSlowInEasing)
                    },
                    label = "offsetY"
                )

                val imageCorner by animateDpAsState(
                    targetValue = if (lyricsPanelVisible || showNextInfo) 8.dp else 12.dp,
                    animationSpec = if (disableExoPlayerAnimation) {
                        snap()
                    } else {
                        tween(500, easing = FastOutSlowInEasing)
                    },
                    label = "imageCorner"
                )

                
                androidx.compose.animation.AnimatedVisibility(
                    visible = lyricsPanelVisible,
                    enter = overlayEnter,
                    exit = overlayExit
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { 
                                isLyricsFullscreen = !isLyricsFullscreen 
                            }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    
                                    Spacer(modifier = Modifier.size(84.dp))
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = song.title,
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist,
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    PlayerActionButtons(
                                        isFavorite = isFavorite,
                                        song = song,
                                        onFavoriteToggle = onFavoriteToggle,
                                        onMoreOptionsClick = { showPlayerActionMenu = true },
                                        iconSize = 22.dp,
                                        buttonSize = 40.dp
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        
                            
                            if (isLoadingLyrics) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            } else if (lyricLines.isNotEmpty()) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight()
                                        .graphicsLayer { alpha = 0.99f }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    0f to Color.Transparent,
                                                    0.1f to Color.Black,
                                                    0.9f to Color.Black,
                                                    1f to Color.Transparent
                                                ),
                                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                                            )
                                        }
                                ) {
                                    item {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = true, 
                                            enter = fadeIn(),
                                            exit = fadeOut()
                                        ) {
                                            
                                            val progressToFirstLyric = if (currentLyricIndex == -1 && lyricLines.isNotEmpty()) {
                                                val firstTime = lyricLines.first().timeMs.toFloat()
                                                val current = currentLyricPositionMs.toFloat()
                                                if (firstTime > 0) (current / firstTime).coerceIn(0f, 1f) else 1f
                                            } else {
                                                1f
                                            }
                                            
                                            
                                            
                                            val dot1Target = if (currentLyricIndex == -1 && progressToFirstLyric > 0.1f) 1f else 0.3f
                                            val dot2Target = if (currentLyricIndex == -1 && progressToFirstLyric > 0.4f) 1f else 0.3f
                                            val dot3Target = if (currentLyricIndex == -1 && progressToFirstLyric > 0.7f) 1f else 0.3f
                                            
                                            val dot1Alpha by animateFloatAsState(targetValue = dot1Target, animationSpec = tween(1000), label = "dot1")
                                            val dot2Alpha by animateFloatAsState(targetValue = dot2Target, animationSpec = tween(1000), label = "dot2")
                                            val dot3Alpha by animateFloatAsState(targetValue = dot3Target, animationSpec = tween(1000), label = "dot3")
                                            
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 100.dp, bottom = 32.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.White.copy(alpha = dot1Alpha)))
                                                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.White.copy(alpha = dot2Alpha)))
                                                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.White.copy(alpha = dot3Alpha)))
                                            }
                                        }
                                    }

                                    itemsIndexed(lyricLines) { index, lyricLine ->
                                        val isCurrent = index == currentLyricIndex
                                        val isPast = index < currentLyricIndex
                                        
                                        
                                        
                                        val isUpcomingFirstLine = currentLyricIndex == -1 && index == 0
                                        
                                        val animatedAlpha by animateFloatAsState(
                                            targetValue = when {
                                                isCurrent -> 1f
                                                isPast -> 0.15f
                                                isUpcomingFirstLine -> 0.15f
                                                else -> 0.15f
                                            },
                                            animationSpec = tween(500, easing = FastOutSlowInEasing),
                                            label = "lyricAlpha"
                                        )
                                        
                                        val textScale by animateFloatAsState(
                                            targetValue = if (isCurrent) 1.0f else 0.8f,
                                            animationSpec = tween(500, easing = FastOutSlowInEasing),
                                            label = "lyricScale"
                                        )
                                        
                                        
                                        val paddingBottom = if (index == lyricLines.lastIndex) 200.dp else 14.dp
                                        
                                        val paddingTop = 14.dp 
                                        
                                        Text(
                                            
                                            text = lyricLine.text.replace("\\n", " ").replace("\n", " "),
                                            color = Color.White.copy(alpha = animatedAlpha),
                                            fontSize = 32.sp,
                                            fontFamily = FontFamily(Font(R.font.sfprodisplaybold)),
                                            lineHeight = 36.sp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = paddingTop, bottom = paddingBottom)
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) {
                                                     isLyricsFullscreen = !isLyricsFullscreen
                                                }
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { isLyricsFullscreen = !isLyricsFullscreen },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Lyrics not available",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                
                androidx.compose.animation.AnimatedVisibility(
                    visible = showNextInfo,
                    enter = overlayEnter,
                    exit = overlayExit
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { 
                                isNextInfoFullscreen = !isNextInfoFullscreen 
                            }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    
                                    Spacer(modifier = Modifier.size(84.dp))
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = song.title,
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist,
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    
                                    
                                    PlayerActionButtons(
                                        isFavorite = isFavorite,
                                        song = song,
                                        onFavoriteToggle = onFavoriteToggle,
                                        onMoreOptionsClick = { showPlayerActionMenu = true },
                                        iconSize = 22.dp,
                                        buttonSize = 40.dp
                                    )
                                }
                                
                                
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(88.dp)
                                            .height(40.dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(if (playerManager.shuffleMode.value) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f))
                                            .clickable { playerManager.toggleShuffleMode() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.shuffle),
                                            contentDescription = "Shuffle",
                                            tint = if (playerManager.shuffleMode.value) Color.White else Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(42.dp)
                                        )
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .width(88.dp)
                                            .height(40.dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(if (playerManager.repeatMode.intValue != androidx.media3.common.Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f))
                                            .clickable { playerManager.toggleRepeatMode() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.repeat),
                                            contentDescription = "Repeat",
                                            tint = when (playerManager.repeatMode.intValue) {
                                                androidx.media3.common.Player.REPEAT_MODE_ONE -> Color(0xFFF13950) 
                                                androidx.media3.common.Player.REPEAT_MODE_ALL -> Color.White
                                                else -> Color.White.copy(alpha = 0.7f)
                                            },
                                            modifier = Modifier.size(42.dp)
                                        )
                                    }
                                }
                            }
                        
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                Text(
                                    text = "Continue Playing",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontFamily = FontFamily(Font(R.font.sfprodisplaymedium)),
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                                
                                val safeCurrentIndex = currentIndex.coerceAtLeast(0)
                                val upcomingOffset = safeCurrentIndex + 1 
                                
                                val filteredQueue = if (isJamActive) {
                                     
                                     
                                     
                                     
                                     
                                     if (queue.isNotEmpty()) queue.drop(1) else emptyList()
                                } else {
                                     if (currentIndex >= 0 && upcomingOffset < queue.size) {
                                        queue.drop(upcomingOffset)
                                     } else {
                                        emptyList()
                                     }
                                }
                                
                                QueueRecyclerView(
                                    queue = filteredQueue,
                                    currentIndex = -1, 
                                    onMove = { from, to -> 
                                        if (isJamActive) {
                                            
                                            val allowEdit = isHost || (jamState?.settings?.allowQueueEdit == true)
                                            if (allowEdit) {
                                                
                                                
                                                
                                                
                                                playerManager.moveQueueItem(from + 1, to + 1, isHost) 
                                            } else {
                                                android.widget.Toast.makeText(context, "Only host can edit queue", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            playerManager.moveQueueItem(from + upcomingOffset, to + upcomingOffset) 
                                        }
                                    },
                                    onItemClick = { index -> 
                                        if (isJamActive) {
                                            
                                            
                                            
                                            if (isHost) {
                                                
                                                
                                                
                                                
                                                
                                                
                                                android.widget.Toast.makeText(context, "Skipping to specific track not supported in Jam yet", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            onQueueItemClick(index + upcomingOffset) 
                                        }
                                    },
                                    onStartDrag = { isNextInfoFullscreen = true },
                                    onStopDrag = { isNextInfoFullscreen = false },
                                    modifier = Modifier.weight(1f).clipToBounds()
                                )
                            }
                        }
                    }
                }

                
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .offset { 
                                androidx.compose.ui.unit.IntOffset(
                                    x = offsetX.roundToPx(),
                                    y = offsetY.roundToPx()
                                )
                            }
                            .layout { measurable, _ ->
                                val sizePx = imageSize.roundToPx()
                                val placeable = measurable.measure(
                                    Constraints.fixed(sizePx, sizePx)
                                )
                                layout(sizePx, sizePx) {
                                    placeable.place(0, 0)
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = thumbnailAlpha.value
                                shape = RoundedCornerShape(imageCorner.toPx())
                                clip = true
                            },
                        userScrollEnabled = !lyricsPanelVisible && !showNextInfo,
                        beyondViewportPageCount = 1,
                        pageSpacing = 16.dp
                    ) { page ->
                        val songAtPage = queue.getOrNull(page) ?: song
                        val coverUrl = songAtPage.coverUrl?.trim().orEmpty()
                        val pageCoverCacheKey = remember(coverUrl) { fullPlayerCoverCacheKey(coverUrl) }
                        
                        val pageBitmapState = remember(pageCoverCacheKey) { 
                            mutableStateOf(ImageMemoryCache.getFromMemory(pageCoverCacheKey)) 
                        }
                        
                        LaunchedEffect(coverUrl, pageCoverCacheKey) {
                            if (coverUrl.isNotBlank()) {
                                Timber.d("FullPlayerScreen: Loading pager cover art: $coverUrl")
                                val cached = withContext(Dispatchers.IO) { ImageMemoryCache.get(pageCoverCacheKey) }
                                if (cached != null) {
                                    Timber.d("FullPlayerScreen: Loaded pager cover art from cache: $coverUrl")
                                    pageBitmapState.value = cached
                                } else {
                                    Timber.w("FullPlayerScreen: Pager cover art NOT in cache, fetching: $coverUrl")
                                    pageBitmapState.value = loadCoverBitmapForFullPlayer(coverUrl, pageCoverCacheKey)
                                }
                            }
                        }
                        
                        pageBitmapState.value?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } ?: Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(staticColor)
                        )
                    }
                }
                
                
                val detailsEnter = if (disableExoPlayerAnimation) {
                    androidx.compose.animation.EnterTransition.None
                } else {
                    fadeIn(tween(150)) + slideInVertically(tween(500), initialOffsetY = { -it / 2 })
                }
                val detailsExit = if (disableExoPlayerAnimation) {
                    androidx.compose.animation.ExitTransition.None
                } else {
                    fadeOut(tween(150)) + slideOutVertically(tween(500), targetOffsetY = { -it / 2 })
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = !lyricsPanelVisible && !showNextInfo,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    enter = detailsEnter,
                    exit = detailsExit
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(8.dp))
        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = song.title,
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                Text(
                                    text = song.artist,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            
                            PlayerActionButtons(
                                isFavorite = isFavorite,
                                song = song,
                                onFavoriteToggle = onFavoriteToggle,
                                onMoreOptionsClick = { showPlayerActionMenu = true },
                                iconSize = 24.dp,
                                buttonSize = 48.dp
                            )
                        }
                        
                        
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            
            
            
            AnimatedVisibility(
                visible = !isLyricsFullscreen && !isNextInfoFullscreen,
                enter = controlsEnter,
                exit = controlsExit
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    
                    PlayerTimeline(
                        currentPositionState = currentPositionState,
                        durationState = durationState,
                        song = song,
                        onSeek = { playerManager.seekTo(it) },
                        onQualityClick = { showQualityDialog = true }
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        
                        IconButton(
                            onClick = { 
                                if (isJamActive) {
                                    if (isHost) {
                                        playerManager.playPrevious()
                                    } else {
                                        android.widget.Toast.makeText(context, "Only host can skip tracks", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    playerManager.playPrevious() 
                                }
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.previous),
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        
                        
                        IconButton(
                            onClick = {
                                if (isJamActive) {
                                    playerManager.togglePlayPause(isHost)
                                } else {
                                    onPlayPauseClick()
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.size(80.dp)
                        ) {
                            if (isLoading && !showNextInfo && !lyricsPanelVisible) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(
                                        id = if (isPlaying) R.drawable.pause else R.drawable.resume
                                    ),
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = if (isLoading) Color.White.copy(alpha = 0.5f) else Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }
                        
                        
                        IconButton(
                            onClick = { 
                                if (isJamActive) {
                                    if (isHost) {
                                        playerManager.playNext()
                                    } else {
                                        android.widget.Toast.makeText(context, "Only host can skip tracks", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    playerManager.playNext() 
                                }
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.forward),
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        
                        Icon(
                            painter = painterResource(id = R.drawable.albumspeaker),
                            contentDescription = "Low volume",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        
                        
                        val currentVolume = playerManager.player.volume
                        var volume by remember { mutableStateOf(currentVolume) }
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val isDragged by interactionSource.collectIsDraggedAsState()
                        val isInteracting = isPressed || isDragged
                        
                        val volumeBarHeight by animateDpAsState(
                            targetValue = if (isInteracting) 12.dp else 8.dp,
                            animationSpec = tween(durationMillis = 200),
                            label = "volumeBarHeight"
                        )
                        
                        val volumeActiveColor by animateColorAsState(
                            targetValue = if (isInteracting) Color.White else Color.White.copy(alpha = 0.5f),
                            animationSpec = tween(durationMillis = 200),
                            label = "volumeActiveColor"
                        )
                        
                        Slider(
                            value = volume,
                            onValueChange = { newVolume ->
                                volume = newVolume
                                playerManager.player.volume = newVolume
                            },
                            interactionSource = interactionSource,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Transparent,
                                activeTrackColor = volumeActiveColor,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                            ),
                            thumb = {
                                
                            },
                            track = { sliderState ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(volumeBarHeight)
                                        .clip(RoundedCornerShape(volumeBarHeight / 2))
                                        .background(Color.White.copy(alpha = 0.15f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(sliderState.value)
                                            .fillMaxHeight()
                                            .background(volumeActiveColor)
                                    )
                                }
                            }
                        )
                        
                        
                        Icon(
                            painter = painterResource(id = R.drawable.albumspeakerlarge),
                            contentDescription = "High volume",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                            
                            Box(
                                contentAlignment = Alignment.Center
                            ) {
                                
                                if (lyricsPanelVisible) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(
                                                color = Color.White.copy(alpha = 0.2f),
                                                shape = CircleShape
                                            )
                                    )
                                }
                                
                                IconButton(
                                    onClick = {
                                        val shouldShowLyrics = !lyricsPanelVisible
                                        showLyrics = shouldShowLyrics
                                        if (shouldShowLyrics) {
                                            showNextInfo = false
                                            if (lyricsText == null && song.id != null) {
                                                scope.launch {
                                                    isLoadingLyrics = true
                                                    lyricsText = fetchTrackLyrics(apiBaseUrl, song.id, context = context, songTitle = song.title, songArtist = song.artist, durationSec = song.durationSec ?: -1)
                                                    isLoadingLyrics = false
                                                }
                                            }
                                        } else {
                                            isLyricsFullscreen = false
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.lyrics),
                                        contentDescription = "Lyrics",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            
                            
                            Box(
                                contentAlignment = Alignment.Center
                            ) {
                                
                                if (showNextInfo) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(
                                                color = Color.White.copy(alpha = 0.2f),
                                                shape = CircleShape
                                            )
                                    )
                                }
                                
                                IconButton(
                                    onClick = {
                                        showNextInfo = !showNextInfo
                                        if (showNextInfo) {
                                            showLyrics = false
                                            isLyricsFullscreen = false
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.nextinfo),
                                        contentDescription = "Next Info",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTimeline(
    currentPositionState: State<Long>,
    durationState: State<Long>,
    song: Song,
    onSeek: (Long) -> Unit,
    onQualityClick: () -> Unit
) {
    val currentPosition by currentPositionState
    val duration by durationState
    
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        val safeCurrentPosition = currentPosition.coerceAtLeast(0L)
        val safeDuration = duration.coerceAtLeast(1L)
        
        val progress = if (safeDuration > 0) {
            (safeCurrentPosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        
        
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val isDragged by interactionSource.collectIsDraggedAsState()
        val isInteracting = isPressed || isDragged
        
        var sliderPosition by remember { mutableStateOf<Float?>(null) }
        val displayProgress = sliderPosition ?: progress

        val barHeight by animateDpAsState(
            targetValue = if (isInteracting) 12.dp else 8.dp,
            animationSpec = tween(durationMillis = 200),
            label = "progressBarHeight"
        )
        
        val progressActiveColor by animateColorAsState(
            targetValue = if (isInteracting) Color.White else Color.White.copy(alpha = 0.5f),
            animationSpec = tween(durationMillis = 200),
            label = "progressActiveColor"
        )
        
        Slider(
            value = displayProgress,
            onValueChange = { newProgress ->
                sliderPosition = newProgress
            },
            onValueChangeFinished = {
                sliderPosition?.let {
                    if (safeDuration > 0) {
                        val newPosition = (it * safeDuration).toLong()
                        onSeek(newPosition)
                    }
                }
                sliderPosition = null
            },
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth().height(18.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = progressActiveColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            thumb = {
                
            },
            track = { sliderState ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(barHeight / 2))
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(sliderState.value)
                            .fillMaxHeight()
                            .background(progressActiveColor)
                    )
                }
            }
        )
        
        Spacer(modifier = Modifier.height(0.dp))
        
        
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp)
        ) {
            
            Text(
                text = formatTime(safeCurrentPosition),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            
            
            song.type?.let { type ->
                val displayFormat = when {
                    type.contains("flac", ignoreCase = true) || type.contains("alac", ignoreCase = true) -> "Hi-Res Lossless"
                    type.contains("mpeg", ignoreCase = true) -> "MP3"
                    type.contains("mp3", ignoreCase = true) -> "MP3"
                    else -> type.uppercase()
                }
                
                Surface(
                    onClick = onQualityClick,
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (displayFormat == "Hi-Res Lossless") {
                            Icon(
                                painter = painterResource(id = R.drawable.apple_lossless_seeklogo),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = displayFormat,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            
            
            Text(
                text = formatTime(safeDuration),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
fun AudioQualityDialog(
    song: Song,
    isAmoled: Boolean,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val type = song.type ?: "unknown"
        val isLossless = type.contains("flac", ignoreCase = true) || type.contains("alac", ignoreCase = true)
        val title = if (isLossless) "Hi-Res Lossless" else "Lossy"
        val colorScheme = MaterialTheme.colorScheme
        val containerColor = if (isAmoled) Color.Black else colorScheme.surface
        
        val samplingRate = song.samplingRateHz
            ?.takeIf { it > 0 }
            ?.let { "${it / 1000.0} kHz" }
            ?: "44.1 kHz"
        val techDetail = if (isLossless) {
            val codecName = if (type.contains("alac", ignoreCase = true)) "ALAC" else "FLAC"
            "$codecName 24 bits/$samplingRate"
        } else {
            "MP3 $samplingRate"
        }

        androidx.compose.material3.Card(
            shape = RoundedCornerShape(32.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 36.dp, bottom = 20.dp, start = 32.dp, end = 32.dp)
            ) {
                if (isLossless) {
                    Icon(
                        painter = painterResource(id = R.drawable.apple_lossless_seeklogo),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(100.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.mp3),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(100.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = title,
                    color = colorScheme.onSurface,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = techDetail,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = "OK",
                        color = colorScheme.primary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}


private fun formatTime(millis: Long): String {
    if (millis < 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerActionButtons(
    isFavorite: Boolean,
    song: Song? = null,
    onFavoriteToggle: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
    buttonSize: androidx.compose.ui.unit.Dp = 40.dp
) {
    val isYouTube = song?.type == "youtube"
    
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onFavoriteToggle,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = if (isYouTube) {
                    if (isFavorite) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp
                } else {
                    if (isFavorite) Icons.Filled.Star else Icons.Default.StarOutline
                },
                contentDescription = if (isYouTube) "Like" else "Favorite",
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
        
        IconButton(
            onClick = onMoreOptionsClick,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerActionBottomSheet(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    isFavorite: Boolean,
    song: Song? = null,
    onFavoriteToggle: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onPlayNextClick: () -> Unit,
    onDownloadClick: () -> Unit,
    isDownloaded: Boolean = false,
    onRemoveDownloadClick: () -> Unit = {},
    onAlbumClick: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    showLyricsSettings: Boolean = false,
    lyricsOffsetMs: Long = 0L,
    onLyricsOffsetChange: ((Long) -> Unit)? = null,
    lyricsProviderMode: String = "auto",
    onLyricsProviderModeChange: ((String) -> Unit)? = null,
    onLyricsRefresh: (() -> Unit)? = null
) {
    if (!expanded) return

    val context = LocalContext.current
    val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
    val sheetBgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val isYouTube = song?.type == "youtube"

    fun dismiss() {
        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = sheetBgColor,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), CircleShape)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            if (song != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverArt(
                        coverUrl = song.coverUrl,
                        fallbackColor = song.color,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        requestSizePx = 128
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = SFProDisplayFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = song.artist,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontFamily = SFProDisplayFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlayerQuickButton(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = "Add to List",
                    modifier = Modifier.weight(1f),
                    onClick = { dismiss(); onAddToPlaylistClick() }
                )

                PlayerQuickButton(
                    icon = if (isYouTube) {
                        if (isFavorite) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp
                    } else {
                        if (isFavorite) Icons.Filled.Star else Icons.Default.StarOutline
                    },
                    label = if (isYouTube) {
                        if (isFavorite) "Liked" else "Like"
                    } else {
                        "Favourite"
                    },
                    tint = if (isFavorite) {
                        if (isYouTube) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onClick = { dismiss(); onFavoriteToggle() }
                )

                PlayerQuickButton(
                    icon = Icons.Default.SkipNext,
                    label = "Play Next",
                    modifier = Modifier.weight(1f),
                    onClick = { dismiss(); onPlayNextClick() }
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            PlayerMenuItem(
                icon = if (isDownloaded) Icons.Default.Delete else Icons.Default.ArrowDownward,
                label = if (isDownloaded) "Remove Download" else "Download",
                tint = if (isDownloaded) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface,
                onClick = {
                    dismiss()
                    if (isDownloaded) onRemoveDownloadClick() else onDownloadClick()
                }
            )

            if (onAlbumClick != null || onArtistClick != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (onAlbumClick != null) {
                PlayerMenuItem(
                    icon = Icons.Default.Album,
                    label = "Go to Album",
                    onClick = { dismiss(); onAlbumClick() }
                )
            }

            if (onArtistClick != null) {
                PlayerMenuItem(
                    icon = Icons.Default.Person,
                    label = "Go to Artist",
                    onClick = { dismiss(); onArtistClick() }
                )
            }

            if (showLyricsSettings && onLyricsOffsetChange != null && onLyricsProviderModeChange != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Lyrics Settings",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = SFProDisplayFontFamily,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = "Lyrics provider",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Lyrics Provider",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = SFProDisplayFontFamily
                                    )
                                    Text(
                                        text = "Swipe to switch source",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        fontFamily = SFProDisplayFontFamily
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            LyricsSettingsActionButton(
                                icon = Icons.Default.Refresh,
                                label = "Refresh",
                                onClick = { onLyricsRefresh?.invoke() }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LyricsProviderModeSelector(
                            selectedMode = lyricsProviderMode,
                            onModeSelected = onLyricsProviderModeChange
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (lyricsOffsetMs != 0L) Icons.Filled.AvTimer else Icons.Default.Timer,
                                        contentDescription = "Offset",
                                        tint = if (lyricsOffsetMs != 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Lyrics Timing",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = SFProDisplayFontFamily
                                    )
                                    Text(
                                        text = when {
                                            lyricsOffsetMs == 0L -> "No timing offset"
                                            lyricsOffsetMs > 0 -> "Delay ${lyricsOffsetMs} ms"
                                            else -> "Advance ${lyricsOffsetMs.absoluteValue} ms"
                                        },
                                        color = if (lyricsOffsetMs != 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        fontFamily = SFProDisplayFontFamily
                                    )
                                }
                            }

                            if (lyricsOffsetMs != 0L) {
                                Text(
                                    text = "Reset",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = SFProDisplayFontFamily,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onLyricsOffsetChange.invoke(0L) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LyricsTimingAdjustButton(
                                label = "-500 ms",
                                modifier = Modifier.weight(1f),
                                onClick = { onLyricsOffsetChange.invoke(lyricsOffsetMs - 500L) }
                            )
                            LyricsTimingAdjustButton(
                                label = "-100 ms",
                                modifier = Modifier.weight(1f),
                                onClick = { onLyricsOffsetChange.invoke(lyricsOffsetMs - 100L) }
                            )
                            LyricsTimingAdjustButton(
                                label = "+100 ms",
                                modifier = Modifier.weight(1f),
                                onClick = { onLyricsOffsetChange.invoke(lyricsOffsetMs + 100L) }
                            )
                            LyricsTimingAdjustButton(
                                label = "+500 ms",
                                modifier = Modifier.weight(1f),
                                onClick = { onLyricsOffsetChange.invoke(lyricsOffsetMs + 500L) }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Negative values bring lyrics earlier. Positive values delay them.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = SFProDisplayFontFamily
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsProviderModeSelector(
    selectedMode: String,
    onModeSelected: (String) -> Unit
) {
    val modes = listOf(
        "auto" to "Auto",
        "lrclib" to "LrcLib",
        "betterlyrics" to "Better",
        "rclyricsband" to "Rc"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        modes.forEach { (mode, label) ->
            val selected = selectedMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily(Font(R.font.sfprodisplaymedium)),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun LyricsSettingsActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily(Font(R.font.sfprodisplaymedium)),
            maxLines = 1
        )
    }
}

@Composable
private fun LyricsTimingAdjustButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily(Font(R.font.sfprodisplaymedium)),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PlayerQuickButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val SFPro = FontFamily(Font(R.font.sfprodisplaymedium))
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val SFPro = FontFamily(Font(R.font.sfprodisplaymedium))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 15.sp,
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium
        )
    }
}
