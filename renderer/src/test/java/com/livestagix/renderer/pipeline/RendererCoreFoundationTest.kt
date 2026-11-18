package com.livestagix.renderer.pipeline

import com.livestagix.renderer.api.RendererMotion
import com.livestagix.renderer.api.RendererMotionConfiguration
import com.livestagix.renderer.api.RendererSettings
import com.livestagix.renderer.api.RendererUpscaleAlgorithm
import com.livestagix.renderer.effects.RendererEffectCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererCoreFoundationTest {
    @Test
    fun motionCalculatorProducesSafeTransformAndBypassesVideo() {
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
                rotationSensitivity = 50
            )
        )

        val transform = RendererMotionTransformCalculator.calculate(motion, 1080, 2400)

        assertTrue(transform.translationX != 0f)
        assertTrue(transform.translationY != 0f)
        assertTrue(transform.scale >= 1f)
        assertEquals(
            RendererMotionTransform.IDENTITY,
            RendererMotionTransformCalculator.calculate(motion, 1080, 2400, isVideo = true)
        )
    }

    @Test
    fun motionSmootherMovesTowardTarget() {
        val smoother = RendererMotionTransformSmoother()
        val start = smoother.update(RendererMotionTransform.IDENTITY, 1_000_000_000L)
        val target = RendererMotionTransform(translationX = 100f, scale = 1.2f)
        val moved = smoother.update(target, 1_050_000_000L)

        assertEquals(0f, start.translationX, 0f)
        assertTrue(moved.translationX > 0f)
        assertTrue(moved.translationX < target.translationX)
    }

    @Test
    fun frameGenerationAnalyzerFindsSyntheticHorizontalMotion() {
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
                val sourceX = (x - 2).coerceIn(0, width - 1)
                currentPixels[y * width + x] = previousPixels[y * width + sourceX]
            }
        }

        val grid = RendererFrameGenerationAnalyzer.estimate(
            previous = RendererLumaFrame(width, height, width, previousPixels),
            current = RendererLumaFrame(width, height, width, currentPixels),
            columns = 4,
            rows = 3,
            searchRadius = 3,
            patchRadius = 2
        )

        assertEquals(12, grid.vectors.size)
        assertTrue(grid.vectors.count { it.xPixels == 2 } >= 8)
    }

    @Test
    fun upscalePolicyRespectsDisabledAndQualityPreferences() {
        val active = RendererUpscalePolicy.evaluate(
            sourceWidth = 720,
            sourceHeight = 1280,
            outputWidth = 1080,
            outputHeight = 2400,
            settings = RendererSettings()
        )
        val disabled = RendererUpscalePolicy.evaluate(
            sourceWidth = 720,
            sourceHeight = 1280,
            outputWidth = 1080,
            outputHeight = 2400,
            settings = RendererSettings(smartUpscaleEnabled = false)
        )

        assertTrue(active.active)
        assertEquals(RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE, active.algorithm)
        assertFalse(disabled.active)
        assertEquals(RendererUpscaleAlgorithm.NONE, disabled.algorithm)
    }

    @Test
    fun performanceControllerReducesScaleAfterSustainedPressure() {
        val controller = RendererPerformanceController()
        var decision: RendererQualityDecision? = null
        repeat(9) {
            decision = controller.update(
                RendererPerformanceSample(
                    frameTimeNanos = 40_000_000L,
                    targetFps = 60
                )
            )
        }

        assertTrue(decision!!.renderScale < 0.8f)
        assertTrue(decision!!.shouldReduceEffectQuality)
    }

    @Test
    fun effectCatalogPreservesLiveWpIdentifiers() {
        assertEquals(RendererEffectCatalog.PULSE_HALO, RendererEffectCatalog.normalize(" PULSE_HALO "))
        assertEquals(RendererEffectCatalog.REACTOR_CORE, RendererEffectCatalog.normalize("reactor_core"))
        assertTrue(RendererEffectCatalog.find("pulse_halo")!!.implementedBackends.isEmpty())
    }
}
