package com.xstream.music.realtime.websocket

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
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.xstream.music.data.api.normalizeApiInput

object PresenceWebSocketManager {
    private var webSocket: WebSocket? = null
    
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var reconnectAttempt = 0
    private const val MAX_RECONNECT_DELAY = 5000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var isIntentionallyDisconnected = false
    
    private var lastApiBaseUrl: String? = null
    private var lastToken: String? = null

    private val _messages = MutableSharedFlow<JSONObject>()
    val messages: SharedFlow<JSONObject> = _messages.asSharedFlow()

    fun isConnected(): Boolean = webSocket != null

    fun connect(apiBaseUrl: String, token: String) {
        if (token.isBlank()) {
            Timber.d("Blocking Presence WebSocket connection for unauthenticated guest.")
            return
        }
        lastApiBaseUrl = apiBaseUrl
        lastToken = token
        disconnect(isReconnecting = true)
        isIntentionallyDisconnected = false

        val normalizedBase = normalizeApiInput(apiBaseUrl)
            .replace("http://", "ws://")
            .replace("https://", "wss://")
        
        val wsUrl = "$normalizedBase/ws/$token"
        Timber.d("Connecting to Presence WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("Presence WebSocket Connected")
                reconnectAttempt = 0
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Timber.d("Presence WebSocket Message: $text")
                try {
                    val json = JSONObject(text)
                    scope.launch { _messages.emit(json) }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse presence message")
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("Presence WebSocket Closed: $code / $reason")
                stopHeartbeat()
                if (PresenceWebSocketManager.webSocket === webSocket) {
                    PresenceWebSocketManager.webSocket = null
                }
                if (!isIntentionallyDisconnected) {
                    attemptReconnect(apiBaseUrl, token)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "Presence WebSocket Failure: ${t.message}")
                stopHeartbeat()
                if (PresenceWebSocketManager.webSocket === webSocket) {
                    PresenceWebSocketManager.webSocket = null
                }
                if (!isIntentionallyDisconnected) {
                    attemptReconnect(apiBaseUrl, token)
                }
            }
        })
    }

    private fun attemptReconnect(apiBaseUrl: String, token: String) {
        if (webSocket != null) return 
        
        reconnectAttempt++
        val delayMs = (reconnectAttempt * 1000L).coerceAtMost(MAX_RECONNECT_DELAY)
        Timber.d("Reconnecting Presence in ${delayMs}ms (Attempt $reconnectAttempt)")
        
        scope.launch {
            delay(delayMs)
            if (!isIntentionallyDisconnected && webSocket == null) {
                connect(apiBaseUrl, token)
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(30_000L) 
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendHeartbeat() {
        val socket = webSocket
        if (socket != null) {
            try {
                val json = JSONObject().apply {
                    put("type", "heartbeat")
                }
                socket.send(json.toString())
                Timber.d("Sent heartbeat")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send heartbeat")
            }
        }
    }

    fun sendListeningUpdate(trackId: String, isPlaying: Boolean, positionSec: Double, jamId: String? = null) {
        val socket = webSocket
        if (socket != null) {
            try {
                val json = JSONObject().apply {
                    put("type", "listening_update")
                    put("track_id", trackId)
                    put("is_playing", isPlaying)
                    put("position_sec", positionSec)
                    if (jamId != null) {
                        put("jam_id", jamId)
                    }
                }
                socket.send(json.toString())
                Timber.d("Sent listening update: track=$trackId, playing=$isPlaying, pos=$positionSec")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send listening update")
            }
        } else if (!isIntentionallyDisconnected) {
            val url = lastApiBaseUrl
            val token = lastToken
            if (url != null && token != null) {
                Timber.d("Socket null, attempting immediate reconnect before update")
                connect(url, token)
            }
        }
    }

    fun disconnect(isReconnecting: Boolean = false) {
        if (!isReconnecting) {
            isIntentionallyDisconnected = true
        }
        stopHeartbeat()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
