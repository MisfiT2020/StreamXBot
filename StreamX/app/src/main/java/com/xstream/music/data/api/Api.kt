package com.xstream.music.data.api

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
import timber.log.Timber
import androidx.compose.ui.graphics.Color
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.YouTubeClient
import android.net.ConnectivityManager
import com.xstream.music.core.utils.YTPlayerUtils
import com.xstream.music.data.model.AlbumData
import com.xstream.music.data.model.ArtistByIdResponse
import com.xstream.music.data.model.ArtistResponse
import com.xstream.music.data.model.BaseResponse
import com.xstream.music.data.model.CreateJamRequest
import com.xstream.music.data.model.Friend
import com.xstream.music.data.model.FriendListening
import com.xstream.music.data.model.FriendRequest
import com.xstream.music.data.model.FriendRequestsResponse
import com.xstream.music.data.model.FriendSettings
import com.xstream.music.data.model.FriendSettingsResponse
import com.xstream.music.data.model.FriendsListeningResponse
import com.xstream.music.data.model.FriendsResponse
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.JamMember
import com.xstream.music.data.model.JamPlayback
import com.xstream.music.data.model.JamResponse
import com.xstream.music.data.model.JamSettings
import com.xstream.music.data.model.LoginRequest
import com.xstream.music.data.model.LoginResponse
import com.xstream.music.data.model.Playlist
import com.xstream.music.data.model.RegisterResponse
import com.xstream.music.data.model.Song
import com.xstream.music.data.model.UserProfile
import com.xstream.music.data.model.ValidateResponse
import com.xstream.music.core.preferences.PlaybackPreferences

enum class AudioQuality {
    AUTO,
    HIGH,
    LOW
}

interface StreamXApi {
    fun getPlaylists(): List<Playlist>
    fun getLatestSongs(): List<Song>
    fun getRandomMixSongs(): List<Song>
}

object LocalStubApi : StreamXApi {
    override fun getPlaylists(): List<Playlist> = emptyList()
    override fun getLatestSongs(): List<Song> = emptyList()
    override fun getRandomMixSongs(): List<Song> = emptyList()
}

object ApiPreferences {
    private const val prefsName = "streamx_prefs"
    private const val keyApiUrl = "api_url"
    private const val keySavedApis = "saved_apis"

    data class ApiEntry(
        val name: String,
        val url: String
    )

    fun getApiUrl(context: Context): String {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(keyApiUrl, "")
            .orEmpty()
    }

    fun setApiUrl(context: Context, apiUrl: String) {
        val normalized = normalizeApiInput(apiUrl)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyApiUrl, normalized)
            .apply()
        if (normalized.isNotBlank()) {
            val existing = getSavedApis(context)
            if (existing.none { it.url.equals(normalized, ignoreCase = true) }) {
                persistSavedApis(context, existing + ApiEntry(name = "API ${existing.size + 1}", url = normalized))
            }
        }
    }

    fun getSavedApis(context: Context): List<ApiEntry> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val raw = prefs.getString(keySavedApis, null).orEmpty()
        if (raw.isBlank()) {
            val current = getApiUrl(context)
            return if (current.isNotBlank()) listOf(ApiEntry(name = "Default", url = current)) else emptyList()
        }

        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (name.isNotBlank() && url.isNotBlank()) {
                        add(ApiEntry(name = name, url = url))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSavedApi(context: Context, name: String, apiUrl: String) {
        val normalizedUrl = normalizeApiInput(apiUrl)
        if (name.isBlank() || normalizedUrl.isBlank()) return

        val existing = getSavedApis(context).toMutableList()
        val index = existing.indexOfFirst { it.url.equals(normalizedUrl, ignoreCase = true) }
        if (index >= 0) {
            existing[index] = existing[index].copy(name = name.trim(), url = normalizedUrl)
        } else {
            existing.add(ApiEntry(name = name.trim(), url = normalizedUrl))
        }
        persistSavedApis(context, existing)
    }

    fun removeSavedApi(context: Context, apiUrl: String) {
        val normalizedUrl = normalizeApiInput(apiUrl)
        if (normalizedUrl.isBlank()) return

        val updated = getSavedApis(context).filterNot { it.url.equals(normalizedUrl, ignoreCase = true) }
        persistSavedApis(context, updated)

        val current = getApiUrl(context)
        if (current.equals(normalizedUrl, ignoreCase = true)) {
            val fallback = updated.firstOrNull()?.url.orEmpty()
            setApiUrl(context, fallback)
        }
    }

    private fun persistSavedApis(context: Context, apis: List<ApiEntry>) {
        val array = JSONArray()
        apis.forEach { api ->
            array.put(
                JSONObject().apply {
                    put("name", api.name)
                    put("url", api.url)
                }
            )
        }

        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keySavedApis, array.toString())
            .apply()
    }
}

data class SearchResponse(
    val page: Int,
    val per_page: Int,
    val total: Int,
    val items: List<SearchItem>,
    val cover_url: String?
)

data class StreamXChannel(
    val id: Long,
    val title: String,
    val username: String?,
    val type: String?
)

