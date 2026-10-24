// Experimental 2x2 face repetition. Input UVs remain unwrapped for derivatives.
// The face is exactly axis-aligned, two blocks wide, and aligned to even section coordinates.
// Reconstruct its affine UV rectangle instead of adding sprite tables or expanding the vertex format.
vec4 pmSampleLod(sampler2D atlas, vec2 uv, vec2 pixelSize, bool rgss, vec3 localPos, uint repeatFace) {
    vec2 du = dFdx(uv), dv = dFdy(uv);
    vec3 px = dFdx(localPos), py = dFdy(localPos);
    vec3 normal = abs(cross(px, py));
    vec2 p, dx, dy;
    if (normal.x >= normal.y && normal.x >= normal.z) {
        p = localPos.yz; dx = px.yz; dy = py.yz;
    } else if (normal.y >= normal.z) {
        p = localPos.xz; dx = px.xz; dy = py.xz;
    } else {
        p = localPos.xy; dx = px.xy; dy = py.xy;
    }
    float det = dx.x * dy.y - dx.y * dy.x;
    // Preserve the original shader's sampling for ordinary blocks and degenerate projections.
    if (repeatFace == 0u || abs(det) < 1e-12) {
        return rgss ? pmSampleRGSS(atlas, uv, pixelSize) : sampleNearest(atlas, uv, pixelSize);
    }
    vec2 a = (du * dy.y - dv * dx.y) / det;
    vec2 b = (dv * dx.x - du * dy.x) / det;
    vec2 cell = p - floor(p * 0.5) * 2.0;
    vec2 base = uv - a * cell.x - b * cell.y;
    vec2 lo = base + min(a * 2.0, vec2(0.0)) + min(b * 2.0, vec2(0.0));
    vec2 hi = base + max(a * 2.0, vec2(0.0)) + max(b * 2.0, vec2(0.0));
    vec2 middle = (lo + hi) * 0.5;
    vec2 tiled = base + a * (fract(p.x) * 2.0) + b * (fract(p.y) * 2.0);
    // Correct mip gradients come from the continuous coordinates, never from fract(tiled).
    du *= 2.0; dv *= 2.0;
    // Linear mip filtering needs a half texel of the coarsest sampled mip, not a base-level texel.
    float gradientMip = max(0.0, log2(max(max(length(du / pixelSize), length(dv / pixelSize)), 1e-20)));
    vec2 margin = pixelSize * exp2(ceil(gradientMip)) * 0.5;
    vec2 nearestLo = min(lo + margin, middle), nearestHi = max(hi - margin, middle);
    vec2 screenSize = max(sqrt(du * du + dv * dv), vec2(1e-20));
    vec2 texel = tiled / pixelSize;
    vec2 center = round(texel) - 0.5;
    vec2 offset = (texel - center - 0.5) * pixelSize / screenSize + 0.5;
    vec2 nearestUv = (center + clamp(offset, 0.0, 1.0)) * pixelSize;
    // Never let atlas filtering or RGSS offsets step outside this sprite's source UV rectangle.
    vec4 nearest = textureGrad(atlas, clamp(nearestUv, nearestLo, nearestHi), du, dv);
    if (!rgss) return nearest;
    float minPixel = min(pixelSize.x, pixelSize.y);
    float blend = smoothstep(minPixel, minPixel * 2.0, max(screenSize.x, screenSize.y));
    // mix(a, b, 0.0) == a exactly: with no blend the supersampling taps are skipped.
    if (PM_RGSS_EARLY_OUT && blend == 0.0) return nearest;
    float effective = sqrt(min(length(du), length(dv)) * max(length(du), length(dv)));
    float mip = max(0.0, log2(max(effective, 1e-20) / minPixel));
    margin = pixelSize * exp2(ceil(mip)) * 0.5;
    vec2 rgssLo = min(lo + margin, middle), rgssHi = max(hi - margin, middle);
    vec4 supersampled = vec4(0.0);
    const vec2 offsets[4] = vec2[](vec2(0.125, 0.375), vec2(-0.125, -0.375), vec2(0.375, -0.125), vec2(-0.375, 0.125));
    for (int i = 0; i < 4; i++) {
        supersampled += textureLod(atlas, clamp(tiled + offsets[i] * pixelSize, rgssLo, rgssHi), mip);
    }
    return mix(nearest, supersampled * 0.25, blend);
}
