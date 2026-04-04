package com.xstream.music.features.mix

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import android.graphics.BitmapFactory
import androidx.compose.material.icons.filled.Refresh
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.Song
import com.xstream.music.player.manager.MusicPlayerManager
import com.xstream.music.ui.components.AddToPlaylistBottomSheet
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.DownloadButton
import com.xstream.music.ui.components.SongContextMenu
import com.xstream.music.ui.components.SongMoreOrSelectButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomMixScreen(
    songs: List<Song>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    isPlayerVisible: Boolean = false
) {
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Random Mix",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (songs.isEmpty() && isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = if (isPlayerVisible) 100.dp else 24.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    RandomMixSongRow(
                        song = song,
                        onClick = { onSongClick(songs, index) },
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick
                    )
                }

                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RandomMixSongRow(
    song: Song, 
    onClick: () -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {}
) {
    val favoriteIds by DataCache.favoriteIds.collectAsState()
    val isFavorite = song.id != null && favoriteIds.contains(song.id)
    val menuExpanded = remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
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
            if (isFavorite) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.fav),
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
                color = MaterialTheme.colorScheme.onSurface,
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
                            val userToken = AuthPreferences.getUser(context)?.token
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
                onDownloadClick = if (!DownloadHelper.isDownloaded(context, song.id ?: "")) {
                    {
                        val apiUrl = ApiPreferences.getApiUrl(context)
                        DownloadHelper.downloadTrack(context, song, apiUrl)
                    }
                } else null,
                onRemoveDownloadClick = if (DownloadHelper.isDownloaded(context, song.id ?: "")) {
                    { DownloadHelper.removeDownloadedSong(context, song.id ?: "") }
                } else null,
                onAlbumClick = song.browsableAlbumId?.let { albumId ->
                    { onAlbumClick(albumId) }
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


