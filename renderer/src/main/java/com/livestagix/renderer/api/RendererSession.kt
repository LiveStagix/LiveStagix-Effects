package com.livestagix.renderer.api

/**
 * One renderer instance attached to one Android surface.
 * A session owns its backend resources and must be closed when the surface is destroyed.
 */
interface RendererSession : AutoCloseable {
    val backendType: RendererType

    fun resize(width: Int, height: Int)

    fun setPaused(paused: Boolean)

    override fun close()
}