suspend fun fetchStreamXChannels(apiUrl: String): List<StreamXChannel> = withContext(Dispatchers.IO) {
    try {
        val normalizedUrl = normalizeApiInput(apiUrl)
        if (normalizedUrl.isBlank()) return@withContext emptyList()
        val endpoint = "$normalizedUrl/channelids"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            val items = root.optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optLong("id")
                    if (id == 0L) continue
                    add(
                        StreamXChannel(
                            id = id,
                            title = item.optString("title").ifBlank { "Channel $id" },
                            username = item.optString("username").takeIf { it.isNotBlank() && it != "null" },
                            type = item.optString("type").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun initYouTubeAuth(context: Context) {
    val prefs = context.getSharedPreferences("youtube_prefs", Context.MODE_PRIVATE)
    YouTube.cookie = prefs.getString("innertube_cookie", "")
    YouTube.visitorData = prefs.getString("visitor_data", "")
    YouTube.dataSyncId = prefs.getString("data_sync_id", "")
    YouTube.useLoginForBrowse = !YouTube.cookie.isNullOrBlank()
}

data class SearchItem(
    val _id: String,
    val title: String,
    val artist: String,
    val artists: List<SongArtist> = emptyList(),
    val album: String?,
    val album_id: String?,
    val duration_sec: Int?,
    val type: String?,
    val sampling_rate_hz: Int?,
    val spotify_url: String?,
    val cover_url: String?,
    val file_size: Long?,
    val updated_at: Double?
)

fun upscaleYoutubeThumbnail(url: String?, size: Int = 600): String? {
    if (url.isNullOrBlank()) return null
    var cleaned = url.trim().removeSurrounding("`").trim()
    if (cleaned.isBlank()) return null
    
    if (cleaned.startsWith("//")) {
        cleaned = "https:$cleaned"
    }
    
    try {
        val host = URL(cleaned).host.lowercase()
        if (host.contains("ytimg.com")) {
            return cleaned.replace("hqdefault.jpg", "mqdefault.jpg")
                .replace("sddefault.jpg", "mqdefault.jpg")
                .replace("/default.jpg", "/mqdefault.jpg")
        }
        
        if (host.contains("googleusercontent.com") || host.contains("yt3.ggpht.com")) {
            val sizeRegex = Regex("=w\\d+-h\\d+.*$", RegexOption.IGNORE_CASE)
            if (sizeRegex.containsMatchIn(cleaned)) {
                val base = sizeRegex.replace(cleaned, "")
                return "$base=w$size-h$size-l90-rj"
            }
            if (cleaned.contains("=")) {
                val parts = cleaned.split("=")
                val lastPart = parts.last().lowercase()
                if (lastPart.startsWith("w") || lastPart.startsWith("s")) {
                    val base = cleaned.substringBeforeLast("=")
                    return "$base=w$size-h$size-l90-rj"
                }
            }
        }
    } catch (e: Exception) {
        
    }
    return cleaned
}

suspend fun getYouTubePlaybackData(videoId: String, context: Context): YTPlayerUtils.PlaybackData? = withContext(Dispatchers.IO) {
    try {
        initYouTubeAuth(context)
        Timber.tag("YouTubeApi").d("Fetching stream for videoId: $videoId")
        
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val result = YTPlayerUtils.playerResponseForPlayback(
            videoId = videoId,
            playlistId = null,
            audioQuality = PlaybackPreferences.getAudioQuality(context),
            connectivityManager = connectivityManager
        )
        
        if (result.isSuccess) {
            val playbackData = result.getOrNull()
            
            playbackData?.format?.let { format ->
                MusicPlayerManager.cacheFormatMetadata(
                    videoId,
                    format.audioSampleRate,
                    format.bitrate / 1000 // Convert to Kbps
                )
                Timber.tag("YouTubeApi").d("Cached format metadata: sampleRate=${format.audioSampleRate}, bitrate=${format.bitrate}")
            }
            
            Timber.tag("YouTubeApi").d("Successfully extracted playback data via YTPlayerUtils with client=${playbackData?.sourceClientName}")
            return@withContext playbackData
        } else {
            Timber.tag("YouTubeApi").e(result.exceptionOrNull(), "Failed to get player response for playback")
        }
    } catch (e: Exception) {
        Timber.tag("YouTubeApi").e(e, "Error fetching YouTube stream")
    }
    null
}

suspend fun getYouTubeStreamUrl(videoId: String, context: Context): String? {
    return getYouTubePlaybackData(videoId, context)?.streamUrl
}

data class YtSearchResult(
    val items: List<SearchItem>,
    val continuation: String?
)

fun YTItem.toSong(): Song? {
    return when (this) {
        is SongItem -> Song(
            id = "yt_$id",
            title = title,
            artist = artists.joinToString(", ") { it.name },
            artists = artists.map { SongArtist(name = it.name, id = it.id) },
            album = album?.name,
            albumId = album?.id,
            durationSec = duration,
            type = "youtube",
            samplingRateHz = null,
            spotifyUrl = shareLink,
            coverUrl = upscaleYoutubeThumbnail(thumbnail, 600),
            color = Color(0xFF1E1E1E),
            bitrateKbps = null,
            fileSize = null
        )
        is EpisodeItem -> Song(
            id = "yt_$id",
            title = title,
            artist = author?.name ?: podcast?.name ?: "Unknown",
            artists = listOfNotNull(author).map { SongArtist(name = it.name, id = it.id) },
            album = podcast?.name,
            albumId = podcast?.id,
            durationSec = duration,
            type = "youtube",
            samplingRateHz = null,
            spotifyUrl = shareLink,
            coverUrl = upscaleYoutubeThumbnail(thumbnail, 600),
            color = Color(0xFF1E1E1E),
            bitrateKbps = null,
            fileSize = null
        )
        else -> null
    }
}

fun YTItem.toPlaylist(): Playlist? {
    return when (this) {
        is PlaylistItem -> Playlist(
            id = id,
            title = title,
            subtitle = author?.name ?: "",
            color = Color(0xFF1E1E1E),
            thumbnailUrl = upscaleYoutubeThumbnail(thumbnail, 600),
            endpoint = id,
            kind = "youtube_playlist"
        )
        is AlbumItem -> Playlist(
            id = id,  
            title = title,
            subtitle = artists?.joinToString(", ") { it.name } ?: "",
            color = Color(0xFF1E1E1E),
            thumbnailUrl = upscaleYoutubeThumbnail(thumbnail, 600),
            endpoint = id,  
            kind = "youtube_album"
        )
        else -> null
    }
}

suspend fun fetchYouTubeHome(context: Context, continuation: String? = null): com.metrolist.innertube.pages.HomePage? = withContext(Dispatchers.IO) {
    try {
        initYouTubeAuth(context)
        val result = YouTube.home(continuation).getOrNull()
        return@withContext result
    } catch (e: Exception) {
        Timber.e(e, "Error fetching YouTube home")
    }
    null
}

suspend fun searchYoutubeMusic(query: String, context: Context): YtSearchResult {
    return withContext(Dispatchers.IO) {
        try {
            initYouTubeAuth(context)
            val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            if (result != null) {
                val searchItems = result.items.filterIsInstance<SongItem>().map { song ->
                    SearchItem(
                        _id = "yt_${song.id}",
                        title = song.title,
                        artist = song.artists.joinToString(", ") { it.name },
                        artists = song.artists.map { SongArtist(name = it.name, id = it.id) },
                        album = song.album?.name ?: "YouTube",
                        album_id = song.album?.id,
                        duration_sec = song.duration ?: 0,
                        type = "youtube",
                        sampling_rate_hz = 0,
                        spotify_url = song.shareLink,
                        cover_url = upscaleYoutubeThumbnail(song.thumbnail, 600),
                        file_size = 0,
                        updated_at = 0.0
                    )
                }
                return@withContext YtSearchResult(searchItems, result.continuation)
            }
        } catch (e: Exception) {
            Timber.tag("SearchApi").e(e, "Error searching YouTube Music")
        }
        YtSearchResult(emptyList(), null)
    }
}

suspend fun searchYoutubeMusicContinuation(continuation: String, context: Context): YtSearchResult {
    return withContext(Dispatchers.IO) {
        try {
            initYouTubeAuth(context)
            val result = YouTube.searchContinuation(continuation).getOrNull()
            if (result != null) {
                val searchItems = result.items.filterIsInstance<SongItem>().map { song ->
                    SearchItem(
                        _id = "yt_${song.id}",
                        title = song.title,
                        artist = song.artists.joinToString(", ") { it.name },
                        artists = song.artists.map { SongArtist(name = it.name, id = it.id) },
                        album = song.album?.name ?: "YouTube",
                        album_id = song.album?.id,
                        duration_sec = song.duration ?: 0,
                        type = "youtube",
                        sampling_rate_hz = 0,
                        spotify_url = song.shareLink,
                        cover_url = upscaleYoutubeThumbnail(song.thumbnail, 600),
                        file_size = 0,
                        updated_at = 0.0
                    )
                }
                return@withContext YtSearchResult(searchItems, result.continuation)
            }
        } catch (e: Exception) {
            Timber.tag("SearchApi").e(e, "Error fetching YouTube Music continuation")
        }
        YtSearchResult(emptyList(), null)
    }
}





suspend fun searchSoundcloud(apiUrl: String, query: String, limit: Int = 20, page: Int = 1): List<SearchItem> = withContext(Dispatchers.IO) {
    try {
        val normalizedUrl = normalizeApiInput(apiUrl)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val urlStr = "$normalizedUrl/soundcloud/search?q=$encodedQuery&page=$page&limit=$limit"
        
        Timber.tag("SoundcloudApi").d("Searching: $urlStr")
        
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        
        val responseCode = connection.responseCode
        Timber.tag("SoundcloudApi").d("Response Code: $responseCode")
        
        if (responseCode in 200..299) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            Timber.tag("SoundcloudApi").d("Response: $responseText")
            
            val json = JSONObject(responseText)
            val collection = json.optJSONArray("items")
            val results = mutableListOf<SearchItem>()
            
            if (collection != null) {
                for (i in 0 until collection.length()) {
                    val track = collection.optJSONObject(i) ?: continue
                    
                    
                    val rawId = track.optLong("id")
                    val id = if (rawId > 0) rawId.toString() else track.optString("id")
                    
                    val title = track.optString("title")
                    val artist = track.optString("username", "Unknown Artist")
                    val durationSec = track.optInt("duration_sec", 0)
                    
                    var coverUrl = track.optString("artwork_url").replace("`", "").trim()
                    if (coverUrl.isNotBlank() && coverUrl != "null") {
                        coverUrl = coverUrl.replace("-large.jpg", "-t500x500.jpg").replace("-large.png", "-t500x500.png")
                    } else {
                        coverUrl = ""
                    }
                    
                    val url = track.optString("permalink_url").replace("`", "").trim()
                    
                    if (id.isNotBlank() && title.isNotBlank()) {
                        results.add(
                            SearchItem(
                                _id = "sc_$id",
                                title = title,
                                artist = artist,
                                album = "SoundCloud",
                                album_id = null,
                                duration_sec = durationSec,
                                type = "soundcloud",
                                sampling_rate_hz = 0,
                                spotify_url = url,
                                cover_url = coverUrl,
                                file_size = 0,
                                updated_at = 0.0
                            )
                        )
                    }
                }
            }
            return@withContext results
        }
    } catch (e: Exception) {
        Timber.tag("SoundcloudApi").e(e, "Error searching tracks")
    }
    emptyList()
}

suspend fun getSoundcloudStreamUrl(apiUrl: String, trackId: String): String? = withContext(Dispatchers.IO) {
    try {
        val realId = trackId.removePrefix("sc_")
        val normalizedUrl = normalizeApiInput(apiUrl)
        val urlStr = "$normalizedUrl/soundcloud/tracks/$realId/hls"
        
        Timber.tag("SoundcloudApi").d("Fetching stream: $urlStr")
        
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        
        val responseCode = connection.responseCode
        Timber.tag("SoundcloudApi").d("Stream Response Code: $responseCode")
        
        if (responseCode in 200..299) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }.trim()
            
            
            val hlsUrl = try {
                val json = JSONObject(responseText)
                json.optString("hls_url", responseText)
            } catch (e: Exception) {
                responseText
            }
            
            if (hlsUrl.isNotBlank() && hlsUrl != "null" && hlsUrl.startsWith("http")) {
                return@withContext hlsUrl.replace("`", "").trim()
            }
        }
    } catch (e: Exception) {
        Timber.tag("SoundcloudApi").e(e, "Error getting stream URL")
    }
    null
}

fun searchSongs(apiUrl: String, query: String, page: Int = 1, limit: Int = 20, channelId: String = ""): SearchResponse {
    
    val normalizedUrl = normalizeApiInput(apiUrl)
    if (normalizedUrl.isBlank()) return SearchResponse(page, limit, 0, emptyList(), null)

    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
    val encodedChannelId = channelId.trim().takeIf { it.isNotEmpty() }?.let { java.net.URLEncoder.encode(it, "UTF-8") }
    val channelParam = encodedChannelId?.let { "&channel_id=$it" } ?: ""
    val url = "$normalizedUrl/search?query=$encodedQuery&page=$page&limit=$limit$channelParam"
    
    try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            return SearchResponse(page, limit, 0, emptyList(), null)
        }

        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(responseText)
        
        val itemsArray = root.optJSONArray("items")
        val items = mutableListOf<SearchItem>()
        
        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                items.add(
                    SearchItem(
                        _id = item.getString("_id"),
                        title = item.getString("title"),
                        artist = item.getString("artist"),
                        artists = item.optSongArtists(),
                        album = item.optString("album").takeIf { it != "null" && it.isNotBlank() },
                        album_id = item.optString("album_id").takeIf { it != "null" && it.isNotBlank() },
                        duration_sec = item.optInt("duration_sec").takeIf { it > 0 },
                        type = item.optString("type").takeIf { it != "null" && it.isNotBlank() },
                        sampling_rate_hz = item.optInt("sampling_rate_hz").takeIf { it > 0 },
                        spotify_url = item.optString("spotify_url").takeIf { it != "null" && it.isNotBlank() },
                        cover_url = item.optString("cover_url").takeIf { it != "null" && it.isNotBlank() },
                        file_size = item.optLong("file_size").takeIf { it > 0L },
                        updated_at = item.optDouble("updated_at")
                    )
                )
            }
        }
        
        return SearchResponse(
            page = root.optInt("page", 1),
            per_page = root.optInt("per_page", 20),
            total = root.optInt("total", 0),
            items = items,
            cover_url = root.optString("cover_url").takeIf { it != "null" && it.isNotBlank() }
        ).also { connection.disconnect() }
    } catch (e: Exception) {
        Timber.tag("SearchApi").e(e, "Error searching songs")
        return SearchResponse(page, limit, 0, emptyList(), null)
    }
}

