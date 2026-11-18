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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusLabel: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wallpaperReturnVerificationRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventRecorder.initialize(this)

        window.statusBarColor = Color.rgb(10, 12, 18)
        window.navigationBarColor = Color.rgb(10, 12, 18)

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

        EventRecorder.setListener { refreshLogs() }
        refreshLogs()
        statusLabel.text = "Ready — choose a renderer to test"
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.rgb(10, 12, 18))
        }

        val title = TextView(this).apply {
            text = "LIVESTAGIX RENDERER LAB"
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

        val previewButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val vulkanPreviewButton = Button(this).apply {
            text = "Test Vulkan"
            setOnClickListener {
                openWallpaperPreview(
                    ComponentName(this@MainActivity, VulkanWallpaperService::class.java),
                    "Vulkan"
                )
            }
        }
        val gles3PreviewButton = Button(this).apply {
            text = "Test GLES 3.0"
            setOnClickListener {
                openWallpaperPreview(
                    ComponentName(this@MainActivity, Gles3WallpaperService::class.java),
                    "GLES 3.0"
                )
            }
        }
        previewButtons.addView(
            vulkanPreviewButton,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        previewButtons.addView(
            gles3PreviewButton,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        root.addView(previewButtons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ))

        val gles2PreviewButton = Button(this).apply {
            text = "Test GLES 2.0"
            setOnClickListener {
                openWallpaperPreview(
                    ComponentName(this@MainActivity, Gles2WallpaperService::class.java),
                    "GLES 2.0"
                )
            }
        }
        root.addView(gles2PreviewButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
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

    private fun openWallpaperPreview(service: ComponentName, backendName: String) {
        val setupIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, service)
        }

        armWallpaperReturnVerification(service)
        statusLabel.text = "Opening Android wallpaper setup ($backendName)..."
        EventRecorder.record(
            "INFO",
            "APP",
            "Launching $backendName live-wallpaper setup; " +
                "component=${service.flattenToShortString()}"
        )

        runCatching {
            startActivity(setupIntent)
        }.onFailure { error ->
            wallpaperSetupPreferences().edit()
                .putBoolean(PREF_RETURN_VERIFY_ARMED, false)
                .putBoolean(PREF_RETURN_VERIFY_BACKGROUND, false)
                .putString(PREF_RETURN_VERIFY_STATE, "idle")
                .apply()
            statusLabel.text = "Could not open wallpaper setup"
            EventRecorder.record(
                "ERROR",
                "APP",
                "Could not open $backendName wallpaper setup: ${error.message}"
            )
            Toast.makeText(this, "Could not open wallpaper setup", Toast.LENGTH_SHORT).show()
        }
    }

    private fun wallpaperSetupPreferences() =
        getSharedPreferences(PREF_WALLPAPER_SETUP, MODE_PRIVATE)

    private fun armWallpaperReturnVerification(service: ComponentName) {
        wallpaperSetupPreferences().edit()
            .putString(PREF_EXPECTED_WALLPAPER_COMPONENT, service.flattenToString())
            .putBoolean(PREF_RETURN_VERIFY_ARMED, true)
            .putBoolean(PREF_RETURN_VERIFY_BACKGROUND, false)
            .putString(PREF_RETURN_VERIFY_STATE, "armed")
            .putBoolean(PREF_SETUP_SUCCEEDED, false)
            .putLong(PREF_SETUP_COMPLETED_AT, 0L)
            .apply()
        EventRecorder.record("INFO", "VERIFY", "Wallpaper return verification armed")
    }

    private fun markWallpaperSetupBackgrounded() {
        val prefs = wallpaperSetupPreferences()
        if (prefs.getBoolean(PREF_RETURN_VERIFY_ARMED, false)) {
            prefs.edit().putBoolean(PREF_RETURN_VERIFY_BACKGROUND, true).apply()
            EventRecorder.record("INFO", "VERIFY", "App backgrounded while wallpaper setup was armed")
        }
    }

    private fun startWallpaperReturnVerificationAfterResume() {
        val prefs = wallpaperSetupPreferences()
        val armed = prefs.getBoolean(PREF_RETURN_VERIFY_ARMED, false)
        val backgrounded = prefs.getBoolean(PREF_RETURN_VERIFY_BACKGROUND, false)
        val state = prefs.getString(PREF_RETURN_VERIFY_STATE, "idle")
        val recoveringInterruptedCheck = state == "verifying"

        if (!(armed && backgrounded) && !recoveringInterruptedCheck) return
        if (wallpaperReturnVerificationRunnable != null) return

        prefs.edit()
            .putBoolean(PREF_RETURN_VERIFY_ARMED, false)
            .putBoolean(PREF_RETURN_VERIFY_BACKGROUND, false)
            .putString(PREF_RETURN_VERIFY_STATE, "verifying")
            .apply()

        EventRecorder.record(
            "INFO",
            "VERIFY",
            "Returned from Android wallpaper setup; checking applied wallpaper in 3 seconds"
        )

        val verification = Runnable {
            try {
                val manager = WallpaperManager.getInstance(this)
                val expected = ComponentName.unflattenFromString(
                    prefs.getString(PREF_EXPECTED_WALLPAPER_COMPONENT, "").orEmpty()
                ) ?: ComponentName(this, VulkanWallpaperService::class.java)
                val home = manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
                val lock = manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)
                val ownsHome = home?.component == expected
                var ownsLock = lock?.component == expected

                // Android may not expose separate Lock WallpaperInfo when Home and Lock share one wallpaper.
                if (ownsHome && !ownsLock) {
                    ownsLock = runCatching {
                        manager.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0
                    }.getOrElse {
                        EventRecorder.record(
                            "WARN",
                            "VERIFY",
                            "Could not confirm shared Lock wallpaper ownership: ${it.message}"
                        )
                        false
                    }
                }

                val succeeded = ownsHome && ownsLock
                val completedAt = System.currentTimeMillis()
                prefs.edit()
                    .putString(PREF_RETURN_VERIFY_STATE, "completed")
                    .putBoolean(PREF_SETUP_SUCCEEDED, succeeded)
                    .putBoolean(PREF_OWNERSHIP_CHECK_SUCCEEDED, true)
                    .putBoolean(PREF_OWNS_HOME, ownsHome)
                    .putBoolean(PREF_OWNS_LOCK, ownsLock)
                    .putLong(PREF_SETUP_COMPLETED_AT, completedAt)
                    .apply()

                statusLabel.text = if (succeeded) {
                    "Wallpaper applied and verified"
                } else {
                    "Setup returned — wallpaper was not applied"
                }
                EventRecorder.record(
                    if (succeeded) "INFO" else "WARN",
                    "VERIFY",
                    "Wallpaper ownership check completed: ownsHome=${ownsHome}, ownsLock=${ownsLock}, setupSucceeded=${succeeded}"
                )
            } catch (error: Throwable) {
                val completedAt = System.currentTimeMillis()
                prefs.edit()
                    .putString(PREF_RETURN_VERIFY_STATE, "completed")
                    .putBoolean(PREF_SETUP_SUCCEEDED, false)
                    .putBoolean(PREF_OWNERSHIP_CHECK_SUCCEEDED, false)
                    .putBoolean(PREF_OWNS_HOME, false)
                    .putBoolean(PREF_OWNS_LOCK, false)
                    .putLong(PREF_SETUP_COMPLETED_AT, completedAt)
                    .apply()
                statusLabel.text = "Could not verify wallpaper setup"
                EventRecorder.record(
                    "ERROR",
                    "VERIFY",
                    "Wallpaper ownership verification failed: ${error.message}"
                )
            } finally {
                wallpaperReturnVerificationRunnable = null
                refreshLogs()
            }
        }

        wallpaperReturnVerificationRunnable = verification
        mainHandler.postDelayed(verification, WALLPAPER_RETURN_VERIFICATION_DELAY_MS)
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
            ClipData.newPlainText("LiveStagix renderer session log", EventRecorder.snapshot())
        )
        Toast.makeText(this, "Session log copied", Toast.LENGTH_SHORT).show()
    }

    private fun refreshLogs() {
        if (!::logText.isInitialized) return
        EventRecorder.refreshFromDisk()
        logText.text = EventRecorder.snapshot().ifBlank { "No events recorded." }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onPause() {
        markWallpaperSetupBackgrounded()
        EventRecorder.record("INFO", "LIFECYCLE", "Activity paused")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        EventRecorder.refreshFromDisk()
        EventRecorder.record("INFO", "LIFECYCLE", "Activity resumed")
        startWallpaperReturnVerificationAfterResume()
        refreshLogs()
    }

    override fun onDestroy() {
        wallpaperReturnVerificationRunnable?.let(mainHandler::removeCallbacks)
        wallpaperReturnVerificationRunnable = null
        EventRecorder.setListener(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREF_WALLPAPER_SETUP = "vulkan_wallpaper_setup"
        private const val PREF_EXPECTED_WALLPAPER_COMPONENT = "wallpaper_return_verification_component"
        private const val PREF_RETURN_VERIFY_ARMED = "wallpaper_return_verification_armed"
        private const val PREF_RETURN_VERIFY_BACKGROUND = "wallpaper_return_verification_backgrounded"
        private const val PREF_RETURN_VERIFY_STATE = "wallpaper_return_verification_state"
        private const val PREF_SETUP_SUCCEEDED = "wallpaper_return_verification_setup_succeeded"
        private const val PREF_SETUP_COMPLETED_AT = "wallpaper_return_verification_completed_at"
        private const val PREF_OWNERSHIP_CHECK_SUCCEEDED = "wallpaper_return_verification_success"
        private const val PREF_OWNS_HOME = "wallpaper_return_verification_owns_home"
        private const val PREF_OWNS_LOCK = "wallpaper_return_verification_owns_lock"
        private const val WALLPAPER_RETURN_VERIFICATION_DELAY_MS = 3_000L

    }
}
