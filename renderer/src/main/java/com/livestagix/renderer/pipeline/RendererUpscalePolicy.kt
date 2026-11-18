package com.livestagix.renderer.pipeline

import com.livestagix.renderer.api.RendererSettings
import com.livestagix.renderer.api.RendererUpscaleAlgorithm
import com.livestagix.renderer.api.RendererUpscaleBackendPreference

data class RendererUpscaleDecision(
    val active: Boolean,
    val algorithm: RendererUpscaleAlgorithm,
    val strength: Float,
    val scaleX: Float,
    val scaleY: Float,
    val reason: String
)

/**
 * Shared Smart Upscale selection policy. This does not execute shaders:
 * each backend must provide and verify its own scaling/sharpening passes.
 */
object RendererUpscalePolicy {
    fun evaluate(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        settings: RendererSettings
    ): RendererUpscaleDecision {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(outputWidth > 0 && outputHeight > 0)

        val scaleX = outputWidth.toFloat() / sourceWidth
        val scaleY = outputHeight.toFloat() / sourceHeight
        val needsUpscale = sourceWidth < outputWidth || sourceHeight < outputHeight

        if (!settings.smartUpscaleEnabled || !needsUpscale || settings.smartUpscaleQuality == 0) {
            val reason = when {
                !settings.smartUpscaleEnabled -> "Smart Upscale is disabled"
                !needsUpscale -> "Source does not need upscaling"
                else -> "Requested quality is zero"
            }
            return RendererUpscaleDecision(
                active = false,
                algorithm = RendererUpscaleAlgorithm.NONE,
                strength = 0f,
                scaleX = scaleX,
                scaleY = scaleY,
                reason = reason
            )
        }

        val algorithm = when (settings.smartUpscaleBackend) {
            RendererUpscaleBackendPreference.AUTO ->
                if (settings.smartUpscaleQuality >= 70) {
                    RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE
                } else {
                    RendererUpscaleAlgorithm.LINEAR
                }
            RendererUpscaleBackendPreference.SA -> RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE
            RendererUpscaleBackendPreference.RCAS -> RendererUpscaleAlgorithm.RCAS_SHARPEN
            RendererUpscaleBackendPreference.GE -> RendererUpscaleAlgorithm.GENERIC_ENHANCE
            RendererUpscaleBackendPreference.LS -> RendererUpscaleAlgorithm.LINEAR
        }

        return RendererUpscaleDecision(
            active = true,
            algorithm = algorithm,
            strength = settings.smartUpscaleQuality / 100f,
            scaleX = scaleX,
            scaleY = scaleY,
            reason = "Source is smaller than the output and Smart Upscale is enabled"
        )
    }
}
