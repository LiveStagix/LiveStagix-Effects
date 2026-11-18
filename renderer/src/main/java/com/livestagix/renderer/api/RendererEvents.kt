package com.livestagix.renderer.api

/** Typed runtime events shared by all backends and the LiveWP compatibility adapter. */
sealed interface RendererEvent {
    val timestampNanos: Long

    data class FramePresented(
        val media: RendererMedia?,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class VideoPrepared(
        val metadata: RendererVideoMetadata,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class VideoFramePresented(
        val presentationTimeNanos: Long,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class FrameGeneration(
        val enabled: Boolean,
        val generated: Boolean,
        val strengthPercent: Int,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class SmartUpscale(
        val enabled: Boolean,
        val qualityPercent: Int,
        val algorithm: RendererUpscaleAlgorithm,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class StandaloneEffectStatus(
        val effectId: String,
        val enabled: Boolean,
        val rendering: Boolean,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class Performance(
        val snapshot: RendererPerformanceSnapshot,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class EffectRecovery(
        val effectId: String,
        val recovered: Boolean,
        val message: String? = null,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class EffectQuality(
        val effectId: String,
        val qualityPercent: Int,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class MediaReleased(
        val media: RendererMedia,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class BackendFallback(
        val failedBackend: RendererType,
        val selectedBackend: RendererType,
        val failure: RendererFailure,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent

    data class Failure(
        val failure: RendererFailure,
        override val timestampNanos: Long = System.nanoTime()
    ) : RendererEvent
}

fun interface RendererEventListener {
    /** Implementations must not block the backend render thread. */
    fun onRendererEvent(event: RendererEvent)
}

/** Dimensions and timing reported after a video decoder prepares its stream. */
data class RendererVideoMetadata(
    val width: Int,
    val height: Int,
    val framesPerSecond: Float? = null,
    val variableFrameRate: Boolean? = null,
    val durationMicros: Long? = null
) {
    init {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
        require(framesPerSecond == null || framesPerSecond.isFinite() && framesPerSecond > 0f) {
            "Video frame rate must be positive and finite"
        }
        require(durationMicros == null || durationMicros >= 0L) {
            "Video duration must not be negative"
        }
    }
}

/** Renderer-owned performance values; battery and visibility policy stays in the host app. */
data class RendererPerformanceSnapshot(
    val effectiveFps: Float = 0f,
    val frameTimeNanos: Long = 0L,
    val gpuTimeNanos: Long? = null,
    val renderScale: Float = 1f,
    val thermalStatus: Int? = null
) {
    init {
        require(effectiveFps.isFinite() && effectiveFps >= 0f)
        require(frameTimeNanos >= 0L)
        require(gpuTimeNanos == null || gpuTimeNanos >= 0L)
        require(renderScale.isFinite() && renderScale in 0f..1f)
    }
}
