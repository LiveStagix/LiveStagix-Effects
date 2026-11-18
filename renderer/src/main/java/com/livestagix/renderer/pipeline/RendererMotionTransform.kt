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
        require(listOf(
            translationX, translationY, translationZ,
            rotationXDegrees, rotationYDegrees, rotationZDegrees, scale
        ).all(Float::isFinite))
        require(scale >= 1f) { "Wallpaper edge-safe scale must be at least 1" }
    }

    companion object {
        val IDENTITY = RendererMotionTransform()
    }
}

/** Backend-neutral port of LiveWP's motion target calculations. */
object RendererMotionTransformCalculator {
    fun calculate(
        motion: RendererMotion,
        viewportWidth: Int,
        viewportHeight: Int,
        isVideo: Boolean = false
    ): RendererMotionTransform {
        if (isVideo) return RendererMotionTransform.IDENTITY

        val c = motion.configuration
        val width = viewportWidth.coerceAtLeast(1).toFloat()
        val height = viewportHeight.coerceAtLeast(1).toFloat()
        val stabilizationOnly = !c.enabled
        val sx = (c.sensitivity2dX / 25f).coerceIn(0f, 4f)
        val sy = (c.sensitivity2dY / 25f).coerceIn(0f, 4f)
        val s3x = (c.sensitivity3dX / 25f).coerceIn(0f, 4f)
        val s3y = (c.sensitivity3dY / 25f).coerceIn(0f, 4f)
        val sd = (c.depthSensitivity / 25f).coerceIn(0f, 4f)

        var tx = 0f
        var ty = 0f
        var rx = 0f
        var ry = 0f
        var tz = 0f
        var rz = 0f
        var scale = 1f

        if (!stabilizationOnly && c.rotationEnabled) {
            val rotationScale = (c.rotationSensitivity / 25f).coerceIn(0f, 4f)
            val direction = if (c.rotationInverted) -1f else 1f
            rz = (motion.rotationDegrees * rotationScale * direction).coerceIn(-20f, 20f)
        }
        if (!stabilizationOnly && c.twoDimensionalEnabled) {
            val inversion = if (c.twoDimensionalInverted) -1f else 1f
            val roll = tanh((motion.rollDegrees / 18f) * sx) * inversion
            val pitch = tanh((motion.pitchDegrees / 18f) * sy) * inversion
            tx = roll * min(width * 0.10f, max(0f, width * 0.08f * sx * 0.72f))
            ty = pitch * min(height * 0.10f, max(0f, height * 0.08f * sy * 0.72f))
        }

        val tilt = min(
            45f,
            sqrt(motion.rollDegrees * motion.rollDegrees + motion.pitchDegrees * motion.pitchDegrees)
        )
        if (!stabilizationOnly && c.threeDimensionalEnabled) {
            val inversion = if (c.threeDimensionalInverted) -1f else 1f
            rx = (-tanh((motion.pitchDegrees / 20f) * s3y) * 14f * s3y * inversion)
                .coerceIn(-14f, 14f)
            ry = (tanh((motion.rollDegrees / 20f) * s3x) * 14f * s3x * inversion)
                .coerceIn(-14f, 14f)
            tz = tanh((tilt / 35f) * sd) * 12f * sd * inversion
        }

        if (c.edgeSafetyEnabled && !stabilizationOnly) {
            scale = when {
                c.threeDimensionalEnabled -> {
                    val depthZoom = 1f + min(1f, tilt / 45f) * 0.08f * sd
                    val radX = Math.toRadians(abs(rx).toDouble()).toFloat()
                    val radY = Math.toRadians(abs(ry).toDouble()).toFloat()
                    val fx = max(0.65f, cos(radY) - ((width * 0.28f) / 900f) * sin(radY))
                    val fy = max(0.65f, cos(radX) - ((height * 0.28f) / 900f) * sin(radX))
                    val panX = 1f + 2f * abs(tx) / width
                    val panY = 1f + 2f * abs(ty) / height
                    val corner = 1f + radX * 0.18f + radY * 0.18f
                    val safe = max((1f / fx) * panX, max((1f / fy) * panY, corner * max(panX, panY)))
                    max(1f + 0.12f * min(1f, tilt / 30f), max(safe, depthZoom))
                }
                c.twoDimensionalEnabled ->
                    max(1f + 2f * abs(tx) / width, 1f + 2f * abs(ty) / height) * 1.015f
                else -> rotationCoverage(rz, width, height)
            }
        }

        return RendererMotionTransform(tx, ty, tz, rx, ry, rz, max(1f, scale))
    }

