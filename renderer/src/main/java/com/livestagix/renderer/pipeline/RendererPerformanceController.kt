package com.livestagix.renderer.pipeline

import com.livestagix.renderer.api.RendererPerformanceSnapshot
import kotlin.math.max

enum class RendererThermalLevel {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY
}

data class RendererPerformanceSample(
    val frameTimeNanos: Long,
    val gpuTimeNanos: Long? = null,
    val targetFps: Int = 60,
    val thermalLevel: RendererThermalLevel = RendererThermalLevel.NONE
) {
    init {
        require(frameTimeNanos >= 0L)
        require(gpuTimeNanos == null || gpuTimeNanos >= 0L)
        require(targetFps in 1..240)
    }
}

data class RendererQualityDecision(
    val renderScale: Float,
    val shouldSkipFrameGeneration: Boolean,
    val shouldBypassSmartUpscale: Boolean,
    val shouldReduceEffectQuality: Boolean,
    val effectiveFrameBudgetNanos: Long,
    val snapshot: RendererPerformanceSnapshot
)

/**
 * Shared workload quality controller driven by measured frame/GPU time and host-fed thermal
 * state. It does not read PowerManager, battery saver, visibility or app preferences.
 */
class RendererPerformanceController(
    initialRenderScale: Float = 1f
) {
    private var renderScale = initialRenderScale
    private var smoothedFrameTimeNanos = 0.0
    private var pressureSamples = 0
    private var headroomSamples = 0

    init {
        require(initialRenderScale.isFinite() && initialRenderScale in 0.5f..1f)
    }

    fun update(sample: RendererPerformanceSample): RendererQualityDecision {
        val measured = max(sample.frameTimeNanos, sample.gpuTimeNanos ?: 0L)
        if (measured > 0L) {
            smoothedFrameTimeNanos = if (smoothedFrameTimeNanos == 0.0) {
                measured.toDouble()
            } else {
                smoothedFrameTimeNanos * 0.8 + measured * 0.2
            }
        }

        val budget = 1_000_000_000L / sample.targetFps
        val severeThermal = sample.thermalLevel.ordinal >= RendererThermalLevel.SEVERE.ordinal
        val moderateThermal = sample.thermalLevel.ordinal >= RendererThermalLevel.MODERATE.ordinal
        val overBudget = smoothedFrameTimeNanos > budget * 1.12
        val hasHeadroom = smoothedFrameTimeNanos > 0.0 &&
            smoothedFrameTimeNanos < budget * 0.72 &&
            sample.thermalLevel.ordinal <= RendererThermalLevel.LIGHT.ordinal

        if (overBudget || moderateThermal) {
            pressureSamples++
            headroomSamples = 0
        } else if (hasHeadroom) {
            headroomSamples++
            pressureSamples = 0
        } else {
            pressureSamples = (pressureSamples - 1).coerceAtLeast(0)
            headroomSamples = 0
        }

        if (severeThermal) {
            renderScale = (renderScale - 0.12f).coerceAtLeast(0.6f)
            pressureSamples = 0
        } else if (pressureSamples >= 3) {
            renderScale = (renderScale - 0.08f).coerceAtLeast(0.6f)
            pressureSamples = 0
        } else if (headroomSamples >= 24) {
            renderScale = (renderScale + 0.04f).coerceAtMost(1f)
            headroomSamples = 0
        }

        val skipFrameGeneration = severeThermal || renderScale <= 0.68f
        val bypassUpscale = moderateThermal || renderScale <= 0.72f
        val reduceEffects = moderateThermal || renderScale <= 0.8f
        val effectiveFps = if (smoothedFrameTimeNanos > 0.0) {
            (1_000_000_000.0 / smoothedFrameTimeNanos).toFloat()
        } else {
            0f
        }

        return RendererQualityDecision(
            renderScale = renderScale,
            shouldSkipFrameGeneration = skipFrameGeneration,
            shouldBypassSmartUpscale = bypassUpscale,
            shouldReduceEffectQuality = reduceEffects,
            effectiveFrameBudgetNanos = budget,
            snapshot = RendererPerformanceSnapshot(
                effectiveFps = effectiveFps,
                frameTimeNanos = smoothedFrameTimeNanos.toLong(),
                gpuTimeNanos = sample.gpuTimeNanos,
                renderScale = renderScale,
                thermalStatus = sample.thermalLevel.ordinal
            )
        )
    }

    fun reset() {
        renderScale = 1f
        smoothedFrameTimeNanos = 0.0
        pressureSamples = 0
        headroomSamples = 0
    }
}
