package com.xstream.music.features.album

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.graphics.Brush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.model.AlbumData
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.Song
import com.xstream.music.player.manager.MusicPlayerManager
import com.xstream.music.ui.components.AddToPlaylistBottomSheet
import com.xstream.music.ui.components.AlbumBottomSheet
import com.xstream.music.ui.components.AlbumContextMenu
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.DownloadButton
import com.xstream.music.ui.components.SongContextMenu
import com.xstream.music.ui.theme.SFProDisplayFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    albumId: String?,
    apiUrl: String,
    onBack: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    isPlayerVisible: Boolean = false,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {}
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var albumData by remember { mutableStateOf<AlbumData?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    val listState = rememberLazyListState()
    var topMenuExpanded by remember { mutableStateOf(false) }
    var addButtonExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(albumId) {
        albumId?.let { id ->
            isLoading = true
            val result = fetchAlbum(apiUrl, id, context)
            albumData = result.first
            songs = result.second
            isLoading = false
        }
    }

    val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
    val bgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.background
    val onBgColor = MaterialTheme.colorScheme.onBackground
    val accentColor = MaterialTheme.colorScheme.primary
    val buttonBgColor = if (isAmoled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    val buttonContentColor = MaterialTheme.colorScheme.onSurface
    val favoriteIds by DataCache.favoriteIds.collectAsState()
    val savedAlbumIds by DataCache.savedAlbumIds.collectAsState()
    val isSaved = albumId?.let { savedAlbumIds.contains(it) } ?: false
    val savedYouTubePlaylistIds by DataCache.savedYouTubePlaylistIds.collectAsState()
    var isYouTubeSaved by remember { mutableStateOf(false) }
    var youtubePlaylistId by remember { mutableStateOf<String?>(null) }
    val playerManager: MusicPlayerManager = androidx.lifecycle.viewmodel.compose.viewModel()
    val bulkTrackIds = songs.mapNotNull { it.id }.distinct()
    val hasBulkTracks = bulkTrackIds.isNotEmpty()
    val areAllTracksFavorite = hasBulkTracks && bulkTrackIds.all { it in favoriteIds }
    val totalSongs = albumData?.tracks_count?.takeIf { it > 0 } ?: songs.size
    val totalMinutes = ((albumData?.duration_total?.takeIf { it > 0 } ?: songs.sumOf { it.durationSec ?: 0 }) / 60)
    val summaryText = "$totalSongs songs, $totalMinutes minutes"
    
    
    LaunchedEffect(albumId, savedYouTubePlaylistIds, youtubePlaylistId) {
        if (albumId?.startsWith("MPREb_") == true || albumId?.startsWith("yt_") == true) {
            val ytId = albumId.removePrefix("yt_")
            
            
            if (youtubePlaylistId == null) {
                withContext(Dispatchers.IO) {
                    val albumPage = com.metrolist.innertube.YouTube.album(ytId).getOrNull()
                    youtubePlaylistId = albumPage?.album?.playlistId
                }
            }
            
            
            isYouTubeSaved = savedYouTubePlaylistIds.contains(ytId) || 
                             savedYouTubePlaylistIds.contains(albumId) ||
                             (youtubePlaylistId != null && savedYouTubePlaylistIds.contains(youtubePlaylistId))
        }
    }

    var showAddToPlaylist by remember { mutableStateOf(false) }

    val topBarAlpha by remember {
        derivedStateOf {
            val threshold = 400f
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset.toFloat() / threshold).coerceIn(0f, 1f)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        androidx.compose.animation.Crossfade(targetState = isLoading, label = "LoadingCrossfade") { loading ->
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accentColor)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 0.dp, bottom = if (isPlayerVisible) 100.dp else 24.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Gray.copy(alpha = 0.2f),
                                            bgColor
                                        )
                                    )
                                )
                                .padding(top = 100.dp, bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val imageUrl = albumData?.cover_url ?: songs.firstOrNull()?.coverUrl
                            if (imageUrl != null) {
                                CoverArt(
                                    coverUrl = imageUrl,
                                    fallbackColor = Color.DarkGray,
                                    modifier = Modifier
                                        .size(240.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(240.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.DarkGray)
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                text = albumData?.title ?: "Unknown Album",
                                color = onBgColor,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = SFProDisplayFontFamily,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = albumData?.artist ?: songs.firstOrNull()?.artist ?: "Unknown Artist",
                                color = accentColor,
                                fontSize = 18.sp,
                                fontFamily = SFProDisplayFontFamily,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(horizontal = 24.dp)
                                    .clickable {
                                        val artist = albumData?.artist ?: songs.firstOrNull()?.artist
                                        if (artist != null) {
                                            onArtistClick(artist)
                                        }
                                    }
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            val firstSong = songs.firstOrNull()
                            val type = firstSong?.type?.uppercase() ?: "FLAC"
                            val isLossless = type.contains("FLAC") || type.contains("ALAC") || (firstSong?.samplingRateHz?.let { it >= 44100 } ?: false)
                            val infoText = buildString {
                                append("Album")
                                append(" • ")
                                append(type)
                                if (isLossless) {
                                    append(" • Lossless")
                                }
                            }

                            Text(
                                text = infoText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontFamily = SFProDisplayFontFamily,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                            ) {
                                Button(
                                    onClick = { 
                                        if (songs.isNotEmpty()) {
                                            onSongClick(songs, 0)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = buttonBgColor),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f).height(56.dp),
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
                                            contentDescription = "Play", 
                                            tint = buttonContentColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Play", 
                                            color = buttonContentColor, 
                                            fontSize = 16.sp, 
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = SFProDisplayFontFamily
                                        )
                                    }
                                }
                                
                                Button(
                                    onClick = { 
                                        if (songs.isNotEmpty()) {
                                            onSongClick(songs.shuffled(), 0)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = buttonBgColor),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f).height(56.dp),
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
                                            contentDescription = "Shuffle", 
                                            tint = buttonContentColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Shuffle", 
                                            color = buttonContentColor, 
                                            fontSize = 16.sp, 
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = SFProDisplayFontFamily
                                        )
                                    }
                                }
                            }
                        }
                    }

                    itemsIndexed(songs, key = { _, song -> song.id ?: song.title + song.artist }) { index, song ->
                        val isFavorite = DataCache.favoriteIds.collectAsState().value.contains(song.id)
                        var contextMenuExpanded by remember { mutableStateOf(false) }

                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSongClick(songs, index) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = Color.Gray,
                                    fontSize = 14.sp,
                                    modifier = Modifier.width(30.dp),
                                    textAlign = TextAlign.Center
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        color = onBgColor,
                                        fontSize = 16.sp,
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

                                DownloadButton(song = song, apiUrl = apiUrl)

                                Box {
                                    IconButton(onClick = { contextMenuExpanded = true }) {
                                        Icon(Icons.Default.MoreHoriz, contentDescription = "More", tint = Color.Gray)
                                    }
                                    
                                    SongContextMenu(
                                        expanded = contextMenuExpanded,
                                        onDismissRequest = { contextMenuExpanded = false },
                                        song = song,
                                        isFavorite = isFavorite,
                                        onAddClick = {
                                            
                                        },
                                        onFavouriteClick = {
                                            scope.launch {
                                                val userToken = AuthPreferences.getEffectiveToken(context)
                                                toggleFavorite(apiUrl, song, context, userToken)
                                            }
                                        },
                                        onPlayNextClick = { 
                                            if (song.id != null) {
                                                playerManager.playNext(song)
                                            }
                                        },
                                        onDownloadClick = if (!DownloadHelper.isDownloaded(context, song.id ?: "")) {
                                            { DownloadHelper.downloadTrack(context, song, apiUrl) }
                                        } else null,
                                        onRemoveDownloadClick = if (DownloadHelper.isDownloaded(context, song.id ?: "")) {
                                            { DownloadHelper.removeDownloadedSong(context, song.id ?: "") }
                                        } else null,
                                        onShareClick = song.id?.takeIf(::isShareableStreamXTrackId)?.let { trackId ->
                                            {
                                                val shareUrl = buildSharedTrackWebLink(trackId, apiUrl)
                                                shareTextLink(context, "Check out this track: $shareUrl", "Share Track")
                                            }
                                        }
                                    )
                                }
                            }
                            
                            if (index < songs.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 46.dp, end = 16.dp),
                                    color = onBgColor.copy(alpha = 0.1f),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }

                    if (songs.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp, horizontal = 16.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = summaryText,
                                    color = Color.Gray,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
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
                    .fillMaxSize()
                    .background(bgColor.copy(alpha = topBarAlpha))
            )
            
            if (topBarAlpha > 0.95f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(onBgColor.copy(alpha = 0.1f))
                )
            }

            
            androidx.compose.animation.AnimatedVisibility(
                visible = topBarAlpha > 0.85f,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            ) {
                Text(
                    text = albumData?.title ?: "Album",
                    color = onBgColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SFProDisplayFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 70.dp)
                )
            }

            val iconTint = onBgColor
            val btnBgColor = if (topBarAlpha > 0.5f) Color.Transparent else Color.Black.copy(alpha = 0.35f)

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
                        .background(btnBgColor, CircleShape)
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
                        .background(btnBgColor),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isYouTube = albumId?.startsWith("MPREb_") == true || albumId?.startsWith("yt_") == true
                    
                    if (isYouTube) {
                        if (isYouTubeSaved) {
                            IconButton(
                                onClick = { DownloadHelper.downloadAll(context, songs, apiUrl) },
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Download", tint = iconTint, modifier = Modifier.size(22.dp))
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
                                                    albumId?.let { current.add(it) }
                                                    DataCache.savedYouTubePlaylistIds.value = current
                                                    android.widget.Toast.makeText(context, "Saved to library", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    android.widget.Toast.makeText(context, "Failed to save to library", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "Loading album info...", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Save to library", tint = iconTint, modifier = Modifier.size(22.dp))
                            }
                        }
                    } else if (isSaved) {
                        IconButton(
                            onClick = { DownloadHelper.downloadAll(context, songs, apiUrl) },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Download", tint = iconTint, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        IconButton(
                            onClick = { 
                                scope.launch {
                                    albumId?.let { id ->
                                        val token = AuthPreferences.getEffectiveToken(context)
                                        val success = saveAlbum(apiUrl, id, context, token)
                                        if (success) {
                                            DataCache.savedAlbumIds.value += id
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Save Album", tint = iconTint, modifier = Modifier.size(22.dp))
                        }
                    }

                    Box {
                        IconButton(onClick = { topMenuExpanded = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = iconTint, modifier = Modifier.size(20.dp))
                        }
                        
                        if (topMenuExpanded) {
                            AlbumBottomSheet(
                                onDismissRequest = { topMenuExpanded = false },
                                isYouTube = isYouTube,
                                isFavorite = areAllTracksFavorite,
                                isSavedToLibrary = isYouTubeSaved,
                                onSyncClick = if (isYouTube && isYouTubeSaved && youtubePlaylistId != null) {
                                    {
                                        scope.launch(Dispatchers.IO) {
                                            android.widget.Toast.makeText(context, "Syncing album...", android.widget.Toast.LENGTH_SHORT).show()
                                            val result = com.metrolist.innertube.YouTube.album(albumId?.removePrefix("yt_") ?: "")
                                            result.onSuccess { albumPage ->
                                                val ytSongs = albumPage.songs
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
                                } else null,
                                onSaveToLibraryClick = if (isYouTube) {
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
                                                            albumId?.let { current.remove(it) }
                                                            DataCache.savedYouTubePlaylistIds.value = current
                                                            android.widget.Toast.makeText(context, "Removed from library", android.widget.Toast.LENGTH_SHORT).show()
                                                            topMenuExpanded = false
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
                                                            albumId?.let { current.add(it) }
                                                            DataCache.savedYouTubePlaylistIds.value = current
                                                            android.widget.Toast.makeText(context, "Saved to library", android.widget.Toast.LENGTH_SHORT).show()
                                                            topMenuExpanded = false
                                                        } else {
                                                            android.widget.Toast.makeText(context, "Failed to save to library", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    android.widget.Toast.makeText(context, "Loading album info...", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                } else null,
                                onAddToPlaylistClick = if (hasBulkTracks) {
                                    { showAddToPlaylist = true }
                                } else null,
                                onFavouriteClick = if (hasBulkTracks) {
                                    {
                                        scope.launch(Dispatchers.IO) {
                                            val addedCount = addSongsToFavorites(
                                                apiBaseUrl = apiUrl,
                                                songs = songs,
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
                                onPlayNextClick = if (songs.isNotEmpty()) {
                                    {
                                        playerManager.playNext(songs)
                                        android.widget.Toast.makeText(context, "Added album to play next", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else null,
                                onDownloadClick = if (songs.isNotEmpty()) {
                                    { DownloadHelper.downloadAll(context, songs, apiUrl) }
                                } else null,
                                onShareClick = {
                                    val shareAlbumId = albumData?._id ?: albumId.orEmpty()
                                    val shareUrl = if (isShareableStreamXAlbumId(shareAlbumId)) {
                                        buildSharedAlbumWebLink(shareAlbumId, apiUrl)
                                    } else {
                                        ""
                                    }
                                    val shareText = if (shareUrl.isNotBlank()) {
                                        "Check out this album: $shareUrl"
                                    } else {
                                        albumData?.title ?: "Check out this album"
                                    }
                                    shareTextLink(context, shareText, "Share Album")
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showAddToPlaylist) {
            AddToPlaylistBottomSheet(
                onDismissRequest = { showAddToPlaylist = false },
                apiBaseUrl = apiUrl,
                trackIds = bulkTrackIds,
                useYouTubePlaylists = bulkTrackIds.firstOrNull()?.startsWith("yt_") == true,
                onPlaylistSelected = { success ->
                    if (success) {
                        android.widget.Toast.makeText(context, "Added ${bulkTrackIds.size} tracks to playlist", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(context, "Failed to add tracks", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    showAddToPlaylist = false
                }
            )
        }
    }
}

suspend fun fetchAlbum(apiUrl: String, albumId: String, context: android.content.Context): Pair<AlbumData?, List<Song>> {
    return if (albumId.startsWith("MPREb_") || albumId.startsWith("yt_")) {
        val ytId = albumId.removePrefix("yt_")
        val albumPage = withContext(Dispatchers.IO) {
            com.metrolist.innertube.YouTube.album(ytId).getOrNull()
        }
        
        val songs = albumPage?.songs?.mapNotNull { it.toSong() } ?: emptyList()
        val albumData = albumPage?.album?.let { album ->
            AlbumData(
                _id = album.browseId,
                artist = album.artists?.joinToString(", ") { it.name } ?: "Unknown",
                cover_url = album.thumbnail,
                duration_total = songs.sumOf { it.durationSec ?: 0 },
                title = album.title,
                tracks_count = songs.size
            )
        }
        Pair(albumData, songs)
    } else {
        fetchAlbum(apiUrl, albumId, context, null)
    }
}