fun normalizeApiInput(input: String): String {
    val cleaned = input
        .trim()
        .removePrefix("`")
        .removeSuffix("`")
        .removePrefix("'")
        .removeSuffix("'")
        .removePrefix("\"")
        .removeSuffix("\"")
        .trim()

    return cleaned.trimEnd('/')
}

private suspend fun makeRequest(url: String, method: String, body: String? = null, token: String? = null): String {
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("accept", "application/json")
            if (token != null) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            if (connection.responseCode !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw Exception("HTTP ${connection.responseCode}: $error")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun rebuildAlbums(apiBaseUrl: String, context: Context? = null, token: String? = null): JSONObject? {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return null
    val url = "$normalizedBase/admin/refresh/albums/from-tracks"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            doOutput = true
            connectTimeout = 60_000
            readTimeout = 60_000
        }
        try {
            val jsonBody = """
                {
                  "dry_run": false,
                  "force_album_id": true,
                  "limit_tracks": 0,
                  "rebuild_albums": true,
                  "limit_albums": 0,
                  "clear_albums": true
                }
            """.trimIndent()
            
            connection.outputStream.use { os ->
                val input = jsonBody.toByteArray(charset("utf-8"))
                os.write(input, 0, input.size)
            }
            
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            JSONObject(responseText)
        } catch (e: Exception) {
            Timber.e(e, "Error rebuilding albums")
            null
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun loginUser(apiBaseUrl: String, request: LoginRequest): LoginResponse {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return LoginResponse(ok = false)
    
    return withContext(Dispatchers.IO) {
        val loginUrl = "$normalizedBase/auth/login?set_cookie=false"
        val connection = (URL(loginUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            val body = JSONObject().apply {
                put("username", request.username)
                put("password", request.password)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = connection.responseCode
            val responseText = try {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }

            if (responseText.isNotBlank()) {
                val root = JSONObject(responseText)
                LoginResponse(
                    ok = root.optBoolean("ok", false),
                    user_id = root.optLong("user_id").takeIf { it != 0L },
                    token = root.optString("token").takeIf { it.isNotBlank() },
                    first_name = root.optString("first_name").takeIf { it.isNotBlank() },
                    profile_url = root.optString("profile_url").takeIf { it.isNotBlank() },
                    photo_url = root.optString("photo_url").takeIf { it.isNotBlank() }
                )
            } else {
                LoginResponse(ok = false)
            }
        } catch (e: Exception) {
            LoginResponse(ok = false)
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun fetchBrowseSongs(apiInput: String, page: Int = 1, context: Context? = null, token: String? = null): List<Song> {
    val normalized = normalizeApiInput(apiInput)
    if (normalized.isBlank()) return emptyList()

    val browseUrl = if (normalized.contains("/browse")) {
        if (Regex("""[?&]page=\d+""").containsMatchIn(normalized)) normalized
        else if (normalized.contains("?")) "$normalized&page=$page" else "$normalized?page=$page"
    } else {
        "$normalized/browse?page=$page"
    }

    return withContext(Dispatchers.IO) {
        val connection = (URL(browseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val root = JSONObject(responseText)
        val items = root.optJSONArray("items") ?: root.optJSONArray("tracks") ?: return@withContext emptyList()

        buildList(items.length()) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val title = item.optString("title")
                val artist = item.optString("artist")
                if (title.isBlank() && artist.isBlank()) continue

                val stableColor = colorFromStableKey(item.optString("_id", "$title|$artist"))

                add(
                    Song(
                        id = item.optString("_id").takeIf { it.isNotBlank() },
                        title = title.ifBlank { "Unknown" },
                        artist = artist.ifBlank { "Unknown" },
                        album = item.optString("album").takeIf { it.isNotBlank() },
                        albumId = (item.optJSONObject("audio")?.optString("album_id") ?: item.optString("album_id")).takeIf { it.isNotBlank() },
                        durationSec = item.optInt("duration_sec").takeIf { it > 0 },
                        type = item.optString("type").takeIf { it.isNotBlank() },
                        samplingRateHz = item.optInt("sampling_rate_hz").takeIf { it > 0 },
                        spotifyUrl = normalizeApiInput(item.optString("spotify_url")).takeIf { it.isNotBlank() },
                        coverUrl = normalizeApiInput(item.optString("cover_url")).takeIf { it.isNotBlank() },
                        color = stableColor
                    )
                )
            }
        }
    }
}

private fun colorFromStableKey(key: String): Color {
    val h = key.hashCode()
    val r = 40 + (h and 0x7F)
    val g = 40 + ((h shr 8) and 0x7F)
    val b = 40 + ((h shr 16) and 0x7F)
    return Color(0xFF000000 or (r shl 16).toLong() or (g shl 8).toLong() or b.toLong())
}

suspend fun fetchSong(apiBaseUrl: String, trackId: String, context: Context? = null, token: String? = null): Song? {
    val normalized = normalizeApiInput(apiBaseUrl)
    if (normalized.isBlank() || trackId.isBlank()) return null

    val trackUrl = "$normalized/tracks/$trackId"

    return withContext(Dispatchers.IO) {
        try {
            val connection = (URL(trackUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("accept", "application/json")
                
                val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
                effectiveToken?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                    setRequestProperty("X-Auth-Token", it)
                }
                
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            val responseText = try {
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    return@withContext null
                }
                stream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val item = JSONObject(responseText)
            val title = item.optString("title")
            val artist = item.optString("artist")
            
            
            val audioObj = item.optJSONObject("audio")
            val durationSec = audioObj?.optInt("duration_sec") ?: item.optInt("duration_sec")
            val audioType = audioObj?.optString("type") ?: item.optString("type")
            val samplingRateHz = audioObj?.optInt("sampling_rate_hz") ?: item.optInt("sampling_rate_hz")
            val bitrateKbps = audioObj?.optInt("bitrate_kbps")
            val fileSize = audioObj?.optLong("file_size") ?: item.optLong("file_size")
            
            
            val spotifyObj = item.optJSONObject("spotify")
            val coverUrl = spotifyObj?.optString("cover_url") ?: item.optString("cover_url")
            val spotifyUrl = spotifyObj?.optString("url") ?: item.optString("spotify_url")
            
            val stableColor = colorFromStableKey(item.optString("_id", "$title|$artist"))

            Song(
                id = item.optString("_id").takeIf { it.isNotBlank() },
                title = if (audioObj != null) audioObj.optString("title") else title,
                artist = if (audioObj != null) audioObj.optString("artist") else artist,
                album = item.optString("album").takeIf { it.isNotBlank() },
                albumId = (audioObj?.optString("album_id") ?: item.optString("album_id")).takeIf { it.isNotBlank() },
                durationSec = durationSec.takeIf { it > 0 },
                type = audioType.takeIf { it.isNotBlank() },
                samplingRateHz = samplingRateHz.takeIf { it > 0 },
                bitrateKbps = bitrateKbps?.takeIf { it > 0 },
                spotifyUrl = normalizeApiInput(spotifyUrl).takeIf { it.isNotBlank() },
                coverUrl = normalizeApiInput(coverUrl).takeIf { it.isNotBlank() },
                color = stableColor
            )
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error fetching track details for $trackId")
            null
        }
    }
}

suspend fun fetchTrackLyrics(
    apiBaseUrl: String, 
    trackId: String, 
    context: Context? = null, 
    token: String? = null,
    songTitle: String? = null,
    songArtist: String? = null,
    durationSec: Int = -1,
    forceRefresh: Boolean = false
): String? {
    
    if (!forceRefresh) {
        context?.let { ctx ->
            try {
                val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "StreamX")
                val lyricsFile = java.io.File(dir, "${trackId}_lyrics.txt")
                if (lyricsFile.exists()) {
                    return lyricsFile.readText()
                }
            } catch (e: Exception) {
            }
        }
    }

    val normalized = normalizeApiInput(apiBaseUrl)
    if (trackId.isBlank()) return null

    var fetchedLyrics: String? = null
    
    if (normalized.isNotBlank()) {
        val lyricsUrl = "$normalized/tracks/$trackId/lyrics"

        fetchedLyrics = withContext(Dispatchers.IO) {
            val connection = (URL(lyricsUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("accept", "application/json")
                
                val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
                effectiveToken?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                    setRequestProperty("X-Auth-Token", it)
                }
                
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            try {
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    return@withContext null
                }
                
                stream.bufferedReader().use { it.readText() }.trim()
            } catch (e: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    if (fetchedLyrics.isNullOrBlank() && songTitle != null && songArtist != null && context != null) {
        val autoFetchLyrics = PlaybackPreferences.isAutoFetchLyricsEnabled(context)
        val provider = PlaybackPreferences.getLyricsProvider(context)
        val providersToTry = if (autoFetchLyrics) {
            listOf("lrclib", "betterlyrics", "rclyricsband")
        } else {
            listOf(provider)
        }

        var onlineLyrics: String? = null
        for (providerToTry in providersToTry) {
            onlineLyrics = try {
                when (providerToTry) {
                    "lrclib" -> com.metrolist.lrclib.LrcLib.getLyrics(songTitle, songArtist, durationSec).getOrNull()
                    "betterlyrics" -> com.metrolist.music.betterlyrics.BetterLyrics.getLyrics(songTitle, songArtist, durationSec).getOrNull()
                    "rclyricsband" -> fetchLyricsFromRcLyricsBand(songTitle, songArtist)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }

            if (!onlineLyrics.isNullOrBlank()) break
        }

        if (!onlineLyrics.isNullOrBlank()) {
            try {
                val dir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir, "StreamX")
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, "${trackId}_lyrics.txt").writeText(onlineLyrics)
            } catch (e: Exception) {}
            fetchedLyrics = onlineLyrics
        }
    }

    return fetchedLyrics
}

suspend fun fetchLyricsFromRcLyricsBand(query: String, artistQuery: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val searchUrl = "https://rclyricsband.com/"
            val connection = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0")
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            
            val postData = "search=${java.net.URLEncoder.encode(query, "UTF-8")}"
            connection.outputStream.use { os ->
                val input = postData.toByteArray(charset("utf-8"))
                os.write(input, 0, input.size)
            }
            
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            
            
            val document = org.jsoup.Jsoup.parse(responseText)
            val results = document.select("li.list_results a.song_search")
            var targetHref: String? = null
            
            for (result in results) {
                val text = result.text()
                
                if (text.contains(artistQuery, ignoreCase = true) || artistQuery.contains(text.substringAfterLast("-").trim(), ignoreCase = true)) {
                    targetHref = result.attr("href")
                    break
                }
            }
            
            if (targetHref == null && results.isNotEmpty()) {
                
                targetHref = results.first()?.attr("href")
            }
            
            if (targetHref != null) {
                val lyricsUrl = "https://rclyricsband.com/$targetHref"
                val lyricsConnection = (URL(lyricsUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0")
                    connectTimeout = 30_000
                    readTimeout = 30_000
                }
                
                val lyricsHtml = lyricsConnection.inputStream.bufferedReader().use { it.readText() }
                lyricsConnection.disconnect()
                
                val lyricsDoc = org.jsoup.Jsoup.parse(lyricsHtml)
                val lrcText = lyricsDoc.select("#lrc_text").html()
                    .replace("<br>", "\n")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                
                if (lrcText.isNotBlank()) {
                    return@withContext lrcText.trim()
                }
            }
            
            null
        } catch (e: Exception) {
            Timber.e(e, "Error fetching lyrics from rclyricsband")
            null
        }
    }
}

suspend fun fetchRandomMix(apiBaseUrl: String, limit: Int = 100, token: String? = null): List<Song> {
    val normalized = normalizeApiInput(apiBaseUrl)
    if (normalized.isBlank()) return emptyList()

    val randomUrl = "$normalized/tracks/shuffle?limit=$limit"

    return withContext(Dispatchers.IO) {
        val connection = (URL(randomUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            token?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val root = JSONObject(responseText)
        val items = root.optJSONArray("items") ?: return@withContext emptyList()

        buildList(items.length()) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val title = item.optString("title")
                val artist = item.optString("artist")
                if (title.isBlank() && artist.isBlank()) continue

                val stableColor = colorFromStableKey(item.optString("_id", "$title|$artist"))

                add(
                    Song(
                        id = item.optString("_id").takeIf { it.isNotBlank() },
                        title = title.ifBlank { "Unknown" },
                        artist = artist.ifBlank { "Unknown" },
                        album = item.optString("album").takeIf { it.isNotBlank() },
                        albumId = (item.optJSONObject("audio")?.optString("album_id") ?: item.optString("album_id")).takeIf { it.isNotBlank() },
                        durationSec = item.optInt("duration_sec").takeIf { it > 0 },
                        type = item.optString("type").takeIf { it.isNotBlank() },
                        samplingRateHz = item.optInt("sampling_rate_hz").takeIf { it > 0 },
                        spotifyUrl = normalizeApiInput(item.optString("spotify_url")).takeIf { it.isNotBlank() },
                        coverUrl = normalizeApiInput(item.optString("cover_url")).takeIf { it.isNotBlank() },
                        color = stableColor
                    )
                )
            }
        }
    }
}

suspend fun fetchPlaylists(apiBaseUrl: String, context: Context? = null, token: String? = null): List<Playlist> {
    val normalized = normalizeApiInput(apiBaseUrl)
    if (normalized.isBlank()) return emptyList()

    val url = "$normalized/playlists/available"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext emptyList()
            }
            stream.bufferedReader().use { it.readText() }
        } catch(e: Exception) {
            return@withContext emptyList()
        } finally {
            connection.disconnect()
        }
        
        val root = try {
            JSONObject(responseText)
        } catch(e: Exception) {
            return@withContext emptyList()
        }
        val items = root.optJSONArray("items") ?: return@withContext emptyList()

        buildList(items.length()) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val title = item.optString("name")
                val stableColor = colorFromStableKey(item.optString("id", title))

                val kindString = item.optString("kind", "")
                val capKind = if (kindString.isNotEmpty()) kindString.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } else null

                add(
                    Playlist(
                        id = item.optString("id"),
                        title = title.ifBlank { "Unknown" },
                        subtitle = capKind,
                        color = stableColor,
                        thumbnailUrl = item.optString("thumbnail_url").takeIf { it.isNotBlank() },
                        endpoint = item.optString("endpoint").takeIf { it.isNotBlank() },
                        kind = item.optString("kind").takeIf { it.isNotBlank() },
                        requiresAuth = item.optBoolean("requires_auth", false)
                    )
                )
            }
        }
    }
}

suspend fun fetchFavoriteIds(apiBaseUrl: String, context: Context? = null, token: String? = null): List<String> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return emptyList()

    val url = "$normalizedBase/me/favourites/ids?page=1&limit=200"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext emptyList()
            }
            stream.bufferedReader().use { it.readText() }
        } catch(e: Exception) {
            return@withContext emptyList()
        } finally {
            connection.disconnect()
        }
        
        val root = try {
            JSONObject(responseText)
        } catch(e: Exception) {
            return@withContext emptyList()
        }
        val idsArray = root.optJSONArray("ids") ?: return@withContext emptyList()

        buildList(idsArray.length()) {
            for (i in 0 until idsArray.length()) {
                add(idsArray.optString(i))
            }
        }
    }
}

