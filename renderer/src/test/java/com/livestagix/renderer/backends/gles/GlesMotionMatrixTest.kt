package com.livestagix.renderer.backends.gles

import com.livestagix.renderer.pipeline.RendererMotionTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlesMotionMatrixTest {
    @Test
    fun twoDimensionalMotionUsesViewportNormalizedTranslationAndScale() {
        val matrix = GlesMotionMatrix.build(
            RendererMotionTransform(
                translationX = 50f,
                translationY = 25f,
                scale = 1.2f
            ),
            viewportWidth = 100,
            viewportHeight = 200,
            threeDimensional = false
        )

        assertEquals(1f, matrix[12], 0.0001f)
        assertEquals(-0.25f, matrix[13], 0.0001f)
        assertEquals(1.2f, matrix[0], 0.0001f)
        assertEquals(1.2f, matrix[5], 0.0001f)
    }

    @Test
    fun threeDimensionalMotionBuildsFinitePerspectiveTransform() {
        val matrix = GlesMotionMatrix.build(
            RendererMotionTransform(
                translationX = 24f,
                translationY = -18f,
                translationZ = 8f,
                rotationXDegrees = 9f,
                rotationYDegrees = -7f,
                rotationZDegrees = 3f,
                scale = 1.15f
            ),
            viewportWidth = 1080,
            viewportHeight = 2400,
            threeDimensional = true
        )

        assertEquals(16, matrix.size)
        assertTrue(matrix.all(Float::isFinite))
        assertTrue(matrix[11] < 0f)
        assertTrue(matrix[14] < 0f)
    }

    @Test
    fun neutralTwoDimensionalTransformIsIdentity() {
        val matrix = GlesMotionMatrix.build(
            RendererMotionTransform.IDENTITY,
            viewportWidth = 1080,
            viewportHeight = 2400,
            threeDimensional = false
        )
        val expected = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        expected.indices.forEach { index ->
            assertEquals(expected[index], matrix[index], 0.0001f)
        }
    }
}
