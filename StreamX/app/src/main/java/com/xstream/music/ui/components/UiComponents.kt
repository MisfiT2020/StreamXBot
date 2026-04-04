package com.xstream.music.ui.components

import com.xstream.music.player.service.*
import com.xstream.music.player.manager.*
import com.xstream.music.ui.components.*
import com.xstream.music.realtime.websocket.*
import com.xstream.music.core.preferences.*
import com.xstream.music.core.cache.*
import com.xstream.music.core.utils.*
import com.xstream.music.data.model.*
import com.xstream.music.data.api.*
import timber.log.Timber
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.KeyboardArrowDown       
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Album
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import java.net.HttpURLConnection
import java.net.URL
import com.xstream.music.R
import org.json.JSONArray
import org.json.JSONObject

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.xstream.music.app.CreatePlaylistDialog
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.cache.ImageMemoryCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.Playlist
import com.xstream.music.data.model.Song
import com.xstream.music.features.auth.YouTubeLoginDialog
import com.xstream.music.BuildConfig
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt
import com.xstream.music.ui.theme.SFProDisplayFontFamily

@Composable
fun PlaylistContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onShareClick: () -> Unit = {},
    onRenameClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 0.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.width(200.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                PlaylistContextMenuItem(
                    icon = Icons.Default.Share,
                    label = "Share",
                    onClick = {
                        onShareClick()
                        onDismissRequest()
                    }
                )
                PlaylistContextMenuItem(
                    icon = Icons.Default.Edit,
                    label = "Rename",
                    onClick = {
                        onRenameClick()
                        onDismissRequest()
                    }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                PlaylistContextMenuItem(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    tint = Color(0xFFEF4444),
                    onClick = {
                        onDeleteClick()
                        onDismissRequest()
                    }
                )
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun PlaylistBottomSheet(
    onDismissRequest: () -> Unit,
    onShareClick: (() -> Unit)? = null,
    onRenameClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onAddClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onFavouriteClick: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onSaveToLibraryClick: (() -> Unit)? = null,
    onSyncClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    isYouTube: Boolean = false,
    isFavorite: Boolean = false,
    isSavedToLibrary: Boolean = false
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
    val sheetBgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
    
    androidx.compose.material3.ModalBottomSheet(
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
            val hasQuickActions = onAddToPlaylistClick != null || onFavouriteClick != null || onPlayNextClick != null
            if (hasQuickActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onAddToPlaylistClick != null) {
                        BottomSheetQuickButton(
                            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                            label = "Add",
                            modifier = Modifier.weight(1f),
                            onClick = { onAddToPlaylistClick(); onDismissRequest() }
                        )
                    }

                    if (onFavouriteClick != null) {
                        BottomSheetQuickButton(
                            icon = if (isYouTube) {
                                if (isFavorite) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp
                            } else {
                                painterResource(id = R.drawable.fav)
                            },
                            label = if (isYouTube) {
                                if (isFavorite) "Liked" else "Like"
                            } else {
                                "Favourite"
                            },
                            tint = if (isFavorite) {
                                if (isYouTube) MaterialTheme.colorScheme.onSurface else Color(0xFFE24A5A)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                            onClick = { onFavouriteClick(); onDismissRequest() }
                        )
                    }

                    if (onPlayNextClick != null) {
                        BottomSheetQuickButton(
                            icon = Icons.Default.SkipNext,
                            label = "Play Next",
                            modifier = Modifier.weight(1f),
                            onClick = { onPlayNextClick(); onDismissRequest() }
                        )
                    }
                }
            }

            val hasPrimaryItems =
                onDownloadClick != null ||
                onShareClick != null ||
                onSaveToLibraryClick != null ||
                onSyncClick != null ||
                onAlbumClick != null ||
                onArtistClick != null
            if (hasPrimaryItems) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                if (onDownloadClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.ArrowDownward,
                        label = "Download",
                        onClick = { onDownloadClick(); onDismissRequest() }
                    )
                }

                if (onShareClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Share,
                        label = "Share",
                        onClick = { onShareClick(); onDismissRequest() }
                    )
                }

                if (onSaveToLibraryClick != null) {
                    BottomSheetMenuItem(
                        icon = if (isSavedToLibrary) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        label = if (isSavedToLibrary) "Remove from Library" else "Save to Library",
                        onClick = { onSaveToLibraryClick(); onDismissRequest() }
                    )
                }

                if (onSyncClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Sync,
                        label = "Sync",
                        onClick = { onSyncClick(); onDismissRequest() }
                    )
                }

                if (onAlbumClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Album,
                        label = "Go to Album",
                        onClick = { onAlbumClick(); onDismissRequest() }
                    )
                }

                if (onArtistClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Person,
                        label = "Go to Artist",
                        onClick = { onArtistClick(); onDismissRequest() }
                    )
                }
            }

            val hasOwnerActions = onRenameClick != null || onDeleteClick != null
            if (hasOwnerActions) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                if (onRenameClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Edit,
                        label = "Rename",
                        onClick = { onRenameClick(); onDismissRequest() }
                    )
                }

                if (onDeleteClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Delete,
                        label = "Delete",
                        tint = Color(0xFFEF4444),
                        onClick = { onDeleteClick(); onDismissRequest() }
                    )
                }
            }
        }
    }
}


@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AlbumBottomSheet(
    onDismissRequest: () -> Unit,
    onShareClick: (() -> Unit)? = null,
    onAddClick: (() -> Unit)? = null,
    onFavouriteClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onSaveToLibraryClick: (() -> Unit)? = null,
    onSyncClick: (() -> Unit)? = null,
    isYouTube: Boolean = false,
    isFavorite: Boolean = false,
    isSavedToLibrary: Boolean = false
) {
    PlaylistBottomSheet(
        onDismissRequest = onDismissRequest,
        onShareClick = onShareClick,
        onRenameClick = null,
        onDeleteClick = null,
        onAddClick = onAddClick,
        onAddToPlaylistClick = onAddToPlaylistClick,
        onFavouriteClick = onFavouriteClick,
        onPlayNextClick = onPlayNextClick,
        onDownloadClick = onDownloadClick,
        onSaveToLibraryClick = onSaveToLibraryClick,
        onSyncClick = onSyncClick,
        isYouTube = isYouTube,
        isFavorite = isFavorite,
        isSavedToLibrary = isSavedToLibrary
    )
}

