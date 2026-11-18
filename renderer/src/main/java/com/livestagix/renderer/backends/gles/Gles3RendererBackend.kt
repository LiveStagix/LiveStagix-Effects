package com.livestagix.renderer.backends.gles

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.livestagix.renderer.api.RendererBackend
import com.livestagix.renderer.api.RendererCapabilities
import com.livestagix.renderer.api.RendererContentFeature
import com.livestagix.renderer.api.RendererContentSession
import com.livestagix.renderer.api.RendererCreateRequest
import com.livestagix.renderer.api.RendererEvent
import com.livestagix.renderer.api.RendererEventListener
import com.livestagix.renderer.api.RendererMediaAccess
import com.livestagix.renderer.api.RendererMediaReleaseListener
import com.livestagix.renderer.api.RendererMotion
import com.livestagix.renderer.api.RendererScene
import com.livestagix.renderer.api.RendererSettings
import com.livestagix.renderer.pipeline.RendererMotionTransformSmoother
import com.livestagix.renderer.api.RendererException
import com.livestagix.renderer.api.RendererFailure
import com.livestagix.renderer.api.RendererFailureCategory
import com.livestagix.renderer.api.RendererFailureListener
import com.livestagix.renderer.api.RendererFeature
import com.livestagix.renderer.api.RendererLogListener
import com.livestagix.renderer.api.RendererSupportResult
import com.livestagix.renderer.api.RendererType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Independent OpenGL ES 3.0 backend.
 *
 * This first migration slice ports the LiveWP Modern path's EGL ES3 context,
 * GLSL ES 3.00 photo sampling, shared CPU photo preparation and motion transforms.
 * Video, effects, frame generation and Smart Upscale remain separate implementation stages.
 *
 * ES2 is never requested as a fallback from this backend.
 */
class Gles3RendererBackend : RendererBackend {
    override val type: RendererType = RendererType.OPENGL_ES_3

    @Volatile
    private var logListener: RendererLogListener? = null

    override fun setLogListener(listener: RendererLogListener?) {
        logListener = listener
    }

    @Suppress("UNUSED_PARAMETER")
    override fun inspectSupport(context: Context): RendererSupportResult {
        val probe = probeEs3Support()
        when (probe) {
            is RendererSupportResult.Supported -> {
                log(
                    "INFO",
                    "GLES 3.0 supported: device=${probe.capabilities.deviceName}, " +
                        "version=${probe.capabilities.apiVersion}"
                )
            }

            is RendererSupportResult.Unsupported -> {
                log("WARN", "GLES 3.0 unavailable: ${probe.failure.message}")
            }

            RendererSupportResult.NotChecked -> {
                log("WARN", "GLES 3.0 support could not be reliably inspected")
            }
        }
        return probe
    }

    override fun createSession(request: RendererCreateRequest): RendererSession {
        if (!request.surface.isValid) {
            throw RendererException(
                RendererFailure(
                    category = RendererFailureCategory.SURFACE_UNAVAILABLE,
                    message = "The Android surface is not valid",
                    diagnosticCode = "GLES3_SURFACE_INVALID"
                )
            )
        }

        val renderThread = Gles3RenderThread(
            surface = request.surface,
            mediaAccess = request.mediaAccess,
            initiallyPaused = request.config.initiallyPaused,
            log = ::log
        )

        renderThread.startAndAwaitReady()
        val capabilities = renderThread.capabilities
            ?: run {
                renderThread.shutdown()
                throw RendererException(
                    RendererFailure(
                        category = RendererFailureCategory.INITIALIZATION_FAILED,
                        message = "GLES 3.0 initialized without reporting capabilities",
                        diagnosticCode = "GLES3_CAPABILITIES_MISSING"
                    )
                )
            }

        log(
            "INFO",
            "GLES 3.0 session created: ${capabilities.deviceName} | " +
                "${capabilities.apiVersion}"
        )
        return Gles3RendererSession(renderThread, capabilities)
    }

