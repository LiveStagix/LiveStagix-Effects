package com.livestagix.renderer.pipeline

import com.livestagix.renderer.api.RendererMotion
import com.livestagix.renderer.api.RendererMotionConfiguration
import com.livestagix.renderer.api.RendererSettings
import com.livestagix.renderer.api.RendererUpscaleAlgorithm
import com.livestagix.renderer.api.RendererUpscaleBackendPreference
import com.livestagix.renderer.effects.RendererEffectCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererCoreFoundationTest {
    @Test
    fun photoCropMatchesLiveWpAspectAndCropOffsets() {
        val left = RendererPhotoDecoder.calculateVisibleCropRect(
            4000, 3000, 1080, 2400, horizontalOffset = 1f
        )
        val right = RendererPhotoDecoder.calculateVisibleCropRect(
            4000, 3000, 1080, 2400, horizontalOffset = -1f
        )
        assertEquals(0, left.left)
        assertTrue(right.left > left.left)
        assertEquals(left.width, right.width)
        assertTrue(left.height > 0)
    }

    @Test
    fun photoCropAccountsForOrientationAndExpandsWithinSourceBounds() {
        val crop = RendererPhotoDecoder.calculateVisibleCropRect(
            4000, 3000, 1080, 2400, orientationDegrees = 90
        )
        val expanded = RendererPhotoDecoder.expandPhotoCropRect(crop, 4000, 3000)
        assertTrue(expanded.left <= crop.left)
        assertTrue(expanded.top <= crop.top)
        assertTrue(expanded.right >= crop.right)
        assertTrue(expanded.bottom >= crop.bottom)
        assertTrue(expanded.right <= 4000)
        assertTrue(expanded.bottom <= 3000)
    }

    @Test
    fun motionCalculatorPreservesLiveWpSensitivityAndEdgeSafety() {
        val motion = RendererMotion(
            rollDegrees = 18f,
            pitchDegrees = 12f,
            depth = 10f,
            rotationDegrees = 8f,
            configuration = RendererMotionConfiguration(
                enabled = true,
                twoDimensionalEnabled = true,
                threeDimensionalEnabled = true,
                rotationEnabled = true,
                sensitivity2dX = 75,
                sensitivity2dY = 75,
                sensitivity3dX = 75,
                sensitivity3dY = 75,
                depthSensitivity = 50,
                rotationSensitivity = 50,
                edgeSafetyEnabled = true
            )
        )
        val transform = RendererMotionTransformCalculator.calculate(motion, 1080, 2400)
        assertTrue(transform.translationX > 70f)
        assertTrue(transform.translationY > 0f)
        assertTrue(transform.rotationXDegrees < 0f)
        assertTrue(transform.rotationYDegrees > 0f)
        assertTrue(transform.scale >= 1f)
        assertEquals(
            RendererMotionTransform.IDENTITY,
            RendererMotionTransformCalculator.calculate(motion, 1080, 2400, isVideo = true)
        )
    }

    @Test
    fun motionControllerAppliesAdvancedRateLimitsAndReturnsTowardNeutral() {
        val configuration = RendererMotionConfiguration(
            enabled = true,
            twoDimensionalEnabled = true,
            sensitivity2dX = 100,
            sensitivity2dY = 100,
            translationRate = 0.05f,
            edgeSafetyEnabled = false
        )
        val controller = RendererMotionTransformSmoother()
        val first = controller.update(
            RendererMotion(rollDegrees = 30f, configuration = configuration, timestampNanos = 1_000_000_000L),
            1080,
            2400
        )
        val second = controller.update(
            RendererMotion(rollDegrees = 30f, configuration = configuration, timestampNanos = 1_050_000_000L),
            1080,
            2400
        )
        assertTrue(first.translationX > 0f)
        assertTrue(second.translationX > first.translationX)
        assertTrue(second.translationX - first.translationX <= 5f)

        val neutral = controller.update(
            RendererMotion(configuration = configuration.copy(enabled = false), timestampNanos = 1_100_000_000L),
            1080,
            2400
        )
        assertTrue(neutral.translationX < second.translationX)
    }

    @Test
    fun motionSmootherCompatibilityOverloadMovesTowardTarget() {
        val smoother = RendererMotionTransformSmoother()
        smoother.update(RendererMotionTransform.IDENTITY, 1_000_000_000L)
        val target = RendererMotionTransform(translationX = 100f, scale = 1.2f)
        val moved = smoother.update(target, 1_050_000_000L)
        assertTrue(moved.translationX > 0f)
        assertTrue(moved.translationX < target.translationX)
    }

    @Test
    fun frameGenerationAnalyzerMatchesLiveWpRegionalMotionAndEncodesField() {
        val width = 64
        val height = 48
        val previousPixels = ByteArray(width * height)
        val currentPixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                previousPixels[y * width + x] = ((x * 37 + y * 71 + x * y * 7) % 251).toByte()
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                currentPixels[y * width + x] = previousPixels[y * width + (x - 2).coerceIn(0, width - 1)]
            }
        }
        val grid = RendererFrameGenerationAnalyzer.estimate(
            RendererLumaFrame(width, height, width, previousPixels),
            RendererLumaFrame(width, height, width, currentPixels),
            columns = 4,
            rows = 3,
            searchRadius = 3,
            patchRadius = 2
        )
        assertEquals(12, grid.vectors.size)
        assertTrue(grid.vectors.count { it.xPixels == 2f } >= 8)
        assertEquals(48, grid.fieldPixels.size)
        assertTrue(grid.acceptedRegions >= 8)
    }

    @Test
    fun frameGenerationAnalyzerRejectsAbruptSceneCut() {
        val width = 64
        val height = 48
        val previous = ByteArray(width * height)
        val current = ByteArray(width * height) { 0xFF.toByte() }
        val grid = RendererFrameGenerationAnalyzer.estimate(
            RendererLumaFrame(width, height, width, previous),
            RendererLumaFrame(width, height, width, current),
            columns = 4,
            rows = 3,
            searchRadius = 3,
            patchRadius = 2
        )
        assertTrue(grid.sceneCutDetected)
        assertEquals(0, grid.acceptedRegions)
        assertEquals(0f, grid.motionConfidence, 0f)
    }

    @Test
    fun upscalePolicyUsesDevicePreferenceAndBackendFallbackOrder() {
        val available = setOf(
            RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE,
            RendererUpscaleAlgorithm.RCAS_SHARPEN,
            RendererUpscaleAlgorithm.GENERIC_ENHANCE,
            RendererUpscaleAlgorithm.LINEAR
        )
        val adrenoAuto = RendererUpscalePolicy.evaluate(
            720, 1280, 1080, 2400,
            RendererSettings(),
            RendererUpscaleDeviceProfile(gpuRenderer = "Adreno (TM) 613"),
            available
        )
        val fallback = RendererUpscalePolicy.evaluate(
            720, 1280, 1080, 2400,
            RendererSettings(smartUpscaleBackend = RendererUpscaleBackendPreference.SA),
            RendererUpscaleDeviceProfile(gpuRenderer = "Adreno 613"),
            setOf(RendererUpscaleAlgorithm.RCAS_SHARPEN)
        )
        val disabled = RendererUpscalePolicy.evaluate(
            720, 1280, 1080, 2400,
            RendererSettings(smartUpscaleEnabled = false),
            availableAlgorithms = setOf(RendererUpscaleAlgorithm.LINEAR)
        )
        assertTrue(adrenoAuto.active)
        assertEquals(RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE, adrenoAuto.algorithm)
        assertEquals(RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE, adrenoAuto.requestedAlgorithm)
        assertTrue(fallback.active)
        assertEquals(RendererUpscaleAlgorithm.RCAS_SHARPEN, fallback.algorithm)
        assertTrue(fallback.fallbackUsed)
        assertFalse(disabled.active)
        assertEquals(RendererUpscaleAlgorithm.NONE, disabled.algorithm)
    }

    @Test
    fun performanceControllerReducesRenderScaleAfterSustainedPressure() {
        val controller = RendererPerformanceController()
        var decision: RendererQualityDecision? = null
        repeat(9) {
            decision = controller.update(
                RendererPerformanceSample(frameTimeNanos = 40_000_000L, targetFps = 60)
            )
        }
        assertTrue(decision!!.renderScale < 0.8f)
        assertTrue(decision!!.shouldReduceEffectQuality)
    }

    @Test
    fun effectQualityControllerUsesLiveWpDeviceAndRuntimeSignals() {
        val profile = RendererEffectHardwareProfile(
            gpuRenderer = "Adreno (TM) 730",
            cpuCoreCount = 8,
            totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
            displayWidth = 1080,
            displayHeight = 2400
        )
        val controller = RendererEffectQualityController(profile)
        assertEquals(RendererEffectQualityTier.HIGH, controller.quality)
        var decision: RendererEffectQualityDecision? = null
        repeat(24) { index ->
            decision = controller.update(
                RendererEffectPerformanceSample(
                    timestampNs = 1_000_000_000L + index * 100_000_000L,
                    targetFps = 60,
                    renderDurationNs = 35_000_000L,
                    frameIntervalNs = 40_000_000L
                )
            )
        }
        assertTrue(
            decision!!.quality == RendererEffectQualityTier.BALANCED ||
                decision!!.quality == RendererEffectQualityTier.EFFICIENT
        )
    }

    @Test
    fun effectCatalogPreservesLiveWpIdsAndDefaults() {
        val pulse = RendererEffectCatalog.find(" PULSE_HALO ")!!
        val reactor = RendererEffectCatalog.find("reactor_core")!!
        assertEquals(RendererEffectCatalog.PULSE_HALO, pulse.id)
        assertEquals(RendererEffectCatalog.REACTOR_CORE, reactor.id)
        assertTrue(pulse.supportsGyro)
        assertFalse(pulse.requiresMedia)
        assertEquals(50, pulse.defaultSpeedPercent)
        assertEquals(50, reactor.defaultIntensityPercent)
        assertTrue(pulse.defaultAnimationEnabled)
        assertTrue(pulse.implementedBackends.isEmpty())
    }
}