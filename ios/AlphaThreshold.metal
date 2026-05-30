#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

// SwiftUI layerEffect shader, referenced from Swift as `ShaderLibrary.…alphaThreshold()`.
//
// After the content has been blurred, every pixel has a *soft* alpha. Where two shapes overlap,
// their soft edges add up and cross the threshold, so the threshold step fuses them into one solid
// blob — the "metaball" / gooey look. Pixels below the threshold are dropped.
[[ stitchable ]] half4 alphaThreshold(float2 position, SwiftUI::Layer layer) {
    half4 color = layer.sample(position);
    if (color.a < 0.001h) {
        return half4(0.0h);
    }
    half threshold = 0.5h;
    half alpha = smoothstep(threshold - 0.05h, threshold + 0.05h, color.a);
    half3 straight = color.rgb / color.a;
    return half4(straight * alpha, alpha);
}
