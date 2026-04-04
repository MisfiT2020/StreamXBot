package com.xstream.music.features.search

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.xstream.music.data.api.searchSongs
import com.xstream.music.data.model.ArtistItem
import com.xstream.music.data.model.SongArtist
import com.xstream.music.data.model.Song
import com.xstream.music.features.playlist.AppleMusicSongRow
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.SearchBar
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsScreen(
    query: String,
    apiUrl: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    isPlayerVisible: Boolean = false,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String, String?) -> Unit = { _, _ -> }
) {
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var ytContinuation by remember { mutableStateOf<String?>(null) }
    var totalResults by remember { mutableIntStateOf(0) }
    
    var isLoadingMore by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            isLoading = true
            page = 1
            songs = emptyList()
            artists = emptyList()
            
            withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
                val source = prefs.getString("search_source", "streamx") ?: "streamx"
                val channelId = prefs.getString("search_channel_id", "") ?: ""
                
                if (source == "youtube") {
                    val ytResults = searchYoutubeMusic(query, context)
                    withContext(Dispatchers.Main) {
                        val mappedSongs = ytResults.items.map { item -> item.toSongModel() }
                        songs = mappedSongs
                        totalResults = mappedSongs.size
                        ytContinuation = ytResults.continuation
                        hasMore = ytResults.continuation != null
                        isLoading = false
                    }
                } else if (source == "soundcloud") {
                    val scResults = searchSoundcloud(apiUrl, query, limit = 20, page = 1)
                    withContext(Dispatchers.Main) {
                        val mappedSongs = scResults.map { item -> item.toSongModel() }
                        songs = mappedSongs
                        totalResults = mappedSongs.size
                        hasMore = scResults.size >= 20 
                        isLoading = false
                    }
                } else {
                    // StreamX search - only fetch songs, no artists
                    val response = searchSongs(apiUrl, query, page = 1, limit = 20, channelId = channelId)
                    val newSongs = response.items.map { item -> item.toSongModel() }
                    
                    withContext(Dispatchers.Main) {
                        artists = emptyList()  // Don't show artists
                        songs = newSongs
                        totalResults = response.total
                        hasMore = newSongs.size < response.total
                        isLoading = false
                    }
                }
            }
        }
    }

    
    val shouldLoadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            
            !isLoading && !isLoadingMore && hasMore && totalItems > 0 && (lastVisibleItemIndex >= totalItems - 5)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore.value }.collect { shouldLoad ->
            if (shouldLoad) {
                isLoadingMore = true
                val nextPage = page + 1
                
                withContext(Dispatchers.IO) {
                    val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
                    val source = prefs.getString("search_source", "streamx") ?: "streamx"
                    val channelId = prefs.getString("search_channel_id", "") ?: ""
                    
                    if (source == "youtube") {
                        val currentContinuation = ytContinuation
                        if (currentContinuation != null) {
                            val ytResults = searchYoutubeMusicContinuation(currentContinuation, context)
                            withContext(Dispatchers.Main) {
                                val mappedSongs = ytResults.items.map { item -> item.toSongModel() }
                                songs = songs + mappedSongs
                                totalResults = songs.size
                                ytContinuation = ytResults.continuation
                                hasMore = ytResults.continuation != null
                                isLoadingMore = false
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                hasMore = false
                                isLoadingMore = false
                            }
                        }
                    } else if (source == "soundcloud") {
                        val scResults = searchSoundcloud(apiUrl, query, limit = 20, page = nextPage)
                        withContext(Dispatchers.Main) {
                            if (scResults.isEmpty()) {
                                hasMore = false
                            } else {
                                val mappedSongs = scResults.map { item -> item.toSongModel() }
                                songs = songs + mappedSongs
                                totalResults = songs.size
                                page = nextPage
                                hasMore = scResults.size >= 20
                            }
                            isLoadingMore = false
                        }
                    } else {
                        val response = searchSongs(apiUrl, query, page = nextPage, limit = 20, channelId = channelId)
                        val newSongs = response.items.map { item -> item.toSongModel() }
                        
                        withContext(Dispatchers.Main) {
                            if (newSongs.isNotEmpty()) {
                                songs = songs + newSongs
                                page = nextPage
                            }
                            hasMore = songs.size < response.total
                            isLoadingMore = false
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Column {
                            Text(
                                text = "Search Results",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (totalResults > 0) {
                                Text(
                                    text = "\"$query\"",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SearchBar(
                        initialQuery = query,
                        onSearch = { newQuery ->
                            if (newQuery.isNotBlank() && newQuery != query) {
                                onSearch(newQuery)
                            }
                        }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (songs.isEmpty() && artists.isEmpty() && !isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No results found for \"$query\"",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 16.sp
                    )
                }
            } else if (songs.isEmpty() && isLoading) {
                
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = if (isPlayerVisible) 100.dp else 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (artists.isNotEmpty()) {
                        item {
                            SearchSectionHeader(title = "Artists")
                        }
                        items(artists, key = { it.id }) { artist ->
                            ArtistSearchRow(
                                artist = artist,
                                onClick = { onArtistClick(artist.name, artist.id) }
                            )
                        }
                    }

                    if (songs.isNotEmpty()) {
                        if (artists.isNotEmpty()) {
                            item {
                                SearchSectionHeader(title = "Songs")
                            }
                        }
                    }

                    items(songs) { song ->
                        AppleMusicSongRow(
                            song = song,
                            showFavoriteStar = true,
                            onClick = { 
                                if (song.id?.startsWith("sc_") == true) {
                                    
                                    onSongClick(songs, songs.indexOf(song))
                                    scope.launch {
                                        val streamUrl = getSoundcloudStreamUrl(apiUrl, song.id)
                                        if (streamUrl != null) {
                                            val updatedSong = song.copy(localPath = streamUrl)
                                            val newList = songs.toMutableList()
                                            val index = songs.indexOf(song)
                                            newList[index] = updatedSong
                                            
                                            onSongClick(newList, index)
                                        } else {
                                            android.widget.Toast.makeText(context, "Stream not found", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    onSongClick(songs, songs.indexOf(song))
                                }
                            },
                            onAlbumClick = onAlbumClick,
                            onArtistClick = { artistName -> onArtistClick(artistName, song.primaryArtistId) }
                        )
                    }
                    
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun SearchItem.toSongModel(): Song {
    val mappedArtists = artists
        .filter { it.name.isNotBlank() }
        .ifEmpty {
            artist.split(",")
                .mapNotNull { raw ->
                    raw.trim().takeIf { it.isNotBlank() }?.let { SongArtist(name = it) }
                }
        }

    return Song(
        id = _id,
        title = title,
        artist = artist,
        artists = mappedArtists,
        album = album,
        albumId = album_id,
        durationSec = duration_sec,
        type = type,
        samplingRateHz = sampling_rate_hz,
        spotifyUrl = spotify_url,
        coverUrl = cover_url,
        color = Color(0xFF1E1E1E),
        bitrateKbps = null,
        fileSize = file_size?.takeIf { it > 0 }
    )
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun ArtistSearchRow(
    artist: ArtistItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val imageUrl = artist.image_url ?: artist.video_poster_url
        if (!imageUrl.isNullOrBlank()) {
            CoverArt(
                coverUrl = imageUrl,
                fallbackColor = Color.DarkGray,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val subtitle = when {
                !artist.hometown.isNullOrBlank() -> artist.hometown
                !artist.formed.isNullOrBlank() -> "Formed ${artist.formed}"
                !artist.born.isNullOrBlank() -> "Born ${artist.born}"
                !artist.genres.isNullOrEmpty() -> artist.genres.take(2).joinToString(" • ")
                else -> "Artist"
            }

            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
