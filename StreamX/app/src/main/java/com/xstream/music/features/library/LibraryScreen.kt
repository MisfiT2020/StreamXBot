package com.xstream.music.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xstream.music.R
import com.xstream.music.core.cache.DataCache
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.api.fetchUserPlaylists
import com.xstream.music.data.model.Playlist
import com.xstream.music.ui.components.CoverArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    isPlayerVisible: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val SFPro = FontFamily(Font(R.font.sfprodisplaymedium))
    
    var playlists by remember { mutableStateOf<List<Playlist>?>(DataCache.getUserPlaylists(context).takeIf { it.isNotEmpty() }) }
    var isLoading by remember { mutableStateOf(playlists == null) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        val apiUrl = ApiPreferences.getApiUrl(context)
        val token = AuthPreferences.getEffectiveToken(context)
        
        if (apiUrl.isNotBlank() && token != null) {
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchUserPlaylists(apiUrl, context, token) }.getOrNull()
            }
            
            if (result != null) {
                playlists = result
                DataCache.saveUserPlaylists(context, result)
            } else if (playlists == null) {
                playlists = emptyList()
            }
            isLoading = false
        }
    }
    
    fun refreshPlaylists() {
        if (isRefreshing) return
        isRefreshing = true
        scope.launch {
            val apiUrl = ApiPreferences.getApiUrl(context)
            val token = AuthPreferences.getEffectiveToken(context)
            
            if (apiUrl.isNotBlank() && token != null) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { fetchUserPlaylists(apiUrl, context, token) }.getOrNull()
                }
                
                if (result != null) {
                    playlists = result
                    DataCache.saveUserPlaylists(context, result)
                }
            }
            isRefreshing = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "From Your Library",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SFPro,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        
        
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            playlists.isNullOrEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.fav),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No playlists yet",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            fontSize = 15.sp,
                            fontFamily = SFPro
                        )
                        Text(
                            text = "Create your first playlist",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontFamily = SFPro
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = if (isPlayerVisible) 100.dp else 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(playlists!!) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { onPlaylistClick(playlist) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverArt(
                                coverUrl = playlist.thumbnailUrl,
                                fallbackColor = playlist.color ?: Color.DarkGray,
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = SFPro,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (playlist.subtitle != null) {
                                    Text(
                                        text = playlist.subtitle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontFamily = SFPro,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
