package com.xstream.music.app

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
import android.content.Intent
import timber.log.Timber
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Add

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith


import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.preferences.JamPreferences
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.api.LocalStubApi
import com.xstream.music.data.api.StreamXApi
import com.xstream.music.data.api.normalizeApiInput
import com.xstream.music.data.model.AlbumData
import com.xstream.music.data.model.CreateJamRequest
import com.xstream.music.data.model.Friend
import com.xstream.music.data.model.FriendListening
import com.xstream.music.data.model.FriendPresence
import com.xstream.music.data.model.FriendRequest
import com.xstream.music.data.model.FriendSettings
import com.xstream.music.data.model.FriendsSection
import com.xstream.music.data.model.HomeAlbumsSection
import com.xstream.music.data.model.AlbumCard
import com.xstream.music.data.model.HomePlaylistsSection
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.JamSessionSection
import com.xstream.music.data.model.JamSettings
import com.xstream.music.data.model.LatestSongs
import com.xstream.music.data.model.LatestSongsLoadingSkeleton
import com.xstream.music.data.model.Playlist
import com.xstream.music.data.model.RandomMix
import com.xstream.music.data.model.RandomMixLoadingSkeleton
import com.xstream.music.data.model.Song
import com.xstream.music.data.model.UpdatedPlaylists
import com.xstream.music.data.model.UpdatedPlaylistsLoadingSkeleton
import com.xstream.music.features.album.AlbumScreen
import com.xstream.music.features.artist.ArtistScreen
import com.xstream.music.features.auth.LoginScreen
import com.xstream.music.features.downloads.DownloadsScreen
import com.xstream.music.features.home.LatestSongsScreen
import com.xstream.music.features.home.SourcePickerScreen
import com.xstream.music.features.home.YouTubeHomeScreen
import com.xstream.music.features.jam.JamScreen
import com.xstream.music.features.library.LibraryScreen
import com.xstream.music.features.mix.RandomMixScreen
import com.xstream.music.features.playlist.PlaylistDetailScreen
import com.xstream.music.features.playlist.PlaylistsScreen
import com.xstream.music.features.playlist.AppleMusicSongRow
import com.xstream.music.features.profile.ProfileScreen
import com.xstream.music.features.search.SearchResultsScreen
import com.xstream.music.features.settings.DeveloperSettingsScreen
import com.xstream.music.features.settings.AppearanceSettingsScreen
import com.xstream.music.features.settings.AboutScreen
import com.xstream.music.features.settings.SettingsScreen
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.xstream.music.player.manager.MusicPlayerManager
import com.xstream.music.player.ui.AudioSettingsScreen
import com.xstream.music.player.ui.FullPlayerScreen
import com.xstream.music.realtime.websocket.JamWebSocketManager
import com.xstream.music.realtime.websocket.PresenceWebSocketManager
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.JoinJamBottomSheet
import com.xstream.music.ui.components.MusicPlayer
import com.xstream.music.ui.components.PlayerCoverArt
import com.xstream.music.ui.components.SearchBar
import com.xstream.music.ui.components.StartJamBottomSheet
import com.xstream.music.ui.components.TopBar

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    api: StreamXApi = LocalStubApi, 
    isDarkTheme: MutableState<Boolean>,
    isDynamicMaterial: MutableState<Boolean>,
    isCustomPicker: MutableState<Boolean>,
    isAmoledBlack: MutableState<Boolean>,
    initialJamId: String? = null,
    initialJamApiUrl: String? = null,
    onJamJoined: () -> Unit = {},
    initialPlaylistId: String? = null,
    initialPlaylistApiUrl: String? = null,
    onPlaylistOpened: () -> Unit = {},
    initialAlbumId: String? = null,
    initialAlbumApiUrl: String? = null,
    onAlbumOpened: () -> Unit = {},
    initialTrackId: String? = null,
    initialTrackApiUrl: String? = null,
    onTrackOpened: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val disableExpandedPlayerAnimation = DataCache.isExoPlayerAnimationDisabled(context)
    val launchPrefs = remember { context.getSharedPreferences("app_launch_prefs", Context.MODE_PRIVATE) }
    val hasSeenLaunchPicker = remember { launchPrefs.getBoolean("has_seen_source_picker", false) }
    val userState = remember { mutableStateOf(AuthPreferences.getUser(context)) }
    val apiUrlState = rememberSaveable { mutableStateOf(ApiPreferences.getApiUrl(context)) }
    val currentHomeProvider by DataCache.homeProvider.collectAsState()
    val currentScreen = rememberSaveable {
        mutableStateOf(
            if (!hasSeenLaunchPicker) "launch_picker" else if (apiUrlState.value.isBlank() && DataCache.getProvider(context) != "youtube") "api" else "home"
        )
    }
    val navigationBackStack = remember { mutableStateListOf<String>() }
    var previousScreen by rememberSaveable { mutableStateOf(currentScreen.value) }
    var isNavigatingBack by remember { mutableStateOf(false) }
    val screenStateHolder = rememberSaveableStateHolder()
    val selectedPlaylistState = remember { mutableStateOf<Playlist?>(null) }
    val selectedAlbumIdState = remember { mutableStateOf<String?>(null) }
    val selectedArtistNameState = remember { mutableStateOf("") }
    val selectedArtistIdState = remember { mutableStateOf<String?>(null) }
    val ytQuickPicksSongsState = remember { mutableStateOf<List<Song>>(emptyList()) }
    val ytQuickPicksTitleState = remember { mutableStateOf("") }
    val ytSectionItemsState = remember { mutableStateOf<List<YTItem>>(emptyList()) }
    val ytSectionTitleState = remember { mutableStateOf("") }
    
    val artistHistoryStack = remember { mutableStateOf(listOf<Pair<String, String?>>()) }
    val isPlayerExpanded = rememberSaveable { mutableStateOf(false) }
    val jamIdState = remember { mutableStateOf<String?>(null) }
    val friendsState = remember { mutableStateOf<List<Friend>>(emptyList()) }
    val friendsListeningState = remember { mutableStateOf<Map<Long, FriendListening>>(emptyMap()) }
    val friendSettingsState = remember { mutableStateOf<FriendSettings?>(DataCache.getFriendSettings(context)) }

    @Suppress("DEPRECATION")
    SideEffect {
        val activity = context as? android.app.Activity ?: return@SideEffect
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        val isFavoritesPlaylist = currentScreen.value == "playlist_detail" &&
            selectedPlaylistState.value?.id == "favorites"
        window.statusBarColor = if (isFavoritesPlaylist) {
            if (isDarkTheme.value) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        } else {
            android.graphics.Color.TRANSPARENT
        }
        val useLightIcons = if (isPlayerExpanded.value) {
            false 
        } else {
            !isDarkTheme.value
        }
        controller.isAppearanceLightStatusBars = useLightIcons
        controller.isAppearanceLightNavigationBars = useLightIcons
    }
    
    LaunchedEffect(Unit) {
        val savedUrl = ApiPreferences.getApiUrl(context)
        val user = AuthPreferences.getUser(context)
        if (savedUrl.isNotBlank() && user != null) {
            scope.launch {
                val token = AuthPreferences.getEffectiveToken(context)
                val settingsResult = fetchFriendSettings(savedUrl, token ?: "")
                if (settingsResult?.ok == true && settingsResult.settings != null) {
                    friendSettingsState.value = settingsResult.settings
                    DataCache.setFriendSettings(context, settingsResult.settings)
                }
            }
        }
    }

    val playerManager: MusicPlayerManager = viewModel { MusicPlayerManager(context) }

    LaunchedEffect(hasSeenLaunchPicker) {
        if (!hasSeenLaunchPicker) {
            playerManager.clearPlaybackState()
            isPlayerExpanded.value = false
        }
    }

    
    LaunchedEffect(Unit) {
        DataCache.loadFavoriteIds(context)
        DownloadHelper.updateParallelSettings(context)
    }

    
    LaunchedEffect(initialJamId, initialJamApiUrl) {
        if (initialJamId != null) {
            val resolvedApiUrl = initialJamApiUrl?.let(::normalizeApiInput).orEmpty().ifBlank { apiUrlState.value }
            if (resolvedApiUrl.isBlank()) {
                onJamJoined()
                return@LaunchedEffect
            }

            if (!resolvedApiUrl.equals(apiUrlState.value, ignoreCase = true)) {
                ApiPreferences.setApiUrl(context, resolvedApiUrl)
                apiUrlState.value = resolvedApiUrl
            }

            val token = AuthPreferences.getEffectiveToken(context)
            val resp = joinJam(resolvedApiUrl, initialJamId, context, token)
            if (resp.ok && resp.jam != null) {
                jamIdState.value = resp.jam.id
                JamPreferences.setStoredJamId(context, resp.jam.id)
                JamWebSocketManager.connect(resolvedApiUrl, resp.jam.id, token ?: "", resp.jam)
                playerManager.enableJamSync(resolvedApiUrl, token ?: "")
                currentScreen.value = "jam_session"
                onJamJoined() 
            } else {
                
                onJamJoined()
            }
        }
    }

    
    LaunchedEffect(initialPlaylistId, initialPlaylistApiUrl) {
        if (initialPlaylistId != null) {
            val resolvedApiUrl = initialPlaylistApiUrl?.let(::normalizeApiInput).orEmpty().ifBlank { apiUrlState.value }
            if (resolvedApiUrl.isBlank()) {
                onPlaylistOpened()
                return@LaunchedEffect
            }

            if (!resolvedApiUrl.equals(apiUrlState.value, ignoreCase = true)) {
                ApiPreferences.setApiUrl(context, resolvedApiUrl)
                apiUrlState.value = resolvedApiUrl
            }

            selectedPlaylistState.value = Playlist(
                id = initialPlaylistId,
                title = "Loading Playlist...",
                requiresAuth = false 
            )
            currentScreen.value = "playlist_detail"
            onPlaylistOpened() 
        }
    }

    LaunchedEffect(initialAlbumId, initialAlbumApiUrl) {
        if (initialAlbumId != null) {
            val resolvedApiUrl = initialAlbumApiUrl?.let(::normalizeApiInput).orEmpty().ifBlank { apiUrlState.value }
            if (resolvedApiUrl.isBlank()) {
                onAlbumOpened()
                return@LaunchedEffect
            }

            if (!resolvedApiUrl.equals(apiUrlState.value, ignoreCase = true)) {
                ApiPreferences.setApiUrl(context, resolvedApiUrl)
                apiUrlState.value = resolvedApiUrl
            }

            selectedAlbumIdState.value = initialAlbumId
            currentScreen.value = "album_detail"
            onAlbumOpened()
        }
    }

    LaunchedEffect(initialTrackId, initialTrackApiUrl) {
        if (initialTrackId != null) {
            val resolvedApiUrl = initialTrackApiUrl?.let(::normalizeApiInput).orEmpty().ifBlank { apiUrlState.value }
            if (resolvedApiUrl.isBlank()) {
                onTrackOpened()
                return@LaunchedEffect
            }

            if (!resolvedApiUrl.equals(apiUrlState.value, ignoreCase = true)) {
                ApiPreferences.setApiUrl(context, resolvedApiUrl)
                apiUrlState.value = resolvedApiUrl
            }

            val token = AuthPreferences.getEffectiveToken(context)
            val song = fetchSong(resolvedApiUrl, initialTrackId, context, token)
            if (song != null) {
                playerManager.setQueueFromLatest(listOf(song), 0, resolvedApiUrl, token)
                if (currentScreen.value == "api" || currentScreen.value == "launch_picker") {
                    currentScreen.value = "home"
                }
            }
            onTrackOpened()
        }
    }

    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val apiUrl = apiUrlState.value
                val user = AuthPreferences.getUser(context)
                if (apiUrl.isNotBlank() && user != null) {
                    val token = AuthPreferences.getEffectiveToken(context) ?: ""
                    scope.launch {
                        val settingsResult = fetchFriendSettings(apiUrl, token)
                        if (settingsResult?.ok == true && settingsResult.settings != null) {
                            friendSettingsState.value = settingsResult.settings
                            DataCache.setFriendSettings(context, settingsResult.settings)
                        }

                        if (friendSettingsState.value?.share_listening != "none") {
                            Timber.d("MusicScreen", "Resuming - ensuring Presence connected")
                            PresenceWebSocketManager.connect(apiUrl, token)
                        } else {
                            PresenceWebSocketManager.disconnect()
                        }

                        val friendsResult = runCatching { fetchFriends(apiUrl, token) }.getOrNull()
                        if (friendsResult != null) {
                            friendsState.value = friendsResult
                        }

                        val listeningResult = runCatching { fetchFriendsListening(apiUrl, token) }.getOrNull()
                        if (listeningResult != null) {
                            friendsListeningState.value = mapListening(listeningResult)
                        }
                    }

                    
                    val activeJamId = jamIdState.value ?: JamPreferences.getStoredJamId(context)
                    if (activeJamId != null && activeJamId.isNotBlank()) {
                         Timber.d("MusicScreen", "Resuming - ensuring Jam ($activeJamId) connected")
                         
                         scope.launch {
                             val resp = fetchJam(apiUrl, activeJamId, context, token)
                             if (resp.ok && resp.jam != null) {
                                 jamIdState.value = resp.jam.id
                                 JamWebSocketManager.connect(apiUrl, resp.jam.id, token, resp.jam)
                                 playerManager.enableJamSync(apiUrl, token)
                             } else {
                                 
                                 
                                 if (!JamWebSocketManager.isConnected()) {
                                     JamWebSocketManager.connect(apiUrl, activeJamId, token)
                                     playerManager.enableJamSync(apiUrl, token)
                                 }
                             }
                         }
                    }
                    
                    
                    playerManager.sendManualUpdate()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    
    val latestSongsState = remember { mutableStateOf(DataCache.getLatestSongs(context)) }
    val randomMixSongsState = remember { mutableStateOf(DataCache.getRandomMix(context)) }
    val playlistsState = remember { mutableStateOf(DataCache.getPlaylists(context)) }
    val userPlaylistsState = remember { mutableStateOf(DataCache.getUserPlaylists(context)) }
    val userAlbumsState = remember { mutableStateOf<List<AlbumData>>(emptyList()) }
    val savedAlbumIds by DataCache.savedAlbumIds.collectAsState()

    LaunchedEffect(apiUrlState.value, userState.value, savedAlbumIds) {
        val savedUrl = apiUrlState.value
        val token = userState.value?.token
        if (savedUrl.isNotBlank() && token != null) {
            val albums = runCatching { fetchSavedAlbums(savedUrl, context = context, token = token) }.getOrNull().orEmpty()
            userAlbumsState.value = albums
            
            if (albums.size != DataCache.savedAlbumIds.value.size) {
                DataCache.savedAlbumIds.value = albums.map { it._id }.toSet()
            }
        } else {
            userAlbumsState.value = emptyList()
            DataCache.savedAlbumIds.value = emptySet()
        }
    }

    val isLoadingPlaylistsState = remember { mutableStateOf(false) }
    val isLoadingLatestState = remember { mutableStateOf(false) }
    val isLoadingRandomMixState = remember { mutableStateOf(false) }
    val hasLoadedRandomMixState = remember { mutableStateOf(false) }
    val hasLoadedUserPlaylistsState = remember { mutableStateOf(false) }
    
    
    LaunchedEffect(Unit) {
        DataCache.cacheCleared.collect { timestamp ->
            if (timestamp > 0) {
                
                playlistsState.value = emptyList()
                latestSongsState.value = emptyList()
                randomMixSongsState.value = emptyList()
                userPlaylistsState.value = emptyList()
                hasLoadedRandomMixState.value = false
                hasLoadedUserPlaylistsState.value = false
                
                
                isLoadingPlaylistsState.value = true
                isLoadingLatestState.value = true
                isLoadingRandomMixState.value = false
                
                launch {
                    val freshPlaylists = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { fetchPlaylists(apiUrlState.value, context = context) }.getOrNull().orEmpty()
                    }
                    if (freshPlaylists.isNotEmpty()) {
                        val frozen = withContext(kotlinx.coroutines.Dispatchers.Default) { freezeList(freshPlaylists) }
                        playlistsState.value = frozen
                        if (DataCache.isSaveCacheEnabled(context)) DataCache.savePlaylists(context, frozen)
                    }
                    isLoadingPlaylistsState.value = false
                }
                
                launch {
                    val fetchedLatest = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { fetchBrowseSongs(apiUrlState.value, page = 1, context = context) }.getOrNull().orEmpty()
                    }
                    if (fetchedLatest.isNotEmpty()) {
                        val frozen = withContext(kotlinx.coroutines.Dispatchers.Default) { freezeList(fetchedLatest) }
                        latestSongsState.value = frozen
                        if (DataCache.isSaveCacheEnabled(context)) DataCache.saveLatestSongs(context, frozen)
                    }
                    isLoadingLatestState.value = false
                }
                
                launch {
                    isLoadingRandomMixState.value = true
                    val token = AuthPreferences.getEffectiveToken(context)
                    val fetchedRandom = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { fetchRandomMix(apiUrlState.value, limit = 100, token = token) }.getOrNull().orEmpty()
                    }
                    if (fetchedRandom.isNotEmpty()) {
                        val frozen = withContext(kotlinx.coroutines.Dispatchers.Default) { freezeList(fetchedRandom) }
                        randomMixSongsState.value = frozen
                        if (DataCache.isSaveCacheEnabled(context)) DataCache.saveRandomMix(context, frozen)
                    }
                    hasLoadedRandomMixState.value = true
                    isLoadingRandomMixState.value = false
                }
                
                launch {
                    val token = AuthPreferences.getEffectiveToken(context)
                    val fetchedUserPlaylists = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { fetchUserPlaylists(apiUrlState.value, context = context, token = token) }.getOrNull().orEmpty()
                    }
                    val frozen = withContext(kotlinx.coroutines.Dispatchers.Default) { freezeList(fetchedUserPlaylists) }
                    userPlaylistsState.value = frozen
                    if (DataCache.isSaveCacheEnabled(context)) {
                        DataCache.saveUserPlaylists(context, frozen)
                    }
                    hasLoadedUserPlaylistsState.value = true
                }
                
            }
        }
    }
    val friendRequestsState = remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    val isLoadingFriends = remember { mutableStateOf(true) }
    val showAddFriendDialog = remember { mutableStateOf(false) }
    val showFriendsSettingsDialog = remember { mutableStateOf(false) }
    val friendToRemove = remember { mutableStateOf<Friend?>(null) }
    val showFriendListeningDialog = remember { mutableStateOf(false) }
    val friendListeningFriend = remember { mutableStateOf<Friend?>(null) }
    val friendListeningSong = remember { mutableStateOf<Song?>(null) }
    val friendListeningJam = remember { mutableStateOf(false) }
    val friendListeningLoading = remember { mutableStateOf(false) }
    val showCreatePlaylistDialog = remember { mutableStateOf(false) }
    val showRenamePlaylistDialog = remember { mutableStateOf(false) }
    val playlistToRename = remember { mutableStateOf<Playlist?>(null) }
    val showStartJamBottomSheet = remember { mutableStateOf(false) }
    val showJoinJamBottomSheet = remember { mutableStateOf(false) }
    val youtubeJamSongsState = remember { mutableStateOf<List<Song>>(emptyList()) }
    
    
    val searchQueryState = remember { mutableStateOf("") }
    val swipeExpandProgress = remember { mutableFloatStateOf(0f) }

    fun performLogout() {
        val token = userState.value?.token
        val activeJamId = jamIdState.value ?: JamPreferences.getStoredJamId(context)
        if (!activeJamId.isNullOrBlank() && token != null && apiUrlState.value.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { leaveJam(apiUrlState.value, activeJamId, context, token) }
            }
        }

        AuthPreferences.clear(context)
        DataCache.clearAuthenticatedUserData(context)
        PresenceWebSocketManager.disconnect()
        JamWebSocketManager.disconnect()
        playerManager.disableJamSync()
        JamPreferences.clearStoredJamId(context)

        userState.value = null
        jamIdState.value = null
        selectedPlaylistState.value = null
        selectedAlbumIdState.value = null
        selectedArtistNameState.value = ""
        selectedArtistIdState.value = null
        artistHistoryStack.value = emptyList()
        userPlaylistsState.value = emptyList()
        userAlbumsState.value = emptyList()
        friendRequestsState.value = emptyList()
        friendsState.value = emptyList()
        friendsListeningState.value = emptyMap()
        friendSettingsState.value = DataCache.getFriendSettings(context)
        if (currentScreen.value == "login" || currentScreen.value == "profile") {
            currentScreen.value = "home"
        }
    }

    LaunchedEffect(currentScreen.value) {
        val target = currentScreen.value
        if (target != previousScreen) {
            if (isNavigatingBack) {
                isNavigatingBack = false
            } else if (navigationBackStack.lastOrNull() != previousScreen) {
                navigationBackStack.add(previousScreen)
            }
            previousScreen = target
        }
    }

    fun navigateBack(fallback: String = "home") {
        if (navigationBackStack.isNotEmpty()) {
            isNavigatingBack = true
            currentScreen.value = navigationBackStack.removeAt(navigationBackStack.lastIndex)
        } else {
            currentScreen.value = fallback
        }
    }

    
    BackHandler(enabled = isPlayerExpanded.value || (currentScreen.value != "home" && currentScreen.value != "launch_picker")) {
        if (isPlayerExpanded.value) {
            isPlayerExpanded.value = false
        } else {
            if (currentScreen.value == "playlist_detail" || currentScreen.value == "all_playlists") {
                if (currentScreen.value == "playlist_detail" && selectedPlaylistState.value != null) {
                    
                    
                }
                selectedPlaylistState.value = null
            }
            navigateBack()
        }
    }

    LaunchedEffect(apiUrlState.value, userState.value) {
        val savedUrl = apiUrlState.value
        if (savedUrl.isBlank()) {
            latestSongsState.value = emptyList()
            randomMixSongsState.value = emptyList()
            playlistsState.value = emptyList()
            userPlaylistsState.value = emptyList()
            hasLoadedRandomMixState.value = false
            hasLoadedUserPlaylistsState.value = false
            return@LaunchedEffect
        }
        
        
        val storedJamId = JamPreferences.getStoredJamId(context)
        if (!storedJamId.isNullOrBlank()) {
            launch {
                val token = AuthPreferences.getEffectiveToken(context)
                val jamResp = fetchJam(savedUrl, storedJamId, context, token)
                if (jamResp.ok && jamResp.jam != null) {
                    jamIdState.value = jamResp.jam.id
                    
                    JamWebSocketManager.connect(savedUrl, jamResp.jam.id, token ?: "", jamResp.jam)
                    playerManager.enableJamSync(savedUrl, token ?: "")
                } else {
                    
                    JamPreferences.clearStoredJamId(context)
                }
            }
        }

        val cacheEnabled = DataCache.isSaveCacheEnabled(context)
        val cacheExpired = DataCache.isCacheExpired(context)
        
        
        
        
        
        val shouldFetchLatest = !cacheEnabled || latestSongsState.value.isEmpty() || cacheExpired
        val shouldFetchPlaylists = !cacheEnabled || playlistsState.value.isEmpty() || 
                                   playlistsState.value.any { it.id.isBlank() || it.id.startsWith("dummy") } || 
                                   cacheExpired
        val shouldFetchRandom = !cacheEnabled || randomMixSongsState.value.isEmpty() || cacheExpired
        val shouldFetchUserPlaylists = !cacheEnabled || userPlaylistsState.value.isEmpty() || cacheExpired
        hasLoadedRandomMixState.value = !shouldFetchRandom
        hasLoadedUserPlaylistsState.value = !shouldFetchUserPlaylists

        if (shouldFetchPlaylists) {
            launch {
                isLoadingPlaylistsState.value = true
                val freshPlaylists = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { fetchPlaylists(savedUrl, context = context) }.getOrNull().orEmpty()
                }
                if (freshPlaylists.isNotEmpty()) {
                    val frozen = withContext(kotlinx.coroutines.Dispatchers.Default) { freezeList(freshPlaylists) }
                    playlistsState.value = frozen
                    if (cacheEnabled) DataCache.savePlaylists(context, frozen)
                }
                isLoadingPlaylistsState.value = false
            }
        }

        if (shouldFetchLatest) {
            launch {
                isLoadingLatestState.value = true
                val fetchedLatest = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { fetchBrowseSongs(savedUrl, page = 1, context = context) }.getOrNull().orEmpty()
                }
                if (fetchedLatest.isNotEmpty()) {
                    val frozen = withContext(kotlinx.coroutines.Dispatchers.Default) { freezeList(fetchedLatest) }
                    latestSongsState.value = frozen
                    if (cacheEnabled) DataCache.saveLatestSongs(context, frozen)
                }
                isLoadingLatestState.value = false
            }
        }
        
        if (shouldFetchRandom) {
            launch {
                isLoadingRandomMixState.value = true
                val token = AuthPreferences.getEffectiveToken(context)
                val fetchedRandom = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { fetchRandomMix(savedUrl, limit = 100, token = token) }.getOrNull().orEmpty()
                }
                if (fetchedRandom.isNotEmpty()) {
                    val frozen = withContext(kotlinx.coroutines.Dispatchers.Default) { freezeList(fetchedRandom) }
                    randomMixSongsState.value = frozen
                    if (cacheEnabled) DataCache.saveRandomMix(context, frozen)
                }
                hasLoadedRandomMixState.value = true
                isLoadingRandomMixState.value = false
            }
        }
        
        if (shouldFetchUserPlaylists) {
            launch {
                val token = AuthPreferences.getEffectiveToken(context)
                val fetchedUserPlaylists = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { fetchUserPlaylists(savedUrl, context = context, token = token) }.getOrNull().orEmpty()
                }
                val frozen = withContext(kotlinx.coroutines.Dispatchers.Default) { freezeList(fetchedUserPlaylists) }
                userPlaylistsState.value = frozen
                if (cacheEnabled) {
                    DataCache.saveUserPlaylists(context, frozen)
                }
                hasLoadedUserPlaylistsState.value = true
            }
        }
        
        launch {
            val user = AuthPreferences.getUser(context)
            if (user != null) {
                val token = AuthPreferences.getEffectiveToken(context) ?: ""
                val settingsResult = fetchFriendSettings(savedUrl, token)
                if (settingsResult?.ok == true && settingsResult.settings != null) {
                    friendSettingsState.value = settingsResult.settings
                    DataCache.setFriendSettings(context, settingsResult.settings)
                }

                if (friendSettingsState.value?.share_listening != "none") {
                    PresenceWebSocketManager.connect(savedUrl, token)
                } else {
                    PresenceWebSocketManager.disconnect()
                }

                val fetchedFavs = runCatching { fetchFavoriteIds(savedUrl, context = context, token = token) }.getOrNull().orEmpty()
                val currentFavs = DataCache.favoriteIds.value.toMutableSet()
                currentFavs.addAll(fetchedFavs)
                DataCache.favoriteIds.value = currentFavs
                DataCache.saveFavoriteIds(context, currentFavs)

                val requestsResult = runCatching { fetchFriendRequests(savedUrl, token) }.getOrNull().orEmpty()
                friendRequestsState.value = requestsResult


                if (friendsState.value.isEmpty()) isLoadingFriends.value = true
                val friendsResult = runCatching { fetchFriends(savedUrl, token) }.getOrNull().orEmpty()
                friendsState.value = friendsResult

                val listeningResult = runCatching { fetchFriendsListening(savedUrl, token) }.getOrNull().orEmpty()
                val listeningMap = mapListening(listeningResult)
                friendsListeningState.value = listeningMap
                isLoadingFriends.value = false

                
                launch {
                    PresenceWebSocketManager.messages.collect { json ->
                        val type = json.optString("type")
                        
                        if (type == "initial_state") {
                            
                            val presenceJson = json.optJSONObject("presence")
                            if (presenceJson != null) {
                                val currentFriends = friendsState.value.toMutableList()
                                var changed = false
                                for (key in presenceJson.keys()) {
                                    val uid = key.toLongOrNull() ?: continue
                                    val pObj = presenceJson.optJSONObject(key) ?: continue
                                    val idx = currentFriends.indexOfFirst { it._id == uid }
                                    if (idx != -1) {
                                        val friend = currentFriends[idx]
                                        val newP = FriendPresence(
                                            online = pObj.optBoolean("online", false),
                                            last_seen = pObj.optDouble("last_seen"),
                                            device = pObj.optString("device")
                                        )
                                        currentFriends[idx] = friend.copy(presence = newP)
                                        changed = true
                                    }
                                }
                                if (changed) friendsState.value = currentFriends
                            }

                            
                            val listeningJson = json.optJSONObject("listening")
                            if (listeningJson != null) {
                                val currentMap = friendsListeningState.value.toMutableMap()
                                for (key in listeningJson.keys()) {
                                    val uid = key.toLongOrNull() ?: continue
                                    val lObj = listeningJson.optJSONObject(key) ?: continue
                                    currentMap[uid] = FriendListening(
                                        _id = "",
                                        user_id = uid,
                                        track_id = lObj.optString("track_id").takeIf { it.isNotEmpty() },
                                        started_at = if (lObj.has("started_at")) lObj.optDouble("started_at") else null,
                                        is_playing = lObj.optBoolean("is_playing", false),
                                        position_sec = lObj.optDouble("position_sec", 0.0),
                                        jam_id = lObj.optString("jam_id").takeIf { it.isNotEmpty() },
                                        updated_at = if (lObj.has("updated_at")) lObj.optDouble("updated_at") else null
                                    )
                                }
                                friendsListeningState.value = currentMap
                            }
                        } else if ((type == "listening_update" || type == "friend_listening_update") && json.has("user_id")) {
                            val userId = json.optLong("user_id")
                            val currentMap = friendsListeningState.value.toMutableMap()
                            val existing = currentMap[userId]

                            val trackId = if (json.has("track_id")) {
                                if (json.isNull("track_id")) null else json.optString("track_id").takeIf { it.isNotEmpty() }
                            } else existing?.track_id

                            val jamId = if (json.has("jam_id")) {
                                if (json.isNull("jam_id")) null else json.optString("jam_id").takeIf { it.isNotEmpty() }
                            } else existing?.jam_id

                            val startedAt = if (json.has("started_at")) {
                                if (json.isNull("started_at")) null else json.optDouble("started_at")
                            } else existing?.started_at

                            val updatedAt = if (json.has("updated_at")) {
                                if (json.isNull("updated_at")) null else json.optDouble("updated_at")
                            } else existing?.updated_at

                            val newListening = FriendListening(
                                _id = json.optString("_id", existing?._id ?: ""),
                                user_id = userId,
                                track_id = trackId,
                                started_at = startedAt,
                                is_playing = if (json.has("is_playing")) json.optBoolean("is_playing") else existing?.is_playing ?: false,
                                position_sec = if (json.has("position_sec")) json.optDouble("position_sec") else existing?.position_sec ?: 0.0,
                                jam_id = jamId,
                                updated_at = updatedAt
                            )
                            currentMap[userId] = newListening
                            friendsListeningState.value = currentMap
                            
                            
                            val currentFriends = friendsState.value.toMutableList()
                            val friendIndex = currentFriends.indexOfFirst { it._id == userId }
                            if (friendIndex != -1) {
                                val friend = currentFriends[friendIndex]
                                val updatedPresence = (friend.presence ?: FriendPresence(true, 0.0, null)).copy(
                                    online = true,
                                    last_seen = updatedAt ?: (System.currentTimeMillis() / 1000.0)
                                )
                                currentFriends[friendIndex] = friend.copy(presence = updatedPresence)
                                friendsState.value = currentFriends
                            }
                        } else if ((type == "presence_update" || type == "friend_presence_update") && json.has("user_id")) {
                            val userId = json.optLong("user_id")
                            val currentFriends = friendsState.value.toMutableList()
                            val index = currentFriends.indexOfFirst { it._id == userId }
                            if (index != -1) {
                                val friend = currentFriends[index]
                                val existingPresence = friend.presence

                                val device = if (json.has("device")) {
                                    if (json.isNull("device")) null else json.optString("device").takeIf { it.isNotEmpty() }
                                } else existingPresence?.device

                                val newPresence = FriendPresence(
                                    online = if (json.has("online")) json.optBoolean("online") else existingPresence?.online ?: true,
                                    last_seen = if (json.has("last_seen")) json.optDouble("last_seen") else existingPresence?.last_seen ?: (System.currentTimeMillis() / 1000.0),
                                    device = device
                                )
                                currentFriends[index] = friend.copy(presence = newPresence)
                                friendsState.value = currentFriends
                            }
                        } else if (type == "new_friend_request") {
                            val userObj = json.optJSONObject("user")
                            if (userObj != null) {
                                val newReq = FriendRequest(
                                    user_id = userObj.optLong("user_id"),
                                    first_name = userObj.optString("first_name"),
                                    username = userObj.optString("username"),
                                    profile_url = userObj.optString("profile_url"),
                                    request_id = "", 
                                    created_at = System.currentTimeMillis() / 1000.0
                                )
                                friendRequestsState.value = (friendRequestsState.value + newReq).distinctBy { it.user_id }
                            }
                        } else if (type == "friend_accepted") {
                            val userObj = json.optJSONObject("user")
                            if (userObj != null) {
                                val userId = userObj.optLong("user_id")
                                
                                friendRequestsState.value = friendRequestsState.value.filter { it.user_id != userId }
                                
                                val newFriend = Friend(
                                    _id = userId,
                                    first_name = userObj.optString("first_name"),
                                    photo_url = userObj.optString("profile_url"),
                                    profile_url = userObj.optString("profile_url"),
                                    settings = null,
                                    presence = FriendPresence(online = true, last_seen = System.currentTimeMillis() / 1000.0)
                                )
                                friendsState.value = (friendsState.value + newFriend).distinctBy { it._id }
                            }
                        } else if (type == "friend_removed") {
                            val userId = json.optLong("user_id")
                            friendsState.value = friendsState.value.filter { it._id != userId }
                            val currentMap = friendsListeningState.value.toMutableMap()
                            currentMap.remove(userId)
                            friendsListeningState.value = currentMap
                        } else if (type == "friends_update") {
                            val newFriendsResult = runCatching { fetchFriends(savedUrl, token) }.getOrNull()
                            if (newFriendsResult != null) friendsState.value = newFriendsResult

                            val newListeningResult = runCatching { fetchFriendsListening(savedUrl, token) }.getOrNull()
                            if (newListeningResult != null) {
                                friendsListeningState.value = mapListening(newListeningResult)
                            }
                        }
                    }
                }
            } else {
                PresenceWebSocketManager.disconnect()
                DataCache.clearAuthenticatedUserData(context)
                userPlaylistsState.value = emptyList()
                userAlbumsState.value = emptyList()
                friendSettingsState.value = DataCache.getFriendSettings(context)
                friendsState.value = emptyList()
                friendsListeningState.value = emptyMap()
                friendRequestsState.value = emptyList()
            }
        }
    }

    val rawStartupLoading by remember {
        androidx.compose.runtime.derivedStateOf {
            isLoadingPlaylistsState.value || isLoadingLatestState.value
        }
    }
    val startupLoadingStable = remember { mutableStateOf(rawStartupLoading) }
    LaunchedEffect(rawStartupLoading) {
        if (rawStartupLoading) {
            startupLoadingStable.value = true
        } else {
            delay(180L)
            if (!isLoadingPlaylistsState.value && !isLoadingLatestState.value) {
                startupLoadingStable.value = false
            }
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = currentScreen.value,
            transitionSpec = {
                val screenDepth = mapOf(
                    "home" to 0,
                    "api" to 1,
                    "login" to 1,
                    "profile" to 1,
                    "settings" to 1,
                    "latest_songs" to 1,
                    "random_mix" to 1,
                    "search_results" to 1,
                    "downloads" to 1,
                    "all_playlists" to 1,
                    "your_albums" to 1,
                    "your_library" to 1,
                    "jam_session" to 1,
                    "yt_quick_picks" to 1,
                    "audio_settings" to 2,
                    "appearance_settings" to 2,
                    "developer_settings" to 2,
                    "playlist_detail" to 2,
                    "album_detail" to 2,
                    "artist_detail" to 2
                )
                fun directionalTransform(forward: Boolean, durationMs: Int = 280): ContentTransform {
                    val enterFrom = if (forward) 1 else -1
                    val exitTo = if (forward) -1 else 1
                    return (slideInHorizontally(
                        animationSpec = tween(durationMs),
                        initialOffsetX = { fullWidth -> enterFrom * (fullWidth / 3) }
                    ) + fadeIn(animationSpec = tween(durationMs))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(durationMs),
                            targetOffsetX = { fullWidth -> exitTo * (fullWidth / 6) }
                        ) + fadeOut(animationSpec = tween(durationMs)))
                }

                fun artistTransform(isEnteringArtist: Boolean): ContentTransform {
                    val enter = if (isEnteringArtist) {
                        fadeIn(animationSpec = tween(170)) + slideInHorizontally(
                            animationSpec = tween(170),
                            initialOffsetX = { fullWidth -> fullWidth / 6 }
                        )
                    } else {
                        fadeIn(animationSpec = tween(160))
                    }
                    val exit = fadeOut(animationSpec = tween(if (isEnteringArtist) 120 else 70))
                    return enter togetherWith exit
                }

                val initialDepth = screenDepth[initialState]
                val targetDepth = screenDepth[targetState]

                when {
                    initialState == "artist_detail" && targetState != "artist_detail" ->
                        artistTransform(isEnteringArtist = false)
                    initialState != "artist_detail" && targetState == "artist_detail" ->
                        artistTransform(isEnteringArtist = true)
                    initialDepth != null && targetDepth != null && initialDepth != targetDepth ->
                        directionalTransform(forward = targetDepth > initialDepth, durationMs = 240)
                    initialDepth == null && targetDepth != null ->
                        directionalTransform(forward = true, durationMs = 220)
                    initialDepth != null && targetDepth == null ->
                        directionalTransform(forward = false, durationMs = 220)
                    else -> {
                        fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(160))
                    }
                }
            },
            label = "ScreenTransition"
        ) { screen ->
            screenStateHolder.SaveableStateProvider(screen) {
                val isHomeFullScreen = remember { mutableStateOf(false) }

                Scaffold(
                topBar = {
                    when (screen) {
                        "launch_picker" -> {}
                        "home" -> {
                            if (!isHomeFullScreen.value) {
                                TopBar(
                                    onApiClick = { currentScreen.value = "api" },
                                    onLoginClick = { currentScreen.value = "login" },
                                    onLogoutClick = { performLogout() },
                                    onSettingsClick = { currentScreen.value = "settings" },
                                    onAppInfoClick = { currentScreen.value = "about" },
                                    onAppearanceClick = { currentScreen.value = "appearance_settings" },
                                    onProfileClick = { currentScreen.value = "profile" },
                                    onAudioClick = { currentScreen.value = "audio_settings" },
                                    isStartupLoading = startupLoadingStable.value
                                )
                            }
                        }
                        "api" -> ApiTopBar(
                            onBack = { navigateBack() },
                            showBack = apiUrlState.value.isNotBlank()
                        )
                        "login" -> {} 
                        "settings" -> SettingsTopBar(onBack = { navigateBack() })
                        "about" -> SettingsTopBar(onBack = { navigateBack() }, title = "About")
                        "appearance_settings" -> AppearanceSettingsTopBar(onBack = { navigateBack() })
                        "audio_settings" -> AudioSettingsTopBar(onBack = { navigateBack() })
                        "developer_settings" -> DeveloperSettingsTopBar(onBack = { navigateBack() })
                        "playlist_detail" -> {}
                        "album_detail" -> {}
                        else -> {}
                    }
                },
                containerColor = Color.Transparent
                ) { padding ->
                    when (screen) {
                    "launch_picker" -> {
                        SourcePickerScreen(
                            onApiClick = {
                                launchPrefs.edit().putBoolean("has_seen_source_picker", true).apply()
                                DataCache.setProvider(context, "streamx")
                                currentScreen.value = "api"
                            },
                            onYouTubeClick = {
                                launchPrefs.edit().putBoolean("has_seen_source_picker", true).apply()
                                DataCache.setProvider(context, "youtube")
                                currentScreen.value = "home"
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        )
                    }
                    "home" -> {
                        if (currentHomeProvider == "youtube") {
                            YouTubeHomeScreen(
                                onSongClick = { songs, index ->
                                    val token = AuthPreferences.getEffectiveToken(context)
                                    playerManager.setQueueFromLatest(songs, index, apiUrlState.value, token)
                                },
                                onPlaylistClick = { playlist ->
                                    selectedPlaylistState.value = playlist
                                    currentScreen.value = "playlist_detail"
                                },
                                onSearchClick = { query ->
                                    if (query.isNotBlank()) {
                                        searchQueryState.value = query
                                        currentScreen.value = "search_results"
                                    }
                                },
                                onAlbumClick = { id ->
                                    selectedAlbumIdState.value = id
                                    currentScreen.value = "album_detail"
                                },
                                  onArtistClick = { name, id ->
                                      selectedArtistNameState.value = name
                                      selectedArtistIdState.value = id
                                      artistHistoryStack.value = emptyList()
                                      currentScreen.value = "artist_detail"
                                  },
                                  onQuickPicksClick = { songs, title ->
                                    ytQuickPicksSongsState.value = songs
                                    ytQuickPicksTitleState.value = title
                                    currentScreen.value = "yt_quick_picks"
                                },
                                onSectionViewAllClick = { title, items ->
                                    ytSectionTitleState.value = title
                                    ytSectionItemsState.value = items
                                    currentScreen.value = "yt_section_view_all"
                                },
                                onDownloadsClick = {
                                    currentScreen.value = "downloads"
                                },
                                onStartJamClick = { showStartJamBottomSheet.value = true },
                                onJoinJamClick = { showJoinJamBottomSheet.value = true },
                                activeJamId = jamIdState.value,
                                onActiveJamClick = {
                                    currentScreen.value = "jam_session"
                                },
                                onLeaveJamClick = {
                                    val jamId = jamIdState.value
                                    if (jamId != null) {
                                        scope.launch {
                                            val token = userState.value?.token
                                            leaveJam(apiUrlState.value, jamId, context, token)
                                            JamWebSocketManager.disconnect()
                                            playerManager.disableJamSync()
                                            jamIdState.value = null
                                            JamPreferences.clearStoredJamId(context)
                                        }
                                    }
                                },
                                onJamSongsChanged = { songs ->
                                    youtubeJamSongsState.value = songs
                                },
                                modifier = Modifier.fillMaxSize().padding(padding),
                                isPlayerVisible = playerManager.currentSong.value != null,
                                onFullScreenChange = { isHomeFullScreen.value = it }
                            )
                        } else if (currentHomeProvider == "soundcloud") {
                            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                                Text("SoundCloud coming soon...", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                            }
                        } else {
                            val showUpdatedPlaylistsSection = DataCache.isShowUpdatedPlaylistsSectionEnabled(context)
                            val showLatestSongsSection = DataCache.isShowLatestSongsSectionEnabled(context)
                            val showRandomMixSection = DataCache.isShowRandomMixSectionEnabled(context)
                            val showPlaylistsSection = DataCache.isShowPlaylistsSectionEnabled(context)
                            val showYourAlbumsSection = DataCache.isShowYourAlbumsSectionEnabled(context)
                            val showFriendsSection = DataCache.isShowFriendsSectionEnabled(context)
                            val focusManager = LocalFocusManager.current
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(horizontal = 16.dp)
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            focusManager.clearFocus(force = true)
                                        }
                                    }
                            ) {
                                item { Spacer(modifier = Modifier.height(16.dp)) }
                                item { 
                                    SearchBar(
                                        onSearch = { query ->
                                            if (query.isNotBlank()) {
                                                searchQueryState.value = query
                                                currentScreen.value = "search_results"
                                            }
                                        }
                                    )
                                }
                                item { Spacer(modifier = Modifier.height(24.dp)) }
                                if (showUpdatedPlaylistsSection) {
                                    item { 
                                        if (isLoadingPlaylistsState.value || playlistsState.value.isEmpty()) {
                                            UpdatedPlaylistsLoadingSkeleton()
                                        } else {
                                            
                                            val filteredPlaylists = playlistsState.value.filter { it.id != "me:top-played" }
                                            UpdatedPlaylists(
                                                playlists = filteredPlaylists,
                                                onPlaylistClick = { playlist ->
                                                    selectedPlaylistState.value = playlist
                                                    currentScreen.value = "playlist_detail"
                                                }
                                            )
                                        }
                                    }
                                    item { Spacer(modifier = Modifier.height(24.dp)) }
                                }
                                if (showLatestSongsSection) {
                                    item { 
                                        if (isLoadingLatestState.value) {
                                            LatestSongsLoadingSkeleton()
                                        } else {
                                            LatestSongs(
                                                songs = latestSongsState.value,
                                                onSongClick = { song ->
                                                    val index = latestSongsState.value.indexOf(song)
                                                    if (index != -1) {
                                                        val token = AuthPreferences.getEffectiveToken(context)
                                                        playerManager.setQueueFromLatest(latestSongsState.value, index, apiUrlState.value, token)
                                                    }
                                                },
                                                onViewAllClick = {
                                                    currentScreen.value = "latest_songs"
                                                },
                                                onAlbumClick = { id ->
                                                    selectedAlbumIdState.value = id
                                                    currentScreen.value = "album_detail"
                                                },
                                                onArtistClick = { artistName ->
                                                    selectedArtistNameState.value = artistName
                                                    selectedArtistIdState.value = null
                                                    artistHistoryStack.value = emptyList()
                                                    currentScreen.value = "artist_detail"
                                                }
                                            )
                                        }
                                    }
                                    item { Spacer(modifier = Modifier.height(24.dp)) }
                                }
                                if (showRandomMixSection) {
                                    item { 
                                        if (isLoadingRandomMixState.value || (randomMixSongsState.value.isEmpty() && apiUrlState.value.isNotBlank())) {
                                            RandomMixLoadingSkeleton()
                                        } else {
                                            RandomMix(
                                                songs = randomMixSongsState.value,
                                                onSongClick = { song ->
                                                    val index = randomMixSongsState.value.indexOf(song)
                                                    if (index != -1) {
                                                        val token = AuthPreferences.getEffectiveToken(context)
                                                        playerManager.setQueueFromLatest(randomMixSongsState.value, index, apiUrlState.value, token)
                                                    }
                                                },
                                                onViewAllClick = {
                                                    currentScreen.value = "random_mix"
                                                },
                                                onRefreshClick = {
                                                    scope.launch {
                                                        isLoadingRandomMixState.value = true
                                                        val token = AuthPreferences.getEffectiveToken(context)
                                                        val fetchedRandom = runCatching { fetchRandomMix(apiUrlState.value, limit = 100, token = token) }.getOrNull().orEmpty()
                                                        if (fetchedRandom.isNotEmpty()) {
                                                            val frozen = freezeList(fetchedRandom)
                                                            randomMixSongsState.value = frozen
                                                            DataCache.saveRandomMix(context, frozen)
                                                        }
                                                        isLoadingRandomMixState.value = false
                                                    }
                                                },
                                                onAlbumClick = { id ->
                                                    selectedAlbumIdState.value = id
                                                    currentScreen.value = "album_detail"
                                                }
                                        )
                                        }
                                    }
                                    item { Spacer(modifier = Modifier.height(24.dp)) }
                                }
                                if (showPlaylistsSection) {
                                    item { 
                                        val topPlayedPlaylist = playlistsState.value.find { it.id == "me:top-played" }
                                        HomePlaylistsSection(
                                            userPlaylists = userPlaylistsState.value,
                                            showTopPlayed = userState.value != null,
                                            topPlayedCoverUrl = topPlayedPlaylist?.thumbnailUrl,
                                            onCreateClick = { showCreatePlaylistDialog.value = true },
                                            onFavoritesClick = {
                                                selectedPlaylistState.value = Playlist(
                                                    id = "favorites",
                                                    title = "Favorites",
                                                    color = Color(0xFFF13950)
                                                )
                                                currentScreen.value = "playlist_detail"
                                            },
                                            onDownloadsClick = {
                                                currentScreen.value = "downloads"
                                            },
                                            onLibraryClick = {
                                                currentScreen.value = "your_library"
                                            },
                                            onTopPlayedClick = {
                                                selectedPlaylistState.value = topPlayedPlaylist ?: Playlist(
                                                    id = "me:top-played",
                                                    title = "Top Played",
                                                    subtitle = "Playlist",
                                                    color = Color(0xFF5E5CE6),
                                                    thumbnailUrl = "",
                                                    endpoint = "/me/top-played",
                                                    kind = "me_top_played",
                                                    requiresAuth = true
                                                )
                                                currentScreen.value = "playlist_detail"
                                            },
                                            onPlaylistClick = { playlist ->
                                                selectedPlaylistState.value = playlist
                                                currentScreen.value = "playlist_detail"
                                            },
                                            onViewAllClick = {
                                                currentScreen.value = "all_playlists"
                                            },
                                            onShareClick = { playlist ->
                                                val shareUrl = buildSharedPlaylistWebLink(playlist.id, apiUrlState.value)
                                                val sendIntent: Intent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, "Check out this playlist: $shareUrl")
                                                    type = "text/plain"
                                                }
                                                val shareIntent = Intent.createChooser(sendIntent, null)
                                                context.startActivity(shareIntent)
                                            },
                                            onRenameClick = { playlist ->
                                                playlistToRename.value = playlist
                                                showRenamePlaylistDialog.value = true
                                            },
                                            onDeleteClick = { playlist ->
                                                scope.launch {
                                                    val token = AuthPreferences.getEffectiveToken(context)
                                                    val success = deletePlaylist(apiUrlState.value, playlist.id, context, token)
                                                    if (success) {
                                                        
                                                        val fetchedUserPlaylists = runCatching {
                                                            fetchUserPlaylists(apiUrlState.value, context = context, token = token)
                                                        }.getOrNull().orEmpty()
                                                        userPlaylistsState.value = fetchedUserPlaylists
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    item { Spacer(modifier = Modifier.height(24.dp)) }
                                }
                                if (showYourAlbumsSection) {
                                    item {
                                        if (userAlbumsState.value.isNotEmpty()) {
                                            HomeAlbumsSection(
                                                albums = userAlbumsState.value,
                                                onAlbumClick = { id ->
                                                    selectedAlbumIdState.value = id
                                                    currentScreen.value = "album_detail"
                                                },
                                                onViewAllClick = { currentScreen.value = "your_albums" }
                                            )
                                        }
                                    }
                                    item { Spacer(modifier = Modifier.height(24.dp)) }
                                }
                                if (showFriendsSection) {
                                    item {
                                        FriendsSection(
                                            friends = friendsState.value,
                                            listeningData = friendsListeningState.value,
                                            requests = friendRequestsState.value,
                                            isLoading = isLoadingFriends.value,
                                            onAddFriendClick = { showAddFriendDialog.value = true },
                                            onSettingsClick = { showFriendsSettingsDialog.value = true },
                                            onRefreshClick = {
                                                scope.launch {
                                                    val token = AuthPreferences.getEffectiveToken(context)
                                                    if (token != null && apiUrlState.value.isNotBlank()) {
                                                        isLoadingFriends.value = true
                                                        friendRequestsState.value = runCatching { fetchFriendRequests(apiUrlState.value, token) }.getOrNull().orEmpty()
                                                        friendsState.value = runCatching { fetchFriends(apiUrlState.value, token) }.getOrNull().orEmpty()
                                                        val listeningResult = runCatching { fetchFriendsListening(apiUrlState.value, token) }.getOrNull().orEmpty()
                                                        friendsListeningState.value = mapListening(listeningResult)
                                                        isLoadingFriends.value = false
                                                    }
                                                }
                                            },
                                            onFriendClick = { friend, listening ->
                                                friendListeningFriend.value = friend
                                                friendListeningSong.value = null
                                                friendListeningJam.value = false
                                                if (listening != null && listening.is_playing) {
                                                    if (listening.jam_id != null) {
                                                        friendListeningJam.value = true
                                                        showFriendListeningDialog.value = true
                                                    } else if (listening.track_id != null) {
                                                        friendListeningLoading.value = true
                                                        showFriendListeningDialog.value = true
                                                        scope.launch {
                                                            val song = fetchSong(apiUrlState.value, listening.track_id, context)
                                                            friendListeningSong.value = song
                                                            friendListeningLoading.value = false
                                                        }
                                                    }
                                                } else {
                                                    showFriendListeningDialog.value = true
                                                }
                                            },
                                            onFriendLongClick = { friend ->
                                                friendToRemove.value = friend
                                            },
                                            onAcceptRequestClick = { userId ->
                                                scope.launch {
                                                    val token = AuthPreferences.getEffectiveToken(context)
                                                    if (token != null) {
                                                        val success = runCatching { acceptFriendRequest(apiUrlState.value, token, userId) }.getOrNull()?.isSuccess == true
                                                        if (success) {
                                                            if (friendsState.value.isEmpty()) isLoadingFriends.value = true
                                                            friendsState.value = runCatching { fetchFriends(apiUrlState.value, token) }.getOrNull().orEmpty()
                                                            friendRequestsState.value = runCatching { fetchFriendRequests(apiUrlState.value, token) }.getOrNull().orEmpty()
                                                            isLoadingFriends.value = false
                                                        } else {
                                                            android.widget.Toast.makeText(context, "Failed to accept request", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    item { Spacer(modifier = Modifier.height(24.dp)) }
                                }
                                item {
                                                JamSessionSection(
                                                    onStartJamClick = { showStartJamBottomSheet.value = true },
                                                    onJoinJamClick = { showJoinJamBottomSheet.value = true },
                                                    activeJamId = jamIdState.value,
                                                    onActiveJamClick = {
                                                        currentScreen.value = "jam_session"
                                                    },
                                                    onLeaveJamClick = {
                                                        val jamId = jamIdState.value
                                                        if (jamId != null) {
                                                            scope.launch {
                                                                val token = userState.value?.token
                                                                leaveJam(apiUrlState.value, jamId, context, token)
                                                                JamWebSocketManager.disconnect()
                                                                playerManager.disableJamSync()
                                                                jamIdState.value = null
                                                                JamPreferences.clearStoredJamId(context)
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                            item { Spacer(modifier = Modifier.height(if (playerManager.currentSong.value != null) 100.dp else 24.dp)) }
                                        }
                                    }
                        }
                    "api" -> {
                        ApiScreen(
                            currentApiUrl = apiUrlState.value,
                            isLoadingLatest = isLoadingLatestState.value,
                            isPlayerVisible = playerManager.currentSong.value != null,
                            onSave = { entered ->
                                val normalized = normalizeApiInput(entered)
                                ApiPreferences.setApiUrl(context, normalized)
                                DataCache.clearCache(context)
                                apiUrlState.value = normalized
                                currentScreen.value = "home"
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(horizontal = 16.dp)
                        )
                    }
                    "profile" -> {
                        ProfileScreen(
                            onBack = { currentScreen.value = "home" }
                        )
                    }
                    "latest_songs" -> {
                        LatestSongsScreen(
                            apiUrl = apiUrlState.value,
                            onBack = { currentScreen.value = "home" },
                            onSongClick = { songs, index ->
                                val token = AuthPreferences.getEffectiveToken(context)
                                playerManager.setQueueFromLatest(songs, index, apiUrlState.value, token)
                            },
                            onAlbumClick = { id ->
                                selectedAlbumIdState.value = id
                                currentScreen.value = "album_detail"
                            },
                            onArtistClick = { artistName ->
                                selectedArtistNameState.value = artistName
                                selectedArtistIdState.value = null
                                artistHistoryStack.value = emptyList()
                                currentScreen.value = "artist_detail"
                            },
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "random_mix" -> {
                        val token = remember(userState.value) { AuthPreferences.getEffectiveToken(context) }
                        RandomMixScreen(
                            songs = randomMixSongsState.value,
                            isLoading = isLoadingRandomMixState.value,
                            onRefresh = {
                                scope.launch {
                                    isLoadingRandomMixState.value = true
                                    val fetchedRandom = runCatching { fetchRandomMix(apiUrlState.value, limit = 100, token = token) }.getOrNull().orEmpty()
                                    if (fetchedRandom.isNotEmpty()) {
                                        randomMixSongsState.value = fetchedRandom
                                        DataCache.saveRandomMix(context, fetchedRandom)
                                    }
                                    isLoadingRandomMixState.value = false
                                }
                            },
                            onBack = { currentScreen.value = "home" },
                            onSongClick = { songs, index ->
                                playerManager.setQueueFromLatest(songs, index, apiUrlState.value, token)
                            },
                            onAlbumClick = { id ->
                                selectedAlbumIdState.value = id
                                currentScreen.value = "album_detail"
                            },
                            onArtistClick = { artistName ->
                                selectedArtistNameState.value = artistName
                                selectedArtistIdState.value = null
                                artistHistoryStack.value = emptyList()
                                currentScreen.value = "artist_detail"
                            },
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "search_results" -> {
                        SearchResultsScreen(
                            query = searchQueryState.value,
                            apiUrl = apiUrlState.value,
                            onBack = { currentScreen.value = "home" },
                            onSearch = { newQuery ->
                                searchQueryState.value = newQuery
                            },
                            onSongClick = { songs, index ->
                                val token = AuthPreferences.getEffectiveToken(context)
                                val song = songs.getOrNull(index)
                                if (DataCache.getProvider(context) == "youtube" && song?.id?.startsWith("yt_") == true) {
                                    scope.launch {
                                        val (watchQueue, watchQueueIndex) = getYouTubeWatchQueue(song, context)
                                        playerManager.setQueueFromLatest(watchQueue, watchQueueIndex, apiUrlState.value, token)
                                    }
                                } else {
                                    playerManager.setQueueFromLatest(songs, index, apiUrlState.value, token)
                                }
                            },
                            isPlayerVisible = playerManager.currentSong.value != null,
                            onAlbumClick = { id: String ->
                                selectedAlbumIdState.value = id
                                currentScreen.value = "album_detail"
                            },
                            onArtistClick = { artistName, artistId ->
                                selectedArtistNameState.value = artistName
                                selectedArtistIdState.value = artistId
                                artistHistoryStack.value = emptyList()
                                currentScreen.value = "artist_detail"
                            }
                        )
                    }
                    "login" -> {
                        LoginScreen(
                            onLoginSuccess = {
                                userState.value = it
                                currentScreen.value = "home"
                            },
                            onBack = {
                                currentScreen.value = "home"
                            }
                        )
                    }
                    "settings" -> {
                        SettingsScreen(
                            isAmoledBlack = isAmoledBlack,
                            onBack = { currentScreen.value = "home" },
                            onAudioSettingsClick = { currentScreen.value = "audio_settings" },
                            onDeveloperSettingsClick = { currentScreen.value = "developer_settings" },
                            onAboutClick = { currentScreen.value = "about" },
                            modifier = Modifier.padding(padding),
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "about" -> {
                        AboutScreen(
                            modifier = Modifier.padding(padding),
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "audio_settings" -> {
                        AudioSettingsScreen(
                            onBack = { currentScreen.value = "home" },
                            modifier = Modifier.padding(padding),
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "appearance_settings" -> {
                        AppearanceSettingsScreen(
                            isDarkTheme = isDarkTheme,
                            isDynamicMaterial = isDynamicMaterial,
                            isCustomPicker = isCustomPicker,
                            isAmoledBlack = isAmoledBlack,
                            modifier = Modifier.padding(padding),
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "developer_settings" -> {
                        DeveloperSettingsScreen(
                            onBack = { currentScreen.value = "settings" },
                            modifier = Modifier.padding(padding),
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "downloads" -> {
                        DownloadsScreen(
                            onBack = { currentScreen.value = "home" },
                            onSongClick = { songs, index ->
                                playerManager.setQueueFromLatest(songs, index, apiUrlState.value, null)
                            },
                            isPlayerVisible = playerManager.currentSong.value != null,
                            onAlbumClick = { id ->
                                selectedAlbumIdState.value = id
                                currentScreen.value = "album_detail"
                            },
                            onArtistClick = { artistName ->
                                selectedArtistNameState.value = artistName
                                selectedArtistIdState.value = null
                                artistHistoryStack.value = emptyList()
                                currentScreen.value = "artist_detail"
                            }
                        )
                    }
                    "yt_quick_picks" -> {
                        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), 
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { navigateBack() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack, 
                                        contentDescription = "Back", 
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                Text(
                                    text = ytQuickPicksTitleState.value, 
                                    fontSize = 22.sp, 
                                    fontWeight = FontWeight.Bold, 
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(), 
                                contentPadding = PaddingValues(bottom = if (playerManager.currentSong.value != null) 100.dp else 24.dp)
                            ) {
                                itemsIndexed(ytQuickPicksSongsState.value) { index, song ->
                                    AppleMusicSongRow(
                                        song = song, 
                                        showFavoriteStar = false,
                                        onClick = { 
                                            val token = AuthPreferences.getEffectiveToken(context)
                                            playerManager.setQueueFromLatest(ytQuickPicksSongsState.value, index, apiUrlState.value, token)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    "yt_section_view_all" -> {
                        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { navigateBack() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                Text(
                                    text = ytSectionTitleState.value,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val isFromYourLibrarySection = ytSectionTitleState.value.equals("From your library", ignoreCase = true)
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = if (playerManager.currentSong.value != null) 100.dp else 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (isFromYourLibrarySection) {
                                    val gridItems = ytSectionItemsState.value.filterNot { it is SongItem }
                                    items(gridItems.chunked(2)) { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            rowItems.forEach { item ->
                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clickable {
                                                            when (item) {
                                                                is PlaylistItem -> item.toPlaylist()?.let { playlist ->
                                                                    selectedPlaylistState.value = playlist
                                                                    currentScreen.value = "playlist_detail"
                                                                }
                                                                is AlbumItem -> item.toPlaylist()?.let { albumPlaylist ->
                                                                    selectedPlaylistState.value = albumPlaylist
                                                                    currentScreen.value = "playlist_detail"
                                                                }
                                                                is ArtistItem -> {
                                                                    selectedArtistNameState.value = item.title
                                                                    selectedArtistIdState.value = item.id
                                                                    artistHistoryStack.value = emptyList()
                                                                    currentScreen.value = "artist_detail"
                                                                }
                                                                else -> Unit
                                                            }
                                                        }
                                                ) {
                                                    val shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(12.dp)
                                                    CoverArt(
                                                        coverUrl = upscaleYoutubeThumbnail(item.thumbnail, 320),
                                                        fallbackColor = Color.DarkGray,
                                                        requestSizePx = 320,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .aspectRatio(1f)
                                                            .clip(shape)
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = item.title,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onBackground,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    val subtitle = when (item) {
                                                        is PlaylistItem -> item.author?.name ?: "Playlist"
                                                        is AlbumItem -> item.artists?.joinToString(", ") { it.name } ?: "Album"
                                                        is ArtistItem -> "Artist"
                                                        else -> ""
                                                    }
                                                    Text(
                                                        text = subtitle,
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            if (rowItems.size == 1) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                } else {
                                    itemsIndexed(
                                        items = ytSectionItemsState.value,
                                        key = { index, item -> "${index}_${item.title}_${item.thumbnail}" }
                                    ) { _, item ->
                                        when (item) {
                                            is SongItem -> item.toSong()?.let { song ->
                                                AppleMusicSongRow(
                                                    song = song,
                                                    showFavoriteStar = false,
                                                    onClick = {
                                                        val songs = ytSectionItemsState.value.mapNotNull { (it as? SongItem)?.toSong() }
                                                        val index = songs.indexOfFirst { it.id == song.id }
                                                        if (index != -1) {
                                                            val token = AuthPreferences.getEffectiveToken(context)
                                                            playerManager.setQueueFromLatest(songs, index, apiUrlState.value, token)
                                                        }
                                                    }
                                                )
                                            }
                                            is PlaylistItem -> {
                                                SongRowCard(
                                                    song = Song(
                                                        id = item.id ?: item.title,
                                                        title = item.title,
                                                        artist = item.author?.name ?: "Playlist",
                                                        coverUrl = item.thumbnail,
                                                        color = Color.Gray
                                                    ),
                                                    onClick = {
                                                        item.toPlaylist()?.let { playlist ->
                                                            selectedPlaylistState.value = playlist
                                                            currentScreen.value = "playlist_detail"
                                                        }
                                                    }
                                                )
                                            }
                                            is AlbumItem -> {
                                                val subtitle = item.artists?.joinToString(", ") { it.name }.orEmpty().ifBlank { "Album" }
                                                SongRowCard(
                                                    song = Song(
                                                        id = item.id ?: item.title,
                                                        title = item.title,
                                                        artist = subtitle,
                                                        coverUrl = item.thumbnail,
                                                        color = Color.Gray
                                                    ),
                                                    onClick = {
                                                        item.toPlaylist()?.let { albumPlaylist ->
                                                            selectedPlaylistState.value = albumPlaylist
                                                            currentScreen.value = "playlist_detail"
                                                        }
                                                    }
                                                )
                                            }
                                            is ArtistItem -> {
                                                SongRowCard(
                                                    song = Song(
                                                        id = item.id ?: item.title,
                                                        title = item.title,
                                                        artist = "Artist",
                                                        coverUrl = item.thumbnail,
                                                        color = Color.Gray
                                                    ),
                                                    onClick = {
                                                        selectedArtistNameState.value = item.title
                                                        selectedArtistIdState.value = item.id
                                                        artistHistoryStack.value = emptyList()
                                                        currentScreen.value = "artist_detail"
                                                    }
                                                )
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "your_library" -> {
                        LibraryScreen(
                            onBack = { navigateBack() },
                            onPlaylistClick = { playlist ->
                                selectedPlaylistState.value = playlist
                                currentScreen.value = "playlist_detail"
                            },
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "playlist_detail" -> {
                        selectedPlaylistState.value?.let { playlist ->
                            PlaylistDetailScreen(
                                playlist = playlist,
                                apiUrl = apiUrlState.value,
                                onBack = {
                                    if (currentScreen.value == "playlist_detail" && selectedPlaylistState.value != null) {
                                        selectedPlaylistState.value = null
                                        navigateBack()
                                    }
                                },
                                onSongClick = { songs, index ->
                                    val token = AuthPreferences.getEffectiveToken(context)
                                    playerManager.setQueueFromLatest(songs, index, apiUrlState.value, token)
                                },
                                isPlayerVisible = playerManager.currentSong.value != null,
                                onShareClick = { p ->
                                    val shareUrl = buildSharedPlaylistWebLink(p.id, apiUrlState.value)
                                    val sendIntent: Intent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Check out this playlist: $shareUrl")
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, null)
                                    context.startActivity(shareIntent)
                                },
                                onRenameClick = { p ->
                                    playlistToRename.value = p
                                    showRenamePlaylistDialog.value = true
                                },
                                onDeleteClick = { p ->
                                    scope.launch {
                                        val token = AuthPreferences.getEffectiveToken(context)
                                        val success = deletePlaylist(apiUrlState.value, p.id, context, token)
                                        if (success) {
                                            
                                            val fetchedUserPlaylists = runCatching { 
                                                fetchUserPlaylists(apiUrlState.value, context = context, token = token) 
                                            }.getOrNull().orEmpty()
                                            userPlaylistsState.value = fetchedUserPlaylists
                                            
                                            selectedPlaylistState.value = null
                                            navigateBack()
                                        }
                                    }
                                },
                                onAlbumClick = { id ->
                                    selectedAlbumIdState.value = id
                                    currentScreen.value = "album_detail"
                                },
                                onArtistClick = { artistName ->
                                    selectedArtistNameState.value = artistName
                                    selectedArtistIdState.value = null
                                    artistHistoryStack.value = emptyList()
                                    currentScreen.value = "artist_detail"
                                }
                            )
                        }
                    }
                    "album_detail" -> {
                        selectedAlbumIdState.value?.let { albumId ->
                            AlbumScreen(
                                albumId = albumId,
                                apiUrl = apiUrlState.value,
                                onBack = {
                                    selectedAlbumIdState.value = null
                                    navigateBack()
                                },
                                onSongClick = { songs, index ->
                                    val token = AuthPreferences.getEffectiveToken(context)
                                    playerManager.setQueueFromLatest(songs, index, apiUrlState.value, token)
                                },
                                isPlayerVisible = playerManager.currentSong.value != null,
                                onAlbumClick = { id ->
                                    selectedAlbumIdState.value = id
                                    currentScreen.value = "album_detail"
                                },
                                onArtistClick = { artistName ->
                                    selectedArtistNameState.value = artistName
                                    selectedArtistIdState.value = null
                                    artistHistoryStack.value = emptyList() 
                                    currentScreen.value = "artist_detail"
                                }
                            )
                        }
                    }
                    "artist_detail" -> {
                        ArtistScreen(
                            artistName = selectedArtistNameState.value,
                            artistId = selectedArtistIdState.value,
                            onBack = {
                                val currentStack = artistHistoryStack.value
                                if (currentStack.isNotEmpty()) {
                                    
                                    val newStack = currentStack.dropLast(1)
                                    artistHistoryStack.value = newStack
                                    if (newStack.isNotEmpty()) {
                                        val prevArtist = newStack.last()
                                        selectedArtistNameState.value = prevArtist.first
                                        selectedArtistIdState.value = prevArtist.second
                                    } else {
                                        selectedArtistIdState.value = null
                                        selectedArtistNameState.value = ""
                                        navigateBack()
                                    }
                                } else {
                                    selectedArtistIdState.value = null
                                    selectedArtistNameState.value = ""
                                    navigateBack()
                                }
                            },
                            onArtistClick = { name, id ->
                                
                                val currentArtist = Pair(selectedArtistNameState.value, selectedArtistIdState.value)
                                artistHistoryStack.value = artistHistoryStack.value + currentArtist
                                
                                selectedArtistNameState.value = name
                                selectedArtistIdState.value = id
                                
                                currentScreen.value = "artist_detail"
                            },
                            onAlbumClick = { id ->
                                selectedAlbumIdState.value = id
                                currentScreen.value = "album_detail"
                            },
                            onPlaylistClick = { playlist ->
                                selectedPlaylistState.value = playlist
                                currentScreen.value = "playlist_detail"
                            },
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "all_playlists" -> {
                        PlaylistsScreen(
                            userPlaylists = userPlaylistsState.value,
                            onBack = { navigateBack() },
                            onPlaylistClick = { playlist ->
                                selectedPlaylistState.value = playlist
                                currentScreen.value = "playlist_detail"
                            },
                            onCreateClick = { showCreatePlaylistDialog.value = true },
                            onFavoritesClick = {
                                selectedPlaylistState.value = Playlist(
                                    id = "favorites",
                                    title = "Favorites",
                                    color = Color(0xFFF13950)
                                )
                                currentScreen.value = "playlist_detail"
                            },
                            isPlayerVisible = playerManager.currentSong.value != null
                        )
                    }
                    "your_albums" -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(bottom = if (playerManager.currentSong.value != null) 100.dp else 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            item { Spacer(modifier = Modifier.height(8.dp)) }
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentScreen.value = "home" },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Your Albums",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                            items(userAlbumsState.value.chunked(2)) { rowAlbums ->
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    rowAlbums.forEach { album ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            AlbumCard(
                                                album = album,
                                                onClick = {
                                                    selectedAlbumIdState.value = album._id
                                                    currentScreen.value = "album_detail"
                                                }
                                            )
                                        }
                                    }
                                    if (rowAlbums.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    "jam_session" -> {
                        jamIdState.value?.let { jamId ->
                            JamScreen(
                                jamId = jamId,
                                apiBaseUrl = apiUrlState.value,
                                onBack = {
                                    currentScreen.value = "home"
                                },
                                onLeave = {
                                    scope.launch {
                                        val token = userState.value?.token
                                        leaveJam(apiUrlState.value, jamId, context, token)
                                        JamWebSocketManager.disconnect()
                                        jamIdState.value = null
                                        JamPreferences.clearStoredJamId(context)
                                        currentScreen.value = "home"
                                        playerManager.disableJamSync()
                                    }
                                },
                                onOpenPlayer = { isPlayerExpanded.value = true },
                                userState = userState.value,
                                isPlaying = playerManager.isPlaying.value,
                                onPlayPause = {
                                    scope.launch {
                                        
                                        
                                        val jamState = JamWebSocketManager.jamState.value
                                        val isHost = jamState?.hostUserId == userState.value?.id
                                        
                                        if (isHost) {
                                            val token = userState.value?.token
                                            if (playerManager.isPlaying.value) {
                                                JamWebSocketManager.sendAction("pause") || jamPause(apiUrlState.value, jamId, context, token)
                                            } else {
                                                JamWebSocketManager.sendAction("play") || jamPlay(apiUrlState.value, jamId, context, token)
                                            }
                                        } else {
                                            
                                            playerManager.toggleLocalPlayPause()
                                        }
                                    }
                                },
                                friends = friendsState.value,
                                onInviteFriend = { friendId ->
                                    val token = userState.value?.token
                                    inviteFriendToJam(apiUrlState.value, jamId, friendId, context, token)
                                }
                            )
                        }
                    }
                    }
                }
            }
        }

        if (showAddFriendDialog.value) {
            AddFriendDialog(
                onDismiss = { showAddFriendDialog.value = false },
                onAdd = { userId ->
                    scope.launch {
                        val token = AuthPreferences.getEffectiveToken(context)
                        val success = runCatching { sendFriendRequest(apiUrlState.value, token, userId) }.getOrNull()?.isSuccess == true
                        if (success) {
                            android.widget.Toast.makeText(context, "Friend request sent", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "Failed to send friend request", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        showAddFriendDialog.value = false
                    }
                }
            )
        }

        friendToRemove.value?.let { friend ->
            RemoveFriendConfirmationDialog(
                friend = friend,
                onDismiss = { friendToRemove.value = null },
                onConfirm = {
                    scope.launch {
                        val token = AuthPreferences.getEffectiveToken(context)
                        if (token != null) {
                            val success = removeFriend(apiUrlState.value, token, friend._id)
                            if (success) {
                                
                                val friendsResult = runCatching { fetchFriends(apiUrlState.value, token) }.getOrNull()
                                if (friendsResult != null) {
                                    friendsState.value = friendsResult
                                }
                                android.widget.Toast.makeText(context, "Removed ${friend.first_name}", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Failed to remove friend", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        friendToRemove.value = null
                    }
                }
            )
        }

        if (showFriendListeningDialog.value && friendListeningFriend.value != null) {
            FriendListeningDialog(
                friend = friendListeningFriend.value!!,
                song = friendListeningSong.value,
                isInJam = friendListeningJam.value,
                isLoading = friendListeningLoading.value,
                apiBaseUrl = apiUrlState.value,
                onDismiss = { showFriendListeningDialog.value = false }
            )
        }

        if (showFriendsSettingsDialog.value) {
            FriendSettingsDialog(
                currentSettings = friendSettingsState.value ?: FriendSettings("friends", true),
                onDismiss = { showFriendsSettingsDialog.value = false },
                onSave = { newSettings ->
                    friendSettingsState.value = newSettings
                    DataCache.setFriendSettings(context, newSettings)
                    if (newSettings.share_listening == "none") {
                        PresenceWebSocketManager.disconnect()
                    } else {
                        val token = AuthPreferences.getEffectiveToken(context)
                        if (token != null) {
                            PresenceWebSocketManager.connect(apiUrlState.value, token)
                        }
                    }
                    
                    scope.launch {
                        val token = AuthPreferences.getEffectiveToken(context)
                        if (token != null) {
                            val success = updateFriendSettings(apiUrlState.value, token, newSettings)
                            if (success) {
                                android.widget.Toast.makeText(context, "Settings updated", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Failed to update settings remotely", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        showFriendsSettingsDialog.value = false
                    }
                }
            )
        }

        
        if (showCreatePlaylistDialog.value) {
            CreatePlaylistDialog(
                onDismiss = { showCreatePlaylistDialog.value = false },
                onCreate = { name ->
                    scope.launch {
                        val token = AuthPreferences.getEffectiveToken(context)
                        val newPlaylist = createPlaylist(apiUrlState.value, name, context, token)
                        if (newPlaylist != null) {
                            
                            val fetchedUserPlaylists = runCatching { 
                                fetchUserPlaylists(apiUrlState.value, context = context, token = token) 
                            }.getOrNull().orEmpty()
                            userPlaylistsState.value = fetchedUserPlaylists
                        }
                        showCreatePlaylistDialog.value = false
                    }
                }
            )
        }
        
        playlistToRename.value?.let { playlist ->
            if (showRenamePlaylistDialog.value) {
                RenamePlaylistDialog(
                    playlist = playlist,
                    onDismiss = { 
                        showRenamePlaylistDialog.value = false
                        playlistToRename.value = null
                    },
                    onRename = { newName ->
                        scope.launch {
                            val token = AuthPreferences.getEffectiveToken(context)
                            val success = renamePlaylist(apiUrlState.value, playlist.id, newName, context, token)
                            if (success) {
                                
                                val fetchedUserPlaylists = runCatching { 
                                    fetchUserPlaylists(apiUrlState.value, context = context, token = token) 
                                }.getOrNull().orEmpty()
                                userPlaylistsState.value = fetchedUserPlaylists
                            }
                            showRenamePlaylistDialog.value = false
                            playlistToRename.value = null
                        }
                    }
                )
            }
        }
        
        
        val sudoSelectedTracks by DataCache.sudoSelectedTrackIds.collectAsState()
        val isSudoMode = DataCache.isSudoModeEnabled(context)
        MiniPlayerHost(
            canShowPlayer = !isPlayerExpanded.value && (!isSudoMode || sudoSelectedTracks.isEmpty()),
            currentScreen = currentScreen.value,
            jamId = jamIdState.value,
            apiBaseUrl = apiUrlState.value,
            userToken = userState.value?.token,
            currentUserId = userState.value?.id,
            context = context,
            playerManager = playerManager,
            onExpandPlayer = {
                swipeExpandProgress.floatValue = 0f
                isPlayerExpanded.value = true
            },
            onStopPlayer = {
                playerManager.stop()
                swipeExpandProgress.floatValue = 0f
                isPlayerExpanded.value = false
            },
            onSwipeProgress = { progress -> swipeExpandProgress.floatValue = progress },
            onJamPlayerClick = {
                if (currentScreen.value == "jam_session") {
                    swipeExpandProgress.floatValue = 0f
                    isPlayerExpanded.value = true
                } else {
                    currentScreen.value = "jam_session"
                }
            }
        )
        
        AnimatedVisibility(
            visible = isSudoMode && sudoSelectedTracks.isNotEmpty() && !isPlayerExpanded.value,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(durationMillis = 300)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(durationMillis = 300)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .height(64.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            DataCache.sudoSelectedTrackIds.value = emptySet()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }

                
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFE24A5A))
                        .clickable {
                            val confirmDelete = android.app.AlertDialog.Builder(context, R.style.Theme_StreamX_Dialog)
                                .setTitle("Delete Tracks")
                                .setMessage("Are you sure you want to delete ${sudoSelectedTracks.size} tracks? This action cannot be undone.")
                                .setPositiveButton("Delete") { _, _ ->
                                    scope.launch {
                                        val token = userState.value?.token
                                        val success = deleteAdminTracks(apiUrlState.value, token, sudoSelectedTracks.toList())
                                        if (success) {
                                            android.widget.Toast.makeText(context, "Tracks deleted", android.widget.Toast.LENGTH_SHORT).show()
                                            DataCache.sudoSelectedTrackIds.value = emptySet()
                                            DataCache.clearCache(context)
                                        } else {
                                            android.widget.Toast.makeText(context, "Failed to delete tracks", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .create()
                            
                            confirmDelete.setOnShowListener {
                                confirmDelete.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(android.graphics.Color.parseColor("#E24A5A"))
                                confirmDelete.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(android.graphics.Color.GRAY)
                            }
                            confirmDelete.show()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Delete (${sudoSelectedTracks.size})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            if (playerManager.currentSong.value != null) {
                                isPlayerExpanded.value = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (playerManager.currentSong.value != null) {
                        PlayerCoverArt(
                            coverUrl = playerManager.currentSong.value?.coverUrl,
                            fallbackColor = playerManager.currentSong.value?.color ?: Color.Gray,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Player",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        val expandedPreviewProgress = if (isPlayerExpanded.value) 1f else swipeExpandProgress.floatValue
        val previewOffsetPx = with(LocalDensity.current) { 520.dp.toPx() }
        val expandedPlayerEnter = if (disableExpandedPlayerAnimation) {
            androidx.compose.animation.EnterTransition.None
        } else {
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 250, easing = LinearOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(durationMillis = 200))
        }
        val expandedPlayerExit = if (disableExpandedPlayerAnimation) {
            androidx.compose.animation.ExitTransition.None
        } else {
            slideOutVertically(
                targetOffsetY = { it / 3 },
                animationSpec = tween(durationMillis = 220)
            ) + fadeOut(animationSpec = tween(durationMillis = 180))
        }

        AnimatedVisibility(
            visible = isPlayerExpanded.value || swipeExpandProgress.floatValue > 0f,
            enter = expandedPlayerEnter,
            exit = expandedPlayerExit
        ) {
            FullPlayerScreen(
                song = playerManager.currentSong.value,
                isPlaying = playerManager.isPlaying.value,
                isLoading = playerManager.isLoading.value,
                onPlayPauseClick = { playerManager.togglePlayPause() },
                onClose = {
                    swipeExpandProgress.floatValue = 0f
                    isPlayerExpanded.value = false
                },
                onLyricsClick = {},
                modifier = Modifier.graphicsLayer {
                    alpha = if (disableExpandedPlayerAnimation || isPlayerExpanded.value) {
                        1f
                    } else {
                        0.35f + (0.65f * expandedPreviewProgress)
                    }
                    translationY = if (isPlayerExpanded.value) 0f else (1f - expandedPreviewProgress) * previewOffsetPx
                    scaleX = if (isPlayerExpanded.value) 1f else (0.94f + (0.06f * expandedPreviewProgress))
                    scaleY = if (isPlayerExpanded.value) 1f else (0.96f + (0.04f * expandedPreviewProgress))
                },
                apiBaseUrl = apiUrlState.value,
                queue = playerManager.queue,
                currentIndex = playerManager.currentIndex,
                onQueueItemClick = { index -> playerManager.skipToQueueItem(index) },
                onAlbumClick = { id ->
                    selectedAlbumIdState.value = id
                    currentScreen.value = "album_detail"
                    isPlayerExpanded.value = false
                },
                onArtistClick = { selectedSong ->
                    selectedSong.primaryArtistName?.let { artistName ->
                        selectedArtistNameState.value = artistName
                        selectedArtistIdState.value = selectedSong.primaryArtistId
                        artistHistoryStack.value = emptyList()
                        currentScreen.value = "artist_detail"
                        isPlayerExpanded.value = false
                    }
                }
            )
        }

        if (showStartJamBottomSheet.value) {
            @OptIn(ExperimentalMaterial3Api::class)
            StartJamBottomSheet(
                onDismissRequest = { showStartJamBottomSheet.value = false },
                songs = if (currentHomeProvider == "youtube") {
                    buildList {
                        playerManager.currentSong.value?.let { currentSong ->
                            val currentSongKey = currentSong.id ?: "${currentSong.title}|${currentSong.artist}"
                            if (none { (it.id ?: "${it.title}|${it.artist}") == currentSongKey }) {
                                add(currentSong)
                            }
                        }
                        youtubeJamSongsState.value.forEach { song ->
                            val songKey = song.id ?: "${song.title}|${song.artist}"
                            if (none { (it.id ?: "${it.title}|${it.artist}") == songKey }) {
                                add(song)
                            }
                        }
                    }
                } else {
                    latestSongsState.value
                },
                onStartJam = { selectedSongId, allowSeek, allowEdit ->
                    scope.launch {
                        val token = userState.value?.token
                        val currentSong = playerManager.currentSong.value
                        val trackId = selectedSongId ?: currentSong?.id ?: return@launch
                        val isStartingCurrentTrack = currentSong?.id == trackId
                        val isPlaying = if (isStartingCurrentTrack) {
                            playerManager.isPlaying.value
                        } else {
                            true
                        }
                        
                        val req = CreateJamRequest(
                            trackId = trackId,
                            positionSec = 0.0,
                            isPlaying = isPlaying,
                            queue = emptyList(),
                            settings = JamSettings(allowSeek, allowEdit)
                        )
                        val resp = createJam(apiUrlState.value, req, context, token)
                        if (resp.ok && resp.jam != null) {
                            if (isStartingCurrentTrack) {
                                playerManager.seekTo(0L)
                            }
                            jamIdState.value = resp.jam.id
                            JamPreferences.setStoredJamId(context, resp.jam.id)
                            JamWebSocketManager.connect(apiUrlState.value, resp.jam.id, token ?: "", resp.jam)
                            playerManager.enableJamSync(apiUrlState.value, token ?: "")
                            currentScreen.value = "jam_session"
                        }
                    }
                    showStartJamBottomSheet.value = false
                }
            )
        }

        if (showJoinJamBottomSheet.value) {
            @OptIn(ExperimentalMaterial3Api::class)
            JoinJamBottomSheet(
                onDismissRequest = { showJoinJamBottomSheet.value = false },
                onJoinJam = { jamId ->
                    scope.launch {
                        val token = userState.value?.token
                        val resp = joinJam(apiUrlState.value, jamId, context, token)
                        if (resp.ok && resp.jam != null) {
                            jamIdState.value = resp.jam.id
                            JamPreferences.setStoredJamId(context, resp.jam.id)
                            JamWebSocketManager.connect(apiUrlState.value, resp.jam.id, token ?: "", resp.jam)
                            playerManager.enableJamSync(apiUrlState.value, token ?: "")
                            currentScreen.value = "jam_session"
                            showJoinJamBottomSheet.value = false
                        } else {
                            
                            
                            val fetchResp = fetchJam(apiUrlState.value, jamId, context, token)
                            if (fetchResp.ok && fetchResp.jam != null) {
                                jamIdState.value = fetchResp.jam.id
                                JamPreferences.setStoredJamId(context, fetchResp.jam.id)
                                JamWebSocketManager.connect(apiUrlState.value, fetchResp.jam.id, token ?: "", fetchResp.jam)
                                playerManager.enableJamSync(apiUrlState.value, token ?: "")
                                currentScreen.value = "jam_session"
                                showJoinJamBottomSheet.value = false
                            } else {
                                
                                android.widget.Toast.makeText(context, "Failed to join Jam. Invalid ID or URL.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )
        }
    }
}

private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp {
    val t = fraction.coerceIn(0f, 1f)
    return (start.value + (stop.value - start.value) * t).dp
}

private fun lerp(start: TextUnit, stop: TextUnit, fraction: Float): TextUnit {
    val t = fraction.coerceIn(0f, 1f)
    if (start.isSp && stop.isSp) {
        return (start.value + (stop.value - start.value) * t).sp
    }
    return start
}

@Composable
fun RemoveFriendConfirmationDialog(
    friend: Friend,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Remove Friend",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = "Are you sure you want to remove ${friend.first_name} from your friends?",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF13950),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendSettingsDialog(
    currentSettings: FriendSettings,
    onDismiss: () -> Unit,
    onSave: (FriendSettings) -> Unit
) {
    var shareListening by remember { mutableStateOf(currentSettings.share_listening) }
    var allowJamInvites by remember { mutableStateOf(currentSettings.allow_jam_invites) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "Friends Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Share Listening", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    val options = listOf("everyone", "friends", "none")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { option ->
                            val isSelected = shareListening == option
                            Button(
                                onClick = { shareListening = option },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) Color(0xFFF13950) else Color.Gray.copy(alpha = 0.1f),
                                    contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(option.replaceFirstChar { it.uppercase() }, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Allow Jam Invites", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    androidx.compose.material3.Switch(
                        checked = allowJamInvites,
                        onCheckedChange = { allowJamInvites = it },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFF13950)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onSave(FriendSettings(shareListening, allowJamInvites)) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF13950),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MiniPlayerHost(
    canShowPlayer: Boolean,
    currentScreen: String,
    jamId: String?,
    apiBaseUrl: String,
    userToken: String?,
    currentUserId: Long?,
    context: Context,
    playerManager: MusicPlayerManager,
    onExpandPlayer: () -> Unit,
    onStopPlayer: () -> Unit,
    onSwipeProgress: (Float) -> Unit,
    onJamPlayerClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentSong = playerManager.currentSong.value
    val isPlaying = playerManager.isPlaying.value
    val rawIsLoading = playerManager.isLoading.value
    val miniPlayerLoadingState = remember { mutableStateOf(false) }
    LaunchedEffect(rawIsLoading) {
        if (rawIsLoading) {
            delay(180L)
            if (playerManager.isLoading.value) {
                miniPlayerLoadingState.value = true
            }
        } else {
            delay(120L)
            if (!playerManager.isLoading.value) {
                miniPlayerLoadingState.value = false
            }
        }
    }
    val isLoading = miniPlayerLoadingState.value
    val showPlayer = canShowPlayer && currentSong != null && currentScreen != "launch_picker"

    AnimatedVisibility(
        visible = showPlayer && jamId == null,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(durationMillis = 300)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(durationMillis = 300)),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
    ) {
        MusicPlayer(
            song = currentSong,
            isPlaying = isPlaying,
            isLoading = isLoading,
            onPlayPauseClick = { playerManager.togglePlayPause() },
            onPlayerClick = onExpandPlayer,
            onPlayerLongClick = onStopPlayer,
            onSwipeProgress = onSwipeProgress,
            onSwipeUp = onExpandPlayer,
            onNextClick = { playerManager.playNext() }
        )
    }

    AnimatedVisibility(
        visible = showPlayer && jamId != null,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(durationMillis = 300)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(durationMillis = 300)),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
    ) {
        MusicPlayer(
            song = currentSong,
            isPlaying = isPlaying,
            isLoading = isLoading,
            isJam = true,
            onPlayPauseClick = {
                scope.launch {
                    val activeJamId = jamId ?: return@launch
                    val jamState = JamWebSocketManager.jamState.value
                    val isHost = jamState?.hostUserId == currentUserId

                    if (isHost) {
                        if (isPlaying) {
                            JamWebSocketManager.sendAction("pause") || jamPause(apiBaseUrl, activeJamId, context, userToken)
                        } else {
                            JamWebSocketManager.sendAction("play") || jamPlay(apiBaseUrl, activeJamId, context, userToken)
                        }
                    } else {
                        playerManager.toggleLocalPlayPause()
                    }
                }
            },
            onPlayerClick = onJamPlayerClick,
            onPlayerLongClick = onStopPlayer,
            onSwipeProgress = onSwipeProgress,
            onSwipeUp = onExpandPlayer,
            onNextClick = {
                scope.launch {
                    val activeJamId = jamId ?: return@launch
                    JamWebSocketManager.sendAction("next") || jamNext(apiBaseUrl, activeJamId, context, userToken)
                }
            }
        )
    }
}

@Composable
fun FriendListeningDialog(
    friend: Friend,
    song: Song?,
    isInJam: Boolean,
    isLoading: Boolean,
    apiBaseUrl: String,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1C1C1E))
                .padding(20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = friend.first_name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
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

                Spacer(modifier = Modifier.height(20.dp))

                if (isInJam) {
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFF13950).copy(alpha = 0.15f))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFFF13950),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "In a Jam Session",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF13950)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${friend.first_name} is listening with friends",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else if (isLoading) {
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Box(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                            )
                        }
                    }
                } else if (song != null) {
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverArt(
                            coverUrl = song.coverUrl,
                            fallbackColor = song.color,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = song.artist,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!song.album.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = song.album,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Listening now",
                        fontSize = 12.sp,
                        color = Color(0xFFF13950),
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "(つ﹏⊂)",
                                fontSize = 28.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Not listening right now",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendDialog(onDismiss: () -> Unit, onAdd: (Long) -> Unit) {
    var userIdText by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }
    
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isAdding) onDismiss() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Add Friend",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                OutlinedTextField(
                    value = userIdText,
                    onValueChange = { if (!isAdding) userIdText = it },
                    placeholder = { Text("User ID") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isAdding,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFF13950),
                        cursorColor = Color(0xFFF13950)
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isAdding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val userId = userIdText.toLongOrNull()
                            if (userId != null) {
                                isAdding = true
                                onAdd(userId)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF13950),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = userIdText.isNotBlank() && userIdText.toLongOrNull() != null && !isAdding
                    ) {
                        if (isAdding) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text("Add")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isCreating) onDismiss() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "New Playlist",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!isCreating) name = it },
                    placeholder = { Text("Playlist Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isCreating,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFF13950),
                        cursorColor = Color(0xFFF13950)
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isCreating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { 
                            if (name.isNotBlank()) {
                                isCreating = true
                                onCreate(name)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF13950),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = name.isNotBlank() && !isCreating
                    ) {
                        if (isCreating) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text("Create")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenamePlaylistDialog(playlist: Playlist, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(playlist.title) }
    var isRenaming by remember { mutableStateOf(false) }
    
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isRenaming) onDismiss() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Rename Playlist",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!isRenaming) name = it },
                    placeholder = { Text("Playlist Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isRenaming,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFF13950),
                        cursorColor = Color(0xFFF13950)
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isRenaming,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { 
                            if (name.isNotBlank()) {
                                isRenaming = true
                                onRename(name)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF13950),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = name.isNotBlank() && !isRenaming && name != playlist.title
                    ) {
                        if (isRenaming) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text("Rename")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun <T> freezeList(input: List<T>): List<T> =
    withContext(Dispatchers.Default) { input.toList() }

private suspend fun mapListening(input: List<FriendListening>): Map<Long, FriendListening> =
    withContext(Dispatchers.Default) { input.associateBy { it.user_id } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit, title: String = "Settings") {
    TopAppBar(
        title = {
            Text(
                text = title,
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiTopBar(onBack: () -> Unit, showBack: Boolean = true) {
    TopAppBar(
        title = {
            Text(
                text = "Api",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "Audio Settings",
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "Appearance",
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperSettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "Developer Settings",
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ApiScreen(
    currentApiUrl: String,
    isLoadingLatest: Boolean,
    isPlayerVisible: Boolean,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scope = rememberCoroutineScope()
    var savedApis by remember { mutableStateOf(ApiPreferences.getSavedApis(context)) }
    var showAddApiDialog by remember { mutableStateOf(false) }
    var apiToDelete by remember { mutableStateOf<ApiPreferences.ApiEntry?>(null) }
    var apiNameInput by rememberSaveable { mutableStateOf("") }
    var apiUrlInput by rememberSaveable { mutableStateOf("") }
    var expandedLogsApiUrl by remember { mutableStateOf<String?>(null) }
    var logsByApi by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var logsLoadingApiUrl by remember { mutableStateOf<String?>(null) }
    var logsErrorByApi by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordDialogApiUrl by remember { mutableStateOf("") }
    var passwordDialogIsSetup by remember { mutableStateOf(false) }
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var passwordDialogLoading by remember { mutableStateOf(false) }
    var passwordDialogError by remember { mutableStateOf<String?>(null) }

    fun toggleLogs(apiUrl: String) {
        val normalized = normalizeApiInput(apiUrl)
        if (expandedLogsApiUrl == normalized) {
            expandedLogsApiUrl = null
            return
        }
        expandedLogsApiUrl = normalized

        if (logsByApi[normalized] != null || logsLoadingApiUrl == normalized) return
        val token = AuthPreferences.getEffectiveToken(context)
        if (token.isNullOrBlank()) {
            logsErrorByApi = logsErrorByApi + (normalized to "Login required to view logs.")
            return
        }

        scope.launch {
            logsLoadingApiUrl = normalized
            logsErrorByApi = logsErrorByApi - normalized
            val role = fetchCurrentUserRole(normalized, context, token)?.lowercase()
            if (role == "owner" || role == "sudo") {
                val logs = fetchServerLogs(normalized, context, token)
                logsByApi = logsByApi + (normalized to logs)
                if (logs.isEmpty()) {
                    logsErrorByApi = logsErrorByApi + (normalized to "No logs available.")
                }
            } else {
                logsErrorByApi = logsErrorByApi + (normalized to "Logs are available only for owner/sudo.")
            }
            logsLoadingApiUrl = null
        }
    }

    val fabBottomPadding by animateDpAsState(
        targetValue = if (isPlayerVisible) 96.dp else 20.dp,
        animationSpec = tween(durationMillis = 260),
        label = "ApiFabBottomPadding"
    )

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 140.dp)
        ) {
            item {
                if (savedApis.isEmpty()) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No APIs saved",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap the + button below to add your first API server.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            items(savedApis) { api ->
                val isSelected = api.url == currentApiUrl
                val normalizedApiUrl = normalizeApiInput(api.url)
                val isLogsExpanded = expandedLogsApiUrl == normalizedApiUrl

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { apiToDelete = api }
                            )
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = api.name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = api.url,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.material3.Surface(
                                onClick = {
                                    if (isSelected) {
                                        onSave(api.url)
                                        return@Surface
                                    }
                                    scope.launch {
                                        passwordDialogLoading = true
                                        passwordDialogError = null
                                        passwordInput = ""
                                        val status = getSetupStatus(api.url)
                                        passwordDialogLoading = false
                                        if (status.ok && status.needs_setup) {
                                            passwordDialogApiUrl = api.url
                                            passwordDialogIsSetup = true
                                            showPasswordDialog = true
                                        } else {
                                            passwordDialogApiUrl = api.url
                                            passwordDialogIsSetup = false
                                            showPasswordDialog = true
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isSelected) "Active" else "Use",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            androidx.compose.material3.Surface(
                                onClick = { toggleLogs(api.url) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isLogsExpanded) Icons.Default.ExpandLess else Icons.AutoMirrored.Filled.List,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isLogsExpanded) "Hide" else "Logs",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        if (isLogsExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                when {
                                    logsLoadingApiUrl == normalizedApiUrl -> {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(
                                                text = "Loading logs…",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                    logsByApi[normalizedApiUrl]?.isNotEmpty() == true -> {
                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(220.dp)
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            items(logsByApi[normalizedApiUrl]!!.take(120)) { line ->
                                                Text(
                                                    text = line,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 11.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        Text(
                                            text = logsErrorByApi[normalizedApiUrl] ?: "No logs found.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp),
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val fabContainerColor = if (isDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
        FloatingActionButton(
            onClick = { showAddApiDialog = true },
            containerColor = fabContainerColor,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = fabBottomPadding)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add API")
        }
    }

    if (showAddApiDialog) {
        AlertDialog(
            onDismissRequest = { showAddApiDialog = false },
            title = { Text("Add API") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = apiNameInput,
                        onValueChange = { apiNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiUrlInput,
                        onValueChange = { apiUrlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API URL") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ApiPreferences.addSavedApi(context, apiNameInput, apiUrlInput)
                        savedApis = ApiPreferences.getSavedApis(context)
                        apiNameInput = ""
                        apiUrlInput = ""
                        showAddApiDialog = false
                    },
                    enabled = apiNameInput.isNotBlank() && apiUrlInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddApiDialog = false
                        apiNameInput = ""
                        apiUrlInput = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    apiToDelete?.let { api ->
        AlertDialog(
            onDismissRequest = { apiToDelete = null },
            title = { Text("Delete API") },
            text = { Text("Delete \"${api.name}\" from saved APIs?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        ApiPreferences.removeSavedApi(context, api.url)
                        savedApis = ApiPreferences.getSavedApis(context)
                        apiToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { apiToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!passwordDialogLoading) {
                    showPasswordDialog = false
                    passwordDialogError = null
                }
            },
            title = {
                Text(
                    if (passwordDialogIsSetup) "Create Admin Password"
                    else "Enter API Password"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (passwordDialogIsSetup) {
                        Text(
                            "This server hasn't been set up yet. Create a password to secure it.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            passwordDialogError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !passwordDialogLoading,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    if (passwordDialogError != null) {
                        Text(
                            text = passwordDialogError!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                    if (passwordDialogLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (passwordInput.isBlank()) {
                            passwordDialogError = "Password is required"
                            return@TextButton
                        }
                        scope.launch {
                            passwordDialogLoading = true
                            passwordDialogError = null
                            val response = if (passwordDialogIsSetup) {
                                setupOwnerPassword(passwordDialogApiUrl, passwordInput)
                            } else {
                                ownerLogin(passwordDialogApiUrl, passwordInput)
                            }
                            passwordDialogLoading = false
                            if (response.ok && response.token != null) {
                                AuthPreferences.clear(context) // Sign out of current account
                                AuthPreferences.saveApiToken(context, response.token)
                                showPasswordDialog = false
                                passwordInput = ""
                                onSave(passwordDialogApiUrl)
                            }
 else {
                                passwordDialogError = if (passwordDialogIsSetup) {
                                    "Setup failed. The server may already be configured."
                                } else {
                                    "Invalid password. Please try again."
                                }
                            }
                        }
                    },
                    enabled = passwordInput.isNotBlank() && !passwordDialogLoading
                ) {
                    Text(if (passwordDialogIsSetup) "Create" else "Login")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!passwordDialogLoading) {
                            showPasswordDialog = false
                            passwordDialogError = null
                        }
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
