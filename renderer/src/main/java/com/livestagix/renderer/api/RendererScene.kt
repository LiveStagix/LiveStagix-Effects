package com.livestagix.renderer.api

import android.net.Uri

/** How a media URI should be decoded when its content type is not self-evident. */
enum class RendererMediaType {
    AUTO,
    PHOTO,
    VIDEO
}

/**
 * Normalized crop offsets for a media source.
 *
 * Both values use the existing LiveWP convention: -1 moves toward the leading edge,
 * +1 moves toward the trailing edge, and 0 keeps the crop centered.
 */
data class RendererCropPosition(
    val horizontal: Float = 0f,
    val vertical: Float = 0f
) {
    init {
        require(horizontal.isFinite() && horizontal in -1f..1f) {
            "Horizontal crop offset must be finite and between -1 and 1"
        }
        require(vertical.isFinite() && vertical in -1f..1f) {
            "Vertical crop offset must be finite and between -1 and 1"
        }
    }
}

/** A photo or video input shared by all rendering backends. */
data class RendererMedia(
    val uri: Uri,
    val type: RendererMediaType = RendererMediaType.AUTO,
    val orientationDegrees: Int = 0,
    val crop: RendererCropPosition = RendererCropPosition(),
    val looping: Boolean = true,
    val audioEnabled: Boolean = false
) {
    init {
        require(uri.toString().isNotBlank()) { "Media URI must not be empty" }
        require(orientationDegrees in setOf(0, 90, 180, 270)) {
            "Media orientation must be 0, 90, 180, or 270 degrees"
        }
    }
}

/**
 * Backend-neutral effect description.
 *
 * Parameters are effect-specific scalar values. Backends may report an unsupported
 * effect or parameter through their normal renderer failure/diagnostic mechanisms.
 */
data class RendererEffect(
    val id: String,
    val speedPercent: Int = 50,
    val intensityPercent: Int = 50,
    val gyroEnabled: Boolean = false,
    val inward: Boolean = false,
    val animationEnabled: Boolean = true,
    val parameters: Map<String, Float> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "Effect ID must not be blank" }
        require(speedPercent in 0..100) { "Effect speed must be between 0 and 100" }
        require(intensityPercent in 0..100) { "Effect intensity must be between 0 and 100" }
        require(parameters.keys.all { it.isNotBlank() }) {
            "Effect parameter names must not be blank"
        }
        require(parameters.values.all { it.isFinite() }) {
            "Effect parameter values must be finite"
        }
    }
}

/**
 * Describes the complete desired wallpaper scene.
 *
 * Media, a standalone effect, and overlay effects can coexist so a backend can support
 * media-backed scenes as well as standalone animated wallpapers. A null media/effect
 * represents an intentionally empty scene.
 */
data class RendererScene(
    val media: RendererMedia? = null,
    val standaloneEffect: RendererEffect? = null,
    val overlayEffects: List<RendererEffect> = emptyList()
) {
    companion object {
        val EMPTY = RendererScene()
    }
}
