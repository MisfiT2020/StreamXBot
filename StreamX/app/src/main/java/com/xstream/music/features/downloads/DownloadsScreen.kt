package com.xstream.music.features.downloads

import com.xstream.music.player.service.*
import com.xstream.music.player.manager.*
import com.xstream.music.ui.components.*
import com.xstream.music.realtime.websocket.*
import com.xstream.music.core.preferences.*
import com.xstream.music.core.cache.*
import com.xstream.music.core.utils.*
import com.xstream.music.data.model.*
import com.xstream.music.data.api.*
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xstream.music.R
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.Song
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.DownloadButton
import com.xstream.music.ui.components.SongContextMenu
import com.xstream.music.ui.components.SongMoreOrSelectButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    isPlayerVisible: Boolean = false,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var isRefreshing by remember { mutableStateOf(false) }
    val totalSongs = songs.size
    val totalMinutes = songs.sumOf { it.durationSec ?: 0 } / 60
    val summaryText = "$totalSongs songs, $totalMinutes minutes"

    val loadSongs = {
        isLoading = true
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val downloadedSongs = DownloadHelper.getDownloadedSongs(context)
            withContext(Dispatchers.Main) {
                songs = downloadedSongs
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadSongs()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Downloads",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isRefreshing = true
                            DownloadHelper.syncDownloadedSongs(context)
                            
                            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                kotlinx.coroutines.delay(1500)
                                loadSongs()
                                isRefreshing = false
                            }
                        },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onBackground,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (songs.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No downloaded songs found", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = if (isPlayerVisible) 100.dp else 16.dp)
                ) {
                    items(songs) { song ->
                        DownloadedSongRow(
                            song = song,
                            onClick = { onSongClick(songs, songs.indexOf(song)) },
                            onRemove = {
                                songs = DownloadHelper.getDownloadedSongs(context)
                            },
                            onAlbumClick = onAlbumClick,
                            onArtistClick = onArtistClick
                        )
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

                if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                }
                }
                }
                }

                @Composable
                fun DownloadedSongRow(
                    song: Song, 
                    onClick: () -> Unit, 
                    onRemove: () -> Unit, 
                    onAlbumClick: (String) -> Unit = {},
                    onArtistClick: (String) -> Unit = {}
                ) {
                val context = LocalContext.current
                val favoriteIds by DataCache.favoriteIds.collectAsState()
                val isFavorite = song.id != null && favoriteIds.contains(song.id)
                val menuExpanded = remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()
                var showAddToPlaylist by remember { mutableStateOf(false) }
                val playerManager: MusicPlayerManager = viewModel()

                Row(
                modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
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
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                Text(song.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
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
                onRemoveDownloadClick = {
                    if (song.id != null) {
                        DownloadHelper.removeDownloadedSong(context, song.id)
                        onRemove()
                    }
                },
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




