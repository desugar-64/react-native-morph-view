package com.morphview.internal.gl

import android.opengl.GLES20

/**
 * An off-screen color texture and its framebuffer, used as a ping-pong blur target. Reallocated only
 * when the size changes. Must be created and used on the GL thread.
 */
internal class RenderTarget {

    private var fbo = 0
    private var texture = 0
    private var width = 0
    private var height = 0

    // (Re)allocates the texture + FBO when the size changes; a no-op otherwise.
    fun ensureSize(newWidth: Int, newHeight: Int) {
        if (texture != 0 && newWidth == width && newHeight == height) return
        release()
        width = newWidth
        height = newHeight
        val tex = IntArray(1)
        val fb = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glGenFramebuffers(1, fb, 0)
        texture = tex[0]
        fbo = fb[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        setDefaultTextureParams()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // Binds this FBO as the draw target at its full size.
    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
    }

    fun texture(): Int = texture

    private fun release() {
        if (texture == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        texture = 0
        fbo = 0
    }
}
