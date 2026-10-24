// No-cull diagnostic. Normalize reversed depth before the conventional farthest-depth reduction.
#include <metal_stdlib>
using namespace metal;
kernel void readDepth(depth2d<float, access::read> depth [[texture(0)]], device float *p [[buffer(0)]], constant uint &reverse [[buffer(1)]], uint2 xy [[thread_position_in_grid]]) {
    if (xy.x < depth.get_width() && xy.y < depth.get_height()) {
        float z=depth.read(xy);p[xy.y*depth.get_width()+xy.x] = reverse ? 1-z : z;
    }
}
struct Level { uint source, destination, width, height, sourceWidth, sourceHeight; };
kernel void reduceDepth(device float *p [[buffer(0)]], constant Level &l [[buffer(1)]], uint2 xy [[thread_position_in_grid]]) {
    if (xy.x >= l.width || xy.y >= l.height) return;
    float farthest = 0;
    for (uint y = xy.y*2; y < min(xy.y*2+2,l.sourceHeight); y++)
        for (uint x = xy.x*2; x < min(xy.x*2+2,l.sourceWidth); x++) {
            float z = p[l.source+y*l.sourceWidth+x];
            // Unknown depth must never become an occluder, including NaN under fast math.
            uint exponent = as_type<uint>(z) & 0x7f800000u;
            z = exponent == 0x7f800000u || z < 0 || z > 1 ? 1 : z;
            farthest = max(farthest,z);
        }
    p[l.destination+xy.y*l.width+xy.x] = farthest;
}
struct Query { int x0,y0,x1,y1; float nearest; uint uncertain; };
struct Config { uint width,height,count,levels; uint offsets[16]; uint widths[16]; };
kernel void queryDepth(device const float *p [[buffer(0)]], device const Query *queries [[buffer(1)]],
                       device uint *hidden [[buffer(2)]], constant Config &c [[buffer(3)]], uint i [[thread_position_in_grid]]) {
    if (i >= c.count) return;
    hidden[i] = 0;
    Query q = queries[i];
    uint exponent = as_type<uint>(q.nearest) & 0x7f800000u;
    if (q.uncertain || exponent == 0x7f800000u || q.nearest < 0 || q.nearest > 1
            || q.x0 < 0 || q.y0 < 0 || q.x1 > int(c.width) || q.y1 > int(c.height)
            || q.x0 >= q.x1 || q.y0 >= q.y1) return;
    // Tiles cover the WHOLE projected rectangle, possibly more. Overcoverage can keep a hidden draw visible;
    // undercoverage could hide visible terrain and is forbidden. Bounds are already conservatively projected.
    uint span = uint(max(q.x1-q.x0,q.y1-q.y0)), level = 0;
    while (level+1 < c.levels && (1u << level) < span) level++;
    float farthest = 0;
    for (uint y = uint(q.y0) >> level; y <= uint(q.y1-1) >> level; y++)
        for (uint x = uint(q.x0) >> level; x <= uint(q.x1-1) >> level; x++) {
            float z = p[c.offsets[level]+y*c.widths[level]+x];
            uint e = as_type<uint>(z) & 0x7f800000u;
            z = e == 0x7f800000u || z < 0 || z > 1 ? 1 : z;
            farthest = max(farthest,z);
        }
    // Conservative margin for depth/projection rounding. Equality always stays visible.
    hidden[i] = q.nearest > farthest + 0.00001f;
}
