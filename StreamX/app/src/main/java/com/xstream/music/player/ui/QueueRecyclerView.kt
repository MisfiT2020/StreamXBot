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
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

import androidx.recyclerview.widget.DiffUtil
import java.util.Collections
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.xstream.music.core.cache.ImageMemoryCache
import com.xstream.music.data.model.Song

@Composable
fun QueueRecyclerView(
    queue: List<Song>,
    currentIndex: Int,
    onMove: (Int, Int) -> Unit,
    onItemClick: (Int) -> Unit,
    onStartDrag: () -> Unit,
    onStopDrag: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                val adapter = QueueAdapter(
                    initialItems = queue, 
                    currentIndex = currentIndex, 
                    onMove = onMove,
                    onItemClick = { index -> 
                        val qAdapter = this.adapter as? QueueAdapter
                        if (qAdapter == null || !qAdapter.isDragging) {
                            onItemClick(index)
                        }
                    },
                    onStartDrag = onStartDrag,
                    onStopDrag = onStopDrag
                )
                this.adapter = adapter

                val callback = object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                    0
                ) {
                    override fun interpolateOutOfBoundsScroll(
                        recyclerView: RecyclerView,
                        viewSize: Int,
                        viewSizeOutOfBounds: Int,
                        totalSize: Int,
                        msSinceStartScroll: Long
                    ): Int {
                        val standardSpeed = super.interpolateOutOfBoundsScroll(
                            recyclerView, viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll
                        )
                        
                        return standardSpeed * 4
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
                    ): Boolean {
                        val from = viewHolder.bindingAdapterPosition
                        val to = target.bindingAdapterPosition
                        if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                            
                            val adapter = recyclerView.adapter as QueueAdapter
                            adapter.swapItemsLocally(from, to)
                            
                            adapter.onMove(from, to)
                            return true
                        }
                        return false
                    }

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
                    
                    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                        super.onSelectedChanged(viewHolder, actionState)
                        val adapter = this@apply.adapter as? QueueAdapter ?: return
                        
                        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                            adapter.isDragging = true
                            adapter.onStartDrag()
                            (viewHolder as? QueueAdapter.QueueViewHolder)?.isDragged?.value = true
                        }
                    }

                    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                        super.clearView(recyclerView, viewHolder)
                        (viewHolder as? QueueAdapter.QueueViewHolder)?.isDragged?.value = false
                        
                        val adapter = recyclerView.adapter as? QueueAdapter
                        if (adapter != null && adapter.isDragging) {
                            adapter.stopDrag()
                            adapter.onStopDrag()
                        }
                    }
                }
                ItemTouchHelper(callback).attachToRecyclerView(this)
            }
        },
        update = { recyclerView ->
            (recyclerView.adapter as? QueueAdapter)?.let { adapter ->
                adapter.updateData(queue, currentIndex)
                adapter.onMove = onMove
                adapter.onItemClick = { index ->
                    if (!adapter.isDragging) {
                        onItemClick(index)
                    }
                }
                adapter.onStartDrag = onStartDrag
                adapter.onStopDrag = onStopDrag
            }
        }
    )
}

class QueueAdapter(
    initialItems: List<Song>,
    private var currentIndex: Int,
    var onMove: (Int, Int) -> Unit,
    var onItemClick: (Int) -> Unit,
    var onStartDrag: () -> Unit = {},
    var onStopDrag: () -> Unit = {}
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    var isDragging = false
    private var items = initialItems.toMutableList()
    private var pendingItems: List<Song>? = null
    private var pendingCurrentIndex: Int? = null

    fun swapItemsLocally(from: Int, to: Int) {
        if (from < items.size && to < items.size) {
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
        }
    }

    fun stopDrag() {
        isDragging = false
        if (pendingItems != null) {
            applyData(pendingItems!!, pendingCurrentIndex ?: currentIndex)
            pendingItems = null
            pendingCurrentIndex = null
        }
    }

    fun updateData(newItems: List<Song>, newCurrentIndex: Int) {
        if (isDragging) {
            
            pendingItems = newItems
            pendingCurrentIndex = newCurrentIndex
            return
        }
        applyData(newItems, newCurrentIndex)
    }

    private fun applyData(newItems: List<Song>, newCurrentIndex: Int) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].id == newItems[newItemPosition].id
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = items[oldItemPosition]
                val new = newItems[newItemPosition]
                val wasCurrent = oldItemPosition == currentIndex
                val isCurrentNow = newItemPosition == newCurrentIndex
                return old == new && wasCurrent == isCurrentNow
            }
        }
        
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems.toMutableList()
        currentIndex = newCurrentIndex
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val composeView = ComposeView(parent.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        return QueueViewHolder(composeView)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val song = items[position]
        holder.bind(song, position, position == currentIndex)
    }

    override fun onViewRecycled(holder: QueueViewHolder) {
        holder.composeView.disposeComposition()
    }

    override fun getItemCount(): Int = items.size

    inner class QueueViewHolder(internal val composeView: ComposeView) : RecyclerView.ViewHolder(composeView) {
        val isDragged = mutableStateOf(false)
        fun bind(song: Song, position: Int, isCurrent: Boolean) {
            composeView.setContent {
                QueueItemRow(song, position, isCurrent, isDragged.value, onItemClick)
            }
        }
    }
}

@Composable
fun QueueItemRow(
    song: Song, 
    index: Int, 
    isCurrent: Boolean, 
    isDragged: Boolean = false,
    onItemClick: (Int) -> Unit
) {
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDragged) Color.Black.copy(alpha = 0.8f) else Color.Transparent,
        label = "dragBgColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onItemClick(index) }
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            val normalizedUrl = song.coverUrl?.trim().orEmpty()
            val bitmapState = remember(normalizedUrl) { 
                mutableStateOf(ImageMemoryCache.getFromMemory(normalizedUrl)) 
            }
            
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
            
            bitmapState.value?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(song.color)
            )
            
            
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.albumspeaker),
                        contentDescription = "Playing",
                        tint = Color(0xFFE24A5A),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                color = if (isCurrent) Color(0xFFE24A5A) else Color.White,
                fontSize = 16.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = "Drag handle",
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer(scaleX = 0.9f, scaleY = 0.7f)
        )
    }
}
