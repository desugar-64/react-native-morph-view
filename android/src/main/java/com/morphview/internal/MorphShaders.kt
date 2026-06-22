package com.morphview.internal

/**
 * GLSL ES 3.00 sources for the morph effect, in pipeline order. All three programs share [VERTEX];
 * the fragment stages run as: [COMPOSITE_FRAGMENT] (crossfade `from`/`to`) -> [BLUR_FRAGMENT]
 * (separable Gaussian, run twice) -> [THRESHOLD_FRAGMENT] (alpha cut that fuses the blobs). That
 * crossfade -> blur -> threshold order is what produces the gooey metaball merge; see
 * [MorphGlPipeline] for how they are wired into FBOs.
 *
 * Everything works in premultiplied alpha (Android bitmaps upload premultiplied).
 */
internal object MorphShaders {

    /** Positions a unit quad via uScale/uOffset; uv.y is flipped so a source's top row maps upright. */
    const val VERTEX = """#version 300 es
      layout(location = 0) in vec2 aPos;   // must match POS_LOCATION in GlUtils
      layout(location = 1) in vec2 aUv;    // must match UV_LOCATION in GlUtils
      uniform vec2 uScale;
      uniform vec2 uOffset;
      out vec2 vUv;
      void main() {
        vUv = aUv;
        gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
      }
    """

    /** Draws one source image at uAlpha with an optional SRC_IN tint, premultiplied. */
    const val COMPOSITE_FRAGMENT = """#version 300 es
      precision highp float;
      uniform sampler2D uTex;
      uniform float uAlpha;
      uniform int uUseTint;
      uniform vec3 uTint;
      in vec2 vUv;
      out vec4 frag;
      void main() {
        vec4 c = texture(uTex, vUv);          // premultiplied (Android bitmaps upload premultiplied)
        float a = c.a * uAlpha;
        vec3 pm = (uUseTint == 1) ? (uTint * a) : (c.rgb * uAlpha);
        frag = vec4(pm, a);
      }
    """

    /** One axis of a separable Gaussian; sigma matches RenderEffect/Skia exactly. */
    const val BLUR_FRAGMENT = """#version 300 es
      precision highp float;
      uniform sampler2D uTex;
      uniform vec2 uTexel;
      uniform vec2 uDir;
      uniform float uRadius;
      in vec2 vUv;
      out vec4 frag;
      const int TAPS = 16;
      void main() {
        // sigma is Skia's SkBlurMask::ConvertRadiusToSigma verbatim — kBLUR_SIGMA_SCALE = 0.57735f
        // (~1/sqrt(3)), sigma = radius*0.57735 + 0.5 — the same radius->sigma mapping that
        // RenderEffect.createBlurEffect feeds Skia, so this GL blur is 1:1 with the platform path.
        // Spread 2*TAPS+1 = 33 samples across +/-3 sigma (where ~99.7% of the Gaussian weight lies).
        float sigma = uRadius * 0.57735 + 0.5;
        float twoSigma2 = 2.0 * sigma * sigma;
        float stepPx = (3.0 * sigma) / float(TAPS);
        vec4 sum = vec4(0.0);
        float wsum = 0.0;
        for (int i = -TAPS; i <= TAPS; i++) {
          float offsetPx = float(i) * stepPx;
          float w = exp(-(offsetPx * offsetPx) / twoSigma2);
          vec2 uv = vUv + uDir * uTexel * offsetPx;
          // DECAL tile mode: out-of-bounds samples are transparent (0), but still normalised by the
          // full weight, so edges fade to transparent instead of clamping.
          vec2 inb = step(vec2(0.0), uv) * step(uv, vec2(1.0));
          sum += texture(uTex, uv) * (w * inb.x * inb.y);
          wsum += w;
        }
        frag = sum / max(wsum, 1e-4);
      }
    """

    /**
     * Alpha threshold that fuses overlapping blurred blobs into the metaball silhouette. GLSL port of
     * the AGSL `ALPHA_THRESHOLD_SHADER` in [RenderEffectMorphRenderer], kept equivalent so the 29–32
     * GL path matches the 33+ RuntimeShader path.
     */
    const val THRESHOLD_FRAGMENT = """#version 300 es
      precision highp float;
      uniform sampler2D uTex;
      uniform float uThreshold;
      in vec2 vUv;
      out vec4 frag;
      void main() {
        // Flip Y for the GL bottom-left vs HardwareBuffer/Bitmap top-left origin difference.
        vec4 c = texture(uTex, vec2(vUv.x, 1.0 - vUv.y));   // premultiplied blurred composite
        if (c.a < 0.001) {
          frag = vec4(0.0);
          return;
        }
        float na = smoothstep(uThreshold - 0.05, uThreshold + 0.05, c.a);
        vec3 straight = c.rgb / c.a;
        frag = vec4(straight * na, na);                     // premultiplied output
      }
    """
}