suspend fun toggleFavorite(
    apiBaseUrl: String, 
    song: com.xstream.music.data.model.Song, 
    context: Context, 
    token: String? = null
): Boolean {
    val trackId = song.id ?: return false
    val isYouTube = song.type == "youtube"
    val isFavorite = com.xstream.music.core.cache.DataCache.favoriteIds.value.contains(trackId)
    
    
    val currentFavs = com.xstream.music.core.cache.DataCache.favoriteIds.value.toMutableSet()
    if (isFavorite) {
        currentFavs.remove(trackId)
    } else {
        currentFavs.add(trackId)
    }
    com.xstream.music.core.cache.DataCache.favoriteIds.value = currentFavs
    com.xstream.music.core.cache.DataCache.saveFavoriteIds(context, currentFavs)

    return if (isYouTube) {
        val videoId = trackId.removePrefix("yt_")
        val result = com.metrolist.innertube.YouTube.likeVideo(videoId, !isFavorite)
        result.isSuccess
    } else {
        if (isFavorite) {
            removeFavorite(apiBaseUrl, trackId, context, token)
        } else {
            addFavorite(apiBaseUrl, trackId, context, token)
        }
    }
}

suspend fun addSongsToFavorites(
    apiBaseUrl: String,
    songs: List<Song>,
    context: Context,
    token: String? = null
): Int {
    val currentFavoriteIds = DataCache.favoriteIds.value.toMutableSet()
    val songsToAdd = songs
        .filter { !it.id.isNullOrBlank() }
        .distinctBy { it.id }
        .filter { song -> song.id !in currentFavoriteIds }

    if (songsToAdd.isEmpty()) return 0

    var addedCount = 0
    songsToAdd.forEach { song ->
        val trackId = song.id ?: return@forEach
        val added = if (song.type == "youtube" || trackId.startsWith("yt_")) {
            com.metrolist.innertube.YouTube.likeVideo(trackId.removePrefix("yt_"), true).isSuccess
        } else {
            addFavorite(apiBaseUrl, trackId, context, token)
        }

        if (added) {
            currentFavoriteIds.add(trackId)
            addedCount++
        }
    }

    if (addedCount > 0) {
        DataCache.favoriteIds.value = currentFavoriteIds
        DataCache.saveFavoriteIds(context, currentFavoriteIds)
    }

    return addedCount
}

