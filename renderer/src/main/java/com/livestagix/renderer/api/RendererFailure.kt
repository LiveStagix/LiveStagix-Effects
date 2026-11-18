package com.livestagix.renderer.api

/** Describes why a backend could not be used or initialized. */
enum class RendererFailureCategory(val isPermanentForDevice: Boolean) {
    UNSUPPORTED_DEVICE(true),
    MISSING_REQUIRED_FEATURE(true),
    DRIVER_INCOMPATIBLE(true),

    BACKEND_UNAVAILABLE(false),
    SURFACE_UNAVAILABLE(false),
    RESOURCE_UNAVAILABLE(false),
    DEVICE_LOST(false),
    INITIALIZATION_FAILED(false),
    TEMPORARY_FAILURE(false),
    UNKNOWN(false)
}

data class RendererFailure(
    val category: RendererFailureCategory,
    val message: String,
    val diagnosticCode: String? = null
)

/**
 * A backend operation failed with a classified renderer error.
 * Only explicitly permanent categories may be remembered for a device.
 */
class RendererException(
    val failure: RendererFailure,
    cause: Throwable? = null
) : RuntimeException("${failure.category}: ${failure.message}", cause)
