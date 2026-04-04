package com.xstream.music.features.artist

import com.xstream.music.player.service.*
import com.xstream.music.player.manager.*
import com.xstream.music.ui.components.*
import com.xstream.music.realtime.websocket.*
import com.xstream.music.core.preferences.*
import com.xstream.music.core.cache.*
import com.xstream.music.core.utils.*
import com.xstream.music.data.model.*
import com.xstream.music.data.api.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xstream.music.R
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.launch

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.ArtistItem
import com.xstream.music.ui.components.CoverArt
import com.xstream.music.ui.theme.SFProDisplayFontFamily
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    artistName: String? = null,
    artistId: String? = null,
    onBack: () -> Unit,
    onArtistClick: (String, String) -> Unit = { _, _ -> },
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (Playlist) -> Unit = {},
    isPlayerVisible: Boolean = false
) {
    val context = LocalContext.current
    var artistItem by remember { mutableStateOf<ArtistItem?>(null) }
    var youtubeArtistPage by remember { mutableStateOf<com.metrolist.innertube.pages.ArtistPage?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val uriHandler = LocalUriHandler.current
    
    val isYouTubeArtist = !artistId.isNullOrBlank() && artistId.any { !it.isDigit() }

    LaunchedEffect(artistName, artistId) {
        isLoading = true
        if (isYouTubeArtist) {
            
            youtubeArtistPage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    initYouTubeAuth(context)
                    com.metrolist.innertube.YouTube.artist(artistId!!).getOrNull()
                }.getOrNull()
            }
        } else if (artistId != null) {
            val response = fetchArtistById(artistId, includePage = true, slug = artistName)
            artistItem = response?.item
        } else if (artistName != null) {
            val response = fetchArtists(artistName, limit = 1, includePage = true)
            artistItem = response?.items?.firstOrNull()
        }
        isLoading = false
    }

    val bgColor = MaterialTheme.colorScheme.background
    val onBgColor = MaterialTheme.colorScheme.onBackground

    Scaffold(
        containerColor = bgColor
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else if (youtubeArtistPage != null) {
            
            YouTubeArtistContent(
                artistPage = youtubeArtistPage!!,
                onBack = onBack,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                onPlaylistClick = onPlaylistClick,
                isPlayerVisible = isPlayerVisible,
                padding = padding
            )
        } else if (artistItem == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Artist not found",
                    color = Color.Gray,
                    fontFamily = SFProDisplayFontFamily
                )
            }
        } else {
            val item = artistItem!!
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = padding.calculateBottomPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        val rawImageUrl = item.image_url
                        val displayImageUrl = remember(rawImageUrl) {
                            rawImageUrl?.replace(Regex("""/\d+x\d+bb\.jpg$"""), "/1000x1000bb.jpg")
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp)
                                .clipToBounds()
                        ) {
                            if (!item.video_hls_url.isNullOrBlank()) {
                                
                                val exoPlayer = remember {
                                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                                    val hlsMediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
                                    
                                    ExoPlayer.Builder(context).build().apply {
                                        val mediaItem = MediaItem.fromUri(item.video_hls_url)
                                        val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)
                                        setMediaSource(mediaSource)
                                        repeatMode = ExoPlayer.REPEAT_MODE_ALL
                                        volume = 0f 
                                        prepare()
                                        playWhenReady = true
                                    }
                                }

                                DisposableEffect(Unit) {
                                    onDispose {
                                        exoPlayer.playWhenReady = false
                                        exoPlayer.stop()
                                        exoPlayer.clearVideoSurface()
                                        exoPlayer.clearMediaItems()
                                        exoPlayer.release()
                                    }
                                }

                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            player = exoPlayer
                                            useController = false 
                                            setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                                            setKeepContentOnPlayerReset(false)
                                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            layoutParams = android.view.ViewGroup.LayoutParams(
                                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                    },
                                    update = { view ->
                                        view.player = exoPlayer
                                    },
                                    onRelease = { view ->
                                        view.player = null
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                CoverArt(
                                    coverUrl = displayImageUrl,
                                    fallbackColor = Color.DarkGray,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.6f to Color.Transparent,
                                            1f to bgColor
                                        )
                                    )
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = item.name,
                                color = onBgColor,
                                fontSize = 36.sp,
                                fontFamily = SFProDisplayFontFamily,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            if (!item.genres.isNullOrEmpty()) {
                                Text(
                                    text = item.genres.joinToString(", "),
                                    color = Color.Gray,
                                    fontSize = 14.sp,
                                    fontFamily = SFProDisplayFontFamily,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            
                            if (!item.links.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    item.links.forEach { link ->
                                        val platform = link.platform.lowercase()
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(onBgColor.copy(alpha = 0.1f))
                                                .clickable { uriHandler.openUri(link.href) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val iconRes = when {
                                                platform.contains("spotify") -> R.drawable.ic_spotify
                                                platform.contains("apple") -> R.drawable.apple_lossless_seeklogo
                                                platform.contains("instagram") -> R.drawable.ic_instagram
                                                platform.contains("youtube") -> R.drawable.ic_youtube
                                                platform.contains("tiktok") -> R.drawable.ic_tiktok
                                                platform == "x" || platform.contains("x.com") || platform.contains("twitter") -> R.drawable.ic_x
                                                platform.contains("facebook") -> R.drawable.ic_facebook
                                                else -> null
                                            }

                                            val iconSize = when {
                                                platform.contains("tiktok") -> 20.dp
                                                platform == "x" || platform.contains("x.com") || platform.contains("twitter") -> 18.dp
                                                platform.contains("facebook") -> 20.dp
                                                else -> 24.dp
                                            }

                                            if (iconRes != null) {
                                                Icon(
                                                    painter = painterResource(id = iconRes),
                                                    contentDescription = link.platform,
                                                    tint = if (platform.contains("apple")) onBgColor else Color.Unspecified,
                                                    modifier = Modifier.size(iconSize)
                                                )
                                            } else {
                                                Text(
                                                    text = "L",
                                                    color = onBgColor,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(32.dp))

                            if (!item.description.isNullOrBlank()) {
                                Text(
                                    text = "About",
                                    color = onBgColor,
                                    fontSize = 20.sp,
                                    fontFamily = SFProDisplayFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val parsedDescription = HtmlCompat.fromHtml(item.description, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                                
                                val formattedDescription = parsedDescription
                                    .replace(Regex("(?m)^\\s*•\\s*"), "\n\n• ")
                                    .replace(" • ", "\n\n• ")

                                Text(
                                    text = formattedDescription,
                                    color = onBgColor.copy(alpha = 0.8f),
                                    fontSize = 15.sp,
                                    fontFamily = SFProDisplayFontFamily,
                                    lineHeight = 22.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                            }

                            val infoList = mutableListOf<Pair<String, String>>()
                            item.hometown?.let { infoList.add("Hometown" to it) }
                            item.formed?.let { infoList.add("Formed" to it) }
                            item.born?.let { infoList.add("Born" to it) }

                            if (infoList.isNotEmpty()) {
                                infoList.forEach { (label, value) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = label,
                                            color = Color.Gray,
                                            fontSize = 15.sp,
                                            fontFamily = SFProDisplayFontFamily
                                        )
                                        Text(
                                            text = value,
                                            color = onBgColor,
                                            fontSize = 15.sp,
                                            fontFamily = SFProDisplayFontFamily,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }

                            if (!item.member_of.isNullOrEmpty()) {
                                Text(
                                    text = "Member of",
                                    color = onBgColor,
                                    fontSize = 20.sp,
                                    fontFamily = SFProDisplayFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }

                    if (!item.member_of.isNullOrEmpty()) {
                        items(item.member_of, key = { it.name + it.href }) { group ->
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                ArtistListRow(
                                    name = group.name,
                                    imageUrl = group.image_url,
                                    onClick = {
                                        val id = group.href.split("/").lastOrNull()
                                        if (id != null) {
                                            onArtistClick(group.name, id)
                                        }
                                    }
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }

                    item {
                        if (!item.members.isNullOrEmpty()) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text(
                                    text = "Members",
                                    color = onBgColor,
                                    fontSize = 20.sp,
                                    fontFamily = SFProDisplayFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }

                    if (!item.members.isNullOrEmpty()) {
                        items(item.members, key = { it.name + it.href }) { member ->
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                ArtistListRow(
                                    name = member.name,
                                    imageUrl = member.image_url,
                                    onClick = {
                                        val id = member.href.split("/").lastOrNull()
                                        if (id != null) {
                                            onArtistClick(member.name, id)
                                        }
                                    }
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(if (isPlayerVisible) 140.dp else 24.dp))
                        }
                    }

                    if (item.members.isNullOrEmpty() && !item.member_of.isNullOrEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(if (isPlayerVisible) 140.dp else 24.dp))
                        }
                    } else if (item.members.isNullOrEmpty() && item.member_of.isNullOrEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(if (isPlayerVisible) 140.dp else 24.dp))
                        }
                    }
                }

                
                TopAppBar(
                    title = { Text("") },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .padding(8.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.3f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.Transparent
                    )
                )
            }
        }
    }
}
@Composable
fun ArtistListRow(
    name: String,
    imageUrl: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.DarkGray)
        ) {
            val displayImageUrl = remember(imageUrl) {
                imageUrl?.replace(Regex("""/\d+x\d+bb\.jpg$"""), "/200x200bb.jpg")
            }
            CoverArt(
                coverUrl = displayImageUrl,
                fallbackColor = Color.DarkGray,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = name,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
            fontFamily = SFProDisplayFontFamily,
            fontWeight = FontWeight.Medium
        )
    }
}


@Composable
fun YouTubeArtistContent(
    artistPage: com.metrolist.innertube.pages.ArtistPage,
    onBack: () -> Unit,
    onArtistClick: (String, String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    isPlayerVisible: Boolean,
    padding: androidx.compose.foundation.layout.PaddingValues
) {
    val context = LocalContext.current
    val bgColor = MaterialTheme.colorScheme.background
    val onBgColor = MaterialTheme.colorScheme.onBackground
    val playerManager: MusicPlayerManager = androidx.lifecycle.viewmodel.compose.viewModel { MusicPlayerManager(context) }
    var isDescriptionExpanded by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    CoverArt(
                        coverUrl = artistPage.artist.thumbnail,
                        fallbackColor = Color.DarkGray,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0.6f to Color.Transparent,
                                    1f to bgColor
                                )
                            )
                    )
                }
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = artistPage.artist.title,
                        color = onBgColor,
                        fontSize = 36.sp,
                        fontFamily = SFProDisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    
                    val subscriberText = artistPage.subscriberCountText
                    if (subscriberText != null) {
                        Text(
                            text = subscriberText,
                            color = Color.Gray,
                            fontSize = 14.sp,
                            fontFamily = SFProDisplayFontFamily,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    val descriptionText = artistPage.description
                    if (descriptionText != null) {
                        Text(
                            text = "About",
                            color = onBgColor,
                            fontSize = 20.sp,
                            fontFamily = SFProDisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val maxLength = 200
                        val shouldTruncate = descriptionText.length > maxLength
                        val displayText = if (shouldTruncate && !isDescriptionExpanded) {
                            descriptionText.take(maxLength) + "..."
                        } else {
                            descriptionText
                        }
                        
                        Column {
                            Text(
                                text = displayText,
                                color = onBgColor.copy(alpha = 0.8f),
                                fontSize = 15.sp,
                                fontFamily = SFProDisplayFontFamily,
                                lineHeight = 22.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                            
                            if (shouldTruncate) {
                                Text(
                                    text = if (isDescriptionExpanded) "Show less" else "Show more",
                                    color = Color(0xFFE24A5A),
                                    fontSize = 15.sp,
                                    fontFamily = SFProDisplayFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .clickable { isDescriptionExpanded = !isDescriptionExpanded }
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
            
            
            artistPage.sections.forEach { section ->
                val carouselItems = section.items.filter {
                    it is com.metrolist.innertube.models.AlbumItem ||
                        it is com.metrolist.innertube.models.PlaylistItem ||
                        it is com.metrolist.innertube.models.ArtistItem
                }
                val songItems = section.items.filterIsInstance<com.metrolist.innertube.models.SongItem>()
                val sectionSongs = songItems.mapNotNull { it.toSong() }

                if (carouselItems.isEmpty() && sectionSongs.isEmpty()) return@forEach
                 
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = section.title,
                            color = onBgColor,
                            fontSize = 20.sp,
                            fontFamily = SFProDisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
                
                
                if (carouselItems.isNotEmpty()) {
                    
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            items(carouselItems, key = { "${section.title}_${it.title}_${it.thumbnail}" }) { item ->
                                when (item) {
                                    is com.metrolist.innertube.models.AlbumItem -> {
                                        Column(
                                            modifier = Modifier
                                                .width(160.dp)
                                                .clickable { 
                                                    item.toPlaylist()?.let { playlist ->
                                                        onPlaylistClick(playlist)
                                                    }
                                                }
                                        ) {
                                            CoverArt(
                                                coverUrl = upscaleYoutubeThumbnail(item.thumbnail, 320),
                                                fallbackColor = Color.DarkGray,
                                                requestSizePx = 320,
                                                modifier = Modifier
                                                    .size(160.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = item.title,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = onBgColor,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                fontFamily = SFProDisplayFontFamily,
                                                modifier = Modifier.height(40.dp)
                                            )
                                            if (item.year != null) {
                                                Text(
                                                    text = item.year.toString(),
                                                    fontSize = 13.sp,
                                                    color = Color.Gray,
                                                    fontFamily = SFProDisplayFontFamily
                                                )
                                            }
                                        }
                                    }
                                    is com.metrolist.innertube.models.PlaylistItem -> {
                                        Column(
                                            modifier = Modifier
                                                .width(160.dp)
                                                .clickable { 
                                                    item.toPlaylist()?.let { playlist ->
                                                        onPlaylistClick(playlist)
                                                    }
                                                }
                                        ) {
                                            CoverArt(
                                                coverUrl = upscaleYoutubeThumbnail(item.thumbnail ?: "", 320),
                                                fallbackColor = Color.DarkGray,
                                                requestSizePx = 320,
                                                modifier = Modifier
                                                    .size(160.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = item.title,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = onBgColor,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                fontFamily = SFProDisplayFontFamily,
                                                modifier = Modifier.height(40.dp)
                                            )
                                            val songCount = item.songCountText
                                            if (songCount != null) {
                                                Text(
                                                    text = songCount,
                                                    fontSize = 13.sp,
                                                    color = Color.Gray,
                                                    fontFamily = SFProDisplayFontFamily
                                                )
                                            }
                                        }
                                    }
                                    is com.metrolist.innertube.models.ArtistItem -> {
                                        Column(
                                            modifier = Modifier
                                                .width(160.dp)
                                                .clickable {
                                                    val targetArtistId = item.channelId ?: item.id
                                                    onArtistClick(item.title, targetArtistId)
                                                },
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            CoverArt(
                                                coverUrl = upscaleYoutubeThumbnail(item.thumbnail, 320),
                                                fallbackColor = Color.DarkGray,
                                                requestSizePx = 320,
                                                modifier = Modifier
                                                    .size(160.dp)
                                                    .clip(CircleShape)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = item.title,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = onBgColor,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                fontFamily = SFProDisplayFontFamily,
                                                modifier = Modifier.height(40.dp)
                                            )
                                        }
                                    }
                                    is com.metrolist.innertube.models.SongItem -> Unit
                                    is com.metrolist.innertube.models.EpisodeItem -> Unit
                                    is com.metrolist.innertube.models.PodcastItem -> Unit
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                } else if (sectionSongs.isNotEmpty()) {
                    
                    items(songItems, key = { "${section.title}_${it.id}_${it.title}" }) { item ->
                        val song = item.toSong()
                        if (song != null) {
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                SongRowCard(
                                    song = song,
                                    onClick = {
                                        val index = sectionSongs.indexOfFirst { it.id == song.id }
                                        if (index != -1) {
                                            val token = AuthPreferences.getUser(context)?.token
                                            playerManager.setQueueFromLatest(
                                                sectionSongs,
                                                index,
                                                ApiPreferences.getApiUrl(context),
                                                token
                                            )
                                        }
                                    },
                                    onAlbumClick = onAlbumClick,
                                    onArtistClickBySong = { selectedSong ->
                                        val artistName = selectedSong.primaryArtistName
                                        val artistId = selectedSong.primaryArtistId
                                        if (!artistName.isNullOrBlank() && !artistId.isNullOrBlank()) {
                                            onArtistClick(artistName, artistId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(if (isPlayerVisible) 140.dp else 24.dp))
            }
        }
        
        
        @OptIn(ExperimentalMaterial3Api::class)
        TopAppBar(
            title = { Text("") },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.3f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = Color.Transparent
            )
        )
    }
}
