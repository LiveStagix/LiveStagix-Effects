package com.livestagix.renderer.backends.vulkan

import com.livestagix.renderer.NativeVulkanRenderer
import com.livestagix.renderer.api.RendererBackend
import com.livestagix.renderer.api.RendererCapabilities
import com.livestagix.renderer.api.RendererCreateRequest
import com.livestagix.renderer.api.RendererException
import com.livestagix.renderer.api.RendererFailure
import com.livestagix.renderer.api.RendererFailureCategory
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

    override fun createSession(request: RendererCreateRequest): RendererSession {
        if (!request.surface.isValid) {
            throw RendererException(
                RendererFailure(
                    RendererFailureCategory.SURFACE_UNAVAILABLE,
                    "The Android surface is not valid"
                )
            )
        }

        checkNativeLibrary()

        val handle = try {
            NativeVulkanRenderer.nativeStart(request.surface, request.assets)
        } catch (error: LinkageError) {
            throw RendererException(
                RendererFailure(
                    RendererFailureCategory.BACKEND_UNAVAILABLE,
                    "Vulkan native entry points are unavailable",
                    "VULKAN_JNI_UNAVAILABLE"
                ),
                error
            )
        } catch (error: RuntimeException) {
            throw initializationFailure(error)
        }

        if (handle == 0L) {
            throw RendererException(
                RendererFailure(
                    RendererFailureCategory.INITIALIZATION_FAILED,
                    "Native Vulkan renderer could not create a session"
                )
            )
        }

        val session = VulkanRendererSession(handle)
        if (request.config.initiallyPaused) {
            try {
                session.setPaused(true)
            } catch (error: RuntimeException) {
                runCatching { session.close() }
                throw initializationFailure(error)
            }
        }

        return session
    }

    private fun checkNativeLibrary() {
        if (!NativeVulkanRenderer.load()) {
            throw RendererException(
                RendererFailure(
                    RendererFailureCategory.BACKEND_UNAVAILABLE,
                    "Vulkan native renderer library could not be loaded"
                )
            )
        }
    }

    private fun initializationFailure(error: Throwable) = RendererException(
        RendererFailure(
            RendererFailureCategory.INITIALIZATION_FAILED,
            error.message ?: "Vulkan renderer initialization failed"
        ),
        error
    )
}

private class VulkanRendererSession(initialHandle: Long) : RendererSession {
    override val backendType: RendererType = RendererType.VULKAN

    // The native device details are not yet exposed as a structured JNI result.
    override val capabilities: RendererCapabilities? = null

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
