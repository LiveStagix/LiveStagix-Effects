package com.livestagix.renderer.api

enum class RendererUpscaleBackendPreference {
    AUTO,
    SA,
    RCAS,
    GE,
    LS
}

/** Algorithm decision reported by the shared Smart Upscale policy. */
enum class RendererUpscaleAlgorithm {
    NONE,
    SPATIAL_ADAPTIVE,
    RCAS_SHARPEN,
    GENERIC_ENHANCE,
    LINEAR
}

/**
 * Rendering preferences shared by Vulkan, OpenGL ES 3.0, and OpenGL ES 2.0.
 * These are requested settings, not promises that every backend supports each feature.
 */
data class RendererSettings(
    val targetFps: Int = 60,
    val frameGenerationEnabled: Boolean = false,
    val frameGenerationStrength: Int = 50,
    val smartUpscaleEnabled: Boolean = true,
    val smartUpscaleQuality: Int = 100,
    val smartUpscaleBackend: RendererUpscaleBackendPreference =
        RendererUpscaleBackendPreference.AUTO,
    val ambientBackdropBlurEnabled: Boolean = false
) {
    init {
        require(targetFps in 1..240) { "Target FPS must be between 1 and 240" }
        require(frameGenerationStrength in 0..100) {
            "Frame-generation strength must be between 0 and 100"
        }
        require(smartUpscaleQuality in 0..100) {
            "Smart Upscale quality must be between 0 and 100"
        }
    }

    companion object {
        val DEFAULT = RendererSettings()
    }
}
