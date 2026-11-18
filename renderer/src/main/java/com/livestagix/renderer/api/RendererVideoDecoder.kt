package com.livestagix.renderer.api

import android.view.Surface

/**
 * Backend-specific Android video decoder boundary.
 *
 * GLES implementations normally provide a Surface backed by SurfaceTexture and import
 * its external image. Vulkan can provide an AImageReader/HardwareBuffer output Surface.
 * The decoder owns MediaExtractor/MediaCodec and must close descriptors from mediaAccess.
 */
interface RendererVideoDecoder : AutoCloseable {
    fun prepare(
        media: RendererMedia,
        mediaAccess: RendererMediaAccess,
        outputSurface: Surface,
        listener: RendererVideoDecoderListener
    )

    fun setPaused(paused: Boolean)
    fun setLooping(looping: Boolean)
    fun seekTo(positionMicros: Long)

    override fun close()
}

interface RendererVideoDecoderListener {
    fun onPrepared(metadata: RendererVideoMetadata)
    fun onFrameAvailable(presentationTimeNanos: Long)
    fun onFailure(failure: RendererFailure)
}
