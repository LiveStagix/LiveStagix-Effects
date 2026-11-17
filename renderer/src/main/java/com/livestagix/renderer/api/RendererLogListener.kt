package com.livestagix.renderer.api

/** Receives diagnostic messages emitted by a renderer backend. */
fun interface RendererLogListener {
    fun onLog(level: String, message: String)
}