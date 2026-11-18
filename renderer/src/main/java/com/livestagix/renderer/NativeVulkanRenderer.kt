package com.livestagix.renderer

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.livestagix.renderer.api.RendererFailure
import com.livestagix.renderer.api.RendererFailureCategory

/**
 * JNI entry point for independent Vulkan renderer instances owned by wallpaper engines.
 */
object NativeVulkanRenderer {
    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var logListener: ((level: String, message: String) -> Unit)? = null

    private val failureLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val failureListeners = mutableMapOf<Long, (RendererFailure) -> Unit>()
    private val pendingFailures = mutableMapOf<Long, RendererFailure>()

    fun setLogListener(listener: ((level: String, message: String) -> Unit)?) {
        logListener = listener
    }

    fun registerFailureListener(
        rendererHandle: Long,
        listener: (RendererFailure) -> Unit
    ) {
        val pending = synchronized(failureLock) {
            failureListeners[rendererHandle] = listener
            pendingFailures.remove(rendererHandle)
        }
        pending?.let { failure -> dispatchFailure(listener, failure) }
    }

    fun unregisterFailureListener(rendererHandle: Long) {
        synchronized(failureLock) {
            failureListeners.remove(rendererHandle)
            pendingFailures.remove(rendererHandle)
        }
    }

    private fun dispatchFailure(
        listener: (RendererFailure) -> Unit,
        failure: RendererFailure
    ) {
        mainHandler.post {
            runCatching { listener(failure) }.onFailure { error ->
                onNativeLog("ERROR", "Renderer failure listener threw: ${error.message}")
            }
        }
    }

    @JvmStatic
    fun onNativeLog(level: String, message: String) {
        runCatching { logListener?.invoke(level, message) }
    }

    @JvmStatic
    fun onNativeFailure(
        rendererHandle: Long,
        categoryName: String,
        diagnosticCode: String,
        message: String
    ) {
        val category = runCatching {
            RendererFailureCategory.valueOf(categoryName)
        }.getOrDefault(RendererFailureCategory.UNKNOWN)
        val failure = RendererFailure(
            category = category,
            message = message,
            diagnosticCode = diagnosticCode.ifBlank { null }
        )

        val listener = synchronized(failureLock) {
            failureListeners[rendererHandle].also { current ->
                if (current == null) pendingFailures[rendererHandle] = failure
            }
        }
        listener?.let { callback -> dispatchFailure(callback, failure) }
    }

    @Synchronized
    fun load(): Boolean {
        if (libraryLoaded) return true

        return runCatching {
            System.loadLibrary("livestagix_renderer")
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

    external fun nativeInspectSupport(): Array<String>?
    external fun nativeStart(surface: Surface, assetManager: AssetManager): Long
    external fun nativeGetCapabilities(rendererHandle: Long): Array<String>?
    external fun nativeResize(rendererHandle: Long, width: Int, height: Int)
    external fun nativeSetPaused(rendererHandle: Long, paused: Boolean)
    external fun nativeStop(rendererHandle: Long)
}