    internal fun rotationCoverage(rotationDegrees: Float, width: Float, height: Float): Float {
        val angle = Math.toRadians(abs(rotationDegrees).toDouble()).toFloat()
        val c = abs(cos(angle))
        val s = abs(sin(angle))
        val aspect = width / max(1f, height)
        return max(c + s / max(0.2f, aspect), c + s * aspect)
    }
}

/**
 * Stateful port of LiveWP's per-axis auto-stabilisation, exponential smoothing and
 * Advanced Motion rate limits. Call update at the renderer's frame cadence.
 */
class RendererMotionTransformSmoother {
    private var current = RendererMotionTransform.IDENTITY
    private var lastTimestampNanos = 0L

    fun update(
        motion: RendererMotion,
        viewportWidth: Int,
        viewportHeight: Int,
        isVideo: Boolean = false,
        timestampNanos: Long = if (motion.timestampNanos > 0L) motion.timestampNanos else System.nanoTime()
    ): RendererMotionTransform {
        if (isVideo) return RendererMotionTransform.IDENTITY

        val c = motion.configuration
        val width = viewportWidth.coerceAtLeast(1).toFloat()
        val height = viewportHeight.coerceAtLeast(1).toFloat()
        val target = RendererMotionTransformCalculator.calculate(motion, viewportWidth, viewportHeight)
        val dt = if (lastTimestampNanos == 0L) {
            0.001f
        } else {
            ((timestampNanos - lastTimestampNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastTimestampNanos = timestampNanos

        fun response(value: Int, minimum: Float = 0.35f) =
            max(minimum, 2.5f - (value / 100f) * 2f)
        fun smoothing(seconds: Float) = 1f - exp(-dt / seconds)

        val k2dX = smoothing(response(c.autoStabilize2dX))
        val k2dY = smoothing(response(c.autoStabilize2dY))
        val k3dX = smoothing(response(c.autoStabilize3dX))
        val k3dY = smoothing(response(c.autoStabilize3dY))
        val kZoom = smoothing(response(c.autoStabilizeZoom))
        val kRotation = smoothing(response(c.autoStabilizeRotation, 0.5f))

        var targetX = target.translationX
        var targetY = target.translationY
        var targetRx = target.rotationXDegrees
        var targetRy = target.rotationYDegrees
        var targetZ = target.translationZ
        val targetRz = target.rotationZDegrees
        var targetScale = target.scale

        if (c.autoStabilize2dX > 0) targetX -= targetX * k2dX
        if (c.autoStabilize2dY > 0) targetY -= targetY * k2dY
        if (c.autoStabilize3dY > 0) targetRx -= targetRx * k3dY
        if (c.autoStabilize3dX > 0) targetRy -= targetRy * k3dX
        if (c.autoStabilize3dX > 0 || c.autoStabilize3dY > 0) {
            targetZ -= targetZ * max(k3dX, k3dY)
        }
        if (c.autoStabilizeZoom > 0) targetScale += (1f - targetScale) * kZoom

        var tx = current.translationX + (targetX - current.translationX) * k2dX
        var ty = current.translationY + (targetY - current.translationY) * k2dY
        var rx = current.rotationXDegrees + (targetRx - current.rotationXDegrees) * k3dY
        var ry = current.rotationYDegrees + (targetRy - current.rotationYDegrees) * k3dX
        var tz = current.translationZ + (targetZ - current.translationZ) * max(k3dX, k3dY)
        var rz = current.rotationZDegrees + (targetRz - current.rotationZDegrees) * kRotation

        var txNorm = 2f * tx / width
        var targetTxNorm = 2f * targetX / width
        var tyNorm = 2f * ty / height
        var targetTyNorm = 2f * targetY / height
        txNorm = rateLimitedStep(txNorm, targetTxNorm, c.translationRate, dt)
        tyNorm = rateLimitedStep(tyNorm, targetTyNorm, c.translationRate, dt)
        tx = txNorm * width / 2f
        ty = tyNorm * height / 2f
        rx = rateLimitedStep(rx, targetRx, c.rotationRateDegreesPerSecond, dt)
        ry = rateLimitedStep(ry, targetRy, c.rotationRateDegreesPerSecond, dt)
        tz = rateLimitedStep(tz, targetZ, c.depthRate, dt)
        rz = rateLimitedStep(rz, targetRz, c.rotationRateZDegreesPerSecond, dt)

        var scale = current.scale
        if (c.edgeSafetyEnabled && c.enabled) {
            val safetyScale = when {
                c.threeDimensionalEnabled -> {
                    val tilt = min(
                        45f,
                        sqrt(motion.rollDegrees * motion.rollDegrees + motion.pitchDegrees * motion.pitchDegrees)
                    )
                    val radX = Math.toRadians(abs(rx).toDouble()).toFloat()
                    val radY = Math.toRadians(abs(ry).toDouble()).toFloat()
                    val fx = max(0.65f, cos(radY) - ((width * 0.28f) / 900f) * sin(radY))
                    val fy = max(0.65f, cos(radX) - ((height * 0.28f) / 900f) * sin(radX))
                    val panX = 1f + 2f * abs(tx) / width
                    val panY = 1f + 2f * abs(ty) / height
                    val corner = 1f + radX * 0.18f + radY * 0.18f
                    val perspective = max(
                        (1f / fx) * panX,
                        max((1f / fy) * panY, corner * max(panX, panY))
                    ) * 1.02f
                    val depthZoom = 1f + min(1f, tilt / 45f) * 0.08f *
                        (c.depthSensitivity / 25f).coerceIn(0f, 4f)
                    max(
                        max(perspective, 1f + 0.12f * min(1f, tilt / 30f)),
                        max(depthZoom, RendererMotionTransformCalculator.rotationCoverage(rz, width, height))
                    )
                }
                c.twoDimensionalEnabled -> max(
                    max(1f + 2f * abs(tx) / width, 1f + 2f * abs(ty) / height) * 1.015f,
                    abs(cos(Math.toRadians(abs(rz).toDouble()).toFloat())) +
                        abs(sin(Math.toRadians(abs(rz).toDouble()).toFloat()))
                )
                else -> RendererMotionTransformCalculator.rotationCoverage(rz, width, height).coerceAtLeast(1f)
            }
            targetScale = max(targetScale, safetyScale)
            scale = max(scale, safetyScale)
        }

        scale += (targetScale - scale) * (kZoom * c.zoomResponseFactor)
        if (abs(tx) < 0.2f) tx = 0f
        if (abs(ty) < 0.2f) ty = 0f
        if (abs(rx) < 0.1f) rx = 0f
        if (abs(ry) < 0.1f) ry = 0f
        if (abs(rz) < 0.05f) rz = 0f
        if (abs(scale - 1f) < 0.003f) scale = 1f

        current = RendererMotionTransform(tx, ty, tz, rx, ry, rz, max(1f, scale))
        return current
    }

    /** Compatibility overload for callers supplying a pre-calculated transform. */
    fun update(
        target: RendererMotionTransform,
        timestampNanos: Long = System.nanoTime(),
        responseSeconds: Float = 0.8f
    ): RendererMotionTransform {
        require(responseSeconds.isFinite() && responseSeconds > 0f)
        val dt = if (lastTimestampNanos == 0L) {
            0.001f
        } else {
            ((timestampNanos - lastTimestampNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastTimestampNanos = timestampNanos
        val amount = 1f - exp(-dt / responseSeconds)
        current = RendererMotionTransform(
            approach(current.translationX, target.translationX, amount),
            approach(current.translationY, target.translationY, amount),
            approach(current.translationZ, target.translationZ, amount),
            approach(current.rotationXDegrees, target.rotationXDegrees, amount),
            approach(current.rotationYDegrees, target.rotationYDegrees, amount),
            approach(current.rotationZDegrees, target.rotationZDegrees, amount),
            max(1f, approach(current.scale, target.scale, amount))
        )
        return current
    }

    fun reset() {
        current = RendererMotionTransform.IDENTITY
        lastTimestampNanos = 0L
    }

    private fun rateLimitedStep(currentValue: Float, targetValue: Float, maxRate: Float, dt: Float): Float {
        val maxStep = maxRate * dt
        val delta = targetValue - currentValue
        return when {
            delta > maxStep -> currentValue + maxStep
            delta < -maxStep -> currentValue - maxStep
            else -> targetValue
        }
    }

    private fun approach(currentValue: Float, targetValue: Float, amount: Float): Float =
        currentValue + (targetValue - currentValue) * amount
}