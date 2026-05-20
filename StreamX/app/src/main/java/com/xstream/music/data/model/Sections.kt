package com.xstream.music.data.model

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.player.manager.MusicPlayerManager
import com.xstream.music.ui.components.AddToPlaylistBottomSheet
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.DownloadButton
import com.xstream.music.ui.components.SongContextMenu
import com.xstream.music.ui.components.SongMoreOrSelectButton

import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.res.painterResource

@Composable
fun UpdatedPlaylists(playlists: List<Playlist>, onPlaylistClick: (Playlist) -> Unit = {}) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Updated Playlists",
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(playlists) { playlist ->
                PlaylistCard(playlist, onClick = { onPlaylistClick(playlist) })
            }
        }
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() }
    ) {
        CoverArt(
            coverUrl = playlist.thumbnailUrl,
            fallbackColor = playlist.color,
            requestSizePx = 320,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.title,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun UpdatedPlaylistsLoadingSkeleton() {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Updated Playlists",
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(3) {
                PlaylistSkeletonCard()
            }
        }
    }
}

@Composable
fun PlaylistSkeletonCard() {
    val skeletonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val skeletonSecondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    
    Column(modifier = Modifier.width(140.dp)) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(skeletonColor)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(skeletonColor)
        )
    }
}

@Composable
fun LatestSongs(
    songs: List<Song>,
    onSongClick: (Song) -> Unit = {},
    onViewAllClick: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onArtistClickBySong: ((Song) -> Unit)? = null
) {
    val rowsPerColumn = 4
    val columnsPerPage = 2
    val pageSize = rowsPerColumn * columnsPerPage
    val pages = songs.take(pageSize * 4).chunked(pageSize)
    
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val fullPageWidth = screenWidth + 96.dp
    val spacing = 16.dp
    val singleColumnWidth = (fullPageWidth - spacing) / 2

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onViewAllClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Latest Songs",
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
        if (songs.isEmpty()) {
            Text(
                text = "No songs available. Configure API to load songs.",
                color = Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(
                    items = pages,
                    key = { pageSongs -> pageSongs.firstOrNull()?.id ?: pageSongs.hashCode() }
                ) { pageSongs ->
                    val isHalfPage = pageSongs.size <= rowsPerColumn
                    val currentWidth = if (isHalfPage) singleColumnWidth else fullPageWidth

                    LatestSongsPage(
                        songs = pageSongs,
                        rowsPerColumn = rowsPerColumn,
                        onSongClick = onSongClick,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onArtistClickBySong = onArtistClickBySong,
                        modifier = Modifier.width(currentWidth)
                    )
                }
            }
        }
    }
}

@Composable
fun LatestSongsLoadingSkeleton() {
    val rowsPerColumn = 4
    val pageWidth = (LocalConfiguration.current.screenWidthDp.dp + 96.dp)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Latest Songs",
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(2) {
                LatestSongsSkeletonPage(
                    rowsPerColumn = rowsPerColumn,
                    modifier = Modifier.width(pageWidth)
                )
            }
        }
    }
}

@Composable
fun LatestSongsSkeletonPage(
    rowsPerColumn: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SongListSkeletonColumn(rowsPerColumn = rowsPerColumn, modifier = Modifier.weight(1f))
        SongListSkeletonColumn(rowsPerColumn = rowsPerColumn, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SongListSkeletonColumn(rowsPerColumn: Int, modifier: Modifier = Modifier) {
    val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Column(modifier = modifier) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = dividerColor
        )
        repeat(rowsPerColumn) { index ->
            SongRowSkeletonCard()
            if (index < rowsPerColumn - 1) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = dividerColor
                )
            }
        }
    }
}

@Composable
fun SongRowSkeletonCard(modifier: Modifier = Modifier) {
    val skeletonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val skeletonSecondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp, horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(18.dp),
            contentAlignment = Alignment.Center
        ) {
            
        }
        
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(skeletonColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(skeletonColor)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(skeletonSecondaryColor)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More",
                tint = skeletonColor
            )
        }
    }
}

