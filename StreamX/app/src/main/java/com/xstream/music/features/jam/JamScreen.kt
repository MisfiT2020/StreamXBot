package com.xstream.music.features.jam

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
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.xstream.music.core.cache.DataCache
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.Friend
import com.xstream.music.data.model.UserData
import com.xstream.music.player.manager.MusicPlayerManager
import com.xstream.music.realtime.websocket.JamWebSocketManager
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.components.ProfileImage
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun JamScreen(
    jamId: String,
    apiBaseUrl: String = "",
    onBack: () -> Unit,
    onLeave: () -> Unit,
    onOpenPlayer: () -> Unit,
    userState: UserData?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    friends: List<Friend> = emptyList(),
    onInviteFriend: suspend (Long) -> InviteJamResult = { InviteJamResult(ok = false, detail = "Invite unavailable") }
) {
    val context = LocalContext.current
    val jamState by JamWebSocketManager.jamState.collectAsState()
    val scrollState = rememberScrollState()
    val shareLink = remember(jamId, apiBaseUrl) { buildJamWebLink(jamId, apiBaseUrl) }
    val isAmoled = remember { DataCache.isAmoledBlackEnabled(context) }
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = colorScheme.background.luminance() < 0.5f
    val accentColor = colorScheme.primary
    val accentLuminance = accentColor.luminance()
    val accentContentColor = if (((accentLuminance + 0.05f) / 0.05f) >= (1.05f / (accentLuminance + 0.05f))) {
        Color.Black
    } else {
        Color.White
    }
    val screenBackgroundColor = if (isDarkTheme && isAmoled) Color.Black else colorScheme.background
    val surfaceColor = if (isDarkTheme && isAmoled) Color.Black else colorScheme.surface
    val elevatedSurfaceColor = if (isDarkTheme && isAmoled) Color(0xFF0A0A0A) else colorScheme.surface
    val liveChipColor = if (isDarkTheme) {
        colorScheme.surface.copy(alpha = 0.96f)
    } else {
        colorScheme.surfaceVariant.copy(alpha = 0.92f)
    }
    val secondaryTextColor = colorScheme.onSurfaceVariant.copy(alpha = if (isDarkTheme) 0.82f else 0.88f)
    val mutedTextColor = colorScheme.onSurfaceVariant.copy(alpha = if (isDarkTheme) 0.68f else 0.76f)
    val subtleSurfaceColor = colorScheme.onSurface.copy(alpha = if (isDarkTheme) 0.05f else 0.06f)
    val accentSurfaceColor = accentColor.copy(alpha = if (isDarkTheme) 0.14f else 0.12f)
    val dividerColor = colorScheme.onSurface.copy(alpha = if (isDarkTheme) 0.05f else 0.10f)
    val sectionBorderColor = if (isDarkTheme) Color.Transparent else colorScheme.onSurface.copy(alpha = 0.08f)
    
    
    val infiniteTransition = rememberInfiniteTransition(label = "blinking")
    val liveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    
    
    
    
    
    
    val playerManager: MusicPlayerManager = viewModel()
    val playerCurrentSong = playerManager.currentSong.value
    val playerPositionMs = playerManager.currentPosition.value
    val playerDurationMs = playerManager.duration.value
    
    val apiUrl = remember { ApiPreferences.getApiUrl(context) }
    val scope = rememberCoroutineScope()
    val inviteCooldownUntilByFriend = remember(jamId) { mutableStateMapOf<Long, Long>() }
    val inviteInFlightByFriend = remember(jamId) { mutableStateMapOf<Long, Boolean>() }
    var inviteClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(jamId) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            inviteClockMs = System.currentTimeMillis()
            inviteCooldownUntilByFriend.entries.removeAll { (_, untilMs) -> untilMs <= inviteClockMs }
        }
    }
    
    
    val playback = jamState?.playback
    val currentTrackId = playback?.trackId
    val syncedQueue = playerManager.queue.toList()
    val jamCurrentSong = syncedQueue.firstOrNull { it.id == currentTrackId }
        ?: playerCurrentSong?.takeIf { currentTrackId.isNullOrBlank() || it.id == currentTrackId }
        ?: syncedQueue.firstOrNull()
        ?: playerCurrentSong
    val effectiveDurationMs = playerDurationMs.takeIf { it > 0 }
        ?: (playback?.durationSec?.times(1000L)?.takeIf { it > 0 })
        ?: ((jamCurrentSong?.durationSec ?: 0) * 1000L)

    val isPlayerSyncedToJamTrack = if (currentTrackId.isNullOrBlank()) {
        jamCurrentSong?.id == playerCurrentSong?.id
    } else {
        playerCurrentSong?.id == currentTrackId
    }

    val playbackServerTimeSec = jamState?.serverTime
    val fallbackAnchorMs = remember(
        currentTrackId,
        playback?.positionSec,
        playback?.startedAt,
        playback?.isPlaying,
        playbackServerTimeSec
    ) {
        System.currentTimeMillis()
    }

    var fallbackClockMs by remember(
        currentTrackId,
        playback?.positionSec,
        playback?.startedAt,
        playback?.isPlaying,
        playbackServerTimeSec
    ) {
        mutableLongStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(
        currentTrackId,
        playback?.positionSec,
        playback?.startedAt,
        playback?.isPlaying,
        playbackServerTimeSec,
        isPlayerSyncedToJamTrack
    ) {
        fallbackClockMs = System.currentTimeMillis()
        if (playback?.isPlaying == true) {
            while (true) {
                kotlinx.coroutines.delay(250)
                fallbackClockMs = System.currentTimeMillis()
            }
        }
    }

    val fallbackPositionMs = playback?.let { livePlayback ->
        val localElapsedSec = (fallbackClockMs - fallbackAnchorMs).coerceAtLeast(0L) / 1000.0
        val syncReferenceSec = playbackServerTimeSec ?: (fallbackAnchorMs / 1000.0)
        val liveReferenceSec = if (livePlayback.isPlaying) {
            syncReferenceSec + localElapsedSec
        } else {
            syncReferenceSec
        }
        val resolvedPosMs = if (livePlayback.isPlaying) {
            val resolvedPositionSec = if (livePlayback.startedAt > 0.0) {
                livePlayback.positionSec + (liveReferenceSec - livePlayback.startedAt).coerceAtLeast(0.0)
            } else {
                livePlayback.positionSec + localElapsedSec
            }
            (resolvedPositionSec * 1000.0).toLong()
        } else {
            (livePlayback.positionSec * 1000).toLong()
        }
            .coerceAtLeast(0L)
        if (effectiveDurationMs > 0) resolvedPosMs.coerceAtMost(effectiveDurationMs) else resolvedPosMs
    } ?: 0L

    val currentPositionMs = if (playback != null) {
        val clampedFallbackPositionMs = if (effectiveDurationMs > 0) {
            fallbackPositionMs.coerceIn(0L, effectiveDurationMs)
        } else {
            fallbackPositionMs.coerceAtLeast(0L)
        }
        val clampedPlayerPositionMs = if (effectiveDurationMs > 0) {
            playerPositionMs.coerceIn(0L, effectiveDurationMs)
        } else {
            playerPositionMs.coerceAtLeast(0L)
        }
        val driftMs = abs(clampedPlayerPositionMs - clampedFallbackPositionMs)

        if (isPlayerSyncedToJamTrack && clampedPlayerPositionMs > 0L && driftMs <= 1500L) {
            clampedPlayerPositionMs
        } else {
            clampedFallbackPositionMs
        }
    } else {
        if (effectiveDurationMs > 0) {
            playerPositionMs.coerceAtMost(effectiveDurationMs).coerceAtLeast(0L)
        } else {
            playerPositionMs.coerceAtLeast(0L)
        }
    }
    
    if (jamState == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenBackgroundColor),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = accentColor)
            Text(
                text = "Connecting to Jam...",
                color = colorScheme.onBackground,
                modifier = Modifier.padding(top = 64.dp)
            )
        }
        return
    }
    
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
                        tint = colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Jam Session",
                        color = colorScheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: $jamId",
                        color = mutedTextColor,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(liveChipColor)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                            .alpha(liveAlpha)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "LIVE",
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                }
            }
        },
        containerColor = screenBackgroundColor
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = if (jamCurrentSong != null) 100.dp else 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .shadow(12.dp, RoundedCornerShape(24.dp))
                    .background(elevatedSurfaceColor)
                    .clickable { onOpenPlayer() }
            ) {
                val coverUrl = jamCurrentSong?.coverUrl?.trim().orEmpty()
                if (coverUrl.isNotBlank()) {
                    CoverArt(
                        coverUrl = coverUrl,
                        fallbackColor = jamCurrentSong?.color ?: elevatedSurfaceColor,
                        modifier = Modifier.fillMaxSize(),
                        requestSizePx = 1024
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.albumspeakerlarge),
                        contentDescription = null,
                        tint = mutedTextColor.copy(alpha = 0.35f),
                        modifier = Modifier.size(120.dp).align(Alignment.Center)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = jamCurrentSong?.title ?: "Nothing playing",
                    color = colorScheme.onBackground,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = jamCurrentSong?.artist ?: "Unknown Artist",
                    color = secondaryTextColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.height(36.dp))
            
            
            val durationMs = effectiveDurationMs
            val formatTime = { ms: Long ->
                val totalSec = ms / 1000
                val min = totalSec / 60
                val sec = totalSec % 60
                String.format("%d:%02d", min, sec)
            }
            
            val isHost = jamState?.hostUserId == userState?.id
            val allowSeek = isHost || (jamState?.settings?.allowSeek == true)
            
            var isDragging by remember { mutableStateOf(false) }
            var dragPosition by remember { mutableStateOf(0f) }
            val displayPosition = if (isDragging) dragPosition.toLong() else currentPositionMs
            val progress = if (durationMs > 0) displayPosition.toFloat() / durationMs.toFloat() else 0f
            
            Column {
                if (allowSeek) {
                    Slider(
                        value = progress,
                        onValueChange = { 
                            isDragging = true
                            dragPosition = it * durationMs
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            scope.launch {
                                val token = userState?.token
                                jamSeek(apiUrl, jamId, dragPosition / 1000.0, context, token)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = dividerColor
                        )
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = accentColor,
                        trackColor = dividerColor
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = formatTime(displayPosition), color = secondaryTextColor, fontSize = 12.sp)
                    Text(text = formatTime(durationMs), color = mutedTextColor, fontSize = 12.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                
                IconButton(
                    onClick = onLeave,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(subtleSurfaceColor)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Leave",
                        tint = colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                
                val isJamPlaying = isPlaying
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = if (isJamPlaying) R.drawable.pause else R.drawable.resume),
                        contentDescription = if (isJamPlaying) "Pause" else "Play",
                        tint = accentContentColor,
                        modifier = Modifier.size(36.dp)
                    )
                }
                
                
                IconButton(
                    onClick = {
                        scope.launch {
                            val token = userState?.token
                            jamNext(apiUrl, jamId, context, token)
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(subtleSurfaceColor)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.forward),
                        contentDescription = "Next",
                        tint = colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            
            val members = jamState?.members ?: emptyList()
            val numListeners = members.size
            var isListenersExpanded by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(surfaceColor)
                    .border(1.dp, sectionBorderColor, RoundedCornerShape(20.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null 
                        ) { isListenersExpanded = !isListenersExpanded }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(accentSurfaceColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Listeners",
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "$numListeners active now",
                            color = secondaryTextColor,
                            fontSize = 13.sp
                        )
                    }
                    
                    var showInviteSheet by remember { mutableStateOf(false) }

                    Button(
                        onClick = {
                            showInviteSheet = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = subtleSurfaceColor),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Invite", color = colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    if (showInviteSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showInviteSheet = false },
                            containerColor = colorScheme.surface,
                            dragHandle = { BottomSheetDefaults.DragHandle(color = mutedTextColor) }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 16.dp)
                            ) {
                                Text(
                                    text = "Invite Friends to Jam",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                        .clickable {
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, "Join my jam on StreamX: $shareLink")
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "Share Jam"))
                                        }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.size(36.dp).clip(CircleShape).background(accentColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share Link",
                                            tint = accentContentColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = "Share Link",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(24.dp))

                                if (friends.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(100.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No friends available", color = mutedTextColor)
                                    }
                                } else {
                                    androidx.compose.foundation.lazy.LazyColumn(
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(friends, key = { it._id }) { friend ->
                                            val inviteCooldownUntil = inviteCooldownUntilByFriend[friend._id] ?: 0L
                                            val remainingCooldownSec = ((inviteCooldownUntil - inviteClockMs).coerceAtLeast(0L) + 999L) / 1000L
                                            val isCoolingDown = remainingCooldownSec > 0L
                                            val isSendingInvite = inviteInFlightByFriend[friend._id] == true
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                
                                                val h = friend._id.hashCode()
                                                val rColor = 40 + (h and 0x7F)
                                                val gColor = 40 + ((h shr 8) and 0x7F)
                                                val bColor = 40 + ((h shr 16) and 0x7F)
                                                val friendColor = Color(0xFF000000 or (rColor shl 16).toLong() or (gColor shl 8).toLong() or bColor.toLong())

                                                Box(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(CircleShape)
                                                        .background(friendColor),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = friend.first_name.firstOrNull()?.uppercase() ?: "?",
                                                        color = Color.White,
                                                        fontSize = 20.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    
                                                    if (!friend.photo_url.isNullOrBlank()) {
                                                        coil.compose.AsyncImage(
                                                            model = coil.request.ImageRequest.Builder(context)
                                                                .data(friend.photo_url)
                                                                .crossfade(true)
                                                                .build(),
                                                            contentDescription = "Friend Profile",
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .clip(CircleShape),
                                                            contentScale = ContentScale.Crop
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Text(
                                                    text = friend.first_name,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(modifier = Modifier.weight(1f))
                                                TextButton(
                                                    enabled = !isSendingInvite && !isCoolingDown,
                                                    onClick = {
                                                        inviteInFlightByFriend[friend._id] = true
                                                        scope.launch {
                                                            val result = onInviteFriend(friend._id)
                                                            inviteInFlightByFriend.remove(friend._id)

                                                            val cooldownSec = when {
                                                                result.ok -> result.cooldownSec ?: 60
                                                                result.retryAfterSec != null -> result.retryAfterSec
                                                                else -> null
                                                            }
                                                            if (cooldownSec != null && cooldownSec > 0) {
                                                                inviteCooldownUntilByFriend[friend._id] = System.currentTimeMillis() + (cooldownSec * 1000L)
                                                                inviteClockMs = System.currentTimeMillis()
                                                            }

                                                            val message = when {
                                                                result.ok -> "Invite sent!"
                                                                result.retryAfterSec != null -> "Please wait ${result.retryAfterSec}s before inviting again"
                                                                !result.detail.isNullOrBlank() -> result.detail
                                                                else -> "Failed to send invite"
                                                            }
                                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.textButtonColors(
                                                        contentColor = if (isSendingInvite || isCoolingDown) mutedTextColor else accentColor
                                                    )
                                                ) {
                                                    Text(
                                                        text = when {
                                                            isSendingInvite -> "Sending..."
                                                            isCoolingDown -> "${remainingCooldownSec}s"
                                                            else -> "Invite"
                                                        },
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(32.dp))
                            }
                        }
                    }
                }
                
                if (isListenersExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        members.forEach { member ->
                            val isHostMember = member.role == "host"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(subtleSurfaceColor)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(36.dp).clip(CircleShape)) {
                                    ProfileImage(
                                        imageUrl = member.profileUrl,
                                        fallbackText = member.firstName.take(1).uppercase(),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = member.firstName,
                                    color = colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isHostMember) {
                                    Text(
                                        text = "HOST",
                                        color = accentColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(accentSurfaceColor)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            
            Text(
                text = "Up Next",
                color = colorScheme.onBackground,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            val upNextQueue = if (syncedQueue.isNotEmpty()) syncedQueue.drop(1) else emptyList()
            
            if (upNextQueue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(surfaceColor)
                        .border(1.dp, sectionBorderColor, RoundedCornerShape(20.dp))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Queue is empty",
                        color = mutedTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(surfaceColor)
                        .border(1.dp, sectionBorderColor, RoundedCornerShape(20.dp))
                ) {
                    upNextQueue.forEachIndexed { index, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { 
                                    if (isHost && song.id != null) {
                                        playerManager.playJamTrack(song.id)
                                    }
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                    color = colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song.artist,
                                    color = secondaryTextColor,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            if (song.durationSec != null) {
                                val min = song.durationSec / 60
                                val sec = song.durationSec % 60
                                Text(
                                    text = String.format("%d:%02d", min, sec),
                                    color = mutedTextColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (index < upNextQueue.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = dividerColor
                            )
                        }
                    }
                }
            }
        }
    }
}
