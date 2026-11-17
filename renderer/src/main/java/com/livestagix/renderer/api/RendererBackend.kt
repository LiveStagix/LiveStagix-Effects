package com.livestagix.renderer.api

import android.content.res.AssetManager
import android.view.Surface

/** Creates renderer sessions without exposing backend-specific handles to the host app. */
interface RendererBackend {
    val type: RendererType

    fun setLogListener(listener: RendererLogListener?)

    fun createSession(surface: Surface, assets: AssetManager): RendererSession
}