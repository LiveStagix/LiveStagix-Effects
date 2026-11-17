package com.livestagix.renderer.backends.vulkan

import android.content.res.AssetManager
import android.view.Surface
import com.livestagix.renderer.NativeVulkanRenderer
import com.livestagix.renderer.api.RendererBackend
import com.livestagix.renderer.api.RendererLogListener
import com.livestagix.renderer.api.RendererSession
import com.livestagix.renderer.api.RendererType

/** Adapts the existing native Vulkan renderer to the backend-neutral API. */
class VulkanRendererBackend : RendererBackend {
    override val type: RendererType = RendererType.VULKAN

    override fun setLogListener(listener: RendererLogListener?) {
        NativeVulkanRenderer.setLogListener(
            listener?.let { callback ->
                { level: String, message: String -> callback.onLog(level, message) }
            }
        )
    }

    override fun createSession(surface: Surface, assets: AssetManager): RendererSession {
        check(NativeVulkanRenderer.load()) {
            "Could not load Vulkan native renderer library"
        }

        val handle = NativeVulkanRenderer.nativeStart(surface, assets)
        check(handle != 0L) {
            "Native Vulkan renderer returned an invalid handle"
        }

        return VulkanRendererSession(handle)
    }
}

private class VulkanRendererSession(initialHandle: Long) : RendererSession {
    override val backendType: RendererType = RendererType.VULKAN

    private var rendererHandle: Long = initialHandle

    @Synchronized
    override fun resize(width: Int, height: Int) {
        val handle = rendererHandle
        if (handle == 0L) return

        NativeVulkanRenderer.nativeResize(handle, width, height)
    }

    @Synchronized
    override fun setPaused(paused: Boolean) {
        val handle = rendererHandle
        if (handle == 0L) return

        NativeVulkanRenderer.nativeSetPaused(handle, paused)
    }

    @Synchronized
    override fun close() {
        val handle = rendererHandle
        if (handle == 0L) return

        NativeVulkanRenderer.nativeStop(handle)
        rendererHandle = 0L
    }
}