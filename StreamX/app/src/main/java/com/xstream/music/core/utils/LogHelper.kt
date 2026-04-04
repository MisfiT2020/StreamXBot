package com.xstream.music.core.utils

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
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object LogHelper {
    private const val LOG_FILE_NAME = "streamx_logs.txt"
    
    fun getLogFile(context: Context): File {
        return File(context.cacheDir, LOG_FILE_NAME)
    }

    fun clearLogs(context: Context) {
        try {
            val cacheDir = context.cacheDir
            val logFiles = cacheDir.listFiles { file ->
                file.name.startsWith("streamx_logs")
            }
            
            var deletedCount = 0
            logFiles?.forEach { file ->
                if (file.delete()) {
                    deletedCount++
                }
            }

            if (deletedCount > 0) {
                android.widget.Toast.makeText(context, "Logs cleared ($deletedCount files)", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(context, "No logs to clear", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error clearing logs")
            android.widget.Toast.makeText(context, "Failed to clear logs", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun exportLogs(context: Context) {
        val file = getLogFile(context)
        if (!file.exists()) {
            android.widget.Toast.makeText(context, "No logs available to export", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "StreamX Debug Logs")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, "Export Logs"))
        } catch (e: Exception) {
            Timber.e(e, "Error exporting logs")
            android.widget.Toast.makeText(context, "Failed to export logs", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    class FileLoggingTree(private val context: Context) : Timber.DebugTree() {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            super.log(priority, tag, message, t)
            
            try {
                val file = getLogFile(context)
                val writer = FileWriter(file, true)
                val timestamp = dateFormat.format(Date())
                val priorityStr = when (priority) {
                    android.util.Log.DEBUG -> "D"
                    android.util.Log.INFO -> "I"
                    android.util.Log.WARN -> "W"
                    android.util.Log.ERROR -> "E"
                    else -> "V"
                }
                
                writer.append("$timestamp $priorityStr/${tag ?: "App"}: $message\n")
                t?.let {
                    writer.append(android.util.Log.getStackTraceString(it))
                    writer.append("\n")
                }
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                
            }
        }
    }
}
