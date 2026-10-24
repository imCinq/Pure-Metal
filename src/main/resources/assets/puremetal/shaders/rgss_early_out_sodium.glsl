// Bit-identical early-out for Sodium's sampleRGSS. Specialization constant 77 selects it (off = stock path).
layout(constant_id = 77) const bool PM_RGSS_EARLY_OUT = true;
vec4 pmSampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    if (!PM_RGSS_EARLY_OUT) return sampleRGSS(source, uv, pixelSize);
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);
    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
    // mix(a, b, 0.0) == a exactly, so the four supersampling taps are skipped when the blend is zero.
    if (blendFactor == 0.0) return nearestColor;
    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);
    float effectiveDerivative = sqrt(minDerivative * maxDerivative);
    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );
    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;
    return mix(nearestColor, rgssColor, blendFactor);
}
