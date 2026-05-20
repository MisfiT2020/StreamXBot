package com.xstream.music.features.playlist

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
import android.graphics.Bitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.cache.ImageMemoryCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.Playlist
import com.xstream.music.data.model.Song
import com.xstream.music.player.manager.MusicPlayerManager
import com.xstream.music.ui.components.AddToPlaylistBottomSheet
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.DownloadButton
import com.xstream.music.ui.components.PlaylistBottomSheet
import com.xstream.music.ui.components.PlaylistContextMenu
import com.xstream.music.ui.components.SkeletonBox
import com.xstream.music.ui.components.SongContextMenu
import com.xstream.music.ui.components.SongMoreOrSelectButton
import com.xstream.music.ui.theme.SFProDisplayFontFamily
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)

@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    apiUrl: String,
    onBack: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    isPlayerVisible: Boolean = false,
    onShareClick: ((Playlist) -> Unit)? = null,
    onRenameClick: ((Playlist) -> Unit)? = null,
    onDeleteClick: ((Playlist) -> Unit)? = null,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val songsState = remember { mutableStateOf<List<Song>>(emptyList()) }
    val isLoadingState = remember { mutableStateOf(true) }
    val isMoreLoadingState = remember { mutableStateOf(false) }
    val currentPageState = remember { mutableStateOf(1) }
    val hasMoreState = remember { mutableStateOf(true) }
    
    val listState = rememberLazyListState()
    val dominantColorState = remember { mutableStateOf(playlist.color) }
    val isLightColorState = remember { mutableStateOf(false) }
    
    val isFavorites = playlist.id == "favorites"
    val menuExpanded = remember { mutableStateOf(false) }
    val addButtonExpanded = remember { mutableStateOf(false) }
    var showSavePlaylistDialog by remember { mutableStateOf(false) }
    val playerManager: MusicPlayerManager = androidx.lifecycle.viewmodel.compose.viewModel()
    val favoriteIds by DataCache.favoriteIds.collectAsState()
    val savedYouTubePlaylistIds by DataCache.savedYouTubePlaylistIds.collectAsState()
    var isYouTubeSaved by remember { mutableStateOf(false) }
    var youtubePlaylistId by remember { mutableStateOf<String?>(null) }
    val lastUpdatedState = remember { mutableStateOf<Long?>(playlist.lastUpdatedAt) }
    val currentPlaylistState = remember { mutableStateOf(playlist) }
    val youtubeContinuationState = remember { mutableStateOf<String?>(null) }
    val bulkTrackIds = songsState.value.mapNotNull { it.id }.distinct()
    val hasBulkTracks = bulkTrackIds.isNotEmpty()
    val areAllTracksFavorite = hasBulkTracks && bulkTrackIds.all { it in favoriteIds }
    val isOwnerPlaylist = currentPlaylistState.value.kind == "user_playlist"
    val isDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val favoritesHeroBackground = if (isDarkMode) MaterialTheme.colorScheme.background else Color.White
    val favoritesPrimaryTextColor = if (isDarkMode) MaterialTheme.colorScheme.onBackground else Color.Black
    val favoritesSecondaryTextColor = if (isDarkMode) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color.Gray
    }
    val favoritesPrimaryButtonColor = if (isDarkMode) MaterialTheme.colorScheme.primary else Color.Black
    val favoritesPrimaryButtonContentColor = if (isDarkMode) Color.Black else Color.White
    val favoritesSecondaryButtonColor = if (isDarkMode) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    } else {
        Color(0xFFE5E5E7)
    }
    val favoritesSecondaryButtonContentColor = if (isDarkMode) MaterialTheme.colorScheme.onBackground else Color.Black
    
    
    LaunchedEffect(playlist.id, savedYouTubePlaylistIds, youtubePlaylistId) {
        val canBeSavedToLibrary = playlist.kind == "youtube_playlist" || playlist.kind == "youtube_album"
        if (canBeSavedToLibrary) {
            val endpoint = currentPlaylistState.value.endpoint.orEmpty()
            val normalizedPlaylistId = playlist.id.removePrefix("yt_").removePrefix("VL")
            val normalizedEndpoint = endpoint.removePrefix("yt_").removePrefix("VL")

            
            val candidateIds = buildSet {
                add(playlist.id)
                add(normalizedPlaylistId)
                add("yt_$normalizedPlaylistId")
                add("VL$normalizedPlaylistId")
                if (endpoint.isNotBlank()) {
                    add(endpoint)
                    add(normalizedEndpoint)
                    add("yt_$normalizedEndpoint")
                    add("VL$normalizedEndpoint")
                }
                youtubePlaylistId?.let { id ->
                    val normalizedLikedId = id.removePrefix("yt_").removePrefix("VL")
                    add(id)
                    add(normalizedLikedId)
                    add("yt_$normalizedLikedId")
                    add("VL$normalizedLikedId")
                }
            }

            isYouTubeSaved = candidateIds.any { it in savedYouTubePlaylistIds }
        } else {
            isYouTubeSaved = false
        }
    }

    val topBarAlpha by remember {
        derivedStateOf {
            val threshold = if (isFavorites) 1200f else 500f
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset.toFloat() / threshold).coerceIn(0f, 1f)
        }
    }

    val isYouTubePlaylist = playlist.kind == "youtube_playlist"
    val isYouTubeAlbum = playlist.kind == "youtube_album"

    LaunchedEffect(playlist.id) {
        val token = AuthPreferences.getEffectiveToken(context)
        var sharedPlaylistSongs: List<Song>? = null
        
        if (!isYouTubePlaylist && !isYouTubeAlbum && (currentPlaylistState.value.title == "Loading Playlist..." || currentPlaylistState.value.endpoint.isNullOrBlank())) {
            val sharedResult = fetchSharedPlaylist(apiUrl, playlist.id)
            if (sharedResult != null) {
                currentPlaylistState.value = sharedResult.first
                dominantColorState.value = sharedResult.first.color
                sharedPlaylistSongs = sharedResult.second
            } else {
                val meta = fetchPlaylistMetadata(apiUrl, playlist.id, context, token)
                if (meta != null) {
                    currentPlaylistState.value = meta
                    dominantColorState.value = meta.color
                }
            }
        }

        val endpoint = currentPlaylistState.value.endpoint ?: ""
        
        if (isFavorites) {
            val result = runCatching {
                fetchFavoriteSongsAndUpdatedTime(apiUrl, page = 1, limit = 20, context = context, token = token)
            }.getOrNull()
            
            val fetchedSongs = result?.first.orEmpty()
            songsState.value = fetchedSongs
            hasMoreState.value = fetchedSongs.size >= 20
            
            if (result?.second != null) {
                lastUpdatedState.value = result.second
            }
        } else if (isYouTubePlaylist || isYouTubeAlbum) {
            val ytEndpoint = if (endpoint.isNotBlank()) endpoint else playlist.id
            Timber.d("Fetching YouTube ${if (isYouTubeAlbum) "album" else "playlist"}: $ytEndpoint")
            
            
            val normalizedId = ytEndpoint.removePrefix("VL")
            val isSpecialPodcastPlaylist = normalizedId == "SE" || normalizedId == "RDPN"
            
            val fetchedSongs = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        normalizedId == "SE" -> {
                            
                            Timber.d("Calling YouTube.episodesForLater()")
                            val episodes = com.metrolist.innertube.YouTube.episodesForLater().getOrNull().orEmpty()
                            Timber.d("Episodes for later received: ${episodes.size} episodes")
                            episodes.mapNotNull { it.toSong() }
                        }
                        normalizedId == "RDPN" -> {
                            
                            Timber.d("Calling YouTube.newEpisodes()")
                            val episodes = com.metrolist.innertube.YouTube.newEpisodes().getOrNull().orEmpty()
                            Timber.d("New episodes received: ${episodes.size} episodes")
                            episodes.mapNotNull { it.toSong() }
                        }
                        isYouTubeAlbum -> {
                            
                            Timber.d("Calling YouTube.album($ytEndpoint)")
                            val albumPage = com.metrolist.innertube.YouTube.album(ytEndpoint).getOrNull()
                            Timber.d("Album page received: ${albumPage?.songs?.size} songs")
                            
                            youtubePlaylistId = albumPage?.album?.playlistId
                            albumPage?.songs?.mapNotNull { it.toSong() }.orEmpty()
                        }
                        else -> {
                            
                            Timber.d("Calling YouTube.playlist($ytEndpoint)")
                            val playlistResult = com.metrolist.innertube.YouTube.playlist(ytEndpoint)
                            if (playlistResult.isFailure) {
                                Timber.e(playlistResult.exceptionOrNull(), "YouTube.playlist() failed for $ytEndpoint")
                            }
                            val playlistPage = playlistResult.getOrNull()
                            Timber.d("Playlist page received: playlist=${playlistPage?.playlist?.title}, songs=${playlistPage?.songs?.size}")
                            if (playlistPage?.songs == null) {
                                Timber.w("Playlist page songs is null for $ytEndpoint")
                            }
                            
                            youtubePlaylistId = ytEndpoint
                            playlistPage?.songs?.mapNotNull { 
                                val song = it.toSong()
                                if (song == null) {
                                    Timber.w("Failed to convert item to song: ${it::class.simpleName} - ${it.title}")
                                }
                                song
                            }.orEmpty()
                        }
                    }
                }.getOrElse { e ->
                    Timber.e(e, "Error fetching YouTube ${if (isYouTubeAlbum) "album" else "playlist"}")
                    emptyList()
                }
            }
            Timber.d("Fetched ${fetchedSongs.size} songs")
            songsState.value = fetchedSongs
            
            if (isYouTubePlaylist && !isSpecialPodcastPlaylist) {
                val playlistPage = withContext(Dispatchers.IO) {
                    runCatching {
                        com.metrolist.innertube.YouTube.playlist(ytEndpoint).getOrNull()
                    }.getOrNull()
                }
                val contToken = playlistPage?.songsContinuation ?: playlistPage?.continuation
                youtubeContinuationState.value = contToken
                hasMoreState.value = contToken != null
            } else {
                youtubeContinuationState.value = null
                hasMoreState.value = false
            }
        } else if (endpoint.isNotBlank()) {
            val fetchedSongs = sharedPlaylistSongs ?: runCatching {
                fetchPlaylistSongs(apiUrl, endpoint, page = 1, limit = 75, context = context, token = token)
            }.getOrNull().orEmpty()
            songsState.value = fetchedSongs
            hasMoreState.value = fetchedSongs.size >= 75
        }
        isLoadingState.value = false
    }

    
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
            lastVisibleItemIndex > (totalItemsNumber - 5) && !isLoadingState.value && !isMoreLoadingState.value && hasMoreState.value
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }.collect { shouldLoad ->
            if (shouldLoad) {
                isMoreLoadingState.value = true
                val token = AuthPreferences.getEffectiveToken(context)
                val nextPage = currentPageState.value + 1
                
                val newSongs = if (isFavorites) {
                    runCatching {
                        fetchFavoriteSongs(apiUrl, page = nextPage, limit = 20, context = context, token = token)
                    }.getOrNull().orEmpty()
                } else if (isYouTubePlaylist || isYouTubeAlbum) {
                    val contToken = youtubeContinuationState.value
                    if (contToken != null) {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                if (isYouTubeAlbum) {
                                    
                                    com.metrolist.innertube.YouTube.playlistContinuation(contToken).getOrNull()
                                } else {
                                    com.metrolist.innertube.YouTube.playlistContinuation(contToken).getOrNull()
                                }
                            }.getOrNull()
                        }
                        val newYTSongs = result?.songs?.mapNotNull { it.toSong() }.orEmpty()
                        youtubeContinuationState.value = result?.continuation
                        newYTSongs
                    } else emptyList()
                } else {
                    val endp = currentPlaylistState.value.endpoint ?: ""
                    runCatching {
                        fetchPlaylistSongs(apiUrl, endp, page = nextPage, limit = 75, context = context, token = token)
                    }.getOrNull().orEmpty()
                }
                
                if (newSongs.isNotEmpty()) {
                    songsState.value = songsState.value + newSongs
                    currentPageState.value = nextPage
                    hasMoreState.value = if (isFavorites || isYouTubePlaylist || isYouTubeAlbum) (isFavorites && newSongs.size >= 20) || (!isFavorites && youtubeContinuationState.value != null) else newSongs.size >= 75
                } else {
                    hasMoreState.value = false
                }
                isMoreLoadingState.value = false
            }
        }
    }

    
    val coverUrlToUse = currentPlaylistState.value.thumbnailUrl
    val isImageLoaded = remember { mutableStateOf(false) }
    
    LaunchedEffect(coverUrlToUse) {
        if (!coverUrlToUse.isNullOrBlank()) {
            val extractedColor = withContext(Dispatchers.IO) {
                runCatching {
                    val cached = ImageMemoryCache.get(coverUrlToUse)
                    val bitmap = if (cached != null) {
                        cached
                    } else {
                        val connection = (URL(coverUrlToUse).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 5000
                            readTimeout = 5000
                        }
                        connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
                    }
                    
                    if (bitmap != null) {
                        
                        if (cached == null) {
                            ImageMemoryCache.put(coverUrlToUse, bitmap)
                        }
                        
                        val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                        val pixel = scaled.getPixel(0, 0)
                        val r = android.graphics.Color.red(pixel)
                        val g = android.graphics.Color.green(pixel)
                        val b = android.graphics.Color.blue(pixel)
                        
                        
                        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
                        isLightColorState.value = luminance > 0.5
                        
                        Color(r, g, b)
                    } else null
                }.getOrNull()
            }
            if (extractedColor != null) {
                dominantColorState.value = extractedColor
            }
            isImageLoaded.value = true
        } else {
            isImageLoaded.value = true
        }
    }

        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        
        
        if (showSavePlaylistDialog) {
            val trackIds = songsState.value.mapNotNull { it.id }
            AddToPlaylistBottomSheet(
                onDismissRequest = { showSavePlaylistDialog = false },
                apiBaseUrl = apiUrl,
                trackIds = trackIds,
                useYouTubePlaylists = isYouTubePlaylist || isYouTubeAlbum,
                onPlaylistSelected = { success ->
                    if (success) {
                        android.widget.Toast.makeText(context, "Saved ${trackIds.size} tracks to playlist", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(context, "Failed to save tracks", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    showSavePlaylistDialog = false
                }
            )
        }

        
        val navigationBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val listBottomPadding = if (isPlayerVisible) 132.dp + navigationBottomPadding else 32.dp + navigationBottomPadding

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 0.dp, bottom = listBottomPadding)
        ) {
            
            item {
                if (isFavorites) {
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(favoritesHeroBackground)
                            .padding(bottom = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fav),
                                contentDescription = null,
                                tint = Color(0xFFF13950),
                                modifier = Modifier.size(280.dp)
                            )
                        }
                        
                        
                        Text(
                            text = "Favourite Songs ★",
                            color = favoritesPrimaryTextColor,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SFProDisplayFontFamily
                        )
                        
                        
                        val user = AuthPreferences.getUser(context)
                        Text(
                            text = user?.firstName?.uppercase() ?: "USER NAME",
                            color = favoritesPrimaryTextColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = SFProDisplayFontFamily,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        
                        
                        val updatedText = if (lastUpdatedState.value != null) {
                            val diffSec = (System.currentTimeMillis() / 1000) - lastUpdatedState.value!!
                            when {
                                diffSec < 60 -> "Updated just now"
                                diffSec < 3600 -> "Updated ${diffSec / 60}m ago"
                                diffSec < 86400 -> "Updated ${diffSec / 3600}h ago"
                                else -> "Updated ${diffSec / 86400}d ago"
                            }
                        } else {
                            "Updated recently"
                        }

                        Text(
                            text = updatedText,
                            color = favoritesSecondaryTextColor,
                            fontSize = 14.sp,
                            fontFamily = SFProDisplayFontFamily,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                
                                Button(
                                    onClick = { 
                                        if (songsState.value.isNotEmpty()) {
                                            onSongClick(songsState.value, 0)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = favoritesPrimaryButtonColor),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    elevation = ButtonDefaults.buttonElevation(
                                        defaultElevation = 0.dp,
                                        pressedElevation = 0.dp
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow, 
                                            contentDescription = null, 
                                            modifier = Modifier.size(22.dp), 
                                            tint = favoritesPrimaryButtonContentColor
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Play", 
                                            fontSize = 16.sp, 
                                            fontWeight = FontWeight.Bold, 
                                            color = favoritesPrimaryButtonContentColor,
                                            fontFamily = SFProDisplayFontFamily
                                        )
                                    }
                                }
                                
                                
                                Button(
                                    onClick = { 
                                        if (songsState.value.isNotEmpty()) {
                                            onSongClick(songsState.value.shuffled(), 0)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = favoritesSecondaryButtonColor),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    elevation = ButtonDefaults.buttonElevation(
                                        defaultElevation = 0.dp,
                                        pressedElevation = 0.dp
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.shuffle), 
                                            contentDescription = null, 
                                            modifier = Modifier.size(24.dp), 
                                            tint = favoritesSecondaryButtonContentColor
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Shuffle", 
                                            fontSize = 16.sp, 
                                            fontWeight = FontWeight.Bold, 
                                            color = favoritesSecondaryButtonContentColor,
                                            fontFamily = SFProDisplayFontFamily
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { showSavePlaylistDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = favoritesSecondaryButtonColor),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                contentPadding = PaddingValues(0.dp),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 0.dp,
                                    pressedElevation = 0.dp
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Add, 
                                        contentDescription = null, 
                                        modifier = Modifier.size(22.dp), 
                                        tint = favoritesSecondaryButtonContentColor
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Save Playlist", 
                                        fontSize = 16.sp, 
                                        fontWeight = FontWeight.Bold, 
                                        color = favoritesSecondaryButtonContentColor,
                                        fontFamily = SFProDisplayFontFamily
                                    )
                                }
                            }
                        }
                    }
                } else {
                    
                    val bgColor = MaterialTheme.colorScheme.background
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        dominantColorState.value.copy(alpha = 0.3f),
                                        bgColor
                                    )
                                )
                            )
                            .padding(top = 80.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        
                        CoverArt(
                            coverUrl = coverUrlToUse ?: "",
                            fallbackColor = dominantColorState.value,
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(24.dp))
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        
                        Text(
                            text = currentPlaylistState.value.title,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        
                        
                        if (!currentPlaylistState.value.subtitle.isNullOrBlank()) {
                            Text(
                                text = currentPlaylistState.value.subtitle ?: "",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { 
                                    if (songsState.value.isNotEmpty()) onSongClick(songsState.value, 0)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).height(48.dp),
                                contentPadding = PaddingValues(0.dp),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 0.dp,
                                    pressedElevation = 0.dp
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow, 
                                        contentDescription = null, 
                                        modifier = Modifier.size(20.dp), 
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Play", 
                                        fontSize = 15.sp, 
                                        fontWeight = FontWeight.Bold, 
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                            
                            Button(
                                onClick = { 
                                    if (songsState.value.isNotEmpty()) onSongClick(songsState.value.shuffled(), 0)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).height(48.dp),
                                contentPadding = PaddingValues(0.dp),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 0.dp,
                                    pressedElevation = 0.dp
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.shuffle), 
                                        contentDescription = null, 
                                        modifier = Modifier.size(22.dp), 
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Shuffle", 
                                        fontSize = 15.sp, 
                                        fontWeight = FontWeight.Bold, 
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                    }
                }
            }

            
            if (isLoadingState.value) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            } else {
                itemsIndexed(songsState.value, key = { _, song -> song.id ?: song.title + song.artist }) { index, song ->
                    AppleMusicSongRow(
                        song = song,
                        showFavoriteStar = true,
                        onClick = { onSongClick(songsState.value, index) },
                        onRemoveFromPlaylistClick = if (playlist.kind == "user_playlist" && song.id != null) {
                            {
                                scope.launch {
                                    val token = AuthPreferences.getEffectiveToken(context)
                                    val success = removeTrackFromPlaylist(apiUrl, playlist.id, song.id, context, token)
                                    if (success) {
                                        
                                        val mutableSongs = songsState.value.toMutableList()
                                        mutableSongs.removeAt(index)
                                        songsState.value = mutableSongs
                                        android.widget.Toast.makeText(context, "Removed from playlist", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Failed to remove", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } else null,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick
                    )
                }
                
                
                if (isMoreLoadingState.value) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
                
                
                if (songsState.value.isNotEmpty()) {
                    item {
                        val totalSongs = songsState.value.size
                        val totalSeconds = songsState.value.sumOf { it.durationSec ?: 0 }
                        val totalMinutes = totalSeconds / 60
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp, bottom = 8.dp, start = 16.dp, end = 16.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "$totalSongs songs, $totalMinutes minutes",
                                color = Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarContentHeight = 64.dp
        val navBarTotalHeight = statusBarHeight + navBarContentHeight
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(navBarTotalHeight)
        ) {
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarHeight + 10.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = if (isFavorites && topBarAlpha < 0.5f) {
                                listOf(Color.Transparent, Color.Transparent) 
                            } else {
                                listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.25f), Color.Transparent)
                            }
                        )
                    )
            )

            
            val navBgColor = MaterialTheme.colorScheme.background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(navBgColor.copy(alpha = topBarAlpha))
            )
            
            
            if (topBarAlpha > 0.95f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                )
            }
            
            
            androidx.compose.animation.AnimatedVisibility(
                visible = topBarAlpha > 0.85f,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            ) {
                Text(
                    text = if (isFavorites) "Favourite Songs" else currentPlaylistState.value.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SFProDisplayFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 70.dp)
                )
            }

            
            val iconTint = MaterialTheme.colorScheme.onBackground
            
            val buttonBgColor = if (topBarAlpha > 0.5f) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(navBarContentHeight)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .background(buttonBgColor, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft, 
                        contentDescription = "Back", 
                        tint = iconTint,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(buttonBgColor),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isDownloadingAnyFlow = remember(songsState.value) {
                        DownloadHelper.downloadProgress.map { progressMap ->
                            songsState.value.any { it.id != null && progressMap.containsKey(it.id) }
                        }.distinctUntilChanged()
                    }
                    val isDownloadingAny by isDownloadingAnyFlow.collectAsState(initial = false)
                    
                    if (isDownloadingAny) {
                        Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = iconTint,
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        val isYouTubePlaylist = playlist.id.startsWith("MPREb_") || playlist.id.startsWith("yt_")
                        val isYouTubeAlbum = playlist.id.startsWith("MPREb_")
                        
                        if (isYouTubePlaylist || isYouTubeAlbum) {
                            if (isYouTubeSaved) {
                                IconButton(
                                    onClick = { DownloadHelper.downloadAll(context, songsState.value, apiUrl) },
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Download All",
                                        tint = iconTint,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val playlistIdToUse = youtubePlaylistId
                                            if (playlistIdToUse != null) {
                                                val result = com.metrolist.innertube.YouTube.likePlaylist(playlistIdToUse, true)
                                                withContext(Dispatchers.Main) {
                                                    if (result.isSuccess) {
                                                        isYouTubeSaved = true
                                                        
                                                        val current = DataCache.savedYouTubePlaylistIds.value.toMutableSet()
                                                        current.add(playlistIdToUse)
                                                        current.add(playlist.id)
                                                        DataCache.savedYouTubePlaylistIds.value = current
                                                        android.widget.Toast.makeText(context, "Saved to library", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        android.widget.Toast.makeText(context, "Failed to save to library", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    android.widget.Toast.makeText(context, "Loading playlist info...", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Save to library",
                                        tint = iconTint,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        } else {
                            IconButton(
                                onClick = { DownloadHelper.downloadAll(context, songsState.value, apiUrl) },
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = "Download All",
                                    tint = iconTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    IconButton(onClick = { menuExpanded.value = true }, modifier = Modifier.size(42.dp)) {
                        Icon(
                            imageVector = Icons.Default.MoreVert, 
                            contentDescription = "More", 
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (!isFavorites) {
                        if (menuExpanded.value) {
                            PlaylistBottomSheet(
                                onDismissRequest = { menuExpanded.value = false },
                                onShareClick = {
                                    if (isYouTubePlaylist || isYouTubeAlbum) {
                                        val ytEndpoint = if (currentPlaylistState.value.endpoint.isNullOrBlank()) playlist.id else currentPlaylistState.value.endpoint!!
                                        val shareUrl = "https://music.youtube.com/playlist?list=$ytEndpoint"
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, shareUrl)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share Playlist"))
                                    } else {
                                        onShareClick?.invoke(currentPlaylistState.value)
                                    }
                                },
                                onRenameClick = if (isOwnerPlaylist && onRenameClick != null) {
                                    { onRenameClick(currentPlaylistState.value) }
                                } else null,
                                onDeleteClick = if (isOwnerPlaylist && onDeleteClick != null) {
                                    { onDeleteClick(currentPlaylistState.value) }
                                } else null,
                                onFavouriteClick = if (hasBulkTracks) {
                                    {
                                        scope.launch(Dispatchers.IO) {
                                            val addedCount = addSongsToFavorites(
                                                apiBaseUrl = apiUrl,
                                                songs = songsState.value,
                                                context = context,
                                                token = AuthPreferences.getEffectiveToken(context)
                                            )
                                            val message = when {
                                                addedCount > 0 -> "Added $addedCount tracks to favourites"
                                                areAllTracksFavorite -> "All tracks are already in favourites"
                                                else -> "Failed to add tracks to favourites"
                                            }
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else null,
                                onSyncClick = if ((isYouTubePlaylist || isYouTubeAlbum) && isYouTubeSaved) {
                                    {
                                        val playlistIdToSync = youtubePlaylistId
                                        if (playlistIdToSync != null) {
                                            scope.launch(Dispatchers.IO) {
                                                withContext(Dispatchers.Main) {
                                                    android.widget.Toast.makeText(context, "Syncing playlist...", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                val result = com.metrolist.innertube.YouTube.playlist(playlistIdToSync)
                                                result.onSuccess { playlistPage ->
                                                    val ytSongs = playlistPage.songs
                                                    withContext(Dispatchers.Main) {
                                                        android.widget.Toast.makeText(context, "Synced ${ytSongs.size} tracks", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }.onFailure {
                                                    withContext(Dispatchers.Main) {
                                                        android.widget.Toast.makeText(context, "Sync failed", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else null,
                                onAddToPlaylistClick = if (hasBulkTracks) {
                                    { showSavePlaylistDialog = true }
                                } else null,
                                onPlayNextClick = if (songsState.value.isNotEmpty()) {
                                    {
                                        playerManager.playNext(songsState.value)
                                        android.widget.Toast.makeText(context, "Added to play next", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else null,
                                onDownloadClick = if (songsState.value.isNotEmpty()) {
                                    {
                                        DownloadHelper.downloadAll(context, songsState.value, apiUrl)
                                        if (menuExpanded.value) {
                                            menuExpanded.value = false
                                        }
                                    }
                                } else null,
                                onSaveToLibraryClick = if (isYouTubePlaylist || isYouTubeAlbum) {
                                    {
                                        scope.launch(Dispatchers.IO) {
                                            val playlistIdToUse = youtubePlaylistId
                                            if (playlistIdToUse != null) {
                                                if (isYouTubeSaved) {
                                                    
                                                    val result = com.metrolist.innertube.YouTube.likePlaylist(playlistIdToUse, false)
                                                    withContext(Dispatchers.Main) {
                                                        if (result.isSuccess) {
                                                            isYouTubeSaved = false
                                                            
                                                            val current = DataCache.savedYouTubePlaylistIds.value.toMutableSet()
                                                            current.remove(playlistIdToUse)
                                                            current.remove(playlist.id)
                                                            DataCache.savedYouTubePlaylistIds.value = current
                                                            android.widget.Toast.makeText(context, "Removed from library", android.widget.Toast.LENGTH_SHORT).show()
                                                            menuExpanded.value = false
                                                        } else {
                                                            android.widget.Toast.makeText(context, "Failed to remove from library", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                } else {
                                                    
                                                    val result = com.metrolist.innertube.YouTube.likePlaylist(playlistIdToUse, true)
                                                    withContext(Dispatchers.Main) {
                                                        if (result.isSuccess) {
                                                            isYouTubeSaved = true
                                                            
                                                            val current = DataCache.savedYouTubePlaylistIds.value.toMutableSet()
                                                            current.add(playlistIdToUse)
                                                            current.add(playlist.id)
                                                            DataCache.savedYouTubePlaylistIds.value = current
                                                            android.widget.Toast.makeText(context, "Saved to library", android.widget.Toast.LENGTH_SHORT).show()
                                                            menuExpanded.value = false
                                                        } else {
                                                            android.widget.Toast.makeText(context, "Failed to save to library", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    android.widget.Toast.makeText(context, "Loading playlist info...", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                } else null,
                                isFavorite = areAllTracksFavorite,
                                isSavedToLibrary = isYouTubeSaved,
                                isYouTube = isYouTubePlaylist || isYouTubeAlbum
                            )
                        }
                        
                        
                    } else {
                        
                        
                        DropdownMenu(
                            expanded = menuExpanded.value,
                            onDismissRequest = { menuExpanded.value = false },
                            containerColor = Color(0xFF1C1C1E)
                        ) {
                            DropdownMenuItem(
                                text = { 
                                    Text(
                                        text = "Share", 
                                        color = Color.White,
                                        fontFamily = SFProDisplayFontFamily
                                    ) 
                                },
                                onClick = { 
                                    onShareClick?.invoke(playlist)
                                    menuExpanded.value = false
                                },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = Color.White) }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun AppleMusicSongRow(
    song: Song, 
    showFavoriteStar: Boolean = true, 
    onClick: () -> Unit,
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {}
) {
    val menuExpanded = remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val favoriteIds by DataCache.favoriteIds.collectAsState()
    val isFavorite = song.id != null && favoriteIds.contains(song.id)
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    
    val playerManager: MusicPlayerManager = viewModel()
    val jamId = playerManager.jamId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(18.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isFavorite && showFavoriteStar) {
                Icon(
                    painter = painterResource(id = R.drawable.fav),
                    contentDescription = "Favorite",
                    tint = Color(0xFFE24A5A),
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        CoverArt(
            coverUrl = song.coverUrl,
            fallbackColor = song.color,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                color = Color.Gray,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val apiUrl = ApiPreferences.getApiUrl(context)
                DownloadButton(song = song, apiUrl = apiUrl)
                SongMoreOrSelectButton(
                    songId = song.id,
                    menuExpanded = menuExpanded.value,
                    onMenuClick = { menuExpanded.value = true },
                    modifier = Modifier.size(32.dp)
                )
            }

            SongContextMenu(
                expanded = menuExpanded.value,
                onDismissRequest = { menuExpanded.value = false },
                song = song,
                isFavorite = isFavorite,
                onAddClick = {
                    if (song.id != null) {
                        if (jamId != null) {
                            playerManager.addToJamQueue(song.id)
                            android.widget.Toast.makeText(context, "Added to Jam Queue", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            playerManager.addToQueue(song)
                            android.widget.Toast.makeText(context, "Added to Queue", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onFavouriteClick = {
                    if (song.id != null) {
                        coroutineScope.launch {
                            val userToken = AuthPreferences.getEffectiveToken(context)
                            val apiUrl = ApiPreferences.getApiUrl(context)
                            toggleFavorite(apiUrl, song, context, userToken)
                        }
                    }
                },
                onPlayNextClick = {
                    if (song.id != null) {
                         playerManager.playNext(song)
                    }
                },
                onAddToPlaylistClick = { showAddToPlaylist = true },
                onRemoveFromPlaylistClick = onRemoveFromPlaylistClick,
                onDownloadClick = if (!DownloadHelper.isDownloaded(context, song.id ?: "")) {
                    {
                        val apiUrl = ApiPreferences.getApiUrl(context)
                        DownloadHelper.downloadTrack(context, song, apiUrl)
                    }
                } else null,
                onRemoveDownloadClick = if (DownloadHelper.isDownloaded(context, song.id ?: "")) {
                    { DownloadHelper.removeDownloadedSong(context, song.id ?: "") }
                } else null,
                onAlbumClick = song.browsableAlbumId?.let { id ->
                    { onAlbumClick(id) }
                },
                onArtistClick = if (song.artist.isNotBlank()) {
                    { onArtistClick(song.artist) }
                } else null,
                onShareClick = song.id?.takeIf(::isShareableStreamXTrackId)?.let { trackId ->
                    {
                        val apiUrl = ApiPreferences.getApiUrl(context)
                        val shareUrl = buildSharedTrackWebLink(trackId, apiUrl)
                        shareTextLink(context, "Check out this track: $shareUrl", "Share Track")
                    }
                }
            )            
            if (showAddToPlaylist && song.id != null) {
                val apiUrl = ApiPreferences.getApiUrl(context)
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                AddToPlaylistBottomSheet(
                    onDismissRequest = { showAddToPlaylist = false },
                    apiBaseUrl = apiUrl,
                    trackId = song.id,
                    useYouTubePlaylists = song.id.startsWith("yt_"),
                    onPlaylistSelected = { success ->
                        val msg = if (success) "Added to playlist" else "Already exists or failed"
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
