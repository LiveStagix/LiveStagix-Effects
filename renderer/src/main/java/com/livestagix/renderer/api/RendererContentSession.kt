package com.livestagix.renderer.api

/**
 * Renderer session that accepts wallpaper content and shared rendering state.
 *
 * Implementations must serialize scene, motion, settings and playback changes with backend
 * work. Calls may originate on host threads and must never perform blocking decode or GPU
 * work on the calling thread. Collection-backed scene values must be snapshotted on receipt.
 */
interface RendererContentSession : RendererSession {
    /** Replaces the desired media/effect scene. */
    fun setScene(scene: RendererScene)

    /**
     * Replaces the scene and optionally acknowledges release of the previous media.
     * The callback is valid only after the backend has stopped referencing that previous
     * media (including decoder/GPU resources); accepting the new scene is not enough.
     */
    fun setScene(
        scene: RendererScene,
        previousMediaReleased: RendererMediaReleaseListener?
    )

    /** Supplies the latest filtered motion sample; implementations may coalesce samples. */
    fun updateMotion(motion: RendererMotion)

    /** Replaces rendering preferences. */
    fun updateSettings(settings: RendererSettings)

    /** Playback pause is separate from app battery/visibility policy. */
    fun setPlaybackPaused(paused: Boolean) = setPaused(paused)

    /** Receives typed events; listeners must not block rendering. */
    fun setEventListener(listener: RendererEventListener?)
}

fun interface RendererMediaReleaseListener {
    fun onReleased(media: RendererMedia)
}
