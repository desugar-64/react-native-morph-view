package com.morphview.internal.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * A GL_TEXTURE_2D holding a source bitmap. [uploadIfChanged] re-uploads only when the bitmap identity
 * changes, so steady-state frames skip the upload. Must be created and used on the GL thread.
 */
internal class SourceTexture {

    private var texture = 0
    private var lastBitmap: Bitmap? = null

    fun id(): Int = texture

    // Uploads bmp only when its identity changed since the last upload.
    fun uploadIfChanged(bmp: Bitmap?) {
        if (bmp === lastBitmap) return
        if (bmp != null) {
            ensureTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        }
        lastBitmap = bmp
    }

    private fun ensureTexture() {
        if (texture != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        setDefaultTextureParams()
    }
}
