package com.xstream.music.features.home

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xstream.music.data.model.Song

class LatestSongsViewModel(private val apiUrl: String, private val token: String? = null) : ViewModel() {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()
    
    private var currentPage = 1
    
    init {
        loadMore()
    }
    
    fun loadMore() {
        if (_isLoading.value || !_hasMore.value) return
        
        viewModelScope.launch {
            _isLoading.value = true
            
            val newSongs = withContext(Dispatchers.IO) {
                try {
                    fetchBrowseSongs(apiUrl, currentPage, token = token) 
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }
            
            if (newSongs.isEmpty()) {
                _hasMore.value = false
            } else {
                _songs.value = _songs.value + newSongs
                currentPage++
            }
            
            _isLoading.value = false
        }
    }
}
