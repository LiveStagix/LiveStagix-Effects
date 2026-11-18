package com.livestagix.renderer.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import com.livestagix.renderer.api.RendererMedia
import com.livestagix.renderer.api.RendererMediaAccess
import com.livestagix.renderer.api.RendererMediaType
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Integer source-image rectangle. Kept independent of Android Rect for JVM testing. */
data class RendererPhotoCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top)
    }

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

/**
 * CPU-side photo preparation shared by every backend.
 *
 * Ports LiveWP's region-decode, crop, gyro overscan and bounded working-texture strategy.
 * It does not create GL/Vulkan resources. The returned bitmap is already oriented; backends
 * must not apply media.orientationDegrees a second time.
 */
class RendererPhotoDecoder {
    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    fun decode(
        media: RendererMedia,
        mediaAccess: RendererMediaAccess,
        targetWidth: Int,
        targetHeight: Int,
        maxTextureSize: Int,
        resolutionFactor: Float = DEFAULT_RESOLUTION_FACTOR,
        overscanFactor: Float = DEFAULT_OVERSCAN_FACTOR
    ): RendererDecodedPhoto {
        require(media.type != RendererMediaType.VIDEO) {
            "A video cannot be decoded as a photo"
        }
        require(targetWidth > 0 && targetHeight > 0) {
            "Target dimensions must be positive"
        }
        require(maxTextureSize > 0) { "Maximum texture size must be positive" }
        require(resolutionFactor.isFinite() && resolutionFactor in 0.1f..2f)
        require(overscanFactor.isFinite() && overscanFactor in 1f..2f)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        mediaAccess.openInputStream(media.uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The selected media is not a decodable image")
        }

        val visibleCrop = calculateVisibleCropRect(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            horizontalOffset = media.crop.horizontal,
            verticalOffset = media.crop.vertical,
            orientationDegrees = media.orientationDegrees
        )
        val workingCrop = expandPhotoCropRect(
            visibleCrop,
            bounds.outWidth,
            bounds.outHeight,
            overscanFactor
        )
        val overscanScaleX = workingCrop.width / visibleCrop.width.toFloat()
        val overscanScaleY = workingCrop.height / visibleCrop.height.toFloat()
        val quarterTurn = media.orientationDegrees == 90 || media.orientationDegrees == 270

        var workingTargetWidth =
            (if (quarterTurn) targetHeight else targetWidth) * resolutionFactor * overscanScaleX
        var workingTargetHeight =
            (if (quarterTurn) targetWidth else targetHeight) * resolutionFactor * overscanScaleY
        val textureLimitScale = min(
            1f,
            maxTextureSize / max(workingTargetWidth, workingTargetHeight)
        )
        workingTargetWidth = max(1f, workingTargetWidth * textureLimitScale)
        workingTargetHeight = max(1f, workingTargetHeight * textureLimitScale)

        var sampleSize = 1
        while (
            sampleSize < (1 shl 20) &&
            workingCrop.width / (sampleSize * 2f) >= workingTargetWidth * 0.80f &&
            workingCrop.height / (sampleSize * 2f) >= workingTargetHeight * 0.80f
        ) {
            sampleSize *= 2
        }

        var bitmap: Bitmap? = null
        var usedRegionDecode = false
        try {
            var decoder: BitmapRegionDecoder? = null
            try {
                mediaAccess.openInputStream(media.uri).use { input ->
                    decoder = BitmapRegionDecoder.newInstance(input, false)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    bitmap = decoder?.decodeRegion(workingCrop.toAndroidRect(), options)
                }
                usedRegionDecode = bitmap != null
            } catch (_: Exception) {
                bitmap?.takeUnless { it.isRecycled }?.recycle()
                bitmap = null
            } finally {
                runCatching { decoder?.recycle() }
            }

            if (bitmap == null) {
                val fallbackOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val full = mediaAccess.openInputStream(media.uri).use { input ->
                    BitmapFactory.decodeStream(input, null, fallbackOptions)
                } ?: throw IOException("Image decoding returned no bitmap")

                val safeCrop = scaleCropRect(
                    workingCrop,
                    full.width,
                    full.height,
                    full.width / bounds.outWidth.toFloat(),
                    full.height / bounds.outHeight.toFloat()
                )
                bitmap = try {
                    Bitmap.createBitmap(
                        full,
                        safeCrop.left,
                        safeCrop.top,
                        safeCrop.width,
                        safeCrop.height
                    ).also { cropped ->
                        if (cropped !== full) full.recycle()
                    }
                } catch (error: Throwable) {
                    if (!full.isRecycled) full.recycle()
                    throw error
                }
            }

            val fitted = fitPhotoWorkingTexture(
                bitmap!!,
                workingTargetWidth.roundToInt().coerceAtLeast(1),
                workingTargetHeight.roundToInt().coerceAtLeast(1)
            )
            bitmap = fitted

            if (media.orientationDegrees != 0) {
                val matrix = Matrix().apply {
                    postRotate(media.orientationDegrees.toFloat())
                }
                val oriented = Bitmap.createBitmap(
                    fitted, 0, 0, fitted.width, fitted.height, matrix, true
                )
                if (oriented !== fitted) fitted.recycle()
                bitmap = oriented
            }

            // The returned bitmap is physically rotated. Keep overscan scales aligned
            // with its output X/Y axes so GLES plane compensation matches the oriented image.
            val (outputOverscanScaleX, outputOverscanScaleY) =
                orientOverscanScales(overscanScaleX, overscanScaleY, media.orientationDegrees)

            return RendererDecodedPhoto(
                bitmap = bitmap!!,
                originalWidth = bounds.outWidth,
                originalHeight = bounds.outHeight,
                sampleSize = sampleSize,
                visibleCropWidth = visibleCrop.width,
                visibleCropHeight = visibleCrop.height,
                workingCropWidth = workingCrop.width,
                workingCropHeight = workingCrop.height,
                overscanScaleX = outputOverscanScaleX,
                overscanScaleY = outputOverscanScaleY,
                surfaceWidth = targetWidth,
                surfaceHeight = targetHeight,
                usedRegionDecode = usedRegionDecode
            )
        } catch (error: Throwable) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            throw error
        }
    }

    companion object {
        const val DEFAULT_OVERSCAN_FACTOR = 1.40f
        const val DEFAULT_RESOLUTION_FACTOR = 1.15f

        /** Remaps source-axis overscan to the output axes after physical photo orientation. */
        internal fun orientOverscanScales(
            sourceScaleX: Float,
            sourceScaleY: Float,
            orientationDegrees: Int
        ): Pair<Float, Float> {
            require(sourceScaleX.isFinite() && sourceScaleX >= 1f)
            require(sourceScaleY.isFinite() && sourceScaleY >= 1f)
            require(orientationDegrees in setOf(0, 90, 180, 270))
            return if (orientationDegrees == 90 || orientationDegrees == 270) {
                sourceScaleY to sourceScaleX
            } else {
                sourceScaleX to sourceScaleY
            }
        }

        /** Same aspect/crop-coordinate calculation used by LiveWP's sourceCropTexCoords. */
        fun calculateVisibleCropRect(
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
            horizontalOffset: Float = 0f,
            verticalOffset: Float = 0f,
            orientationDegrees: Int = 0
        ): RendererPhotoCropRect {
            require(sourceWidth > 0 && sourceHeight > 0)
            require(targetWidth > 0 && targetHeight > 0)
            require(horizontalOffset.isFinite() && horizontalOffset in -1f..1f)
            require(verticalOffset.isFinite() && verticalOffset in -1f..1f)
            require(orientationDegrees in setOf(0, 90, 180, 270))

            val sourceAspect = sourceWidth / sourceHeight.toFloat()
            var targetAspect = targetWidth / targetHeight.toFloat()
            if (orientationDegrees == 90 || orientationDegrees == 270) {
                targetAspect = 1f / targetAspect
            }

            var u0 = 0f
            var u1 = 1f
            var v0 = 0f
            var v1 = 1f
            if (sourceAspect > targetAspect) {
                val visible = targetAspect / sourceAspect
                val slack = max(0f, 1f - visible)
                u0 = ((0.5f - horizontalOffset * 0.5f) * slack).coerceIn(0f, slack)
                u1 = u0 + visible
            } else if (sourceAspect < targetAspect) {
                val visible = sourceAspect / targetAspect
                val slack = max(0f, 1f - visible)
                v0 = ((0.5f - verticalOffset * 0.5f) * slack).coerceIn(0f, slack)
                v1 = v0 + visible
            }

            val left = floor(u0 * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
            val right = ceil(u1 * sourceWidth).toInt().coerceIn(left + 1, sourceWidth)
            val top = floor((1f - v1) * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
            val bottom = ceil((1f - v0) * sourceHeight).toInt().coerceIn(top + 1, sourceHeight)
            return RendererPhotoCropRect(left, top, right, bottom)
        }

        fun expandPhotoCropRect(
            visibleCrop: RendererPhotoCropRect,
            sourceWidth: Int,
            sourceHeight: Int,
            overscanFactor: Float = DEFAULT_OVERSCAN_FACTOR
        ): RendererPhotoCropRect {
            require(sourceWidth >= visibleCrop.right && sourceHeight >= visibleCrop.bottom)
            require(overscanFactor.isFinite() && overscanFactor >= 1f)

            val centerX = (visibleCrop.left + visibleCrop.right) * 0.5f
            val centerY = (visibleCrop.top + visibleCrop.bottom) * 0.5f
            val visibleWidth = visibleCrop.width
            val visibleHeight = visibleCrop.height
            val scaleX = min(
                overscanFactor,
                min(2f * centerX / visibleWidth, 2f * (sourceWidth - centerX) / visibleWidth)
            ).coerceAtLeast(1f)
            val scaleY = min(
                overscanFactor,
                min(2f * centerY / visibleHeight, 2f * (sourceHeight - centerY) / visibleHeight)
            ).coerceAtLeast(1f)

            val expandedWidth = max(
                visibleWidth,
                min(sourceWidth, (visibleWidth * scaleX).roundToInt())
            )
            val expandedHeight = max(
                visibleHeight,
                min(sourceHeight, (visibleHeight * scaleY).roundToInt())
            )
            val left = (centerX - expandedWidth * 0.5f).roundToInt()
                .coerceIn(0, sourceWidth - expandedWidth)
            val top = (centerY - expandedHeight * 0.5f).roundToInt()
                .coerceIn(0, sourceHeight - expandedHeight)
            return RendererPhotoCropRect(left, top, left + expandedWidth, top + expandedHeight)
        }

        private fun scaleCropRect(
            source: RendererPhotoCropRect,
            targetWidth: Int,
            targetHeight: Int,
            scaleX: Float,
            scaleY: Float
        ): RendererPhotoCropRect {
            val left = floor(source.left * scaleX).toInt().coerceIn(0, targetWidth - 1)
            val top = floor(source.top * scaleY).toInt().coerceIn(0, targetHeight - 1)
            val right = ceil(source.right * scaleX).toInt().coerceIn(left + 1, targetWidth)
            val bottom = ceil(source.bottom * scaleY).toInt().coerceIn(top + 1, targetHeight)
            return RendererPhotoCropRect(left, top, right, bottom)
        }

        private fun fitPhotoWorkingTexture(
            bitmap: Bitmap,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap {
            val scale = min(
                1f,
                min(
                    targetWidth / bitmap.width.toFloat(),
                    targetHeight / bitmap.height.toFloat()
                )
            )
            if (scale >= 0.9999f) return bitmap
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }
    }
}

data class RendererDecodedPhoto(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val sampleSize: Int,
    val visibleCropWidth: Int,
    val visibleCropHeight: Int,
    val workingCropWidth: Int,
    val workingCropHeight: Int,
    val overscanScaleX: Float,
    val overscanScaleY: Float,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val usedRegionDecode: Boolean
) : AutoCloseable {
    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}