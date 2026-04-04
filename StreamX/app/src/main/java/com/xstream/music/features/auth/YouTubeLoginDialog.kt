package com.xstream.music.features.auth

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
import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var hasCompletedLogin by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webViewContext ->
                val prefs = webViewContext.getSharedPreferences("youtube_prefs", Context.MODE_PRIVATE)
                
                var visitorData = prefs.getString("visitor_data", "") ?: ""
                var dataSyncId = prefs.getString("data_sync_id", "") ?: ""

                WebView(webViewContext).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
                            loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt.config_.DATASYNC_ID)")

                            if (url?.startsWith("https://music.youtube.com") == true && !hasCompletedLogin) {
                                val innerTubeCookie = CookieManager.getInstance().getCookie(url) ?: ""
                                val hasAuthCookies = innerTubeCookie.contains("SAPISID=") || innerTubeCookie.contains("__Secure-3PAPISID=")
                                if (!hasAuthCookies) return
                                hasCompletedLogin = true

                                coroutineScope.launch {
                                    delay(500)

                                    prefs.edit()
                                        .putString("innertube_cookie", innerTubeCookie)
                                        .putString("visitor_data", visitorData)
                                        .putString("data_sync_id", dataSyncId)
                                        .apply()

                                    YouTube.cookie = innerTubeCookie
                                    YouTube.dataSyncId = dataSyncId
                                    YouTube.visitorData = visitorData

                                    Timber.d("Login: YouTube object initialized, validating...")

                                    YouTube.accountInfo().onSuccess {
                                        Timber.d("Login: Successfully logged in as ${it.name}")
                                        prefs.edit().putString("account_name", it.name).apply()

                                        stopLoading()
                                        clearHistory()
                                        clearCache(true)
                                        clearFormData()
                                        
                                        onLoginSuccess(it.name)
                                    }.onFailure {
                                        Timber.w(it, "Login: Authentication validation failed, continuing with cookie login")
                                        val fallbackName = prefs.getString("account_name", null) ?: "YouTube"
                                        onLoginSuccess(fallbackName)
                                    }
                                }
                            }
                        }
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onRetrieveVisitorData(newVisitorData: String?) {
                            if (newVisitorData != null) {
                                visitorData = newVisitorData
                            }
                        }
                        @JavascriptInterface
                        fun onRetrieveDataSyncId(newDataSyncId: String?) {
                            if (newDataSyncId != null) {
                                dataSyncId = newDataSyncId.substringBefore("||")
                            }
                        }
                    }, "Android")
                    
                    loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
                }
            }
        )
    }
}
