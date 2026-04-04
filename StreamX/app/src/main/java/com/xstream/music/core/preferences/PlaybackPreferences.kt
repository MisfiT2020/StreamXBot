package com.xstream.music.core.preferences

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
import androidx.compose.ui.graphics.Color
import com.xstream.music.data.api.AudioQuality
import org.json.JSONArray
import org.json.JSONObject
import com.xstream.music.data.model.Song

object PlaybackPreferences {
    private const val PREFS_NAME = "streamx_playback_prefs"
    private const val KEY_QUEUE = "queue"
    private const val KEY_CURRENT_INDEX = "current_index"
    private const val KEY_CURRENT_POSITION = "current_position"
    private const val KEY_LYRICS_PROVIDER = "lyrics_provider"
    private const val KEY_AUDIO_QUALITY = "audio_quality"
    private const val KEY_AUTO_FETCH_LYRICS = "auto_fetch_lyrics"

    fun getLyricsProvider(context: Context): String {
        val provider = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LYRICS_PROVIDER, "lrclib") ?: "lrclib"
        return if (provider == "auto") "lrclib" else provider
    }

    fun getLyricsProviderMode(context: Context): String {
        return if (isAutoFetchLyricsEnabled(context)) {
            "auto"
        } else {
            getLyricsProvider(context)
        }
    }

    fun setLyricsProvider(context: Context, provider: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LYRICS_PROVIDER, provider)
            .apply()
    }

    fun setLyricsProviderMode(context: Context, providerMode: String) {
        if (providerMode == "auto") {
            setAutoFetchLyricsEnabled(context, true)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentProvider = prefs.getString(KEY_LYRICS_PROVIDER, "lrclib") ?: "lrclib"
            if (currentProvider == "auto") {
                setLyricsProvider(context, "lrclib")
            }
        } else {
            setAutoFetchLyricsEnabled(context, false)
            setLyricsProvider(context, providerMode)
        }
    }

    fun isAutoFetchLyricsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_AUTO_FETCH_LYRICS)) {
            prefs.getBoolean(KEY_AUTO_FETCH_LYRICS, false)
        } else {
            prefs.getString(KEY_LYRICS_PROVIDER, "lrclib") == "auto"
        }
    }

    fun setAutoFetchLyricsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_FETCH_LYRICS, enabled)
            .apply()
    }

    fun getAudioQuality(context: Context): AudioQuality {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUDIO_QUALITY, AudioQuality.AUTO.name) ?: AudioQuality.AUTO.name
        return AudioQuality.entries.firstOrNull { it.name == saved } ?: AudioQuality.AUTO
    }

    fun setAudioQuality(context: Context, quality: AudioQuality) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_AUDIO_QUALITY, quality.name)
            .apply()
    }

    fun savePlaybackState(context: Context, queue: List<Song>, currentIndex: Int, currentPosition: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        queue.forEach { array.put(songToJson(it)) }
        
        prefs.edit()
            .putString(KEY_QUEUE, array.toString())
            .putInt(KEY_CURRENT_INDEX, currentIndex)
            .putLong(KEY_CURRENT_POSITION, currentPosition)
            .apply()
    }

    fun getSavedQueue(context: Context): List<Song> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { jsonToSong(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSavedIndex(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CURRENT_INDEX, 0)
    }

    fun getSavedPosition(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CURRENT_POSITION, 0L)
    }

    fun clearPlaybackState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_QUEUE)
            .remove(KEY_CURRENT_INDEX)
            .remove(KEY_CURRENT_POSITION)
            .apply()
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
        put("localPath", song.localPath)
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
        localPath = obj.optString("localPath").takeIf { it != "null" && it.isNotBlank() },
        fileSize = obj.optLong("fileSize").takeIf { it > 0 },
        artists = obj.optSongArtists()
    )
}
