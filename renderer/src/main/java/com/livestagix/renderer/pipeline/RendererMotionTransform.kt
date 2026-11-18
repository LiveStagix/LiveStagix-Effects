package com.livestagix.renderer.pipeline

import com.livestagix.renderer.api.RendererMotion
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Backend-neutral transform in pixel and degree units. */
data class RendererMotionTransform(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val translationZ: Float = 0f,
    val rotationXDegrees: Float = 0f,
    val rotationYDegrees: Float = 0f,
    val rotationZDegrees: Float = 0f,
    val scale: Float = 1f
) {
    init {
        require(
            listOf(
                translationX, translationY, translationZ,
                rotationXDegrees, rotationYDegrees, rotationZDegrees, scale
            ).all(Float::isFinite)
        )
        require(scale >= 1f) { "Wallpaper edge-safe scale must be at least 1" }
    }

    companion object {
        val IDENTITY = RendererMotionTransform()
    }
}

/**
 * Shared first-pass motion calculation. It returns values rather than an OpenGL matrix,
 * so Vulkan, GLES3 and GLES2 can apply the same state in their own graphics API.
 *
 * Video intentionally bypasses wallpaper motion, matching LiveWP. This calculator is a
 * foundation and is not yet a complete port of LiveWP's rate-limited auto-stabilisation.
 */
object RendererMotionTransformCalculator {
    fun calculate(
        motion: RendererMotion,
        viewportWidth: Int,
        viewportHeight: Int,
        isVideo: Boolean = false
    ): RendererMotionTransform {
        if (isVideo || !motion.configuration.enabled) {
            return RendererMotionTransform.IDENTITY
        }

        val width = viewportWidth.coerceAtLeast(1).toFloat()
        val height = viewportHeight.coerceAtLeast(1).toFloat()
        val config = motion.configuration
        val sx = (config.sensitivity2dX / 25f).coerceIn(0f, 4f)
        val sy = (config.sensitivity2dY / 25f).coerceIn(0f, 4f)
        val s3x = (config.sensitivity3dX / 25f).coerceIn(0f, 4f)
        val s3y = (config.sensitivity3dY / 25f).coerceIn(0f, 4f)
        val sd = (config.depthSensitivity / 25f).coerceIn(0f, 4f)

        val inversion2d = if (config.twoDimensionalInverted) -1f else 1f
        val inversion3d = if (config.threeDimensionalInverted) -1f else 1f
        val roll2d = motion.rollDegrees * inversion2d
        val pitch2d = motion.pitchDegrees * inversion2d
        val roll3d = motion.rollDegrees * inversion3d
        val pitch3d = motion.pitchDegrees * inversion3d

        var tx = 0f
        var ty = 0f
        var tz = 0f
        var rx = 0f
        var ry = 0f
        var rz = 0f
        var safeScale = 1f

        if (config.twoDimensionalEnabled) {
            val xBudget = width * 0.08f * sx * 0.72f
            val yBudget = height * 0.08f * sy * 0.72f
            tx = tanh((roll2d / 18f) * sx) * min(width * 0.10f, xBudget)
            ty = tanh((pitch2d / 18f) * sy) * min(height * 0.10f, yBudget)
        }

        val tilt = min(45f, sqrt(roll3d * roll3d + pitch3d * pitch3d))
        if (config.threeDimensionalEnabled) {
            rx = (-tanh((pitch3d / 20f) * s3y) * 14f * s3y).coerceIn(-14f, 14f)
            ry = (tanh((roll3d / 20f) * s3x) * 14f * s3x).coerceIn(-14f, 14f)
            val depthInput = max(tilt, motion.depth.coerceAtLeast(0f))
            tz = tanh((depthInput / 35f) * sd) * 12f * sd
        }

        if (config.rotationEnabled) {
            val rotationScale = (config.rotationSensitivity / 25f).coerceIn(0f, 4f)
            val direction = if (config.rotationInverted) -1f else 1f
            rz = (motion.rotationDegrees * rotationScale * direction).coerceIn(-20f, 20f)
        }

        if (config.edgeSafetyEnabled) {
            if (config.threeDimensionalEnabled) {
                val radX = Math.toRadians(abs(rx).toDouble()).toFloat()
                val radY = Math.toRadians(abs(ry).toDouble()).toFloat()
                val fx = max(0.65f, cos(radY) - ((width * 0.28f) / 900f) * sin(radY))
                val fy = max(0.65f, cos(radX) - ((height * 0.28f) / 900f) * sin(radX))
                val panX = 1f + (2f * abs(tx)) / width
                val panY = 1f + (2f * abs(ty)) / height
                val corner = 1f + radX * 0.18f + radY * 0.18f
                val perspectiveScale = max(
                    (1f / fx) * panX,
                    max((1f / fy) * panY, corner * max(panX, panY))
                ) * 1.02f
                val depthScale = 1f + 0.08f * min(1f, tilt / 45f) * sd
                val dynamicDepthScale = 1f + 0.12f * min(1f, tilt / 30f)
                safeScale = max(perspectiveScale, max(depthScale, dynamicDepthScale))
            } else if (config.twoDimensionalEnabled) {
                val panX = 1f + (2f * abs(tx)) / width
                val panY = 1f + (2f * abs(ty)) / height
                safeScale = max(panX, panY) * 1.015f
            }

            if (abs(rz) > 0.001f) {
                val angle = Math.toRadians(abs(rz).toDouble()).toFloat()
                val rotationCoverage = max(
                    (width * abs(cos(angle)) + height * abs(sin(angle))) / width,
                    (height * abs(cos(angle)) + width * abs(sin(angle))) / height
                )
                safeScale = max(safeScale, rotationCoverage)
            }
        }

        return RendererMotionTransform(
            translationX = tx,
            translationY = ty,
            translationZ = tz,
            rotationXDegrees = rx,
            rotationYDegrees = ry,
            rotationZDegrees = rz,
            scale = max(1f, safeScale)
        )
    }
}

