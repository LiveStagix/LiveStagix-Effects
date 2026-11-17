package com.livestagix.renderer.api

import android.content.res.AssetManager
import android.view.Surface

/** Backend-neutral options applied when a renderer session is created. */
data class RendererCreateConfig(
    /** Start paused when the wallpaper surface is not currently visible. */
    val initiallyPaused: Boolean = false
)

/** Inputs required to create one renderer session for an Android surface. */
data class RendererCreateRequest(
    val surface: Surface,
    val assets: AssetManager,
    val config: RendererCreateConfig = RendererCreateConfig()
)
