package com.xstream.music.core.cache

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
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import com.xstream.music.data.model.FriendSettings
import com.xstream.music.data.model.Playlist
import com.xstream.music.data.model.Song

object DataCache {
    val favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val savedAlbumIds = MutableStateFlow<Set<String>>(emptySet())
    val savedYouTubePlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val cacheCleared = MutableStateFlow<Long>(0)
    val cardsEnabled = MutableStateFlow(true)
    
    val sudoSelectedTrackIds = MutableStateFlow<Set<String>>(emptySet())

    private const val PREFS_NAME = "streamx_data_cache"
    private const val KEY_LATEST_SONGS = "latest_songs"
    private const val KEY_RANDOM_MIX = "random_mix"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_USER_PLAYLISTS = "user_playlists"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val KEY_FAVORITE_IDS = "favorite_ids"
    
    private const val KEY_SAVE_CACHE = "save_cache"
    private const val KEY_AMOLED_BLACK = "amoled_black"
    private const val KEY_DARK_THEME_ENABLED = "dark_theme_enabled"
    private const val KEY_DYNAMIC_MATERIAL_ENABLED = "dynamic_material_enabled"
    private const val KEY_WEB_SONGLIST = "web_songlist"
    private const val KEY_RED_SELECTOR = "red_selector"
    private const val KEY_AUTO_HIDE_PLAYER = "auto_hide_player"
    private const val KEY_NAVBAR_OFFSET = "navbar_offset"
    private const val KEY_SHOW_DOWNLOAD_BUTTON = "show_download_button"
    private const val KEY_PARALLEL_DOWNLOADS_ENABLED = "parallel_downloads_enabled"
    private const val KEY_PARALLEL_DOWNLOADS_COUNT = "parallel_downloads_count"
    private const val KEY_DOWNLOAD_FOLDER = "download_folder"
    private const val KEY_AGGRESSIVE_STREAMING = "aggressive_streaming"
    private const val KEY_STREAMING_MODE = "streaming_mode"
    private const val KEY_DEVELOPER_LOGS_ENABLED = "developer_logs_enabled"
    private const val KEY_DEV_MODE_ENABLED = "dev_mode_enabled"
    private const val KEY_SUDO_MODE_ENABLED = "sudo_mode_enabled"
    private const val KEY_SHOW_SECTION_UPDATED_PLAYLISTS = "show_section_updated_playlists"
    private const val KEY_SHOW_SECTION_LATEST_SONGS = "show_section_latest_songs"
    private const val KEY_SHOW_SECTION_RANDOM_MIX = "show_section_random_mix"
    private const val KEY_SHOW_SECTION_PLAYLISTS = "show_section_playlists"
    private const val KEY_SHOW_SECTION_YOUR_ALBUMS = "show_section_your_albums"
    private const val KEY_SHOW_SECTION_FRIENDS = "show_section_friends"
    private const val KEY_SHOW_YT_HOME_CHIPS = "show_yt_home_chips"
    private const val KEY_SHOW_YT_ACCOUNT_PLAYLISTS = "show_yt_account_playlists"
    private const val KEY_SHOW_YT_SONG_SECTIONS = "show_yt_song_sections"
    private const val KEY_SHOW_YT_BROWSE_SECTIONS = "show_yt_browse_sections"
    private const val KEY_CARDS_ENABLED = "cards_enabled"
    private const val KEY_SHOW_HOME_HEADER_NAME = "show_home_header_name"
    private const val KEY_BACKGROUND_USAGE_PROMPT_SHOWN = "background_usage_prompt_shown"

    private const val KEY_YT_SECTION_PREFIX = "yt_section_"
    private const val KEY_YT_ENABLED_SECTIONS = "yt_enabled_sections"

