package com.livestagix.renderer.api

/**
 * Renderer session that accepts wallpaper content and shared rendering state.
 *
 * Implementations must serialize scene, motion, and settings changes with the backend's
 * rendering work. These methods may be called from host threads; they must not perform
 * blocking decode or GPU work on the calling thread.
 *
 * Scene updates must retain call order. Motion samples are latest-state input and may be
 * coalesced so a burst of sensor updates does not force one rendered frame per sample.
 * Implementations must snapshot collection-backed scene values before queuing them.
 */
interface RendererContentSession : RendererSession {
    /** Replaces the desired media/effect scene. */
    fun setScene(scene: RendererScene)

    /** Supplies the latest filtered motion sample and its configuration. */
    fun updateMotion(motion: RendererMotion)

    /** Replaces the current rendering preferences. */
    fun updateSettings(settings: RendererSettings)
}
