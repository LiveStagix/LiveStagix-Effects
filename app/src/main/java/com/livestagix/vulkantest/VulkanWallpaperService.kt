package com.livestagix.vulkantest

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class VulkanWallpaperService : WallpaperService() {
    private var nativeLibraryLoaded = false

    override fun onCreate() {
        super.onCreate()
        EventRecorder.initialize(this)
        nativeLibraryLoaded = NativeVulkanRenderer.load()
        EventRecorder.record("INFO", "WALLPAPER", "Wallpaper service created")
    }

    override fun onCreateEngine(): Engine = VulkanEngine()

    inner class VulkanEngine : Engine() {
        private var rendererStarted = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            EventRecorder.record("INFO", "WALLPAPER", "Wallpaper engine created")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            EventRecorder.record("INFO", "SURFACE", "Wallpaper surface created")

            if (!nativeLibraryLoaded) {
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Wallpaper renderer could not start because the native library is unavailable"
                )
                return
            }

            if (!holder.surface.isValid) {
                EventRecorder.record("ERROR", "SURFACE", "Wallpaper surface is not valid")
                return
            }

            runCatching {
                NativeVulkanRenderer.nativeStart(holder.surface, assets)
                rendererStarted = true
                NativeVulkanRenderer.nativeSetPaused(!isVisible)
            }.onFailure {
                rendererStarted = false
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not start Vulkan wallpaper renderer: ${{it.message}"
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
                "Wallpaper surface size changed to ${{width}x${{height}"
            )

            if (rendererStarted) {
                runCatching {
                    NativeVulkanRenderer.nativeResize(width, height)
                }.onFailure {
                    EventRecorder.record(
                        "ERROR",
                        "SURFACE",
                        "Could not resize Vulkan wallpaper surface: ${{it.message}"
                    )
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            EventRecorder.record("INFO", "WALLPAPER", "Visibility changed: $visible")

            if (rendererStarted) {
                runCatching {
                    NativeVulkanRenderer.nativeSetPaused(!visible)
                }.onFailure {
                    EventRecorder.record(
                        "ERROR",
                        "WALLPAPER",
                        "Could not update Vulkan renderer visibility: ${{it.message}"
                    )
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            EventRecorder.record("INFO", "SURFACE", "Wallpaper surface destroyed")
            stopRenderer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopRenderer()
            EventRecorder.record("INFO", "WALLPAPER", "Wallpaper engine destroyed")
            super.onDestroy()
        }

        private fun stopRenderer() {
            if (!rendererStarted) return

            runCatching {
                NativeVulkanRenderer.nativeStop()
            }.onFailure {
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not stop Vulkan wallpaper renderer: ${{it.message}"
                )
            }
            rendererStarted = false
        }
    }
}
