package com.livestagix.vulkantest

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small, persistent session recorder for the Vulkan test app.
 * It keeps the latest entries in memory and appends them to app-private storage.
 */
object EventRecorder {
    private const val MAX_MEMORY_ENTRIES = 600
    private const val MAX_FILE_BYTES = 1_000_000L

    private val lock = Any()
    private val entries = mutableListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var listener: (() -> Unit)? = null

    private var logFile: File? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            logFile = File(context.applicationContext.filesDir, "vulkan-session.log")
            entries.clear()

            runCatching {
                logFile?.takeIf { it.exists() }?.readLines()
                    ?.takeLast(MAX_MEMORY_ENTRIES)
                    ?.let(entries::addAll)
            }
        }

        record("INFO", "APP", "Session started")
    }

    fun setListener(callback: (() -> Unit)?) {
        listener = callback
    }

    fun refreshFromDisk() {
        synchronized(lock) {
            val file = logFile
            if (file != null && file.isFile) {
                runCatching {
                    val latestEntries = file.readLines().takeLast(MAX_MEMORY_ENTRIES)
                    entries.clear()
                    entries.addAll(latestEntries)
                }
            }
        }
    }

    fun record(level: String, source: String, message: String) {
        val callback: (() -> Unit)?
        synchronized(lock) {
            val timestamp = synchronized(timeFormat) {
                timeFormat.format(Date())
            }
            val line = "$timestamp | ${level.uppercase(Locale.US)} | $source | $message"

            entries.add(line)
            if (entries.size > MAX_MEMORY_ENTRIES) {
                entries.removeAt(0)
            }

            runCatching {
                val file = logFile
                if (file != null) {
                    if (file.length() > MAX_FILE_BYTES) {
                        file.writeText("Log rotated because the previous session log reached 1 MB.\n")
                    }
                    file.appendText("$line\n")
                }
            }

            callback = listener
        }

        callback?.let { mainHandler.post { it() } }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            runCatching { logFile?.writeText("") }
        }
        record("INFO", "APP", "Log cleared")
    }

    fun snapshot(): String = synchronized(lock) {
        entries.joinToString(separator = "\n")
    }

    fun entryCount(): Int = synchronized(lock) {
        entries.size
    }

    fun logFilePath(): String? = synchronized(lock) {
        logFile?.absolutePath
    }
}