/** Exponential smoother matching the existing LiveWP 0.8-second settling time. */
class RendererMotionTransformSmoother(
    private val responseSeconds: Float = 0.8f
) {
    private var current = RendererMotionTransform.IDENTITY
    private var lastTimestampNanos = 0L
    private var initialized = false

    init {
        require(responseSeconds.isFinite() && responseSeconds > 0f)
    }

    fun update(
        target: RendererMotionTransform,
        timestampNanos: Long = System.nanoTime()
    ): RendererMotionTransform {
        require(timestampNanos >= 0L)
        if (!initialized) {
            current = target
            lastTimestampNanos = timestampNanos
            initialized = true
            return current
        }

        val elapsedNanos = (timestampNanos - lastTimestampNanos).coerceAtLeast(0L)
        val dt = (elapsedNanos / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        lastTimestampNanos = timestampNanos
        val amount = 1f - exp(-dt / responseSeconds)

        current = RendererMotionTransform(
            translationX = approach(current.translationX, target.translationX, amount),
            translationY = approach(current.translationY, target.translationY, amount),
            translationZ = approach(current.translationZ, target.translationZ, amount),
            rotationXDegrees = approach(current.rotationXDegrees, target.rotationXDegrees, amount),
            rotationYDegrees = approach(current.rotationYDegrees, target.rotationYDegrees, amount),
            rotationZDegrees = approach(current.rotationZDegrees, target.rotationZDegrees, amount),
            scale = max(1f, approach(current.scale, target.scale, amount))
        )
        return current
    }

    fun reset() {
        current = RendererMotionTransform.IDENTITY
        lastTimestampNanos = 0L
        initialized = false
    }

    private fun approach(currentValue: Float, targetValue: Float, amount: Float): Float =
        currentValue + (targetValue - currentValue) * amount
}
