package com.livestagix.vulkantest

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.livestagix.renderer.api.RendererBackend
import com.livestagix.renderer.api.RendererCreateConfig
import com.livestagix.renderer.api.RendererCreateRequest
import com.livestagix.renderer.api.RendererFailureCategory
import com.livestagix.renderer.api.RendererFailureListener
import com.livestagix.renderer.api.RendererException
import com.livestagix.renderer.api.RendererSession
import com.livestagix.renderer.backends.gles.Gles3RendererBackend
import java.util.concurrent.atomic.AtomicInteger

/** Separate Lab service for testing the independent OpenGL ES 3.0 backend. */
class Gles3WallpaperService : WallpaperService() {
    private val rendererBackend: RendererBackend = Gles3RendererBackend()

    override fun onCreate() {
        super.onCreate()
        EventRecorder.initialize(this)
        rendererBackend.setLogListener { level, message ->
            EventRecorder.record(level, "GLES3", message)
        }
        EventRecorder.record("INFO", "WALLPAPER", "GLES 3.0 wallpaper service created")
    }

    override fun onDestroy() {
        rendererBackend.setLogListener(null)
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine {
        val engine = Gles3Engine()
        EventRecorder.record(
            "INFO",
            "WALLPAPER",
            "GLES 3.0 wallpaper engine allocated: engine=${engine.engineId}"
        )
        return engine
    }

    inner class Gles3Engine : Engine() {
        val engineId: Int = nextEngineId.incrementAndGet()
        private var rendererSession: RendererSession? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            EventRecorder.record(
                "INFO",
                "WALLPAPER",
                "GLES 3.0 wallpaper engine created: engine=${engineId}, " +
                    "isPreview=${isPreview()}, surfaceValid=${surfaceHolder.surface.isValid}"
            )
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            EventRecorder.record(
                "INFO",
                "SURFACE",
                "GLES 3.0 surface created: engine=${engineId}, " +
                    "isPreview=${isPreview()}, valid=${holder.surface.isValid}"
            )
            stopRenderer()

            var startedSession: RendererSession? = null
            runCatching {
                val session = rendererBackend.createSession(
                    RendererCreateRequest(
                        surface = holder.surface,
                        assets = assets,
                        config = RendererCreateConfig(initiallyPaused = !isVisible)
                    )
                )
                startedSession = session
                session.setFailureListener(RendererFailureListener { failure ->
                    EventRecorder.record(
                        "ERROR",
                        "GLES3",
                        "Runtime failure: engine=${engineId}, category=${failure.category}, " +
                            "code=${failure.diagnosticCode ?: "none"}, message=${failure.message}"
                    )
                })
                rendererSession = session
                EventRecorder.record(
                    "INFO",
                    "GLES3",
                    "Renderer attached to engine=${engineId}, backend=${session.backendType}"
                )
            }.onFailure { error ->
                startedSession?.let { session -> runCatching { session.close() } }
                rendererSession = null

                val failure = (error as? RendererException)?.failure
                val category = failure?.category ?: RendererFailureCategory.UNKNOWN
                val message = failure?.message ?: error.message ?: "Unknown GLES 3.0 renderer error"
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not start GLES 3.0 renderer for engine=${engineId}; " +
                        "category=${category}: ${message}"
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
                "GLES 3.0 surface changed to ${width}x${height}; " +
                    "engine=${engineId}, valid=${holder.surface.isValid}"
            )

            val session = rendererSession ?: return
            runCatching {
                session.resize(width, height)
            }.onFailure { error ->
                EventRecorder.record(
                    "ERROR",
                    "SURFACE",
                    "Could not resize GLES 3.0 renderer for engine=${engineId}: ${error.message}"
                )
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            EventRecorder.record(
                "INFO",
                "WALLPAPER",
                "GLES 3.0 visibility changed: visible=${visible}, engine=${engineId}, " +
                    "isPreview=${isPreview()}"
            )

            val session = rendererSession ?: return
            runCatching {
                session.setPaused(!visible)
            }.onFailure { error ->
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not update GLES 3.0 visibility for engine=${engineId}: ${error.message}"
                )
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            EventRecorder.record(
                "INFO",
                "SURFACE",
                "GLES 3.0 surface destroyed: engine=${engineId}, isPreview=${isPreview()}"
            )
            super.onSurfaceDestroyed(holder)
            stopRenderer()
        }

        override fun onDestroy() {
            stopRenderer()
            EventRecorder.record(
                "INFO",
                "WALLPAPER",
                "GLES 3.0 wallpaper engine destroyed: engine=${engineId}, isPreview=${isPreview()}"
            )
            super.onDestroy()
        }

        private fun stopRenderer() {
            val session = rendererSession ?: return
            rendererSession = null

            runCatching {
                session.close()
            }.onFailure { error ->
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not stop GLES 3.0 renderer for engine=${engineId}: ${error.message}"
                )
            }
            EventRecorder.record(
                "INFO",
                "GLES3",
                "Renderer detached from engine=${engineId}, backend=${session.backendType}"
            )
        }
    }

    companion object {
        private val nextEngineId = AtomicInteger(0)
    }
}