suspend fun syncYouTubeTrack(
    apiBaseUrl: String,
    youtubeUrl: String,
    context: Context? = null,
    token: String? = null
): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || youtubeUrl.isBlank()) return false

    val endpoint = "$normalizedBase/youtube/download"

    return withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")

            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }

            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val jsonInputString = JSONObject().apply {
                put("url", youtubeUrl)
            }.toString()

            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            JSONObject(responseText).optBoolean("ok", false)
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error syncing YouTube track")
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun addFavorite(apiBaseUrl: String, trackId: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || trackId.isBlank()) return false

    val url = "$normalizedBase/me/favourites"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val jsonInputString = JSONObject().apply {
                put("track_id", trackId)
            }.toString()

            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext false
            }
            val responseText = stream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            root.optBoolean("ok", false)
        } catch(e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun removeFavorite(apiBaseUrl: String, trackId: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || trackId.isBlank()) return false

    val url = "$normalizedBase/me/favourites/$trackId"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext false
            }
            val responseText = stream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            root.optBoolean("ok", false)
        } catch(e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun fetchFavoriteSongsAndUpdatedTime(apiBaseUrl: String, page: Int = 1, limit: Int = 20, context: Context? = null, token: String? = null): Pair<List<Song>, Long?> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return Pair(emptyList(), null)

    val url = "$normalizedBase/me/favourites?page=$page&limit=$limit"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext Pair(emptyList(), null)
            }
            stream.bufferedReader().use { it.readText() }
        } catch(e: Exception) {
            return@withContext Pair(emptyList(), null)
        } finally {
            connection.disconnect()
        }
        
        val root = try {
            JSONObject(responseText)
        } catch(e: Exception) {
            return@withContext Pair(emptyList(), null)
        }
        
        val lastUpdatedAt = if (root.has("last_updated_at")) {
             root.optDouble("last_updated_at").toLong()
        } else null
        
        val items = root.optJSONArray("items") ?: return@withContext Pair(emptyList(), lastUpdatedAt)

        val songs = buildList(items.length()) {
            for (i in 0 until items.length()) {
                val itemOuter = items.optJSONObject(i) ?: continue
                val track = itemOuter.optJSONObject("track") ?: continue
                
                val audioObj = track.optJSONObject("audio")
                val spotifyObj = track.optJSONObject("spotify")
                
                val title = audioObj?.optString("title") ?: track.optString("title")
                val artist = audioObj?.optString("artist") ?: track.optString("artist")
                
                if (title.isBlank() && artist.isBlank()) continue

                val durationSec = audioObj?.optInt("duration_sec") ?: track.optInt("duration_sec")
                val audioType = audioObj?.optString("type") ?: track.optString("type")
                val samplingRateHz = audioObj?.optInt("sampling_rate_hz") ?: track.optInt("sampling_rate_hz")
                val bitrateKbps = audioObj?.optInt("bitrate_kbps")
                val fileSize = audioObj?.optLong("file_size") ?: track.optLong("file_size")
                
                val coverUrl = spotifyObj?.optString("cover_url") ?: track.optString("cover_url")
                val spotifyUrl = spotifyObj?.optString("url") ?: track.optString("spotify_url")
                
                val stableColor = colorFromStableKey(track.optString("_id", "$title|$artist"))

                add(
                    Song(
                        id = track.optString("_id").takeIf { it.isNotBlank() },
                        title = title.ifBlank { "Unknown" },
                        artist = artist.ifBlank { "Unknown" },
                        album = audioObj?.optString("album") ?: track.optString("album").takeIf { it.isNotBlank() },
                        albumId = (audioObj?.optString("album_id") ?: track.optString("album_id")).takeIf { it.isNotBlank() },
                        durationSec = durationSec.takeIf { it > 0 },
                        type = audioType.takeIf { it.isNotBlank() },
                        samplingRateHz = samplingRateHz.takeIf { it > 0 },
                        bitrateKbps = bitrateKbps?.takeIf { it > 0 },
                        spotifyUrl = normalizeApiInput(spotifyUrl).takeIf { it.isNotBlank() },
                        coverUrl = normalizeApiInput(coverUrl).takeIf { it.isNotBlank() },
                        color = stableColor
                    )
                )
            }
        }
        Pair(songs, lastUpdatedAt)
    }
}

suspend fun fetchFavoriteSongs(apiBaseUrl: String, page: Int = 1, limit: Int = 20, context: Context? = null, token: String? = null): List<Song> {
    return fetchFavoriteSongsAndUpdatedTime(apiBaseUrl, page, limit, context, token).first
}

suspend fun fetchSharedPlaylist(apiBaseUrl: String, playlistId: String): Pair<Playlist, List<Song>>? {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || playlistId.isBlank()) return null

    val url = "$normalizedBase/share/playlists/$playlistId"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext null
            }
            stream.bufferedReader().use { it.readText() }
        } catch(e: Exception) {
            return@withContext null
        } finally {
            connection.disconnect()
        }
        
        val root = try {
            JSONObject(responseText)
        } catch(e: Exception) {
            return@withContext null
        }

        val title = root.optString("name")
        val playlistIdReal = root.optString("playlist_id")
        val stableColor = colorFromStableKey(playlistIdReal.ifBlank { title })
        
        val playlist = Playlist(
            id = playlistIdReal,
            title = title.ifBlank { "Unknown" },
            subtitle = "Shared Playlist",
            color = stableColor,
            thumbnailUrl = root.optString("cover_url").takeIf { it.isNotBlank() },
            endpoint = "/share/playlists/$playlistIdReal",
            kind = "shared_playlist",
            requiresAuth = false 
        )

        val tracksArray = root.optJSONArray("tracks")
        val songs = mutableListOf<Song>()
        if (tracksArray != null) {
            for (i in 0 until tracksArray.length()) {
                val item = tracksArray.getJSONObject(i)
                val audioObj = item.optJSONObject("audio")
                val spotifyObj = item.optJSONObject("spotify")
                
                val t = audioObj?.optString("title") ?: item.optString("title")
                val a = audioObj?.optString("artist") ?: item.optString("artist")
                val c = colorFromStableKey(item.optString("_id", "$t|$a"))

                songs.add(
                    Song(
                        id = item.optString("_id"),
                        title = t.ifBlank { "Unknown" },
                        artist = a.ifBlank { "Unknown" },
                        album = audioObj?.optString("album"),
                        albumId = audioObj?.optString("album_id") ?: item.optString("album_id"),
                        durationSec = audioObj?.optInt("duration_sec"),
                        type = audioObj?.optString("type"),
                        samplingRateHz = audioObj?.optInt("sampling_rate_hz"),
                        coverUrl = spotifyObj?.optString("cover_url"),
                        spotifyUrl = spotifyObj?.optString("url"),
                        color = c
                    )
                )
            }
        }
        
        Pair(playlist, songs)
    }
}

suspend fun fetchPlaylistMetadata(apiBaseUrl: String, playlistId: String, context: Context? = null, token: String? = null): Playlist? {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || playlistId.isBlank()) return null

    
    val shareResult = fetchSharedPlaylist(apiBaseUrl, playlistId)
    if (shareResult != null) return shareResult.first

    val url = "$normalizedBase/playlists/$playlistId"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext null
            }
            stream.bufferedReader().use { it.readText() }
        } catch(e: Exception) {
            return@withContext null
        } finally {
            connection.disconnect()
        }
        
        val item = try {
            JSONObject(responseText)
        } catch(e: Exception) {
            return@withContext null
        }

        val title = item.optString("name")
        val stableColor = colorFromStableKey(item.optString("id", title))
        val kindString = item.optString("kind", "")
        val capKind = if (kindString.isNotEmpty()) kindString.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } else null

        Playlist(
            id = item.optString("id"),
            title = title.ifBlank { "Unknown" },
            subtitle = capKind,
            color = stableColor,
            thumbnailUrl = item.optString("thumbnail_url").takeIf { it.isNotBlank() },
            endpoint = "/playlists/${item.optString("id")}",
            kind = kindString,
            requiresAuth = false 
        )
    }
}

suspend fun fetchPlaylistSongs(apiBaseUrl: String, playlistEndpoint: String, page: Int = 1, limit: Int = 75, context: Context? = null, token: String? = null): List<Song> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    val normalizedEndpoint = normalizeApiInput(playlistEndpoint)
    if (normalizedBase.isBlank() || normalizedEndpoint.isBlank()) return emptyList()
    val endpointPath = if (normalizedEndpoint.startsWith("http")) {
        runCatching { URL(normalizedEndpoint).path }.getOrDefault(normalizedEndpoint)
    } else {
        normalizedEndpoint
    }
    val sharedPlaylistIdFallback = Regex("""^/?me/playlists/([^/?#]+)/tracks/?$""")
        .find(endpointPath)
        ?.groupValues
        ?.getOrNull(1)

    val url = if (normalizedEndpoint.startsWith("http")) {
        if (normalizedEndpoint.contains("?")) "$normalizedEndpoint&page=$page&limit=$limit" else "$normalizedEndpoint?page=$page&limit=$limit"
    } else {
        val path = if (normalizedEndpoint.startsWith("/")) normalizedEndpoint else "/$normalizedEndpoint"
        "$normalizedBase$path?page=$page&limit=$limit"
    }

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                if (responseCode == 404 && page == 1 && sharedPlaylistIdFallback != null) {
                    return@withContext fetchSharedPlaylist(normalizedBase, sharedPlaylistIdFallback)?.second.orEmpty()
                }
                return@withContext emptyList()
            }
            stream.bufferedReader().use { it.readText() }
        } catch(e: Exception) {
            return@withContext emptyList()
        } finally {
            connection.disconnect()
        }

        val root = try {
            JSONObject(responseText)
        } catch(e: Exception) {
            return@withContext emptyList()
        }
        val items = root.optJSONArray("items") ?: root.optJSONArray("tracks") ?: return@withContext emptyList()

        buildList(items.length()) {
            for (i in 0 until items.length()) {
                val itemOuter = items.optJSONObject(i) ?: continue
                val item = itemOuter.optJSONObject("track") ?: itemOuter
                
                val audioObj = item.optJSONObject("audio")
                val spotifyObj = item.optJSONObject("spotify")
                
                val title = audioObj?.optString("title") ?: item.optString("title")
                val artist = audioObj?.optString("artist") ?: item.optString("artist")
                if (title.isBlank() && artist.isBlank()) continue

                val durationSec = audioObj?.optInt("duration_sec") ?: item.optInt("duration_sec")
                val audioType = audioObj?.optString("type") ?: item.optString("type")
                val samplingRateHz = audioObj?.optInt("sampling_rate_hz") ?: item.optInt("sampling_rate_hz")
                val bitrateKbps = audioObj?.optInt("bitrate_kbps")
                val fileSize = audioObj?.optLong("file_size") ?: item.optLong("file_size")

                val coverUrl = spotifyObj?.optString("cover_url") ?: item.optString("cover_url")
                val spotifyUrl = spotifyObj?.optString("url") ?: item.optString("spotify_url")

                val stableColor = colorFromStableKey(item.optString("_id", "$title|$artist"))

                add(
                    Song(
                        id = item.optString("_id").takeIf { it.isNotBlank() },
                        title = title.ifBlank { "Unknown" },
                        artist = artist.ifBlank { "Unknown" },
                        album = audioObj?.optString("album") ?: item.optString("album").takeIf { it.isNotBlank() },
                        albumId = (audioObj?.optString("album_id") ?: item.optString("album_id")).takeIf { it.isNotBlank() },
                        durationSec = durationSec.takeIf { it > 0 },
                        type = audioType.takeIf { it.isNotBlank() },
                        samplingRateHz = samplingRateHz.takeIf { it > 0 },
                        bitrateKbps = bitrateKbps?.takeIf { it > 0 },
                        spotifyUrl = normalizeApiInput(spotifyUrl).takeIf { it.isNotBlank() },
                        coverUrl = normalizeApiInput(coverUrl).takeIf { it.isNotBlank() },
                        color = stableColor
                    )
                )
            }
        }
    }
}

