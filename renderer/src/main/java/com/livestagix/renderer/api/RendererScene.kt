package com.livestagix.renderer.api

import android.net.Uri

enum class RendererMediaType {
    AUTO,
    PHOTO,
    VIDEO
}

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

    fun snapshot(): RendererEffect = copy(parameters = parameters.toMap())
}

/** Complete desired wallpaper scene; media and effects may coexist. */
data class RendererScene(
    val media: RendererMedia? = null,
    val standaloneEffect: RendererEffect? = null,
    val overlayEffects: List<RendererEffect> = emptyList()
) {
    /** Creates a defensive snapshot for asynchronous backend queues. */
    fun snapshot(): RendererScene = copy(
        media = media?.copy(crop = media.crop.copy()),
        standaloneEffect = standaloneEffect?.snapshot(),
        overlayEffects = overlayEffects.map { it.snapshot() }
    )

    companion object {
        val EMPTY = RendererScene()
    }
}
