package com.morphview.internal

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Build
import androidx.annotation.RequiresApi
import com.morphview.internal.gl.EglCore
import com.morphview.internal.gl.RenderTarget
import com.morphview.internal.gl.SourceTexture
import com.morphview.internal.gl.SwapChain
import com.morphview.internal.gl.createFullscreenQuad
import com.morphview.internal.gl.createQuadVao
import com.morphview.internal.gl.linkProgram

/**
 * Off-screen GL pipeline that renders the morph (composite -> separable Gaussian blur ->
 * alpha-threshold) into a [HardwareBuffer][android.hardware.HardwareBuffer] and hands it back as a
 * [SwapChain.Lease]. Every method must run on the single GL thread that owns the EGL context (see
 * [HardwareBufferMorphRenderer]).
 *
 * Output egress is the zero-dependency path: render into the [SwapChain]'s ImageReader surface, then
 * acquire it as a HardwareBuffer-backed [Bitmap]. The bitmap composites through the normal HWUI path —
 * no SurfaceView, no `androidx.graphics` dependency, no `glReadPixels` round-trip.
 *
 * Blur matches `RenderEffect.createBlurEffect` 1:1 — Skia maps its blur radius to a Gaussian
 * `sigma = radius*0.57735 + 0.5`; both passes run at full resolution. Sources upload premultiplied
 * (Android default) so every pass works in premultiplied space; the threshold shader flips Y for the
 * GL-vs-HardwareBuffer origin difference.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class MorphGlPipeline {

    private val eglCore = EglCore()
    private val swapChain = SwapChain(eglCore)

    // Ping-pong blur targets: composite + final blur output, and the horizontal-pass scratch.
    private val compositeTarget = RenderTarget()
    private val blurIntermediate = RenderTarget()

    // Source textures for from/to, re-uploaded only when their bitmap identity changes.
    private val srcFrom = SourceTexture()
    private val srcTo = SourceTexture()

    private var programs: Programs? = null
    private var quadVao = 0

    /**
     * Renders one morph frame synchronously and returns it as a [SwapChain.Lease], or null if any GL
     * step fails (the caller skips the frame and retries next draw). The [SwapChain], [RenderTarget]s
     * and programs bring themselves up lazily and resize themselves; steady-state calls only re-upload
     * changed source bitmaps and run the four passes.
     *
     * Passes: (1) composite from/to FIT_CENTER with premultiplied src-over into compositeTarget; (2,3)
     * separable Gaussian H into blurIntermediate then V back into compositeTarget; (4) alpha-threshold
     * to the window surface. A [GLES20.glFinish] before read-back makes the GPU writes visible to HWUI.
     */
    fun render(
        viewWidth: Int,
        viewHeight: Int,
        from: Bitmap?,
        to: Bitmap?,
        progress: Float,
        blurRadiusPx: Float,
        tintColor: Int?,
    ): SwapChain.Lease? {
        swapChain.ensureSize(viewWidth, viewHeight)
        if (!swapChain.makeCurrent()) return null
        ensureGlObjects()
        compositeTarget.ensureSize(viewWidth, viewHeight)
        blurIntermediate.ensureSize(viewWidth, viewHeight)
        srcFrom.uploadIfChanged(from)
        srcTo.uploadIfChanged(to)
        GLES30.glBindVertexArray(quadVao) // static quad geometry for every pass; bound once per frame

        val gl = programs ?: return null
        val radius = morphBlurRadiusPx(progress = progress, blurRadiusPx = blurRadiusPx).coerceAtLeast(0.5f)

        // Pass 1 — composite from/to (crossfade + tint) into compositeTarget, premultiplied src-over.
        compositeTarget.bind()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (from != null) gl.composite.draw(srcFrom.id(), from, 1f - progress, tintColor, viewWidth, viewHeight)
        if (to != null) gl.composite.draw(srcTo.id(), to, progress, tintColor, viewWidth, viewHeight)
        GLES20.glDisable(GLES20.GL_BLEND)

        // Pass 2/3 — separable Gaussian: horizontal into blurIntermediate, then vertical back into compositeTarget.
        blurIntermediate.bind()
        gl.blur.draw(compositeTarget.texture(), viewWidth, viewHeight, BlurAxis.Horizontal, radius)
        compositeTarget.bind()
        gl.blur.draw(blurIntermediate.texture(), viewWidth, viewHeight, BlurAxis.Vertical, radius)

        // Pass 4 — alpha threshold, compositeTarget -> window surface (no clear: the quad covers every pixel).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        gl.threshold.draw(compositeTarget.texture(), 0.5f)

        swapChain.swapBuffers()
        GLES20.glFinish() // ensure GPU writes complete before HWUI samples the buffer (off the UI thread)

        return swapChain.acquireLease()
    }

    fun release() {
        swapChain.release()
        eglCore.release()
    }

    private fun ensureGlObjects() {
        if (programs != null) return
        // Resolve every program once; the per-frame passes never call glGet*Location again.
        val built = Programs(
            composite = CompositeProgram(linkProgram(vertexSrc = MorphShaders.VERTEX, fragmentSrc = MorphShaders.COMPOSITE_FRAGMENT)),
            blur = BlurProgram(linkProgram(vertexSrc = MorphShaders.VERTEX, fragmentSrc = MorphShaders.BLUR_FRAGMENT)),
            threshold = ThresholdProgram(linkProgram(vertexSrc = MorphShaders.VERTEX, fragmentSrc = MorphShaders.THRESHOLD_FRAGMENT)),
        )
        quadVao = createQuadVao(createFullscreenQuad())
        programs = built // publish last, so readiness == (programs != null)
    }

    /** The three linked programs, built together in [ensureGlObjects]; non-null == GL is ready. */
    private class Programs(
        val composite: CompositeProgram,
        val blur: BlurProgram,
        val threshold: ThresholdProgram,
    )
}