suspend fun createPlaylist(apiBaseUrl: String, name: String, context: Context? = null, token: String? = null): Playlist? {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return null

    val url = "$normalizedBase/me/playlists"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val body = JSONObject().apply {
                put("name", name)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@withContext null

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val item = JSONObject(responseText)
            
            val id = item.optString("playlist_id")
            val title = item.optString("name")
            val coverUrl = item.optString("cover_url").takeIf { it.isNotBlank() }
            val thumbnailUrl = item.optString("thumbnail_url").takeIf { it.isNotBlank() }
            val normalCoverUrl = item.optString("normal_thumbnail").takeIf { it.isNotBlank() }
            
            Playlist(
                id = id,
                title = title.ifBlank { "Unknown" },
                subtitle = "Playlist",
                color = colorFromStableKey(id.ifBlank { title }),
                thumbnailUrl = thumbnailUrl ?: coverUrl,
                endpoint = "/me/playlists/$id/tracks",
                kind = "user_playlist"
            )
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun deletePlaylist(apiBaseUrl: String, playlistId: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || playlistId.isBlank()) return false

    val url = "$normalizedBase/me/playlists/$playlistId"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@withContext false
            
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            root.optBoolean("ok", false)
        } catch(e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun renamePlaylist(apiBaseUrl: String, playlistId: String, newName: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || playlistId.isBlank()) return false

    val url = "$normalizedBase/me/playlists/$playlistId"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val body = JSONObject().apply {
                put("name", newName)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@withContext false
            
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            root.has("playlist_id")
        } catch(e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun fetchFriendSettings(apiUrl: String, token: String): FriendSettingsResponse? {
    val normalizedBase = normalizeApiInput(apiUrl)
    val url = "$normalizedBase/friends/settings"
    return withContext(Dispatchers.IO) {
        try {
            val response = makeRequest(url, "GET", token = token)
            GsonBuilder().create().fromJson(response, FriendSettingsResponse::class.java)
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error fetching friend settings")
            null
        }
    }
}

suspend fun updateFriendSettings(apiUrl: String, token: String, settings: FriendSettings): Boolean {
    val normalizedBase = normalizeApiInput(apiUrl)
    val url = "$normalizedBase/friends/settings"
    val body = GsonBuilder().create().toJson(settings)
    return withContext(Dispatchers.IO) {
        try {
            val response = makeRequest(url, "PUT", body, token)
            val res = GsonBuilder().create().fromJson(response, BaseResponse::class.java)
            res.ok
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error updating friend settings")
            false
        }
    }
}

suspend fun removeFriend(apiUrl: String, token: String, friendId: Long): Boolean {
    val url = "$apiUrl/friends/$friendId"
    return withContext(Dispatchers.IO) {
        try {
            val response = makeRequest(url, "DELETE", token = token)
            val res = GsonBuilder().create().fromJson(response, BaseResponse::class.java)
            res.ok
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error removing friend $friendId")
            false
        }
    }
}

suspend fun fetchFriends(apiUrl: String, token: String): List<Friend> {
    val normalizedBase = normalizeApiInput(apiUrl)
    if (normalizedBase.isBlank() || token.isBlank()) return emptyList()

    val url = "$normalizedBase/friends"
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL(url)
            connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                val gson = GsonBuilder().create()
                val response = gson.fromJson(responseStr, FriendsResponse::class.java)
                if (response.ok) {
                    response.friends ?: emptyList()
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e)
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }
}

suspend fun fetchFriendsListening(apiBaseUrl: String, token: String?): List<FriendListening> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || token.isNullOrBlank()) return emptyList()

    val url = "$normalizedBase/friends/listening"
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL(url)
            connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                val gson = GsonBuilder().create()
                val response = gson.fromJson(responseStr, FriendsListeningResponse::class.java)
                if (response.ok) {
                    response.listening ?: emptyList()
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e)
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }
}

suspend fun sendFriendRequest(apiBaseUrl: String, token: String?, toUserId: Long): Result<Boolean> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || token.isNullOrBlank()) return Result.failure(Exception("Invalid setup"))

    val url = "$normalizedBase/friends/request"
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL(url)
            connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("accept", "application/json")
            connection.doOutput = true

            val jsonBody = JSONObject().apply {
                put("to", toUserId)
            }.toString()

            connection.outputStream.use { os ->
                val input = jsonBody.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            if (connection.responseCode in 200..299) {
                Result.success(true)
            } else {
                val errorStr = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { null }
                val detail = try {
                    errorStr?.let { JSONObject(it).optString("detail") }
                } catch (e: Exception) { null }
                Result.failure(Exception(detail ?: "Failed to send request"))
            }
        } catch (e: Exception) {
            Timber.e(e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}

suspend fun fetchFriendRequests(apiBaseUrl: String, token: String?): List<FriendRequest> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || token.isNullOrBlank()) return emptyList()

    val url = "$normalizedBase/friends/requests"
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL(url)
            connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                val gson = GsonBuilder().create()
                val response = gson.fromJson(responseStr, FriendRequestsResponse::class.java)
                if (response.ok) {
                    response.requests ?: emptyList()
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e)
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }
}

suspend fun acceptFriendRequest(apiBaseUrl: String, token: String?, userId: Long): Result<Boolean> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || token.isNullOrBlank()) return Result.failure(Exception("Invalid setup"))

    val url = "$normalizedBase/friends/accept"
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL(url)
            connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("accept", "application/json")
            connection.doOutput = true

            val jsonBody = JSONObject().apply {
                put("userId", userId)
            }.toString()

            connection.outputStream.use { os ->
                val input = jsonBody.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            if (connection.responseCode in 200..299) {
                Result.success(true)
            } else {
                val errorStr = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { null }
                val detail = try {
                    errorStr?.let { JSONObject(it).optString("detail") }
                } catch (e: Exception) { null }
                Result.failure(Exception(detail ?: "Failed to accept request"))
            }
        } catch (e: Exception) {
            Timber.e(e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}

suspend fun addTracksToPlaylist(apiBaseUrl: String, playlistId: String, trackIds: List<String>, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || playlistId.isBlank() || trackIds.isEmpty()) return false

    val url = "$normalizedBase/me/playlists/$playlistId/tracks"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val jsonBody = JSONObject().apply {
                put("track_ids", org.json.JSONArray(trackIds))
            }.toString()

            connection.outputStream.use { os ->
                val input = jsonBody.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext false
            }
            val responseText = stream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            root.optBoolean("ok", false)
        } catch (e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun addTrackToPlaylist(apiBaseUrl: String, playlistId: String, trackId: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || playlistId.isBlank() || trackId.isBlank()) return false

    val url = "$normalizedBase/me/playlists/$playlistId/tracks"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val jsonInputString = JSONObject().apply {
                put("track_id", trackId)
            }.toString()

            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext false
            }
            val responseText = stream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            root.optBoolean("ok", false) || root.optBoolean("already_exists", false)
        } catch(e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun removeTrackFromPlaylist(apiBaseUrl: String, playlistId: String, trackId: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || playlistId.isBlank() || trackId.isBlank()) return false

    val url = "$normalizedBase/me/playlists/$playlistId/tracks/$trackId"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext false
            }
            val responseText = stream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            root.optBoolean("ok", false) && root.optBoolean("deleted", false)
        } catch(e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

fun parseJam(jamObj: JSONObject): Jam {
    val playbackObj = jamObj.optJSONObject("playback") ?: JSONObject()
    val settingsObj = jamObj.optJSONObject("settings") ?: JSONObject()
    
    val queueArr = jamObj.optJSONArray("queue") ?: org.json.JSONArray()
    val queue = buildList {
        for (i in 0 until queueArr.length()) {
            add(queueArr.optString(i))
        }
    }
    
    val membersArr = jamObj.optJSONArray("members") ?: org.json.JSONArray()
    val members = buildList {
        for (i in 0 until membersArr.length()) {
            val m = membersArr.optJSONObject(i) ?: continue
            add(JamMember(
                userId = m.optLong("user_id"),
                role = m.optString("role"),
                firstName = m.optString("first_name"),
                profileUrl = m.optString("profile_url")
            ))
        }
    }
    
    return Jam(
        id = jamObj.optString("_id", ""),
        hostUserId = jamObj.optLong("host_user_id"),
        createdAt = jamObj.optDouble("created_at"),
        updatedAt = jamObj.optDouble("updated_at"),
        playback = JamPlayback(
            trackId = playbackObj.optString("track_id", ""),
            durationSec = playbackObj.optInt("duration_sec").takeIf { it > 0 },
            positionSec = playbackObj.optDouble("position_sec"),
            startedAt = playbackObj.optDouble("started_at"),
            isPlaying = playbackObj.optBoolean("is_playing")
        ),
        queue = queue,
        members = members,
        settings = JamSettings(
            allowSeek = settingsObj.optBoolean("allow_seek"),
            allowQueueEdit = settingsObj.optBoolean("allow_queue_edit")
        ),
        serverTime = jamObj.optDouble("server_time").takeIf { !it.isNaN() && it > 0.0 }
    )
}

suspend fun createJam(apiBaseUrl: String, request: CreateJamRequest, context: Context? = null, token: String? = null): JamResponse {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return JamResponse(false)

    val url = "$normalizedBase/jam/create"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val body = JSONObject().apply {
                put("track_id", request.trackId)
                put("position_sec", request.positionSec)
                put("is_playing", request.isPlaying)
                put("queue", org.json.JSONArray(request.queue))
                put("settings", JSONObject().apply {
                    put("allow_seek", request.settings.allowSeek)
                    put("allow_queue_edit", request.settings.allowQueueEdit)
                })
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            
            val root = JSONObject(responseText)
            val ok = root.optBoolean("ok", false)
            val jamObj = root.optJSONObject("jam")
            
            if (ok && jamObj != null) {
                JamResponse(ok = true, jam = parseJam(jamObj))
            } else {
                JamResponse(ok = false)
            }
        } catch (e: Exception) {
            JamResponse(ok = false)
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun joinJam(apiBaseUrl: String, jamId: String, context: Context? = null, token: String? = null): JamResponse {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || jamId.isBlank()) return JamResponse(false)

    val url = "$normalizedBase/jam/$jamId/join"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Length", "0")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            doOutput = false 
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            
            Timber.tag("StreamXApi").d("joinJam response: $responseText")
            
            val root = JSONObject(responseText)
            val ok = root.optBoolean("ok", false)
            val jamObj = root.optJSONObject("jam")
            
            if (ok && jamObj != null) {
                JamResponse(ok = true, jam = parseJam(jamObj))
            } else {
                JamResponse(ok = false)
            }
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error joining jam")
            JamResponse(ok = false)
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun fetchJam(apiBaseUrl: String, jamId: String, context: Context? = null, token: String? = null): JamResponse {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || jamId.isBlank()) return JamResponse(false)

    val url = "$normalizedBase/jam/$jamId"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            
            val root = JSONObject(responseText)
            val ok = root.optBoolean("ok", false)
            val jamObj = root.optJSONObject("jam")
            
            if (ok && jamObj != null) {
                JamResponse(ok = true, jam = parseJam(jamObj))
            } else {
                JamResponse(ok = false)
            }
        } catch (e: Exception) {
            JamResponse(ok = false)
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun jamPlay(apiBaseUrl: String, jamId: String, context: Context? = null, token: String? = null): Boolean {
    Timber.tag("StreamXApi").d("Sending Play command for jam $jamId")
    return sendJamCommand(apiBaseUrl, jamId, "play", context, token)
}

suspend fun jamPause(apiBaseUrl: String, jamId: String, context: Context? = null, token: String? = null): Boolean {
    Timber.tag("StreamXApi").d("Sending Pause command for jam $jamId")
    return sendJamCommand(apiBaseUrl, jamId, "pause", context, token)
}

suspend fun jamNext(apiBaseUrl: String, jamId: String, context: Context? = null, token: String? = null): Boolean {
    Timber.tag("StreamXApi").d("Sending Next command for jam $jamId")
    return sendJamCommand(apiBaseUrl, jamId, "next", context, token)
}

suspend fun jamPrevious(apiBaseUrl: String, jamId: String, context: Context? = null, token: String? = null): Boolean {
    Timber.tag("StreamXApi").d("Sending Previous command for jam $jamId")
    return sendJamCommand(apiBaseUrl, jamId, "previous", context, token)
}

suspend fun jamSeek(apiBaseUrl: String, jamId: String, positionSec: Double, context: Context? = null, token: String? = null): Boolean {
    Timber.tag("StreamXApi").d("Sending Seek command for jam $jamId to $positionSec")
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || jamId.isBlank()) return false
    val url = "$normalizedBase/jam/$jamId/seek"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            doOutput = true
        }
        try {
            val body = JSONObject().apply { put("position_sec", positionSec) }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            Timber.tag("StreamXApi").d("Seek response code: $code")
            code in 200..299
        } catch (e: Exception) { 
            Timber.tag("StreamXApi").e(e, "Seek error")
            false 
        } finally { connection.disconnect() }
    }
}

