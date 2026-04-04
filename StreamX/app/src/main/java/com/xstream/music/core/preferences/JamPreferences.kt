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

object JamPreferences {
    private const val PREFS_NAME = "streamx_jam_prefs"
    private const val KEY_JAM_ID = "jam_id"

    fun getStoredJamId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JAM_ID, null)
    }

    fun setStoredJamId(context: Context, jamId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JAM_ID, jamId)
            .apply()
    }

    fun clearStoredJamId(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_JAM_ID)
            .apply()
    }
}
