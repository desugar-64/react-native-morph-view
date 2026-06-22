package com.morphview.internal.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Stateless GL helpers shared by the morph pipeline: shader/program building and fixed setup.

// Vertex layout of the fullscreen quad. The attribute locations must match the `layout(location=...)`
// pins in the pipeline's vertex shader so one VAO is valid for every program.
private const val POS_LOCATION = 0
private const val UV_LOCATION = 1
private const val STRIDE_BYTES = 16 // 4 floats * 4 bytes
private const val UV_OFFSET_BYTES = 8

/** Compiles and links a program from [vertexSrc] and [fragmentSrc]; throws on compile/link failure. */
internal fun linkProgram(vertexSrc: String, fragmentSrc: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteProgram(program)
        throw RuntimeException("GlUtils: program link failed: $log")
    }
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)
    return program
}

private fun compileShader(type: Int, src: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, src)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(shader)
        GLES20.glDeleteShader(shader)
        throw RuntimeException("GlUtils: shader compile failed: $log")
    }
    return shader
}

/**
 * Static [-1,1] fullscreen quad VBO, 4 interleaved floats per vertex (pos.xy, uv.xy), 4 vertices.
 * uv.y is flipped so a source bitmap's top row maps to NDC +1 (upright in the FBO).
 */
internal fun createFullscreenQuad(): Int {
    val verts = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f,
    )
    val buffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    buffer.put(verts).position(0)
    val ids = IntArray(1)
    GLES20.glGenBuffers(1, ids, 0)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, buffer, GLES20.GL_STATIC_DRAW)
    return ids[0]
}

/**
 * Creates a VAO that records [vbo] bound with the fullscreen-quad attribute layout (pos at
 * [POS_LOCATION], uv at [UV_LOCATION]). Bind it once per frame instead of re-specifying the vertex
 * attributes on every draw. Leaves no VAO bound.
 */
internal fun createQuadVao(vbo: Int): Int {
    val ids = IntArray(1)
    GLES30.glGenVertexArrays(1, ids, 0)
    GLES30.glBindVertexArray(ids[0])
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
    GLES20.glEnableVertexAttribArray(POS_LOCATION)
    GLES20.glVertexAttribPointer(POS_LOCATION, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, 0)
    GLES20.glEnableVertexAttribArray(UV_LOCATION)
    GLES20.glVertexAttribPointer(UV_LOCATION, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, UV_OFFSET_BYTES)
    GLES30.glBindVertexArray(0)
    return ids[0]
}

/** CLAMP_TO_EDGE wrap + bilinear filtering on the currently bound GL_TEXTURE_2D. */
internal fun setDefaultTextureParams() {
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
}
