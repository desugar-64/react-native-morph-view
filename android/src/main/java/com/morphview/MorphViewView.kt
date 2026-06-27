package com.morphview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import com.morphview.internal.MorphRenderer
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gooey "metaball" morph between two images. This view owns image loading, the crossfade animation,
 * and the optional baked border; the actual effect (crossfade → blur → alpha-threshold) is delegated
 * to a [MorphRenderer] whose backend is chosen per API level in [MorphRenderer.create].
 */
class MorphViewView : View {

  private var fromBitmap: Bitmap? = null
  private var toBitmap: Bitmap? = null

  private var fromUri: String? = null
  private var toUri: String? = null
  private var toggle: Boolean = false
  private var blurRadius: Float = 24f
  private var durationMs: Float = 600f
  private var tintColor: Int? = null
  private var borderColor: Int? = null
  private var borderWidth: Float = 0f

  /** 0 = showing `from`, 1 = showing `to`. */
  private var progress: Float = 0f
  private var animator: ValueAnimator? = null

  // Async backends invalidate the view off-thread when a new frame is ready.
  private val renderer = MorphRenderer.create(context) { postInvalidateOnAnimation() }

  private val ioExecutor = Executors.newCachedThreadPool()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val density = resources.displayMetrics.density

  // Bumped on each load; a background result is discarded if a newer load has started for the slot.
  private val fromGen = AtomicInteger()
  private val toGen = AtomicInteger()

  constructor(context: Context) : super(context) { init() }
  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
  constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
    super(context, attrs, defStyleAttr) { init() }

  private fun init() {
    setWillNotDraw(false)
    applyProgress()
  }

  // MARK: - Props

  fun setFromUri(uri: String?) {
    if (uri == fromUri) return
    fromUri = uri
    loadFrom()
  }

  fun setToUri(uri: String?) {
    if (uri == toUri) return
    toUri = uri
    loadTo()
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
    invalidate()
  }

  fun setBorderColorInt(color: Int?) {
    borderColor = color
    loadFrom()
    loadTo()
  }

  fun setBorderWidthPt(value: Float) {
    borderWidth = value
    loadFrom()
    loadTo()
  }

  // MARK: - Animation

  private fun animateTo(target: Float) {
    animator?.cancel()
    applyProgress()
    val distance = abs(target - progress)
    animator = ValueAnimator.ofFloat(progress, target).apply {
      // Scale duration by remaining distance so a mid-animation reversal completes quickly.
      duration = (distance * durationMs).toLong().coerceAtLeast(1)
      interpolator = MORPH_INTERPOLATOR
      addUpdateListener {
        progress = it.animatedValue as Float
        applyProgress()
      }
      start()
    }
  }

  /** Effect parameters are recomputed from [progress] by the renderer in [onDraw]; just redraw. */
  private fun applyProgress() {
    invalidate()
  }

  // MARK: - Drawing

  override fun onDraw(canvas: Canvas) {
    renderer.draw(canvas, width, height, fromBitmap, toBitmap, progress, blurRadius * density, tintColor)
  }

  // MARK: - Image loading

  private fun loadFrom() = loadInto(true, fromUri, fromGen)
  private fun loadTo()   = loadInto(false, toUri, toGen)

  private fun loadInto(isFrom: Boolean, uri: String?, gen: AtomicInteger) {
    val myGen = gen.incrementAndGet()
    if (uri.isNullOrEmpty()) {
      if (isFrom) fromBitmap = null else toBitmap = null
      invalidate()
      return
    }
    ioExecutor.execute {
      val bytes = try { readBytes(uri) } catch (e: Exception) { null }
      if (gen.get() != myGen) return@execute
      // Downsample to ~view size: the shader runs every frame, so a full-res bitmap is what lags it.
      val decoded = bytes?.let { decodeSampled(it, targetDecodePx()) }
      if (gen.get() != myGen) return@execute
      val bitmap = if (decoded != null && borderColor != null && borderWidth > 0f)
        bakeBorder(decoded, borderColor!!, borderWidth)
      else
        decoded
      if (gen.get() != myGen) return@execute
      mainHandler.post {
        if (gen.get() == myGen) {
          if (isFrom) fromBitmap = bitmap else toBitmap = bitmap
          invalidate()
        }
      }
    }
  }

  private fun readBytes(uri: String): ByteArray? = when {
    uri.startsWith("http") -> URL(uri).openStream().use { it.readBytes() }
    uri.startsWith("file://") -> java.io.File(uri.removePrefix("file://")).readBytes()
    uri.startsWith("content://") || uri.startsWith("android.resource://") ->
      context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
    else -> {
      val resId = resources.getIdentifier(uri, "drawable", context.packageName)
      if (resId != 0) resources.openRawResource(resId).use { it.readBytes() } else null
    }
  }

  /**
   * Bakes a hard [borderWidthDp]-dp border around the image silhouette via morphological dilation:
   * the coloured source is stamped at [steps] points on a circle of radius [r], then the original
   * is drawn on top. Border width is normalised to the view's pixel size so it stays constant
   * regardless of decoded resolution.
   */
  private fun bakeBorder(src: Bitmap, borderColor: Int, borderWidthDp: Float): Bitmap {
    val viewPx = maxOf(width, height).takeIf { it > 0 } ?: (200 * density).toInt()
    val imgPx = maxOf(src.width, src.height)
    val r = (borderWidthDp * density * imgPx.toFloat() / viewPx).coerceAtLeast(1f)
    val pad = ceil(r).toInt()

    val result = Bitmap.createBitmap(src.width + 2 * pad, src.height + 2 * pad, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      colorFilter = PorterDuffColorFilter(borderColor, PorterDuff.Mode.SRC_IN)
    }

    val steps = maxOf(24, (2.0 * Math.PI * r).toInt())
    for (i in 0 until steps) {
      val angle = i * 2.0 * Math.PI / steps
      val dx = (r * cos(angle)).toFloat()
      val dy = (r * sin(angle)).toFloat()
      canvas.drawBitmap(src, pad + dx, pad + dy, borderPaint)
    }

    canvas.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
    return result
  }

  private fun targetDecodePx(): Int {
    val longest = maxOf(width, height)
    val target = if (longest > 0) longest else (512 * density).toInt()
    return target.coerceIn(256, 1024)
  }

  /** Decodes [bytes] downsampled so its longest side is ~[maxPx] via power-of-two `inSampleSize`. */
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
    // Re-decode at the real size once known (images are often set before the first layout pass).
    val grew = maxOf(w, h) > maxOf(oldw, oldh)
    if (grew) {
      loadFrom()
      loadTo()
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    animator?.cancel()
    renderer.release()
  }

  companion object {
    private val MORPH_INTERPOLATOR = PathInterpolator(0.42f, 0f, 0.58f, 1f)
  }
}
