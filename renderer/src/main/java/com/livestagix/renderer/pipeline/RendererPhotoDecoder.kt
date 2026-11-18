package com.livestagix.renderer.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import com.livestagix.renderer.api.RendererMedia
import com.livestagix.renderer.api.RendererMediaAccess
import com.livestagix.renderer.api.RendererMediaType
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared CPU photo preparation. Decodes off the render thread, applies host-supplied
 * orientation/crop, and constrains the working bitmap to the selected GPU texture limit.
 * GPU upload and texture lifetime remain the responsibility of each backend.
 */
class RendererPhotoDecoder {
    @Throws(IOException::class)
    fun decode(
        media: RendererMedia,
        mediaAccess: RendererMediaAccess,
        targetWidth: Int,
        targetHeight: Int,
        maxTextureSize: Int
    ): RendererDecodedPhoto {
        require(media.type != RendererMediaType.VIDEO) {
            "A video cannot be decoded as a photo"
        }
        require(targetWidth > 0 && targetHeight > 0) {
            "Target dimensions must be positive"
        }
        require(maxTextureSize > 0) { "Maximum texture size must be positive" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        mediaAccess.openInputStream(media.uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The selected media is not a decodable image")
        }

        var sampleSize = 1
        while (
            max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxTextureSize &&
            sampleSize <= (1 shl 29)
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = mediaAccess.openInputStream(media.uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IOException("Image decoding returned no bitmap")

        val oriented = try {
            if (media.orientationDegrees == 0) {
                decoded
            } else {
                val matrix = Matrix().apply {
                    postRotate(media.orientationDegrees.toFloat())
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { if (it !== decoded) decoded.recycle() }
            }
        } catch (error: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw error
        }

        return try {
            val crop = calculateCropRect(
                sourceWidth = oriented.width,
                sourceHeight = oriented.height,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                horizontalOffset = media.crop.horizontal,
                verticalOffset = media.crop.vertical
            )
            val cropped = if (
                crop.left == 0 && crop.top == 0 &&
                crop.right == oriented.width && crop.bottom == oriented.height
            ) {
                oriented
            } else {
                Bitmap.createBitmap(
                    oriented,
                    crop.left,
                    crop.top,
                    crop.width(),
                    crop.height()
                ).also { if (it !== oriented) oriented.recycle() }
            }

            RendererDecodedPhoto(
                bitmap = cropped,
                originalWidth = bounds.outWidth,
                originalHeight = bounds.outHeight,
                sampleSize = sampleSize
            )
        } catch (error: Throwable) {
            if (!oriented.isRecycled) oriented.recycle()
            throw error
        }
    }

    companion object {
        /** Cover crop: -1 aligns to the leading edge, 0 centers, +1 aligns trailing edge. */
        fun calculateCropRect(
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
            horizontalOffset: Float = 0f,
            verticalOffset: Float = 0f
        ): Rect {
            require(sourceWidth > 0 && sourceHeight > 0)
            require(targetWidth > 0 && targetHeight > 0)
            require(horizontalOffset.isFinite() && horizontalOffset in -1f..1f)
            require(verticalOffset.isFinite() && verticalOffset in -1f..1f)

            val sourceAspect = sourceWidth.toFloat() / sourceHeight
            val targetAspect = targetWidth.toFloat() / targetHeight
            var cropWidth = sourceWidth
            var cropHeight = sourceHeight

            if (sourceAspect > targetAspect) {
                cropWidth = (sourceHeight * targetAspect).roundToInt().coerceIn(1, sourceWidth)
            } else {
                cropHeight = (sourceWidth / targetAspect).roundToInt().coerceIn(1, sourceHeight)
            }

            val extraX = sourceWidth - cropWidth
            val extraY = sourceHeight - cropHeight
            val left = ((horizontalOffset + 1f) * 0.5f * extraX)
                .roundToInt().coerceIn(0, extraX)
            val top = ((verticalOffset + 1f) * 0.5f * extraY)
                .roundToInt().coerceIn(0, extraY)

            return Rect(left, top, left + cropWidth, top + cropHeight)
        }
    }
}

data class RendererDecodedPhoto(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val sampleSize: Int
) : AutoCloseable {
    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
