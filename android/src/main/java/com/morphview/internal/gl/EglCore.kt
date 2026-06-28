package com.morphview.internal.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Minimal EGL bring-up for off-screen rendering: a default-display, RGBA8888, OpenGL ES 3.0 context.
 * ES 3.0 is required, not optional — the morph pipeline uses VAOs, `#version 300 es` shaders, and
 * explicit `layout(location = ...)` attributes. The config is requested with the ES3 renderable bit
 * and the context with client version 3 so the requirement is explicit, not driver-leniency.
 *
 * Owns the display/config/context; window surfaces are created per render target and handed back to
 * the caller, which owns their lifetime. Construct and use on a single GL thread.
 */
internal class EglCore {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay returned no display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configAttr = intArrayOf(
            // Require an ES 3.0-capable config — the pipeline needs VAOs and GLSL ES 3.00.
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttr, 0, configs, 0, 1, numConfig, 0) &&
                numConfig[0] > 0 && configs[0] != null,
        ) { "no ES3-capable EGL config" }
        config = configs[0]
        // EGL_CONTEXT_CLIENT_VERSION = 3 creates an OpenGL ES 3.0 context.
        val contextAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttr, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    fun createWindowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)

    /** Binds [eglSurface] for both draw and read; false if the context could not be made current. */
    fun makeCurrent(eglSurface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

    fun makeNothingCurrent(): Boolean =
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

    fun swapBuffers(eglSurface: EGLSurface): Boolean =
        EGL14.eglSwapBuffers(display, eglSurface)

    fun destroySurface(eglSurface: EGLSurface) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
    }

    /** Destroys the context and terminates the display. The caller must destroy its surfaces first. */
    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }
}