@Composable
fun PlaylistContextMenuItem(
    icon: Any,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (icon) {
            is ImageVector -> {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
            is Painter -> {
                Icon(
                    painter = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 15.sp,
            fontFamily = SFProDisplayFontFamily,
            fontWeight = FontWeight.Medium
        )
    }
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SongContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: Song? = null,
    isFavorite: Boolean = false,
    onAddClick: () -> Unit = {},
    onFavouriteClick: () -> Unit = {},
    onPlayNextClick: () -> Unit = {},
    onAddToPlaylistClick: () -> Unit = {},
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    onAddToJamQueueClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onRemoveDownloadClick: (() -> Unit)? = null,
    onSyncClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null
) {
    if (!expanded) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
    val sheetBgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.material3.ModalBottomSheet(
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
                    if (song.coverUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(song.color.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        CoverArt(
                            coverUrl = song.coverUrl,
                            fallbackColor = song.color,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            requestSizePx = 128,
                            loadAsync = false
                        )
                    }
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
                
                BottomSheetQuickButton(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = "Add",
                    modifier = Modifier.weight(1f),
                    onClick = { onAddToPlaylistClick(); onDismissRequest() }
                )
                val isYouTube = song?.id?.startsWith("yt_") == true
                BottomSheetQuickButton(
                    icon = if (isYouTube) {
                        if (isFavorite) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp
                    } else {
                        painterResource(id = R.drawable.fav)
                    },
                    label = if (isYouTube) {
                        if (isFavorite) "Liked" else "Like"
                    } else {
                        "Favourite"
                    },
                    tint = if (isFavorite) {
                        if (isYouTube) MaterialTheme.colorScheme.onSurface else Color(0xFFE24A5A)
                    } else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onClick = { onFavouriteClick(); onDismissRequest() }
                )
                
                BottomSheetQuickButton(
                    icon = Icons.Default.SkipNext,
                    label = "Play Next",
                    modifier = Modifier.weight(1f),
                    onClick = { onPlayNextClick(); onDismissRequest() }
                )
            }

            
            if (onAddToJamQueueClick != null) {
                BottomSheetMenuItem(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "Add to Jam Queue",
                    onClick = { onAddToJamQueueClick(); onDismissRequest() }
                )
            }

            
            if (onDownloadClick != null || onRemoveDownloadClick != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (onDownloadClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.ArrowDownward,
                        label = "Download",
                        onClick = { onDownloadClick(); onDismissRequest() }
                    )
                }
                if (onRemoveDownloadClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Delete,
                        label = "Remove Download",
                        tint = Color(0xFFEF4444),
                        onClick = { onRemoveDownloadClick(); onDismissRequest() }
                    )
                }
            }

            val searchSource = remember {
                context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
                    .getString("search_source", "streamx")
                    ?: "streamx"
            }
            val isYouTubeSong = song?.id?.startsWith("yt_") == true
            val shouldShowYouTubeSync = isYouTubeSong && searchSource == "youtube"
            if (shouldShowYouTubeSync) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                BottomSheetMenuItem(
                    icon = Icons.Default.Sync,
                    label = "Sync",
                    onClick = {
                        coroutineScope.launch {
                            val apiUrl = ApiPreferences.getApiUrl(context)
                            val userToken = AuthPreferences.getUser(context)?.token
                            val videoId = song?.id?.removePrefix("yt_")
                            if (videoId.isNullOrBlank()) {
                                android.widget.Toast.makeText(context, "Unable to sync this track", android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val youtubeUrl = "https://music.youtube.com/watch?v=$videoId"
                            val synced = syncYouTubeTrack(apiUrl, youtubeUrl, context, userToken)
                            if (!synced) {
                                android.widget.Toast.makeText(context, "Failed to start sync", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        onDismissRequest()
                    }
                )
            }

            
            val hasNavItems = onAlbumClick != null || onArtistClick != null || onShareClick != null
            if (hasNavItems) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (onAlbumClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Album,
                        label = "Go to Album",
                        onClick = { onAlbumClick(); onDismissRequest() }
                    )
                }
                if (onArtistClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Person,
                        label = "Go to Artist",
                        onClick = { onArtistClick(); onDismissRequest() }
                    )
                }
                if (onShareClick != null) {
                    BottomSheetMenuItem(
                        icon = Icons.Default.Share,
                        label = "Share",
                        onClick = { onShareClick(); onDismissRequest() }
                    )
                }
            }

            
            if (onRemoveFromPlaylistClick != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                BottomSheetMenuItem(
                    icon = Icons.Default.Delete,
                    label = "Remove from Playlist",
                    tint = Color(0xFFEF4444),
                    onClick = { onRemoveFromPlaylistClick(); onDismissRequest() }
                )
            }
        }
    }
}

