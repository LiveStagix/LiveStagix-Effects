package com.livestagix.renderer.backends.gles

import com.livestagix.renderer.pipeline.RendererMotionTransform
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Converts the shared motion result into an OpenGL column-major transform matrix.
 *
 * This is backend presentation math only. It deliberately does not apply media orientation:
 * RendererPhotoDecoder has already physically oriented photo bitmaps.
 */
internal object GlesMotionMatrix {
    fun build(
        transform: RendererMotionTransform,
        viewportWidth: Int,
        viewportHeight: Int,
        threeDimensional: Boolean,
        baseScaleX: Float = 1f,
        baseScaleY: Float = 1f
    ): FloatArray {
        require(baseScaleX.isFinite() && baseScaleX >= 1f)
        require(baseScaleY.isFinite() && baseScaleY >= 1f)
        val width = viewportWidth.coerceAtLeast(1).toFloat()
        val height = viewportHeight.coerceAtLeast(1).toFloat()
        val aspect = width / height

        if (threeDimensional) {
            val projection = perspective(90f, aspect, 0.05f, 10f)
            var model = identity()
            val nativeX = (2f * transform.translationX / width) * aspect
            val nativeY = (2f * transform.translationY / height)
            val depth = (transform.translationZ / 100f).coerceIn(-0.35f, 0.35f)

            model = multiply(model, translation(nativeX, -nativeY, -1f + depth))
            model = multiply(model, rotationX(transform.rotationXDegrees))
            model = multiply(model, rotationY(transform.rotationYDegrees))
            model = multiply(model, rotationZ(transform.rotationZDegrees))
            model = multiply(
                model,
                scale(
                    aspect * transform.scale * baseScaleX,
                    transform.scale * baseScaleY,
                    1f
                )
            )
            return multiply(projection, model)
        }

        var result = identity()
        result = multiply(
            result,
            translation(
                2f * transform.translationX / width,
                -2f * transform.translationY / height,
                0f
            )
        )
        result = multiply(result, rotationZ(transform.rotationZDegrees))
        result = multiply(
            result,
            scale(transform.scale * baseScaleX, transform.scale * baseScaleY, 1f)
        )
        return result
    }

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    private fun translation(x: Float, y: Float, z: Float) = identity().apply {
        this[12] = x
        this[13] = y
        this[14] = z
    }

    private fun scale(x: Float, y: Float, z: Float) = identity().apply {
        this[0] = x
        this[5] = y
        this[10] = z
    }

    private fun rotationX(degrees: Float): FloatArray {
        val radians = Math.toRadians(degrees.toDouble()).toFloat()
        val c = cos(radians)
        val s = sin(radians)
        return identity().apply {
            this[5] = c
            this[6] = s
            this[9] = -s
            this[10] = c
        }
    }

    private fun rotationY(degrees: Float): FloatArray {
        val radians = Math.toRadians(degrees.toDouble()).toFloat()
        val c = cos(radians)
        val s = sin(radians)
        return identity().apply {
            this[0] = c
            this[2] = -s
            this[8] = s
            this[10] = c
        }
    }

    private fun rotationZ(degrees: Float): FloatArray {
        val radians = Math.toRadians(degrees.toDouble()).toFloat()
        val c = cos(radians)
        val s = sin(radians)
        return identity().apply {
            this[0] = c
            this[1] = s
            this[4] = -s
            this[5] = c
        }
    }

    private fun perspective(
        fieldOfViewDegrees: Float,
        aspect: Float,
        near: Float,
        far: Float
    ): FloatArray {
        val f = 1f / tan(Math.toRadians(fieldOfViewDegrees.toDouble() * 0.5).toFloat())
        val safeAspect = max(0.0001f, aspect)
        val result = FloatArray(16)
        result[0] = f / safeAspect
        result[5] = f
        result[10] = (far + near) / (near - far)
        result[11] = -1f
        result[14] = (2f * far * near) / (near - far)
        return result
    }

    /** Column-major 4x4 matrix multiplication: result = left * right. */
    private fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (column in 0..3) {
            for (row in 0..3) {
                var value = 0f
                for (index in 0..3) {
                    value += left[index * 4 + row] * right[column * 4 + index]
                }
                result[column * 4 + row] = value
            }
        }
        return result
    }
}
