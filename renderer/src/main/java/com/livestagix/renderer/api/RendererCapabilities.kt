package com.livestagix.renderer.api

/**
 * Structured capabilities reported by a backend after device inspection.
 *
 * Nullable identity/version fields mean that the backend has not exposed that
 * information yet. Extension names remain backend-specific diagnostic data.
 */
data class RendererCapabilities(
    val backendType: RendererType,
    val deviceName: String? = null,
    val apiVersion: String? = null,
    val driverVersion: String? = null,
    val extensions: Set<String> = emptySet(),
    val features: Set<RendererFeature> = emptySet(),
    val maxTextureSize: Int? = null
)
