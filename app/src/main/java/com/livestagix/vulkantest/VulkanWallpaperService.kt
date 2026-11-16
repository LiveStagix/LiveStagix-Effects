package com.livestagix.vulkantest

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.concurrent.atomic.AtomicInteger

class VulkanWallpaperService : WallpaperService() {
    private var nativeLibraryLoaded = false

    override fun onCreate() {
        super.onCreate()
        EventRecorder.initialize(this)
        nativeLibraryLoaded = NativeVulkanRenderer.load()
        EventRecorder.record("INFO", "WALLPAPER", "Wallpaper service created")
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
        private var rendererHandle = 0L
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

            if (!nativeLibraryLoaded) {
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Renderer unavailable for engine=${engineId}: native library is not loaded"
                )
                return
            }

            if (!holder.surface.isValid) {
                EventRecorder.record(
                    "ERROR",
                    "SURFACE",
                    "Invalid wallpaper surface for engine=${engineId}"
                )
                return
            }

            var startedHandle = 0L
            runCatching {
                startedHandle = NativeVulkanRenderer.nativeStart(holder.surface, assets)
                check(startedHandle != 0L) {
                    "Native Vulkan renderer returned an invalid handle"
                }
                rendererHandle = startedHandle
                EventRecorder.record(
                    "INFO",
                    "VULKAN",
                    "Renderer attached to engine=${engineId}, handle=${startedHandle}"
                )
                NativeVulkanRenderer.nativeSetPaused(startedHandle, !isVisible)
            }.onFailure {
                if (startedHandle != 0L) {
                    runCatching { NativeVulkanRenderer.nativeStop(startedHandle) }
                }
                rendererHandle = 0L
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not start Vulkan renderer for engine=${engineId}: ${it.message}"
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

            val handle = rendererHandle
            if (handle != 0L) {
                runCatching {
                    NativeVulkanRenderer.nativeResize(handle, width, height)
                }.onFailure {
                    EventRecorder.record(
                        "ERROR",
                        "SURFACE",
                        "Could not resize renderer for engine=${engineId}, handle=${handle}: ${it.message}"
                    )
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            EventRecorder.record(
                "INFO",
                "WALLPAPER",
                "Visibility changed: visible=${visible}, engine=${engineId}, " +
                    "isPreview=${isPreview()}, rendererHandle=${rendererHandle}"
            )

            val handle = rendererHandle
            if (handle != 0L) {
                runCatching {
                    NativeVulkanRenderer.nativeSetPaused(handle, !visible)
                }.onFailure {
                    EventRecorder.record(
                        "ERROR",
                        "WALLPAPER",
                        "Could not update renderer visibility for engine=${engineId}, " +
                            "handle=${handle}: ${it.message}"
                    )
                }
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
            val handle = rendererHandle
            if (handle == 0L) return
            rendererHandle = 0L

            runCatching {
                NativeVulkanRenderer.nativeStop(handle)
            }.onFailure {
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not stop renderer for engine=${engineId}, handle=${handle}: ${it.message}"
                )
            }
            EventRecorder.record(
                "INFO",
                "VULKAN",
                "Renderer detached from engine=${engineId}, handle=${handle}"
            )
        }
    }

    companion object {
        private val nextEngineId = AtomicInteger(0)
    }
}
