package com.xstream.music.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xstream.music.R
import com.xstream.music.data.model.Song
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowMediaInfo(
    song: Song,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val qualityLabel = when {
                song.type?.lowercase() == "flac" -> "Lossless"
                song.type?.lowercase() in listOf("m4a", "alac") -> "Lossless"
                else -> "Lossy"
            }
            
            val formatDisplay = song.type?.uppercase() ?: "MP3"
            val sampleRateDisplay = song.samplingRateHz
                ?.takeIf { it > 0 }
                ?.let { "${it / 1000.0} kHz" }
                ?: "Unknown"
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(24.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mp3),
                        contentDescription = "Format",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = qualityLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$formatDisplay $sampleRateDisplay",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Text(
                text = "Information",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            val infoItems = buildList {
                add("Title" to song.title)
                add("Artist" to song.artist)
                song.album?.let { add("Album" to it) }
                song.durationSec?.let { 
                    val minutes = it / 60
                    val seconds = it % 60
                    add("Duration" to String.format("%d:%02d", minutes, seconds))
                }
                
                song.type?.let { 
                    val displayType = when (it.lowercase()) {
                        "flac" -> "FLAC (Lossless)"
                        "m4a" -> "M4A/AAC"
                        "webm" -> "WebM/Opus"
                        "opus" -> "Opus"
                        "mp3" -> "MP3"
                        "ogg" -> "OGG Vorbis"
                        "wav" -> "WAV"
                        else -> it.uppercase()
                    }
                    add("Format" to displayType)
                }
                
                song.bitrateKbps?.let { 
                    add("Bitrate" to "$it Kbps")
                }
                
                song.samplingRateHz?.let { 
                    val khz = it / 1000.0
                    add("Sample rate" to "$khz kHz")
                }
                
                song.fileSize?.let {
                    add("File size" to Formatter.formatShortFileSize(context, it))
                }
                
                song.localPath?.let { path ->
                    if (!path.startsWith("content://")) {
                        val file = File(path)
                        if (file.exists()) {
                            add("File size" to Formatter.formatShortFileSize(context, file.length()))
                        }
                    }
                }
            }
            
            infoItems.forEach { (label, value) ->
                InfoItem(
                    label = label,
                    value = value,
                    onCopy = {
                        copyToClipboard(context, label, value)
                    }
                )
            }
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    clipboard.setPrimaryClip(clip)
}