@Composable
fun BottomSheetQuickButton(
    icon: Any,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (icon) {
            is ImageVector -> Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            is Painter -> Icon(
                painter = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            fontFamily = SFProDisplayFontFamily,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BottomSheetMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
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
            fontFamily = SFProDisplayFontFamily,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ContextMenuItemTop(
    icon: Any, 
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (icon) {
            is ImageVector -> Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            is Painter -> Icon(
                painter = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = SFProDisplayFontFamily,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


@Composable
fun AlbumContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    isFavorite: Boolean = false,
    onAddClick: () -> Unit = {},
    onFavouriteClick: () -> Unit = {},
    onShareClick: () -> Unit = {},
    onAddToPlaylistClick: () -> Unit = {},
    onPlayNextClick: () -> Unit = {},
    onDownloadClick: (() -> Unit)? = null
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 0.dp),
        containerColor = Color.Transparent, 
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.width(250.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1C1E),
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContextMenuItemTop(
                        icon = Icons.Default.Add,
                        label = "Add",
                        onClick = {
                            onAddClick()
                            onDismissRequest()
                        }
                    )
                    ContextMenuItemTop(
                        icon = painterResource(id = R.drawable.fav),
                        label = "Favourite",
                        tint = if (isFavorite) Color(0xFFE24A5A) else Color.White,
                        onClick = {
                            onFavouriteClick()
                            onDismissRequest()
                        }
                    )
                    ContextMenuItemTop(
                        icon = Icons.Default.Share,
                        label = "Share",
                        onClick = {
                            onShareClick()
                            onDismissRequest()
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onAddToPlaylistClick()
                            onDismissRequest()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Add to a Playlist",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = SFProDisplayFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onPlayNextClick()
                            onDismissRequest()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Play Next",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = SFProDisplayFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                if (onDownloadClick != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onDownloadClick()
                                onDismissRequest()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Download",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Download",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = SFProDisplayFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SongMoreOrSelectButton(
    songId: String?,
    menuExpanded: Boolean,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isSudoMode = remember { mutableStateOf(DataCache.isSudoModeEnabled(context)) }
    val selectedIds by DataCache.sudoSelectedTrackIds.collectAsState()
    val isSelected = songId != null && selectedIds.contains(songId)

    if (isSudoMode.value) {
        IconButton(
            onClick = {
                if (songId != null) {
                    val current = selectedIds.toMutableSet()
                    if (current.contains(songId)) current.remove(songId) else current.add(songId)
                    DataCache.sudoSelectedTrackIds.value = current
                }
            },
            modifier = modifier
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = "Select",
                tint = if (isSelected) Color(0xFFE24A5A) else Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    } else {
        IconButton(
            onClick = onMenuClick,
            modifier = modifier
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier) {
    val shimmerColors = listOf(
        Color.White.copy(alpha = 0.05f),
        Color.White.copy(alpha = 0.1f),
        Color.White.copy(alpha = 0.05f),
    )
    
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )

    Box(
        modifier = modifier.background(brush)
    )
}

@Composable
fun CoverArt(
    coverUrl: String?,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
    requestSizePx: Int = 320,
    loadAsync: Boolean = true
) {
    val normalizedUrl = coverUrl?.trim().orEmpty()
    val resolvedSizePx = remember(requestSizePx) { requestSizePx.coerceAtLeast(64) }
    val cacheKey = remember(normalizedUrl, resolvedSizePx) { stableImageCacheKey("${normalizedUrl}_$resolvedSizePx") }
    val bitmapState = remember(cacheKey) { mutableStateOf(ImageMemoryCache.getFromMemory(cacheKey)) }

    LaunchedEffect(normalizedUrl, cacheKey, loadAsync) {
        if (!loadAsync || normalizedUrl.isBlank() || bitmapState.value != null) return@LaunchedEffect

        Timber.d("CoverArt loading: $normalizedUrl")
        val cached = withContext(Dispatchers.IO) { ImageMemoryCache.get(cacheKey) }
        if (cached != null) {
            Timber.d("CoverArt loaded from disk cache: $normalizedUrl")
            bitmapState.value = cached
            return@LaunchedEffect
        }

        val downloadedBitmap = withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                if (normalizedUrl.startsWith("file://")) {
                    val path = normalizedUrl.removePrefix("file://")
                    val f = File(path)
                    if (f.exists()) {
                        decodeSampledBitmapFromFile(path, resolvedSizePx)
                    } else {
                        Timber.w("CoverArt: Local file does not exist at $path")
                        null
                    }
                } else {
                    val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                        connectTimeout = 10_000
                        readTimeout = 10_000
                    }
                    try {
                        if (connection.responseCode !in 200..299) {
                            Timber.w("CoverArt: HTTP error ${connection.responseCode} for $normalizedUrl")
                            return@runCatching null
                        }
                        connection.inputStream.use { stream ->
                            val bytes = stream.readBytes()
                            decodeSampledBitmapFromByteArray(bytes, resolvedSizePx)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            }.getOrNull()
            
            if (bitmap != null) {
                Timber.d("CoverArt: Successfully decoded bitmap for $normalizedUrl")
                ImageMemoryCache.put(cacheKey, bitmap)
            } else {
                Timber.w("CoverArt: Failed to decode bitmap for $normalizedUrl")
            }
            bitmap
        } as Bitmap?

        if (downloadedBitmap != null) {
            bitmapState.value = downloadedBitmap
        }
    }

    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        if (loadAsync) {
            SkeletonBox(modifier = modifier)
        } else {
            Box(modifier = modifier.background(fallbackColor.copy(alpha = 0.25f)))
        }
    }
}

private fun decodeSampledBitmapFromFile(path: String, reqSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds, reqSizePx, reqSizePx)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(path, options)
}

private fun decodeSampledBitmapFromByteArray(data: ByteArray, reqSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds, reqSizePx, reqSizePx)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
            halfHeight = (halfHeight).coerceAtLeast(1)
            halfWidth = (halfWidth).coerceAtLeast(1)
        }
    }

    return inSampleSize.coerceAtLeast(1)
}

@Composable
fun ProfileImage(
    imageUrl: String?,
    fallbackText: String,
    modifier: Modifier = Modifier
) {
    val normalizedUrl = imageUrl?.trim().orEmpty()
    val cacheKey = remember(normalizedUrl) { stableImageCacheKey(normalizedUrl) }
    val bitmapState = remember(cacheKey) { mutableStateOf(ImageMemoryCache.getFromMemory(cacheKey)) }

    LaunchedEffect(normalizedUrl, cacheKey) {
        if (normalizedUrl.isBlank() || bitmapState.value != null) return@LaunchedEffect

        val cached = withContext(Dispatchers.IO) { ImageMemoryCache.get(cacheKey) }
        if (cached != null) {
            bitmapState.value = cached
            return@LaunchedEffect
        }

        val downloadedBitmap = withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            
            if (bitmap != null) {
                ImageMemoryCache.put(cacheKey, bitmap)
            }
            bitmap
        } as Bitmap?

        if (downloadedBitmap != null) {
            bitmapState.value = downloadedBitmap
        }
    }

    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    onApiClick: () -> Unit = {},
    onLoginClick: () -> Unit = {},
    onLogoutClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onAppInfoClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onAudioClick: () -> Unit = {},
    isStartupLoading: Boolean = false
) {
    val context = LocalContext.current
    val menuExpanded = remember { mutableStateOf(false) }
    val appInfoHeaderTapCount = remember { mutableStateOf(0) }
    val showHiddenAppInfo = remember { mutableStateOf(false) }
    val profileSheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val userState = remember { mutableStateOf(AuthPreferences.getUser(context)) }
    val devModeEnabled = remember { mutableStateOf(false) }
    val isAmoled = remember { mutableStateOf(false) }
    val showHomeHeaderName = remember { mutableStateOf(true) }
    
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val user = AuthPreferences.getUser(context)
            val devMode = DataCache.isDevModeEnabled(context)
            val amoled = DataCache.isAmoledBlackEnabled(context)
            val showName = DataCache.isHomeHeaderNameEnabled(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                userState.value = user
                devModeEnabled.value = devMode
                isAmoled.value = amoled
                showHomeHeaderName.value = showName
            }
        }
    }

    
    LaunchedEffect(menuExpanded.value) {
        if (menuExpanded.value) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val user = AuthPreferences.getUser(context)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    userState.value = user
                }
            }
        } else {
            appInfoHeaderTapCount.value = 0
            showHiddenAppInfo.value = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "StreamX",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            
            Row(
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { menuExpanded.value = !menuExpanded.value }
                    .padding(
                        horizontal = if (showHomeHeaderName.value) 10.dp else 6.dp,
                        vertical = 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val user = userState.value
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (user != null && user.photoUrl.isNotBlank()) {
                        ProfileImage(
                            imageUrl = user.photoUrl,
                            fallbackText = user.firstName.take(1).uppercase(),
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (showHomeHeaderName.value) {
                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = user?.firstName ?: "Guest",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Icon(
                        imageVector = if (menuExpanded.value) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (menuExpanded.value) {
                val sheetBgColor = if (isAmoled.value) Color.Black else MaterialTheme.colorScheme.surface
                
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { menuExpanded.value = false },
                    sheetState = profileSheetState,
                    containerColor = sheetBgColor,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    dragHandle = {
                        Box(
                            modifier = Modifier
                                .padding(top = 12.dp, bottom = 8.dp)
                                .width(40.dp)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    CircleShape
                                )
                        )
                    }
                ) {
                    if (isStartupLoading && userState.value == null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isAmoled.value) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.surfaceContainerLowest
                            ) {
                                Column {
                                    ProfileMenuItem(
                                        icon = Icons.Default.Tune,
                                        text = "Audio Quality & Playback",
                                        subtitle = "Playback preferences",
                                        iconBackground = Color(0xFF2E2156),
                                        iconTint = Color.White,
                                        onClick = {
                                            menuExpanded.value = false
                                            onAudioClick()
                                        }
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                    ProfileMenuItem(
                                        icon = Icons.Default.Search,
                                        text = "Api",
                                        subtitle = "Endpoint tools",
                                        iconBackground = Color(0xFF4A320D),
                                        iconTint = Color.White,
                                        onClick = {
                                            menuExpanded.value = false
                                            onApiClick()
                                        }
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                    ProfileMenuItem(
                                        icon = Icons.Default.Settings,
                                        text = "General Settings",
                                        subtitle = "Language & more",
                                        iconBackground = Color(0xFF103A52),
                                        iconTint = Color.White,
                                        onClick = {
                                            menuExpanded.value = false
                                            onSettingsClick()
                                        }
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                    ProfileMenuItem(
                                        icon = Icons.Default.Palette,
                                        text = "Theme & Colors",
                                        subtitle = "Dark mode · Accent color",
                                        iconBackground = Color(0xFF4A143C),
                                        iconTint = Color.White,
                                        onClick = {
                                            menuExpanded.value = false
                                            onAppearanceClick()
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    } else Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 24.dp)
                    ) {
                        val user = userState.value

                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!showHiddenAppInfo.value) {
                                        val nextTapCount = appInfoHeaderTapCount.value + 1
                                        appInfoHeaderTapCount.value = nextTapCount
                                        if (nextTapCount >= 5) {
                                            showHiddenAppInfo.value = true
                                        }
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                if (user != null && user.photoUrl.isNotBlank()) {
                                    ProfileImage(
                                        imageUrl = user.photoUrl,
                                        fallbackText = user.firstName.take(1).uppercase(),
                                        modifier = Modifier.size(56.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = user?.firstName ?: "Guest",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = SFProDisplayFontFamily
                                )
                                Text(
                                    text = if (user != null) "Logged in" else "Not logged in",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal,
                                    fontFamily = SFProDisplayFontFamily
                                )
                            }

                            IconButton(
                                onClick = { menuExpanded.value = false },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            thickness = 0.5.dp
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        
                        Text(
                            text = "APP SETTINGS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 24.dp, bottom = 10.dp)
                        )

                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isAmoled.value) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.surfaceContainerLowest,
                            tonalElevation = 0.dp
                        ) {
                            Column {
                                ProfileMenuItem(
                                    icon = Icons.Default.Tune,
                                    text = "Audio Quality & Playback",
                                    subtitle = "Playback preferences",
                                    iconBackground = Color(0xFF2E2156),
                                    iconTint = Color.White,
                                    onClick = {
                                        menuExpanded.value = false
                                        onAudioClick()
                                    }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                ProfileMenuItem(
                                    icon = Icons.Default.DownloadForOffline,
                                    text = "API",
                                    subtitle = "Endpoint tools",
                                    iconBackground = Color(0xFF4A320D),
                                    iconTint = Color.White,
                                    onClick = {
                                        menuExpanded.value = false
                                        onApiClick()
                                    }
                                )
                                if (showHiddenAppInfo.value) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                    ProfileMenuItem(
                                        icon = Icons.Default.BugReport,
                                        text = "App Info & Diagnostics",
                                        subtitle = "Build ${BuildConfig.VERSION_NAME}",
                                        iconBackground = Color(0xFF4A2A0E),
                                        iconTint = Color.White,
                                        onClick = {
                                            menuExpanded.value = false
                                            onAppInfoClick()
                                        }
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                ProfileMenuItem(
                                    icon = Icons.Default.Settings,
                                    text = "General Settings",
                                    subtitle = "Language & more",
                                    iconBackground = Color(0xFF103A52),
                                    iconTint = Color.White,
                                    onClick = {
                                        menuExpanded.value = false
                                        onSettingsClick()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        
                        
                        Text(
                            text = "APPEARANCE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 24.dp, bottom = 10.dp)
                        )

                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isAmoled.value) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.surfaceContainerLowest,
                            tonalElevation = 0.dp
                        ) {
                            Column {
                                ProfileMenuItem(
                                    icon = Icons.Default.Palette,
                                    text = "Theme & Colors",
                                    subtitle = "Dark mode · Accent color",
                                    iconBackground = Color(0xFF4A143C),
                                    iconTint = Color.White,
                                    onClick = {
                                        menuExpanded.value = false
                                        onAppearanceClick()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        
                        
                        Text(
                            text = "ACCOUNT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 24.dp, bottom = 10.dp)
                        )

                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isAmoled.value) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.surfaceContainerLowest,
                            tonalElevation = 0.dp
                        ) {
                            if (user != null) {
                                ProfileMenuItem(
                                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                                    text = "Sign Out",
                                    subtitle = user.firstName,
                                    tint = MaterialTheme.colorScheme.error,
                                    iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                    iconBackground = MaterialTheme.colorScheme.errorContainer,
                                    onClick = {
                                        onLogoutClick()
                                        userState.value = null
                                        menuExpanded.value = false
                                    }
                                )
                            } else {
                                ProfileMenuItem(
                                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                                    text = "Login with Account",
                                    subtitle = "Sync playlists and preferences",
                                    iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    onClick = {
                                        menuExpanded.value = false
                                        onLoginClick()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    text: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = tint,
    iconBackground: Color = tint.copy(alpha = 0.12f),
    trailingBadge: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                color = tint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = SFProDisplayFontFamily
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = tint.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = SFProDisplayFontFamily
                )
            }
        }
        if (!trailingBadge.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Text(
                    text = trailingBadge,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = tint.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}



@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(initialQuery: String = "", onSearch: (String) -> Unit = {}) {
    var query by remember { mutableStateOf(initialQuery) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    
    var searchSource by remember { mutableStateOf(DataCache.getProvider(context)) }
    var channelId by remember { mutableStateOf(context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE).getString("search_channel_id", "") ?: "") }
    var availableChannels by remember { mutableStateOf<List<StreamXChannel>>(emptyList()) }
    var isLoadingChannels by remember { mutableStateOf(false) }
    var channelsExpanded by remember { mutableStateOf(false) }
    
    var showYouTubeLogin by remember { mutableStateOf(false) }
    var ytAccountName by remember { mutableStateOf(context.getSharedPreferences("youtube_prefs", Context.MODE_PRIVATE).getString("account_name", null)) }

    if (showYouTubeLogin) {
        YouTubeLoginDialog(
            onDismiss = { showYouTubeLogin = false },
            onLoginSuccess = { name ->
                ytAccountName = name
                showYouTubeLogin = false
            }
        )
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(26.dp),
        placeholder = { 
            Text(
                "Search songs, artists, albums...", 
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 14.sp
            ) 
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { 
                        if (query.isNotBlank()) {
                            focusManager.clearFocus()
                            onSearch(query)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Search Settings",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        },
        keyboardActions = KeyboardActions(
            onSearch = {
                if (query.isNotBlank()) {
                    focusManager.clearFocus()
                    onSearch(query)
                }
            }
        ),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search
        ),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        singleLine = true
    )

    if (showSettingsDialog) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
        val sheetBgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
        val searchPrefs = remember { context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE) }
        val activeApiUrl = remember { ApiPreferences.getApiUrl(context) }

        LaunchedEffect(showSettingsDialog, searchSource) {
            if (!showSettingsDialog || searchSource != "streamx") return@LaunchedEffect
            isLoadingChannels = true
            val cacheKeySuffix = activeApiUrl.ifBlank { "default" }
            val cacheRaw = searchPrefs.getString("channel_ids_cache_json_$cacheKeySuffix", null)
            val cacheTs = searchPrefs.getLong("channel_ids_cache_ts_$cacheKeySuffix", 0L)
            val now = System.currentTimeMillis()

            val cachedItems = cacheRaw?.let { raw ->
                runCatching {
                    val arr = JSONArray(raw)
                    buildList {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val id = item.optLong("id")
                            if (id == 0L) continue
                            add(
                                StreamXChannel(
                                    id = id,
                                    title = item.optString("title").ifBlank { "Channel $id" },
                                    username = item.optString("username").takeIf { it.isNotBlank() && it != "null" },
                                    type = item.optString("type").takeIf { it.isNotBlank() && it != "null" }
                                )
                            )
                        }
                    }
                }.getOrDefault(emptyList())
            } ?: emptyList()

            if (cachedItems.isNotEmpty() && now - cacheTs < 10 * 60 * 1000) {
                availableChannels = cachedItems
                isLoadingChannels = false
            } else {
                val freshItems = fetchStreamXChannels(activeApiUrl)
                availableChannels = freshItems
                if (freshItems.isNotEmpty()) {
                    val arr = JSONArray()
                    freshItems.forEach { channel ->
                        arr.put(
                            JSONObject().apply {
                                put("id", channel.id)
                                put("title", channel.title)
                                put("username", channel.username)
                                put("type", channel.type)
                            }
                        )
                    }
                    searchPrefs.edit()
                        .putString("channel_ids_cache_json_$cacheKeySuffix", arr.toString())
                        .putLong("channel_ids_cache_ts_$cacheKeySuffix", now)
                        .apply()
                }
                isLoadingChannels = false
            }
        }
        
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showSettingsDialog = false },
            sheetState = sheetState,
            containerColor = sheetBgColor,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp, top = 8.dp)
            ) {
                Text(
                    text = "Search Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Choose where StreamX searches for your music.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Text(
                    text = "SEARCH SOURCE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val streamxSelected = searchSource == "streamx"
                    val youtubeSelected = searchSource == "youtube"
                    val soundcloudSelected = searchSource == "soundcloud"
                    FilterChip(
                        selected = streamxSelected,
                        onClick = { searchSource = "streamx" },
                        label = { Text("StreamX") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = youtubeSelected,
                        onClick = { searchSource = "youtube" },
                        label = { Text("YouTube") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = soundcloudSelected,
                        onClick = { searchSource = "soundcloud" },
                        label = { Text("SCloud") },
                        leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                if (searchSource == "streamx") {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "STREAMX CHANNEL ID (OPTIONAL)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Choose a channel from the endpoint. Default is no channel filter.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    val selectedChannel = availableChannels.firstOrNull { it.id.toString() == channelId }

                    ExposedDropdownMenuBox(
                        expanded = channelsExpanded,
                        onExpandedChange = { channelsExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedChannel?.title ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .menuAnchor(
                                    type = androidx.compose.material3.MenuAnchorType.PrimaryNotEditable,
                                    enabled = true
                                )
                                .fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelsExpanded) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            supportingText = {
                                if (isLoadingChannels) {
                                    Text("Loading channels…")
                                } else {
                                    Text(selectedChannel?.let { "ID: ${it.id}" } ?: "No channel selected")
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = channelsExpanded,
                            onDismissRequest = { channelsExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = {
                                    channelId = ""
                                    channelsExpanded = false
                                }
                            )
                            availableChannels.forEach { channel ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(channel.title)
                                            Text(
                                                text = "ID: ${channel.id}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        channelId = channel.id.toString()
                                        channelsExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else if (searchSource == "youtube") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Searches directly on YouTube Music and supports sync.",
                        fontSize = 13.sp,
                        color = Color.Gray.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (ytAccountName != null) "Logged in as $ytAccountName" else "Not logged in",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(
                            onClick = { 
                                if (ytAccountName != null) {
                                    context.getSharedPreferences("youtube_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                                    ytAccountName = null
                                    com.metrolist.innertube.YouTube.cookie = null
                                    com.metrolist.innertube.YouTube.visitorData = null
                                    com.metrolist.innertube.YouTube.dataSyncId = null
                                } else {
                                    showYouTubeLogin = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(if (ytAccountName != null) "Logout" else "Login")
                        }
                    }
                } else if (searchSource == "soundcloud") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Searches directly on SoundCloud. Best for indie artists, remixes, and DJ sets.",
                        fontSize = 13.sp,
                        color = Color.Gray.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = {
                        context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE).edit()
                            .putString("search_source", searchSource)
                            .putString("search_channel_id", channelId)
                            .apply()
                        showSettingsDialog = false 
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("Apply", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MusicPlayer(
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPauseClick: () -> Unit,
    onPlayerClick: () -> Unit = {},
    onPlayerLongClick: (() -> Unit)? = null,
    onSwipeProgress: (Float) -> Unit = {},
    onSwipeUp: (() -> Unit)? = null,
    onNextClick: () -> Unit = {},
    isJam: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (song == null) return
    var dragAmountY by remember { mutableStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        val next = (dragAmountY + delta).coerceIn(-220f, 0f)
        dragAmountY = next
        val progress = (-dragAmountY / 220f).coerceIn(0f, 1f)
        onSwipeProgress(progress)
    }

    Surface(
        modifier = modifier
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val shouldExpand = dragAmountY < -90f || velocity < -1400f
                    dragAmountY = 0f
                    onSwipeProgress(0f)
                    if (shouldExpand) {
                        onSwipeUp?.invoke()
                    }
                }
            )
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .shadow(8.dp, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onPlayerClick() },
                onLongClick = onPlayerLongClick
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    PlayerCoverArt(
                        coverUrl = song.coverUrl,
                        fallbackColor = song.color,
                        modifier = Modifier.size(48.dp)
                    )
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.9f))
                        ) {
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = song.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isJam) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "JAM",
                                color = Color(0xFFF13950),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFF13950).copy(alpha = 0.1f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = song.artist,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { 
                        onPlayPauseClick()
                    },
                    enabled = !isLoading
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) R.drawable.pause else R.drawable.resume
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isLoading) Color.Gray else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = onNextClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.forward),
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerCoverArt(
    coverUrl: String?,
    fallbackColor: Color,
    modifier: Modifier = Modifier
) {
    val normalizedUrl = coverUrl?.trim().orEmpty()
    val requestSizePx = remember { 512 }
    val cacheKey = remember(normalizedUrl, requestSizePx) {
        stableImageCacheKey("${normalizedUrl}_player_$requestSizePx")
    }
    val bitmapState = remember(cacheKey) { mutableStateOf(ImageMemoryCache.getFromMemory(cacheKey)) }

    LaunchedEffect(normalizedUrl, cacheKey) {
        if (normalizedUrl.isBlank()) {
            bitmapState.value = null
            return@LaunchedEffect
        }

        val cached = withContext(Dispatchers.IO) { ImageMemoryCache.get(cacheKey) }
        if (cached != null) {
            bitmapState.value = cached
            return@LaunchedEffect
        }

        val bitmap = withContext(Dispatchers.IO) {
            Timber.d("PlayerCoverArt: Fetching thumbnail from network/file: $normalizedUrl")
            val downloaded = runCatching {
                if (normalizedUrl.startsWith("file://")) {
                    val path = normalizedUrl.removePrefix("file://")
                    decodeSampledBitmapFromFile(path, requestSizePx)
                } else {
                    val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                        connectTimeout = 10_000
                        readTimeout = 10_000
                    }
                    try {
                        if (connection.responseCode !in 200..299) {
                             Timber.w("PlayerCoverArt: HTTP error ${connection.responseCode} for $normalizedUrl")
                             return@runCatching null
                        }
                        connection.inputStream.use { stream ->
                            decodeSampledBitmapFromByteArray(stream.readBytes(), requestSizePx)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            }.getOrNull()
            
            if (downloaded != null) {
                Timber.d("PlayerCoverArt: Successfully fetched and decoded $normalizedUrl")
                ImageMemoryCache.put(cacheKey, downloaded)
            } else {
                Timber.w("PlayerCoverArt: Failed to fetch/decode $normalizedUrl")
            }
            downloaded
        }
        bitmapState.value = bitmap
    }

    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier = modifier.background(fallbackColor))
    }
}

private fun stableImageCacheKey(url: String): String {
    if (url.isBlank() || url.startsWith("file://")) return url
    return url.substringBefore('?')
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AddToPlaylistBottomSheet(
    onDismissRequest: () -> Unit,
    apiBaseUrl: String,
    trackId: String? = null,
    trackIds: List<String> = emptyList(),
    useYouTubePlaylists: Boolean = false,
    onPlaylistSelected: (Boolean) -> Unit 
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val playlists = androidx.compose.runtime.remember { 
        androidx.compose.runtime.mutableStateOf(DataCache.getUserPlaylists(context).takeIf { it.isNotEmpty() }) 
    }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
    val sheetBgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
    val SFPro = FontFamily(Font(R.font.sfprodisplaymedium))
    
    val allTrackIds = androidx.compose.runtime.remember(trackId, trackIds) {
        (listOfNotNull(trackId) + trackIds).distinct()
    }
    
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!useYouTubePlaylists) {
            if (playlists.value == null) {
                val cached = DataCache.getUserPlaylists(context)
                if (cached.isNotEmpty()) {
                    playlists.value = cached
                }
            }

            val result = runCatching { fetchUserPlaylists(apiBaseUrl, context) }
            val fetched = result.getOrNull()

            if (fetched != null) {
                playlists.value = fetched
                DataCache.saveUserPlaylists(context, fetched)
            } else if (playlists.value == null) {
                playlists.value = emptyList()
            }
        } else {
            val fetched = runCatching {
                initYouTubeAuth(context)
                com.metrolist.innertube.YouTube.library("FEmusic_liked_playlists")
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<com.metrolist.innertube.models.PlaylistItem>()
                    ?.filter { it.isEditable } 
                    ?.mapNotNull { it.toPlaylist() }
                    .orEmpty()
            }.getOrDefault(emptyList())
            playlists.value = fetched
        }
    }
    
    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                coroutineScope.launch(Dispatchers.Main) {
                    val success = if (useYouTubePlaylists) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                initYouTubeAuth(context)
                                val createdPlaylistId = com.metrolist.innertube.YouTube.createPlaylist(name)
                                val ytTrackIds = allTrackIds.mapNotNull { it.removePrefix("yt_").ifBlank { null } }
                                ytTrackIds.isNotEmpty() &&
                                    ytTrackIds.all { track ->
                                        com.metrolist.innertube.YouTube.addToPlaylist(createdPlaylistId, track).isSuccess
                                    }
                            }.getOrDefault(false)
                        }
                    } else {
                        val token = AuthPreferences.getUser(context)?.token
                        val newPlaylist = createPlaylist(apiBaseUrl, name, context, token)
                        if (newPlaylist != null) {
                            addTracksToPlaylist(apiBaseUrl, newPlaylist.id, allTrackIds, context, token)
                        } else {
                            false
                        }
                    }
                    onPlaylistSelected(success)
                    onDismissRequest()
                    showCreatePlaylistDialog = false
                }
            }
        )
    }

    androidx.compose.material3.ModalBottomSheet(
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
                .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.65f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Add to Playlist",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SFPro,
                modifier = Modifier.padding(bottom = 20.dp)
            )
            
            if (playlists.value == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                .clickable { showCreatePlaylistDialog = true }
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New Playlist",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Create New Playlist",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = SFPro
                                )
                                Text(
                                    text = "Add to a new playlist",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontFamily = SFPro
                                )
                            }
                        }
                        
                        
                        if (useYouTubePlaylists) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFFFF6B9D).copy(alpha = 0.15f))
                                    .clickable {
                                        onDismissRequest()
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val ytTrackIds = allTrackIds.mapNotNull { it.removePrefix("yt_").ifBlank { null } }
                                            val success = ytTrackIds.isNotEmpty() &&
                                                ytTrackIds.all { track ->
                                                    com.metrolist.innertube.YouTube.likeVideo(track, true).isSuccess
                                                }
                                            withContext(Dispatchers.Main) {
                                                onPlaylistSelected(success)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFFF6B9D).copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fav),
                                        contentDescription = "Liked Music",
                                        tint = Color(0xFFFF6B9D),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Liked Music",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = SFPro
                                    )
                                    Text(
                                        text = "Add to your liked songs",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontFamily = SFPro
                                    )
                                }
                            }
                        }
                        
                        if (playlists.value!!.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }

                    if (playlists.value!!.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "No playlists yet",
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                        fontSize = 15.sp,
                                        fontFamily = SFPro
                                    )
                                    Text(
                                        text = "Create your first playlist",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontFamily = SFPro
                                    )
                                }
                            }
                        }
                    } else {
                        items(playlists.value!!) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                    .clickable {
                                        onDismissRequest()
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val success = if (useYouTubePlaylists) {
                                                val ytTrackIds = allTrackIds.mapNotNull { it.removePrefix("yt_").ifBlank { null } }
                                                ytTrackIds.isNotEmpty() &&
                                                    ytTrackIds.all { track ->
                                                        com.metrolist.innertube.YouTube.addToPlaylist(playlist.id, track).isSuccess
                                                    }
                                            } else {
                                                val token = AuthPreferences.getUser(context)?.token
                                                addTracksToPlaylist(apiBaseUrl, playlist.id, allTrackIds, context, token)
                                            }
                                            withContext(Dispatchers.Main) {
                                                onPlaylistSelected(success)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverArt(
                                    coverUrl = playlist.thumbnailUrl,
                                    fallbackColor = playlist.color ?: Color.DarkGray,
                                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = SFPro,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (playlist.subtitle != null) {
                                        Text(
                                            text = playlist.subtitle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 13.sp,
                                            fontFamily = SFPro
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Add",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun StartJamBottomSheet(
    onDismissRequest: () -> Unit,
    songs: List<Song>,
    onStartJam: (String?, Boolean, Boolean) -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedSongId by remember { mutableStateOf<String?>(null) }
    var allowSeek by remember { mutableStateOf(false) }
    var allowEditQueue by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
    val colorScheme = MaterialTheme.colorScheme
    val sheetColor = if (isAmoled) Color.Black else colorScheme.surface
    val fieldColor = if (isAmoled) Color(0xFF1E1E1E) else colorScheme.surfaceVariant
    val accentColor = colorScheme.primary
    val accentLuminance = accentColor.luminance()
    val accentContentColor = if (((accentLuminance + 0.05f) / 0.05f) >= (1.05f / (accentLuminance + 0.05f))) {
        Color.Black
    } else {
        Color.White
    }
    val secondaryButtonColor = if (isAmoled) Color(0xFF1E1E1E) else colorScheme.surfaceVariant
    val secondaryButtonTextColor = if (isAmoled) colorScheme.onSurface else colorScheme.onSurface
    val disabledPrimaryButtonColor = if (isAmoled) accentColor.copy(alpha = 0.35f) else colorScheme.surfaceVariant
    val disabledPrimaryContentColor = if (isAmoled) accentContentColor.copy(alpha = 0.72f) else colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

    val filteredSongs = remember(searchQuery, songs) {
        if (searchQuery.isBlank()) songs
        else songs.filter { 
            it.title.contains(searchQuery, ignoreCase = true) || 
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = sheetColor,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Start Jam Session",
                    color = colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .background(colorScheme.onSurface.copy(alpha = 0.1f), androidx.compose.foundation.shape.CircleShape)
                        .size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "SELECT TRACK", color = colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search tracks", color = colorScheme.onSurfaceVariant) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = fieldColor,
                    unfocusedContainerColor = fieldColor,
                    cursorColor = accentColor
                ),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp)
            ) {
                items(filteredSongs.take(20)) { song ->
                    val isSelected = selectedSongId == song.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) accentColor.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { selectedSongId = if (isSelected) null else song.id }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverArt(
                            coverUrl = song.coverUrl,
                            fallbackColor = song.color,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                color = colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artist,
                                color = colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Add, 
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "SETTINGS", color = colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            androidx.compose.material3.Card(
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = fieldColor
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { allowSeek = !allowSeek }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = allowSeek,
                        onCheckedChange = { allowSeek = it },
                        modifier = Modifier.size(20.dp),
                        colors = androidx.compose.material3.CheckboxDefaults.colors(
                            checkedColor = accentColor,
                            uncheckedColor = colorScheme.onSurfaceVariant,
                            checkmarkColor = accentContentColor
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow listeners to seek", color = colorScheme.onSurface, fontSize = 14.sp)
                }
                HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { allowEditQueue = !allowEditQueue }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = allowEditQueue,
                        onCheckedChange = { allowEditQueue = it },
                        modifier = Modifier.size(20.dp),
                        colors = androidx.compose.material3.CheckboxDefaults.colors(
                            checkedColor = accentColor,
                            uncheckedColor = colorScheme.onSurfaceVariant,
                            checkmarkColor = accentContentColor
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow listeners to edit queue", color = colorScheme.onSurface, fontSize = 14.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            androidx.compose.material3.Button(
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = secondaryButtonColor,
                    contentColor = secondaryButtonTextColor,
                    disabledContainerColor = secondaryButtonColor.copy(alpha = if (isAmoled) 0.7f else 0.92f),
                    disabledContentColor = secondaryButtonTextColor.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel", color = secondaryButtonTextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.Button(
                onClick = { onStartJam(selectedSongId, allowSeek, allowEditQueue) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = accentContentColor,
                    disabledContainerColor = disabledPrimaryButtonColor,
                    disabledContentColor = disabledPrimaryContentColor
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Start Jam", color = accentContentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun JoinJamBottomSheet(
    onDismissRequest: () -> Unit,
    onJoinJam: (String) -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var jamInput by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
    val colorScheme = MaterialTheme.colorScheme
    val sheetColor = if (isAmoled) Color.Black else colorScheme.surface
    val fieldColor = if (isAmoled) Color(0xFF1E1E1E) else colorScheme.surfaceVariant
    val accentColor = colorScheme.primary
    val accentLuminance = accentColor.luminance()
    val accentContentColor = if (((accentLuminance + 0.05f) / 0.05f) >= (1.05f / (accentLuminance + 0.05f))) {
        Color.Black
    } else {
        Color.White
    }
    val secondaryButtonColor = if (isAmoled) Color(0xFF1E1E1E) else colorScheme.surfaceVariant
    val secondaryButtonTextColor = if (isAmoled) colorScheme.onSurface else colorScheme.onSurface
    val disabledPrimaryButtonColor = if (isAmoled) accentColor.copy(alpha = 0.35f) else colorScheme.surfaceVariant
    val disabledPrimaryContentColor = if (isAmoled) accentContentColor.copy(alpha = 0.72f) else colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = sheetColor,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = colorScheme.onSurfaceVariant) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Join Jam Session",
                color = colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            androidx.compose.material3.Surface(
                modifier = Modifier.size(80.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = fieldColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground), 
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(40.dp)
                )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Enter the Jam Session ID or paste the invite link to join",
                color = colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = jamInput,
                onValueChange = { if (!isJoining) jamInput = it },
                placeholder = { Text("Jam ID or link", color = colorScheme.onSurfaceVariant) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isJoining,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = fieldColor,
                    unfocusedContainerColor = fieldColor,
                    focusedTextColor = colorScheme.onSurface,
                    unfocusedTextColor = colorScheme.onSurface,
                    cursorColor = accentColor
                ),
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            androidx.compose.material3.Button(
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !isJoining,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = secondaryButtonColor,
                    contentColor = secondaryButtonTextColor,
                    disabledContainerColor = secondaryButtonColor.copy(alpha = if (isAmoled) 0.7f else 0.92f),
                    disabledContentColor = secondaryButtonTextColor.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel", color = secondaryButtonTextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            androidx.compose.material3.Button(
                onClick = { 
                    if (jamInput.isNotBlank() && !isJoining) {
                        isJoining = true
                        
                        val id = if (jamInput.contains("jam_")) {
                            "jam_" + jamInput.substringAfter("jam_").substringBefore("/")
                        } else {
                            jamInput.trim()
                        }
                        onJoinJam(id)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = accentContentColor,
                    disabledContainerColor = disabledPrimaryButtonColor,
                    disabledContentColor = disabledPrimaryContentColor
                ),
                shape = RoundedCornerShape(12.dp),
                enabled = jamInput.isNotBlank() && !isJoining
            ) {
                if (isJoining) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = accentContentColor
                    )
                } else {
                    Text("Join Jam", color = accentContentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(32.dp)) 
        }
    }
}
