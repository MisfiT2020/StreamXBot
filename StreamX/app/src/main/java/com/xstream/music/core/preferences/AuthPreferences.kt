package com.xstream.music.core.preferences

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
import android.content.Context
import com.xstream.music.data.model.UserData

object AuthPreferences {
    private const val PREFS_NAME = "auth_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_FIRST_NAME = "first_name"
    private const val KEY_PROFILE_URL = "profile_url"
    private const val KEY_PHOTO_URL = "photo_url"

    fun saveUser(context: Context, user: UserData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong(KEY_USER_ID, user.id)
            putString(KEY_TOKEN, user.token)
            putString(KEY_FIRST_NAME, user.firstName)
            putString(KEY_PROFILE_URL, user.profileUrl)
            putString(KEY_PHOTO_URL, user.photoUrl)
            apply()
        }
    }

    fun getUser(context: Context): UserData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val id = prefs.getLong(KEY_USER_ID, 0)
        val firstName = prefs.getString(KEY_FIRST_NAME, "") ?: ""
        val profileUrl = prefs.getString(KEY_PROFILE_URL, "") ?: ""
        val photoUrl = prefs.getString(KEY_PHOTO_URL, "") ?: ""
        return UserData(id, token, firstName, profileUrl, photoUrl)
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
