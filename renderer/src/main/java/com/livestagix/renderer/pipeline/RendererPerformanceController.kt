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


/** LiveWP-compatible adaptive standalone-effect quality policy. */
class RendererEffectQualityController(
    hardwareProfile: RendererEffectHardwareProfile,
    initialQuality: RendererEffectQualityTier = chooseInitialQuality(hardwareProfile),
) {

    var quality: RendererEffectQualityTier = initialQuality
        private set

    private var lastSampleTimestampNs = 0L
    private var sampleCount = 0
    private var pressureSinceNs = 0L
    private var headroomSinceNs = 0L
    private var lastQualityChangeNs = 0L

    fun update(sample: RendererEffectPerformanceSample): RendererEffectQualityDecision {
        if (!sample.isValid()) {
            return decision(RendererEffectQualityReason.INVALID_SAMPLE)
        }

        if (lastSampleTimestampNs > 0L && sample.timestampNs <= lastSampleTimestampNs) {
            resetSampleWindow()
            lastSampleTimestampNs = sample.timestampNs
            return decision(RendererEffectQualityReason.NON_MONOTONIC_SAMPLE)
        }

        if (
            lastSampleTimestampNs > 0L &&
                sample.timestampNs - lastSampleTimestampNs > STALE_SAMPLE_RESET_NS
        ) {
            resetSampleWindow()
        }

        lastSampleTimestampNs = sample.timestampNs
        sampleCount = (sampleCount + 1).coerceAtMost(MIN_SAMPLES_FOR_DECISION)

        if (sample.thermalLevel == RendererEffectThermalLevel.CRITICAL) {
            resetSampleWindow()
            return changeQuality(
                RendererEffectQualityTier.EFFICIENT,
                RendererEffectQualityReason.CRITICAL_THERMAL_PRESSURE,
                sample.timestampNs,
            )
        }

        val frameBudgetNs = NANOS_PER_SECOND / sample.targetFps
        val frameTimingIsSlow =
            sample.frameIntervalNs > frameBudgetNs * FRAME_INTERVAL_STRAIN_PERCENT / 100L
        val renderTimeIsSlow =
            sample.renderDurationNs > frameBudgetNs * RENDER_STRAIN_PERCENT / 100L
        val thermalIsSevere = sample.thermalLevel == RendererEffectThermalLevel.SEVERE
        val performanceIsUnderPressure = frameTimingIsSlow || renderTimeIsSlow || thermalIsSevere

        if (performanceIsUnderPressure) {
            headroomSinceNs = 0L
            if (pressureSinceNs == 0L) {
                pressureSinceNs = sample.timestampNs
            }

            if (
                sampleCount < MIN_SAMPLES_FOR_DECISION ||
                    sample.timestampNs - pressureSinceNs < STRAIN_CONFIRM_NS ||
                    !hasTransitionCooldownElapsed(sample.timestampNs)
            ) {
                return decision(
                    if (thermalIsSevere) {
                        RendererEffectQualityReason.SEVERE_THERMAL_PRESSURE
                    } else {
                        RendererEffectQualityReason.FRAME_STRAIN
                    },
                )
            }

            val nextQuality = quality.lower()
            if (nextQuality == quality) {
                return decision(RendererEffectQualityReason.MINIMUM_QUALITY_REACHED)
            }

            return changeQuality(
                nextQuality,
                if (thermalIsSevere) {
                    RendererEffectQualityReason.SEVERE_THERMAL_PRESSURE
                } else {
                    RendererEffectQualityReason.SUSTAINED_FRAME_STRAIN
                },
                sample.timestampNs,
            )
        }

        pressureSinceNs = 0L
        val hasPerformanceHeadroom =
            sample.frameIntervalNs > 0L &&
                sample.renderDurationNs <= frameBudgetNs * RECOVERY_RENDER_PERCENT / 100L &&
                sample.frameIntervalNs <= frameBudgetNs * RECOVERY_INTERVAL_PERCENT / 100L &&
                sample.thermalLevel < RendererEffectThermalLevel.MODERATE

        if (!hasPerformanceHeadroom) {
            headroomSinceNs = 0L
            return decision(RendererEffectQualityReason.NO_CHANGE)
        }

        if (headroomSinceNs == 0L) {
            headroomSinceNs = sample.timestampNs
        }

        if (
            sampleCount < MIN_SAMPLES_FOR_DECISION ||
                sample.timestampNs - headroomSinceNs < RECOVERY_CONFIRM_NS ||
                !hasTransitionCooldownElapsed(sample.timestampNs)
        ) {
            return decision(RendererEffectQualityReason.PERFORMANCE_HEADROOM)
        }

        val nextQuality = quality.higher()
        if (nextQuality == quality) {
            return decision(RendererEffectQualityReason.MAXIMUM_QUALITY_REACHED)
        }

        return changeQuality(
            nextQuality,
            RendererEffectQualityReason.SUSTAINED_PERFORMANCE_HEADROOM,
            sample.timestampNs,
        )
    }

    private fun hasTransitionCooldownElapsed(nowNs: Long): Boolean =
        lastQualityChangeNs == 0L || nowNs - lastQualityChangeNs >= QUALITY_CHANGE_COOLDOWN_NS

    private fun changeQuality(
        nextQuality: RendererEffectQualityTier,
        reason: RendererEffectQualityReason,
        nowNs: Long,
    ): RendererEffectQualityDecision {
        if (nextQuality == quality) {
            return decision(reason)
        }

        quality = nextQuality
        lastQualityChangeNs = nowNs
        resetSampleWindow()
        return decision(reason, changed = true)
    }

    private fun resetSampleWindow() {
        sampleCount = 0
        pressureSinceNs = 0L
        headroomSinceNs = 0L
    }

    private fun decision(
        reason: RendererEffectQualityReason,
        changed: Boolean = false,
    ): RendererEffectQualityDecision =
        RendererEffectQualityDecision(
            quality = quality,
            changed = changed,
            reason = reason,
        )

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MIN_SAMPLES_FOR_DECISION = 20
        private const val RENDER_STRAIN_PERCENT = 70L
        private const val FRAME_INTERVAL_STRAIN_PERCENT = 135L
        private const val RECOVERY_RENDER_PERCENT = 55L
        private const val RECOVERY_INTERVAL_PERCENT = 110L
        private const val STRAIN_CONFIRM_NS = 1_000_000_000L
        private const val RECOVERY_CONFIRM_NS = 8_000_000_000L
        private const val QUALITY_CHANGE_COOLDOWN_NS = 3_000_000_000L
        private const val STALE_SAMPLE_RESET_NS = 2_000_000_000L
        private const val GIB = 1024L * 1024L * 1024L

        private val adrenoPattern = Regex("adreno\\D*(\\d{3})")
        private val maliPattern = Regex("mali[-\\s]+g(\\d{2,3})")

        /** Maps Android thermal statuses to platform-neutral quality pressure levels. */
        @JvmStatic
        fun mapAndroidThermalStatus(status: Int): RendererEffectThermalLevel =
            when (status) {
                0 -> RendererEffectThermalLevel.NONE
                1 -> RendererEffectThermalLevel.LIGHT
                2 -> RendererEffectThermalLevel.MODERATE
                3 -> RendererEffectThermalLevel.SEVERE
                4,
                5,
                6 -> RendererEffectThermalLevel.CRITICAL
                else -> RendererEffectThermalLevel.NONE
            }

        /**
         * Uses broad GPU-family hints together with CPU count, memory capacity and pixel load.
         * Unknown GPU names deliberately contribute no score; runtime measurements can then correct
         * the conservative Balanced starting point.
         */
        @JvmStatic
        fun chooseInitialQuality(
            profile: RendererEffectHardwareProfile,
        ): RendererEffectQualityTier {
            var score = estimateGpuScore(profile.gpuRenderer)

            if (profile.cpuCoreCount >= 8) {
                score += 1
            } else if (profile.cpuCoreCount in 1..4) {
                score -= 1
            }

            if (profile.totalMemoryBytes >= 8L * GIB) {
                score += 1
            } else if (profile.totalMemoryBytes in 1L..(4L * GIB)) {
                score -= 1
            }

            val displayPixels = profile.displayWidth.toLong() * profile.displayHeight.toLong()
            if (displayPixels >= 4_000_000L) {
                score -= 1
            } else if (displayPixels in 1L..1_500_000L) {
                score += 1
            }

            return when {
                score >= 3 -> RendererEffectQualityTier.HIGH
                score <= -2 -> RendererEffectQualityTier.EFFICIENT
                else -> RendererEffectQualityTier.BALANCED
            }
        }

        private fun estimateGpuScore(renderer: String): Int {
            val normalized = renderer.lowercase()
            if (normalized.contains("immortalis")) return 2

            val adrenoModel =
                adrenoPattern.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (adrenoModel != null) {
                return when {
                    adrenoModel >= 700 -> 2
                    adrenoModel >= 620 -> 1
                    adrenoModel >= 500 -> 0
                    else -> -1
                }
            }

            val maliModel = maliPattern.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (maliModel != null) {
                return when {
                    maliModel >= 700 || maliModel in 70..99 -> 2
                    maliModel >= 600 || maliModel in 60..69 -> 1
                    maliModel >= 500 || maliModel in 50..59 -> 0
                    else -> -1
                }
            }

            return 0
        }
    }
}

