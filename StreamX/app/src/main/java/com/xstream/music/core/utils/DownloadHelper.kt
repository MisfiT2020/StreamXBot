package com.xstream.music.core.utils

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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import android.os.PowerManager
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import timber.log.Timber
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.data.model.Song

object DownloadHelper {
    private const val GENERATED_COVER_SIZE_PX = 1024

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds

    private val downloadJobs = mutableMapOf<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val activeDownloadTitles = ConcurrentHashMap<String, String>()
    
    private val streamUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        scope.launch {
            refreshDownloadedIds(context)          
            cleanExpiredStreamUrls()
        }
    }
    
    private fun cleanExpiredStreamUrls() {
        val now = System.currentTimeMillis()
        streamUrlCache.entries.removeIf { it.value.second <= now }
        if (streamUrlCache.isNotEmpty()) {
            Timber.d("Cleaned expired stream URLs, ${streamUrlCache.size} remaining")
        }
    }

    private fun refreshDownloadedIds(context: Context) {
        val ids = getDownloadedSongs(context).mapNotNull { it.id }.flatMap { id ->
            if (id.startsWith("yt_")) {
                listOf(id, id.removePrefix("yt_"))
            } else {
                listOf(id, "yt_$id")
            }
        }.toSet()
        _downloadedIds.value = ids
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var downloadSemaphore = Semaphore(3)
    
    fun updateParallelSettings(context: Context) {
        val enabled = DataCache.isParallelDownloadsEnabled(context)
        val count = if (enabled) DataCache.getParallelDownloadsCount(context) else 1
        Timber.d("Updating semaphore: enabled=$enabled, count=$count")
        downloadSemaphore = Semaphore(count)
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private fun getDownloadDir(context: Context): Any {
        val customPath = DataCache.getDownloadFolder(context)
        if (!customPath.isNullOrBlank() && customPath.startsWith("content://")) {
            return Uri.parse(customPath)
        }
        
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val dir = File(baseDir, "StreamX")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun acquireWakeLock(context: Context) {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamX::DownloadWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(30 * 60 * 1000L) 
            Timber.d("WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (_downloadProgress.value.isEmpty() && wakeLock?.isHeld == true) {
            wakeLock?.release()
            Timber.d("WakeLock released")
        }
    }

    private const val CHANNEL_ID = "streamx_downloads"
    private const val NOTIFICATION_ID = 1001
    private const val GROUP_KEY_DOWNLOADS = "com.xstream.music.DOWNLOADS"

    private fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Downloads"
            val descriptionText = "Track download progress"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateTrackNotification(context: Context, trackId: String, title: String, progress: Float?, isCompleteOrCancelled: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = trackId.hashCode()

        if (isCompleteOrCancelled) {
            notificationManager.cancel(notificationId)
            if (_downloadProgress.value.isEmpty()) {
                notificationManager.cancel(NOTIFICATION_ID)
            }
            return
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setGroup(GROUP_KEY_DOWNLOADS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        if (progress != null && progress >= 0f) {
            builder.setContentText("Downloading... ${(progress * 100).toInt()}%")
            builder.setProgress(100, (progress * 100).toInt(), false)
        } else {
            builder.setContentText("Queued")
            builder.setProgress(100, 0, true)
        }

        notificationManager.notify(notificationId, builder.build())

        val activeCount = _downloadProgress.value.size
        val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("StreamX Downloads")
            .setContentText("Downloading $activeCount tracks...")
            .setGroup(GROUP_KEY_DOWNLOADS)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        notificationManager.notify(NOTIFICATION_ID, summaryBuilder.build())
    }

    fun downloadTrack(context: Context, song: Song, apiUrl: String) {
        val trackId = song.id ?: return
        
        if (_downloadProgress.value.containsKey(trackId)) {
            Timber.d("Track ${song.title} is already queued or downloading")
            return
        }
        
        Timber.d("Queueing track: ${song.title}")
        
        try {
            initNotificationChannel(context)
            _downloadProgress.update { it + (trackId to 0f) }
            activeDownloadTitles[trackId] = song.title
            updateTrackNotification(context, trackId, song.title, null, false)
            acquireWakeLock(context)
        } catch (e: Exception) {
            Timber.e(e, "Error initializing download for ${song.title}")
        }

        val job = scope.launch {
            try {
                Timber.d("Waiting for semaphore permit for: ${song.title}")
                downloadSemaphore.withPermit {
                    if (!downloadJobs.containsKey(trackId)) {
                        Timber.d("Job was cancelled before execution for: ${song.title}")
                        return@withPermit
                    }
                    
                    val normalizedApiUrl = apiUrl.removeSuffix("/")
                    val token = AuthPreferences.getUser(context)?.token
                    val isYouTubeTrack = song.type == "youtube" || trackId.startsWith("yt_")

                    val lyrics: String? = if (!isYouTubeTrack) {
                        Timber.d("Checking lyrics for: ${song.title}")
                        val lyrics = fetchTrackLyrics(normalizedApiUrl, trackId, context, token, songTitle = song.title, songArtist = song.artist, durationSec = song.durationSec ?: -1)
                        if (lyrics.isNullOrBlank()) {
                            Timber.w("No lyrics found for ${song.title}. Skipping download.")
                            launch(Dispatchers.Main) {
                                Toast.makeText(context, "No lyrics found for ${song.title}. Skipping.", Toast.LENGTH_SHORT).show()
                            }
                            return@withPermit
                        }
                        lyrics
                    } else null

                    Timber.d("Starting download execution for: ${song.title}")
                    
                    val requestUrl = if (isYouTubeTrack) {
                        val videoId = trackId.removePrefix("yt_")
                            
                        val cached = streamUrlCache[videoId]
                        val now = System.currentTimeMillis()
                        
                        if (cached != null && cached.second > now) {
                            Timber.d("Using cached stream URL for ${song.title}")
                            cached.first
                        } else {
                            Timber.d("Fetching fresh stream URL for ${song.title}")
                            val streamUrl = getYouTubeStreamUrl(videoId, context)
                            if (streamUrl != null) {
                                
                                streamUrlCache[videoId] = streamUrl to (now + 5 * 60 * 60 * 1000L)
                            }
                            streamUrl
                        }
                    } else {
                        "$normalizedApiUrl/tracks/$trackId/download"
                    }
                    
                    if (requestUrl.isNullOrBlank()) {
                        throw Exception("Failed to resolve download URL for ${song.title}")
                    }
                    Timber.d("Request URL: $requestUrl")
                    
                    val requestBuilder = Request.Builder().url(requestUrl)
                    if (isYouTubeTrack) {
                        
                        requestBuilder.addHeader(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
                        )
                        
                        requestBuilder.addHeader("Range", "bytes=0-")
                    } else if (token != null) {
                        requestBuilder.addHeader("Authorization", "Bearer $token")
                        requestBuilder.addHeader("X-Auth-Token", token)
                    }
                    val request = requestBuilder.build()
                    val call = client.newCall(request)
                    activeCalls[trackId] = call
                    val response = call.execute()

                    if (!response.isSuccessful) {
                        Timber.e("HTTP Error ${response.code} for: ${song.title}")
                        
                        
                        if (isYouTubeTrack) {
                            val videoId = trackId.removePrefix("yt_")
                            streamUrlCache.remove(videoId)
                            Timber.d("Cleared cached stream URL due to error")
                        }
                        
                        throw Exception("Failed to download: HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Empty body")
                    val length = body.contentLength()
                    
                    Timber.d("Content length for ${song.title}: $length bytes")
                    
                    val contentType = response.header("Content-Type") ?: response.header("content-type") ?: ""
                    val contentDisposition = response.header("Content-Disposition") ?: response.header("content-disposition") ?: ""
                    
                    Timber.d("Download headers for ${song.title}: Content-Type='$contentType', Content-Disposition='$contentDisposition', song.type='${song.type}'")
                    
                    var extension = "mp3"
                    
                    
                    if (isYouTubeTrack) {
                        extension = when {
                            contentType.contains("webm", ignoreCase = true) -> "webm"
                            contentType.contains("mp4", ignoreCase = true) || contentType.contains("m4a", ignoreCase = true) -> "m4a"
                            contentType.contains("opus", ignoreCase = true) -> "opus"
                            else -> "m4a" 
                        }
                        Timber.d("YouTube track extension determined: $extension")
                    } else {
                        
                        val filenameRegex = Regex("""filename\*?=['"]?(?:UTF-\d['"]*)?([^;\r\n"']*)['"]?""", RegexOption.IGNORE_CASE)
                        val matchResult = filenameRegex.find(contentDisposition)
                        if (matchResult != null) {
                            val filename = matchResult.groupValues[1]
                            if (filename.contains(".")) {
                                extension = filename.substringAfterLast(".", "mp3").lowercase()
                            }
                        } else if (contentDisposition.contains("filename=")) {
                            val filenamePart = contentDisposition.substringAfter("filename=").substringBefore(";").trim('"', '\'', ' ')
                            if (filenamePart.contains(".")) {
                                extension = filenamePart.substringAfterLast(".", "mp3").lowercase()
                            }
                        }
                        
                        if (extension == "mp3") {
                            val typeHint = (contentType + " " + (song.type ?: "")).lowercase()
                            if (typeHint.contains("flac")) {
                                extension = "flac"
                            } else if (typeHint.contains("mp4") || typeHint.contains("m4a") || typeHint.contains("alac")) {
                                extension = "m4a"
                            } else if (typeHint.contains("ogg")) {
                                extension = "ogg"
                            } else if (typeHint.contains("wav")) {
                                extension = "wav"
                            }
                        }
                    }

                    Timber.d("Determined extension for ${song.title}: $extension")

                    val downloadTarget = getDownloadDir(context)
                    val safeTitle = song.title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                    val fileName = "$safeTitle.$extension"
                    val mimeType = when (extension) {
                        "flac" -> "audio/flac"
                        "m4a" -> "audio/mp4"
                        "webm" -> "audio/webm"
                        "opus" -> "audio/opus"
                        "ogg" -> "audio/ogg"
                        "wav" -> "audio/wav"
                        else -> "audio/mpeg"
                    }
                    
                    var savedPath: String? = null

                    if (downloadTarget is Uri) {
                        Timber.d("Using SAF for: ${song.title} at $downloadTarget")
                        val rootDoc = DocumentFile.fromTreeUri(context, downloadTarget)
                        if (rootDoc == null || !rootDoc.canWrite()) {
                            throw Exception("Cannot write to custom folder. Check permissions.")
                        }
                        
                        var streamXDir = rootDoc.findFile("StreamX")
                        if (streamXDir == null) {
                            streamXDir = rootDoc.createDirectory("StreamX")
                        }
                        
                        if (streamXDir == null) throw Exception("Could not create StreamX directory")
                        
                        val docFile = streamXDir.findFile(fileName) ?: streamXDir.createFile(mimeType, fileName)
                        if (docFile == null) throw Exception("Could not create document file")
                        
                        context.contentResolver.openOutputStream(docFile.uri).use { output ->
                            if (output == null) throw Exception("Failed to open output stream")
                            body.source().use { input ->
                                val buffer = ByteArray(8 * 1024)
                                var bytesRead: Int
                                var totalBytesRead = 0L
                                var lastUpdateTime = System.currentTimeMillis()

                                try {
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        if (!this@launch.isActive) throw kotlinx.coroutines.CancellationException("Download cancelled")
                                        output.write(buffer, 0, bytesRead)
                                        totalBytesRead += bytesRead
                                        if (length > 0) {
                                            val currentTime = System.currentTimeMillis()
                                            if (currentTime - lastUpdateTime > 500) {
                                                val progress = totalBytesRead.toFloat() / length.toFloat()
                                                _downloadProgress.update { it + (trackId to progress) }
                                                updateTrackNotification(context, trackId, song.title, progress, false)
                                                lastUpdateTime = currentTime
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (!this@launch.isActive) throw kotlinx.coroutines.CancellationException("Download cancelled")
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Timber.w(e, "Stream reading interrupted for ${song.title}")
                                    throw e
                                }
                            }
                        }
                        savedPath = docFile.uri.toString()
                        
                        lyrics?.let { lyricsText ->
                            val lyricName = "${trackId}_lyrics.txt"
                            val lyricFile = streamXDir.findFile(lyricName) ?: streamXDir.createFile("text/plain", lyricName)
                            lyricFile?.let {
                                context.contentResolver.openOutputStream(it.uri)?.use { os ->
                                    os.write(lyricsText.toByteArray())
                                }
                            }
                            Timber.d("Lyrics saved via SAF for ${song.title}")
                        }
                    } else if (downloadTarget is File) {
                        val file = File(downloadTarget, fileName)
                        body.source().use { input ->
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(8 * 1024)
                                var bytesRead: Int
                                var totalBytesRead = 0L
                                var lastUpdateTime = System.currentTimeMillis()

                                try {
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        if (!this@launch.isActive) throw kotlinx.coroutines.CancellationException("Download cancelled")
                                        output.write(buffer, 0, bytesRead)
                                        totalBytesRead += bytesRead
                                        if (length > 0) {
                                            val currentTime = System.currentTimeMillis()
                                            if (currentTime - lastUpdateTime > 500) {
                                                val progress = totalBytesRead.toFloat() / length.toFloat()
                                                _downloadProgress.update { it + (trackId to progress) }
                                                updateTrackNotification(context, trackId, song.title, progress, false)
                                                lastUpdateTime = currentTime
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (!this@launch.isActive) throw kotlinx.coroutines.CancellationException("Download cancelled")
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Timber.w(e, "Stream reading interrupted for ${song.title}")
                                    throw e
                                }
                            }
                        }
                        savedPath = file.absolutePath
                        
                        lyrics?.let { lyricsText ->
                            val lyricsFile = File(downloadTarget, "${trackId}_lyrics.txt")
                            lyricsFile.writeText(lyricsText)
                            Timber.d("Lyrics saved for ${song.title}")
                        }
                    }

                    if (savedPath != null) {
                        Timber.d("File downloaded and saved to: $savedPath")
                        val updatedSong = song.copy(localPath = savedPath)
                        saveDownloadedSong(context, updatedSong)
                        _downloadProgress.update { it + (trackId to 1f) }
                        updateTrackNotification(context, trackId, song.title, 1f, false)
                    }

                } 
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Timber.e(e, "Download failed for ${song.title}")
                }
            } finally {
                activeCalls.remove(trackId)
                _downloadProgress.update { it - trackId }
                activeDownloadTitles.remove(trackId)
                downloadJobs.remove(trackId)
                updateTrackNotification(context, trackId, song.title, null, true)
                releaseWakeLock()
            }
        }
        downloadJobs[trackId] = job
    }

    fun cancelDownload(trackId: String) {
        val title = activeDownloadTitles[trackId] ?: "Unknown Track"
        Timber.d("Cancelling download for: $title ($trackId)")
        activeCalls[trackId]?.cancel()
        downloadJobs[trackId]?.cancel()
        downloadJobs.remove(trackId)
        activeCalls.remove(trackId)
        activeDownloadTitles.remove(trackId)
        _downloadProgress.update { it - trackId }
        releaseWakeLock()
    }
    
    fun clearStreamUrlCache() {
        streamUrlCache.clear()
        Timber.d("Cleared all cached stream URLs")
    }
    
    fun clearStreamUrlCache(videoId: String) {
        streamUrlCache.remove(videoId)
        Timber.d("Cleared cached stream URL for video: $videoId")
    }

    fun downloadAll(context: Context, songs: List<Song>, apiUrl: String) {
        if (songs.isEmpty()) return
        songs.forEach { song ->
            if (song.id != null) {
                downloadTrack(context, song, apiUrl)
            }
        }
    }

    private const val PREFS_NAME = "streamx_downloads_cache"
    private const val KEY_DOWNLOADS = "downloaded_songs"

    fun saveDownloadedSong(context: Context, song: Song) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]"
        val array = JSONArray(json)
        
        val newArray = JSONArray()
        var added = false
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            
            val isDuplicate = obj.optString("id") == song.id || 
                              (obj.optString("title") == song.title && obj.optString("artist") == song.artist)
            
            if (isDuplicate && !added) {
                newArray.put(songToJson(song))
                added = true
            } else if (!isDuplicate) {
                newArray.put(obj)
            }
        }
        if (!added) {
            newArray.put(songToJson(song))
        }
        
        prefs.edit().putString(KEY_DOWNLOADS, newArray.toString()).apply()
        refreshDownloadedIds(context)
    }

    fun getDownloadedSongs(context: Context): List<Song> {
        Timber.d("getDownloadedSongs: Starting scan")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]"
        val array = JSONArray(json)
        val cachedSongs = mutableMapOf<String, Song>()
        val validSongs = mutableListOf<Song>()
        var cacheNeedsUpdate = false

        Timber.d("getDownloadedSongs: Loading ${array.length()} cached songs")
        for (i in 0 until array.length()) {
            val song = jsonToSong(array.getJSONObject(i))
            val path = song.localPath
            if (path != null) {
                var exists = false
                if (path.startsWith("content://")) {
                    exists = true 
                } else {
                    exists = File(path).exists()
                }
                
                if (exists) {
                    cachedSongs[path] = song
                    validSongs.add(song)
                } else {
                    Timber.d("getDownloadedSongs: Cached song no longer exists: ${song.title} at $path")
                    cacheNeedsUpdate = true
                }
            }
        }

        val currentFilePaths = mutableSetOf<String>()
        val downloadTarget = getDownloadDir(context)
        Timber.d("getDownloadedSongs: Scanning download directory: $downloadTarget")

        
        
        
        
        
        
        Timber.d("getDownloadedSongs: Finished fast load, returning ${validSongs.size} valid songs")
        
        
        val uniqueSongs = mutableListOf<Song>()
        val seenSignatures = mutableSetOf<String>()
        for (song in validSongs) {
            val signature = "${song.title}_${song.artist}".lowercase()
            if (!seenSignatures.contains(signature)) {
                seenSignatures.add(signature)
                uniqueSongs.add(song)
            } else {
                Timber.d("Filtered out duplicate song from cache: ${song.title} by ${song.artist}")
            }
        }
        
        return uniqueSongs
    }

    fun syncDownloadedSongs(context: Context) {
        scope.launch(Dispatchers.IO) {
            Timber.d("syncDownloadedSongs: Starting deep directory scan")
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]"
            val array = JSONArray(json)
            val cachedSongs = mutableMapOf<String, Song>()
            val validSongs = mutableListOf<Song>()
            var cacheNeedsUpdate = false

            for (i in 0 until array.length()) {
                val song = jsonToSong(array.getJSONObject(i))
                val path = song.localPath
                if (path != null) {
                    cachedSongs[path] = song
                    validSongs.add(song)
                }
            }

            val currentFilePaths = mutableSetOf<String>()
            val downloadTarget = getDownloadDir(context)

            fun shouldRefreshGeneratedCover(coverUrl: String?): Boolean {
                val normalizedCoverUrl = coverUrl?.takeIf { it.startsWith("file://") } ?: return false
                val coverPath = normalizedCoverUrl.removePrefix("file://")
                if (!coverPath.startsWith(context.cacheDir.absolutePath)) return false

                val coverFile = File(coverPath)
                if (!coverFile.exists()) return true

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(coverFile.absolutePath, bounds)
                return bounds.outWidth > GENERATED_COVER_SIZE_PX || bounds.outHeight > GENERATED_COVER_SIZE_PX
            }

            fun saveNormalizedEmbeddedCover(trackId: String, picture: ByteArray): String? {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)

                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateEmbeddedCoverInSampleSize(
                        options = bounds,
                        reqWidth = GENERATED_COVER_SIZE_PX,
                        reqHeight = GENERATED_COVER_SIZE_PX
                    )
                    inPreferredConfig = Bitmap.Config.RGB_565
                }

                val decodedBitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size, options)
                    ?: return null

                val coverFile = File(context.cacheDir, "${trackId}_cover.jpg")
                FileOutputStream(coverFile).use { fos ->
                    decodedBitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                }
                decodedBitmap.recycle()

                return "file://${coverFile.absolutePath}"
            }

            fun processFile(fileUriStr: String, name: String, getRetriever: () -> android.media.MediaMetadataRetriever) {
                currentFilePaths.add(fileUriStr)
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in listOf("mp3", "flac", "m4a", "ogg", "wav", "aac", "webm", "opus")) {
                    val existing = cachedSongs[fileUriStr]
                    val needsCoverRefresh = shouldRefreshGeneratedCover(existing?.coverUrl)
                    if (existing == null || existing.coverUrl == null || needsCoverRefresh) {
                        Timber.d("syncDownloadedSongs: Processing file: $name (New or missing cover)")
                        try {
                            val retriever = getRetriever()
                            val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: existing?.title ?: name.substringBeforeLast('.')
                            val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: existing?.artist ?: "Unknown Artist"
                            val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: existing?.album ?: "Local File"
                            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            val durationSec = (durationStr?.toLongOrNull() ?: 0L) / 1000
                            val bitrateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                            val bitrateKbps = (bitrateStr?.toIntOrNull() ?: 0) / 1000
                            val sampleRateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                            val sampleRateHz = sampleRateStr?.toIntOrNull()
                            
                            val trackId = existing?.id ?: "local_${java.util.UUID.randomUUID()}"
                            var coverUrlStr: String? = existing?.coverUrl
                            
                            val picture = retriever.embeddedPicture
                            if (picture != null && (coverUrlStr == null || needsCoverRefresh)) {
                                try {
                                    coverUrlStr = saveNormalizedEmbeddedCover(trackId, picture)
                                } catch (e: Exception) {
                                    Timber.w(e, "Failed to save embedded picture for $name")
                                }
                            }
                            
                            retriever.release()
                            
                            val newSong = Song(
                                id = trackId,
                                title = title,
                                artist = artist,
                                album = album,
                                durationSec = if (durationSec > 0) durationSec.toInt() else existing?.durationSec,
                                type = ext,
                                coverUrl = coverUrlStr,
                                color = existing?.color ?: Color(0xFF1DB954),
                                bitrateKbps = if (bitrateKbps > 0) bitrateKbps else existing?.bitrateKbps,
                                samplingRateHz = sampleRateHz ?: existing?.samplingRateHz,
                                localPath = fileUriStr
                            )
                            
                            if (existing == null) {
                                validSongs.add(newSong)
                                cachedSongs[fileUriStr] = newSong
                            } else {
                                val idx = validSongs.indexOf(existing)
                                if (idx != -1) validSongs[idx] = newSong
                                cachedSongs[fileUriStr] = newSong
                            }
                            cacheNeedsUpdate = true
                        } catch (e: Exception) {
                            Timber.w(e, "Could not extract metadata for $name")
                        }
                    }
                }
            }

            if (downloadTarget is Uri) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, downloadTarget)
                    val streamXDir = rootDoc?.findFile("StreamX") ?: rootDoc
                    streamXDir?.listFiles()?.forEach { docFile ->
                        if (docFile.isFile && docFile.name != null) {
                            processFile(docFile.uri.toString(), docFile.name!!) {
                                android.media.MediaMetadataRetriever().apply {
                                    setDataSource(context, docFile.uri)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to scan SAF directory")
                }
            } else if (downloadTarget is File) {
                val dir = downloadTarget
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            processFile(file.absolutePath, file.name) {
                                android.media.MediaMetadataRetriever().apply {
                                    setDataSource(file.absolutePath)
                                }
                            }
                        }
                    }
                }
            }

            if (cacheNeedsUpdate) {
                Timber.d("syncDownloadedSongs: Saving updated cache")
                val newArray = JSONArray()
                
                
                val uniqueSongs = mutableListOf<Song>()
                val seenSignatures = mutableSetOf<String>()
                for (song in validSongs) {
                    val signature = "${song.title}_${song.artist}".lowercase()
                    if (!seenSignatures.contains(signature)) {
                        seenSignatures.add(signature)
                        uniqueSongs.add(song)
                        newArray.put(songToJson(song))
                    }
                }
                
                prefs.edit().putString(KEY_DOWNLOADS, newArray.toString()).apply()
                refreshDownloadedIds(context)
            }
            Timber.d("syncDownloadedSongs: Finished deep scan")
        }
    }

    fun removeDownloadedSong(context: Context, trackId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]"
        val array = JSONArray(json)
        val newArray = JSONArray()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.optString("id") == trackId) {
                val localPath = obj.optString("localPath")
                if (localPath.isNotBlank()) {
                    if (localPath.startsWith("content://")) {
                        try {
                            val doc = DocumentFile.fromSingleUri(context, Uri.parse(localPath))
                            doc?.delete()
                        } catch (e: Exception) {}
                    } else {
                        val file = File(localPath)
                        if (file.exists()) file.delete()
                        val dir = file.parentFile
                        if (dir != null) {
                            val lyricsFile = File(dir, "${trackId}_lyrics.txt")
                            if (lyricsFile.exists()) lyricsFile.delete()
                        }
                    }
                }
            } else {
                newArray.put(obj)
            }
        }
        
        prefs.edit().putString(KEY_DOWNLOADS, newArray.toString()).apply()
        refreshDownloadedIds(context)
    }

    fun isDownloaded(context: Context, trackId: String): Boolean {
        val normalized = if (trackId.startsWith("yt_")) trackId.removePrefix("yt_") else trackId
        return _downloadedIds.value.contains(trackId) || _downloadedIds.value.contains(normalized) || _downloadedIds.value.contains("yt_$normalized")
    }

    fun getDownloadedSongById(context: Context, trackId: String): Song? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.optString("id")
            val normalized = if (trackId.startsWith("yt_")) trackId.removePrefix("yt_") else trackId
            if (id == trackId || id == normalized || id == "yt_$normalized") {
                return jsonToSong(obj)
            }
        }
        return null
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
        put("localPath", song.localPath)
        put("artists", song.artists.toJsonArray())
    }

    private fun jsonToSong(obj: JSONObject): Song {
        val coverUrl = obj.optString("coverUrl").takeIf { it != "null" && it.isNotBlank() }
        val title = obj.getString("title")
        
        return Song(
            id = obj.optString("id").takeIf { it != "null" && it.isNotBlank() },
            title = title,
            artist = obj.getString("artist"),
            album = obj.optString("album").takeIf { it != "null" && it.isNotBlank() },
            albumId = obj.optString("albumId").takeIf { it != "null" && it.isNotBlank() },
            durationSec = obj.optInt("durationSec").takeIf { it > 0 },
            type = obj.optString("type").takeIf { it != "null" && it.isNotBlank() },
            samplingRateHz = obj.optInt("samplingRateHz").takeIf { it > 0 },
            spotifyUrl = obj.optString("spotifyUrl").takeIf { it != "null" && it.isNotBlank() },
            coverUrl = coverUrl,
            color = Color(obj.getLong("color").toULong()),
            bitrateKbps = obj.optInt("bitrateKbps").takeIf { it > 0 },
            fileSize = obj.optLong("fileSize").takeIf { it > 0L },
            localPath = obj.optString("localPath").takeIf { it != "null" && it.isNotBlank() },
            artists = obj.optSongArtists()
        )
    }

    private fun calculateEmbeddedCoverInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
                halfHeight = halfHeight.coerceAtLeast(1)
                halfWidth = halfWidth.coerceAtLeast(1)
            }
        }

        return inSampleSize.coerceAtLeast(1)
    }
}
