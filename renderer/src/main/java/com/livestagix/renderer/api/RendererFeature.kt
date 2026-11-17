package com.livestagix.renderer.api

/** Backend capabilities that can be compared without depending on a graphics API. */
enum class RendererFeature {
    GPU_TIMESTAMP_QUERIES,
    COMPUTE_SHADERS,
    EXTERNAL_IMAGE_IMPORT,
    HARDWARE_BUFFER_IMPORT,
    WIDE_COLOR_OUTPUT
}
