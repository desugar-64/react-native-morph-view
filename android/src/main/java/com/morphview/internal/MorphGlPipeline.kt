package com.morphview.internal

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import androidx.annotation.RequiresApi
import com.morphview.internal.gl.EglCore
import com.morphview.internal.gl.createFullscreenQuad
import com.morphview.internal.gl.createQuadVao
import com.morphview.internal.gl.linkProgram
import com.morphview.internal.gl.setDefaultTextureParams
import kotlin.math.min

/**
 * Off-screen GL pipeline that renders the morph (composite -> separable Gaussian blur ->
 * alpha-threshold) into a [HardwareBuffer] and hands it back as a [Lease]. Every method must run on
 * the single GL thread that owns the EGL context (see [HardwareBufferMorphRenderer]).
 *
 * Output egress is the zero-dependency path: render into an [ImageReader]'s `Surface` (the EGL
 * window surface), then [ImageReader.acquireLatestImage] -> [Image.getHardwareBuffer] ->
 * [Bitmap.wrapHardwareBuffer]. The bitmap composites through the normal HWUI path — no SurfaceView,
 * no `androidx.graphics` dependency, no `glReadPixels` round-trip.
 *
 * Blur matches `RenderEffect.createBlurEffect` 1:1 — Skia maps its blur radius to a Gaussian
 * `sigma = radius*0.57735 + 0.5`; both passes run at full resolution. Sources upload premultiplied
 * (Android default) so every pass works in premultiplied space; the final pass flips Y for the
 * GL-vs-HardwareBuffer origin difference.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class MorphGlPipeline {

    /** A produced frame: [bitmap] is valid until [close]; closing recycles the GPU buffer. */
    internal class Lease(
        private val image: Image,
        private val buffer: HardwareBuffer,
        val bitmap: Bitmap,
    ) {
        fun close() {
            // Drop our buffer handle first, then return the Image to the reader's queue.
            buffer.close()
            image.close()
        }
    }

    private var eglCore: EglCore? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var imageReader: ImageReader? = null

    private var width = 0
    private var height = 0
    private var fbosDirty = true

    private var programs: Programs? = null
    private var quadVbo = 0
    private var quadVao = 0

    // RGBA ping-pong FBOs: composite target + separable-blur intermediate.
    private val fbo = IntArray(FBO_COUNT)
    private val tex = IntArray(FBO_COUNT)

    // Source textures (from, to) and the bitmap refs last uploaded into them (identity-compared).
    private val srcTex = IntArray(SOURCE_TEXTURE_COUNT)
    private var lastFrom: Bitmap? = null
    private var lastTo: Bitmap? = null

    /**
     * Renders one morph frame synchronously and returns it as a [Lease], or null if any GL step
     * fails (the caller skips the frame and retries next draw). Lazily brings up EGL, the programs,
     * and the size-dependent FBOs on first call / on resize; steady-state calls only re-upload
     * changed source bitmaps and run the four passes.
     *
     * Passes: (1) composite from/to FIT_CENTER with premultiplied src-over into FBO 0; (2,3)
     * separable Gaussian H then V; (4) alpha-threshold to the window surface, which the
     * [ImageReader] hands back as a [HardwareBuffer]-backed [Bitmap]. A [GLES20.glFinish] before
     * read-back guarantees the GPU writes are visible to HWUI (we are off the UI thread).
     */
    fun render(
        viewWidth: Int,
        viewHeight: Int,
        from: Bitmap?,
        to: Bitmap?,
        progress: Float,
        blurRadiusPx: Float,
        tintColor: Int?,
    ): Lease? {
        val core = ensureEgl()
        ensureSurface(core = core, viewWidth = viewWidth, viewHeight = viewHeight)
        if (!core.makeCurrent(eglSurface)) return null
        ensureGlObjects()
        ensureFbos()
        uploadIfChanged(from = from, to = to)
        GLES30.glBindVertexArray(quadVao) // static quad geometry for every pass; bound once per frame

        val gl = programs ?: return null
        val comp = gl.composite
        val bl = gl.blur
        val th = gl.threshold
        val radius = morphBlurRadiusPx(progress = progress, blurRadiusPx = blurRadiusPx).coerceAtLeast(0.5f)

        // Pass 1 — composite from/to (crossfade + tint) into FBO 0, premultiplied src-over.
        bindTarget(fbo[0])
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(comp.id)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (from != null) drawSourceImage(program = comp, texture = srcTex[0], bmp = from, alpha = 1f - progress, tintColor = tintColor)
        if (to != null) drawSourceImage(program = comp, texture = srcTex[1], bmp = to, alpha = progress, tintColor = tintColor)
        GLES20.glDisable(GLES20.GL_BLEND)

        // Pass 2/3 — separable Gaussian: horizontal blur into FBO 1, then vertical back into FBO 0.
        blurPass(program = bl, texture = tex[0], dstFbo = fbo[1], axis = BlurAxis.Horizontal, radius = radius)
        blurPass(program = bl, texture = tex[1], dstFbo = fbo[0], axis = BlurAxis.Vertical, radius = radius)

        // Pass 4 — alpha threshold, FBO 0 -> window surface (the ImageReader). The fullscreen quad
        // covers every pixel with blend off, so the surface needs no clear.
        bindTarget(0)
        GLES20.glUseProgram(th.id)
        setQuadTransform(program = th, scaleX = 1f, scaleY = 1f, offsetX = 0f, offsetY = 0f)
        bindTexture(program = th, texture = tex[0])
        GLES20.glUniform1f(th.uThreshold, 0.5f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        core.swapBuffers(eglSurface)
        GLES20.glFinish() // ensure GPU writes complete before HWUI samples the buffer (off the UI thread)

        return acquireLease()
    }

    /**
     * Wraps the most recently rendered image as a [Lease], handing ownership of the [Image] and its
     * [HardwareBuffer] to the lease. Returns null — releasing anything already acquired — if the
     * reader has no image yet or the buffer can't be wrapped.
     */
    private fun acquireLease(): Lease? {
        val image = imageReader?.acquireLatestImage() ?: return null
        val buffer = image.hardwareBuffer
        val bitmap = buffer?.let {
            try {
                Bitmap.wrapHardwareBuffer(it, SRGB)
            } catch (e: Exception) {
                null
            }
        }
        if (buffer == null || bitmap == null) {
            buffer?.close()
            image.close()
            return null
        }
        return Lease(image = image, buffer = buffer, bitmap = bitmap)
    }

    fun release() {
        eglCore?.let { core ->
            core.makeNothingCurrent()
            core.destroySurface(eglSurface)
            core.release()
        }
        imageReader?.close()
        eglCore = null
        eglSurface = EGL14.EGL_NO_SURFACE
        imageReader = null
    }

    // MARK: - Passes

    private fun bindTarget(fboId: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)
    }

    private fun drawSourceImage(program: CompositeProgram, texture: Int, bmp: Bitmap, alpha: Float, tintColor: Int?) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        // FIT_CENTER rect -> NDC scale/offset (Y flipped: screen-top maps to NDC +1).
        val scale = min(width / bw, height / bh)
        val dw = bw * scale
        val dh = bh * scale
        val cx = (width - dw) / 2f + dw / 2f
        val cy = (height - dh) / 2f + dh / 2f
        setQuadTransform(
            program = program,
            scaleX = dw / width,
            scaleY = dh / height,
            offsetX = (cx / width) * 2f - 1f,
            offsetY = 1f - (cy / height) * 2f,
        )
        bindTexture(program = program, texture = texture)
        GLES20.glUniform1f(program.uAlpha, alpha)
        if (tintColor != null) {
            GLES20.glUniform1i(program.uUseTint, 1)
            GLES20.glUniform3f(
                program.uTint,
                Color.red(tintColor) / 255f,
                Color.green(tintColor) / 255f,
                Color.blue(tintColor) / 255f,
            )
        } else {
            GLES20.glUniform1i(program.uUseTint, 0)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun blurPass(program: BlurProgram, texture: Int, dstFbo: Int, axis: BlurAxis, radius: Float) {
        bindTarget(dstFbo)
        GLES20.glUseProgram(program.id)
        setQuadTransform(program = program, scaleX = 1f, scaleY = 1f, offsetX = 0f, offsetY = 0f)
        bindTexture(program = program, texture = texture)
        GLES20.glUniform2f(program.uTexel, 1f / width, 1f / height)
        GLES20.glUniform2f(program.uDir, axis.dx, axis.dy)
        GLES20.glUniform1f(program.uRadius, radius)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindTexture(program: QuadProgram, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(program.uTex, 0)
    }

    // Sets the quad's NDC scale/offset for [program]. Geometry comes from the bound VAO (quadVao).
    private fun setQuadTransform(program: QuadProgram, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
        GLES20.glUniform2f(program.uScale, scaleX, scaleY)
        GLES20.glUniform2f(program.uOffset, offsetX, offsetY)
    }

    // MARK: - Resource lifecycle

    private fun ensureEgl(): EglCore = eglCore ?: EglCore().also { eglCore = it }

    private fun ensureSurface(core: EglCore, viewWidth: Int, viewHeight: Int) {
        if (imageReader != null && viewWidth == width && viewHeight == height) return
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            core.makeNothingCurrent()
            core.destroySurface(eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        imageReader?.close()
        width = viewWidth
        height = viewHeight
        // PRIVATE lets gralloc pick a proprietary GPU-only layout (no CPU access); the buffer is
        // still GPU-rendered and GPU-sampled. Bitmap.wrapHardwareBuffer must derive a color type
        // from the resolved format — driver-dependent, but a failure degrades to crossfade (the
        // acquireLease() catch returns null), never a crash.
        val reader = ImageReader.newInstance(
            viewWidth, viewHeight, ImageFormat.PRIVATE, MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        imageReader = reader
        eglSurface = core.createWindowSurface(reader.surface)
        fbosDirty = true
    }

    private fun ensureGlObjects() {
        if (programs != null) return
        // Resolve every attribute/uniform location once here so the per-frame passes never call
        // glGet*Location (a driver-side string lookup) again.
        val built = Programs(
            composite = CompositeProgram(linkProgram(vertexSrc = MorphShaders.VERTEX, fragmentSrc = MorphShaders.COMPOSITE_FRAGMENT)),
            blur = BlurProgram(linkProgram(vertexSrc = MorphShaders.VERTEX, fragmentSrc = MorphShaders.BLUR_FRAGMENT)),
            threshold = ThresholdProgram(linkProgram(vertexSrc = MorphShaders.VERTEX, fragmentSrc = MorphShaders.THRESHOLD_FRAGMENT)),
        )
        quadVbo = createFullscreenQuad()
        quadVao = createQuadVao(quadVbo)
        GLES20.glGenTextures(SOURCE_TEXTURE_COUNT, srcTex, 0)
        for (texture in srcTex) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            setDefaultTextureParams()
        }
        programs = built // publish last, so readiness == (programs != null)
    }

    private fun ensureFbos() {
        if (!fbosDirty) return
        if (tex[0] != 0) {
            GLES20.glDeleteTextures(FBO_COUNT, tex, 0)
            GLES20.glDeleteFramebuffers(FBO_COUNT, fbo, 0)
        }
        GLES20.glGenTextures(FBO_COUNT, tex, 0)
        GLES20.glGenFramebuffers(FBO_COUNT, fbo, 0)
        for (i in 0 until FBO_COUNT) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            setDefaultTextureParams()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0,
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fbosDirty = false
    }

    private fun uploadIfChanged(from: Bitmap?, to: Bitmap?) {
        if (from !== lastFrom) {
            if (from != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex[0])
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, from, 0)
            }
            lastFrom = from
        }
        if (to !== lastTo) {
            if (to != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex[1])
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, to, 0)
            }
            lastTo = to
        }
    }

    /** Attribute/uniform locations shared by every quad-drawing program (resolved once at link). */
    private open class QuadProgram(val id: Int) {
        val uScale = GLES20.glGetUniformLocation(id, "uScale")
        val uOffset = GLES20.glGetUniformLocation(id, "uOffset")
        val uTex = GLES20.glGetUniformLocation(id, "uTex")
    }

    private class CompositeProgram(id: Int) : QuadProgram(id) {
        val uAlpha = GLES20.glGetUniformLocation(id, "uAlpha")
        val uUseTint = GLES20.glGetUniformLocation(id, "uUseTint")
        val uTint = GLES20.glGetUniformLocation(id, "uTint")
    }

    private class BlurProgram(id: Int) : QuadProgram(id) {
        val uTexel = GLES20.glGetUniformLocation(id, "uTexel")
        val uDir = GLES20.glGetUniformLocation(id, "uDir")
        val uRadius = GLES20.glGetUniformLocation(id, "uRadius")
    }

    private class ThresholdProgram(id: Int) : QuadProgram(id) {
        val uThreshold = GLES20.glGetUniformLocation(id, "uThreshold")
    }

    /** Direction of one separable-blur pass, as the `uDir` step applied to the texel size. */
    private enum class BlurAxis(val dx: Float, val dy: Float) {
        Horizontal(1f, 0f),
        Vertical(0f, 1f),
    }

    /** The three linked programs, built together in [ensureGlObjects]; non-null == GL is ready. */
    private class Programs(
        val composite: CompositeProgram,
        val blur: BlurProgram,
        val threshold: ThresholdProgram,
    )

    private companion object {
        // ImageReader buffer-queue depth: 2 retained leases (newest + previous, still being
        // composited by HWUI) + 1 held transiently while acquiring the next before trimming + 1 free
        // for the GL producer to render into = 4. Lower starves acquire/swap; higher just wastes
        // width*height*4 bytes per extra buffer.
        private const val MAX_IMAGES = 4

        // Ping-pong render targets: FBO 0 = composite + final blur output, FBO 1 = blur intermediate.
        private const val FBO_COUNT = 2

        // Source-image textures: one for `from`, one for `to`.
        private const val SOURCE_TEXTURE_COUNT = 2

        // Hoisted out of the per-frame path: the wrapHardwareBuffer color space never changes.
        private val SRGB = ColorSpace.get(ColorSpace.Named.SRGB)
    }
}
