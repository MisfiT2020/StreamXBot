package com.xstream.music.ui.components

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.utils.DownloadHelper
import com.xstream.music.data.model.Song

@Composable
fun DownloadButton(song: Song, apiUrl: String) {
    val context = LocalContext.current

    val downloadedIds by DownloadHelper.downloadedIds.collectAsState()
    val isDownloaded = remember(song.id, downloadedIds) {
        song.id != null && downloadedIds.contains(song.id)
    }

    val progressFlow = remember(song.id) {
        DownloadHelper.downloadProgress.map { it[song.id] }.distinctUntilChanged()
    }
    val progress by progressFlow.collectAsState(initial = null)

    if (isDownloaded) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = "Downloaded",
                tint = Color(0xFFE24A5A),
                modifier = Modifier.size(20.dp)
            )
        }
        return
    }

    if (progress != null) {
        val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
            targetValue = progress ?: 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 500),
            label = "download_progress"
        )
        
        Box(
            modifier = Modifier
                .size(32.dp)
                .clickable { DownloadHelper.cancelDownload(song.id!!) },
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(20.dp),
                color = Color(0xFFE24A5A),
                strokeWidth = 2.dp,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel Download",
                tint = Color(0xFFE24A5A),
                modifier = Modifier.size(10.dp)
            )
        }
        return
    }

    val showDownloadButton = remember { DataCache.isShowDownloadButtonEnabled(context) }
    if (!showDownloadButton) return

    IconButton(
        onClick = { DownloadHelper.downloadTrack(context, song, apiUrl) },
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ArrowDownward,
            contentDescription = "Download",
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
    }
}
