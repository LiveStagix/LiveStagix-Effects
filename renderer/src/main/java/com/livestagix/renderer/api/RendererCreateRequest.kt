package com.livestagix.renderer.api

import android.content.res.AssetManager
import android.view.Surface

/** Backend-neutral options applied when a renderer session is created. */
data class RendererCreateConfig(
    /** Start paused when the wallpaper surface is not currently visible. */
    val initiallyPaused: Boolean = false
)

/** Device and runtime information supplied by the host, without exposing its Context. */
data class RendererEnvironment(
    val apiLevel: Int = 0,
    val manufacturer: String? = null,
    val deviceModel: String? = null,
    val totalMemoryBytes: Long? = null,
    val displayRefreshRateHz: Float? = null,
    val thermalStatus: Int? = null
) {
    init {
        require(apiLevel >= 0)
        require(totalMemoryBytes == null || totalMemoryBytes > 0L)
        require(displayRefreshRateHz == null ||
            displayRefreshRateHz.isFinite() && displayRefreshRateHz > 0f)
    }
}

/** Inputs required to create one renderer session for an Android surface. */
data class RendererCreateRequest(
    val surface: Surface,
    val assets: AssetManager,
    val config: RendererCreateConfig = RendererCreateConfig(),
    /** Host adapter for content URIs, files and native decoder descriptors. */
    val mediaAccess: RendererMediaAccess? = null,
    /** Host-observed device state used by portable renderer policies. */
    val environment: RendererEnvironment = RendererEnvironment()
)
