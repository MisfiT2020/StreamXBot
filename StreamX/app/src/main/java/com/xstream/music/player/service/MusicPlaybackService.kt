package com.xstream.music.player.service

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
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xstream.music.app.MainActivity
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.preferences.JamPreferences
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.player.manager.MusicPlayerManager

@UnstableApi
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val ACTION_TOGGLE_FAVORITE = "com.xstream.music.TOGGLE_FAVORITE"
    }

    override fun onCreate() {
        super.onCreate()

        val player = MusicPlayerManager.getPlayer(this)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val favoriteCommand = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
        
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCustomLayout(listOf(getFavoriteButton(false)))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val connectionResult = super.onConnect(session, controller)
                    val sessionCommands = connectionResult.availableSessionCommands
                        .buildUpon()
                        .add(favoriteCommand)
                        .build()
                    
                    
                    val currentId = player.currentMediaItem?.mediaId
                    val isFav = currentId != null && DataCache.favoriteIds.value.contains(currentId)
                    session.setCustomLayout(controller, listOf(getFavoriteButton(isFav)))
                    
                    return MediaSession.ConnectionResult.accept(
                        sessionCommands,
                        connectionResult.availablePlayerCommands
                    )
                }

                @Suppress("DEPRECATION")
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    if (playerCommand == androidx.media3.common.Player.COMMAND_PLAY_PAUSE) {
                        val activeManager = MusicPlayerManager.getActiveManager()
                        if (activeManager?.jamId != null) {
                            activeManager.handleMediaControllerJamCommand(playerCommand)
                        } else {
                            handleBackgroundJamPlayPause(player)
                        }
                    }
                    return super.onPlayerCommandRequest(session, controller, playerCommand)
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == ACTION_TOGGLE_FAVORITE) {
                        handleToggleFavorite()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
            
        
        serviceScope.launch(Dispatchers.Main) {
            launch {
                DataCache.favoriteIds.collect { favs ->
                    val currentId = player.currentMediaItem?.mediaId
                    val isFav = currentId != null && favs.contains(currentId)
                    updateFavoriteButton(isFav)
                }
            }
            
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_ENDED) {
                        val activeManager = MusicPlayerManager.getActiveManager()
                        if (activeManager?.jamId != null) {
                            activeManager.handleJamPlaybackEnded()
                        } else {
                            handleBackgroundJamPlaybackEnded()
                        }
                    }
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    val isFav = mediaItem?.mediaId?.let { DataCache.favoriteIds.value.contains(it) } == true
                    updateFavoriteButton(isFav)
                }
            })
        }
    }
    
    private fun getFavoriteButton(isFavorite: Boolean): CommandButton {
        val iconResId = if (isFavorite) R.drawable.ic_check_circle else R.drawable.ic_add_circle_outline
        val displayName = if (isFavorite) "Remove from Favorites" else "Add to Favorites"
        val favoriteCommand = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
        return CommandButton.Builder()
            .setDisplayName(displayName)
            .setIconResId(iconResId)
            .setSessionCommand(favoriteCommand)
            .build()
    }

    private fun updateFavoriteButton(isFavorite: Boolean) {
        val button = getFavoriteButton(isFavorite)
        mediaSession?.let { session ->
            session.connectedControllers.forEach { controller ->
                session.setCustomLayout(controller, listOf(button))
            }
        }
    }

    private fun handleToggleFavorite() {
        serviceScope.launch {
            
            val trackId = withContext(Dispatchers.Main) {
                val exoPlayer = MusicPlayerManager.getPlayer(this@MusicPlaybackService)
                exoPlayer.currentMediaItem?.mediaId
            }
            
            if (trackId.isNullOrBlank()) return@launch
            
            val context = this@MusicPlaybackService
            val userToken = AuthPreferences.getUser(context)?.token
            val apiBaseUrl = ApiPreferences.getApiUrl(context)
            
            val isFavorite = DataCache.favoriteIds.value.contains(trackId)
            val currentFavs = DataCache.favoriteIds.value.toMutableSet()
            
            if (isFavorite) {
                currentFavs.remove(trackId)
                DataCache.favoriteIds.value = currentFavs
                removeFavorite(apiBaseUrl, trackId, context, userToken)
            } else {
                currentFavs.add(trackId)
                DataCache.favoriteIds.value = currentFavs
                addFavorite(apiBaseUrl, trackId, context, userToken)
            }
        }
    }

    private fun handleBackgroundJamPlayPause(player: androidx.media3.common.Player) {
        val jamId = JamPreferences.getStoredJamId(this).orEmpty()
        if (jamId.isBlank()) return

        val apiBaseUrl = ApiPreferences.getApiUrl(this)
        val user = AuthPreferences.getUser(this)
        val token = user?.token
        if (apiBaseUrl.isBlank() || token.isNullOrBlank()) return

        serviceScope.launch {
            val jamResponse = fetchJam(apiBaseUrl, jamId, this@MusicPlaybackService, token)
            val jam = jamResponse.jam
            if (jam == null) return@launch

            JamWebSocketManager.connect(apiBaseUrl, jamId, token, jam)
            val isHost = jam.hostUserId == user?.id
            if (!isHost) return@launch

            val shouldPause = player.playWhenReady
            if (shouldPause) {
                if (JamWebSocketManager.sendAction("pause") || jamPause(apiBaseUrl, jamId, this@MusicPlaybackService, token)) {
                    refreshOrApplyJamState(apiBaseUrl, jamId, token)
                }
            } else {
                if (JamWebSocketManager.sendAction("play") || jamPlay(apiBaseUrl, jamId, this@MusicPlaybackService, token)) {
                    refreshOrApplyJamState(apiBaseUrl, jamId, token)
                }
            }
        }
    }

    private fun handleBackgroundJamPlaybackEnded() {
        val jamId = JamPreferences.getStoredJamId(this).orEmpty()
        if (jamId.isBlank()) return

        val apiBaseUrl = ApiPreferences.getApiUrl(this)
        val user = AuthPreferences.getUser(this)
        val token = user?.token
        if (apiBaseUrl.isBlank() || token.isNullOrBlank()) return

        serviceScope.launch {
            val jamResponse = fetchJam(apiBaseUrl, jamId, this@MusicPlaybackService, token)
            val jam = jamResponse.jam ?: return@launch

            JamWebSocketManager.connect(apiBaseUrl, jamId, token, jam)
            if (jam.hostUserId == user?.id) {
                if (jam.queue.isNotEmpty()) {
                    if (JamWebSocketManager.sendAction("next") || jamNext(apiBaseUrl, jamId, this@MusicPlaybackService, token)) {
                        refreshOrApplyJamState(apiBaseUrl, jamId, token)
                    }
                } else {
                    if (JamWebSocketManager.sendAction("pause") || jamPause(apiBaseUrl, jamId, this@MusicPlaybackService, token)) {
                        refreshOrApplyJamState(apiBaseUrl, jamId, token)
                    }
                }
            }
        }
    }

    private suspend fun refreshOrApplyJamState(apiBaseUrl: String, jamId: String, token: String) {
        val jam = fetchJam(apiBaseUrl, jamId, this@MusicPlaybackService, token).jam ?: return
        JamWebSocketManager.updateState(jam)
        if (!JamWebSocketManager.isConnected()) {
            JamWebSocketManager.connect(apiBaseUrl, jamId, token, jam)
        }
        if (MusicPlayerManager.getActiveManager() == null) {
            applyJamPlaybackDirectly(jam, apiBaseUrl, token)
        }
    }

    private suspend fun applyJamPlaybackDirectly(jam: Jam, apiBaseUrl: String, token: String) {
        val trackId = jam.playback.trackId.takeIf { it.isNotBlank() } ?: return
        val song = fetchSong(apiBaseUrl, trackId, this@MusicPlaybackService, token) ?: return
        val mediaItem = buildServiceMediaItem(song, apiBaseUrl, token)
        val targetPositionMs = resolveJamPositionMs(jam)

        withContext(Dispatchers.Main) {
            val player = MusicPlayerManager.getPlayer(this@MusicPlaybackService)
            val sameTrack = player.currentMediaItem?.mediaId == mediaItem.mediaId
            if (!sameTrack || player.playbackState == androidx.media3.common.Player.STATE_ENDED || player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                player.setMediaItem(mediaItem, targetPositionMs)
                player.prepare()
            } else if (kotlin.math.abs(player.currentPosition - targetPositionMs) > 1500L) {
                player.seekTo(targetPositionMs)
            }

            player.playWhenReady = jam.playback.isPlaying
            if (jam.playback.isPlaying) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    private fun resolveJamPositionMs(jam: Jam): Long {
        val playback = jam.playback
        val basePositionSec = (playback.positionSec ?: 0.0).coerceAtLeast(0.0)
        val startedAtSec = playback.startedAt ?: 0.0
        val resolvedPositionSec = if (playback.isPlaying && startedAtSec > 0.0) {
            val referenceSec = jam.serverTime?.takeIf { it > 0.0 } ?: (System.currentTimeMillis() / 1000.0)
            basePositionSec + (referenceSec - startedAtSec).coerceAtLeast(0.0)
        } else {
            basePositionSec
        }
        val durationMs = playback.durationSec?.times(1000L)?.takeIf { it > 0 }
        val rawPositionMs = (resolvedPositionSec * 1000.0).toLong().coerceAtLeast(0L)
        return durationMs?.let { rawPositionMs.coerceAtMost(it) } ?: rawPositionMs
    }

    private suspend fun buildServiceMediaItem(song: Song, apiBaseUrl: String, token: String?): MediaItem {
        val streamUrl = MusicPlayerManager.resolvePlaybackStreamUrl(
            context = this,
            song = song,
            apiBaseUrl = apiBaseUrl,
            token = token,
            preResolveSpecialStreamUrl = true
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album ?: "")
            .setArtworkUri(song.coverUrl?.let { android.net.Uri.parse(it) })
            .build()

        val builder = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id ?: "")
            .setMediaMetadata(metadata)

        resolveStreamMimeType(song.type, streamUrl)?.let(builder::setMimeType)
        if (streamUrl.contains(".m3u8") || streamUrl.startsWith("scstream:")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        return builder.build()
    }

    private fun resolveStreamMimeType(typeHint: String?, streamUrl: String): String? {
        val normalizedType = typeHint
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: streamUrl
                .substringAfterLast('.', "")
                .lowercase()
                .takeIf {
                    it.isNotBlank() &&
                        !streamUrl.startsWith("ytstream:") &&
                        !streamUrl.startsWith("scstream:")
                }

        return when (normalizedType) {
            null, "", "youtube", "soundcloud" -> null
            "flac", "audio/flac", "audio/x-flac", "audio/xflac" -> MimeTypes.AUDIO_FLAC
            "m4a", "mp4", "audio/m4a", "audio/mp4" -> MimeTypes.AUDIO_MP4
            "alac", "audio/alac" -> MimeTypes.AUDIO_ALAC
            "aac", "mp4a", "mp4a-latm", "audio/aac", "audio/mp4a-latm" -> MimeTypes.AUDIO_AAC
            "mp3", "audio/mpeg" -> MimeTypes.AUDIO_MPEG
            "wav", "audio/wav" -> MimeTypes.AUDIO_WAV
            "ogg", "audio/ogg" -> MimeTypes.AUDIO_OGG
            "opus", "audio/opus" -> MimeTypes.AUDIO_OPUS
            "webm", "audio/webm" -> MimeTypes.AUDIO_WEBM
            else -> if (normalizedType.startsWith("audio/")) normalizedType else "audio/$normalizedType"
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
