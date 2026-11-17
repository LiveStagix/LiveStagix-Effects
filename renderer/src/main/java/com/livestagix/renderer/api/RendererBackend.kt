package com.livestagix.renderer.api

import android.content.Context

/** Creates renderer sessions without exposing backend-specific handles to the host app. */
interface RendererBackend {
    val type: RendererType

    fun setLogListener(listener: RendererLogListener?)

    /**
     * Inspects device support without creating a rendering session.
     * Backends must return NotChecked rather than guessing when reliable
     * capability information is not available.
     */
    fun inspectSupport(context: Context): RendererSupportResult =
        RendererSupportResult.NotChecked

    /**
     * Creates one session. Failures should be reported as RendererException
     * with a meaningful category whenever the backend can classify them.
     */
    fun createSession(request: RendererCreateRequest): RendererSession
}
