package com.livestagix.vulkantest

import android.content.res.AssetManager
import android.view.Surface

/**
 * Shared JNI entry point for the single Vulkan renderer used by the wallpaper engine.
 */
object NativeVulkanRenderer {
    @Volatile
    private var libraryLoaded = false

    @Synchronized
    fun load(): Boolean {
        if (libraryLoaded) return true

        return runCatching {
            System.loadLibrary("vulkan_test")
            libraryLoaded = true
            true
        }.getOrElse {
            EventRecorder.record(
                "ERROR",
                "VULKAN",
                "Could not load native renderer library: ${{it.message}"
            )
            false
        }
    }

    external fun nativeStart(surface: Surface, assetManager: AssetManager)
    external fun nativeResize(width: Int, height: Int)
    external fun nativeSetPaused(paused: Boolean)
    external fun nativeStop()
}
