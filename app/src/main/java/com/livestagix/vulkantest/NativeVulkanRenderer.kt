package com.livestagix.vulkantest

import android.content.res.AssetManager
import android.view.Surface

/**
 * JNI entry point for independent Vulkan renderer instances owned by wallpaper engines.
 */
object NativeVulkanRenderer {
    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var logListener: ((level: String, message: String) -> Unit)? = null

    fun setLogListener(listener: ((level: String, message: String) -> Unit)?) {
        logListener = listener
    }

    @JvmStatic
    fun onNativeLog(level: String, message: String) {
        logListener?.invoke(level, message)
    }

    @Synchronized
    fun load(): Boolean {
        if (libraryLoaded) return true

        return runCatching {
            System.loadLibrary("vulkan_test")
            libraryLoaded = true
            true
        }.getOrElse {
            onNativeLog(
                "ERROR",
                "Could not load native renderer library: ${it.message}"
            )
            false
        }
    }

    external fun nativeStart(surface: Surface, assetManager: AssetManager): Long
    external fun nativeResize(rendererHandle: Long, width: Int, height: Int)
    external fun nativeSetPaused(rendererHandle: Long, paused: Boolean)
    external fun nativeStop(rendererHandle: Long)
}
