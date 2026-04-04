package com.xstream.music.core.cache

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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object ImageMemoryCache {
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(maxSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var diskCacheDir: File? = null

    fun init(context: Context) {
        diskCacheDir = File(context.cacheDir, "images").apply { if (!exists()) mkdirs() }
    }

    fun getFromMemory(url: String): Bitmap? = cache.get(url)

    fun get(url: String): Bitmap? {
        
        getFromMemory(url)?.let { return it }

        
        val diskFile = getDiskFile(url)
        if (diskFile != null && diskFile.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bitmap != null) {
                    cache.put(url, bitmap)
                    return bitmap
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return null
    }

    fun put(url: String, bitmap: Bitmap) {
        
        cache.put(url, bitmap)

        
        val diskFile = getDiskFile(url)
        if (diskFile != null && !diskFile.exists()) {
            try {
                FileOutputStream(diskFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getDiskFile(url: String): File? {
        val dir = diskCacheDir ?: return null
        val key = md5(url)
        return File(dir, key)
    }

    private fun md5(s: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(s.toByteArray())
            val messageDigest = digest.digest()
            val hexString = StringBuilder()
            for (aMessageDigest in messageDigest) {
                var h = Integer.toHexString(0xFF and aMessageDigest.toInt())
                while (h.length < 2) h = "0$h"
                hexString.append(h)
            }
            hexString.toString()
        } catch (e: Exception) {
            s.hashCode().toString()
        }
    }

    private fun maxSizeKb(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return maxMemoryKb / 8
    }

    fun clear(context: Context) {
        
        cache.evictAll()

        
        val dir = diskCacheDir ?: File(context.cacheDir, "images")
        if (dir.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
        }
    }
}
