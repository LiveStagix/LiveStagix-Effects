package com.livestagix.renderer.api

/**
 * One renderer instance attached to one Android surface.
 * A session owns its backend resources and must be closed when the surface is destroyed.
 */
interface RendererSession : AutoCloseable {
    val backendType: RendererType

    /** Structured device capabilities, when exposed by the backend. */
    val capabilities: RendererCapabilities?

    fun resize(width: Int, height: Int)

    fun setPaused(paused: Boolean)

    override fun close()
}
