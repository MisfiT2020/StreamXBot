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
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.core.utils.LogHelper
import com.xstream.music.ui.theme.AppTypography

private data class PendingJamLink(
    val jamId: String,
    val apiUrl: String? = null
)

private data class PendingPlaylistLink(
    val playlistId: String,
    val apiUrl: String? = null
)

private data class PendingAlbumLink(
    val albumId: String,
    val apiUrl: String? = null
)

private data class PendingTrackLink(
    val trackId: String,
    val apiUrl: String? = null
)

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBackgroundUsageRequest(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = packageUri })
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = packageUri })
    }

    intents.firstOrNull { intent ->
        runCatching {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

private fun normalizeDeepLinkApiUrl(raw: String?): String? {
    val normalized = raw?.let(::normalizeApiInput).orEmpty()
    return normalized.takeIf { it.isNotBlank() }
}

private fun inferApiUrlFromShareUri(uri: Uri?): String? {
    if (uri == null) return null
    val scheme = uri.scheme?.lowercase().orEmpty()
    val authority = uri.authority?.trim().orEmpty()
    if ((scheme == "http" || scheme == "https") && authority.isNotBlank()) {
        return normalizeDeepLinkApiUrl("$scheme://$authority")
    }
    return null
}

private fun extractJamLink(intent: Intent?): PendingJamLink? {
    if (intent == null) return null

    val action = intent.getStringExtra("action")
    val jamIdFromExtras = intent.getStringExtra("jam_id")?.trim().takeIf { !it.isNullOrBlank() }
    if (action == "join_jam" && jamIdFromExtras != null) {
        return PendingJamLink(
            jamId = jamIdFromExtras,
            apiUrl = normalizeDeepLinkApiUrl(intent.getStringExtra("api_url"))
        )
    }

    val data = intent.data ?: return null
    val host = data.host?.lowercase().orEmpty()
    val path = data.path.orEmpty()
    val jamId = when {
        data.scheme.equals("streamx", ignoreCase = true) && (host == "jam" || host == "join-jam") -> {
            data.lastPathSegment
        }
        path.startsWith("/share/jam/") || path.startsWith("/jam/") || path.startsWith("/join-jam/") -> {
            data.lastPathSegment
        }
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }

    val inferredApiUrl = if (
        path.startsWith("/share/jam/") ||
        path.startsWith("/jam/") ||
        path.startsWith("/join-jam/")
    ) inferApiUrlFromShareUri(data) else null

    return jamId?.let {
        PendingJamLink(
            jamId = it,
            apiUrl = normalizeDeepLinkApiUrl(data.getQueryParameter("api")) ?: inferredApiUrl
        )
    }
}

private fun extractPlaylistLink(intent: Intent?): PendingPlaylistLink? {
    if (intent == null) return null

    val data = intent.data ?: return null
    val host = data.host?.lowercase().orEmpty()
    val path = data.path.orEmpty()
    val playlistId = when {
        data.scheme.equals("streamx", ignoreCase = true) && host == "playlist" -> {
            data.lastPathSegment
        }
        path.startsWith("/share/playlists/") || path.startsWith("/playlists/") -> {
            data.lastPathSegment
        }
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }

    if (playlistId == null) return null

    val inferredApiUrl = if (
        path.startsWith("/share/playlists/") ||
        path.startsWith("/playlists/")
    ) inferApiUrlFromShareUri(data) else null

    return PendingPlaylistLink(
        playlistId = playlistId,
        apiUrl = normalizeDeepLinkApiUrl(data.getQueryParameter("api")) ?: inferredApiUrl
    )
}

private fun extractAlbumLink(intent: Intent?): PendingAlbumLink? {
    if (intent == null) return null

    val data = intent.data ?: return null
    val host = data.host?.lowercase().orEmpty()
    val path = data.path.orEmpty()
    val albumId = when {
        data.scheme.equals("streamx", ignoreCase = true) && host == "album" -> {
            data.lastPathSegment
        }
        path.startsWith("/share/albums/") || path.startsWith("/albums/") -> {
            data.lastPathSegment
        }
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }

    if (albumId == null) return null

    val inferredApiUrl = if (
        path.startsWith("/share/albums/") ||
        path.startsWith("/albums/")
    ) inferApiUrlFromShareUri(data) else null

    return PendingAlbumLink(
        albumId = albumId,
        apiUrl = normalizeDeepLinkApiUrl(data.getQueryParameter("api")) ?: inferredApiUrl
    )
}

private fun extractTrackLink(intent: Intent?): PendingTrackLink? {
    if (intent == null) return null

    val data = intent.data ?: return null
    val host = data.host?.lowercase().orEmpty()
    val path = data.path.orEmpty()
    val trackId = when {
        data.scheme.equals("streamx", ignoreCase = true) && host == "track" -> {
            data.lastPathSegment
        }
        path.startsWith("/share/tracks/") || path.startsWith("/tracks/") -> {
            data.lastPathSegment
        }
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }

    if (trackId == null) return null

    val inferredApiUrl = if (
        path.startsWith("/share/tracks/") ||
        path.startsWith("/tracks/")
    ) inferApiUrlFromShareUri(data) else null

    return PendingTrackLink(
        trackId = trackId,
        apiUrl = normalizeDeepLinkApiUrl(data.getQueryParameter("api")) ?: inferredApiUrl
    )
}

@UnstableApi
@Composable
fun MusicApp() {
    val context = LocalContext.current
    val isDarkTheme = remember { mutableStateOf(DataCache.isDarkThemeEnabled(context)) }
    val isAmoledBlack = remember { mutableStateOf(DataCache.isAmoledBlackEnabled(context)) }
    val isDynamicMaterial = remember { mutableStateOf(DataCache.isDynamicMaterialEnabled(context)) }
    val isCustomPicker = remember { mutableStateOf(false) }

    val pendingJamLink = remember { mutableStateOf<PendingJamLink?>(null) }
    val pendingPlaylistLink = remember { mutableStateOf<PendingPlaylistLink?>(null) }
    val pendingAlbumLink = remember { mutableStateOf<PendingAlbumLink?>(null) }
    val pendingTrackLink = remember { mutableStateOf<PendingTrackLink?>(null) }

    
    LaunchedEffect(Unit) {
        if (DataCache.isDeveloperLogsEnabled(context)) {
            val plantedTrees = timber.log.Timber.forest()
            if (plantedTrees.none { it::class == timber.log.Timber.DebugTree::class }) {
                timber.log.Timber.plant(timber.log.Timber.DebugTree())
            }
            if (plantedTrees.none { it::class == LogHelper.FileLoggingTree::class }) {
                timber.log.Timber.plant(LogHelper.FileLoggingTree(context))
            }
        }
        DownloadHelper.init(context)
        val activity = context as? Activity

        val initialJamLink = extractJamLink(activity?.intent)
        if (initialJamLink != null) {
            pendingJamLink.value = initialJamLink
        }

        val initialPlaylistLink = extractPlaylistLink(activity?.intent)
        if (initialPlaylistLink != null) {
            pendingPlaylistLink.value = initialPlaylistLink
        }

        val initialAlbumLink = extractAlbumLink(activity?.intent)
        if (initialAlbumLink != null) {
            pendingAlbumLink.value = initialAlbumLink
        }

        val initialTrackLink = extractTrackLink(activity?.intent)
        if (initialTrackLink != null) {
            pendingTrackLink.value = initialTrackLink
        }

        if (!DataCache.hasShownBackgroundUsagePrompt(context)) {
            DataCache.setBackgroundUsagePromptShown(context, true)
            if (isBatteryOptimizationIgnored(context)) {
                return@LaunchedEffect
            } else {
                openBackgroundUsageRequest(context)
            }
        }
    }

    
    androidx.compose.runtime.DisposableEffect(context) {
        val activity = context as? androidx.activity.ComponentActivity
        val listener = androidx.core.util.Consumer<Intent> { intent ->
            val jamLink = extractJamLink(intent)
            if (jamLink != null) {
                pendingJamLink.value = jamLink
            }

            val playlistLink = extractPlaylistLink(intent)
            if (playlistLink != null) {
                pendingPlaylistLink.value = playlistLink
            }

            val albumLink = extractAlbumLink(intent)
            if (albumLink != null) {
                pendingAlbumLink.value = albumLink
            }

            val trackLink = extractTrackLink(intent)
            if (trackLink != null) {
                pendingTrackLink.value = trackLink
            }
        }
        activity?.addOnNewIntentListener(listener)
        onDispose {
            activity?.removeOnNewIntentListener(listener)
        }
    }

    MusicAppTheme(
        darkTheme = isDarkTheme.value,
        dynamicMaterial = isDynamicMaterial.value,
        amoledBlack = isAmoledBlack.value
    ) {
        MusicScreen(
            isDarkTheme = isDarkTheme,
            isDynamicMaterial = isDynamicMaterial,
            isCustomPicker = isCustomPicker,
            isAmoledBlack = isAmoledBlack,
            initialJamId = pendingJamLink.value?.jamId,
            initialJamApiUrl = pendingJamLink.value?.apiUrl,
            onJamJoined = { pendingJamLink.value = null },
            initialPlaylistId = pendingPlaylistLink.value?.playlistId,
            initialPlaylistApiUrl = pendingPlaylistLink.value?.apiUrl,
            onPlaylistOpened = { pendingPlaylistLink.value = null },
            initialAlbumId = pendingAlbumLink.value?.albumId,
            initialAlbumApiUrl = pendingAlbumLink.value?.apiUrl,
            onAlbumOpened = { pendingAlbumLink.value = null },
            initialTrackId = pendingTrackLink.value?.trackId,
            initialTrackApiUrl = pendingTrackLink.value?.apiUrl,
            onTrackOpened = { pendingTrackLink.value = null }
        )
    }}

@Composable
fun MusicAppTheme(
    darkTheme: Boolean = true,
    dynamicMaterial: Boolean = false,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    fun accentTone(base: Color, accent: Color, amount: Float): Color = lerp(base, accent, amount)

    val baseColorScheme = if (dynamicMaterial && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (darkTheme) {
        darkColorScheme(
            background = if (amoledBlack) Color.Black else Color(0xFF121212),
            surface = if (amoledBlack) Color.Black else Color(0xFF1E1E1E),
            primary = Color(0xFFF13950),
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            background = Color(0xFFF5F5F5),
            surface = Color.White,
            primary = Color(0xFFF13950),
            onBackground = Color(0xFF121212),
            onSurface = Color(0xFF121212)
        )
    }
    val colorScheme = if (darkTheme) {
        baseColorScheme.copy(
            onPrimary = Color.White,
            onPrimaryContainer = Color.White,
            onSecondary = Color.White,
            onSecondaryContainer = Color.White,
            onTertiary = Color.White,
            onTertiaryContainer = Color.White
        )
    } else {
        baseColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography
    ) {
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(
            LocalOverscrollConfiguration provides null,
            content = content
        )
    }
}
