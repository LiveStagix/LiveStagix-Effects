package com.livestagix.vulkantest

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var statusLabel: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var nativeLibraryLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventRecorder.initialize(this)
        activeActivity = WeakReference(this)

        window.statusBarColor = Color.rgb(10, 12, 18)
        window.navigationBarColor = Color.rgb(10, 12, 18)

        nativeLibraryLoaded = runCatching {
            System.loadLibrary("vulkan_test")
            true
        }.getOrElse {
            EventRecorder.record("ERROR", "APP", "Could not load native renderer: ${it.message}")
            false
        }

        buildScreen()
        EventRecorder.record(
            "INFO",
            "DEVICE",
            "Device=${Build.MANUFACTURER} ${Build.MODEL}; Android API=${Build.VERSION.SDK_INT}; " +
                "hardware=${Build.HARDWARE}; ABI=${Build.SUPPORTED_ABIS.joinToString()}"
        )
        logVulkanFeatures()

        EventRecorder.logFilePath()?.let {
            EventRecorder.record("INFO", "APP", "Private session log: $it")
        }

        EventRecorder.setListener {
            refreshLogs()
        }
        refreshLogs()

        if (!nativeLibraryLoaded) {
            statusLabel.text = "Renderer status: native library failed to load"
        } else {
            statusLabel.text = "Renderer status: waiting for Android surface"
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.rgb(10, 12, 18))
        }

        val title = TextView(this).apply {
            text = "LIVESTAGIX VULKAN LAB"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
        ))

        statusLabel = TextView(this).apply {
            text = "Renderer status: preparing"
            textSize = 12f
            setTextColor(Color.rgb(177, 184, 202))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(30)
        ))

        val surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.OPAQUE)
            setKeepScreenOn(true)
        }
        surfaceView.holder.addCallback(this)

        val surfaceFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 21, 32))
            addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        root.addView(surfaceFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(245)
        ))

        val logHeader = TextView(this).apply {
            text = "EVENT RECORDER"
            textSize = 12f
            setTextColor(Color.rgb(190, 196, 214))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(logHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(32)
        ))

        logText = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(215, 221, 235))
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setBackgroundColor(Color.rgb(18, 21, 30))
            text = "Waiting for events..."
        }

        logScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(logText)
        }
        root.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val copyButton = Button(this).apply {
            text = "Copy logs"
            setOnClickListener { copyLogs() }
        }
        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener {
                EventRecorder.clear()
                refreshLogs()
            }
        }

        buttons.addView(copyButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(clearButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ))

        setContentView(root)
    }

    private fun logVulkanFeatures() {
        val features = packageManager.systemAvailableFeatures
            .filter { it.name?.contains("vulkan", ignoreCase = true) == true }
            .joinToString { "${it.name} (version=${it.version})" }

        if (features.isBlank()) {
            EventRecorder.record(
                "WARN",
                "DEVICE",
                "Android did not advertise Vulkan hardware features through PackageManager"
            )
        } else {
            EventRecorder.record("INFO", "DEVICE", "Advertised Vulkan features: $features")
        }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("LiveStagix Vulkan session log", EventRecorder.snapshot())
        )
        Toast.makeText(this, "Session log copied", Toast.LENGTH_SHORT).show()
    }

    private fun refreshLogs() {
        if (!::logText.isInitialized) return
        logText.text = EventRecorder.snapshot().ifBlank { "No events recorded." }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        EventRecorder.record("INFO", "SURFACE", "Surface created")
        if (!nativeLibraryLoaded) return

        runCatching {
            nativeStart(holder.surface, assets)
        }.onFailure {
            EventRecorder.record("ERROR", "APP", "Could not start Vulkan renderer: ${it.message}")
        }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        EventRecorder.record("INFO", "SURFACE", "Surface size changed to ${width}x${height}")
        if (nativeLibraryLoaded) nativeResize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        EventRecorder.record("INFO", "SURFACE", "Surface destroyed")
        if (nativeLibraryLoaded) nativeStop()
    }

    override fun onPause() {
        if (nativeLibraryLoaded) nativeSetPaused(true)
        EventRecorder.record("INFO", "LIFECYCLE", "Activity paused")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (nativeLibraryLoaded) nativeSetPaused(false)
        EventRecorder.record("INFO", "LIFECYCLE", "Activity resumed")
    }

    override fun onDestroy() {
        if (nativeLibraryLoaded) nativeStop()
        EventRecorder.setListener(null)
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private external fun nativeStart(surface: Surface, assetManager: AssetManager)
    private external fun nativeResize(width: Int, height: Int)
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeStop()

    companion object {
        private var activeActivity: WeakReference<MainActivity>? = null

        @JvmStatic
        fun onNativeLog(level: String, message: String) {
            EventRecorder.record(level, "VULKAN", message)
            val activity = activeActivity?.get() ?: return

            activity.runOnUiThread {
                if (!activity::statusLabel.isInitialized) return@runOnUiThread

                activity.statusLabel.text = when {
                    level.equals("ERROR", ignoreCase = true) ->
                        "Renderer status: error — $message"
                    message.startsWith("READY:") ->
                        "Renderer status: running — ${message.removePrefix("READY:").trim()}"
                    message.contains("paused", ignoreCase = true) ->
                        "Renderer status: paused"
                    message.contains("resumed", ignoreCase = true) ->
                        "Renderer status: running"
                    message.startsWith("Renderer stopped") ->
                        "Renderer status: stopped"
                    else -> activity.statusLabel.text
                }
            }
        }
    }
}