data class RendererEffectHardwareProfile(
    val gpuRenderer: String = "",
    val cpuCoreCount: Int = 0,
    val totalMemoryBytes: Long = 0L,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val gpuVendor: String = "",
    val glVersion: String = "",
    val socManufacturer: String = "",
    val socModel: String = "",
)

data class RendererEffectPerformanceSample(
    val timestampNs: Long,
    val targetFps: Int,
    val renderDurationNs: Long,
    val frameIntervalNs: Long,
    val thermalLevel: RendererEffectThermalLevel = RendererEffectThermalLevel.NONE,
) {
    fun isValid(): Boolean =
        timestampNs > 0L && targetFps in 1..240 && renderDurationNs >= 0L && frameIntervalNs >= 0L
}

data class RendererEffectQualityDecision(
    val quality: RendererEffectQualityTier,
    val changed: Boolean,
    val reason: RendererEffectQualityReason,
)

enum class RendererEffectQualityTier {
    EFFICIENT,
    BALANCED,
    HIGH;

    fun lower(): RendererEffectQualityTier =
        when (this) {
            HIGH -> BALANCED
            BALANCED -> EFFICIENT
            EFFICIENT -> EFFICIENT
        }

    fun higher(): RendererEffectQualityTier =
        when (this) {
            EFFICIENT -> BALANCED
            BALANCED -> HIGH
            HIGH -> HIGH
        }
}

enum class RendererEffectThermalLevel {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

enum class RendererEffectQualityReason {
    NO_CHANGE,
    INVALID_SAMPLE,
    NON_MONOTONIC_SAMPLE,
    FRAME_STRAIN,
    SEVERE_THERMAL_PRESSURE,
    CRITICAL_THERMAL_PRESSURE,
    SUSTAINED_FRAME_STRAIN,
    PERFORMANCE_HEADROOM,
    SUSTAINED_PERFORMANCE_HEADROOM,
    MINIMUM_QUALITY_REACHED,
    MAXIMUM_QUALITY_REACHED,
}
