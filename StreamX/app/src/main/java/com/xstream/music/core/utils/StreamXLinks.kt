package com.xstream.music.core.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xstream.music.data.api.normalizeApiInput

private const val OPEN_APP_QUERY_PARAMETER = "openApp"

private fun buildStreamXBackendLink(path: String, apiBaseUrl: String?): String {
    val normalizedApiBaseUrl = apiBaseUrl
        ?.let(::normalizeApiInput)
        ?.takeIf { it.isNotBlank() }
        ?: return ""

    val normalizedPath = if (path.startsWith("/")) path else "/$path"
    val builder = Uri.parse("$normalizedApiBaseUrl$normalizedPath").buildUpon()

    builder.appendQueryParameter(OPEN_APP_QUERY_PARAMETER, "1")
    return builder.build().toString()
}

fun buildSharedPlaylistWebLink(playlistId: String, apiBaseUrl: String? = null): String {
    val normalizedPlaylistId = playlistId.trim()
    if (normalizedPlaylistId.isBlank()) return ""
    return buildStreamXBackendLink("/share/playlists/$normalizedPlaylistId", apiBaseUrl)
}

fun buildSharedAlbumWebLink(albumId: String, apiBaseUrl: String? = null): String {
    val normalizedAlbumId = albumId.trim()
    if (normalizedAlbumId.isBlank()) return ""
    return buildStreamXBackendLink("/share/albums/$normalizedAlbumId", apiBaseUrl)
}

fun buildSharedTrackWebLink(trackId: String, apiBaseUrl: String? = null): String {
    val normalizedTrackId = trackId.trim()
    if (normalizedTrackId.isBlank()) return ""
    return buildStreamXBackendLink("/share/tracks/$normalizedTrackId", apiBaseUrl)
}

fun buildJamWebLink(jamId: String, apiBaseUrl: String? = null): String {
    val normalizedJamId = jamId.trim()
    if (normalizedJamId.isBlank()) return ""
    return buildStreamXBackendLink("/share/jam/$normalizedJamId", apiBaseUrl)
}

fun isShareableStreamXAlbumId(albumId: String?): Boolean {
    val normalizedAlbumId = albumId?.trim().orEmpty()
    return normalizedAlbumId.isNotBlank() &&
        !normalizedAlbumId.startsWith("yt_", ignoreCase = true) &&
        !normalizedAlbumId.startsWith("MPREb_", ignoreCase = true)
}

fun isShareableStreamXTrackId(trackId: String?): Boolean {
    val normalizedTrackId = trackId?.trim().orEmpty()
    return normalizedTrackId.isNotBlank() &&
        !normalizedTrackId.startsWith("yt_", ignoreCase = true) &&
        !normalizedTrackId.startsWith("sc_", ignoreCase = true)
}

fun shareTextLink(context: Context, text: String, chooserTitle: String? = null) {
    val normalizedText = text.trim()
    if (normalizedText.isBlank()) return

    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, normalizedText)
    }
    context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
}
