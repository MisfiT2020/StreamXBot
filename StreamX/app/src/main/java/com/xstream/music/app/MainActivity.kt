package com.xstream.music.app

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
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import timber.log.Timber
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.Jam
import com.xstream.music.data.model.Notification
import com.xstream.music.player.service.MusicPlaybackService

@UnstableApi
class MainActivity : ComponentActivity() {

    private var mediaController: MediaController? = null
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Timber.d("Notification permission granted")
        } else {
            Timber.d("Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.let { win ->
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    win.windowManager.defaultDisplay
                }
                
                if (display != null) {
                    val modes = display.supportedModes
                    val maxMode = modes.maxByOrNull { it.refreshRate }
                    if (maxMode != null) {
                        val layoutParams = win.attributes
                        layoutParams.preferredDisplayModeId = maxMode.modeId
                        win.attributes = layoutParams
                    }
                }
            }
        }
        
        enableEdgeToEdge()

        askNotificationPermission()
        registerFcmTokenOnStart()

        setContent {
            MusicApp()
        }

        val sessionToken = SessionToken(
            this,
            ComponentName(this, MusicPlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        controllerFuture.addListener({
            mediaController = controllerFuture.get()
        }, MoreExecutors.directExecutor())
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                
            } else {
                
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun registerFcmTokenOnStart() {
        Timber.d("Starting FCM token registration check...")
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.w(task.exception, "Fetching FCM registration token failed")
                return@addOnCompleteListener
            }

            
            val token = task.result
            Timber.d("FCM Token retrieved successfully: $token")

            
            val user = AuthPreferences.getEffectiveToken(applicationContext)
            val apiUrl = ApiPreferences.getApiUrl(applicationContext)
            if (user != null && apiUrl.isNotEmpty()) {
                Timber.d("User is logged in, registering token with backend...")
                lifecycleScope.launch {
                    val success = registerFcmToken(apiUrl, token, user)
                    Timber.d("FCM token registration success: $success")
                }
            } else {
                Timber.d("Skipping FCM registration: user logged in? ${user != null}, API URL? ${apiUrl.isNotEmpty()}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        
        val action = intent.getStringExtra("action")
        var jamId = intent.getStringExtra("jam_id")

        
        val data = intent.data
        if (data != null && data.scheme == "streamx" && data.host == "join-jam") {
            
            jamId = data.lastPathSegment
            Timber.d("Deep link detected: Join Jam $jamId")
        }

        if ((action == "join_jam" || data != null) && jamId != null) {
            Timber.d("Processing Join Jam for $jamId")
            
        }
    }

    override fun onDestroy() {
        if (::controllerFuture.isInitialized) {
            MediaController.releaseFuture(controllerFuture)
        }
        super.onDestroy()
    }
}
