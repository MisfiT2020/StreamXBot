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
import android.app.Application
import com.xstream.music.core.cache.ImageMemoryCache

class StreamXApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ImageMemoryCache.init(this)
        com.xstream.music.utils.cipher.CipherDeobfuscator.initialize(this)
    }
}
