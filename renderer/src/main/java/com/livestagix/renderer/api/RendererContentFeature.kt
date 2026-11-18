package com.livestagix.renderer.api

/**
 * High-level rendering features, separate from RendererFeature's low-level GPU capabilities.
 * A backend must only advertise a content feature after its full path is implemented and verified.
 */
enum class RendererContentFeature {
    PHOTO,
    VIDEO,
    MOTION_2D,
    MOTION_3D,
    MOTION_ROTATION,
    STANDALONE_EFFECTS,
    OVERLAY_EFFECTS,
    FRAME_GENERATION,
    SMART_UPSCALE,
    AMBIENT_BACKDROP_BLUR,
    HARDWARE_BUFFER_VIDEO
}
