package com.morphview.internal

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Backend that draws the gooey metaball morph (crossfade → blur → alpha-threshold) for a single
 * [com.morphview.MorphViewView]. One interface, implementation chosen by API level in [create].
 *
 * The effect needs a Gaussian blur followed by an alpha threshold, which gate the platform path to
 * API 33 ([RuntimeShader]). Below that, [HardwareBufferMorphRenderer] reproduces it in OpenGL and
 * presents the result as a hardware [Bitmap]; below API 29 we fall back to a plain crossfade.
 */
internal interface MorphRenderer {

    /**
     * Draws [from] and [to] crossfaded by [progress] (0 = `from`, 1 = `to`), scaled FIT_CENTER into
     * [width]×[height], with the morph blur+threshold effect. [blurRadiusPx] is the configured radius
     * in pixels at the morph midpoint; the backend applies the morph curve. [tintColor] is an optional
     * SRC_IN tint.
     */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        from: Bitmap?,
        to: Bitmap?,
        progress: Float,
        blurRadiusPx: Float,
        tintColor: Int?,
    )

    fun release() {}

    companion object {
        /** [onFrameReady] is invoked (possibly off the main thread) when an async backend has a frame. */
        fun create(context: Context, onFrameReady: () -> Unit): MorphRenderer {
            val sdk = Build.VERSION.SDK_INT
            return when {
                sdk >= Build.VERSION_CODES.TIRAMISU -> RenderEffectMorphRenderer()
                sdk >= Build.VERSION_CODES.Q && supportsGlEs3(context) -> HardwareBufferMorphRenderer(onFrameReady)
                else -> CrossfadeMorphRenderer()
            }
        }

        // The floor for the GL morph (VAOs, GLSL ES 3.00).
        private const val GL_ES_3_0 = 0x30000

        private fun supportsGlEs3(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return am.deviceConfigurationInfo.reqGlEsVersion >= GL_ES_3_0
        }
    }
}

/**
 * The morph blur strength over [progress]. `sqrt(sin)` has a vertical tangent at the ends, so any
 * motion off rest is already heavily blurred — a transition interrupted near completion can't flash
 * a crisp, un-fused image.
 */
internal fun morphBlurRadiusPx(progress: Float, blurRadiusPx: Float): Float =
    // sin(progress*PI) dips a hair below 0 at progress == 1 (32-bit PI rounds slightly high), and
    // sqrt(negative) = NaN — which then propagates through the blur to an empty frame. Clamp to >= 0.
    sqrt(sin(progress * PI.toFloat()).coerceAtLeast(0f)) * blurRadiusPx

/**
 * Draws a bitmap scaled-to-fit (ImageView FIT_CENTER) at a given alpha with an optional SRC_IN tint.
 * Extracted from `MorphViewView.drawImage` so every backend shares one crossfade primitive.
 */
internal class FitCenterPainter {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private var tintColor: Int? = null
    private var tintFilter: PorterDuffColorFilter? = null

    fun draw(canvas: Canvas, bmp: Bitmap?, width: Int, height: Int, alpha: Float, tint: Int?) {
        if (bmp == null || alpha <= 0.001f) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (vw <= 0f || vh <= 0f || bw <= 0f || bh <= 0f) return

        val scale = min(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        dst.set(left, top, left + dw, top + dh)

        if (tint != tintColor) {
            tintColor = tint
            tintFilter = tint?.let { PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
        }
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        paint.colorFilter = tintFilter
        canvas.drawBitmap(bmp, null, dst, paint)
    }
}

/** Pre-API-29 / software-canvas fallback: plain crossfade, no morph effect. */
internal class CrossfadeMorphRenderer : MorphRenderer {
    private val painter = FitCenterPainter()

    override fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        from: Bitmap?,
        to: Bitmap?,
        progress: Float,
        blurRadiusPx: Float,
        tintColor: Int?,
    ) {
        painter.draw(canvas, from, width, height, 1f - progress, tintColor)
        painter.draw(canvas, to, width, height, progress, tintColor)
    }
}

/**
 * API 33+: both images are drawn into a single [RenderNode] that carries the blur + alpha-threshold
 * [RenderEffect], and only that node is drawn — so the crossfade and the effect commit in the same
 * frame and the raw image never flashes.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class RenderEffectMorphRenderer : MorphRenderer {
    private val painter = FitCenterPainter()
    private val node = RenderNode("morph")
    private val shader = RuntimeShader(ALPHA_THRESHOLD_SHADER)

    override fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        from: Bitmap?,
        to: Bitmap?,
        progress: Float,
        blurRadiusPx: Float,
        tintColor: Int?,
    ) {
        if (!canvas.isHardwareAccelerated || width <= 0 || height <= 0) {
            // Software canvas can't carry a RenderEffect — plain crossfade.
            painter.draw(canvas, from, width, height, 1f - progress, tintColor)
            painter.draw(canvas, to, width, height, progress, tintColor)
            return
        }

        shader.setFloatUniform("threshold", 0.5f)
        val thresholdEffect = RenderEffect.createRuntimeShaderEffect(shader, "composable")
        // 0.5px floor keeps the same effect chain at rest instead of toggling it on/off at 0 and 1.
        val effectiveRadius = morphBlurRadiusPx(progress, blurRadiusPx).coerceAtLeast(0.5f)
        val blurEffect = RenderEffect.createBlurEffect(effectiveRadius, effectiveRadius, Shader.TileMode.DECAL)
        // createChainEffect(outer, inner) applies inner first: blur, then threshold.
        node.setRenderEffect(RenderEffect.createChainEffect(thresholdEffect, blurEffect))

        node.setPosition(0, 0, width, height)
        val recordingCanvas = node.beginRecording()
        try {
            painter.draw(recordingCanvas, from, width, height, 1f - progress, tintColor)
            painter.draw(recordingCanvas, to, width, height, progress, tintColor)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }

    override fun release() {
        node.discardDisplayList()
    }

    companion object {
        private const val ALPHA_THRESHOLD_SHADER = """
      uniform shader composable;
      uniform float threshold;

      half4 main(float2 coord) {
        half4 color = composable.eval(coord);
        if (color.a < 0.001) {
          return half4(0.0);
        }
        half alpha = smoothstep(threshold - 0.05, threshold + 0.05, color.a);
        half3 straight = color.rgb / color.a;
        return half4(straight * alpha, alpha);
      }
    """
    }
}
