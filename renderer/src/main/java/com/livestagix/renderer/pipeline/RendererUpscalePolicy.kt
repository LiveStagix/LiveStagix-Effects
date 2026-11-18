package com.livestagix.renderer.pipeline

import com.livestagix.renderer.api.RendererSettings
import com.livestagix.renderer.api.RendererUpscaleAlgorithm
import com.livestagix.renderer.api.RendererUpscaleBackendPreference

/** Device information observed by the selected backend. */
data class RendererUpscaleDeviceProfile(
    val gpuRenderer: String = "",
    val supportsGlslEs31: Boolean = false
)

/** The backend supplies the set of pipelines it actually compiled. */
data class RendererUpscaleDecision(
    val active: Boolean,
    val algorithm: RendererUpscaleAlgorithm,
    val requestedAlgorithm: RendererUpscaleAlgorithm,
    val strength: Float,
    val scaleX: Float,
    val scaleY: Float,
    val fallbackUsed: Boolean,
    val reason: String
)

/**
 * Ports LiveWP's device-aware Smart Upscale selection and fallback order.
 * This policy does not compile or execute shaders. An empty availableAlgorithms set safely
 * disables SU until the backend reports which shader paths are ready.
 */
object RendererUpscalePolicy {
    fun evaluate(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        settings: RendererSettings,
        device: RendererUpscaleDeviceProfile = RendererUpscaleDeviceProfile(),
        availableAlgorithms: Set<RendererUpscaleAlgorithm> = emptySet()
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
            return inactive(scaleX, scaleY, reason)
        }

        val requested = requestedAlgorithm(settings.smartUpscaleBackend, device.gpuRenderer)
        val selected = fallbackOrder(requested).firstOrNull { it in availableAlgorithms }
            ?: RendererUpscaleAlgorithm.NONE
        if (selected == RendererUpscaleAlgorithm.NONE) {
            return RendererUpscaleDecision(
                active = false,
                algorithm = RendererUpscaleAlgorithm.NONE,
                requestedAlgorithm = requested,
                strength = 0f,
                scaleX = scaleX,
                scaleY = scaleY,
                fallbackUsed = false,
                reason = "No compatible Smart Upscale pipeline is available"
            )
        }

        val fallbackUsed = selected != requested
        return RendererUpscaleDecision(
            active = true,
            algorithm = selected,
            requestedAlgorithm = requested,
            strength = settings.smartUpscaleQuality / 100f,
            scaleX = scaleX,
            scaleY = scaleY,
            fallbackUsed = fallbackUsed,
            reason = if (fallbackUsed) {
                "Requested $requested is unavailable; using $selected"
            } else {
                "Selected $selected for the requested device and quality settings"
            }
        )
    }

    /** Same GPU-family preference used by LiveWP's VideoSmartUpscaler. */
    fun requestedAlgorithm(
        preference: RendererUpscaleBackendPreference,
        gpuRenderer: String
    ): RendererUpscaleAlgorithm = when (preference) {
        RendererUpscaleBackendPreference.SA -> RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE
        RendererUpscaleBackendPreference.RCAS -> RendererUpscaleAlgorithm.RCAS_SHARPEN
        RendererUpscaleBackendPreference.GE -> RendererUpscaleAlgorithm.GENERIC_ENHANCE
        RendererUpscaleBackendPreference.LS -> RendererUpscaleAlgorithm.LINEAR
        RendererUpscaleBackendPreference.AUTO -> {
            val renderer = gpuRenderer.lowercase()
            when {
                "adreno" in renderer -> RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE
                listOf(
                    "mali", "immortalis", "xclipse", "geforce",
                    "radeon", "apple", "powervr"
                ).any { it in renderer } -> RendererUpscaleAlgorithm.RCAS_SHARPEN
                else -> RendererUpscaleAlgorithm.GENERIC_ENHANCE
            }
        }
    }

    private fun fallbackOrder(requested: RendererUpscaleAlgorithm) = when (requested) {
        RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE -> listOf(
            RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE,
            RendererUpscaleAlgorithm.RCAS_SHARPEN,
            RendererUpscaleAlgorithm.GENERIC_ENHANCE,
            RendererUpscaleAlgorithm.LINEAR
        )
        RendererUpscaleAlgorithm.RCAS_SHARPEN -> listOf(
            RendererUpscaleAlgorithm.RCAS_SHARPEN,
            RendererUpscaleAlgorithm.GENERIC_ENHANCE,
            RendererUpscaleAlgorithm.LINEAR,
            RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE
        )
        RendererUpscaleAlgorithm.GENERIC_ENHANCE -> listOf(
            RendererUpscaleAlgorithm.GENERIC_ENHANCE,
            RendererUpscaleAlgorithm.LINEAR,
            RendererUpscaleAlgorithm.RCAS_SHARPEN,
            RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE
        )
        RendererUpscaleAlgorithm.LINEAR -> listOf(
            RendererUpscaleAlgorithm.LINEAR,
            RendererUpscaleAlgorithm.RCAS_SHARPEN,
            RendererUpscaleAlgorithm.GENERIC_ENHANCE,
            RendererUpscaleAlgorithm.SPATIAL_ADAPTIVE
        )
        RendererUpscaleAlgorithm.NONE -> emptyList()
    }

    private fun inactive(
        scaleX: Float,
        scaleY: Float,
        reason: String
    ) = RendererUpscaleDecision(
        active = false,
        algorithm = RendererUpscaleAlgorithm.NONE,
        requestedAlgorithm = RendererUpscaleAlgorithm.NONE,
        strength = 0f,
        scaleX = scaleX,
        scaleY = scaleY,
        fallbackUsed = false,
        reason = reason
    )
}