    fun isDevModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEV_MODE_ENABLED, com.xstream.music.BuildConfig.DEBUG)
    }

    fun setDevModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEV_MODE_ENABLED, enabled).apply()
    }
    private const val KEY_STATIC_BACKGROUND_COVER = "static_background_cover"
    private const val KEY_DISABLE_EXOPLAYER_ANIMATION = "disable_exoplayer_animation"
    private const val KEY_FRIEND_SHARE_LISTENING = "friend_share_listening"
    private const val KEY_FRIEND_ALLOW_JAM_INVITES = "friend_allow_jam_invites"

    private fun getLastUpdateTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATE, 0L)
    }

    fun isCacheExpired(context: Context): Boolean {
        val lastUpdate = getLastUpdateTime(context)
        return System.currentTimeMillis() - lastUpdate > 3 * 60 * 60 * 1000
    }


    fun isSaveCacheEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAVE_CACHE, true)
    }

    fun setSaveCacheEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAVE_CACHE, enabled).apply()
    }

    private const val KEY_SEARCH_SOURCE = "search_source"
    val homeProvider = MutableStateFlow("streamx")
    
    fun getProvider(context: Context): String {
        val provider = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SEARCH_SOURCE, "streamx") ?: "streamx"
        homeProvider.value = provider
        return provider
    }
    
    fun setProvider(context: Context, provider: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SEARCH_SOURCE, provider).apply()
        homeProvider.value = provider
    }

    fun isWebSonglistEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WEB_SONGLIST, true)
    }

    fun setWebSonglistEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WEB_SONGLIST, enabled).apply()
    }

    fun isRedSelectorEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RED_SELECTOR, false)
    }

    fun setRedSelectorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RED_SELECTOR, enabled).apply()
    }

    fun isAutoHidePlayerEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_HIDE_PLAYER, false)
    }

    fun setAutoHidePlayerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_HIDE_PLAYER, enabled).apply()
    }

    fun getNavbarOffset(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_NAVBAR_OFFSET, 0)
    }

    fun setNavbarOffset(context: Context, offset: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_NAVBAR_OFFSET, offset).apply()
    }

    fun isAmoledBlackEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AMOLED_BLACK, false) && isDarkThemeEnabled(context)
    }

    fun setAmoledBlackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AMOLED_BLACK, enabled).apply()
    }

    fun isDarkThemeEnabled(context: Context): Boolean {
        val defaultDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_THEME_ENABLED, defaultDark)
    }

    fun setDarkThemeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK_THEME_ENABLED, enabled).apply()
    }

    fun isDynamicMaterialEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DYNAMIC_MATERIAL_ENABLED, false)
    }

    fun setDynamicMaterialEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DYNAMIC_MATERIAL_ENABLED, enabled).apply()
    }

    fun isCardsEnabled(context: Context): Boolean {
        val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CARDS_ENABLED, true)
        cardsEnabled.value = enabled
        return enabled
    }

    fun setCardsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CARDS_ENABLED, enabled).apply()
        cardsEnabled.value = enabled
    }

    fun isHomeHeaderNameEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_HOME_HEADER_NAME, true)
    }

    fun setHomeHeaderNameEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_HOME_HEADER_NAME, enabled).apply()
    }

    fun hasShownBackgroundUsagePrompt(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND_USAGE_PROMPT_SHOWN, false)
    }

    fun setBackgroundUsagePromptShown(context: Context, shown: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BACKGROUND_USAGE_PROMPT_SHOWN, shown).apply()
    }

    fun isShowDownloadButtonEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_DOWNLOAD_BUTTON, true)
    }

    fun setShowDownloadButtonEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_DOWNLOAD_BUTTON, enabled).apply()
    }

    fun isParallelDownloadsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PARALLEL_DOWNLOADS_ENABLED, true)
    }

    fun setParallelDownloadsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PARALLEL_DOWNLOADS_ENABLED, enabled).apply()
    }

    fun getParallelDownloadsCount(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PARALLEL_DOWNLOADS_COUNT, 3)
    }

    fun setParallelDownloadsCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PARALLEL_DOWNLOADS_COUNT, count).apply()
    }

    fun getDownloadFolder(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOAD_FOLDER, null)
    }

    fun setDownloadFolder(context: Context, folder: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DOWNLOAD_FOLDER, folder).apply()
    }

    fun isAggressiveStreamingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGGRESSIVE_STREAMING, false)
    }

    fun setAggressiveStreamingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AGGRESSIVE_STREAMING, enabled).apply()
    }

    fun getStreamingMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STREAMING_MODE, "Balanced") ?: "Balanced"
    }

    fun setStreamingMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STREAMING_MODE, mode).apply()
    }

    fun isDeveloperLogsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEVELOPER_LOGS_ENABLED, false)
    }

    fun setDeveloperLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEVELOPER_LOGS_ENABLED, enabled).apply()
    }

    fun isSudoModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUDO_MODE_ENABLED, false)
    }

    fun setSudoModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SUDO_MODE_ENABLED, enabled).apply()
    }

    fun isShowUpdatedPlaylistsSectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SECTION_UPDATED_PLAYLISTS, true)
    }

    fun setShowUpdatedPlaylistsSectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SECTION_UPDATED_PLAYLISTS, enabled).apply()
    }

    fun isShowLatestSongsSectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SECTION_LATEST_SONGS, true)
    }

    fun setShowLatestSongsSectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SECTION_LATEST_SONGS, enabled).apply()
    }

    fun isShowRandomMixSectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SECTION_RANDOM_MIX, true)
    }

    fun setShowRandomMixSectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SECTION_RANDOM_MIX, enabled).apply()
    }

    fun isShowPlaylistsSectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SECTION_PLAYLISTS, true)
    }

    fun setShowPlaylistsSectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SECTION_PLAYLISTS, enabled).apply()
    }

    fun isShowYourAlbumsSectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SECTION_YOUR_ALBUMS, true)
    }

    fun setShowYourAlbumsSectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SECTION_YOUR_ALBUMS, enabled).apply()
    }

    fun isShowFriendsSectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SECTION_FRIENDS, true)
    }

    fun setShowFriendsSectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SECTION_FRIENDS, enabled).apply()
    }

    fun isShowYouTubeHomeChipsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_YT_HOME_CHIPS, true)
    }

    fun setShowYouTubeHomeChipsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_YT_HOME_CHIPS, enabled).apply()
    }

    fun isShowYouTubeAccountPlaylistsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_YT_ACCOUNT_PLAYLISTS, true)
    }

    fun setShowYouTubeAccountPlaylistsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_YT_ACCOUNT_PLAYLISTS, enabled).apply()
    }

    fun isShowYouTubeSongSectionsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_YT_SONG_SECTIONS, true)
    }

    fun setShowYouTubeSongSectionsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_YT_SONG_SECTIONS, enabled).apply()
    }

    fun isShowYouTubeBrowseSectionsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_YT_BROWSE_SECTIONS, true)
    }

    fun setShowYouTubeBrowseSectionsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_YT_BROWSE_SECTIONS, enabled).apply()
    }

    
    fun isYouTubeSectionEnabled(context: Context, sectionTitle: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabledSections = getEnabledYouTubeSections(context)
        
        
        if (enabledSections.isEmpty()) {
            return true
        }
        
        return enabledSections.contains(sectionTitle)
    }

    fun setYouTubeSectionEnabled(context: Context, sectionTitle: String, enabled: Boolean) {
        val enabledSections = getEnabledYouTubeSections(context).toMutableSet()
        
        if (enabled) {
            enabledSections.add(sectionTitle)
        } else {
            enabledSections.remove(sectionTitle)
        }
        
        saveEnabledYouTubeSections(context, enabledSections)
    }

    fun getEnabledYouTubeSections(context: Context): Set<String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_YT_ENABLED_SECTIONS, null) ?: return emptySet()
        
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveEnabledYouTubeSections(context: Context, sections: Set<String>) {
        val array = JSONArray()
        sections.forEach { array.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_YT_ENABLED_SECTIONS, array.toString())
            .apply()
    }

    fun getAllDiscoveredYouTubeSections(context: Context): Set<String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("yt_discovered_sections", null) ?: return emptySet()
        
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun addDiscoveredYouTubeSection(context: Context, sectionTitle: String) {
        val discovered = getAllDiscoveredYouTubeSections(context).toMutableSet()
        discovered.add(sectionTitle)
        
        val array = JSONArray()
        discovered.forEach { array.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("yt_discovered_sections", array.toString())
            .apply()
    }

    fun resetYouTubeSectionPreferences(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_YT_ENABLED_SECTIONS)
            .remove("yt_discovered_sections")
            .apply()
    }

    fun isStaticBackgroundCoverEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STATIC_BACKGROUND_COVER, false)
    }

    fun setStaticBackgroundCoverEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_STATIC_BACKGROUND_COVER, enabled).apply()
    }

    fun isExoPlayerAnimationDisabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_EXOPLAYER_ANIMATION, false)
    }

    fun setExoPlayerAnimationDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISABLE_EXOPLAYER_ANIMATION, disabled).apply()
    }

    fun getFriendSettings(context: Context): FriendSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return FriendSettings(
            share_listening = prefs.getString(KEY_FRIEND_SHARE_LISTENING, "friends") ?: "friends",
            allow_jam_invites = prefs.getBoolean(KEY_FRIEND_ALLOW_JAM_INVITES, true)
        )
    }

    fun setFriendSettings(context: Context, settings: FriendSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FRIEND_SHARE_LISTENING, settings.share_listening)
            .putBoolean(KEY_FRIEND_ALLOW_JAM_INVITES, settings.allow_jam_invites)
            .apply()
    }

    fun saveLatestSongs(context: Context, songs: List<Song>) {
        if (!isSaveCacheEnabled(context)) return
        val array = JSONArray()
        songs.forEach { array.put(songToJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LATEST_SONGS, array.toString())
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    fun getLatestSongs(context: Context): List<Song> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LATEST_SONGS, null) ?: return emptyList()
        val array = JSONArray(json)
        return List(array.length()) { jsonToSong(array.getJSONObject(it)) }
    }

    fun saveRandomMix(context: Context, songs: List<Song>) {
        if (!isSaveCacheEnabled(context)) return
        val array = JSONArray()
        songs.forEach { array.put(songToJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RANDOM_MIX, array.toString())
            .apply()
    }

    fun getRandomMix(context: Context): List<Song> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RANDOM_MIX, null) ?: return emptyList()
        val array = JSONArray(json)
        return List(array.length()) { jsonToSong(array.getJSONObject(it)) }
    }

    fun savePlaylists(context: Context, playlists: List<Playlist>) {
        if (!isSaveCacheEnabled(context)) return
        val array = JSONArray()
        playlists.forEach { array.put(playlistToJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYLISTS, array.toString())
            .apply()
    }

    fun getPlaylists(context: Context): List<Playlist> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYLISTS, null) ?: return emptyList()
        val array = JSONArray(json)
        return List(array.length()) { jsonToPlaylist(array.getJSONObject(it)) }
    }

    fun saveUserPlaylists(context: Context, playlists: List<Playlist>) {
        if (!isSaveCacheEnabled(context)) return
        val array = JSONArray()
        playlists.forEach { array.put(playlistToJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_PLAYLISTS, array.toString())
            .apply()
    }

    fun getUserPlaylists(context: Context): List<Playlist> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_PLAYLISTS, null) ?: return emptyList()
        val array = JSONArray(json)
        return List(array.length()) { jsonToPlaylist(array.getJSONObject(it)) }
    }

    fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LATEST_SONGS)
            .remove(KEY_RANDOM_MIX)
            .remove(KEY_PLAYLISTS)
            .remove(KEY_USER_PLAYLISTS)
            .remove(KEY_FAVORITE_IDS)
            .remove(KEY_LAST_UPDATE)
            .apply()
        
        cacheCleared.value = System.currentTimeMillis()
    }

    fun clearAuthenticatedUserData(context: Context) {
        val retainedFavoriteIds = favoriteIds.value.filterTo(mutableSetOf()) { it.startsWith("yt_") }
        favoriteIds.value = retainedFavoriteIds
        savedAlbumIds.value = emptySet()
        sudoSelectedTrackIds.value = emptySet()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_USER_PLAYLISTS)
            .remove(KEY_FRIEND_SHARE_LISTENING)
            .remove(KEY_FRIEND_ALLOW_JAM_INVITES)
            .apply()

        if (retainedFavoriteIds.isEmpty()) {
            prefs.edit().remove(KEY_FAVORITE_IDS).apply()
        } else {
            saveFavoriteIds(context, retainedFavoriteIds)
        }
    }

    fun saveFavoriteIds(context: Context, ids: Set<String>) {
        if (!isSaveCacheEnabled(context)) return
        val array = JSONArray()
        ids.forEach { array.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FAVORITE_IDS, array.toString())
            .apply()
    }

    fun getFavoriteIds(context: Context): Set<String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FAVORITE_IDS, null) ?: return emptySet()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }
    
    fun loadFavoriteIds(context: Context) {
        favoriteIds.value = getFavoriteIds(context)
    }

    private fun songToJson(song: Song): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("albumId", song.albumId)
        put("durationSec", song.durationSec ?: 0)
        put("type", song.type)
        put("samplingRateHz", song.samplingRateHz ?: 0)
        put("spotifyUrl", song.spotifyUrl)
        put("coverUrl", song.coverUrl)
        put("color", song.color.value.toLong())
        put("bitrateKbps", song.bitrateKbps ?: 0)
        put("fileSize", song.fileSize ?: 0L)
        put("artists", song.artists.toJsonArray())
    }

    private fun jsonToSong(obj: JSONObject): Song = Song(
        id = obj.optString("id").takeIf { it != "null" && it.isNotBlank() },
        title = obj.getString("title"),
        artist = obj.getString("artist"),
        album = obj.optString("album").takeIf { it != "null" && it.isNotBlank() },
        albumId = obj.optString("albumId").takeIf { it != "null" && it.isNotBlank() },
        durationSec = obj.optInt("durationSec").takeIf { it > 0 },
        type = obj.optString("type").takeIf { it != "null" && it.isNotBlank() },
        samplingRateHz = obj.optInt("samplingRateHz").takeIf { it > 0 },
        spotifyUrl = obj.optString("spotifyUrl").takeIf { it != "null" && it.isNotBlank() },
        coverUrl = obj.optString("coverUrl").takeIf { it != "null" && it.isNotBlank() },
        color = Color(obj.getLong("color").toULong()),
        bitrateKbps = obj.optInt("bitrateKbps").takeIf { it > 0 },
        artists = obj.optSongArtists()
    )

    private fun playlistToJson(playlist: Playlist): JSONObject = JSONObject().apply {
        put("id", playlist.id)
        put("title", playlist.title)
        put("subtitle", playlist.subtitle ?: "")
        put("color", playlist.color.value.toLong())
        put("thumbnailUrl", playlist.thumbnailUrl ?: "")
        put("endpoint", playlist.endpoint ?: "")
        put("kind", playlist.kind ?: "")
        put("requiresAuth", playlist.requiresAuth)
    }

    private fun jsonToPlaylist(obj: JSONObject): Playlist = Playlist(
        id = obj.optString("id", ""),
        title = obj.getString("title"),
        subtitle = obj.optString("subtitle").takeIf { it.isNotBlank() },
        color = Color(obj.getLong("color").toULong()),
        thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
        endpoint = obj.optString("endpoint").takeIf { it.isNotBlank() },
        kind = obj.optString("kind").takeIf { it.isNotBlank() },
        requiresAuth = obj.optBoolean("requiresAuth", false)
    )
}
