package com.livestagix.vulkantest

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.livestagix.renderer.api.RendererCreateConfig
import com.livestagix.renderer.api.RendererCreateRequest
import com.livestagix.renderer.api.RendererFailureCategory
import com.livestagix.renderer.api.RendererFailureListener
import com.livestagix.renderer.api.RendererException
import com.livestagix.renderer.api.RendererSession
import com.livestagix.renderer.backends.gles.Gles2RendererBackend
import java.util.concurrent.atomic.AtomicInteger

/** Separate Lab service for checking the independent OpenGL ES 2.0 compatibility backend. */
class Gles2WallpaperService : WallpaperService() {
    private val rendererBackend = Gles2RendererBackend()

    override fun onCreate() {
        super.onCreate()
        EventRecorder.initialize(this)
        rendererBackend.setLogListener { level, message ->
            EventRecorder.record(level, "GLES2", message)
        }
        EventRecorder.record("INFO", "WALLPAPER", "GLES 2.0 wallpaper service created")
    }

    override fun onDestroy() {
        rendererBackend.setLogListener(null)
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = Gles2Engine()

    inner class Gles2Engine : Engine() {
        private val engineId = nextEngineId.incrementAndGet()
        private var rendererSession: RendererSession? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            EventRecorder.record(
                "INFO",
                "WALLPAPER",
                "GLES 2.0 wallpaper engine created: engine=" + engineId +
                    ", preview=" + isPreview() +
                    ", surfaceValid=" + surfaceHolder.surface.isValid
            )
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
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
                        "GLES2",
                        "Runtime failure: engine=" + engineId +
                            ", category=" + failure.category +
                            ", code=" + (failure.diagnosticCode ?: "none") +
                            ", message=" + failure.message
                    )
                })
                rendererSession = session
                EventRecorder.record(
                    "INFO",
                    "GLES2",
                    "Renderer attached: engine=" + engineId +
                        ", backend=" + session.backendType
                )
            }.onFailure { error ->
                startedSession?.let { runCatching { it.close() } }
                rendererSession = null
                val failure = (error as? RendererException)?.failure
                EventRecorder.record(
                    "ERROR",
                    "WALLPAPER",
                    "Could not start GLES 2.0 renderer: category=" +
                        (failure?.category ?: RendererFailureCategory.UNKNOWN) +
                        ", message=" + (failure?.message ?: error.message)
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
            rendererSession?.let { session ->
                runCatching { session.resize(width, height) }.onFailure {
                    EventRecorder.record("ERROR", "SURFACE", "GLES2 resize failed: " + it.message)
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            rendererSession?.let { session ->
                runCatching { session.setPaused(!visible) }.onFailure {
                    EventRecorder.record("ERROR", "WALLPAPER", "GLES2 pause update failed: " + it.message)
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopRenderer()
        }

        override fun onDestroy() {
            stopRenderer()
            super.onDestroy()
        }

        private fun stopRenderer() {
            val session = rendererSession ?: return
            rendererSession = null
            runCatching { session.close() }.onFailure {
                EventRecorder.record("ERROR", "GLES2", "Renderer shutdown failed: " + it.message)
            }
            EventRecorder.record("INFO", "GLES2", "Renderer detached: backend=" + session.backendType)
        }
    }

    companion object {
        private val nextEngineId = AtomicInteger(0)
    }
}
