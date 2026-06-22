package com.morphview.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API 29–32 backend: renders the morph in OpenGL on a dedicated [HandlerThread] and presents the
 * result as a hardware [Bitmap] via [Bitmap.wrapHardwareBuffer] — the "present via hardware Bitmap,
 * no SurfaceView" path.
 *
 * Async is hidden behind the synchronous [draw] contract: each draw submits the latest state to the
 * GL thread (coalesced to newest-only) and blits the latest ready frame. The result is stale by at
 * most one frame — invisible mid-morph, where everything is already blurred — and the very first
 * frames fall back to a plain crossfade until GL catches up.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class HardwareBufferMorphRenderer(
    private val onFrameReady: () -> Unit,
) : MorphRenderer {

    private val fallback = FitCenterPainter()
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blitDst = RectF()

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    // GL-thread-confined.
    private var pipeline: MorphGlPipeline? = null
    private val leases = ArrayDeque<MorphGlPipeline.Lease>()

    // Latest requested frame: UI thread writes, GL thread reads.
    @Volatile
    private var pending: RenderRequest? = null
    private var lastSubmitted: RenderRequest? = null // UI-thread only
    private val renderScheduled = AtomicBoolean(false)

    // Newest GL output: GL thread writes, UI thread blits.
    @Volatile
    private var displayBitmap: Bitmap? = null

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
        if (width <= 0 || height <= 0) return
        ensureThread()

        // Submit only when the frame changed — otherwise each produced frame's invalidate would
        // re-trigger an identical render, spinning the GL thread at rest.
        val req = RenderRequest(width, height, from, to, progress, blurRadiusPx, tintColor)
        if (req != lastSubmitted) {
            lastSubmitted = req
            pending = req
            scheduleRender()
        }

        val bmp = displayBitmap
        if (bmp != null && !bmp.isRecycled) {
            blitDst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bmp, null, blitDst, blitPaint)
        } else {
            // No GL frame yet — crossfade so the view isn't blank on the first frames.
            fallback.draw(canvas, from, width, height, 1f - progress, tintColor)
            fallback.draw(canvas, to, width, height, progress, tintColor)
        }
    }

    override fun release() {
        displayBitmap = null
        glHandler?.post {
            pipeline?.release()
            pipeline = null
            while (leases.isNotEmpty()) leases.removeFirst().close()
        }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }

    private fun ensureThread() {
        if (glThread != null) return
        val thread = HandlerThread("MorphViewGL").apply { start() }
        glThread = thread
        glHandler = Handler(thread.looper)
    }

    // Collapse a burst of draws into a single render of the newest state.
    private fun scheduleRender() {
        if (renderScheduled.compareAndSet(false, true)) {
            glHandler?.post {
                renderScheduled.set(false)
                pending?.let { renderFrame(it) }
            }
        }
    }

    // GL thread.
    private fun renderFrame(req: RenderRequest) {
        val lease = produceLease(req) ?: return
        leases.addLast(lease)
        // Keep newest + one previous (HWUI may still be compositing last frame); free older buffers.
        while (leases.size > 2) leases.removeFirst().close()
        displayBitmap = lease.bitmap
        onFrameReady()
    }

    /**
     * GL thread. Lazily creates the pipeline and renders [req]. Returns null if GL initialisation or
     * the render fails (e.g. context loss) — the caller skips the frame and retries on the next draw.
     */
    private fun produceLease(req: RenderRequest): MorphGlPipeline.Lease? = try {
        val gl = pipeline ?: MorphGlPipeline().also { pipeline = it }
        gl.render(req.width, req.height, req.from, req.to, req.progress, req.blurRadiusPx, req.tintColor)
    } catch (e: Exception) {
        null
    }

    private data class RenderRequest(
        val width: Int,
        val height: Int,
        val from: Bitmap?,
        val to: Bitmap?,
        val progress: Float,
        val blurRadiusPx: Float,
        val tintColor: Int?,
    )
}
