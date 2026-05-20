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
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.xstream.music.data.api.normalizeApiInput
import com.xstream.music.data.api.parseJam
import com.xstream.music.data.model.Jam

object JamWebSocketManager {
    private var webSocket: WebSocket? = null
    @Volatile
    private var manualDisconnectRequested = false
    
    private val _jamState = MutableStateFlow<Jam?>(null)
    val jamState: StateFlow<Jam?> = _jamState.asStateFlow()

    fun isConnected(): Boolean = webSocket != null

    fun updateState(state: Jam) {
        _jamState.value = state
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS) 
        .build()

    private var reconnectAttempt = 0
    private const val MAX_RECONNECT_DELAY = 5000L

    fun connect(apiBaseUrl: String, jamId: String, token: String, initialState: Jam? = null) {
        if (token.isBlank()) {
            Timber.d("Blocking Jam WebSocket connection for unauthenticated guest.")
            return
        }
        
        manualDisconnectRequested = false
        disconnect(isReconnecting = true)

        if (initialState != null) {
            _jamState.value = initialState
        }

        val normalizedBase = normalizeApiInput(apiBaseUrl)
            .replace("http://", "ws://")
            .replace("https://", "wss://")
        
        val wsUrl = "$normalizedBase/jam/$jamId/ws?token=$token"
        Timber.d("Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("WebSocket Connected")
                reconnectAttempt = 0
                manualDisconnectRequested = false
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Timber.d("WebSocket Message: $text")
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    if (type == "jam_state") {
                        val jamObj = json.optJSONObject("jam")
                        if (jamObj != null) {
                            val state = parseJam(jamObj)
                            _jamState.value = state
                            Timber.i("WS: Parsed full Jam State. Track: ${state.playback.trackId}. Queue length: ${state.queue.size}. Listeners: ${state.members.size}")
                            Timber.d("WS: Playback -> isPlaying=${state.playback.isPlaying}, pos=${state.playback.positionSec}, startedAt=${state.playback.startedAt}, updated=${state.updatedAt}")
                        } else {
                             Timber.e("jam_state received but no jam object")
                        }
                    } else if (type == "jam_ended") {
                        Timber.i("WS: Received jam_ended event. Disconnecting...")
                        _jamState.value = null
                        disconnect()
                    } else if (type == "pong") {
                         Timber.d("WS: Received Pong")
                    } else {
                         Timber.d("WS: Unknown message type: $type")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error parsing message: $text")
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("WebSocket Closed: $code / $reason")
                val isActiveSocket = JamWebSocketManager.webSocket === webSocket
                if (isActiveSocket) {
                    JamWebSocketManager.webSocket = null
                }
                if (isActiveSocket && !manualDisconnectRequested) {
                    attemptReconnect(apiBaseUrl, jamId, token)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "WebSocket Failure: ${t.message}")
                val isActiveSocket = JamWebSocketManager.webSocket === webSocket
                if (isActiveSocket) {
                    JamWebSocketManager.webSocket = null
                }
                if (isActiveSocket && !manualDisconnectRequested) {
                    attemptReconnect(apiBaseUrl, jamId, token)
                }
            }
        })
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun attemptReconnect(apiBaseUrl: String, jamId: String, token: String) {
        if (webSocket != null) return 
        
        reconnectAttempt++
        val delayMs = (reconnectAttempt * 1000L).coerceAtMost(MAX_RECONNECT_DELAY)
        Timber.d("Reconnecting Jam in ${delayMs}ms (Attempt $reconnectAttempt)")
        
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (webSocket == null) {
                connect(apiBaseUrl, jamId, token)
            }
        }
    }

    fun disconnect(isReconnecting: Boolean = false) {
        manualDisconnectRequested = !isReconnecting
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        if (!isReconnecting) {
            _jamState.value = null
        }
    }

    fun sendAction(type: String, data: Map<String, Any?> = emptyMap()): Boolean {
        val socket = webSocket
        if (socket == null) {
            Timber.e("Cannot send action '$type': WebSocket is null (disconnected)")
            return false
        }
        try {
            val json = JSONObject().apply {
                put("type", type)
                data.forEach { (key, value) ->
                    when (value) {
                        is List<*> -> {
                            val arr = org.json.JSONArray()
                            value.forEach { arr.put(it) }
                            put(key, arr)
                        }
                        else -> put(key, value)
                    }
                }
            }
            val message = json.toString()
            Timber.d("Sending WebSocket Message: $message")
            return socket.send(message)
        } catch (e: Exception) {
            Timber.e(e, "Error sending action: $type")
            return false
        }
    }
}
