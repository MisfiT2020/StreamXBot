package com.xstream.music.data.model

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
import androidx.compose.ui.graphics.Color
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val color: Color = Color.Transparent,
    val thumbnailUrl: String? = null,
    val endpoint: String? = null,
    val kind: String? = null,
    val requiresAuth: Boolean = false,
    val lastUpdatedAt: Long? = null
)

data class SongArtist(
    val name: String,
    val id: String? = null
)

data class Song(
    val id: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val durationSec: Int? = null,
    val type: String? = null,
    val samplingRateHz: Int? = null,
    val spotifyUrl: String? = null,
    val coverUrl: String? = null,
    val color: Color,
    val bitrateKbps: Int? = null,
    val localPath: String? = null,
    val fileSize: Long? = null,
    val artists: List<SongArtist> = emptyList()
)

private fun normalizeAlbumReference(value: String?): String =
    value?.trim()?.lowercase().orEmpty()

private fun normalizeAlbumIdPart(value: String?): String {
    var normalized = value?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return ""

    normalized = normalized
        .replace("÷", " divide ")
        .replace("&", " and ")
        .replace("+", " plus ")

    normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')

    return normalized
}

private fun resolveAlbumTitle(albumTitle: String?, fallbackTitle: String): String? {
    val normalizedAlbumTitle = normalizeAlbumReference(albumTitle)
    if (normalizedAlbumTitle.isNotBlank() && normalizedAlbumTitle != "youtube" && normalizedAlbumTitle != "youtube music") {
        return albumTitle?.trim()?.takeIf { it.isNotBlank() }
    }

    return fallbackTitle.trim().takeIf { it.isNotBlank() }
}

fun isGenericAlbumReference(albumId: String?, albumTitle: String? = null): Boolean {
    val normalizedId = normalizeAlbumReference(albumId)
    if (normalizedId.isBlank()) return false

    if (normalizedId == "album_youtube" || normalizedId.startsWith("album_youtube_")) {
        return true
    }

    val normalizedTitle = normalizeAlbumReference(albumTitle)
    return normalizedTitle == "youtube" || normalizedTitle == "youtube music"
}

val Song.browsableAlbumId: String?
    get() {
        val rawAlbumId = albumId?.trim()?.takeIf { it.isNotBlank() }
        if (rawAlbumId != null && !isGenericAlbumReference(rawAlbumId, album)) {
            return rawAlbumId
        }

        val isProviderYouTube = type.equals("youtube", ignoreCase = true) || id?.startsWith("yt_") == true
        if (isProviderYouTube) {
            return null
        }

        val resolvedTitle = resolveAlbumTitle(album, title) ?: return null
        val slug = normalizeAlbumIdPart(resolvedTitle)
        return slug.takeIf { it.isNotBlank() }?.let { "album_$it" }
    }

val Song.primaryArtistName: String?
    get() = artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        ?: artist
            .split(",")
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

val Song.primaryArtistId: String?
    get() = artists.firstOrNull()?.id?.takeIf { it.isNotBlank() }

fun List<SongArtist>.toJsonArray(): JSONArray = JSONArray().apply {
    forEach { artist ->
        put(
            JSONObject().apply {
                put("name", artist.name)
                put("id", artist.id)
            }
        )
    }
}

fun JSONObject.optSongArtists(key: String = "artists"): List<SongArtist> {
    val array = optJSONArray(key) ?: return emptyList()
    return List(array.length()) { index ->
        array.optJSONObject(index)?.let { artist ->
            SongArtist(
                name = artist.optString("name").trim(),
                id = artist.optString("id").takeIf { it != "null" && it.isNotBlank() }
            )
        }
    }.filterNotNull().filter { it.name.isNotBlank() }
}

data class AlbumData(
    val _id: String,
    val artist: String,
    val cover_url: String? = null,
    val duration_total: Int? = null,
    val title: String,
    val tracks_count: Int
)

data class ArtistLink(
    val href: String,
    val platform: String
)

data class ArtistMember(
    val name: String,
    val href: String,
    val image_url: String? = null
)

