package com.morphview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

class MorphViewView : FrameLayout {

  private val fromView = ImageView(context)
  private val toView = ImageView(context)

  private var fromUri: String? = null
  private var toUri: String? = null
  private var toggle: Boolean = false
  private var blurRadius: Float = 24f
  private var durationMs: Float = 600f
  private var tintColor: Int? = null

  /** 0 = showing `from`, 1 = showing `to`. */
  private var progress: Float = 0f
  private var animator: ValueAnimator? = null

  private val shader: RuntimeShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      RuntimeShader(ALPHA_THRESHOLD_SHADER)
    } else {
      null
    }

  private val ioExecutor = Executors.newCachedThreadPool()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val density = resources.displayMetrics.density

  constructor(context: Context) : super(context) { init() }
  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
  constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
    super(context, attrs, defStyleAttr) { init() }

  private fun init() {
    val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
    fromView.scaleType = ImageView.ScaleType.FIT_CENTER
    toView.scaleType = ImageView.ScaleType.FIT_CENTER
    addView(fromView, lp)
    addView(toView, lp)
    applyProgress()
  }

  // MARK: - Props

  fun setFromUri(uri: String?) {
    if (uri == fromUri) return
    fromUri = uri
    loadInto(fromView, uri)
  }

  fun setToUri(uri: String?) {
    if (uri == toUri) return
    toUri = uri
    loadInto(toView, uri)
  }

  fun setToggle(value: Boolean) {
    if (value == toggle) return
    toggle = value
    animateTo(if (value) 1f else 0f)
  }

  fun setBlurRadius(value: Float) {
    blurRadius = value
    applyProgress()
  }

  fun setDurationMs(value: Float) {
    durationMs = value
  }

  fun setTintColorInt(color: Int?) {
    tintColor = color
    applyTint(fromView)
    applyTint(toView)
  }

  // MARK: - Animation

  private fun animateTo(target: Float) {
    animator?.cancel()
    animator = ValueAnimator.ofFloat(progress, target).apply {
      duration = durationMs.toLong().coerceAtLeast(1)
      interpolator = MORPH_INTERPOLATOR
      addUpdateListener {
        progress = it.animatedValue as Float
        applyProgress()
      }
      start()
    }
  }

  private fun applyProgress() {
    fromView.alpha = 1f - progress
    toView.alpha = progress

    // Reversible blur progress: 0 at both ends, peaks at 0.5 in the middle of the transition.
    val blurProgress = if (progress > 0.5f) abs(1f - progress) else progress
    val radiusPx = blurProgress * blurRadius * density

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shader == null) {
      // No RuntimeShader before API 33 — fall back to a plain crossfade.
      return
    }

    if (radiusPx <= 0f) {
      // View exposes setRenderEffect (API 31+) but no getter, so it must be called as a method.
      setRenderEffect(null)
      return
    }

    shader.setFloatUniform("threshold", 0.5f)
    val thresholdEffect = RenderEffect.createRuntimeShaderEffect(shader, "composable")
    val blurEffect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.DECAL)
    // createChainEffect(outer, inner): inner (blur) runs first, then the threshold.
    setRenderEffect(RenderEffect.createChainEffect(thresholdEffect, blurEffect))
  }

  // MARK: - Image loading

  private fun applyTint(view: ImageView) {
    val color = tintColor
    if (color != null) {
      view.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    } else {
      view.clearColorFilter()
    }
  }

  private fun loadInto(view: ImageView, uri: String?) {
    if (uri.isNullOrEmpty()) {
      view.setImageDrawable(null)
      return
    }
    ioExecutor.execute {
      val bytes = try {
        readBytes(uri)
      } catch (e: Exception) {
        null
      }
      // Downsample at decode time. The blur + alpha-threshold shader run on every animation
      // frame, so feeding them a full-resolution bitmap (e.g. a multi-megapixel photo) is what
      // makes the morph lag — even when the view is only ~200dp. Decoding to roughly the view
      // size keeps each frame cheap; the blur hides the loss of detail anyway.
      val bitmap = bytes?.let { decodeSampled(it, targetDecodePx()) }
      mainHandler.post {
        view.setImageBitmap(bitmap)
        applyTint(view)
      }
    }
  }

  /** Reads the raw bytes for any supported URI form so they can be decoded with downsampling. */
  private fun readBytes(uri: String): ByteArray? = when {
    uri.startsWith("http") -> URL(uri).openStream().use { it.readBytes() }
    uri.startsWith("file://") -> java.io.File(uri.removePrefix("file://")).readBytes()
    uri.startsWith("content://") || uri.startsWith("android.resource://") ->
      context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
    else -> {
      // Bare resource name (release-mode bundled asset).
      val resId = resources.getIdentifier(uri, "drawable", context.packageName)
      if (resId != 0) resources.openRawResource(resId).use { it.readBytes() } else null
    }
  }

  /** Longest-side pixel cap for decoding: the view's pixel size, with a floor/ceiling. */
  private fun targetDecodePx(): Int {
    val longest = maxOf(width, height)
    val target = if (longest > 0) longest else (512 * density).toInt()
    return target.coerceIn(256, 1024)
  }

  /**
   * Decodes [bytes] downsampled so its longest side is ~[maxPx], using BitmapFactory's
   * power-of-two `inSampleSize` (cheap; never allocates the full bitmap).
   */
  private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxPx) {
      sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    // Images are usually set before the first layout pass, so they get decoded against the
    // fallback size. Once the real size is known, re-decode at the matching resolution.
    val grew = maxOf(w, h) > maxOf(oldw, oldh)
    if (grew) {
      loadInto(fromView, fromUri)
      loadInto(toView, toUri)
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    animator?.cancel()
  }

  companion object {
    // ease-in-out S-curve, matching SwiftUI's `.easeInOut` and the Compose MorphEasing.
    private val MORPH_INTERPOLATOR = PathInterpolator(0.42f, 0f, 0.58f, 1f)

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
