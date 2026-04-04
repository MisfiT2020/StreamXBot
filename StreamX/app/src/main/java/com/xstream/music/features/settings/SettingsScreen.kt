package com.xstream.music.features.settings

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.cache.ImageMemoryCache

@Composable
fun SettingsScreen(
    isAmoledBlack: MutableState<Boolean>,
    onBack: () -> Unit,
    onAudioSettingsClick: () -> Unit = {},
    onDeveloperSettingsClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    isPlayerVisible: Boolean = false
) {
    val context = LocalContext.current
    
    val homeProvider = DataCache.homeProvider.collectAsState()
    val saveCache = remember { mutableStateOf(DataCache.isSaveCacheEnabled(context)) }
    val showDownloadButton = remember { mutableStateOf(DataCache.isShowDownloadButtonEnabled(context)) }
    val sudoMode = remember { mutableStateOf(DataCache.isSudoModeEnabled(context)) }
    val devMode = remember { mutableStateOf(DataCache.isDevModeEnabled(context)) }
    val showUpdatedPlaylistsSection = remember { mutableStateOf(DataCache.isShowUpdatedPlaylistsSectionEnabled(context)) }
    val showLatestSongsSection = remember { mutableStateOf(DataCache.isShowLatestSongsSectionEnabled(context)) }
    val showRandomMixSection = remember { mutableStateOf(DataCache.isShowRandomMixSectionEnabled(context)) }
    val showPlaylistsSection = remember { mutableStateOf(DataCache.isShowPlaylistsSectionEnabled(context)) }
    val showYourAlbumsSection = remember { mutableStateOf(DataCache.isShowYourAlbumsSectionEnabled(context)) }
    val showFriendsSection = remember { mutableStateOf(DataCache.isShowFriendsSectionEnabled(context)) }
    val disableExoPlayerAnimation = remember { mutableStateOf(DataCache.isExoPlayerAnimationDisabled(context)) }
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val globalActiveColor = if (isDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val globalActiveContentColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            SettingsSectionHeader("STORAGE")
            SettingsCard {
                SettingsToggle(
                    title = "Save cache",
                    subtitle = "Store latest tracks and playlists (refreshes every 3h).",
                    checked = saveCache.value,
                    onCheckedChange = {
                        saveCache.value = it
                        DataCache.setSaveCacheEnabled(context, it)
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                SettingsAction(
                    title = "Clear cache",
                    subtitle = "Enable Save cache to clear stored playlists and tracks.",
                    enabled = saveCache.value,
                    actionText = "Clear",
                    onClick = {
                        DataCache.clearCache(context)
                        ImageMemoryCache.clear(context)
                        android.widget.Toast.makeText(context, "Cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("DOWNLOADS")
            SettingsCard {
                SettingsToggle(
                    title = "Show download button",
                    subtitle = "Show the download arrow next to tracks in lists.",
                    checked = showDownloadButton.value,
                    onCheckedChange = {
                        showDownloadButton.value = it
                        DataCache.setShowDownloadButtonEnabled(context, it)
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("CONTENT PROVIDER")
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Home Screen Provider",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Current: ${homeProvider.value.replaceFirstChar { it.uppercase() }}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val options = listOf("streamx" to "StreamX", "youtube" to "YouTube", "soundcloud" to "SoundCloud")
                        options.forEach { (providerId, providerName) ->
                            val selected = homeProvider.value == providerId
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) globalActiveColor else Color.Transparent)
                                    .clickable { DataCache.setProvider(context, providerId) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = providerName,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = if (selected) globalActiveContentColor else MaterialTheme.colorScheme.onSurfaceVariant
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
                    title = "Disable animation on EX player",
                    subtitle = "Disable thumbnail sliding transition in Lyrics and Next Info.",
                    checked = disableExoPlayerAnimation.value,
                    onCheckedChange = {
                        disableExoPlayerAnimation.value = it
                        DataCache.setExoPlayerAnimationDisabled(context, it)
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("HOME SECTIONS")
            SettingsCard {
                if (homeProvider.value == "youtube") {
                    val showYouTubeHomeChips = remember { mutableStateOf(DataCache.isShowYouTubeHomeChipsEnabled(context)) }
                    val showYouTubeAccountPlaylists = remember { mutableStateOf(DataCache.isShowYouTubeAccountPlaylistsEnabled(context)) }
                    
                    SettingsToggle(
                        title = "Quick Filter Chips",
                        subtitle = "Show the YouTube home chip filters row.",
                        checked = showYouTubeHomeChips.value,
                        onCheckedChange = {
                            showYouTubeHomeChips.value = it
                            DataCache.setShowYouTubeHomeChipsEnabled(context, it)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                    SettingsToggle(
                        title = "Your YouTube Playlists",
                        subtitle = "Show account and playlist shortcuts at the top.",
                        checked = showYouTubeAccountPlaylists.value,
                        onCheckedChange = {
                            showYouTubeAccountPlaylists.value = it
                            DataCache.setShowYouTubeAccountPlaylistsEnabled(context, it)
                        }
                    )
                    
                    
                    val discoveredSections = remember { mutableStateOf(DataCache.getAllDiscoveredYouTubeSections(context).sorted()) }
                    val sectionStates = remember {
                        mutableStateMapOf<String, Boolean>().apply {
                            discoveredSections.value.forEach { section ->
                                this[section] = DataCache.isYouTubeSectionEnabled(context, section)
                            }
                        }
                    }
                    
                    if (discoveredSections.value.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                        
                        var showAllSections by remember { mutableStateOf(false) }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAllSections = !showAllSections }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Individual Sections",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Customize which sections appear (${discoveredSections.value.size} found)",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Icon(
                                imageVector = if (showAllSections) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (showAllSections) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        
                        if (showAllSections) {
                            discoveredSections.value.forEachIndexed { index, section ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), thickness = 0.5.dp)
                                }
                                SettingsToggle(
                                    title = section,
                                    subtitle = null,
                                    checked = sectionStates[section] ?: true,
                                    onCheckedChange = { enabled ->
                                        sectionStates[section] = enabled
                                        DataCache.setYouTubeSectionEnabled(context, section, enabled)
                                    }
                                )
                            }
                            
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        DataCache.resetYouTubeSectionPreferences(context)
                                        discoveredSections.value = emptyList()
                                        sectionStates.clear()
                                    }
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Reset Section Preferences",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFFE24A5A)
                                    )
                                    Text(
                                        text = "Clear all section preferences and rediscover",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    SettingsToggle(
                        title = "Updated Playlists",
                        subtitle = "Show the Updated Playlists section on Home.",
                        checked = showUpdatedPlaylistsSection.value,
                        onCheckedChange = {
                            showUpdatedPlaylistsSection.value = it
                            DataCache.setShowUpdatedPlaylistsSectionEnabled(context, it)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                    SettingsToggle(
                        title = "Latest Songs",
                        subtitle = "Show the Latest Songs section on Home.",
                        checked = showLatestSongsSection.value,
                        onCheckedChange = {
                            showLatestSongsSection.value = it
                            DataCache.setShowLatestSongsSectionEnabled(context, it)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                    SettingsToggle(
                        title = "Random Mix",
                        subtitle = "Show the Random Mix section on Home.",
                        checked = showRandomMixSection.value,
                        onCheckedChange = {
                            showRandomMixSection.value = it
                            DataCache.setShowRandomMixSectionEnabled(context, it)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                    SettingsToggle(
                        title = "Playlists",
                        subtitle = "Show the Playlists section on Home.",
                        checked = showPlaylistsSection.value,
                        onCheckedChange = {
                            showPlaylistsSection.value = it
                            DataCache.setShowPlaylistsSectionEnabled(context, it)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                    SettingsToggle(
                        title = "Your Albums",
                        subtitle = "Show the Your Albums section on Home.",
                        checked = showYourAlbumsSection.value,
                        onCheckedChange = {
                            showYourAlbumsSection.value = it
                            DataCache.setShowYourAlbumsSectionEnabled(context, it)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                    SettingsToggle(
                        title = "Friends",
                        subtitle = "Show the Friends section on Home.",
                        checked = showFriendsSection.value,
                        onCheckedChange = {
                            showFriendsSection.value = it
                            DataCache.setShowFriendsSectionEnabled(context, it)
                        }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("SYSTEM")
            SettingsCard {
                SettingsToggle(
                    title = "Developer Mode",
                    subtitle = "Enable advanced tools and reveal Developer Settings.",
                    checked = devMode.value,
                    onCheckedChange = {
                        devMode.value = it
                        DataCache.setDevModeEnabled(context, it)
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                SettingsToggle(
                    title = "Sudo Mode",
                    subtitle = "Enable track deletion",
                    checked = sudoMode.value,
                    onCheckedChange = {
                        sudoMode.value = it
                        DataCache.setSudoModeEnabled(context, it)
                    }
                )
                if (devMode.value) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                    SettingsAction(
                        title = "Developer Settings",
                        subtitle = "Internal debugging and logging tools.",
                        enabled = true,
                        actionText = "Open",
                        onClick = onDeveloperSettingsClick
                    )
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("ABOUT")
            SettingsCard {
                SettingsAction(
                    title = "About streamX",
                    subtitle = "Version, updates & socials",
                    enabled = true,
                    actionText = "Open",
                    onClick = onAboutClick
                )
            }
        }
        
        item { Spacer(modifier = Modifier.height(if (isPlayerVisible) 100.dp else 24.dp)) }
    }
}

@Composable
fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        DataCache.isCardsEnabled(context)
    }
    val cardsEnabled by DataCache.cardsEnabled.collectAsState()

    if (!cardsEnabled) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
        return
    }

    val cardShape = RoundedCornerShape(16.dp)
    val cardColor =
        MaterialTheme.colorScheme.onSurface
            .copy(alpha = 0.04f)
            .compositeOver(MaterialTheme.colorScheme.surface)

    Surface(
        color = cardColor,
        shape = cardShape,
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                shape = cardShape
            )
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    checkedTrackColor: Color? = null
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val activeTrackColor = if (isDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val activeThumbColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(text = subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = activeThumbColor,
                checkedTrackColor = checkedTrackColor ?: activeTrackColor,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = Color.Transparent,
                disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
        )
    }
}

@Composable
fun SettingsAction(
    title: String,
    subtitle: String,
    enabled: Boolean,
    actionText: String,
    onClick: () -> Unit
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val actionContainerColor = if (isDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val actionContentColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) actionContainerColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                contentColor = if (enabled) actionContentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = actionText,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun SettingsNumberInput(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value.ifBlank { "0" },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                modifier = Modifier.widthIn(min = 20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Increase",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp).clickable { 
                        val next = (value.toIntOrNull() ?: 0) + 1
                        onValueChange(next.toString())
                    }
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Decrease",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp).clickable { 
                        val next = (value.toIntOrNull() ?: 0) - 1
                        onValueChange(next.toString())
                    }
                )
            }
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit, isAmoledBlack: Boolean = false) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (isAmoledBlack) Color.Black else MaterialTheme.colorScheme.surface,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text(text = "About streamX", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Logo",
                    modifier = Modifier.size(72.dp),
                    tint = Color.Unspecified
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "streamX",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Version 1.0.0",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = {
                        android.widget.Toast.makeText(context, "Checking for updates...", android.widget.Toast.LENGTH_SHORT).show()
                        val updateUri = "https://github.com/MisfiT2020/streamX/releases"
                        try {
                            uriHandler.openUri(updateUri)
                        } catch (e: Exception) {
                            
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Check for updates")
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                ) {
                    IconButton(onClick = { uriHandler.openUri("https://github.com/MisfiT2020") }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                    IconButton(onClick = { uriHandler.openUri("https://t.me/RaidenEiSupport") }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_telegram),
                            contentDescription = "Telegram",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    )
}