data class ArtistItem(
    val id: String,
    val name: String,
    val image_url: String? = null,
    val video_poster_url: String? = null,
    val video_hls_url: String? = null,
    val genres: List<String>? = null,
    val description: String? = null,
    val hometown: String? = null,
    val born: String? = null,
    val formed: String? = null,
    val links: List<ArtistLink>? = null,
    val members: List<ArtistMember>? = null,
    val member_of: List<ArtistMember>? = null
)

data class ArtistByIdResponse(
    val ok: Boolean,
    val id: String? = null,
    val item: ArtistItem? = null
)

data class ArtistResponse(
    val ok: Boolean,
    val items: List<ArtistItem>? = null
)

data class AlbumResponse(
    val ok: Boolean,
    val album: AlbumData? = null,
    val tracks: List<Any>? = null
)

data class UserData(
    val id: Long,
    val token: String,
    val firstName: String,
    val profileUrl: String,
    val photoUrl: String
)

data class UserProfile(
    val id: Long,
    val username: String,
    val firstName: String,
    val photoUrl: String,
    val profileUrl: String
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val ok: Boolean,
    val user_id: Long? = null,
    val token: String? = null,
    val first_name: String? = null,
    val profile_url: String? = null,
    val photo_url: String? = null
)

data class JamSettings(
    val allowSeek: Boolean,
    val allowQueueEdit: Boolean
)

data class JamMember(
    val userId: Long,
    val role: String,
    val firstName: String,
    val profileUrl: String
)

data class JamPlayback(
    val trackId: String,
    val durationSec: Int? = null,
    val positionSec: Double,
    val startedAt: Double,
    val isPlaying: Boolean
)

data class Jam(
    val id: String,
    val hostUserId: Long,
    val createdAt: Double,
    val updatedAt: Double,
    val playback: JamPlayback,
    val queue: List<String>,
    val members: List<JamMember>,
    val settings: JamSettings,
    val serverTime: Double? = null
)

data class JamResponse(
    val ok: Boolean,
    val jam: Jam? = null
)

data class FriendSettings(
    val share_listening: String, 
    val allow_jam_invites: Boolean
)

data class FriendSettingsResponse(
    val ok: Boolean,
    val settings: FriendSettings? = null
)

data class FriendPresence(
    val online: Boolean,
    val last_seen: Double,
    val device: String? = null
)

data class Friend(
    val _id: Long,
    val first_name: String,
    val photo_url: String?,
    val profile_url: String?,
    val settings: FriendSettings?,
    val presence: FriendPresence?
)

data class FriendsResponse(
    val ok: Boolean,
    val friends: List<Friend>?
)

data class FriendListening(
    val _id: String,
    val user_id: Long,
    val track_id: String?,
    val started_at: Double?,
    val is_playing: Boolean,
    val position_sec: Double,
    val jam_id: String?,
    val updated_at: Double?
)

data class FriendsListeningResponse(
    val ok: Boolean,
    val listening: List<FriendListening>?
)

data class FriendRequest(
    val user_id: Long,
    val first_name: String,
    val username: String?,
    val profile_url: String?,
    val request_id: String,
    val created_at: Double
)

data class FriendRequestsResponse(
    val ok: Boolean,
    val requests: List<FriendRequest>?
)

data class SimpleResponse(
    val ok: Boolean?,
    val detail: String?
)

data class NotificationPayload(
    val jam_id: String?,
    val from_user: Long?
)

data class Notification(
    val _id: String,
    val user_id: Long,
    val type: String,
    val payload: NotificationPayload?,
    val read: Boolean,
    val created_at: Double
)

data class NotificationsResponse(
    val ok: Boolean,
    val notifications: List<Notification>?
)

data class BaseResponse(
    val ok: Boolean
)

data class CreateJamRequest(
    val trackId: String,
    val positionSec: Double,
    val isPlaying: Boolean,
    val queue: List<String>,
    val settings: JamSettings
)

data class RegisterResponse(
    val ok: Boolean,
    val detail: String? = null
)

data class ValidateResponse(
    val ok: Boolean,
    val detail: String? = null,
    val user_id: Long? = null,
    val token: String? = null,
    val first_name: String? = null,
    val profile_url: String? = null,
    val photo_url: String? = null
)