data class InviteJamResult(
    val ok: Boolean,
    val cooldownSec: Int? = null,
    val retryAfterSec: Int? = null,
    val detail: String? = null
)

suspend fun inviteFriendToJam(apiBaseUrl: String, jamId: String, targetUserId: Long, context: Context? = null, token: String? = null): InviteJamResult {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || jamId.isBlank()) return InviteJamResult(ok = false, detail = "Invalid jam invite request")
    val url = "$normalizedBase/friends/invite-jam"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            doOutput = true
        }
        try {
            val body = JSONObject().apply { 
                put("toUserId", targetUserId)
                put("jamId", jamId)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            Timber.tag("StreamXApi").d("Invite to Jam response code: $code")

            val responseText = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val root = runCatching { JSONObject(responseText) }.getOrNull()
            val detailValue = root?.opt("detail")
            val detailMessage = when (detailValue) {
                is JSONObject -> detailValue.optString("message").takeIf { it.isNotBlank() }
                is String -> detailValue.takeIf { it.isNotBlank() }
                else -> null
            }
            val retryAfterFromBody = when (detailValue) {
                is JSONObject -> detailValue.optInt("retry_after_sec").takeIf { it > 0 }
                else -> null
            } ?: root?.optInt("retry_after_sec")?.takeIf { it > 0 }
            val retryAfterFromHeader = connection.getHeaderField("Retry-After")?.toIntOrNull()?.takeIf { it > 0 }

            if (code in 200..299) {
                InviteJamResult(
                    ok = root?.optBoolean("ok", true) ?: true,
                    cooldownSec = root?.optInt("cooldown_sec")?.takeIf { it > 0 },
                    detail = detailMessage
                )
            } else {
                InviteJamResult(
                    ok = false,
                    retryAfterSec = retryAfterFromBody ?: retryAfterFromHeader,
                    detail = detailMessage ?: "Failed to send invite"
                )
            }
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Invite to Jam error")
            InviteJamResult(ok = false, detail = "Failed to send invite")
        } finally { connection.disconnect() }
    }
}

suspend fun leaveJam(apiBaseUrl: String, jamId: String, context: Context? = null, token: String? = null): Boolean {
    Timber.tag("StreamXApi").d("Leaving jam $jamId")
    return sendJamCommand(apiBaseUrl, jamId, "leave", context, token)
}

suspend fun jamAddQueue(
    apiBaseUrl: String,
    jamId: String,
    trackId: String,
    position: Int = 0,
    context: Context? = null,
    token: String? = null
): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || jamId.isBlank() || trackId.isBlank()) return false
    val url = "$normalizedBase/jam/$jamId/queue/add"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            doOutput = true
        }
        try {
            val body = JSONObject().apply {
                put("track_id", trackId)
                put("position", position.coerceAtLeast(0))
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            Timber.tag("StreamXApi").d("Jam Add Queue response code: $code")
            code in 200..299
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Jam Add Queue error")
            false
        } finally { connection.disconnect() }
    }
}

suspend fun jamReorderQueue(apiBaseUrl: String, jamId: String, queue: List<String>, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || jamId.isBlank()) return false
    val url = "$normalizedBase/jam/$jamId/queue/reorder"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            doOutput = true
        }
        try {
            val body = JSONObject().apply {
                put("queue", org.json.JSONArray(queue))
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            Timber.tag("StreamXApi").d("Jam Reorder Queue response code: $code")
            code in 200..299
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Jam Reorder Queue error")
            false
        } finally { connection.disconnect() }
    }
}

suspend fun fetchUserProfile(apiBaseUrl: String, context: Context? = null, token: String? = null): UserProfile? {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return null

    val url = "$normalizedBase/auth/me"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@withContext null

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            val userObj = root.optJSONObject("user") ?: return@withContext null
            val telegramObj = userObj.optJSONObject("telegram")
            
            UserProfile(
                id = userObj.optLong("_id"),
                username = telegramObj?.optString("username") ?: userObj.optString("username"),
                firstName = userObj.optString("first_name"),
                photoUrl = userObj.optString("photo_url"),
                profileUrl = userObj.optString("profile_url")
            )
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error fetching user profile")
            null
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun fetchCurrentUserRole(apiBaseUrl: String, context: Context? = null, token: String? = null): String? {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return null

    val url = "$normalizedBase/auth/me"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            val userObj = root.optJSONObject("user") ?: return@withContext null
            userObj.optString("role").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error fetching current user role")
            null
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun fetchServerLogs(apiBaseUrl: String, context: Context? = null, token: String? = null): List<String> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return emptyList()

    val url = "$normalizedBase/logs/"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            val logsArray = root.optJSONArray("logs") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until logsArray.length()) {
                    val line = logsArray.optString(i)
                    if (line.isNotBlank()) add(line)
                }
            }
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error fetching server logs")
            emptyList()
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun registerFcmToken(apiBaseUrl: String, token: String, authToken: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || token.isBlank()) return false

    val url = "$normalizedBase/auth/fcm-token"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            
            authToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            val body = JSONObject().apply {
                put("fcm_token", token)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = connection.responseCode
            Timber.tag("StreamXApi").d("FCM token registration response code: $responseCode")
            responseCode in 200..299
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Error registering FCM token")
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun fetchUserPlaylists(apiBaseUrl: String, context: Context? = null, token: String? = null): List<Playlist> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return emptyList()

    val url = "$normalizedBase/me/playlists"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        val responseText = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                return@withContext emptyList()
            }
            stream.bufferedReader().use { it.readText() }
        } catch(e: Exception) {
            return@withContext emptyList()
        } finally {
            connection.disconnect()
        }
        
        val root = try {
            JSONObject(responseText)
        } catch(e: Exception) {
            return@withContext emptyList()
        }
        val items = root.optJSONArray("items") ?: return@withContext emptyList()

        buildList(items.length()) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val title = item.optString("name")
                val stableColor = colorFromStableKey(item.optString("playlist_id", title))

                val coverUrl = item.optString("cover_url").takeIf { it.isNotBlank() }
                val thumbnailsArray = item.optJSONArray("thumbnails")
                val firstThumbnail = if (thumbnailsArray != null && thumbnailsArray.length() > 0) {
                    thumbnailsArray.optString(0)
                } else null

                add(
                    Playlist(
                        id = item.optString("playlist_id"),
                        title = title.ifBlank { "Unknown" },
                        subtitle = "Playlist",
                        color = stableColor,
                        thumbnailUrl = coverUrl ?: firstThumbnail,
                        endpoint = "/me/playlists/${item.optString("playlist_id")}/tracks",
                        kind = "user_playlist"
                    )
                )
            }
        }
    }
}

