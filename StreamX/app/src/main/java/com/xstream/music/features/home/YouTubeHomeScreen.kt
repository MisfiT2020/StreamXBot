package com.xstream.music.features.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.YouTube
import androidx.compose.foundation.lazy.itemsIndexed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import android.content.Context
import com.xstream.music.data.api.initYouTubeAuth
import com.xstream.music.data.api.upscaleYoutubeThumbnail
import com.xstream.music.data.model.LatestSongs
import com.xstream.music.data.model.LatestSongsPage
import com.xstream.music.data.model.Playlist
import com.xstream.music.data.model.Song
import com.xstream.music.features.auth.YouTubeLoginDialog
import com.xstream.music.features.playlist.AppleMusicSongRow
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.ProfileImage
import com.xstream.music.ui.components.SearchBar
import com.metrolist.innertube.utils.completed
import kotlinx.coroutines.launch

private object YouTubeHomeMemoryCache {
    var homePage: HomePage? = null
    var accountInfo: AccountInfo? = null
    var accountPlaylists: List<PlaylistItem>? = null
    var lastLoadedAtMs: Long = 0L
}

@Composable
fun YouTubeHomeScreen(
    onSongClick: (List<Song>, Int) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onSearchClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String, String?) -> Unit = { _, _ -> },
    onQuickPicksClick: (List<Song>, String) -> Unit = { _, _ -> },
    onSectionViewAllClick: (String, List<YTItem>) -> Unit = { _, _ -> },
    onDownloadsClick: () -> Unit = {},
    onStartJamClick: () -> Unit = {},
    onJoinJamClick: () -> Unit = {},
    activeJamId: String? = null,
    onActiveJamClick: () -> Unit = {},
    onLeaveJamClick: () -> Unit = {},
    onJamSongsChanged: (List<Song>) -> Unit = {},
    modifier: Modifier = Modifier,
    isPlayerVisible: Boolean = false,
    onFullScreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var homePage by remember { mutableStateOf(YouTubeHomeMemoryCache.homePage) }
    var accountInfo by remember { mutableStateOf(YouTubeHomeMemoryCache.accountInfo) }
    var accountPlaylists by remember { mutableStateOf(YouTubeHomeMemoryCache.accountPlaylists) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    val selectedChip = remember { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    val showHomeChips = remember { mutableStateOf(DataCache.isShowYouTubeHomeChipsEnabled(context)) }
    val showAccountPlaylists = remember { mutableStateOf(DataCache.isShowYouTubeAccountPlaylistsEnabled(context)) }
    val showSongSections = remember { mutableStateOf(DataCache.isShowYouTubeSongSectionsEnabled(context)) }
    val showBrowseSections = remember { mutableStateOf(DataCache.isShowYouTubeBrowseSectionsEnabled(context)) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val jamSongs = remember(homePage) {
        homePage?.sections
            .orEmpty()
            .flatMap { section -> section.items }
            .filterIsInstance<SongItem>()
            .mapNotNull { item -> item.toSong() }
            .distinctBy { song -> song.id ?: "${song.title}|${song.artist}" }
    }

    LaunchedEffect(Unit) {
        showHomeChips.value = DataCache.isShowYouTubeHomeChipsEnabled(context)
        showAccountPlaylists.value = DataCache.isShowYouTubeAccountPlaylistsEnabled(context)
        showSongSections.value = DataCache.isShowYouTubeSongSectionsEnabled(context)
        showBrowseSections.value = DataCache.isShowYouTubeBrowseSectionsEnabled(context)
    }

    LaunchedEffect(jamSongs) {
        onJamSongsChanged(jamSongs)
    }

    if (showLogoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out of YouTube", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("Are you sure you want to log out?", color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    context.getSharedPreferences("youtube_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                    com.metrolist.innertube.YouTube.cookie = null
                    com.metrolist.innertube.YouTube.visitorData = null
                    com.metrolist.innertube.YouTube.dataSyncId = null
                    accountInfo = null
                    accountPlaylists = null
                    YouTubeHomeMemoryCache.homePage = null
                    YouTubeHomeMemoryCache.accountInfo = null
                    YouTubeHomeMemoryCache.accountPlaylists = null
                    YouTubeHomeMemoryCache.lastLoadedAtMs = 0L
                    refreshTrigger++
                    selectedChip.value = selectedChip.value 
                }) { Text("Log out", color = Color(0xFFE24A5A)) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showLoginDialog) {
        YouTubeLoginDialog(
            onDismiss = { showLoginDialog = false },
            onLoginSuccess = { name ->
                showLoginDialog = false
                YouTubeHomeMemoryCache.homePage = null
                YouTubeHomeMemoryCache.accountInfo = null
                YouTubeHomeMemoryCache.accountPlaylists = null
                YouTubeHomeMemoryCache.lastLoadedAtMs = 0L
                refreshTrigger++
                selectedChip.value = selectedChip.value 
            }
        )
    }

    LaunchedEffect(selectedChip.value, refreshTrigger) {        val chipValue = selectedChip.value
        val cacheFresh = (System.currentTimeMillis() - YouTubeHomeMemoryCache.lastLoadedAtMs) < 5 * 60 * 1000
        if (chipValue == null && cacheFresh && YouTubeHomeMemoryCache.homePage != null) {
            homePage = YouTubeHomeMemoryCache.homePage
            accountInfo = YouTubeHomeMemoryCache.accountInfo
            accountPlaylists = YouTubeHomeMemoryCache.accountPlaylists
            isLoading = false
            
            if (homePage?.continuation == null) {
                return@LaunchedEffect
            }
            
            isLoadingMore = true
            var continuation = homePage?.continuation
            while (continuation != null) {
                val next = fetchYouTubeHome(context, continuation)
                if (next != null && next.sections.isNotEmpty()) {
                    homePage = homePage?.copy(
                        sections = (homePage?.sections.orEmpty() + next.sections),
                        continuation = next.continuation
                    )
                    continuation = next.continuation
                } else {
                    continuation = null
                }
            }
            isLoadingMore = false
            YouTubeHomeMemoryCache.homePage = homePage
            YouTubeHomeMemoryCache.lastLoadedAtMs = System.currentTimeMillis()
            return@LaunchedEffect
        }

        isLoading = true
        initYouTubeAuth(context)
        
        if (YouTube.cookie?.isNotBlank() == true && accountInfo == null) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    YouTube.accountInfo().onSuccess { accountInfo = it }
                    YouTube.library("FEmusic_liked_playlists").onSuccess { result ->
                        accountPlaylists = result.items.filterIsInstance<PlaylistItem>()
                        
                        val playlistIds = accountPlaylists?.mapNotNull { it.id }?.toSet() ?: emptySet()
                        DataCache.savedYouTubePlaylistIds.value = playlistIds
                    }
                } catch (e: Exception) {}
            }
            
            launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    YouTube.playlist("LM").completed().onSuccess { result ->
                        val ytLikedIds = result.songs.map { "yt_${it.id}" }
                        val currentFavs = DataCache.favoriteIds.value.toMutableSet()
                        currentFavs.addAll(ytLikedIds)
                        DataCache.favoriteIds.value = currentFavs
                        DataCache.saveFavoriteIds(context, currentFavs)
                    }
                } catch (e: Exception) {}
            }
        }

        val result = if (chipValue != null) {
            
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    YouTube.home(params = chipValue).getOrNull()
                } catch (e: Exception) { null }
            }
        } else {
            fetchYouTubeHome(context, null)
        }
        homePage = result
        if (chipValue == null && result != null) {
            YouTubeHomeMemoryCache.homePage = result
            YouTubeHomeMemoryCache.accountInfo = accountInfo
            YouTubeHomeMemoryCache.accountPlaylists = accountPlaylists
            YouTubeHomeMemoryCache.lastLoadedAtMs = System.currentTimeMillis()
        }
        isLoading = false

        
        if (chipValue == null && result?.continuation != null) {
            isLoadingMore = true
            var continuation = result.continuation
            while (continuation != null) {
                val next = fetchYouTubeHome(context, continuation)
                if (next != null && next.sections.isNotEmpty()) {
                    homePage = homePage?.copy(
                        sections = (homePage?.sections.orEmpty() + next.sections),
                        continuation = next.continuation
                    )
                    continuation = next.continuation
                } else {
                    continuation = null
                }
            }
            isLoadingMore = false
            YouTubeHomeMemoryCache.homePage = homePage
            YouTubeHomeMemoryCache.lastLoadedAtMs = System.currentTimeMillis()
        }
    }

    if (isLoading && homePage == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else {
        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        val fullPageWidth = screenWidth + 96.dp
        val spacing = 16.dp
        val singleColumnWidth = (fullPageWidth - spacing) / 2

        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (isPlayerVisible) 100.dp else 24.dp)
        ) {
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SearchBar(onSearch = onSearchClick)
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    JamSessionSection(
                        onStartJamClick = onStartJamClick,
                        onJoinJamClick = onJoinJamClick,
                        activeJamId = activeJamId,
                        onActiveJamClick = onActiveJamClick,
                        onLeaveJamClick = onLeaveJamClick
                    )
                }
            }

            if (showHomeChips.value) {
                homePage?.chips?.let { chips ->
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            items(chips, key = { it.title }) { chip ->
                                FilterChip(
                                    selected = selectedChip.value == chip.endpoint?.params,
                                    onClick = {
                                        if (selectedChip.value == chip.endpoint?.params) selectedChip.value = null
                                        else selectedChip.value = chip.endpoint?.params
                                    },
                                    label = { Text(chip.title) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFE24A5A),
                                        selectedLabelColor = Color.White,
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        labelColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    border = null,
                                    shape = RoundedCornerShape(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (showAccountPlaylists.value) {
                item {
                    Column(modifier = Modifier.padding(vertical = 16.dp)) {
                        if (accountInfo != null) {
                            val acc = accountInfo!!
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Your YouTube playlists",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                                        .clickable { showLogoutDialog = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    ProfileImage(
                                        imageUrl = acc.thumbnailUrl ?: "",
                                        fallbackText = acc.name.take(1).uppercase(),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = acc.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Icon(
                                        Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Your YouTube playlists",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Login",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE24A5A),
                                    modifier = Modifier
                                        .clickable { showLoginDialog = true }
                                        .padding(8.dp)
                                )
                            }
                        }

                        accountPlaylists?.takeIf { it.isNotEmpty() }?.let { playlists ->
                            Spacer(modifier = Modifier.height(16.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                if (playlists.isNotEmpty()) {
                                    item {
                                        YTItemCard(
                                            item = playlists[0],
                                            onClick = { playlists[0].toPlaylist()?.let { onPlaylistClick(it) } }
                                        )
                                    }
                                    item {
                                        DownloadPlaylistsCard(onClick = onDownloadsClick)
                                    }
                                }

                                items(playlists.drop(1), key = { it.title }) { item ->
                                    YTItemCard(
                                        item = item,
                                        onClick = { item.toPlaylist()?.let { onPlaylistClick(it) } }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            homePage?.sections?.forEach { section ->
                
                DataCache.addDiscoveredYouTubeSection(context, section.title)
                
                if (!DataCache.isYouTubeSectionEnabled(context, section.title)) {
                    return@forEach
                }
                
                val filteredItems = section.items.filterNot { item ->
                    item.title.equals("Liked Music", ignoreCase = true)
                }
                if (filteredItems.isEmpty()) return@forEach

                val sectionCopy = section.copy(items = filteredItems)
                val isSongOnlySection = sectionCopy.items.all { it is SongItem }

                if (isSongOnlySection) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val songs = sectionCopy.items.mapNotNull { (it as? SongItem)?.toSong() }
                                        onQuickPicksClick(songs, sectionCopy.title)
                                    },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sectionCopy.title,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "More",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))

                            val songs = sectionCopy.items.mapNotNull { (it as? SongItem)?.toSong() }
                            val rowsPerColumn = 4
                            val columnsPerPage = 2
                            val pageSize = rowsPerColumn * columnsPerPage
                            val pages = songs.take(pageSize * 4).chunked(pageSize)

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(pages, key = { page -> page.firstOrNull()?.id ?: page.hashCode() }) { pageSongs ->
                                    val isHalfPage = pageSongs.size <= rowsPerColumn
                                    val currentWidth = if (isHalfPage) singleColumnWidth else fullPageWidth

                                    LatestSongsPage(
                                        songs = pageSongs,
                                        rowsPerColumn = rowsPerColumn,
                                        onSongClick = { song ->
                                            val index = songs.indexOf(song)
                                            if (index != -1) onSongClick(songs, index)
                                        },
                                        onAlbumClick = onAlbumClick,
                                        onArtistClickBySong = { song ->
                                            song.primaryArtistName?.let { artistName ->
                                                onArtistClick(artistName, song.primaryArtistId)
                                            }
                                        },
                                        modifier = Modifier.width(currentWidth)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val isFromYourLibrarySection = sectionCopy.title.equals("From your library", ignoreCase = true)
                    item {
                        Column(modifier = Modifier.padding(vertical = 16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .let { base ->
                                        if (isFromYourLibrarySection) {
                                            base.clickable { onSectionViewAllClick(sectionCopy.title, sectionCopy.items) }
                                        } else {
                                            base
                                        }
                                    }
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sectionCopy.title,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                if (isFromYourLibrarySection) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "View all",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                items(sectionCopy.items, key = { "${it.title}_${it.thumbnail}" }) { item ->
                                    YTItemCard(
                                        item = item,
                                        onClick = {
                                            when (item) {
                                                is SongItem -> item.toSong()?.let { onSongClick(listOf(it), 0) }
                                                is PlaylistItem -> item.toPlaylist()?.let { onPlaylistClick(it) }
                                                is AlbumItem -> item.toPlaylist()?.let { onPlaylistClick(it) }
                                                is ArtistItem -> onArtistClick(item.title, item.id)
                                                else -> Unit
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun YTItemCard(
    item: YTItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() }
    ) {
        val shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(12.dp)
        
        Box {
            CoverArt(
                coverUrl = upscaleYoutubeThumbnail(item.thumbnail, 320),
                fallbackColor = Color.DarkGray,
                requestSizePx = 320,
                modifier = Modifier
                    .size(160.dp)
                    .clip(shape)
            )
            
            if (item is SongItem || item is PlaylistItem || item is AlbumItem) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = item.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        val subtitle: String = when (item) {
            is SongItem -> item.artists.joinToString(", ") { it.name }
            is AlbumItem -> item.artists?.joinToString(", ") { it.name } ?: ""
            is PlaylistItem -> item.author?.name ?: ""
            is ArtistItem -> "Artist"
            else -> ""
        }
        
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DownloadPlaylistsCard(
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1E3A8A),
                            Color(0xFF3B82F6)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.download),
                    contentDescription = "Downloads",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Downloads",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Downloaded playlists",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        Text(
            text = "Offline playback",
            fontSize = 13.sp,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
