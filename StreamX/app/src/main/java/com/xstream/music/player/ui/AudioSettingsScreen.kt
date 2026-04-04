package com.xstream.music.player.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import kotlinx.coroutines.launch
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.features.settings.SettingsAction
import com.xstream.music.features.settings.SettingsCard
import com.xstream.music.features.settings.SettingsNumberInput
import com.xstream.music.features.settings.SettingsSectionHeader
import com.xstream.music.features.settings.SettingsToggle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    isPlayerVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showRebuildDialog by remember { mutableStateOf(false) }
    var rebuildResult by remember { mutableStateOf<String?>(null) }
    var isRebuilding by remember { mutableStateOf(false) }

    val parallelEnabled = remember { mutableStateOf(DataCache.isParallelDownloadsEnabled(context)) }
    val parallelCount = remember { mutableStateOf(DataCache.getParallelDownloadsCount(context).toString()) }
    val downloadFolder = remember { mutableStateOf(DataCache.getDownloadFolder(context) ?: "Default") }
    
    val aggressiveStreaming = remember { mutableStateOf(DataCache.isAggressiveStreamingEnabled(context)) }
    val streamingMode = remember { mutableStateOf(DataCache.getStreamingMode(context)) }
    val audioQuality = remember { mutableStateOf(PlaybackPreferences.getAudioQuality(context)) }
    val staticBackgroundCover = remember { mutableStateOf(DataCache.isStaticBackgroundCoverEnabled(context)) }
    val lyricsProvider = remember { mutableStateOf(PlaybackPreferences.getLyricsProvider(context)) }
    val autoFetchLyrics = remember { mutableStateOf(PlaybackPreferences.isAutoFetchLyricsEnabled(context)) }
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val globalActiveColor = if (isDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val globalActiveContentColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer

    val formatFolder: (String) -> String = { uriString ->
        if (uriString == "Default") "Default"
        else try {
            android.net.Uri.parse(uriString).lastPathSegment?.substringAfterLast(":") ?: uriString
        } catch (e: Exception) {
            uriString
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val contentResolver = context.contentResolver
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(it, takeFlags)
            
            val path = it.toString()
            DataCache.setDownloadFolder(context, path)
            downloadFolder.value = path
            
            
            DownloadHelper.syncDownloadedSongs(context)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            SettingsSectionHeader("STREAMING")
            SettingsCard {
                AudioQualitySegmentedSelector(
                    selectedQuality = audioQuality.value,
                    onQualitySelected = { quality ->
                        audioQuality.value = quality
                        PlaybackPreferences.setAudioQuality(context, quality)
                    }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SettingsToggle(
                    title = "Aggressive Streaming",
                    subtitle = "Fetch the full track while playing to enable faster seeking.",
                    checked = aggressiveStreaming.value,
                    onCheckedChange = {
                        aggressiveStreaming.value = it
                        DataCache.setAggressiveStreamingEnabled(context, it)
                    },
                    checkedTrackColor = globalActiveColor
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Streaming Mode", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = if (aggressiveStreaming.value) "Aggressive overrides mode." else "Balanced is recommended for most users.", 
                            color = MaterialTheme.colorScheme.onSurfaceVariant, 
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val modes = listOf("Balanced", "Saver")
                        modes.forEach { mode ->
                            val isSelected = streamingMode.value == mode
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected && !aggressiveStreaming.value) globalActiveColor
                                        else Color.Transparent
                                    )
                                    .then(
                                        if (!aggressiveStreaming.value) {
                                            Modifier.clickable {
                                                streamingMode.value = mode
                                                DataCache.setStreamingMode(context, mode)
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode,
                                    color = if (isSelected && !aggressiveStreaming.value) globalActiveContentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (aggressiveStreaming.value) 0.45f else 1f),
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected && !aggressiveStreaming.value) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("PLAYER")
            SettingsCard {
                SettingsToggle(
                    title = "Static Background Cover",
                    subtitle = "Show a static background color in the expanded player instead of dynamic album art color.",
                    checked = staticBackgroundCover.value,
                    onCheckedChange = {
                        staticBackgroundCover.value = it
                        DataCache.setStaticBackgroundCoverEnabled(context, it)
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("LYRICS")
            SettingsCard {
                SettingsToggle(
                    title = "Auto Fetch Lyrics",
                    subtitle = "Try all providers until one returns lyrics.",
                    checked = autoFetchLyrics.value,
                    onCheckedChange = {
                        autoFetchLyrics.value = it
                        PlaybackPreferences.setAutoFetchLyricsEnabled(context, it)
                    },
                    checkedTrackColor = globalActiveColor
                )

                if (!autoFetchLyrics.value) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    LyricsProviderSelector(
                        selectedProvider = lyricsProvider.value,
                        onProviderSelected = { provider ->
                            lyricsProvider.value = provider
                            PlaybackPreferences.setLyricsProvider(context, provider)
                        }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("DOWNLOADS")
            SettingsCard {
                SettingsToggle(
                    title = "Enable parallel downloads",
                    subtitle = "Download multiple tracks at once. Off will download tracks one by one.",
                    checked = parallelEnabled.value,
                    onCheckedChange = {
                        parallelEnabled.value = it
                        DataCache.setParallelDownloadsEnabled(context, it)
                        DownloadHelper.updateParallelSettings(context)
                    }
                )
                
                if (parallelEnabled.value) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), 
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    SettingsNumberInput(
                        title = "Parallel download count",
                        subtitle = "Number of simultaneous downloads allowed.",
                        value = parallelCount.value,
                        onValueChange = {
                            parallelCount.value = it
                            val count = it.toIntOrNull() ?: 1
                            val clampedCount = count.coerceIn(1, 10)
                            DataCache.setParallelDownloadsCount(context, clampedCount)
                            DownloadHelper.updateParallelSettings(context)
                        }
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), 
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                SettingsAction(
                    title = "Download Folder",
                    subtitle = "Current: ${formatFolder(downloadFolder.value)}",
                    actionText = "Change",
                    enabled = true,
                    onClick = {
                        folderPickerLauncher.launch(null)
                    }
                )
                
                if (downloadFolder.value != "Default") {
                    Text(
                        text = "Reset to Default",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                DataCache.setDownloadFolder(context, null)
                                downloadFolder.value = "Default"
                                
                                
                                DownloadHelper.syncDownloadedSongs(context)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("ADMIN")
            SettingsCard {
                SettingsAction(
                    title = "Rebuild Albums",
                    subtitle = "Rebuild existing tracks into album groups.",
                    actionText = "Rebuild",
                    enabled = !isRebuilding,
                    onClick = {
                        isRebuilding = true
                        rebuildResult = null
                        showRebuildDialog = true
                        coroutineScope.launch {
                            val apiUrl = ApiPreferences.getApiUrl(context)
                            val token = AuthPreferences.getUser(context)?.token
                            val result = rebuildAlbums(apiUrl, context, token)
                            isRebuilding = false
                            if (result != null) {
                                rebuildResult = """
                                    Status: ${if (result.optBoolean("ok")) "Success" else "Failed"}
                                    Tracks Scanned: ${result.optInt("scanned_tracks")}
                                    Tracks Updated: ${result.optInt("tracks_updated")}
                                    Albums Rebuilt: ${result.optBoolean("albums_rebuilt")}
                                    Album Groups: ${result.optInt("album_groups")}
                                    Albums Upserted: ${result.optInt("albums_upserted")}
                                """.trimIndent()
                            } else {
                                rebuildResult = "Failed to connect to server or unauthorized."
                            }
                        }
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(if (isPlayerVisible) 100.dp else 24.dp)) }
    }

    if (showRebuildDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isRebuilding) showRebuildDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Rebuild Albums",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (isRebuilding) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Rebuilding in progress...",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    } else {
                        Text(
                            text = rebuildResult ?: "Unknown error",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { showRebuildDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Close", color = MaterialTheme.colorScheme.surface)
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun AudioQualitySegmentedSelector(
    selectedQuality: AudioQuality,
    onQualitySelected: (AudioQuality) -> Unit
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val globalActiveColor = if (isDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val globalActiveContentColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(globalActiveColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = globalActiveContentColor
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Streaming Quality", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "YouTube audio preference",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val options = listOf(AudioQuality.LOW, AudioQuality.AUTO, AudioQuality.HIGH)
            options.forEach { quality ->
                val selected = selectedQuality == quality
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) globalActiveColor else Color.Transparent)
                        .clickable { onQualitySelected(quality) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = quality.name.lowercase().replaceFirstChar { it.uppercase() },
                        color = if (selected) globalActiveContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsProviderSelector(
    selectedProvider: String,
    onProviderSelected: (String) -> Unit
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val globalActiveColor = if (isDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val globalActiveContentColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(globalActiveColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = globalActiveContentColor
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Lyrics Provider", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose the lyrics source",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val options = listOf(
                "lrclib" to "LrcLib",
                "betterlyrics" to "BetterLyrics",
                "rclyricsband" to "RcLyricsBand"
            )
            options.forEach { (provider, label) ->
                val selected = selectedProvider == provider
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) globalActiveColor else Color.Transparent)
                        .clickable { onProviderSelected(provider) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) globalActiveContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
