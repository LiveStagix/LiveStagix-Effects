package com.livestagix.renderer.api

/** Receives terminal failures that occur after a renderer session has started. */
fun interface RendererFailureListener {
    fun onFailure(failure: RendererFailure)
}
