package com.livestagix.renderer.backends.gles

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Independent OpenGL ES 2.0 backend.
 *
 * This is the compatibility baseline: its own EGL ES2 context, shader program,
 * fullscreen quad and surface presentation loop. It does not request an ES3 context
 * and does not claim photo, video, motion or post-processing support yet.
 */
class Gles2RendererBackend : RendererBackend {
    override val type: RendererType = RendererType.OPENGL_ES_2

    @Volatile
    private var logListener: RendererLogListener? = null

    override fun setLogListener(listener: RendererLogListener?) {
        logListener = listener
    }

    @Suppress("UNUSED_PARAMETER")
    override fun inspectSupport(context: Context): RendererSupportResult {
        val support = probeEs2Support()
        when (support) {
            is RendererSupportResult.Supported -> log(
                "INFO",
                "GLES 2.0 supported: device=" + support.capabilities.deviceName +
                    ", version=" + support.capabilities.apiVersion
            )
            is RendererSupportResult.Unsupported ->
                log("WARN", "GLES 2.0 unavailable: " + support.failure.message)
            RendererSupportResult.NotChecked ->
                log("WARN", "GLES 2.0 support could not be reliably inspected")
        }
        return support
    }

    override fun createSession(request: RendererCreateRequest): RendererSession {
        if (!request.surface.isValid) {
            throw RendererException(
                RendererFailure(
                    RendererFailureCategory.SURFACE_UNAVAILABLE,
                    "The Android surface is not valid",
                    "GLES2_SURFACE_INVALID"
                )
            )
        }

        val renderThread = Gles2RenderThread(
            surface = request.surface,
            initiallyPaused = request.config.initiallyPaused,
            log = ::log
        )
        renderThread.startAndAwaitReady()
        val capabilities = renderThread.capabilities
            ?: run {
                renderThread.shutdown()
                throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.INITIALIZATION_FAILED,
                        "GLES 2.0 initialized without reporting capabilities",
                        "GLES2_CAPABILITIES_MISSING"
                    )
                )
            }

        log(
            "INFO",
            "GLES 2.0 session created: " + capabilities.deviceName +
                " | " + capabilities.apiVersion
        )
        return Gles2RendererSession(renderThread, capabilities)
    }

    private fun log(level: String, message: String) {
        runCatching { logListener?.onLog(level, message) }
    }

    private fun probeEs2Support(): RendererSupportResult {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        var initialized = false

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return RendererSupportResult.NotChecked

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return RendererSupportResult.NotChecked
            }
            initialized = true

            val config = chooseEs2Config(display)
                ?: return unsupportedEs2("No EGL configuration supports an ES2 context")

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                ES2_CONTEXT_ATTRIBUTES,
                0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return RendererSupportResult.NotChecked

            pbuffer = EGL14.eglCreatePbufferSurface(
                display,
                config,
                PBUFFER_ATTRIBUTES,
                0
            )
            if (pbuffer == EGL14.EGL_NO_SURFACE) return RendererSupportResult.NotChecked
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                return RendererSupportResult.NotChecked
            }

            val capabilities = readCapabilities()
                ?: return RendererSupportResult.NotChecked
            return RendererSupportResult.Supported(capabilities)
        } catch (_: Exception) {
            return RendererSupportResult.NotChecked
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (pbuffer != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, pbuffer)
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                }
                if (initialized) EGL14.eglTerminate(display)
            }
            EGL14.eglReleaseThread()
        }
    }

    private class Gles2RendererSession(
        private val renderThread: Gles2RenderThread,
        override val capabilities: RendererCapabilities
    ) : RendererSession {
        override val backendType: RendererType = RendererType.OPENGL_ES_2

        override fun resize(width: Int, height: Int) {
            renderThread.resize(width, height)
        }

        override fun setPaused(paused: Boolean) {
            renderThread.setPaused(paused)
        }

        override fun setFailureListener(listener: RendererFailureListener?) {
            renderThread.setFailureListener(listener)
        }

        override fun close() {
            renderThread.shutdown()
        }
    }

    private class Gles2RenderThread(
        private val surface: Surface,
        initiallyPaused: Boolean,
        private val log: (String, String) -> Unit
    ) : Thread("LiveStagix-GLES2") {
        private val startupLatch = CountDownLatch(1)
        private val failureLock = Any()
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var stopRequested = false

        @Volatile
        private var paused = initiallyPaused

        @Volatile
        private var requestedWidth = 0

        @Volatile
        private var requestedHeight = 0

        @Volatile
        var capabilities: RendererCapabilities? = null
            private set

        @Volatile
        private var startupFailure: RendererException? = null

        @Volatile
        private var startupComplete = false

        private var failureListener: RendererFailureListener? = null
        private var pendingFailure: RendererFailure? = null

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var eglInitialized = false
        private var program = 0
        private var positionAttribute = -1
        private var resolutionUniform = -1
        private var vertexBuffer = 0

        fun startAndAwaitReady() {
            start()
            val ready = try {
                startupLatch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                shutdown()
                throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.TEMPORARY_FAILURE,
                        "Interrupted while waiting for GLES 2.0 initialization",
                        "GLES2_STARTUP_INTERRUPTED"
                    ),
                    error
                )
            }

            if (!ready) {
                shutdown()
                throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.INITIALIZATION_FAILED,
                        "GLES 2.0 initialization timed out",
                        "GLES2_STARTUP_TIMEOUT"
                    )
                )
            }

            startupFailure?.let {
                shutdown()
                throw it
            }
        }

        fun resize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            requestedWidth = width
            requestedHeight = height
            LockSupport.unpark(this)
        }

        fun setPaused(value: Boolean) {
            paused = value
            LockSupport.unpark(this)
        }

        fun setFailureListener(listener: RendererFailureListener?) {
            val pending = synchronized(failureLock) {
                failureListener = listener
                if (listener == null) null else pendingFailure.also { pendingFailure = null }
            }
            if (listener != null) pending?.let { dispatchFailure(listener, it) }
        }

        fun shutdown() {
            stopRequested = true
            LockSupport.unpark(this)
            if (isAlive && Thread.currentThread() !== this) {
                try {
                    join(SHUTDOWN_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log("WARN", "Interrupted while stopping GLES 2.0 renderer thread")
                }
                if (isAlive) {
                    log("ERROR", "GLES 2.0 renderer thread did not stop within the shutdown limit")
                }
            }
        }

        override fun run() {
            try {
                initialize()
                startupComplete = true
                startupLatch.countDown()
                log(
                    "INFO",
                    "READY: " + (capabilities?.deviceName ?: "unknown GPU") +
                        " | " + (capabilities?.apiVersion ?: "OpenGL ES 2")
                )
                renderLoop()
            } catch (error: Throwable) {
                val failure = toRendererFailure(error, startup = !startupComplete)
                if (!startupComplete) {
                    startupFailure = failure
                } else {
                    reportRuntimeFailure(failure.failure)
                }
                log(
                    "ERROR",
                    "GLES 2.0 renderer stopped: " + failure.failure.category +
                        " | " + (failure.failure.diagnosticCode ?: "no-code") +
                        " | " + failure.failure.message
                )
            } finally {
                cleanup()
                startupLatch.countDown()
                log("INFO", "GLES 2.0 renderer resources released")
            }
        }

        private fun initialize() {
            if (!surface.isValid) {
                throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.SURFACE_UNAVAILABLE,
                        "Android surface became invalid before EGL initialization",
                        "GLES2_SURFACE_INVALID_DURING_STARTUP"
                    )
                )
            }

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                throw eglException("eglGetDisplay failed", startup = true)
            }

            val eglVersion = IntArray(2)
            if (!EGL14.eglInitialize(display, eglVersion, 0, eglVersion, 1)) {
                throw eglException("eglInitialize failed", startup = true)
            }
            eglInitialized = true
            log("INFO", "EGL initialized: " + eglVersion[0] + "." + eglVersion[1])

            val config = chooseEs2Config(display)
                ?: throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.MISSING_REQUIRED_FEATURE,
                        "No EGL configuration supports an ES2 window surface",
                        "GLES2_WINDOW_CONFIG_UNAVAILABLE"
                    )
                )

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                ES2_CONTEXT_ATTRIBUTES,
                0
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                throw eglException("eglCreateContext(ES2) failed", startup = true)
            }

            windowSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                WINDOW_SURFACE_ATTRIBUTES,
                0
            )
            if (windowSurface == EGL14.EGL_NO_SURFACE) {
                throw eglException("eglCreateWindowSurface failed", startup = true)
            }

            if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
                throw eglException("eglMakeCurrent failed", startup = true)
            }

            capabilities = readCapabilities()
                ?: throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.INITIALIZATION_FAILED,
                        "The active context did not report OpenGL ES capabilities",
                        "GLES2_CAPABILITIES_UNAVAILABLE"
                    )
                )

            val size = querySurfaceSize()
            requestedWidth = size.first
            requestedHeight = size.second
            createGraphicsResources()
            log(
                "INFO",
                "GLES 2.0 context ready: " + capabilities?.deviceName +
                    " | " + capabilities?.apiVersion +
                    " | surface=" + requestedWidth + "x" + requestedHeight
            )
        }

        private fun createGraphicsResources() {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionAttribute = GLES20.glGetAttribLocation(program, "aPosition")
            resolutionUniform = GLES20.glGetUniformLocation(program, "uResolution")
            if (positionAttribute < 0 || resolutionUniform < 0) {
                throw IllegalStateException("GLES 2.0 shader is missing a required input")
            }

            val buffers = IntArray(1)
            GLES20.glGenBuffers(1, buffers, 0)
            vertexBuffer = buffers[0]
            if (vertexBuffer == 0) {
                throw IllegalStateException("Could not allocate GLES 2.0 vertex buffer")
            }

            val vertices = floatBuffer(QUAD_VERTICES)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                QUAD_VERTICES.size * Float.SIZE_BYTES,
                vertices,
                GLES20.GL_STATIC_DRAW
            )
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            checkGlError("GLES 2.0 resource setup")
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = try {
                compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            } catch (error: Throwable) {
                GLES20.glDeleteShader(vertexShader)
                throw error
            }

            val linkedProgram = GLES20.glCreateProgram()
            if (linkedProgram == 0) {
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                throw IllegalStateException("glCreateProgram returned 0")
            }

            GLES20.glAttachShader(linkedProgram, vertexShader)
            GLES20.glAttachShader(linkedProgram, fragmentShader)
            GLES20.glLinkProgram(linkedProgram)

            val status = IntArray(1)
            GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            if (status[0] == 0) {
                val message = GLES20.glGetProgramInfoLog(linkedProgram)
                GLES20.glDeleteProgram(linkedProgram)
                throw IllegalStateException("GLES 2.0 shader link failed: " + message)
            }
            return linkedProgram
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) throw IllegalStateException("glCreateShader returned 0")

            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("GLES 2.0 shader compilation failed: " + message)
            }
            return shader
        }

        private fun renderLoop() {
            while (!stopRequested) {
                if (paused) {
                    LockSupport.parkNanos(PAUSED_POLL_INTERVAL_NS)
                    continue
                }

                val frameStart = System.nanoTime()
                renderFrame()
                val remaining = FRAME_INTERVAL_NS - (System.nanoTime() - frameStart)
                if (remaining > 0L) LockSupport.parkNanos(remaining)
            }
        }

        private fun renderFrame() {
            val width = requestedWidth
            val height = requestedHeight
            if (width <= 0 || height <= 0) return

            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glUniform2f(resolutionUniform, width.toFloat(), height.toFloat())
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionAttribute)
            GLES20.glVertexAttribPointer(
                positionAttribute,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                0
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionAttribute)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glUseProgram(0)
            checkGlError("GLES 2.0 frame rendering")

            if (!EGL14.eglSwapBuffers(display, windowSurface)) {
                throw eglException("eglSwapBuffers failed", startup = false)
            }
        }

        private fun querySurfaceSize(): Pair<Int, Int> {
            val values = IntArray(1)
            val width = if (EGL14.eglQuerySurface(
                    display, windowSurface, EGL14.EGL_WIDTH, values, 0
                )
            ) values[0] else 0
            val height = if (EGL14.eglQuerySurface(
                    display, windowSurface, EGL14.EGL_HEIGHT, values, 0
                )
            ) values[0] else 0
            return width to height
        }

        private fun checkGlError(operation: String) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw IllegalStateException(
                    operation + " failed with GL error 0x" + error.toString(16)
                )
            }
        }

        private fun eglException(message: String, startup: Boolean): RendererException {
            val error = EGL14.eglGetError()
            val category = when (error) {
                EGL14.EGL_BAD_SURFACE, EGL14.EGL_BAD_NATIVE_WINDOW ->
                    RendererFailureCategory.SURFACE_UNAVAILABLE
                EGL14.EGL_CONTEXT_LOST -> RendererFailureCategory.DEVICE_LOST
                EGL14.EGL_BAD_ALLOC -> RendererFailureCategory.RESOURCE_UNAVAILABLE
                else -> if (startup) {
                    RendererFailureCategory.INITIALIZATION_FAILED
                } else {
                    RendererFailureCategory.TEMPORARY_FAILURE
                }
            }
            return RendererException(
                RendererFailure(
                    category = category,
                    message = message + " (EGL error 0x" + error.toString(16) + ")",
                    diagnosticCode = "GLES2_EGL_0x" + error.toString(16)
                )
            )
        }

        private fun toRendererFailure(error: Throwable, startup: Boolean): RendererException {
            if (error is RendererException) return error
            return RendererException(
                RendererFailure(
                    category = if (startup) {
                        RendererFailureCategory.INITIALIZATION_FAILED
                    } else {
                        RendererFailureCategory.TEMPORARY_FAILURE
                    },
                    message = error.message ?: "Unknown GLES 2.0 renderer failure",
                    diagnosticCode = if (startup) {
                        "GLES2_INITIALIZATION_FAILED"
                    } else {
                        "GLES2_RUNTIME_FAILURE"
                    }
                ),
                error
            )
        }

        private fun reportRuntimeFailure(failure: RendererFailure) {
            val listener = synchronized(failureLock) {
                failureListener.also { if (it == null) pendingFailure = failure }
            }
            listener?.let { dispatchFailure(it, failure) }
        }

        private fun dispatchFailure(listener: RendererFailureListener, failure: RendererFailure) {
            mainHandler.post {
                runCatching { listener.onFailure(failure) }.onFailure {
                    log("ERROR", "GLES 2.0 failure listener threw: " + it.message)
                }
            }
        }

        private fun cleanup() {
            val canReleaseGlResources =
                display != EGL14.EGL_NO_DISPLAY &&
                    windowSurface != EGL14.EGL_NO_SURFACE &&
                    context != EGL14.EGL_NO_CONTEXT &&
                    EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)

            // A partially initialized context may never have become current. In that case,
            // destroying the EGL context releases any resources created before the failure.
            if (canReleaseGlResources) {
                if (program != 0) {
                    GLES20.glDeleteProgram(program)
                    program = 0
                }
                if (vertexBuffer != 0) {
                    GLES20.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
                    vertexBuffer = 0
                }
            }

            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, windowSurface)
                    windowSurface = EGL14.EGL_NO_SURFACE
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                    context = EGL14.EGL_NO_CONTEXT
                }
                if (eglInitialized) {
                    EGL14.eglTerminate(display)
                    eglInitialized = false
                }
                EGL14.eglReleaseThread()
                display = EGL14.EGL_NO_DISPLAY
            }
        }
    }

    private companion object {
        const val EGL_OPENGL_ES2_BIT = 0x0004
        const val STARTUP_TIMEOUT_SECONDS = 10L
        const val SHUTDOWN_TIMEOUT_MS = 3_000L
        const val FRAME_INTERVAL_NS = 16_666_667L
        const val PAUSED_POLL_INTERVAL_NS = 100_000_000L

        val ES2_CONFIG_ATTRIBUTES = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val ES2_CONTEXT_ATTRIBUTES = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val PBUFFER_ATTRIBUTES = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val WINDOW_SURFACE_ATTRIBUTES = intArrayOf(EGL14.EGL_NONE)
        val VERSION_REGEX = Regex("""OpenGL ES\s+(\d+)\.(\d+)""")
        val WHITESPACE_REGEX = Regex("""\s+""")
        val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val VERTEX_SHADER = """
            attribute vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()
        val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec2 uResolution;
            void main() {
                vec2 uv = gl_FragCoord.xy / max(uResolution, vec2(1.0));
                gl_FragColor = vec4(
                    0.025 + uv.x * 0.04,
                    0.035 + uv.y * 0.06,
                    0.09 + uv.x * 0.12,
                    1.0
                );
            }
        """.trimIndent()

        private fun chooseEs2Config(display: EGLDisplay): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val found = EGL14.eglChooseConfig(
                display,
                ES2_CONFIG_ATTRIBUTES,
                0,
                configs,
                0,
                configs.size,
                count,
                0
            )
            return if (found && count[0] > 0) configs[0] else null
        }

        private fun readCapabilities(): RendererCapabilities? {
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: return null
            val parsed = VERSION_REGEX.find(version) ?: return null
            val major = parsed.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
            val minor = parsed.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (major < 2) return null

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
                .orEmpty()
                .split(WHITESPACE_REGEX)
                .filter(String::isNotBlank)
                .toSet()
            val maxTextureSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            val features = buildSet {
                if ("GL_OES_EGL_image_external" in extensions) {
                    add(RendererFeature.EXTERNAL_IMAGE_IMPORT)
                }
            }
            return RendererCapabilities(
                backendType = RendererType.OPENGL_ES_2,
                deviceName = renderer?.takeIf(String::isNotBlank)
                    ?: vendor?.takeIf(String::isNotBlank),
                apiVersion = version,
                extensions = extensions,
                features = features,
                maxTextureSize = maxTextureSize[0].takeIf { it > 0 }
            )
        }

        private fun unsupportedEs2(message: String): RendererSupportResult.Unsupported =
            RendererSupportResult.Unsupported(
                RendererFailure(
                    RendererFailureCategory.MISSING_REQUIRED_FEATURE,
                    message,
                    "GLES2_CONTEXT_UNAVAILABLE"
                )
            )

        private fun floatBuffer(values: FloatArray) =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