    private fun log(level: String, message: String) {
        runCatching { logListener?.onLog(level, message) }
    }

    private fun probeEs3Support(): RendererSupportResult {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        var initialized = false

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return RendererSupportResult.NotChecked
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return RendererSupportResult.NotChecked
            }
            initialized = true

            val config = chooseEs3Config(display)
                ?: return unsupportedEs3("No EGL configuration supports an ES3 window context")

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                ES3_CONTEXT_ATTRIBUTES,
                0
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                val error = EGL14.eglGetError()
                return if (isConfirmedEs3ConfigurationFailure(error)) {
                    unsupportedEs3(
                        "The EGL driver does not provide an OpenGL ES 3.0 context",
                        error
                    )
                } else {
                    RendererSupportResult.NotChecked
                }
            }

            pbuffer = EGL14.eglCreatePbufferSurface(
                display,
                config,
                PBUFFER_ATTRIBUTES,
                0
            )
            if (pbuffer == EGL14.EGL_NO_SURFACE) {
                return RendererSupportResult.NotChecked
            }

            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                return RendererSupportResult.NotChecked
            }

            val capabilities = readCapabilities()
                ?: return unsupportedEs3("The created context did not report OpenGL ES 3.0")

            return RendererSupportResult.Supported(capabilities)
        } catch (_: Throwable) {
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

    private class Gles3RendererSession(
        private val renderThread: Gles3RenderThread,
        override val capabilities: RendererCapabilities
    ) : RendererContentSession {
        override val backendType: RendererType = RendererType.OPENGL_ES_3

        override fun resize(width: Int, height: Int) {
            renderThread.resize(width, height)
        }

        override fun setPaused(paused: Boolean) {
            renderThread.setPaused(paused)
        }

        override fun setFailureListener(listener: RendererFailureListener?) {
            renderThread.setFailureListener(listener)
        }

        override fun setScene(scene: RendererScene) {
            renderThread.setScene(scene.snapshot(), null)
        }

        override fun setScene(
            scene: RendererScene,
            previousMediaReleased: RendererMediaReleaseListener?
        ) {
            renderThread.setScene(scene.snapshot(), previousMediaReleased)
        }

        override fun updateMotion(motion: RendererMotion) {
            renderThread.updateMotion(motion)
        }

        override fun updateSettings(settings: RendererSettings) {
            renderThread.updateSettings(settings)
        }

        override fun setEventListener(listener: RendererEventListener?) {
            renderThread.setEventListener(listener)
        }

        override fun close() {
            renderThread.shutdown()
        }
    }

    private class Gles3RenderThread(
        private val surface: Surface,
        private val mediaAccess: RendererMediaAccess?,
        initiallyPaused: Boolean,
        private val log: (String, String) -> Unit
    ) : Thread("LiveStagix-GLES3") {
        private val startupLatch = CountDownLatch(1)
        private val failureLock = Any()
        private val commandLock = Any()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val renderCommands = ConcurrentLinkedQueue<() -> Unit>()
        private val motionSmoother = RendererMotionTransformSmoother()

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

        @Volatile
        private var eventListener: RendererEventListener? = null

        @Volatile
        private var latestMotion: RendererMotion = RendererMotion.NEUTRAL

        @Volatile
        private var targetFps: Int = 60

        private var contentController: GlesPhotoContentController? = null

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var eglInitialized = false

        private var shaderProgram = 0
        private var positionAttribute = -1
        private var texCoordAttribute = -1
        private var textureUniform = -1
        private var transformUniform = -1
        private var geometryBuffer = 0

        fun startAndAwaitReady() {
            start()
            val ready = try {
                startupLatch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                shutdown()
                throw RendererException(
                    RendererFailure(
                        category = RendererFailureCategory.TEMPORARY_FAILURE,
                        message = "Interrupted while waiting for GLES 3.0 initialization",
                        diagnosticCode = "GLES3_STARTUP_INTERRUPTED"
                    ),
                    error
                )
            }

            if (!ready) {
                shutdown()
                throw RendererException(
                    RendererFailure(
                        category = RendererFailureCategory.INITIALIZATION_FAILED,
                        message = "GLES 3.0 initialization timed out",
                        diagnosticCode = "GLES3_STARTUP_TIMEOUT"
                    )
                )
            }

            startupFailure?.let { failure ->
                shutdown()
                throw failure
            }
        }

        fun resize(width: Int, height: Int) {
            val safeWidth = width.coerceAtLeast(0)
            val safeHeight = height.coerceAtLeast(0)
            requestedWidth = safeWidth
            requestedHeight = safeHeight
            enqueueRenderCommand { contentController?.resize(safeWidth, safeHeight) }
        }

        fun setPaused(value: Boolean) {
            paused = value
            LockSupport.unpark(this)
        }

        fun setScene(scene: RendererScene, previousMediaReleased: RendererMediaReleaseListener?) {
            val snapshot = scene.snapshot()
            enqueueRenderCommand { contentController?.setScene(snapshot, previousMediaReleased) }
        }

        fun updateMotion(motion: RendererMotion) {
            latestMotion = motion
            LockSupport.unpark(this)
        }

        fun updateSettings(settings: RendererSettings) {
            enqueueRenderCommand { targetFps = settings.targetFps }
        }

        fun setEventListener(listener: RendererEventListener?) {
            eventListener = listener
        }

        private fun enqueueRenderCommand(command: () -> Unit): Boolean {
            val accepted = synchronized(commandLock) {
                if (stopRequested) {
                    false
                } else {
                    renderCommands.add(command)
                    true
                }
            }
            if (accepted) LockSupport.unpark(this)
            return accepted
        }

        private fun drainRenderCommands() {
            while (true) {
                val command = renderCommands.poll() ?: return
                runCatching { command() }.onFailure { error ->
                    log("ERROR", "GLES 3.0 render command failed: ${error.message}")
                    dispatchEvent(
                        RendererEvent.Failure(
                            RendererFailure(
                                RendererFailureCategory.UNKNOWN,
                                error.message ?: "GLES3 render command failed",
                                "GLES3_RENDER_COMMAND_FAILED"
                            )
                        )
                    )
                }
            }
        }

        private fun dispatchEvent(event: RendererEvent) {
            val listener = eventListener ?: return
            mainHandler.post {
                if (eventListener === listener) {
                    runCatching { listener.onRendererEvent(event) }
                        .onFailure { error ->
                            log("ERROR", "GLES 3.0 event listener threw: ${error.message}")
                        }
                }
            }
        }

        private fun dispatchMediaReleased(
            media: com.livestagix.renderer.api.RendererMedia,
            listener: RendererMediaReleaseListener?
        ) {
            mainHandler.post {
                val currentListener = eventListener
                if (currentListener != null) {
                    runCatching {
                        currentListener.onRendererEvent(RendererEvent.MediaReleased(media))
                    }
                }
                runCatching { listener?.onReleased(media) }
                    .onFailure { error ->
                        log("ERROR", "GLES 3.0 media-release listener threw: ${error.message}")
                    }
            }
        }

        fun setFailureListener(listener: RendererFailureListener?) {
            val pending = synchronized(failureLock) {
                failureListener = listener
                if (listener == null) null else pendingFailure.also { pendingFailure = null }
            }
            if (listener != null && pending != null) {
                dispatchFailure(listener, pending)
            }
        }

        fun shutdown() {
            synchronized(commandLock) { stopRequested = true }
            LockSupport.unpark(this)
            if (Thread.currentThread() !== this) {
                try {
                    join(SHUTDOWN_TIMEOUT_MILLIS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log("WARN", "Interrupted while stopping GLES 3.0 renderer thread")
                }
                if (isAlive) {
                    log("ERROR", "GLES 3.0 renderer thread did not stop within the shutdown limit")
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
                    "READY: ${capabilities?.deviceName ?: "unknown GPU"} | " +
                        "${capabilities?.apiVersion ?: "OpenGL ES 3"}"
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
                    "GLES 3.0 renderer stopped: ${failure.failure.category} | " +
                        "${failure.failure.diagnosticCode ?: "no-code"} | " +
                        failure.failure.message
                )
            } finally {
                cleanup()
                startupLatch.countDown()
                log("INFO", "GLES 3.0 renderer resources released")
            }
        }

        private fun initialize() {
            if (!surface.isValid) {
                throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.SURFACE_UNAVAILABLE,
                        "Android surface became invalid before EGL initialization",
                        "GLES3_SURFACE_INVALID_DURING_STARTUP"
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
            log("INFO", "EGL initialized: ${eglVersion[0]}.${eglVersion[1]}")

            val config = chooseEs3Config(display)
                ?: throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.MISSING_REQUIRED_FEATURE,
                        "No EGL configuration supports an ES3 window surface",
                        "GLES3_WINDOW_CONFIG_UNAVAILABLE"
                    )
                )

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                ES3_CONTEXT_ATTRIBUTES,
                0
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                throw eglException("Could not create an OpenGL ES 3.0 context", startup = true)
            }

            windowSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                WINDOW_SURFACE_ATTRIBUTES,
                0
            )
            if (windowSurface == EGL14.EGL_NO_SURFACE) {
                throw eglException("Could not create the EGL window surface", startup = true)
            }

            if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
                throw eglException("eglMakeCurrent failed", startup = true)
            }

            if (!EGL14.eglSwapInterval(display, 1)) {
                log("WARN", "Could not request EGL swap interval 1")
            }

            capabilities = readCapabilities()
                ?: throw RendererException(
                    RendererFailure(
                        RendererFailureCategory.MISSING_REQUIRED_FEATURE,
                        "The created EGL context did not expose OpenGL ES 3.0",
                        "GLES3_VERSION_CHECK_FAILED"
                    )
                )

            createGraphicsResources()
            val (surfaceWidth, surfaceHeight) = querySurfaceSize()
            requestedWidth = surfaceWidth
            requestedHeight = surfaceHeight
            contentController = GlesPhotoContentController(
                mediaAccess = mediaAccess,
                maxTextureSize = { capabilities?.maxTextureSize ?: 4096 },
                enqueueRenderCommand = ::enqueueRenderCommand,
                uploadBitmap = GlesPhotoTexture::upload,
                deleteTexture = GlesPhotoTexture::release,
                dispatchEvent = ::dispatchEvent,
                dispatchMediaReleased = ::dispatchMediaReleased,
                log = log
            )
            log(
                "INFO",
                "GLES 3.0 context ready: ${capabilities?.deviceName}, " +
                    "surface=${surfaceWidth}x${surfaceHeight}"
            )
        }

        private fun createGraphicsResources() {
            shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionAttribute = GLES30.glGetAttribLocation(shaderProgram, "aPosition")
            texCoordAttribute = GLES30.glGetAttribLocation(shaderProgram, "aTexCoord")
            textureUniform = GLES30.glGetUniformLocation(shaderProgram, "uTexture")
            transformUniform = GLES30.glGetUniformLocation(shaderProgram, "uTransform")

            if (
                positionAttribute < 0 || texCoordAttribute < 0 ||
                textureUniform < 0 || transformUniform < 0
            ) {
                throw IllegalStateException("GLES 3.0 photo shader interface is incomplete")
            }

            val vertexData = floatBuffer(QUAD_VERTICES)
            val bufferIds = IntArray(1)
            GLES30.glGenBuffers(1, bufferIds, 0)
            geometryBuffer = bufferIds[0]
            if (geometryBuffer == 0) throw IllegalStateException("Could not allocate quad VBO")

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometryBuffer)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                QUAD_VERTICES.size * Float.SIZE_BYTES,
                vertexData,
                GLES30.GL_STATIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                throw IllegalStateException(
                    "GLES 3.0 photo resource setup failed: 0x${error.toString(16)}"
                )
            }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = try {
                compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            } catch (error: Throwable) {
                GLES30.glDeleteShader(vertexShader)
                throw error
            }

            val program = GLES30.glCreateProgram()
            if (program == 0) {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
                throw IllegalStateException("glCreateProgram returned 0")
            }

            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)

            if (status[0] == 0) {
                val message = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw IllegalStateException("GLES 3.0 shader link failed: $message")
            }
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) throw IllegalStateException("glCreateShader returned 0")

            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)

            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("GLES 3.0 shader compilation failed: $message")
            }
            return shader
        }

        private fun renderLoop() {
            while (!stopRequested) {
                drainRenderCommands()
                if (stopRequested) break
                if (paused) {
                    LockSupport.parkNanos(PAUSED_POLL_INTERVAL_NS)
                    continue
                }

                val frameStartNs = System.nanoTime()
                renderFrame(frameStartNs)
                val frameDurationNs = System.nanoTime() - frameStartNs
                val frameIntervalNs = 1_000_000_000L / targetFps.coerceIn(1, 240)
                val remainingNs = frameIntervalNs - frameDurationNs
                if (remainingNs > 0L) LockSupport.parkNanos(remainingNs)
            }
        }

        private fun renderFrame(frameStartNanos: Long) {
            val width = requestedWidth
            val height = requestedHeight
            if (width <= 0 || height <= 0) return

            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            val photoTexture = contentController?.textureId ?: 0
            if (photoTexture != 0) {
                val motion = latestMotion
                val transform = motionSmoother.update(
                    motion,
                    width,
                    height,
                    timestampNanos = frameStartNanos
                )
                val matrix = GlesMotionMatrix.build(
                    transform,
                    width,
                    height,
                    motion.configuration.threeDimensionalEnabled,
                    baseScaleX = contentController?.overscanScaleX ?: 1f,
                    baseScaleY = contentController?.overscanScaleY ?: 1f
                )

                GLES30.glUseProgram(shaderProgram)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTexture)
                GLES30.glUniform1i(textureUniform, 0)
                GLES30.glUniformMatrix4fv(transformUniform, 1, false, matrix, 0)

                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometryBuffer)
                GLES30.glEnableVertexAttribArray(positionAttribute)
                GLES30.glVertexAttribPointer(
                    positionAttribute,
                    2,
                    GLES30.GL_FLOAT,
                    false,
                    QUAD_VERTEX_STRIDE_BYTES,
                    0
                )
                GLES30.glEnableVertexAttribArray(texCoordAttribute)
                GLES30.glVertexAttribPointer(
                    texCoordAttribute,
                    2,
                    GLES30.GL_FLOAT,
                    false,
                    QUAD_VERTEX_STRIDE_BYTES,
                    QUAD_TEX_COORD_OFFSET_BYTES
                )
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, QUAD_VERTEX_COUNT)
                GLES30.glDisableVertexAttribArray(positionAttribute)
                GLES30.glDisableVertexAttribArray(texCoordAttribute)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                GLES30.glUseProgram(0)
            }

            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                throw IllegalStateException(
                    "GLES 3.0 frame rendering failed: 0x${error.toString(16)}"
                )
            }
            if (!EGL14.eglSwapBuffers(display, windowSurface)) {
                throw eglException("eglSwapBuffers failed", startup = false)
            }

            contentController?.currentMedia?.let { media ->
                if (photoTexture != 0) dispatchEvent(RendererEvent.FramePresented(media))
            }
        }

        private fun querySurfaceSize(): Pair<Int, Int> {
            val values = IntArray(1)
            val width = if (EGL14.eglQuerySurface(
                    display,
                    windowSurface,
                    EGL14.EGL_WIDTH,
                    values,
                    0
                )
            ) values[0] else 0

            val height = if (EGL14.eglQuerySurface(
                    display,
                    windowSurface,
                    EGL14.EGL_HEIGHT,
                    values,
                    0
                )
            ) values[0] else 0

            return width to height
        }

        private fun eglException(message: String, startup: Boolean): RendererException {
            val error = EGL14.eglGetError()
            val category = when (error) {
                EGL14.EGL_BAD_SURFACE, EGL14.EGL_BAD_NATIVE_WINDOW ->
                    RendererFailureCategory.SURFACE_UNAVAILABLE
                EGL14.EGL_CONTEXT_LOST ->
                    RendererFailureCategory.DEVICE_LOST
                EGL14.EGL_BAD_ALLOC ->
                    RendererFailureCategory.RESOURCE_UNAVAILABLE
                else -> if (startup) {
                    RendererFailureCategory.INITIALIZATION_FAILED
                } else {
                    RendererFailureCategory.TEMPORARY_FAILURE
                }
            }
            return RendererException(
                RendererFailure(
                    category = category,
                    message = "$message (EGL error 0x${error.toString(16)})",
                    diagnosticCode = "GLES3_EGL_0x${error.toString(16)}"
                )
            )
        }

        private fun toRendererFailure(
            error: Throwable,
            startup: Boolean
        ): RendererException {
            if (error is RendererException) return error
            val glError = if (context != EGL14.EGL_NO_CONTEXT) {
                runCatching { GLES30.glGetError() }.getOrNull()
            } else {
                null
            }
            val code = glError
                ?.takeIf { it != GLES30.GL_NO_ERROR }
                ?.let { "GLES3_GL_0x${it.toString(16)}" }
            return RendererException(
                RendererFailure(
                    category = if (startup) {
                        RendererFailureCategory.INITIALIZATION_FAILED
                    } else {
                        RendererFailureCategory.TEMPORARY_FAILURE
                    },
                    message = error.message ?: "Unknown GLES 3.0 renderer failure",
                    diagnosticCode = code ?: if (startup) {
                        "GLES3_INITIALIZATION_FAILED"
                    } else {
                        "GLES3_RUNTIME_FAILURE"
                    }
                ),
                error
            )
        }

        private fun reportRuntimeFailure(failure: RendererFailure) {
            val listener = synchronized(failureLock) {
                failureListener.also { current ->
                    if (current == null) pendingFailure = failure
                }
            }
            if (listener != null) dispatchFailure(listener, failure)
            synchronized(commandLock) { stopRequested = true }
            LockSupport.unpark(this)
        }

        private fun dispatchFailure(
            listener: RendererFailureListener,
            failure: RendererFailure
        ) {
            mainHandler.post {
                runCatching { listener.onFailure(failure) }
                    .onFailure { error ->
                        log("ERROR", "GLES 3.0 failure listener threw: ${error.message}")
                    }
            }
        }

        private fun wakeRenderer() {
            LockSupport.unpark(this)
        }

        private fun cleanup() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                val contextCurrent = context != EGL14.EGL_NO_CONTEXT &&
                    windowSurface != EGL14.EGL_NO_SURFACE &&
                    EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)

                if (contextCurrent) {
                    drainRenderCommands()
                    contentController?.close()
                    contentController = null
                    if (geometryBuffer != 0) {
                        GLES30.glDeleteBuffers(1, intArrayOf(geometryBuffer), 0)
                    }
                    if (shaderProgram != 0) GLES30.glDeleteProgram(shaderProgram)
                }

                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, windowSurface)
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                }
                if (eglInitialized) EGL14.eglTerminate(display)
            }
            EGL14.eglReleaseThread()

            windowSurface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
            geometryBuffer = 0
            shaderProgram = 0
            eglInitialized = false
        }

        companion object {
            private const val STARTUP_TIMEOUT_SECONDS = 10L
            private const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L
            private const val PAUSED_POLL_INTERVAL_NS = 100_000_000L
            private const val QUAD_VERTEX_COUNT = 6
            private const val QUAD_VERTEX_STRIDE_BYTES = 4 * Float.SIZE_BYTES
            private const val QUAD_TEX_COORD_OFFSET_BYTES = 2 * Float.SIZE_BYTES

            private val QUAD_VERTICES = floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f, -1f, 1f, 1f,
                 1f,  1f, 1f, 0f,
                -1f,  1f, 0f, 0f
            )

            private val VERTEX_SHADER = """
                #version 300 es
                in vec2 aPosition;
                in vec2 aTexCoord;
                uniform mat4 uTransform;
                out vec2 vTexCoord;
                void main() {
                    gl_Position = uTransform * vec4(aPosition, 0.0, 1.0);
                    vTexCoord = aTexCoord;
                }
            """.trimIndent()

            private val FRAGMENT_SHADER = """
                #version 300 es
                precision mediump float;
                uniform sampler2D uTexture;
                in vec2 vTexCoord;
                out vec4 fragColor;
                void main() {
                    fragColor = texture(uTexture, vTexCoord);
                }
            """.trimIndent()

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

    private companion object {
        private fun chooseEs3Config(display: EGLDisplay): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val found = EGL14.eglChooseConfig(
                display,
                ES3_CONFIG_ATTRIBUTES,
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
            val version = GLES30.glGetString(GLES30.GL_VERSION) ?: return null
            val parsedVersion = ES_VERSION_REGEX.find(version)
            val major = parsedVersion?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
            val minor = parsedVersion.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (major < 3) return null
    
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
            val vendor = GLES30.glGetString(GLES30.GL_VENDOR)
            val extensionString = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            val extensions = extensionString
                .split(WHITESPACE_REGEX)
                .filter(String::isNotBlank)
                .toSet()
    
            val maxTextureSize = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
    
            val features = buildSet {
                if (major > 3 || (major == 3 && minor >= 1)) {
                    add(RendererFeature.COMPUTE_SHADERS)
                }
                if ("GL_OES_EGL_image_external_essl3" in extensions) {
                    add(RendererFeature.EXTERNAL_IMAGE_IMPORT)
                }
            }
    
            return RendererCapabilities(
                backendType = RendererType.OPENGL_ES_3,
                deviceName = renderer?.takeIf(String::isNotBlank)
                    ?: vendor?.takeIf(String::isNotBlank),
                apiVersion = version,
                driverVersion = null,
                extensions = extensions,
                features = features,
                maxTextureSize = maxTextureSize[0].takeIf { it > 0 },
                contentFeatures = setOf(
                    RendererContentFeature.PHOTO,
                    RendererContentFeature.MOTION_2D,
                    RendererContentFeature.MOTION_3D,
                    RendererContentFeature.MOTION_ROTATION
                )
            )
        }
    
        private fun unsupportedEs3(
            message: String,
            eglError: Int? = null
        ): RendererSupportResult.Unsupported {
            val errorCode = eglError?.let { "0x" + it.toString(16) }
            return RendererSupportResult.Unsupported(
                RendererFailure(
                    category = RendererFailureCategory.MISSING_REQUIRED_FEATURE,
                    message = message,
                    diagnosticCode = errorCode?.let { "GLES3_ES3_CONTEXT_UNAVAILABLE_$it" }
                        ?: "GLES3_ES3_CONTEXT_UNAVAILABLE"
                )
            )
        }
    
        private fun isConfirmedEs3ConfigurationFailure(error: Int): Boolean =
            error == EGL14.EGL_BAD_CONFIG ||
                error == EGL14.EGL_BAD_MATCH ||
                error == EGL14.EGL_BAD_ATTRIBUTE

        const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

        val ES3_CONFIG_ATTRIBUTES = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val ES3_CONTEXT_ATTRIBUTES = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )

        val PBUFFER_ATTRIBUTES = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )

        val WINDOW_SURFACE_ATTRIBUTES = intArrayOf(EGL14.EGL_NONE)
        val ES_VERSION_REGEX = Regex("""OpenGL ES\s+(\d+)\.(\d+)""")
        val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
