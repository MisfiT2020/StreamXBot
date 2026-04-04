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
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import com.xstream.music.core.cache.ImageMemoryCache

@Composable
fun QueueCoverArt(
    coverUrl: String?,
    modifier: Modifier = Modifier
) {
    val normalizedUrl = coverUrl?.trim().orEmpty()
    val bitmapState = remember(normalizedUrl) { mutableStateOf(ImageMemoryCache.getFromMemory(normalizedUrl)) }

    LaunchedEffect(normalizedUrl) {
        if (normalizedUrl.isBlank()) {
            bitmapState.value = null
            return@LaunchedEffect
        }

        val cached = withContext(Dispatchers.IO) { ImageMemoryCache.get(normalizedUrl) }
        if (cached != null) {
            bitmapState.value = cached
            return@LaunchedEffect
        }

        val bitmap = withContext(Dispatchers.IO) {
            val downloaded = runCatching {
                val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                try {
                    if (connection.responseCode in 200..299) {
                        connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
                    } else null
                } catch (e: Exception) {
                    null
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            
            if (downloaded != null) {
                ImageMemoryCache.put(normalizedUrl, downloaded)
            }
            downloaded
        }
        bitmapState.value = bitmap
    }

    val bitmap = bitmapState.value
    Box(modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color.Gray)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
