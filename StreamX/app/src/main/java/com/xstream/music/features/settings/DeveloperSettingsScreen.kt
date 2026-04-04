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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.utils.LogHelper

@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    isPlayerVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logsEnabled = remember { mutableStateOf(DataCache.isDeveloperLogsEnabled(context)) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            SettingsSectionHeader("LOGGING")
            SettingsCard {
                SettingsToggle(
                    title = "Enable logs",
                    subtitle = "Enable Timber logging for debugging. Requires app restart to take full effect.",
                    checked = logsEnabled.value,
                    onCheckedChange = {
                        logsEnabled.value = it
                        DataCache.setDeveloperLogsEnabled(context, it)
                    }
                )
                
                if (logsEnabled.value) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), 
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    SettingsAction(
                        title = "Export Logs",
                        subtitle = "Share the internal log file.",
                        enabled = true,
                        actionText = "Export",
                        onClick = {
                            LogHelper.exportLogs(context)
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), 
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    SettingsAction(
                        title = "Clear Logs",
                        subtitle = "Delete all stored debug logs.",
                        enabled = true,
                        actionText = "Clear",
                        onClick = {
                            LogHelper.clearLogs(context)
                        }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(if (isPlayerVisible) 100.dp else 24.dp)) }
    }
}
