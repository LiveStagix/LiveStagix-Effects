package com.livestagix.vulkantest

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.livestagix.renderer.api.RendererBackend
import com.livestagix.renderer.api.RendererSession
import com.livestagix.renderer.backends.vulkan.VulkanRendererBackend
import java.util.concurrent.atomic.AtomicInteger

class VulkanWallpaperService : WallpaperService() {
    private val rendererBackend: RendererBackend = VulkanRendererBackend()

    override fun onCreate() {
        super.onCreate()
        EventRecorder.initialize(this)
        rendererBackend.setLogListener { level, message ->
            EventRecorder.record(level, "VULKAN", message)
        }
        EventRecorder.record("INFO", "WALLPAPER", "Wallpaper service created")
    }

    override fun onDestroy() {
        rendererBackend.setLogListener(null)
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine {
        val engine = VulkanEngine()
        EventRecorder.record(
            "INFO",
            "WALLPAPER",
            "Wallpaper engine instance allocated: engine=${engine.engineId}"
        )
        return engine
    }

    inner class VulkanEngine : Engine() {
        val engineId: Int = nextEngineId.incrementAndGet()
        private var rendererSession: RendererSession? = null
        private var previewEngine = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            previewEngine = isPreview()
            EventRecorder.record(
                "INFO",
                "WALLPAPER",
                "Wallpaper engine created: engine=${engineId}, isPreview=${previewEngine}, " +
                    "surfaceValid=${surfaceHolder.surface.isValid}"
            )
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            EventRecorder.record(
                "INFO",
                "SURFACE",
                "Wallpaper surface created: engine=${engineId}, isPreview=${isPreview()}, " +
                    "valid=${holder.surface.isValid}"
            )
            stopRenderer()

            if (!holder.surface.isValid) {
                EventRecorder.record(
                    "ERROR",
                    "SURFACE",
                    "Invalid wallpaper surface for engine=${engineId}"
                )
                return
            }

            var startedSession: RendererSession? = null
            runCatching {
                val session = rendererBackend.createSession(holder.surface, assets)
                startedSession = session
                rendererSession = session
                EventRecorder.record(
                    "INFO",
                    "VULKAN",
                    "Renderer attached to engine=${engineId}, backend=${session.backendType}"
                )
                session.setPaused(!isVisible)
            }.onFailure {
                startedSession?.let { session -> runCatching { session.close() } }
                rendererSession = null
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not start ${rendererBackend.type} renderer for engine=${engineId}: ${it.message}"
                )
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            EventRecorder.record(
                "INFO",
                "SURFACE",
                "Wallpaper surface changed to ${width}x${height}; " +
                    "engine=${engineId}, isPreview=${isPreview()}, valid=${holder.surface.isValid}"
            )

            val session = rendererSession ?: return
            runCatching {
                session.resize(width, height)
            }.onFailure {
                EventRecorder.record(
                    "ERROR",
                    "SURFACE",
                    "Could not resize ${session.backendType} renderer for engine=${engineId}: ${it.message}"
                )
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            EventRecorder.record(
                "INFO",
                "WALLPAPER",
                "Visibility changed: visible=${visible}, engine=${engineId}, " +
                    "isPreview=${isPreview()}, backend=${rendererSession?.backendType ?: "none"}"
            )

            val session = rendererSession ?: return
            runCatching {
                session.setPaused(!visible)
            }.onFailure {
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not update ${session.backendType} renderer visibility for engine=${engineId}: ${it.message}"
                )
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            EventRecorder.record(
                "INFO",
                "SURFACE",
                "Wallpaper surface destroyed: engine=${engineId}, isPreview=${isPreview()}"
            )
            super.onSurfaceDestroyed(holder)
            stopRenderer()
        }

        override fun onDestroy() {
            stopRenderer()
            EventRecorder.record(
                "INFO",
                "WALLPAPER",
                "Wallpaper engine destroyed: engine=${engineId}, isPreview=${isPreview()}"
            )
            super.onDestroy()
        }

        private fun stopRenderer() {
            val session = rendererSession ?: return
            rendererSession = null

            runCatching {
                session.close()
            }.onFailure {
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not stop ${session.backendType} renderer for engine=${engineId}: ${it.message}"
                )
            }
            EventRecorder.record(
                "INFO",
                "VULKAN",
                "Renderer detached from engine=${engineId}, backend=${session.backendType}"
            )
        }
    }

    companion object {
        private val nextEngineId = AtomicInteger(0)
    }
}