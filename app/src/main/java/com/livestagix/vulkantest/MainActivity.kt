package com.livestagix.vulkantest

import android.app.Activity
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference

class MainActivity : Activity() {
    private lateinit var statusLabel: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventRecorder.initialize(this)
        activeActivity = WeakReference(this)

        window.statusBarColor = Color.rgb(10, 12, 18)
        window.navigationBarColor = Color.rgb(10, 12, 18)

        buildScreen()
        EventRecorder.record(
            "INFO",
            "DEVICE",
            "Device=${{Build.MANUFACTURER} ${{Build.MODEL}; Android API=${{Build.VERSION.SDK_INT}; " +
                "hardware=${{Build.HARDWARE}; ABI=${{Build.SUPPORTED_ABIS.joinToString()}"
        )
        logVulkanFeatures()

        EventRecorder.logFilePath()?.let {
            EventRecorder.record("INFO", "APP", "Private session log: $it")
        }

        EventRecorder.setListener { refreshLogs() }
        refreshLogs()
        statusLabel.text = "Ready — preview the Vulkan live wallpaper"
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.rgb(10, 12, 18))
        }

        val title = TextView(this).apply {
            text = "LIVESTAGIX VULKAN MOCK"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(38)
        ))

        statusLabel = TextView(this).apply {
            text = "Preparing wallpaper preview"
            textSize = 12f
            setTextColor(Color.rgb(177, 184, 202))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
        ))

        val previewButton = Button(this).apply {
            text = "Preview LiveStagix wallpaper"
            setOnClickListener { openWallpaperPreview() }
        }
        root.addView(previewButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ))

        val logHeader = TextView(this).apply {
            text = "EVENT RECORDER"
            textSize = 12f
            setTextColor(Color.rgb(190, 196, 214))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(logHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
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

    private fun openWallpaperPreview() {
        val service = ComponentName(this, VulkanWallpaperService::class.java)
        val previewIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, service)
        }

        statusLabel.text = "Opening Android wallpaper preview..."
        EventRecorder.record("INFO", "APP", "Opening Android live wallpaper preview")

        runCatching {
            startActivity(previewIntent)
        }.onFailure {
            statusLabel.text = "Could not open wallpaper preview"
            EventRecorder.record(
                "ERROR",
                "APP",
                "Could not open Android wallpaper preview: ${{it.message}"
            )
            Toast.makeText(
                this,
                "Could not open wallpaper preview",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun logVulkanFeatures() {
        val features = packageManager.systemAvailableFeatures
            .filter { it.name?.contains("vulkan", ignoreCase = true) == true }
            .joinToString { "${{it.name} (version=${{it.version})" }

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

    override fun onPause() {
        EventRecorder.record("INFO", "LIFECYCLE", "Activity paused")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        EventRecorder.record("INFO", "LIFECYCLE", "Activity resumed")
    }

    override fun onDestroy() {
        EventRecorder.setListener(null)
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
                        "Wallpaper status: error — $message"
                    message.startsWith("READY:") ->
                        "Wallpaper status: rendering — ${{message.removePrefix("READY:").trim()}"
                    message.contains("Renderer paused", ignoreCase = true) ->
                        "Wallpaper status: paused"
                    message.contains("Renderer resumed", ignoreCase = true) ->
                        "Wallpaper status: rendering"
                    message.startsWith("Renderer stopped") ->
                        "Wallpaper status: stopped"
                    else -> activity.statusLabel.text
                }
            }
        }
    }
}
