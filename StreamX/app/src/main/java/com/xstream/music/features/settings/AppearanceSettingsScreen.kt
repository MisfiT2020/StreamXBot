package com.xstream.music.features.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xstream.music.core.cache.DataCache

@Composable
fun AppearanceSettingsScreen(
    isDarkTheme: MutableState<Boolean>,
    isDynamicMaterial: MutableState<Boolean>,
    isCustomPicker: MutableState<Boolean>,
    isAmoledBlack: MutableState<Boolean>,
    modifier: Modifier = Modifier,
    isPlayerVisible: Boolean = false
) {
    val context = LocalContext.current
    val cardsEnabled = remember { mutableStateOf(DataCache.isCardsEnabled(context)) }
    val showHomeHeaderName = remember { mutableStateOf(DataCache.isHomeHeaderNameEnabled(context)) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            SettingsSectionHeader("THEME & COLORS")
            SettingsCard {
                SettingsToggle(
                    title = "Dark Mode toggle",
                    subtitle = "Turn on for dark mode, off for light mode.",
                    checked = isDarkTheme.value,
                    onCheckedChange = {
                        isDarkTheme.value = it
                        DataCache.setDarkThemeEnabled(context, it)
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                SettingsToggle(
                    title = "Dynamic Material UI toggle",
                    subtitle = "Use device Material 3 dynamic color palette for app colors.",
                    checked = isDynamicMaterial.value,
                    onCheckedChange = {
                        isDynamicMaterial.value = it
                        DataCache.setDynamicMaterialEnabled(context, it)
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                SettingsToggle(
                    title = "Amoled Black",
                    subtitle = "Use pure black background for AMOLED screens (Dark mode only).",
                    checked = isAmoledBlack.value && isDarkTheme.value,
                    onCheckedChange = {
                        isAmoledBlack.value = it
                        DataCache.setAmoledBlackEnabled(context, it)
                    },
                    enabled = isDarkTheme.value
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            SettingsSectionHeader("UI")
            SettingsCard {
                SettingsToggle(
                    title = "Cards",
                    subtitle = "Show grouped setting cards. Turn off for a flat list layout.",
                    checked = cardsEnabled.value,
                    onCheckedChange = {
                        cardsEnabled.value = it
                        DataCache.setCardsEnabled(context, it)
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.5.dp)
                SettingsToggle(
                    title = "Home header name",
                    subtitle = "Show your name beside the profile picture on the home screen.",
                    checked = showHomeHeaderName.value,
                    onCheckedChange = {
                        showHomeHeaderName.value = it
                        DataCache.setHomeHeaderNameEnabled(context, it)
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(if (isPlayerVisible) 100.dp else 24.dp)) }
    }
}