private suspend fun sendJamCommand(apiBaseUrl: String, jamId: String, command: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || jamId.isBlank()) return false
    val url = "$normalizedBase/jam/$jamId/$command"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
        }
        try { 
            val code = connection.responseCode
            Timber.tag("StreamXApi").d("Command $command response code: $code")
            code in 200..299 
        } catch (e: Exception) {
            Timber.tag("StreamXApi").e(e, "Command $command error")
            false
        } finally { connection.disconnect() }
        }
        }

        suspend fun registerUser(apiBaseUrl: String, userid: Long, username: String, password: String): RegisterResponse {
        val normalizedBase = normalizeApiInput(apiBaseUrl)
        if (normalizedBase.isBlank()) return RegisterResponse(ok = false, detail = "Invalid API URL")

        return withContext(Dispatchers.IO) {
        val registerUrl = "$normalizedBase/auth/register"
        val connection = (URL(registerUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            val body = JSONObject().apply {
                put("userid", userid)
                put("username", username)
                put("password", password)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = connection.responseCode
            val responseText = try {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }

            if (responseText.isNotBlank()) {
                try {
                    val root = JSONObject(responseText)
                    if (responseCode in 200..299) {
                        RegisterResponse(ok = true)
                    } else {
                        val detail = root.optString("detail", "Registration failed")
                        RegisterResponse(ok = false, detail = detail)
                    }
                } catch (e: Exception) {
                    RegisterResponse(ok = responseCode in 200..299, detail = "Error parsing response")
                }
            } else {
                RegisterResponse(ok = responseCode in 200..299)
            }
        } catch (e: Exception) {
            RegisterResponse(ok = false, detail = e.message)
        } finally {
            connection.disconnect()
        }
        }
        }

suspend fun deleteAdminTracks(baseUrl: String, token: String?, trackIds: List<String>): Boolean {
    if (baseUrl.isBlank() || token == null || trackIds.isEmpty()) return false
    val normalizedBase = normalizeApiInput(baseUrl)
    return withContext(Dispatchers.IO) {
        val url = URL("$normalizedBase/admin/tracks/delete")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doOutput = true

            val jsonBody = JSONObject().apply {
                val array = org.json.JSONArray()
                trackIds.forEach { array.put(it) }
                put("track_ids", array)
            }
            connection.outputStream.use { os ->
                val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            connection.responseCode in 200..299
        } catch (e: Exception) {
            Timber.e(e, "Error deleting admin tracks")
            false
        } finally {
            connection.disconnect()
        }
    }
}

        suspend fun validateUser(apiBaseUrl: String, userid: Long, otp: String): ValidateResponse {
        val normalizedBase = normalizeApiInput(apiBaseUrl)
        if (normalizedBase.isBlank()) return ValidateResponse(ok = false, detail = "Invalid API URL")

        return withContext(Dispatchers.IO) {
        val validateUrl = "$normalizedBase/auth/validate?set_cookie=false"
        val connection = (URL(validateUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            val body = JSONObject().apply {
                put("userid", userid)
                put("otp", otp)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = connection.responseCode
            val responseText = try {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }

            if (responseText.isNotBlank()) {
                try {
                    val root = JSONObject(responseText)
                    if (responseCode in 200..299) {
                        ValidateResponse(
                            ok = root.optBoolean("ok", true),
                            user_id = root.optLong("user_id").takeIf { it != 0L },
                            token = root.optString("token").takeIf { it.isNotBlank() },
                            first_name = root.optString("first_name").takeIf { it.isNotBlank() },
                            profile_url = root.optString("profile_url").takeIf { it.isNotBlank() },
                            photo_url = root.optString("photo_url").takeIf { it.isNotBlank() }
                        )
                    } else {
                        val detail = root.optString("detail", "Validation failed")
                        ValidateResponse(ok = false, detail = detail)
                    }
                } catch (e: Exception) {
                    ValidateResponse(ok = responseCode in 200..299, detail = "Error parsing response")
                }
            } else {
                ValidateResponse(ok = responseCode in 200..299)
            }
        } catch (e: Exception) {
            ValidateResponse(ok = false, detail = e.message)
        } finally {
            connection.disconnect()
        }
        }
        }

suspend fun fetchAlbum(apiBaseUrl: String, albumId: String, context: Context? = null, token: String? = null): Pair<AlbumData?, List<Song>> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || albumId.isBlank()) return Pair(null, emptyList())

    val url = "$normalizedBase/albums/$albumId"

    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseText)
            
            val albumObj = root.optJSONObject("album")
            val albumData = if (albumObj != null) {
                AlbumData(
                    _id = albumObj.optString("_id"),
                    artist = albumObj.optString("artist"),
                    cover_url = normalizeApiInput(albumObj.optString("cover_url")).takeIf { it.isNotBlank() },
                    duration_total = albumObj.optInt("duration_total"),
                    title = albumObj.optString("title"),
                    tracks_count = albumObj.optInt("tracks_count")
                )
            } else null

            val tracksArray = root.optJSONArray("tracks") ?: org.json.JSONArray()
            val songs = buildList(tracksArray.length()) {
                for (i in 0 until tracksArray.length()) {
                    val trackOuter = tracksArray.optJSONObject(i) ?: continue
                    val track = trackOuter.optJSONObject("audio") ?: trackOuter
                    val spotify = trackOuter.optJSONObject("spotify")

                    val title = track.optString("title").ifBlank { "Unknown" }
                    val artist = track.optString("artist").ifBlank { "Unknown" }
                    val stableColor = colorFromStableKey(trackOuter.optString("_id", "$title|$artist"))

                    add(Song(
                        id = trackOuter.optString("_id").takeIf { it.isNotBlank() },
                        title = title,
                        artist = artist,
                        album = track.optString("album").takeIf { it.isNotBlank() },
                        albumId = (track.optJSONObject("audio")?.optString("album_id") ?: track.optString("album_id")).takeIf { it.isNotBlank() },
                        durationSec = track.optInt("duration_sec").takeIf { it > 0 },
                        type = track.optString("type").takeIf { it.isNotBlank() },
                        samplingRateHz = track.optInt("sampling_rate_hz").takeIf { it > 0 },
                        bitrateKbps = track.optInt("bitrate_kbps").takeIf { it > 0 },
                        coverUrl = normalizeApiInput(spotify?.optString("cover_url") ?: trackOuter.optString("cover_url")).takeIf { it.isNotBlank() },
                        spotifyUrl = normalizeApiInput(spotify?.optString("url") ?: trackOuter.optString("spotify_url")).takeIf { it.isNotBlank() },
                        color = stableColor
                    ))
                }
            }
            Pair(albumData, songs)
        } catch (e: Exception) {
            Timber.e(e, "Error fetching album")
            Pair(null, emptyList())
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun saveAlbum(apiBaseUrl: String, albumId: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || albumId.isBlank()) return false
    val url = "$normalizedBase/me/albums"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val jsonInputString = "{\"album_id\": \"$albumId\"}"
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(charset("utf-8"))
                os.write(input, 0, input.size)
            }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            Timber.e(e, "Error saving album")
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun removeAlbum(apiBaseUrl: String, albumId: String, context: Context? = null, token: String? = null): Boolean {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank() || albumId.isBlank()) return false
    val url = "$normalizedBase/me/albums/$albumId"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("accept", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            connection.responseCode in 200..299
        } catch (e: Exception) {
            Timber.e(e, "Error removing album")
            false
        } finally {
            connection.disconnect()
        }
    }
}

suspend fun fetchSavedAlbums(apiBaseUrl: String, page: Int = 1, limit: Int = 50, context: Context? = null, token: String? = null): List<AlbumData> {
    val normalizedBase = normalizeApiInput(apiBaseUrl)
    if (normalizedBase.isBlank()) return emptyList()
    val url = "$normalizedBase/me/albums?page=$page&limit=$limit"
    return withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
            val effectiveToken = token ?: context?.let { AuthPreferences.getUser(it)?.token }
            effectiveToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
                setRequestProperty("X-Auth-Token", it)
            }
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            
            val root = JSONObject(responseText)
            val items = root.optJSONArray("items") ?: return@withContext emptyList()
            
            val albums = mutableListOf<AlbumData>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val albumObj = item.optJSONObject("album") ?: continue
                val albumId = albumObj.optString("_id").takeIf { it.isNotBlank() } ?: continue
                val albumTitle = albumObj.optString("title").takeIf { it.isNotBlank() }
                if (isGenericAlbumReference(albumId, albumTitle)) continue
                albums.add(AlbumData(
                    _id = albumId,
                    artist = albumObj.optString("artist"),
                    cover_url = normalizeApiInput(albumObj.optString("cover_url")).takeIf { it.isNotBlank() },
                    duration_total = albumObj.optInt("duration_total").takeIf { it > 0 },
                    title = albumTitle ?: "",
                    tracks_count = albumObj.optInt("tracks_count")
                ))
            }
            albums
        } catch (e: Exception) {
            Timber.e(e, "Error fetching saved albums")
            emptyList()
        } finally {
            connection.disconnect()
        }
    }
}
