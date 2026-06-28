package com.morphview.internal

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import kotlin.math.min

/**
 * The linked GL programs for the morph passes. Each resolves its uniform locations once at link, then
 * its `draw` sets the per-pass uniforms and issues the fullscreen-quad draw into the bound framebuffer.
 * Geometry comes from the VAO bound once per frame by the pipeline. Used on the GL thread.
 */
internal open class QuadProgram(val id: Int) {

    private val uScale = GLES20.glGetUniformLocation(id, "uScale")
    private val uOffset = GLES20.glGetUniformLocation(id, "uOffset")
    private val uTex = GLES20.glGetUniformLocation(id, "uTex")

    protected fun use() = GLES20.glUseProgram(id)

    protected fun setQuadTransform(scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
        GLES20.glUniform2f(uScale, scaleX, scaleY)
        GLES20.glUniform2f(uOffset, offsetX, offsetY)
    }

    protected fun bindTexture(texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uTex, 0)
    }

    protected fun drawQuad() = GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
}

internal class CompositeProgram(id: Int) : QuadProgram(id) {

    private val uAlpha = GLES20.glGetUniformLocation(id, "uAlpha")
    private val uUseTint = GLES20.glGetUniformLocation(id, "uUseTint")
    private val uTint = GLES20.glGetUniformLocation(id, "uTint")

    // Draws bmp FIT_CENTER into the targetW x targetH framebuffer at the given alpha, premultiplied.
    fun draw(texture: Int, bmp: Bitmap, alpha: Float, tintColor: Int?, targetW: Int, targetH: Int) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        use()
        // FIT_CENTER rect -> NDC scale/offset (Y flipped: screen-top maps to NDC +1).
        val scale = min(targetW / bw, targetH / bh)
        val dw = bw * scale
        val dh = bh * scale
        val cx = (targetW - dw) / 2f + dw / 2f
        val cy = (targetH - dh) / 2f + dh / 2f
        setQuadTransform(dw / targetW, dh / targetH, (cx / targetW) * 2f - 1f, 1f - (cy / targetH) * 2f)
        bindTexture(texture)
        GLES20.glUniform1f(uAlpha, alpha)
        if (tintColor != null) {
            GLES20.glUniform1i(uUseTint, 1)
            GLES20.glUniform3f(
                uTint,
                Color.red(tintColor) / 255f,
                Color.green(tintColor) / 255f,
                Color.blue(tintColor) / 255f,
            )
        } else {
            GLES20.glUniform1i(uUseTint, 0)
        }
        drawQuad()
    }
}

internal class BlurProgram(id: Int) : QuadProgram(id) {

    private val uTexel = GLES20.glGetUniformLocation(id, "uTexel")
    private val uDir = GLES20.glGetUniformLocation(id, "uDir")
    private val uRadius = GLES20.glGetUniformLocation(id, "uRadius")

    // Blurs texture along axis into the bound width x height framebuffer.
    fun draw(texture: Int, width: Int, height: Int, axis: BlurAxis, radius: Float) {
        use()
        setQuadTransform(1f, 1f, 0f, 0f)
        bindTexture(texture)
        GLES20.glUniform2f(uTexel, 1f / width, 1f / height)
        GLES20.glUniform2f(uDir, axis.dx, axis.dy)
        GLES20.glUniform1f(uRadius, radius)
        drawQuad()
    }
}

internal class ThresholdProgram(id: Int) : QuadProgram(id) {

    private val uThreshold = GLES20.glGetUniformLocation(id, "uThreshold")

    // Alpha-thresholds texture into the bound framebuffer (the shader flips Y for the buffer origin).
    fun draw(texture: Int, threshold: Float) {
        use()
        setQuadTransform(1f, 1f, 0f, 0f)
        bindTexture(texture)
        GLES20.glUniform1f(uThreshold, threshold)
        drawQuad()
    }
}

/** Direction of one separable-blur pass, as the `uDir` step applied to the texel size. */
internal enum class BlurAxis(val dx: Float, val dy: Float) {
    Horizontal(1f, 0f),
    Vertical(0f, 1f),
}
