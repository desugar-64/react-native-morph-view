package com.morphview.internal.gl

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLSurface

/**
 * Producer/consumer swap chain: an [ImageReader]-backed EGL window surface that the final pass renders
 * into. [swapBuffers] queues a frame; [acquireLease] dequeues the latest as a [HardwareBuffer]-backed
 * [Bitmap]. Owns the surface and reader, rebuilt on size change. Runs on the GL thread that owns [core].
 */
internal class SwapChain(private val core: EglCore) {

    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0

    // Rebuilds the reader + output surface when the size changes; a no-op otherwise.
    fun ensureSize(newWidth: Int, newHeight: Int) {
        if (imageReader != null && newWidth == width && newHeight == height) return
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            core.makeNothingCurrent()
            core.destroySurface(eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        imageReader?.close()
        width = newWidth
        height = newHeight
        // PRIVATE lets gralloc pick a GPU-only layout; a wrap failure degrades to crossfade, never a crash.
        val reader = ImageReader.newInstance(
            newWidth, newHeight, ImageFormat.PRIVATE, MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        imageReader = reader
        eglSurface = core.createWindowSurface(reader.surface)
    }

    fun makeCurrent(): Boolean = eglSurface != EGL14.EGL_NO_SURFACE && core.makeCurrent(eglSurface)

    fun swapBuffers() {
        core.swapBuffers(eglSurface)
    }

    // Wraps the latest rendered frame as a Lease, or null if none is ready or the buffer can't be wrapped.
    fun acquireLease(): Lease? {
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
        return Lease(image, buffer, bitmap)
    }

    fun release() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            core.makeNothingCurrent()
            core.destroySurface(eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        imageReader?.close()
        imageReader = null
    }

    /** A produced frame: [bitmap] is valid until [close], which recycles it and returns the buffer. */
    internal class Lease(
        private val image: Image,
        private val buffer: HardwareBuffer,
        val bitmap: Bitmap,
    ) {
        fun close() {
            // Recycle first to drop the buffer ref deterministically, then buffer, then image.
            bitmap.recycle()
            buffer.close()
            image.close()
        }
    }

    private companion object {
        // Buffer-queue depth: 2 retained (newest + previous) + 1 acquiring + 1 producer = 4.
        private const val MAX_IMAGES = 4

        private val SRGB = ColorSpace.get(ColorSpace.Named.SRGB)
    }
}
