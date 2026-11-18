package com.livestagix.renderer.backends.vulkan

import android.content.Context
import com.livestagix.renderer.NativeVulkanRenderer
import com.livestagix.renderer.api.RendererBackend
import com.livestagix.renderer.api.RendererCapabilities
import com.livestagix.renderer.api.RendererCreateRequest
import com.livestagix.renderer.api.RendererException
import com.livestagix.renderer.api.RendererFailure
import com.livestagix.renderer.api.RendererFailureCategory
import com.livestagix.renderer.api.RendererFailureListener
import com.livestagix.renderer.api.RendererFeature
import com.livestagix.renderer.api.RendererLogListener
import com.livestagix.renderer.api.RendererSession
import com.livestagix.renderer.api.RendererSupportResult
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

    /**
     * Checks Vulkan loader/device capability without claiming that a particular Android
     * surface can be presented to. Surface presentation is confirmed during createSession.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun inspectSupport(context: Context): RendererSupportResult {
        if (!NativeVulkanRenderer.load()) return RendererSupportResult.NotChecked

        val result = runCatching {
            NativeVulkanRenderer.nativeInspectSupport()
        }.getOrNull() ?: return RendererSupportResult.NotChecked

        if (result.size < 4) return RendererSupportResult.NotChecked

        return when (result[0]) {
            "SUPPORTED" -> {
                val capabilityFields = result.drop(4).toTypedArray()
                val capabilities = runCatching {
                    parseCapabilities(capabilityFields)
                }.getOrNull()
                if (capabilities == null) {
                    RendererSupportResult.NotChecked
                } else {
                    RendererSupportResult.Supported(capabilities)
                }
            }

            "UNSUPPORTED" -> {
                val category = runCatching {
                    RendererFailureCategory.valueOf(result[1])
                }.getOrDefault(RendererFailureCategory.UNKNOWN)
                if (!category.isPermanentForDevice) {
                    RendererSupportResult.NotChecked
                } else {
                    RendererSupportResult.Unsupported(
                        RendererFailure(
                            category = category,
                            diagnosticCode = result[2].takeIf { it.isNotBlank() },
                            message = result[3]
                        )
                    )
                }
            }

            else -> RendererSupportResult.NotChecked
        }
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

        val capabilities = try {
            parseCapabilities(
                NativeVulkanRenderer.nativeGetCapabilities(handle)
                    ?: throw IllegalStateException("Native Vulkan capabilities are unavailable")
            )
        } catch (error: RuntimeException) {
            NativeVulkanRenderer.nativeStop(handle)
            NativeVulkanRenderer.unregisterFailureListener(handle)
            throw initializationFailure(error)
        }

        val session = VulkanRendererSession(handle, capabilities)
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

    private fun initializationFailure(error: Throwable): RendererException {
        val parts = error.message
            ?.takeIf { it.startsWith(NATIVE_FAILURE_PREFIX) }
            ?.split('|', limit = 4)

        val category = parts
            ?.getOrNull(1)
            ?.let { name -> runCatching { RendererFailureCategory.valueOf(name) }.getOrNull() }
            ?: RendererFailureCategory.INITIALIZATION_FAILED
        val diagnosticCode = parts?.getOrNull(2)
        val message = parts?.getOrNull(3)
            ?: error.message
            ?: "Vulkan renderer initialization failed"

        return RendererException(
            RendererFailure(
                category = category,
                message = message,
                diagnosticCode = diagnosticCode
            ),
            error
        )
    }

    private companion object {
        const val NATIVE_FAILURE_PREFIX = "LIVESTAGIX_RENDERER_FAILURE|"
    }
}

private class VulkanRendererSession(
    initialHandle: Long,
    override val capabilities: RendererCapabilities
) : RendererSession {
    override val backendType: RendererType = RendererType.VULKAN

    private var rendererHandle: Long = initialHandle
    private var failureListener: RendererFailureListener? = null
    private var pendingFailure: RendererFailure? = null

    init {
        NativeVulkanRenderer.registerFailureListener(initialHandle) { failure ->
            val listener = synchronized(this) {
                if (rendererHandle != initialHandle) {
                    null
                } else {
                    failureListener ?: run {
                        pendingFailure = failure
                        null
                    }
                }
            }
            listener?.let { callback -> runCatching { callback.onFailure(failure) } }
        }
    }

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

    override fun setFailureListener(listener: RendererFailureListener?) {
        val pending = synchronized(this) {
            failureListener = listener
            if (listener == null) null else pendingFailure.also { pendingFailure = null }
        }
        if (listener != null) pending?.let { failure ->
            runCatching { listener.onFailure(failure) }
        }
    }

    override fun close() {
        val handle = synchronized(this) {
            val current = rendererHandle
            rendererHandle = 0L
            failureListener = null
            pendingFailure = null
            current
        }
        if (handle == 0L) return

        // Stop outside the session monitor: nativeStop joins the render thread,
        // which may still be delivering its final runtime-failure callback.
        NativeVulkanRenderer.nativeStop(handle)
        NativeVulkanRenderer.unregisterFailureListener(handle)
        synchronized(this) {
            pendingFailure = null
        }
    }
}

private fun parseCapabilities(fields: Array<String>): RendererCapabilities {
    require(fields.size >= 6) { "Native Vulkan capability response is incomplete" }

    val features = buildSet {
        if (fields[4].toBoolean()) add(RendererFeature.GPU_TIMESTAMP_QUERIES)
        if (fields[5].toBoolean()) add(RendererFeature.COMPUTE_SHADERS)
    }

    return RendererCapabilities(
        backendType = RendererType.VULKAN,
        deviceName = fields[0].takeIf { it.isNotBlank() },
        apiVersion = fields[1].takeIf { it.isNotBlank() },
        driverVersion = fields[2].takeIf { it.isNotBlank() },
        maxTextureSize = fields[3].toIntOrNull(),
        features = features,
        extensions = fields.drop(6).filter { it.isNotBlank() }.toSet()
    )
}