@Composable
fun LatestSongsPage(
    songs: List<Song>,
    rowsPerColumn: Int,
    onSongClick: (Song) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onArtistClickBySong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val leftColumnSongs = songs.take(rowsPerColumn)
    val rightColumnSongs = songs.drop(rowsPerColumn).take(rowsPerColumn)
    val hasRightColumn = rightColumnSongs.isNotEmpty()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SongListColumn(
            songs = leftColumnSongs,
            onSongClick = onSongClick,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onArtistClickBySong = onArtistClickBySong,
            modifier = Modifier.weight(1f)
        )
        if (hasRightColumn) {
            SongListColumn(
                songs = rightColumnSongs,
                onSongClick = onSongClick,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onArtistClickBySong = onArtistClickBySong,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun SongListColumn(
    songs: List<Song>,
    onSongClick: (Song) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onArtistClickBySong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Column(modifier = modifier) {
        if (songs.isNotEmpty()) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = dividerColor
            )
        }
        songs.forEachIndexed { index, song ->
            SongRowCard(
                song = song,
                onClick = { onSongClick(song) },
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onArtistClickBySong = onArtistClickBySong
            )
            if (index < songs.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = dividerColor
                )
            }
        }
    }
}

@Composable
fun SongRowCard(
    song: Song,
    onClick: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onArtistClickBySong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val menuExpanded = remember { mutableStateOf(false) }
    val showAddToPlaylist = remember { mutableStateOf(false) }
    val favoriteIds by DataCache.favoriteIds.collectAsState()
    val isFavorite = song.id != null && favoriteIds.contains(song.id)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    
    val playerManager: MusicPlayerManager = viewModel()
    val jamId = playerManager.jamId

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(vertical = 4.dp, horizontal = 0.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(18.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isFavorite) {
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
            requestSizePx = 128,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                color = Color.Gray,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier.wrapContentWidth(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val apiUrl = ApiPreferences.getApiUrl(context)
                DownloadButton(song = song, apiUrl = apiUrl)
                SongMoreOrSelectButton(
                    songId = song.id,
                    menuExpanded = menuExpanded.value,
                    onMenuClick = { menuExpanded.value = true }
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
                onAddToPlaylistClick = { showAddToPlaylist.value = true },
                onDownloadClick = if (!DownloadHelper.isDownloaded(context, song.id ?: "")) {
                    {
                        val apiUrl = ApiPreferences.getApiUrl(context)
                        DownloadHelper.downloadTrack(context, song, apiUrl)
                    }
                } else null,
                onRemoveDownloadClick = if (DownloadHelper.isDownloaded(context, song.id ?: "")) {
                    { DownloadHelper.removeDownloadedSong(context, song.id ?: "") }
                } else null,
                onSyncClick = if (
                    song.id?.startsWith("yt_") == true &&
                    DataCache.getProvider(context) == "youtube"
                ) {
                    {
                        coroutineScope.launch {
                            val apiUrl = ApiPreferences.getApiUrl(context)
                            val userToken = AuthPreferences.getEffectiveToken(context)
                            val videoId = song.id?.removePrefix("yt_")
                            if (videoId.isNullOrBlank()) {
                                android.widget.Toast.makeText(context, "Unable to sync this track", android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val youtubeUrl = "https://music.youtube.com/watch?v=$videoId"
                            val synced = syncYouTubeTrack(apiUrl, youtubeUrl, context, userToken)
                            if (!synced) {
                                val message = "Failed to start sync"
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else null,
                onAlbumClick = song.browsableAlbumId?.let { id ->
                    { onAlbumClick(id) }
                },
                onArtistClick = song.primaryArtistName?.let { artistName ->
                    {
                        onArtistClickBySong?.invoke(song) ?: onArtistClick(artistName)
                    }
                },
                onShareClick = song.id?.takeIf(::isShareableStreamXTrackId)?.let { trackId ->
                    {
                        val apiUrl = ApiPreferences.getApiUrl(context)
                        val shareUrl = buildSharedTrackWebLink(trackId, apiUrl)
                        shareTextLink(context, "Check out this track: $shareUrl", "Share Track")
                    }
                }
            )
            
            if (showAddToPlaylist.value && song.id != null) {
                val apiUrl = ApiPreferences.getApiUrl(context)
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                AddToPlaylistBottomSheet(
                    onDismissRequest = { showAddToPlaylist.value = false },
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

@Composable
fun RandomMix(
    songs: List<Song>,
    onSongClick: (Song) -> Unit = {},
    onViewAllClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {}
) {
    val rowsPerColumn = 4
    val columnsPerPage = 2
    val pageSize = rowsPerColumn * columnsPerPage
    val pages = remember(songs) { songs.take(pageSize * 4).chunked(pageSize) }
    
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val fullPageWidth = screenWidth + 96.dp
    val spacing = 16.dp
    val singleColumnWidth = (fullPageWidth - spacing) / 2

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onViewAllClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Random Mix",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onRefreshClick() }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (songs.isEmpty()) {
            Text(
                text = "No songs available.",
                color = Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(pages, key = { index, _ -> "random_mix_page_$index" }) { _, pageSongs ->
                    val isHalfPage = pageSongs.size <= rowsPerColumn
                    val currentWidth = if (isHalfPage) singleColumnWidth else fullPageWidth

                    LatestSongsPage(
                        songs = pageSongs,
                        rowsPerColumn = rowsPerColumn,
                        onSongClick = onSongClick,
                        onAlbumClick = onAlbumClick,
                        modifier = Modifier.width(currentWidth)
                    )
                }
            }
        }
    }
}

@Composable
fun RandomMixLoadingSkeleton() {
    val rowsPerColumn = 4
    val pageWidth = (LocalConfiguration.current.screenWidthDp.dp + 96.dp)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Random Mix",
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(2) {
                LatestSongsSkeletonPage(
                    rowsPerColumn = rowsPerColumn,
                    modifier = Modifier.width(pageWidth)
                )
            }
        }
    }
}

@Composable
fun HomePlaylistsSection(
    userPlaylists: List<Playlist> = emptyList(),
    showTopPlayed: Boolean = true,
    topPlayedCoverUrl: String? = null,
    onCreateClick: () -> Unit = {},
    onFavoritesClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onTopPlayedClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onPlaylistClick: (Playlist) -> Unit = {},
    onViewAllClick: () -> Unit = {},
    onShareClick: (Playlist) -> Unit = {},
    onRenameClick: (Playlist) -> Unit = {},
    onDeleteClick: (Playlist) -> Unit = {}
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onViewAllClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Playlists",
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                CreatePlaylistCard(onClick = onCreateClick)
            }
            item {
                FavoritesCard(onClick = onFavoritesClick)
            }
            item {
                DownloadsCard(onClick = onDownloadsClick)
            }
            if (showTopPlayed) {
                item {
                    TopPlayedCard(coverUrl = topPlayedCoverUrl, onClick = onTopPlayedClick)
                }
            }
            items(userPlaylists) { playlist ->
                UserPlaylistCard(
                    playlist = playlist,
                    onClick = { onPlaylistClick(playlist) }
                )
            }
        }
    }
}

@Composable
fun TopPlayedCard(coverUrl: String? = null, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CoverArt(
            coverUrl = coverUrl,
            fallbackColor = Color(0xFF5E5CE6),
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Top Played",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
fun DownloadsCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF333333)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = "Downloads",
                tint = Color.White,
                modifier = Modifier.size(60.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Downloads",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
fun LibraryCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF5E5CE6)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.editplaylist),
                contentDescription = "Library",
                tint = Color.White,
                modifier = Modifier.size(60.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your Library",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
fun UserPlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CoverArt(
            coverUrl = playlist.thumbnailUrl,
            fallbackColor = playlist.color,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.title,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CreatePlaylistCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(140.dp),
            contentAlignment = Alignment.Center
        ) {
            
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFF13950).copy(alpha = 0.5f), Color.Transparent)
                        ),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(Color(0xFFF13950), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
fun FavoritesCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.fav),
                contentDescription = "Favorites",
                tint = Color(0xFFF13950),
                modifier = Modifier.size(80.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Favorites",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
fun FriendsSection(
    friends: List<Friend>,
    listeningData: Map<Long, FriendListening>,
    requests: List<FriendRequest> = emptyList(),
    isLoading: Boolean = false,
    onAddFriendClick: () -> Unit = {},
    onAcceptRequestClick: (Long) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {},
    onFriendClick: (Friend, FriendListening?) -> Unit = { _, _ -> },
    onFriendLongClick: (Friend) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Friends",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                        contentDescription = "Friends Settings",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onRefreshClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                        contentDescription = "Refresh Friends",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onAddFriendClick) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Add,
                    contentDescription = "Add Friend",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (requests.isNotEmpty()) {
            val showRequestsDialog = remember { mutableStateOf(false) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1C1C1E))
                    .clickable { showRequestsDialog.value = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF13950)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${requests.size}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Friend Request${if (requests.size > 1) "s" else ""}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "View",
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (showRequestsDialog.value) {
                FriendRequestsDialog(
                    requests = requests,
                    onAcceptClick = onAcceptRequestClick,
                    onDismiss = { showRequestsDialog.value = false }
                )
            }
        }

        if (!isLoading && friends.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No friends yet",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (isLoading) {
                    items(4) {
                        FriendSkeletonCard()
                    }
                } else {
                    items(friends, key = { it._id }) { friend ->
                        val listening = listeningData[friend._id]
                        FriendCard(
                            friend = friend, 
                            listeningData = listening, 
                            onClick = { onFriendClick(friend, listening) },
                            onLongClick = { onFriendLongClick(friend) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FriendRequestsDialog(
    requests: List<FriendRequest>,
    onAcceptClick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1C1C1E))
                .padding(top = 20.dp, bottom = 16.dp)
        ) {
            Column {
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Friend Requests",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF13950)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${requests.size}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                
                val scrollState = androidx.compose.foundation.rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                ) {
                    requests.forEachIndexed { index, request ->
                        key(request.user_id) {
                            FriendRequestCard(request, onAcceptClick)
                        }
                        if (index < requests.size - 1) {
                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.06f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 66.dp, end = 14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FriendRequestCard(request: FriendRequest, onAcceptClick: (Long) -> Unit) {
    val name = request.first_name
    val h = request.user_id.hashCode()
    val r = 40 + (h and 0x7F)
    val g = 40 + ((h shr 8) and 0x7F)
    val b = 40 + ((h shr 16) and 0x7F)
    val avatarColor = Color(0xFF000000 or (r shl 16).toLong() or (g shl 8).toLong() or b.toLong())

    var isVisible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + androidx.compose.animation.expandVertically(),
        exit = fadeOut() + androidx.compose.animation.shrinkVertically()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Sent you a request",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { isVisible = false },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF13950))
                    .clickable {
                        isVisible = false
                        onAcceptClick(request.user_id)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun FriendSkeletonCard() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FriendCard(friend: Friend, listeningData: FriendListening?, onClick: () -> Unit = {}, onLongClick: () -> Unit = {}) {
    val name = friend.first_name
    val context = LocalContext.current

    
    val currentTimeSec = System.currentTimeMillis() / 1000.0
    val lastSeen = friend.presence?.last_seen ?: 0.0
    val isOnline = friend.presence?.online == true && (currentTimeSec - lastSeen) <= 600.0

    val (status, indicatorColor) = if (listeningData != null && listeningData.is_playing) {
        Pair("Listening", Color(0xFFF13950))
    } else if (isOnline) {
        Pair("Online", Color(0xFF34C759))
    } else {
        Pair("Offline", Color.Gray)
    }

    val h = friend._id.hashCode()
    val r = 40 + (h and 0x7F)
    val g = 40 + ((h shr 8) and 0x7F)
    val b = 40 + ((h shr 16) and 0x7F)
    val color = Color(0xFF000000 or (r shl 16).toLong() or (g shl 8).toLong() or b.toLong())

    val imageLoader = remember {
        coil.ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(modifier = Modifier.size(64.dp)) {
            
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                
                if (!friend.photo_url.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(friend.photo_url)
                            .crossfade(true)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = "Friend Profile",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(indicatorColor)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = name,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = status,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun JamSessionSection(
    onStartJamClick: () -> Unit,
    onJoinJamClick: () -> Unit,
    activeJamId: String? = null,
    onActiveJamClick: () -> Unit = {},
    onLeaveJamClick: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Jam Session",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        if (activeJamId != null) {
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF13950))
            ) {
                
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onActiveJamClick() }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(32.dp).background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Jam Session",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Active Now",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = 12.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                )
                
                
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable { onLeaveJamClick() }
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Leave",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            
            androidx.compose.material3.Button(
                onClick = onStartJamClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFF13950)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Box(
                        modifier = Modifier.size(24.dp).background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFFF13950),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Start Jam",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            androidx.compose.material3.Button(
                onClick = onJoinJamClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Join Jam",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun HomeAlbumsSection(
    albums: List<AlbumData>,
    onAlbumClick: (String) -> Unit,
    onViewAllClick: () -> Unit = {}
) {
    if (albums.isEmpty()) return
    val visibleAlbums = remember(albums) { albums.take(20) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onViewAllClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Albums",
                fontSize = 22.sp,
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
        
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(
                items = visibleAlbums,
                key = { album -> album._id }
            ) { album ->
                AlbumCard(
                    album = album,
                    onClick = { onAlbumClick(album._id) }
                )
            }
        }
    }
}

@Composable
fun AlbumCard(
    album: AlbumData,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() }
    ) {
        CoverArt(
            coverUrl = album.cover_url,
            fallbackColor = Color.DarkGray,
            requestSizePx = 320,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = album.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        Text(
            text = album.artist,
            fontSize = 13.sp,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
