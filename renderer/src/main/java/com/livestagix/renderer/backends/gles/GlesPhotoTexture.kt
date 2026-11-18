package com.livestagix.renderer.backends.gles

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils

/** Texture upload and lifetime shared by the independent GLES2 and GLES3 contexts. */
internal object GlesPhotoTexture {
    fun upload(bitmap: Bitmap): Int {
        require(!bitmap.isRecycled) { "Cannot upload a recycled photo bitmap" }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texture = textures[0]
        check(texture != 0) { "Could not allocate a photo texture" }

        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) {
                "Photo texture upload failed with GL error 0x${error.toString(16)}"
            }
            return texture
        } catch (error: Throwable) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            throw error
        }
    }

    fun release(texture: Int) {
        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }
}
