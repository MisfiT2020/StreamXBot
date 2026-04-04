package com.xstream.music.notifications

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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.xstream.music.app.MainActivity
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.Jam

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("onNewToken called with token: $token")
        
        
        val user = AuthPreferences.getUser(applicationContext)
        val apiUrl = ApiPreferences.getApiUrl(applicationContext)
        
        if (user != null && apiUrl.isNotEmpty()) {
            Timber.d("User is logged in, sending new token to backend...")
            scope.launch {
                val success = registerFcmToken(apiUrl, token, user.token)
                Timber.d("Token registration outcome: $success")
            }
        } else {
            Timber.d("Token updated but skipping registration: user null? ${user == null}, API empty? ${apiUrl.isEmpty()}")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Timber.d("Message received from: ${message.from}")
        Timber.d("Data payload: ${message.data}")

        
        val title = message.data["title"] ?: message.notification?.title ?: "StreamX"
        val body = message.data["body"] ?: message.notification?.body ?: "You have a new update"
        val type = message.data["type"]
        val jamId = message.data["jam_id"]
        val fromUserName = message.data["from_name"] ?: message.data["from"]

        val notificationTitle = if (type == "jam_invite" && fromUserName != null) {
            "Jam Invite"
        } else title

        val notificationBody = if (type == "jam_invite" && fromUserName != null) {
            "$fromUserName invited you to join a Jam!"
        } else body

        showNotification(notificationTitle, notificationBody, type, jamId)
    }

    private fun showNotification(title: String, body: String, type: String?, jamId: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "streamx_notifications"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "General Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Jam invites and system updates"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (type == "jam_invite" && jamId != null) {
                val apiUrl = ApiPreferences.getApiUrl(this@MyFirebaseMessagingService)
                putExtra("jam_id", jamId)
                putExtra("action", "join_jam")
                data = android.net.Uri.parse(
                    buildJamWebLink(jamId, apiUrl).ifBlank { "streamx://jam/$jamId" }
                )
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.albumspeaker) 
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}
