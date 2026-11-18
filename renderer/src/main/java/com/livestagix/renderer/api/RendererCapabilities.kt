package com.livestagix.renderer.api

/**
 * Structured capabilities reported by a backend after device inspection.
 *
 * features contains low-level GPU/API capabilities. contentFeatures contains high-level
 * renderer features that are actually implemented end-to-end by this backend.
 * Empty contentFeatures means no media/effect feature has been verified yet.
 */
data class RendererCapabilities(
    val backendType: RendererType,
    val deviceName: String? = null,
    val apiVersion: String? = null,
    val driverVersion: String? = null,
    val extensions: Set<String> = emptySet(),
    val features: Set<RendererFeature> = emptySet(),
    val maxTextureSize: Int? = null,
    val contentFeatures: Set<RendererContentFeature> = emptySet()
)
