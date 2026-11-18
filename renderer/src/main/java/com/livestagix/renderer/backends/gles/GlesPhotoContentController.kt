package com.livestagix.renderer.backends.gles

import android.graphics.Bitmap
import com.livestagix.renderer.api.RendererEvent
import com.livestagix.renderer.api.RendererFailure
import com.livestagix.renderer.api.RendererFailureCategory
import com.livestagix.renderer.api.RendererMedia
import com.livestagix.renderer.api.RendererMediaAccess
import com.livestagix.renderer.api.RendererMediaReleaseListener
import com.livestagix.renderer.api.RendererMediaType
import com.livestagix.renderer.api.RendererScene
import com.livestagix.renderer.pipeline.RendererPhotoDecoder

/**
 * EGL-thread-owned scene state for the GLES photo path.
 *
 * CPU decode runs on worker threads. Every texture upload, replacement and deletion is
 * serialized back onto the owning EGL thread. Old-media acknowledgements wait until any decode
 * still reading that media has finished and its previous GL texture has been released.
 */
internal class GlesPhotoContentController(
    private val mediaAccess: RendererMediaAccess?,
    private val maxTextureSize: () -> Int,
    private val enqueueRenderCommand: ((() -> Unit) -> Boolean),
    private val uploadBitmap: (Bitmap) -> Int,
    private val deleteTexture: (Int) -> Unit,
    private val dispatchEvent: (RendererEvent) -> Unit,
    private val dispatchMediaReleased: (RendererMedia, RendererMediaReleaseListener?) -> Unit,
    private val log: (String, String) -> Unit
) : AutoCloseable {
    private val decoder = RendererPhotoDecoder()
    private val decodeLock = Any()
    private val inFlightDecodes = mutableMapOf<RendererMedia, Int>()
    private val pendingReleases =
        mutableMapOf<RendererMedia, MutableList<RendererMediaReleaseListener?>>()

    private var scene = RendererScene.EMPTY
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var photoTexture = 0
    private var decodeGeneration = 0L
    private var closed = false

    val textureId: Int
        get() = photoTexture

    val currentMedia: RendererMedia?
        get() = scene.media

    fun resize(width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height
        if (scene.media != null) startPhotoDecode()
    }

    fun setScene(
        requestedScene: RendererScene,
        previousMediaReleased: RendererMediaReleaseListener?
    ) {
        if (closed) return
        val nextScene = requestedScene.snapshot()
        val nextMedia = nextScene.media

        val unsupportedReason = when {
            nextScene.standaloneEffect != null || nextScene.overlayEffects.isNotEmpty() ->
                "Standalone and overlay effects are not implemented by this GLES content pass"
            nextMedia?.type == RendererMediaType.VIDEO ->
                "Video playback is not implemented by this GLES content pass yet"
            nextMedia != null && mediaAccess == null ->
                "A RendererMediaAccess provider is required to load wallpaper photos"
            else -> null
        }
        if (unsupportedReason != null) {
            reportFailure(
                unsupportedReason,
                "GLES_PHOTO_SCENE_UNSUPPORTED",
                RendererFailureCategory.MISSING_REQUIRED_FEATURE
            )
            return
        }

        if (nextScene == scene) return

        val previousMedia = scene.media
        scene = nextScene
        decodeGeneration++

        if (previousMedia != nextMedia) {
            val previousTexture = photoTexture
            photoTexture = 0
            deleteTexture(previousTexture)
            if (previousMedia != null) {
                releaseMediaWhenSafe(previousMedia, previousMediaReleased)
            }
        }

        if (nextMedia != null) {
            startPhotoDecode()
        }
    }

    private fun startPhotoDecode() {
        val media = scene.media ?: return
        val access = mediaAccess ?: return
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0 || closed) return

        val generation = ++decodeGeneration
        synchronized(decodeLock) {
            inFlightDecodes[media] = (inFlightDecodes[media] ?: 0) + 1
        }

        val worker = Thread({
            var decoded: com.livestagix.renderer.pipeline.RendererDecodedPhoto? = null
            var decodeFailure: Throwable? = null
            try {
                decoded = decoder.decode(
                    media = media,
                    mediaAccess = access,
                    targetWidth = width,
                    targetHeight = height,
                    maxTextureSize = maxTextureSize().coerceAtLeast(1)
                )
            } catch (error: Throwable) {
                decodeFailure = error
            } finally {
                finishDecode(media)
            }

            val result = decoded
            val failure = decodeFailure
            val accepted = enqueueRenderCommand {
                val stale = closed || generation != decodeGeneration || scene.media != media
                if (stale) {
                    result?.close()
                } else if (failure != null || result == null) {
                    result?.close()
                    reportFailure(
                        "Could not decode wallpaper photo: " +
                            (failure?.message ?: "decoder returned no bitmap"),
                        "GLES_PHOTO_DECODE_FAILED",
                        RendererFailureCategory.RESOURCE_UNAVAILABLE
                    )
                } else {
                    try {
                        val replacement = uploadBitmap(result.bitmap)
                        check(replacement != 0) { "Photo texture upload returned texture 0" }
                        val previousTexture = photoTexture
                        photoTexture = replacement
                        deleteTexture(previousTexture)
                        log(
                            "INFO",
                            "Photo ready: ${result.bitmap.width}x${result.bitmap.height}, " +
                                "sample=${result.sampleSize}, regionDecode=${result.usedRegionDecode}"
                        )
                    } catch (error: Throwable) {
                        reportFailure(
                            "Could not upload wallpaper photo: " +
                                (error.message ?: error.javaClass.simpleName),
                            "GLES_PHOTO_UPLOAD_FAILED",
                            RendererFailureCategory.RESOURCE_UNAVAILABLE
                        )
                    } finally {
                        result.close()
                    }
                }
            }

            if (!accepted) result?.close()
        }, "LiveStagix-GLES-PhotoDecode")
        worker.isDaemon = true
        worker.start()
    }

    private fun finishDecode(media: RendererMedia) {
        val releases = synchronized(decodeLock) {
            val remaining = (inFlightDecodes[media] ?: 1) - 1
            if (remaining <= 0) {
                inFlightDecodes.remove(media)
                pendingReleases.remove(media)
            } else {
                inFlightDecodes[media] = remaining
                null
            }
        }
        releases?.forEach { listener -> dispatchMediaReleased(media, listener) }
    }

    private fun releaseMediaWhenSafe(
        media: RendererMedia,
        listener: RendererMediaReleaseListener?
    ) {
        val releaseNow = synchronized(decodeLock) {
            if ((inFlightDecodes[media] ?: 0) > 0) {
                pendingReleases.getOrPut(media) { mutableListOf() }.add(listener)
                false
            } else {
                true
            }
        }
        if (releaseNow) dispatchMediaReleased(media, listener)
    }

    private fun reportFailure(
        message: String,
        code: String,
        category: RendererFailureCategory
    ) {
        dispatchEvent(
            RendererEvent.Failure(
                RendererFailure(
                    category = category,
                    message = message,
                    diagnosticCode = code
                )
            )
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        decodeGeneration++
        val previousTexture = photoTexture
        photoTexture = 0
        deleteTexture(previousTexture)
        scene = RendererScene.EMPTY
    }
}
