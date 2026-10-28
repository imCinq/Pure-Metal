// Pure Metal native bridge. Every object handed to Java is retained (+1) and released with pm_release.
// Java owns ordering: all encoding happens on the render thread, in call order, into the device's current
// command buffer. Enums arrive as Pure Metal codes (see MetalConst.java) and are mapped here by name.

#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <MetalFX/MetalFX.h>
#import <Foundation/Foundation.h>
#import <objc/runtime.h>
#include <pthread.h>
#include <mach/mach_time.h>
#include <stdatomic.h>
#include <string.h>
#include <math.h>

#define PM_EXPORT __attribute__((visibility("default")))

// Opt-in frame trace (-Dpuremetal.frameTrace=true): a bounded ring of timestamped events, read back by the bench.
typedef struct { uint64_t t; int64_t a, b, c; int32_t type, pad; } PMTraceEv;
#define PM_TRACE_CAP 262144
static PMTraceEv g_trace[PM_TRACE_CAP];
static _Atomic uint64_t g_traceN;
static _Atomic int g_traceOn;
static pthread_mutex_t g_traceLock = PTHREAD_MUTEX_INITIALIZER;
static mach_timebase_info_data_t g_tb;
static uint64_t pm_now_ns(void) { return mach_absolute_time() * g_tb.numer / g_tb.denom; }
// GPU timestamps, MTL4CounterHeap entries included, are in mach ticks rather than nanoseconds (unlike MTLCounterResultTimestamp).
static uint64_t pm_ticks_ns(uint64_t ticks) {
    if (!g_tb.denom) mach_timebase_info(&g_tb);
    return g_tb.denom ? ticks * g_tb.numer / g_tb.denom : 0;
}
static uint64_t pm_sec_ns(double sec) { return sec > 0 ? (uint64_t)(sec * 1e9) : 0; }
static void pm_atomic_max(_Atomic uint64_t *counter, uint64_t value) {
    uint64_t previous = atomic_load_explicit(counter, memory_order_relaxed);
    while (previous < value && !atomic_compare_exchange_weak_explicit(counter, &previous, value,
            memory_order_relaxed, memory_order_relaxed)) { }
}
static inline void trace_ev(int type, int64_t a, int64_t b, int64_t c) {
    if (!atomic_load_explicit(&g_traceOn, memory_order_relaxed)) return;
    pthread_mutex_lock(&g_traceLock);
    if (atomic_load(&g_traceOn)) {
        uint64_t i = atomic_fetch_add(&g_traceN, 1) % PM_TRACE_CAP;
        g_trace[i] = (PMTraceEv){ pm_now_ns(), a, b, c, type, 0 };
    }
    pthread_mutex_unlock(&g_traceLock);
}
PM_EXPORT void pm_trace_enable(void) {
    pthread_mutex_lock(&g_traceLock);
    mach_timebase_info(&g_tb);
    atomic_store(&g_traceOn, 1);
    pthread_mutex_unlock(&g_traceLock);
}
PM_EXPORT void pm_trace_mark(int type, int64_t a, int64_t b, int64_t c) { trace_ev(type, a, b, c); }
// Stops recording and copies the newest events (oldest first) into out; returns how many.
PM_EXPORT int pm_trace_read(PMTraceEv *out, int cap) {
    atomic_store(&g_traceOn, 0);
    // Wait for any record already being written before copying the ring.
    pthread_mutex_lock(&g_traceLock);
    uint64_t n = atomic_load(&g_traceN);
    uint64_t count = n < PM_TRACE_CAP ? n : PM_TRACE_CAP;
    if (count > (uint64_t)cap) count = cap;
    for (uint64_t k = 0; k < count; k++) out[k] = g_trace[(n - count + k) % PM_TRACE_CAP];
    pthread_mutex_unlock(&g_traceLock);
    return (int)count;
}
// Opt-in encode CPU profile (-Dpuremetal.bench): native time spent encoding, counted per outermost entry so nested calls are not
// added twice, by category, plus the Metal calls actually issued. Off, every entry point costs one relaxed flag load.
enum { PM_ENC_PASS, PM_ENC_BIND, PM_ENC_DRAW, PM_ENC_COPY, PM_ENC_UPSCALE, PM_ENC_SUBMIT, PM_ENC_PRESENT, PM_ENC_PRESENT_THREAD, PM_ENC_CATS };
enum { PM_ENC_DRAWS = PM_ENC_CATS, PM_ENC_BUFFER_BINDS, PM_ENC_TEXTURE_BINDS, PM_ENC_ENCODERS, PM_ENC_CBS_CREATED, PM_ENC_CBS_COMMITTED,
       PM_ENC_ENTRIES, PM_ENC_BARRIERS, PM_ENC_SLOTS };
static _Atomic int g_encOn;
static _Atomic uint64_t g_enc[PM_ENC_SLOTS];
static _Thread_local int t_encDepth;
typedef struct { uint64_t t0; int cat; } PMEncScope;
static inline PMEncScope enc_enter(int cat) {
    PMEncScope s = { 0, -1 };
    if (!atomic_load_explicit(&g_encOn, memory_order_relaxed)) return s;
    if (t_encDepth++ == 0) {
        s.t0 = pm_now_ns();
        atomic_fetch_add_explicit(&g_enc[PM_ENC_ENTRIES], 1, memory_order_relaxed);
    }
    s.cat = cat;
    return s;
}
static inline void enc_leave(PMEncScope *s) {
    if (s->cat >= 0 && --t_encDepth == 0) atomic_fetch_add_explicit(&g_enc[s->cat], pm_now_ns() - s->t0, memory_order_relaxed);
}
#define PM_ENC_SCOPE(cat) PMEncScope pm_enc_scope __attribute__((cleanup(enc_leave))) = enc_enter(cat)
#define PM_ENC_COUNT(slot, n) do { if (atomic_load_explicit(&g_encOn, memory_order_relaxed)) \
    atomic_fetch_add_explicit(&g_enc[slot], (uint64_t)(n), memory_order_relaxed); } while (0)
PM_EXPORT void pm_enc_enable(void) {
    mach_timebase_info(&g_tb);
    atomic_store(&g_encOn, 1);
}
// Copies and resets the PM_ENC_SLOTS counters: ns per category, then draws, buffer binds, texture/sampler binds, encoders created,
// command buffers created and committed, outermost entries timed, Metal 4 queue barriers encoded.
PM_EXPORT void pm_enc_take(uint64_t *out) {
    for (int i = 0; i < PM_ENC_SLOTS; i++) out[i] = atomic_exchange(&g_enc[i], 0);
}

#define PM_MAX_COLOR 8
#define PM_VERTEX_BUFFER_BASE 16

// The device running the Metal 4 command model, or NULL. Metal 4 does not retain what a command buffer uses, so every retain and
// release of a buffer, texture, pipeline or sampler is routed through m4_retained / m4_released (below).
static struct PMDevice *g_m4Device;
static void m4_retained(id obj);
static bool m4_released(void *object);
PM_EXPORT void pm_release(void *obj);

// Java owns one explicit retain; temporary Objective-C objects are drained at each native entry point.
static inline void *pm_retain(id obj) {
    if (obj && g_m4Device) m4_retained(obj);
    return obj ? (void *)CFBridgingRetain(obj) : NULL;
}
#define PM_OBJ(type, ptr) ((__bridge type)(ptr))

static void pm_copy_string(NSString *s, char *out, int cap) {
    if (!out || cap <= 0) return;
    const char *c = s ? [s UTF8String] : "";
    strncpy(out, c ? c : "", (size_t)cap - 1);
    out[cap - 1] = 0;
}

// ---------------------------------------------------------------------------------------------------------------
// Formats. Order must match MetalConst.FORMAT_NAMES.

static const MTLPixelFormat PIXEL_FORMATS[] = {
    MTLPixelFormatR8Unorm, MTLPixelFormatR8Snorm, MTLPixelFormatRG8Unorm, MTLPixelFormatRG8Snorm,
    MTLPixelFormatInvalid, MTLPixelFormatInvalid, MTLPixelFormatRGBA8Unorm, MTLPixelFormatRGBA8Snorm,
    MTLPixelFormatR16Unorm, MTLPixelFormatR16Snorm, MTLPixelFormatRG16Unorm, MTLPixelFormatRG16Snorm,
    MTLPixelFormatInvalid, MTLPixelFormatInvalid, MTLPixelFormatRGBA16Unorm, MTLPixelFormatRGBA16Snorm,
    MTLPixelFormatR8Uint, MTLPixelFormatR8Sint, MTLPixelFormatRG8Uint, MTLPixelFormatRG8Sint,
    MTLPixelFormatInvalid, MTLPixelFormatInvalid, MTLPixelFormatRGBA8Uint, MTLPixelFormatRGBA8Sint,
    MTLPixelFormatR16Uint, MTLPixelFormatR16Sint, MTLPixelFormatRG16Uint, MTLPixelFormatRG16Sint,
    MTLPixelFormatInvalid, MTLPixelFormatInvalid, MTLPixelFormatRGBA16Uint, MTLPixelFormatRGBA16Sint,
    MTLPixelFormatR32Uint, MTLPixelFormatR32Sint, MTLPixelFormatRG32Uint, MTLPixelFormatRG32Sint,
    MTLPixelFormatInvalid, MTLPixelFormatInvalid, MTLPixelFormatRGBA32Uint, MTLPixelFormatRGBA32Sint,
    MTLPixelFormatR16Float, MTLPixelFormatRG16Float, MTLPixelFormatInvalid, MTLPixelFormatRGBA16Float,
    MTLPixelFormatR32Float, MTLPixelFormatRG32Float, MTLPixelFormatInvalid, MTLPixelFormatRGBA32Float,
    MTLPixelFormatRGB10A2Unorm, MTLPixelFormatRGB10A2Uint, MTLPixelFormatRG11B10Float,
    MTLPixelFormatDepth32Float, MTLPixelFormatDepth32Float_Stencil8, MTLPixelFormatDepth32Float_Stencil8,
    MTLPixelFormatDepth16Unorm, MTLPixelFormatStencil8,
};

static const MTLVertexFormat VERTEX_FORMATS[] = {
    MTLVertexFormatUCharNormalized, MTLVertexFormatCharNormalized, MTLVertexFormatUChar2Normalized, MTLVertexFormatChar2Normalized,
    MTLVertexFormatUChar3Normalized, MTLVertexFormatChar3Normalized, MTLVertexFormatUChar4Normalized, MTLVertexFormatChar4Normalized,
    MTLVertexFormatUShortNormalized, MTLVertexFormatShortNormalized, MTLVertexFormatUShort2Normalized, MTLVertexFormatShort2Normalized,
    MTLVertexFormatUShort3Normalized, MTLVertexFormatShort3Normalized, MTLVertexFormatUShort4Normalized, MTLVertexFormatShort4Normalized,
    MTLVertexFormatUChar, MTLVertexFormatChar, MTLVertexFormatUChar2, MTLVertexFormatChar2,
    MTLVertexFormatUChar3, MTLVertexFormatChar3, MTLVertexFormatUChar4, MTLVertexFormatChar4,
    MTLVertexFormatUShort, MTLVertexFormatShort, MTLVertexFormatUShort2, MTLVertexFormatShort2,
    MTLVertexFormatUShort3, MTLVertexFormatShort3, MTLVertexFormatUShort4, MTLVertexFormatShort4,
    MTLVertexFormatUInt, MTLVertexFormatInt, MTLVertexFormatUInt2, MTLVertexFormatInt2,
    MTLVertexFormatUInt3, MTLVertexFormatInt3, MTLVertexFormatUInt4, MTLVertexFormatInt4,
    MTLVertexFormatHalf, MTLVertexFormatHalf2, MTLVertexFormatHalf3, MTLVertexFormatHalf4,
    MTLVertexFormatFloat, MTLVertexFormatFloat2, MTLVertexFormatFloat3, MTLVertexFormatFloat4,
    MTLVertexFormatUInt1010102Normalized, MTLVertexFormatInvalid, MTLVertexFormatFloatRG11B10,
    MTLVertexFormatInvalid, MTLVertexFormatInvalid, MTLVertexFormatInvalid, MTLVertexFormatInvalid, MTLVertexFormatInvalid,
};

#define FORMAT_COUNT ((int)(sizeof(PIXEL_FORMATS) / sizeof(PIXEL_FORMATS[0])))
_Static_assert(sizeof(PIXEL_FORMATS) / sizeof(PIXEL_FORMATS[0]) == sizeof(VERTEX_FORMATS) / sizeof(VERTEX_FORMATS[0]), "format tables differ");

static MTLPixelFormat pixel_format(int code) { return code >= 0 && code < FORMAT_COUNT ? PIXEL_FORMATS[code] : MTLPixelFormatInvalid; }

PM_EXPORT int pm_format_count(void) { return FORMAT_COUNT; }
PM_EXPORT int pm_pixel_format_supported(int code) { return pixel_format(code) != MTLPixelFormatInvalid; }

static MTLCompareFunction compare_fn(int c) {
    switch (c) {
        case 0: return MTLCompareFunctionAlways;
        case 1: return MTLCompareFunctionLess;
        case 2: return MTLCompareFunctionLessEqual;
        case 3: return MTLCompareFunctionEqual;
        case 4: return MTLCompareFunctionNotEqual;
        case 5: return MTLCompareFunctionGreaterEqual;
        case 6: return MTLCompareFunctionGreater;
        default: return MTLCompareFunctionNever;
    }
}

static MTLBlendFactor blend_factor(int c) {
    switch (c) {
        case 0: return MTLBlendFactorBlendAlpha;
        case 1: return MTLBlendFactorBlendColor;
        case 2: return MTLBlendFactorDestinationAlpha;
        case 3: return MTLBlendFactorDestinationColor;
        case 4: return MTLBlendFactorOne;
        case 5: return MTLBlendFactorOneMinusBlendAlpha;
        case 6: return MTLBlendFactorOneMinusBlendColor;
        case 7: return MTLBlendFactorOneMinusDestinationAlpha;
        case 8: return MTLBlendFactorOneMinusDestinationColor;
        case 9: return MTLBlendFactorOneMinusSourceAlpha;
        case 10: return MTLBlendFactorOneMinusSourceColor;
        case 11: return MTLBlendFactorSourceAlpha;
        case 12: return MTLBlendFactorSourceAlphaSaturated;
        case 13: return MTLBlendFactorSourceColor;
        default: return MTLBlendFactorZero;
    }
}

static MTLBlendOperation blend_op(int c) {
    switch (c) {
        case 1: return MTLBlendOperationSubtract;
        case 2: return MTLBlendOperationReverseSubtract;
        case 3: return MTLBlendOperationMin;
        case 4: return MTLBlendOperationMax;
        default: return MTLBlendOperationAdd;
    }
}

// Pure Metal primitive codes: 0 triangles, 1 triangle strip, 2 lines, 3 line strip, 4 points.
static MTLPrimitiveType primitive(int c) {
    switch (c) {
        case 1: return MTLPrimitiveTypeTriangleStrip;
        case 2: return MTLPrimitiveTypeLine;
        case 3: return MTLPrimitiveTypeLineStrip;
        case 4: return MTLPrimitiveTypePoint;
        default: return MTLPrimitiveTypeTriangle;
    }
}

static MTLPrimitiveTopologyClass topology_class(int c) {
    switch (c) {
        case 2: case 3: return MTLPrimitiveTopologyClassLine;
        case 4: return MTLPrimitiveTopologyClassPoint;
        default: return MTLPrimitiveTopologyClassTriangle;
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Device and command stream.

#define PM_MAX_PENDING_CLEARS 8
#define PM_BUFFER_BINDINGS 31
#define PM_TEXTURE_BINDINGS 31
#define PM_SAMPLER_BINDINGS 16

// Two frames on the GPU, one waiting for the screen, one being presented and a spare, so the newest frame always
// finds a slot (with 3, the newest frames were dropped and older ones shown, ~75 ms behind).
#define PM_PRESENT_SLOTS 5

// Metal 4 path: command allocators and inline-constant chunks are kept per frame slot. Java waits for submit N - 4 at most, so a
// slot's previous frame has finished by the time it is reused.
#define PM_M4_FRAMES 6

// Completion counters (completed, m4Done) are prefixes, not high-water marks: Java recycles a buffer, runs a deferred action and
// returns from pm_wait on `tag <= completed`, so a single commit's feedback may only move the counter to an index every index at or
// below which has finished. Finished indexes that arrive out of order are remembered in a ring of one byte per slot, keyed by
// index & (PM_DONE_RING - 1), until the prefix reaches them.
#define PM_DONE_RING 64
#define PM_M4_CHUNKS 64
#define PM_M4_CHUNK_BYTES (1u << 20)
#define PM_M4_SIGNALS 8
#define PM_M4_HAZARDS 64
// Barrier tracking (frame command buffer): resources with a recorded access since the last epoch. A new epoch starts when the
// table passes PM_M4_TRACK_SOFT at an encoder boundary; PM_M4_TRACK_MAX is the hard limit.
#define PM_M4_TRACK_SLOTS 16384
#define PM_M4_TRACK_MAX 8192
#define PM_M4_TRACK_SOFT 6144
// -Dpuremetal.m4BarrierLog=N: dump every barrier and fence the Metal 4 path issues, one line each, for N frames starting after the
// first PM_M4_LOG_SKIP, the default of -Dpuremetal.m4BarrierLogSkip=K (startup compiles, the first residency commit and the window's
// first frames stay out of the log; the skip moves the window to, say, the position the bench measures).
#define PM_M4_LOG_SKIP 600
// -Dpuremetal.profile on Metal 4: MTL4 pass descriptors cannot carry a counter sample buffer, so GPU spans come from timestamps
// written into an MTL4CounterHeap instead. A render pass writes before its first draw, after its vertex work and after its fragment
// work (three entries, two samples: vertex then fragment); a compute or blit encoder writes one pair (its start when the encoder is
// created, its end when the next encoder starts). Heap slot, entries used and sample list belong to the frame slot and are only
// reused once that frame's commit feedback has resolved them.
#define PM_M4_TS_PAIRS 64
#define PM_M4_TS_ENTRIES (2 * PM_M4_TS_PAIRS)

enum { PM_STAGE_VERTEX = 1, PM_STAGE_FRAGMENT = 2 };

// -Dpuremetal.m4PreciseBarriers: which shader stages of one pipeline can read each bound slot, from the pipeline's reflection.
// 0 for a slot means the reflection said nothing about it (or there is none), and callers fall back to both stages: a hazard
// is never dropped for a slot the reflection does not describe.
typedef struct PMBindMask {
    uint8_t buffer[PM_BUFFER_BINDINGS], texture[PM_TEXTURE_BINDINGS];
} PMBindMask;

// Borrowed identities of resources retained by the render encoder/command buffer. This cache belongs to one
// encoder, never to a pipeline: switching shaders doesn't clear Metal's argument tables.
typedef struct PMBindingState {
    void *pipeline, *depth;
    // -Dpuremetal.m4PreciseBarriers: a pipeline set after the binds were recorded may narrow or move their stages (the same
    // table slot is still bound), so the first draw after a pipeline change re-registers them with that pipeline's masks.
    bool stageMaskStale;
    // -Dpuremetal.m4PreciseBarriers: the masks of the pipeline that is set, resolved once when it is set (pm_pass_set_pipeline)
    // so a bind that changes nothing pays no associated-object fetch. False when the pipeline carries no reflection.
    PMBindMask bindMask;
    bool bindMaskKnown;
    void *vertexBuffers[PM_BUFFER_BINDINGS], *fragmentBuffers[PM_BUFFER_BINDINGS];
    uint64_t vertexOffsets[PM_BUFFER_BINDINGS], fragmentOffsets[PM_BUFFER_BINDINGS];
    uint32_t vertexBufferSet, fragmentBufferSet;
    void *textures[PM_TEXTURE_BINDINGS], *samplers[PM_SAMPLER_BINDINGS];
    uint32_t textureSet, samplerSet;
} PMBindingState;

// What the Metal 4 render table physically holds, per slot: the buffer address, texture and sampler resource IDs last written. The
// table outlives encoders and logical passes (a draw snapshots it when encoded), so this survives them too; every write to the table
// goes through the m4_table_* setters, which skip a write that would not change the slot.
typedef struct PMTableCache {
    uint64_t buffer[PM_BUFFER_BINDINGS], texture[PM_TEXTURE_BINDINGS], sampler[PM_SAMPLER_BINDINGS];
    uint32_t bufferSet, textureSet, samplerSet;
} PMTableCache;

typedef struct PMPendingClear {
    void *texture;  // id<MTLTexture>, retained while pending
    float color[4];
    double depth;
} PMPendingClear;

#define PM_DIAG_PIECES 24
typedef struct PMDiagPiece {
    char name[64];
    _Atomic uint64_t ns, count;
} PMDiagPiece;

typedef struct PMProbeQuery { int32_t x0, y0, x1, y1; float nearest; uint32_t uncertain; } PMProbeQuery;
// One observation's buffers. The pyramid follows the depth size; queries and results hold the 4096 queries a submit can carry.
// `busy` is set when the slot is picked for a submit and cleared by that submit's completion, the only moment it can be reused.
#define PM_PROBE_SLOTS 4
typedef struct PMProbeSlot {
    void *pyramid, *queries, *results, *samples;
    uint32_t width, height, count;
    _Atomic int busy;
} PMProbeSlot;

// Metal 4 does not track hazards between the commands of one blit/compute encoder: the resources (root textures, buffers) the encoder
// has copied from or to, so a copy that depends on an earlier one can get a barrier first.
typedef struct PMBlitHazard {
    void *resource[PM_M4_HAZARDS];
    bool written[PM_M4_HAZARDS];
    int count;
} PMBlitHazard;

// One resource (buffer or root texture) an encoder has used since the last epoch. prev* are the stages of earlier encoders'
// accesses, cur* those of the encoder `enc`; `ordered` says the encoder already has a barrier for a read (1) / write (2), and
// ordR / ordW hold the stages those barriers actually hold back (m4PreciseBarriers: a second access at another stage still
// needs its own barrier). `frame` is the newest frame (m4Seq) that accessed it: the entry outlives the frame it was recorded in,
// and m4_prune drops it once that frame has finished on the GPU. wframe / rframe are the frames of the newest write and read, so
// m4_prune can drop the write (read) history a finished frame left behind without dropping the entry a later frame still reads.
// m4PreciseBarriers adds the producer-side ordering: pubV / pubA / pubQ are the stages of prevW a producer barrier already
// published to the vertex stage only, to every render and dispatch stage, or to every stage; pendV holds the fragment writes
// whose publishing is still owed, and upW says the early-upload encoder wrote the resource. frameWSeq is the newest frame whose
// own encoder (not the early-upload one) wrote it: the upload command buffer executes before the frame's, so a write from the
// frame being recorded is never covered by the upload's producer barrier, even when the same frame's upload wrote it as well.
typedef struct PMM4Use {
    void *res;
    uint64_t frame, wframe, rframe, frameWSeq;
    uint32_t gen, enc, prevW, prevR, curW, curR;
    uint32_t ordR, ordW;
    uint32_t pubV, pubA, pubQ, pendV;
    uint8_t ordered;
    bool readAtVertex;  // a vertex stage read it: its fragment writes are published as the encoder that wrote them ends
    bool upW;           // the early-upload encoder wrote it: end_uploads publishes that write to every consumer stage
} PMM4Use;
typedef struct PMM4Track {
    PMM4Use *uses;
    uint32_t gen, serial, count, fullStages; // fullStages: the stages a full-barrier fallback of this encoder covers (reset at each encoder start)
    bool started, poisoned;
    bool pendV; // an entry has fragment writes waiting for the producer barrier m4_publish_fragment_writes owes them
} PMM4Track;

// Something pm_release was called on while a frame that may use it was still recording or running (Metal 4 path).
typedef struct PMGrave {
    void *object;
    uint64_t frame;          // m4Seq at the time: freed once that frame has finished on the GPU
} PMGrave;

// Opt-in (bench) per-physical-pass attachment trace: what each render encoder loaded and stored.
typedef struct PMPassTraceAtt {
    int kind;                // 0 color, 1 depth
    int idx;
    unsigned format;
    unsigned width, height;
    uint64_t bytes;
    char name[40];
    int load;                // MTLLoadAction
} PMPassTraceAtt;
typedef struct PMPassTrace {
    char label[48];
    int width, height, merged, count;
    PMPassTraceAtt att[PM_MAX_COLOR + 1];
} PMPassTrace;
#define PM_TRACE_ENTRIES 128
typedef struct PMTraceEntry { char key[768]; uint64_t count; } PMTraceEntry;

typedef struct PMDevice {
    void *device;            // id<MTLDevice>
    void *queue;             // id<MTLCommandQueue>
    void *cb;                // id<MTLCommandBuffer>, current
    void *blit;              // id<MTLBlitCommandEncoder>, current
    void *render;            // id<MTLRenderCommandEncoder>, current
    // A render pass that ended but whose encoder is kept open, so a following pass on the same attachments can
    // continue in it instead of storing and reloading them (at 4K that is ~100 MB of traffic per pass). Any other
    // command buffer use ends it first, via current_cb.
    void *parked;
    void *passColors[PM_MAX_COLOR], *parkedColors[PM_MAX_COLOR];
    void *passDepth, *parkedDepth;
    int passWidth, passHeight, parkedWidth, parkedHeight;
    // Whole-texture clears not encoded yet. The next pass that attaches the texture clears it through its load
    // action instead (a separate clear pass writes the full target and the next pass reads it straight back: at 4K
    // that is ~116 MB per frame for the main color + depth). Anything else that could observe it encodes them first.
    PMPendingClear clears[PM_MAX_PENDING_CLEARS];
    int clearCount;
    void *presentPipeline;   // id<MTLRenderPipelineState> keyed to presentFormat
    void *presentLibrary;    // id<MTLLibrary>
    // Keep identical attachments open across a full depth-only clear; on by default (depthClearFold A/B switch).
    // PUREMETAL_DEPTH_CLEAR_FOLD=0 disables it.
    bool foldDepthClears;
    void *depthClearLibrary, *depthClearPipeline, *depthClearState;
    void *partialDepthLibrary, *partialDepthPipeline, *partialDepthState;
    MTLPixelFormat partialDepthFormat;
    MTLPixelFormat depthClearFormats[PM_MAX_COLOR];
    MTLPixelFormat presentFormat;
    // Render scale: the world drawn smaller is upscaled into the full-size target (pm_upscale).
    void *upscalePipeline;   // id<MTLRenderPipelineState> keyed to upscaleFormat
    MTLPixelFormat upscaleFormat;
    void *upscaleSampler;    // id<MTLSamplerState>, linear clamp
    void *upscaleDepthPipeline; // the same pass for a target drawn with a Depth32Float attachment, keyed to upscaleDepthFormat
    MTLPixelFormat upscaleDepthFormat;
    // Temporal upscale result still to be stretched into bgDst (the full-size target): folded into the next pass that loads
    // bgDst as that pass's first draw, or encoded as a pass of its own before anything else runs (flush_backdrop).
    void *bgDst, *bgSrc;     // id<MTLTexture> (root texture of the target / the scaler output), retained while pending
    void *fxScaler;          // id<MTLFXSpatialScaler> keyed to the fx* sizes and format below
    void *fxOut;             // id<MTLTexture>, the scaler's full-size output
    NSUInteger fxInW, fxInH, fxOutW, fxOutH;
    MTLPixelFormat fxFormat, fxSrcFormat;
    int fxUnsupported;
    int fxAllocFailed;       // the output texture could not be allocated for the fx* sizes (retried when they change)
    // MetalFX temporal upscaling (pm_temporal_*): the scaler, its world-sized inputs and the compute pipelines that fill them.
    void *txScaler, *txMotion, *txDepth, *txOut, *txExposure;
    void *txLibrary, *txMotionPipeline, *txHandPipeline;
    NSUInteger txInW, txInH, txDstW, txDstH; // txDst* is the full-size target
    MTLPixelFormat txColorFormat, txOutFormat;
    int txUnsupported, txPrepared, txFresh, txDirect, txDiag;
    // A texture allocation failed for these sizes (not a missing capability): skipped until they change or the state is reset.
    int txAllocFailed;
    NSUInteger txFailInW, txFailInH, txFailDstW, txFailDstH;
    MTLPixelFormat txFailColorFormat, txFailOutFormat;
    float txFailOutScale;
    float txOutScale;        // 1 = scaler writes full size; below 1 it writes a smaller image the final pass stretches
    // Opt-in (puremetal.profile) temporal diagnostics, taken with pm_take_stat(12 + i): scaler creations, scaler resets,
    // encoders the temporal path added, command buffer splits, GPU ns of the temporal span (stages 1-5), spans measured,
    // direct frames, chain ns (first stage start to last stage end), then GPU ns per stage at 8..13: world flush, motion,
    // hand pass, hand patch, scaler, copy.
    _Atomic uint64_t txDiagStats[16];
    _Atomic uint64_t txChainStart; // GPUStartTime of the current span's first stage, ns
    // Hand-pass breakdown (diagnostics): while txSpan is set every render pass starts a new timed command buffer, and the GPU
    // time of each is added to the entry named after the pass (label, size, depth), logged once a second.
    int txSpan, txPiece;
    PMDiagPiece txPieces[PM_DIAG_PIECES];
    atomic_int txPieceCount;
    _Atomic uint64_t txPieceSpans, txPieceReportedMs;
    char txInfo[768];        // what the scaler was last created with (read once by pm_temporal_info)
    int txInfoPending;
    atomic_int handlers;     // command-buffer completion handlers not yet finished (they touch this struct)
    MTLIndexType indexType;
    void *indexBuffer;       // borrowed id<MTLBuffer>
    uint64_t indexAddress, indexLength; // its GPU address and length, read when it is bound (Metal 4 draws take them)
    // Cull, fill and depth bias last sent to the open render encoder; cleared whenever a render encoder begins.
    int stateSet, cullSet, wireSet;
    float biasSet[2];
    PMBindingState bindings;
    // Bounded content snapshots for inline bindings, not borrowed CPU pointers. No per-draw allocations.
    unsigned char inlineBytes[PM_BUFFER_BINDINGS][4096];
    uint16_t inlineLengths[PM_BUFFER_BINDINGS];
    uint32_t inlineValid;
    int debugLabels;
    // Metal 4 shader compiler, created on first use. mtl4On is the compile path (toggleable); m4 below is the command model.
    // m4Pipelines=false (-Dpuremetal.m4Pipelines=false) keeps Metal 4 command encoding but builds libraries and pipelines on the
    // classic Metal 3 device path. Default on, so the option changes nothing.
    void *mtl4Compiler;      // id<MTL4Compiler>
    _Atomic int mtl4On, mtl4Warned, cbWarned; // cbWarned: no frame command buffer could be created (logged once)
    bool m4Pipelines;
    // Metal 4 command model, decided once in pm_device_create (opt-in). With m4 set, the command buffer, render and blit slots above hold
    // MTL4CommandBuffer / MTL4RenderCommandEncoder / MTL4ComputeCommandEncoder objects (blits run on the compute encoder). Code both
    // paths share only sends them selectors both protocols declare; everything else branches on m4.
    int m4;
    char m4Reason[160];
    void *m4Queue;           // id<MTL4CommandQueue>
    void *m4PresentQueue;    // id<MTL4CommandQueue>, used by the present thread
    void *m4Residency;       // id<MTLResidencySet> holding every buffer and root texture: committed before each command buffer
    void *m4Table, *m4PresentTable; // id<MTL4ArgumentTable>: bindings of the encoder being recorded (render thread / present thread)
    void *m4Event;           // id<MTLSharedEvent> for pm_wait_idle and the early-upload hand-off
    uint64_t m4EventValue;
    // Completion timeline: the frame queue signals m4FrameEvent with the frame's sequence value behind every frame, and the listener
    // below advances completed / m4Done from it, so the render thread learns about completion without waiting for the commit
    // feedback (which stays for GPU timings and errors).
    void *m4FrameEvent;      // id<MTLSharedEvent>, signaled with the frame number once the frame's command buffer has finished
    void *m4CompletionQueue; // dispatch_queue_t (serial), retained
    void *m4CompletionListener; // MTLSharedEventListener for m4FrameEvent, retained
    int64_t m4IndexBase;     // submit index - frame number, fixed once: the timeline counts frames, Java waits on submit indexes
    bool m4IndexKnown;
    bool m4EventCompletion;  // A/B switch (-Dpuremetal.m4EventCompletion=false): completion comes only from the commit feedback
    // One allocator and reusable command buffer per frame slot (frame m4Seq uses slot m4Seq % PM_M4_FRAMES), reset when the slot's
    // previous frame has finished; the early-upload command buffer has its own because both are recorded at once.
    void *m4Alloc[PM_M4_FRAMES], *m4UploadAlloc[PM_M4_FRAMES];
    void *m4Cb[PM_M4_FRAMES], *m4UploadCb[PM_M4_FRAMES];
    void *m4PresentAlloc[PM_PRESENT_SLOTS], *m4PresentCb[PM_PRESENT_SLOTS]; // per present slot, reset when the slot is reused
    void *m4PresentConst[PM_PRESENT_SLOTS];                                  // id<MTLBuffer>, the present pass's constant
    void *m4Scratch;         // id<MTLBuffer>, target of the fence encoder's fill
    _Atomic uint64_t m4Seq;  // frame being recorded, from 1; advanced by every pm_submit (the present thread reads it under m4Lock)
    uint64_t m4UploadWait;   // event value the next frame command buffer waits for: signaled behind the early uploads
    uint64_t m4Armed, m4UploadArmed; // frame whose allocator has been reset
    _Atomic uint64_t m4Done; // newest frame the GPU has finished (a prefix: every frame <= it has finished)
    uint8_t m4DoneMark[PM_DONE_RING]; // frames finished ahead of m4Done, see done_mark
    _Atomic uint64_t m4FrameFences; // fences at a frame boundary since the last pm_take_stat(28): 0 unless a switch or an overflow asks for one
    // Inline constants (setBytes has no Metal 4 counterpart): bump-allocated from shared chunks, per frame slot.
    void *m4Chunks[PM_M4_FRAMES][PM_M4_CHUNKS];
    int m4ChunkCount[PM_M4_FRAMES], m4ChunkCur;
    uint32_t m4ChunkUsed;
    uint64_t m4ChunkSeq;
    PMBlitHazard blitHaz, uploadHaz;
    PMTableCache m4Cache;    // contents of m4Table (render thread)
    // Barrier tracking for the frame's command buffer; the early-upload one keeps a full barrier unless m4TrackedUploads takes it
    // through the tracker as well. Its resource history survives the frame boundary (m4_prune trims it), so an encoder only waits
    // for what earlier frames still have in flight.
    PMM4Track m4Track;
    bool m4FullBarriers;     // A/B switch (-Dpuremetal.m4FullBarriers=true): every encoder waits for the whole queue
    bool m4PreciseBarriers;  // A/B switch (-Dpuremetal.m4PreciseBarriers=true): barriers name only the stages that really touch the resource,
                             // using each pipeline's binding reflection; it also implies the tracked upload path (m4_uploads_tracked)
    bool m4FrameFence;       // A/B switch (-Dpuremetal.m4FrameFence=true): the frame's first encoder waits for the whole queue
    bool m4UploadOrderAll;   // the tracker had no room for an early upload: the upload buffer ends with a queue-wide barrier instead
    bool m4UploadNeedsFull;  // a fence cleared the tracker's history: the next early copy must wait for the whole queue before it writes
    bool m4EarlyUploads;     // A/B switch (-Dpuremetal.m4EarlyUploads=false): early copies go into the frame's command buffer
    bool m4TrackedUploads;   // A/B switch (-Dpuremetal.m4TrackedUploads=true): the early-upload encoder tracks the copies' resources and the
                             // frame commits it ahead of its own buffer, instead of waiting for the whole queue
    // -Dpuremetal.m4TileSize=N: tile width and height of the Metal 4 render pass descriptors. 0 leaves the choice to Metal, like
    // Metal 3 does. No API reports the sizes a GPU accepts, so pm_set_option accepts 16, 32 and 64 and ignores anything else.
    uint32_t m4TileSize;
    bool m4TileWarned;
    // -Dpuremetal.m4BarrierLog=N: diagnostics for the ordering question the Metal System Trace raised (does anything on Metal 4 order
    // a frame's vertex work behind the previous one's fragment work?). 0, the default, records nothing: every hook in this path is one
    // branch on m4BarrierLog, and the encoder bookkeeping below only runs while a frame is actually being logged.
    int m4BarrierLog;
    // -Dpuremetal.m4BarrierLogSkip=K: first frame the dump covers (default PM_M4_LOG_SKIP). The attribution state of the dump is
    // per thread (see PMM4LogCtx): the presenter records on another thread, so it must not write the render thread's fields.
    uint64_t m4LogSkip;
    // Event signals the frame's command buffer owes (queue signals run after the commit, not inside the buffer).
    struct { void *event; uint64_t value; } m4Signals[PM_M4_SIGNALS];
    int m4SignalCount;
    // -Dpuremetal.profile on Metal 4: per-pass GPU timing through MTL4CounterHeaps (one per frame slot) instead of the Metal 3
    // counter sample attachments. Each sample names the two heap entries it spans and the report bin it belongs to (0 render
    // encoder span, 1 the fragment tail after its last vertex work, 2 compute/blit). m4TsOpen is the entry of the compute/blit
    // span being recorded and m4TsPass the one of the open render encoder, -1 when none is open. Both stay -1 with profiling
    // off, and every hook tests m4Profile plus the heap first, so the default path writes nothing. Render thread only: the
    // presenter records on another thread and another command buffer, and never touches this state.
    void *m4TsHeap[PM_M4_FRAMES];
    struct { uint16_t used, count; struct { uint16_t first, second; uint8_t bin; } pair[PM_M4_TS_PAIRS]; } m4Ts[PM_M4_FRAMES];
    _Atomic int m4TsBusy[PM_M4_FRAMES]; // set while a submitted frame's heap still has to be resolved for its slot to be reusable
    _Atomic uint64_t m4TsDropped;       // samples the heap's capacity could not hold (pm_take_stat 37)
    int m4TsOpen, m4TsPass;
    int m4Profile;
    // Encoder opens per frame by kind (render, clear, blit on the frame command buffer, upload blit, compute, present): pm_take_stat
    // 29..34. Then the time blocked in the surface's vsyncDepth/slotsFree semaphore waits and how many of those waits blocked: 35, 36.
    _Atomic uint64_t encCounts[6];
    _Atomic uint64_t surfaceWaitNs, surfaceWaitCount; // blocked only: the uncontended attempt is not counted
    _Atomic uint64_t drawableWaitMaxNs, layerConfigureMaxNs; // frame-trace only; consumed once per reporting interval
    // pm_release deferrals (a Metal 4 command buffer does not retain what it uses) and how many handles of each tracked allocation exist.
    pthread_mutex_t m4Lock;
    CFMutableDictionaryRef m4Tracked;
    PMGrave *m4Grave;
    int m4GraveCount, m4GraveCap, m4Dirty;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    _Atomic uint64_t completed;
    uint8_t doneMark[PM_DONE_RING]; // submit indexes finished ahead of completed, see done_mark
    _Atomic int failed;
    _Atomic uint64_t gpuNs;
    _Atomic uint64_t gpuFrames;
    _Atomic uint64_t uploadGpuNs;
    _Atomic uint64_t uploadGpuCommands;
    void *profileCounterSet;
    unsigned profileSamples;
    _Atomic uint64_t profileStats[6]; // Metal 3: vertex ns, fragment ns, in-frame blit ns, then sample counts; Metal 4: render
                                      // encoder span and its fragment tail, then compute/blit
    void *probeRead, *probeReduce, *probeQuery;
    PMProbeSlot probeSlots[PM_PROBE_SLOTS]; // pooled per in-flight submission, reused only after its commit has finished
    PMProbeSlot *probeCur;                  // the slot encoded for the submit being recorded, handed to its completion in pm_submit
    PMProbeQuery probeQueries[4096];
    unsigned probeCount;
    bool probeSampledSubmit;
    uint32_t probeReverse;
    _Atomic uint64_t probeStats[6]; // completed queries, hidden, dropped, compute-span ns, probes, valid spans
    // Render-thread counters for the bench line: new render encoders, merged passes, blit encoders, blits that closed a
    // parked render encoder, attachment bytes loaded and stored, uploads moved ahead of the frame, then pass-closing blits
    // by kind (buffer copy, buffer to texture, texture to buffer, texture to texture, present).
    uint64_t stats[12];
    int traceOn, traceCount;
    PMPassTrace traceRender, traceParked;
    PMTraceEntry trace[PM_TRACE_ENTRIES];
    // Uploads into buffers the current frame hasn't touched yet go into this command buffer, committed just before the
    // frame's, so they don't split the frame's render passes (each split stores and reloads every attachment).
    void *uploadCb;
    void *uploadBlit;
    char error[512];
} PMDevice;

static void reset_temporal(PMDevice *d);
static int ensure_upscale_pipeline(PMDevice *d, MTLPixelFormat format);
static int ensure_upscale_depth_pipeline(PMDevice *d, MTLPixelFormat format);
static void draw_stretch(PMDevice *d, id<MTLRenderCommandEncoder> e, id<MTLTexture> src, int width, int height, bool withDepth);

static void encode_probe(PMDevice *d, void *depth);
#define DEV_MTL(d) PM_OBJ(id<MTLDevice>, (d)->device)

// ---------------------------------------------------------------------------------------------------------------
// Metal 4 compile path. Libraries and pipelines come from MTL4Compiler when enabled (macOS 26+); the results are the
// same MTLLibrary / MTLRenderPipelineState / MTLComputePipelineState objects the Metal 3 encoders use. Any failure
// is logged once and that object is built on the Metal 3 path instead.

static const char kFunctionInfoKey = 0;
static const char kPipelineBindMaskKey = 0;

// -Dpuremetal.m4PreciseBarriers: the pipeline reflection names the slots each stage actually reads (MTLBinding.index, .used),
// which is what lets pm_pass_bind register a texture the fragment shader alone samples for MTLStageFragment only. Buffers and
// textures have separate index spaces in the reflection, just as in the argument table. The mask lives on the pipeline state as
// an associated object: the objects handed to Java are the MTLRenderPipelineStates themselves, so there is nowhere else to keep
// it, and the association goes away with the pipeline.
API_AVAILABLE(macos(13.0))
static void pm_pipeline_attach_mask(id<MTLRenderPipelineState> state, MTLRenderPipelineReflection *reflection) {
    if (!state) return;
    PMBindMask mask;
    memset(&mask, 0, sizeof(mask));
    for (id<MTLBinding> b in reflection.vertexBindings) {
        if (!b.used) continue;
        if (b.type == MTLBindingTypeBuffer) {
            if (b.index < PM_BUFFER_BINDINGS) mask.buffer[b.index] |= PM_STAGE_VERTEX;
        } else if (b.type == MTLBindingTypeTexture && b.index < PM_TEXTURE_BINDINGS) {
            mask.texture[b.index] |= PM_STAGE_VERTEX;
        }
    }
    for (id<MTLBinding> b in reflection.fragmentBindings) {
        if (!b.used) continue;
        if (b.type == MTLBindingTypeBuffer) {
            if (b.index < PM_BUFFER_BINDINGS) mask.buffer[b.index] |= PM_STAGE_FRAGMENT;
        } else if (b.type == MTLBindingTypeTexture && b.index < PM_TEXTURE_BINDINGS) {
            mask.texture[b.index] |= PM_STAGE_FRAGMENT;
        }
    }
    objc_setAssociatedObject(state, &kPipelineBindMaskKey, [NSData dataWithBytes:&mask length:sizeof(mask)],
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// The mask recorded for this pipeline, if any. False means the caller has to assume both stages.
static bool pm_pipeline_bind_mask(void *pipeline, PMBindMask *out) {
    if (!pipeline) return false;
    NSData *data = objc_getAssociatedObject((__bridge id<MTLRenderPipelineState>)pipeline, &kPipelineBindMaskKey);
    if (!data || data.length != sizeof(*out)) return false;
    [data getBytes:out length:sizeof(*out)];
    return true;
}

static void pm_mtl4_failed(PMDevice *d, NSString *what, NSError *error) {
    if (atomic_exchange(&d->mtl4Warned, 1) == 0) {
        NSLog(@"Pure Metal: Metal 4 compiler failed on a %@ (%@); building it with Metal 3 (further failures are not logged)",
              what, error ? error.localizedDescription : @"unknown error");
    }
}

// Remembers where a function came from; MTL4 pipelines are described by library + name, not by MTLFunction.
static id<MTLFunction> pm_track_function(id<MTLFunction> fn, id<MTLLibrary> lib, NSString *name, NSNumber *constIndex, NSNumber *constValue) {
    if (fn) {
        NSMutableDictionary *info = [@{@"lib": lib, @"name": name} mutableCopy];
        if (constIndex) { info[@"index"] = constIndex; info[@"value"] = constValue; }
        objc_setAssociatedObject(fn, &kFunctionInfoKey, info, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    return fn;
}

static id<MTLLibrary> pm_new_library(PMDevice *d, NSString *source, MTLCompileOptions *opts, NSError **error) {
    if (atomic_load(&d->mtl4On) && d->m4Pipelines) {
        if (@available(macOS 26.0, *)) {
            MTL4LibraryDescriptor *ld = [MTL4LibraryDescriptor new];
            ld.source = source;
            ld.options = opts;
            NSError *e4 = nil;
            id<MTLLibrary> lib = [PM_OBJ(id<MTL4Compiler>, d->mtl4Compiler) newLibraryWithDescriptor:ld error:&e4];
            if (lib) return lib;
            pm_mtl4_failed(d, @"library", e4);
        }
    }
    return [DEV_MTL(d) newLibraryWithSource:source options:opts error:error];
}

API_AVAILABLE(macos(26.0))
static MTL4FunctionDescriptor *pm_mtl4_function(id<MTLFunction> fn) {
    NSDictionary *info = fn ? objc_getAssociatedObject(fn, &kFunctionInfoKey) : nil;
    if (!info) return nil;
    MTL4LibraryFunctionDescriptor *f = [MTL4LibraryFunctionDescriptor new];
    f.library = info[@"lib"];
    f.name = info[@"name"];
    if (!info[@"index"]) return f;
    MTLFunctionConstantValues *values = [MTLFunctionConstantValues new];
    bool v = [info[@"value"] intValue] != 0;
    [values setConstantValue:&v type:MTLDataTypeBool atIndex:[info[@"index"] unsignedIntegerValue]];
    MTL4SpecializedFunctionDescriptor *s = [MTL4SpecializedFunctionDescriptor new];
    s.functionDescriptor = f;
    s.constantValues = values;
    return s;
}

// MTL4 pipeline descriptors carry no depth/stencil formats, so those are dropped. A pipeline without a fragment function
// (depth only) or built from an untracked function stays on Metal 3. With -Dpuremetal.m4Pipelines=false every pipeline is built
// here: the state is the same MTLRenderPipelineState the MTL4 encoders take, and its depth/stencil formats come along.
static id<MTLRenderPipelineState> pm_new_render_pipeline(PMDevice *d, MTLRenderPipelineDescriptor *p, NSError **error) {
    if (atomic_load(&d->mtl4On) && d->m4Pipelines && p.fragmentFunction) {
        if (@available(macOS 26.0, *)) {
            MTL4FunctionDescriptor *vs = pm_mtl4_function(p.vertexFunction), *fs = pm_mtl4_function(p.fragmentFunction);
            if (vs && fs) {
                MTL4RenderPipelineDescriptor *rd = [MTL4RenderPipelineDescriptor new];
                rd.label = p.label;
                rd.vertexFunctionDescriptor = vs;
                rd.fragmentFunctionDescriptor = fs;
                rd.vertexDescriptor = p.vertexDescriptor;
                rd.inputPrimitiveTopology = p.inputPrimitiveTopology;
                rd.rasterSampleCount = p.rasterSampleCount;
                for (NSUInteger i = 0; i < PM_MAX_COLOR; i++) {
                    MTLRenderPipelineColorAttachmentDescriptor *c = p.colorAttachments[i];
                    if (c.pixelFormat == MTLPixelFormatInvalid) continue;
                    MTL4RenderPipelineColorAttachmentDescriptor *t = rd.colorAttachments[i];
                    t.pixelFormat = c.pixelFormat;
                    t.writeMask = c.writeMask;
                    if (c.blendingEnabled) {
                        t.blendingState = MTL4BlendStateEnabled;
                        t.sourceRGBBlendFactor = c.sourceRGBBlendFactor;
                        t.destinationRGBBlendFactor = c.destinationRGBBlendFactor;
                        t.rgbBlendOperation = c.rgbBlendOperation;
                        t.sourceAlphaBlendFactor = c.sourceAlphaBlendFactor;
                        t.destinationAlphaBlendFactor = c.destinationAlphaBlendFactor;
                        t.alphaBlendOperation = c.alphaBlendOperation;
                    }
                }
                NSError *e4 = nil;
                // -Dpuremetal.m4PreciseBarriers: ask for the binding reflection the masks on the pipeline are built from.
                if (d->m4PreciseBarriers) {
                    MTL4PipelineOptions *opts = [MTL4PipelineOptions new];
                    opts.shaderReflection = MTL4ShaderReflectionBindingInfo;
                    rd.options = opts;
                }
                id<MTLRenderPipelineState> state = [PM_OBJ(id<MTL4Compiler>, d->mtl4Compiler) newRenderPipelineStateWithDescriptor:rd
                                                                                                               compilerTaskOptions:nil error:&e4];
                if (state) {
                    if (d->m4PreciseBarriers && state.reflection) pm_pipeline_attach_mask(state, state.reflection);
                    return state;
                }
                pm_mtl4_failed(d, @"render pipeline", e4);
            }
        }
    }
    if (d->m4PreciseBarriers) {
        // Metal 3 fallback with the same reflection, so the masks do not depend on which path built the pipeline.
        MTLAutoreleasedRenderPipelineReflection reflection = nil;
        id<MTLRenderPipelineState> state = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:p options:MTLPipelineOptionBindingInfo
                                                                                reflection:&reflection error:error];
        if (state && reflection) pm_pipeline_attach_mask(state, reflection);
        return state;
    }
    return [DEV_MTL(d) newRenderPipelineStateWithDescriptor:p error:error];
}

static id<MTLComputePipelineState> pm_new_compute_pipeline(PMDevice *d, id<MTLLibrary> lib, NSString *name, NSError **error) {
    if (atomic_load(&d->mtl4On) && d->m4Pipelines) {
        if (@available(macOS 26.0, *)) {
            MTL4LibraryFunctionDescriptor *f = [MTL4LibraryFunctionDescriptor new];
            f.library = lib;
            f.name = name;
            MTL4ComputePipelineDescriptor *cd = [MTL4ComputePipelineDescriptor new];
            cd.computeFunctionDescriptor = f;
            NSError *e4 = nil;
            id<MTLComputePipelineState> state = [PM_OBJ(id<MTL4Compiler>, d->mtl4Compiler) newComputePipelineStateWithDescriptor:cd
                                                                                                             compilerTaskOptions:nil error:&e4];
            if (state) return state;
            pm_mtl4_failed(d, @"compute pipeline", e4);
        }
    }
    return [DEV_MTL(d) newComputePipelineStateWithFunction:[lib newFunctionWithName:name] error:error];
}

// The device's MTL4Compiler, created on first use (by the compile path, or by the Metal 4 command model for its MetalFX scalers).
API_AVAILABLE(macos(26.0))
static id<MTL4Compiler> pm_mtl4_compiler(PMDevice *d, NSString **why) {
    if (!d->mtl4Compiler) {
        NSError *error = nil;
        id<MTL4Compiler> compiler = [DEV_MTL(d) newCompilerWithDescriptor:[MTL4CompilerDescriptor new] error:&error];
        if (!compiler) {
            *why = [NSString stringWithFormat:@"could not create the compiler: %@", error.localizedDescription];
            return nil;
        }
        d->mtl4Compiler = pm_retain(compiler);
    }
    return PM_OBJ(id<MTL4Compiler>, d->mtl4Compiler);
}

// Turns the Metal 4 compile path on or off. Returns whether it is active; reason says why (or why not).
PM_EXPORT int pm_metal4_enable(PMDevice *d, int enable, char *reason, int cap) {
    @autoreleasepool {
        if (!enable) {
            atomic_store(&d->mtl4On, 0);
            pm_copy_string(@"turned off", reason, cap);
            return 0;
        }
        if (@available(macOS 26.0, *)) {
            if (![DEV_MTL(d) supportsFamily:MTLGPUFamilyApple7]) {
                pm_copy_string(@"this GPU is older than Apple7", reason, cap);
                return 0;
            }
            NSString *why = nil;
            if (!pm_mtl4_compiler(d, &why)) {
                pm_copy_string(why, reason, cap);
                return 0;
            }
            atomic_store(&d->mtl4On, 1);
            pm_copy_string(@"enabled", reason, cap);
            return 1;
        }
        pm_copy_string(@"needs macOS 26 or newer", reason, cap);
        return 0;
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Metal 4 command model (opt-in, decided once in pm_device_create). Commands go through an MTL4CommandQueue with one
// MTL4CommandAllocator per frame slot, bindings through MTL4ArgumentTables and residency through one MTLResidencySet that holds
// every buffer and root texture. Metal 4 tracks no hazards and retains nothing a command buffer uses, so this section also owns:
//  - barriers: an encoder waits for the stages that last wrote (or, for a write, read) a resource it touches, tracked per buffer /
//    root texture in PMM4Track. The tracker keeps its history across frame boundaries: it tags an entry with the frame that last
//    used the resource and drops it once that frame has finished, so consecutive frames overlap and an encoder only waits for what
//    is still in flight. Anything the tracker cannot follow (MetalFX passes, table overflow) ends in a new epoch with a fence.
//    The early-upload command buffer keeps a full barrier per encoder unless -Dpuremetal.m4TrackedUploads takes it through the
//    tracker too, in which case it is committed in the same queue commit as the frame's own buffer, ahead of it, so the copies run
//    before the frame without an event the frame would have to wait for; a fence makes the next early copy wait for the whole queue
//    instead, since it cannot lean on a barrier that comes after it in queue order. The present queue also keeps full barriers, as
//    does -Dpuremetal.m4FullBarriers.
//    -Dpuremetal.m4PreciseBarriers narrows each tracked barrier to the stages that actually touch the resource (an attachment is
//    fragment work, a vertex attribute buffer or index buffer is vertex work, a fragment-only texture is fragment work) and lets
//    one encoder take a second barrier for a stage an earlier one did not cover.
//    -Dpuremetal.m4FrameFence restores the fence at the frame's first encoder (the whole queue must finish before the frame starts).
//    -Dpuremetal.m4BarrierLog=N dumps every barrier and fence of N frames after the first PM_M4_LOG_SKIP (the default of
//    -Dpuremetal.m4BarrierLogSkip=K), one NSLog line each, with frame, encoder, direction (consumer / producer / intra / fence),
//    call site, resource and both stage sets: the diagnostic for what orders vertex work behind earlier fragment work.
//    Copies inside one encoder get a barrier only when they touch a resource an earlier copy in it did (PMBlitHazard).
//  - lifetimes: pm_release defers buffers, textures, pipelines, samplers and scalers until the frame being recorded has finished.

static void set_error(PMDevice *d, NSString *msg);

// Only a statement prefix works: clang accepts @available alone in an if condition.
#define PM_IF_M4(d) if (@available(macOS 26.0, *)) if ((d)->m4)

// 1: a buffer or texture (tracked in the residency set), 2: something else a command can reference (freed late), 0: anything else.
// The answer is per class, cached in a small table (pointers to classes are aligned, so the low bits hold the answer).
static int pm_kind(id obj) {
    static _Atomic uintptr_t cache[64];
    uintptr_t key = (uintptr_t)object_getClass(obj);
    _Atomic uintptr_t *slot = &cache[(key >> 4) & 63];
    uintptr_t entry = atomic_load_explicit(slot, memory_order_relaxed);
    if ((entry & ~(uintptr_t)3) == key) return (int)(entry & 3);
    int kind = 0;
    if ([obj conformsToProtocol:@protocol(MTLBuffer)] || [obj conformsToProtocol:@protocol(MTLTexture)]) kind = 1;
    else if ([obj conformsToProtocol:@protocol(MTLRenderPipelineState)] || [obj conformsToProtocol:@protocol(MTLComputePipelineState)]
             || [obj conformsToProtocol:@protocol(MTLDepthStencilState)] || [obj conformsToProtocol:@protocol(MTLSamplerState)]
             || [obj conformsToProtocol:@protocol(MTLFXSpatialScalerBase)] || [obj conformsToProtocol:@protocol(MTLFXTemporalScalerBase)]) kind = 2;
    atomic_store_explicit(slot, key | (uintptr_t)kind, memory_order_relaxed);
    return kind;
}

// Every pm_retain of a buffer or texture counts one handle; the first one puts a root allocation (not a view, not a texel buffer's
// texture, which share their parent's memory) into the residency set, committed before the next command buffer.
static void m4_retained(id obj) {
    PMDevice *d = g_m4Device;
    if (!d || pm_kind(obj) != 1) return;
    if (@available(macOS 26.0, *)) {
        pthread_mutex_lock(&d->m4Lock);
        uintptr_t handles = (uintptr_t)CFDictionaryGetValue(d->m4Tracked, (__bridge const void *)obj);
        CFDictionarySetValue(d->m4Tracked, (__bridge const void *)obj, (const void *)(handles + 1));
        if (handles == 0) {
            bool root = true;
            if ([obj conformsToProtocol:@protocol(MTLTexture)]) {
                id<MTLTexture> t = (id<MTLTexture>)obj;
                root = !t.parentTexture && !t.buffer;
            }
            if (root) {
                [PM_OBJ(id<MTLResidencySet>, d->m4Residency) addAllocation:(id<MTLAllocation>)obj];
                d->m4Dirty = 1;
            }
        }
        pthread_mutex_unlock(&d->m4Lock);
    }
}

// Returns true when the object must not be released now: it is parked until the frame being recorded has finished on the GPU
// (m4_sync frees it). Other handles of the same allocation, and objects no command can reference, are released at once.
static bool m4_released(void *object) {
    PMDevice *d = g_m4Device;
    int kind = d ? pm_kind((__bridge id)object) : 0;
    if (kind == 0) return false;
    pthread_mutex_lock(&d->m4Lock);
    if (kind == 1) {
        uintptr_t handles = (uintptr_t)CFDictionaryGetValue(d->m4Tracked, object);
        if (handles > 1) {
            CFDictionarySetValue(d->m4Tracked, object, (const void *)(handles - 1));
            pthread_mutex_unlock(&d->m4Lock);
            return false;
        }
        CFDictionaryRemoveValue(d->m4Tracked, object);
    }
    if (d->m4GraveCount == d->m4GraveCap) {
        d->m4GraveCap = d->m4GraveCap ? d->m4GraveCap * 2 : 256;
        PMGrave *grown = realloc(d->m4Grave, (size_t)d->m4GraveCap * sizeof(PMGrave));
        if (!grown) {
            // Out of memory: keep the old table and leak this one object; releasing it now could free memory the GPU still reads.
            d->m4GraveCap = d->m4GraveCount;
            pthread_mutex_unlock(&d->m4Lock);
            return true;
        }
        d->m4Grave = grown;
    }
    d->m4Grave[d->m4GraveCount++] = (PMGrave){ object, d->m4Seq };
    pthread_mutex_unlock(&d->m4Lock);
    return true;
}

// Frees what pm_release parked once the GPU has finished its frame (all of it when `all`), and commits residency changes so the
// command buffer about to be committed sees every allocation created since. Render thread, before each commit.
API_AVAILABLE(macos(26.0))
static void m4_sync(PMDevice *d, bool all) {
    id<MTLResidencySet> set = PM_OBJ(id<MTLResidencySet>, d->m4Residency);
    uint64_t done = all ? UINT64_MAX : atomic_load(&d->m4Done);
    pthread_mutex_lock(&d->m4Lock);
    int keep = 0;
    for (int i = 0; i < d->m4GraveCount; i++) {
        PMGrave g = d->m4Grave[i];
        if (g.frame > done) { d->m4Grave[keep++] = g; continue; }
        if (pm_kind((__bridge id)g.object) == 1 && [set containsAllocation:(__bridge id<MTLAllocation>)g.object]) {
            [set removeAllocation:(__bridge id<MTLAllocation>)g.object];
            d->m4Dirty = 1;
        }
        CFRelease(g.object);
    }
    d->m4GraveCount = keep;
    if (d->m4Dirty) {
        [set commit];
        d->m4Dirty = 0;
    }
    pthread_mutex_unlock(&d->m4Lock);
}

// Waits (bounded) until the frame that last used the slot of the frame being recorded has finished, so its allocator can be reset.
// With -Dpuremetal.profile the slot also waits for its commit feedback to resolve the pass timestamps out of its counter heap.
API_AVAILABLE(macos(26.0))
static bool m4_slot_ready(PMDevice *d) {
    int slot = (int)(d->m4Seq % PM_M4_FRAMES);
    // Frames before the pool is full never wait on a previous frame (there is none), but a heap already submitted on this slot may
    // still be resolving.
    uint64_t need = d->m4Seq > PM_M4_FRAMES ? d->m4Seq - PM_M4_FRAMES : 0;
    if (atomic_load(&d->m4Done) >= need && !atomic_load(&d->m4TsBusy[slot])) return true;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 5;
    pthread_mutex_lock(&d->lock);
    int rc = 0;
    while ((atomic_load(&d->m4Done) < need || atomic_load(&d->m4TsBusy[slot])) && rc == 0) {
        rc = pthread_cond_timedwait(&d->cond, &d->lock, &deadline);
    }
    pthread_mutex_unlock(&d->lock);
    if (atomic_load(&d->m4Done) >= need && !atomic_load(&d->m4TsBusy[slot])) return true;
    set_error(d, @"the GPU did not release a command allocator within 5s");
    return false;
}

// Begins the frame's command buffer (or the early-upload one) on this frame slot's allocator, reset once per frame.
API_AVAILABLE(macos(26.0))
static id<MTL4CommandBuffer> m4_begin_cb(PMDevice *d, bool upload) {
    if (!m4_slot_ready(d)) return nil;
    int slot = (int)(d->m4Seq % PM_M4_FRAMES);
    if (!upload) d->m4Track.started = false; // the frame's first encoder prepares the tracker
    uint64_t *armed = upload ? &d->m4UploadArmed : &d->m4Armed;
    id<MTL4CommandAllocator> alloc = PM_OBJ(id<MTL4CommandAllocator>, upload ? d->m4UploadAlloc[slot] : d->m4Alloc[slot]);
    if (*armed != d->m4Seq) {
        [alloc reset];
        *armed = d->m4Seq;
    }
    id<MTL4CommandBuffer> cb = PM_OBJ(id<MTL4CommandBuffer>, upload ? d->m4UploadCb[slot] : d->m4Cb[slot]);
    if (!upload) {
        // No span is open when a frame's command buffer begins. Set it unconditionally: with profiling off the heap pointers are
        // nil, and a close must find nothing to write into. The sample lists of the slot reset only when they are used: the
        // previous frame on this slot has already resolved them (m4_slot_ready).
        d->m4TsOpen = -1;
        d->m4TsPass = -1;
        if (d->m4Profile) {
            d->m4Ts[slot].used = 0;
            d->m4Ts[slot].count = 0;
        }
    }
    [cb beginCommandBufferWithAllocator:alloc];
    return cb;
}

// The resource a texture access conflicts on: the buffer behind a texel buffer's texture, else the root of a view chain.
static void *tex_key(id<MTLTexture> t) {
    for (id<MTLTexture> p = t.parentTexture; p; p = t.parentTexture) t = p;
    id<MTLBuffer> b = t.buffer;
    return b ? (__bridge void *)b : (__bridge void *)t;
}

static void *tex_key_ptr(void *texture) { return tex_key(PM_OBJ(id<MTLTexture>, texture)); }

// ---------------------------------------------------------------------------------------------------------------
// -Dpuremetal.m4BarrierLog=N: one NSLog line per barrier and fence the Metal 4 path issues, for N frames after the first
// -Dpuremetal.m4BarrierLogSkip=K (PM_M4_LOG_SKIP by default). It answers the question the Metal System Trace raised (nothing on
// Metal 3 orders a pass's vertex work behind an earlier pass's fragment work, so the two overlap; what on Metal 4 would?): every queue
// barrier, fence and encoder-local barrier is listed with the frame, the encoder that recorded it, its direction, the call site, the
// resource and both stage sets. 0, the default, means every hook below returns on its first test, so the path costs one branch per
// barrier.

// The dump's per-encoder attribution state, and the reason it is per thread: the render thread records into the frame's command
// buffer while the presenter records into its own on another thread, so a shared copy let a presenter encoder relabel the render
// thread's following barrier lines (or turn them into encoder -1). Each thread names its encoder here, and the barrier and bind
// lines that follow look it up. The label buffer is part of that state, so the two threads never format into the same string.
typedef struct {
    uint64_t frame;   // frame the index below belongs to (the frame command buffer's counter restarts with it)
    uint32_t next;    // next encoder index of this thread's command buffer
    uint32_t index;   // index of the encoder `enc` stands for
    void *enc;        // encoder the following barrier lines are attributed to (pointer compare only, no retain)
    char kind[96];    // that encoder's kind and label
} PMM4LogCtx;

static _Thread_local PMM4LogCtx pm_m4_log;

// MTLStages as letters: V, F, B, D(ispatch), A(ll). All is a mask of every bit, not a bit of its own, so it is decoded apart
// from the four named stages (and anything left over after them shows as `?`).
API_AVAILABLE(macos(26.0))
static void m4_log_stages(MTLStages stages, char *out, size_t cap) {
    static const struct { MTLStages bit; char letter; } letters[] = {
        { MTLStageVertex, 'V' }, { MTLStageFragment, 'F' }, { MTLStageBlit, 'B' }, { MTLStageDispatch, 'D' },
    };
    if (!stages) { snprintf(out, cap, "-"); return; }
    if ((NSUInteger)stages == (NSUInteger)MTLStageAll) { snprintf(out, cap, "A"); return; }
    size_t n = 0;
    NSUInteger left = (NSUInteger)stages;
    for (size_t i = 0; i < sizeof(letters) / sizeof(letters[0]) && n + 1 < cap; i++) {
        if (stages & letters[i].bit) { out[n++] = letters[i].letter; left &= ~(NSUInteger)letters[i].bit; }
    }
    if (left && n + 1 < cap) out[n++] = '?';
    out[n] = 0;
}

// The frame's inline-constant chunks are ordinary buffers to the tracker: name them, since a barrier that names one is a barrier
// against buffers written on the CPU every frame.
static bool m4_log_inline(PMDevice *d, void *res) {
    int slot = (int)(atomic_load(&d->m4Seq) % PM_M4_FRAMES);
    for (int i = 0; i < d->m4ChunkCount[slot]; i++) if (d->m4Chunks[slot][i] == res) return true;
    return false;
}

// A short identity of the resource a barrier names: kind, pointer, size (buffers) or format (textures).
static void m4_log_resource(PMDevice *d, void *res, char *out, size_t cap) {
    if (!res) { snprintf(out, cap, "none"); return; }
    id obj = PM_OBJ(id, res);
    if ([obj conformsToProtocol:@protocol(MTLBuffer)]) {
        snprintf(out, cap, "buffer %p %lluB%s", res, (unsigned long long)((id<MTLBuffer>)obj).length,
                 m4_log_inline(d, res) ? " inline-ring" : "");
    } else if ([obj conformsToProtocol:@protocol(MTLTexture)]) {
        id<MTLTexture> t = (id<MTLTexture>)obj;
        snprintf(out, cap, "texture %p %lux%lu fmt %lu", res, (unsigned long)t.width, (unsigned long)t.height,
                 (unsigned long)t.pixelFormat);
    } else {
        snprintf(out, cap, "resource %p", res);
    }
}

// Is this one of the frames the option asks for? Frames start at 1, so the first logged one is m4LogSkip + 1.
static bool m4_log_frame(PMDevice *d, uint64_t frame) {
    return frame > d->m4LogSkip && frame - d->m4LogSkip <= (uint64_t)d->m4BarrierLog;
}

// Names an encoder as it is created, so the barrier lines that follow it carry an index and a label. `frameCb` selects the frame
// command buffer's counter (restarted when the recorder moves to the next frame) from the presenter's, which records on another
// thread into another command buffer and therefore keeps its own attribution state.
API_AVAILABLE(macos(26.0))
static void m4_log_encoder(PMDevice *d, id<MTL4CommandEncoder> e, bool frameCb, const char *kind, const char *label) {
    if (!d->m4BarrierLog) return;
    uint64_t frame = atomic_load(&d->m4Seq);
    PMM4LogCtx *c = &pm_m4_log;
    if (frameCb && c->frame != frame) { c->frame = frame; c->next = 0; }
    c->index = c->next++;
    c->enc = (__bridge void *)e;
    // Formatted on the stack, then copied into this thread's context: the string the following lines print is per thread, so the
    // presenter's label and the render thread's never share a formatting buffer.
    char kindText[96] = {0};
    snprintf(kindText, sizeof(kindText), "%s%s%s", kind, label && label[0] ? ": " : "", label && label[0] ? label : "");
    memcpy(c->kind, kindText, sizeof(c->kind));
    if (!m4_log_frame(d, frame)) return;
    NSLog(@"Pure Metal m4 order: frame %llu encoder %u %s (created)", (unsigned long long)frame, (unsigned int)c->index, c->kind);
}

// One barrier line. `kind` is the barrier's direction, so a queue-wide wait for earlier work is never counted as a barrier that
// holds later encoders back: "consumer" for barrierAfterQueueStages: (waits for prior queue work before this encoder's stages),
// "producer" for barrierAfterStages:beforeQueueStages: (holds back later encoders' stages), "intra" for
// barrierAfterEncoderStages:beforeEncoderStages:, "fence" for the fence marker's own queue-wide wait (m4_fence). `site` is the
// function that issued it, `role` what the resource is to the encoder (attachment, copy destination, ...), `res` the tracked buffer
// or root texture (nil when the barrier names no resource), and `full` says whether it is a queue-wide full barrier rather than a
// tracked one. `after` / `before` are the stages the barrier names in its own terms: the stages that must finish, then the stages
// held until they have.
API_AVAILABLE(macos(26.0))
static void m4_log_barrier(PMDevice *d, id<MTL4CommandEncoder> e, const char *kind, const char *site, const char *role, void *res,
                           MTLStages after, MTLStages before, bool full) {
    if (!d->m4BarrierLog) return;
    uint64_t frame = atomic_load(&d->m4Seq);
    if (!m4_log_frame(d, frame)) return;
    PMM4LogCtx *c = &pm_m4_log;
    bool current = (__bridge void *)e == c->enc;
    char afterText[16], beforeText[16], resText[128];
    m4_log_stages(after, afterText, sizeof(afterText));
    m4_log_stages(before, beforeText, sizeof(beforeText));
    m4_log_resource(d, res, resText, sizeof(resText));
    NSLog(@"Pure Metal m4 order: frame %llu encoder %d %s kind=%s %s site=%s after=%s before=%s res=%s role=%s%s",
          (unsigned long long)frame, current ? (int)c->index : -1, current ? c->kind : "?", kind,
          full ? "FULL" : "barrier", site, afterText, beforeText, resText, role ? role : "-",
          full ? " (full barrier)" : "");
}

API_AVAILABLE(macos(26.0))
static void m4_full_barrier(PMDevice *d, id<MTL4CommandEncoder> e, const char *kind, const char *site, const char *role, void *res,
                            MTLStages before) {
    [e barrierAfterQueueStages:MTLStageAll beforeStages:before visibilityOptions:MTL4VisibilityOptionDevice];
    PM_ENC_COUNT(PM_ENC_BARRIERS, 1);
    if (d->m4BarrierLog) m4_log_barrier(d, e, kind, site, role, res, MTLStageAll, before, true);
}

// Waits for everything before it in the queue and holds everything after it back: MetalFX encodes its own passes into the command
// buffer, and Metal 4 has nothing else ordering them behind the pass that produced their input. It also starts a tracking epoch:
// after it, nothing recorded earlier can still be running, so the tracker forgets it.
API_AVAILABLE(macos(26.0))
static void m4_fence(PMDevice *d, id<MTL4CommandBuffer> cb) {
    id<MTL4ComputeCommandEncoder> e = [cb computeCommandEncoder];
    if (!e) return;
    PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
    if (d->m4BarrierLog) m4_log_encoder(d, e, true, "compute", "fence");
    m4_full_barrier(d, e, "fence", "m4_fence", "none", NULL, MTLStageDispatch | MTLStageBlit);
    if (!d->m4Scratch) {
        id<MTLBuffer> scratch = [DEV_MTL(d) newBufferWithLength:16 options:MTLResourceStorageModePrivate];
        d->m4Scratch = pm_retain(scratch);
    }
    [e fillBuffer:PM_OBJ(id<MTLBuffer>, d->m4Scratch) range:NSMakeRange(0, 4) value:0];
    // barrierAfterStages:beforeQueueStages: names the fill's blit work first and the queue stages it holds back second (this used to
    // be logged the other way round, which read like a consumer wait for the whole queue).
    [e barrierAfterStages:MTLStageBlit beforeQueueStages:MTLStageAll visibilityOptions:MTL4VisibilityOptionDevice];
    if (d->m4BarrierLog) m4_log_barrier(d, e, "producer", "m4_fence", "scratch fill", d->m4Scratch, MTLStageBlit, MTLStageAll,
                                        true);
    [e endEncoding];
    PMM4Track *t = &d->m4Track;
    t->gen++;
    t->count = 0;
    t->poisoned = false;
    t->started = true;
    // The epoch reset forgets what earlier frames still have in flight: the early uploads of this frame execute before this fence in
    // queue order (they are committed ahead of the whole frame buffer), so they cannot rely on it to order their writes against those
    // forgotten reads. The next early copy waits for the whole queue instead (see pm_copy_buffer_early).
    d->m4UploadNeedsFull = true;
}

API_AVAILABLE(macos(26.0))
static PMM4Use *m4_track_find(PMM4Track *t, void *res) {
    uint32_t i = (uint32_t)((((uint64_t)(uintptr_t)res >> 4) * 0x9E3779B97F4A7C15ull) >> 50) & (PM_M4_TRACK_SLOTS - 1);
    for (uint32_t n = 0; n < PM_M4_TRACK_SLOTS; n++, i = (i + 1) & (PM_M4_TRACK_SLOTS - 1)) {
        PMM4Use *u = &t->uses[i];
        if (u->gen != t->gen) {
            if (t->count >= PM_M4_TRACK_MAX) return NULL;
            *u = (PMM4Use){ .res = res, .gen = t->gen };
            t->count++;
            return u;
        }
        if (u->res == res) return u;
    }
    return NULL;
}

// Records that the encoder being recorded (`e`, running `stages`) reads and/or writes `res`, and first encodes a barrier if an
// earlier encoder (of this command buffer or of a frame still in flight) wrote it, or, for a write, read it: waiting only for the
// stages of those accesses, only for the stages of this encoder, and only once per encoder, stage and kind of access. When the
// table is full the encoder gets a full barrier instead (once per stage it has not covered yet) and the next encoder starts a new
// epoch. `role` only names the resource's part in the pass for -Dpuremetal.m4BarrierLog.
API_AVAILABLE(macos(26.0))
static void m4_use(PMDevice *d, id<MTL4CommandEncoder> e, void *res, MTLStages stages, bool read, bool write, const char *role) {
    PMM4Track *t = &d->m4Track;
    PMM4Use *u = m4_track_find(t, res);
    if (!u) {
        // No room to track the resource: a full barrier covers this encoder's stages, remembered so a later untracked access in a
        // stage no fallback of this encoder covered yet still gets its wait instead of relying on one issued for another stage.
        // fullStages belongs to the current encoder: m4_prepare clears it whenever one starts, so it never outlives its encoder.
        uint32_t uncovered = (uint32_t)stages & ~t->fullStages;
        if (uncovered) {
            m4_full_barrier(d, e, "consumer", "m4_use (tracker full)", role, res, (MTLStages)uncovered);
            t->fullStages |= uncovered;
        }
        t->poisoned = true;
        return;
    }
    if (u->enc != t->serial) {
        u->prevW |= u->curW;
        u->prevR |= u->curR;
        u->curW = u->curR = 0;
        u->enc = t->serial;
        u->ordered = 0;
        u->ordR = u->ordW = 0;
    }
    uint32_t need = write ? (u->prevW | u->prevR) : u->prevW;
    if (d->m4PreciseBarriers) {
        // m4PreciseBarriers: part of the earlier writes may already be ordered for the reader by a producer barrier
        // (m4_publish_fragment_writes, end_uploads): those writes are complete and visible at the reader's stages, so this read
        // waits only for the rest. A write still waits for everything (it needs the earlier readers and writers out of the way).
        // A publication only covers the stages its barrier named (pubV: the vertex stage, pubA: the render and dispatch stages,
        // pubQ: every stage), so with several stages in one access the wait is only dropped for what all of them have published:
        // a read at the vertex and fragment stages still waits at the fragment stage, and a copy at the blit stage only accepts
        // a publication that named the blit stage.
        if (!write) {
            uint32_t published = u->pubV | u->pubA | u->pubQ;
            if (stages & (MTLStageFragment | MTLStageDispatch)) published &= u->pubA | u->pubQ;
            if (stages & MTLStageBlit) published &= u->pubQ;
            need &= ~published;
        }
        // Hold back only the stages of this access that no barrier of this encoder covers yet: an earlier barrier issued for a
        // fragment read does not order a vertex read, and this access needs nothing at the stages it already waited for.
        uint32_t wait = (uint32_t)stages & ~(write ? u->ordW : u->ordR);
        if (need && wait) {
            [e barrierAfterQueueStages:(MTLStages)need beforeStages:(MTLStages)wait visibilityOptions:MTL4VisibilityOptionDevice];
            PM_ENC_COUNT(PM_ENC_BARRIERS, 1);
            if (d->m4BarrierLog) m4_log_barrier(d, e, "consumer", "m4_use", role, res, (MTLStages)need, (MTLStages)wait, false);
            u->ordR |= wait; // a write barrier waits for everything a read barrier does, so it covers reads too
            if (write) u->ordW |= wait;
        }
    } else {
        uint8_t kind = write ? 2 : 1;
        if (need && !(u->ordered & kind)) {
            [e barrierAfterQueueStages:(MTLStages)need beforeStages:stages visibilityOptions:MTL4VisibilityOptionDevice];
            PM_ENC_COUNT(PM_ENC_BARRIERS, 1);
            if (d->m4BarrierLog) m4_log_barrier(d, e, "consumer", "m4_use", role, res, (MTLStages)need, stages, false);
            u->ordered |= write ? 3 : 1;
        }
    }
    if (write) {
        u->curW |= (uint32_t)stages;
        u->wframe = d->m4Seq;
        // A fresh write has not been published by any producer barrier yet.
        u->pubV &= ~(uint32_t)stages;
        u->pubA &= ~(uint32_t)stages;
        u->pubQ &= ~(uint32_t)stages;
        // The early-upload command buffer is committed ahead of the frame's, so any write a frame encoder records (before or after
        // that copy) runs after the upload's producer barrier on the queue and must not be published by it: remember the frame
        // that wrote it, so m4_publish_upload_writes can leave this write to the consumer-side tracking.
        if ((__bridge void *)e != d->uploadBlit) u->frameWSeq = d->m4Seq;
        // A fragment write of a resource vertex shaders read is published when the encoder recording it ends, so a later encoder's
        // vertex stage is ordered behind it without a queue barrier of its own (m4_publish_fragment_writes).
        if (d->m4PreciseBarriers && u->readAtVertex && (stages & MTLStageFragment)) {
            u->pendV |= (uint32_t)stages & (uint32_t)MTLStageFragment;
            t->pendV = true;
        }
    }
    if (read) {
        u->curR |= (uint32_t)stages;
        u->rframe = d->m4Seq;
        // Sticky: from now on the fragment writes of this resource are published for vertex readers as the writing encoder ends.
        if (stages & MTLStageVertex) u->readAtVertex = true;
    }
    u->frame = d->m4Seq; // the entry now belongs to this frame's history: m4_prune keeps it until that frame has finished
}

// A draw's read of a buffer or root texture, `stages` being the stages whose bindings made it reachable (a vertex attribute buffer
// is read by the vertex stage only, a sampled texture bound to the fragment stage only by that stage).
API_AVAILABLE(macos(26.0))
static inline void m4_read(PMDevice *d, id e, void *res, MTLStages stages) {
    // Without m4PreciseBarriers every read is a read by both shader stages, whatever stage the binding that made it reachable named.
    if (!d->m4PreciseBarriers) stages = MTLStageVertex | MTLStageFragment;
    if (res && !d->m4FullBarriers) m4_use(d, (id<MTL4CommandEncoder>)e, res, stages, true, false, "bound");
}

// Attachment load and store traffic is the work of the pass's fragments: the vertex stage never reads or writes an attachment, so
// under m4PreciseBarriers a dependency on one only has to wait for (and hold back) MTLStageFragment, which lets the next pass's
// vertex work start while the previous pass's fragments drain. With the switch off, both stages, as before it existed.
API_AVAILABLE(macos(26.0))
static void m4_attachment(PMDevice *d, id<MTL4CommandEncoder> e, MTLRenderPassAttachmentDescriptor *a) {
    id<MTLTexture> texture = a.texture;
    MTLStages stages = d->m4PreciseBarriers ? MTLStageFragment : (MTLStageVertex | MTLStageFragment);
    if (texture) m4_use(d, e, tex_key(texture), stages, a.loadAction == MTLLoadActionLoad, true,
                        a.loadAction == MTLLoadActionLoad ? "attachment load" : "attachment store");
}

// Drops the history of frames the GPU has finished: nothing they recorded can still be running, so the barriers this frame emits
// only have to wait for the frames still in flight. Runs at the start of a frame (m4_prepare), which is what lets the tracker keep
// its entries across frame boundaries without filling up. The survivors are reinserted under a new generation rather than holes
// being punched into the table: a freed slot would end a linear-probing chain early and hide the entries after it.
// -Dpuremetal.m4PreciseBarriers also drops the access history of the survivors' finished frames, keeping the entry itself: a
// resource a pass wrote once at the fragment stage and reads from vertex shaders every frame (the 16x16 lightmap) stays in the
// table for its reads, but the write bits of the finished frame that recorded it are gone, so it is not waited for any more.
API_AVAILABLE(macos(26.0))
static void m4_prune(PMDevice *d) {
    static PMM4Use *keep; // render thread only
    if (!keep) keep = malloc(PM_M4_TRACK_SLOTS * sizeof(PMM4Use));
    if (!keep) return; // nothing pruned: the table fills and the frame falls back to a fence
    PMM4Track *t = &d->m4Track;
    uint64_t done = atomic_load(&d->m4Done);
    uint32_t live = t->count, seen = 0, kept = 0;
    for (uint32_t i = 0; i < PM_M4_TRACK_SLOTS && seen < live; i++) {
        PMM4Use *u = &t->uses[i];
        if (u->gen != t->gen) continue;
        seen++;
        if (u->frame > done) {
            if (d->m4PreciseBarriers) {
                // Every write (read) of an entry happened at a frame <= wframe (rframe), because frames only grow: once the newest
                // one has finished, all of them have, and no barrier of this frame has to wait for any of them. This frame's own
                // accesses are stamped with m4Seq, which is newer than every finished frame, so they are never cleared here.
                if (u->wframe <= done) {
                    u->prevW = u->curW = 0;
                    u->pubV = u->pubA = u->pubQ = u->pendV = 0;
                    u->upW = false;
                }
                if (u->rframe <= done) u->prevR = u->curR = 0;
            }
            keep[kept++] = *u;
        }
    }
    if (kept == live) return;
    t->gen++;
    t->count = 0;
    for (uint32_t k = 0; k < kept; k++) {
        PMM4Use *u = m4_track_find(t, keep[k].res);
        uint32_t gen = u->gen;
        *u = keep[k];
        u->gen = gen;
    }
}

// Records a copy in the early-upload command buffer, which is committed before the frame's: its copies are earlier work on the
// queue for every encoder of the frame, whichever order they were recorded in. Recording them here is what makes the frame's reads
// of an uploaded resource wait for the copy (m4_use) now that the frame no longer starts with a fence.
API_AVAILABLE(macos(26.0))
static void m4_early_use(PMDevice *d, void *res, bool write) {
    if (!res || d->m4FullBarriers) return;
    PMM4Track *t = &d->m4Track;
    PMM4Use *u = m4_track_find(t, res);
    if (!u) {
        // No room to remember it: commit_uploads orders the whole frame behind the uploads instead, with a queue-wide barrier.
        d->m4UploadOrderAll = true;
        t->poisoned = true;
        return;
    }
    if (write) {
        u->prevW |= (uint32_t)MTLStageBlit;
        u->wframe = d->m4Seq;
    } else {
        u->prevR |= (uint32_t)MTLStageBlit;
        u->rframe = d->m4Seq;
    }
    u->frame = d->m4Seq;
}

// -Dpuremetal.m4PreciseBarriers: publishes the fragment writes this encoder just ended made to resources that some vertex shader
// reads, with a producer barrier before its endEncoding. `barrierAfterStages:beforeQueueStages:` (the SDK has no encoder-stages
// variant of it) waits for the stages named by afterStages of the commands encoded before it (this encoder's and, per
// MTL4CommandEncoder, the ones before it) and holds back the stages named by beforeQueueStages of every later encoder: the next
// encoder's vertex work is therefore ordered behind this write without a `barrierAfterQueueStages:MTLStageFragment
// beforeStages:MTLStageVertex` of its own, which would have waited for the fragment work of *every* encoder on the queue. m4_use
// sees the write in pubV and drops it from the read's wait. A fragment read of the same resource still waits (pubV covers the
// vertex stage only).
API_AVAILABLE(macos(26.0))
static void m4_publish_fragment_writes(PMDevice *d, id<MTL4CommandEncoder> e, const char *site) {
    PMM4Track *t = &d->m4Track;
    if (!d->m4PreciseBarriers || !t->pendV) return;
    t->pendV = false;
    uint32_t published = 0, live = t->count, seen = 0;
    for (uint32_t i = 0; i < PM_M4_TRACK_SLOTS && seen < live; i++) {
        PMM4Use *u = &t->uses[i];
        if (u->gen != t->gen) continue;
        seen++;
        if (!u->pendV) continue;
        published |= u->pendV;
        u->pubV |= u->pendV;
        u->pendV = 0;
    }
    if (!published) return;
    [e barrierAfterStages:(MTLStages)published beforeQueueStages:MTLStageVertex visibilityOptions:MTL4VisibilityOptionDevice];
    PM_ENC_COUNT(PM_ENC_BARRIERS, 1);
    if (d->m4BarrierLog) {
        m4_log_barrier(d, e, "producer", site, "fragment writes read at vertex", NULL, (MTLStages)published,
                       MTLStageVertex, false);
    }
}

// -Dpuremetal.m4PreciseBarriers: remembers that the early-upload encoder is the writer of `res`. The tracker already has the copy
// (m4_blit_deps records it as a blit write), but the copy may be recorded after a frame encoder has started, so the entry cannot be
// told apart by encoder serial at end_uploads: this bit is what lets the upload publish exactly the writes it made.
API_AVAILABLE(macos(26.0))
static void m4_upload_written(PMDevice *d, void *res) {
    if (!d->m4PreciseBarriers || !res) return;
    PMM4Use *u = m4_track_find(&d->m4Track, res);
    if (u) u->upW = true;
}

// -Dpuremetal.m4PreciseBarriers: the upload encoder's copies are earlier work on the queue than every encoder of the frame (its
// command buffer is committed ahead of the frame's), so its blit writes can be published once, for all render and dispatch stages,
// with a producer barrier at its end. Later readers then skip the `barrierAfterQueueStages:MTLStageBlit` they would otherwise emit
// for every uploaded buffer; writers still wait for the earlier readers (prevR) as before. `everyStage`: the queue-wide barrier
// end_uploads already encoded for an untracked upload covers the blit stage too. Returns the published stages, 0 when this upload
// wrote nothing tracked.
API_AVAILABLE(macos(26.0))
static uint32_t m4_publish_upload_writes(PMDevice *d, bool everyStage) {
    PMM4Track *t = &d->m4Track;
    if (!d->m4PreciseBarriers) return 0;
    uint32_t published = 0, live = t->count, seen = 0;
    for (uint32_t i = 0; i < PM_M4_TRACK_SLOTS && seen < live; i++) {
        PMM4Use *u = &t->uses[i];
        if (u->gen != t->gen) continue;
        seen++;
        if (!u->upW) continue;
        u->upW = false;
        // A frame encoder also wrote it in the frame being recorded: that write is after this upload's producer barrier on the
        // queue, so publishing here would let later readers skip a wait that is still owed. The normal consumer-side tracking
        // (prevW / wframe) orders that write; nothing of this upload is left to publish.
        if (u->frameWSeq == d->m4Seq) continue;
        if (everyStage) u->pubQ |= (uint32_t)MTLStageBlit;
        else u->pubA |= (uint32_t)MTLStageBlit;
        published |= (uint32_t)MTLStageBlit;
    }
    return published;
}

// Before the first encoder of the frame's command buffer, and when the tracker cannot follow any more: a fresh epoch. The first
// encoder of a frame otherwise keeps the tracker's history (m4_prune trims what the GPU has finished), so this frame's encoders
// barrier only against the resources the frames still in flight touched.
API_AVAILABLE(macos(26.0))
static void m4_prepare(PMDevice *d, id<MTL4CommandBuffer> cb) {
    PMM4Track *t = &d->m4Track;
    // Every encoder starts with no fallback barrier of its own, whatever encoder a previous serial number belonged to.
    t->fullStages = 0;
    bool first = !t->started;
    if (first) {
        t->started = true;
        if (d->m4FrameFence || t->poisoned) {
            atomic_fetch_add(&d->m4FrameFences, 1);
            m4_fence(d, cb);
        } else {
            m4_prune(d);
            if (t->count >= PM_M4_TRACK_SOFT) {
                atomic_fetch_add(&d->m4FrameFences, 1);
                m4_fence(d, cb);
            }
        }
    } else if (t->poisoned || t->count >= PM_M4_TRACK_SOFT) {
        m4_fence(d, cb);
    }
    t->serial++;
}

// -Dpuremetal.profile on Metal 4: GPU timestamps written into the frame slot's MTL4CounterHeap. Every hook below tests this
// first, so with the option off (or a slot whose heap could not be created) no timestamp is written at all: the default path
// only reads one int. m4TsOpen/m4TsPass stay -1 then, so a close finds nothing to write.
API_AVAILABLE(macos(26.0))
static bool m4_ts_ready(PMDevice *d) {
    return d->m4Profile && d->m4TsHeap[(int)(d->m4Seq % PM_M4_FRAMES)] != NULL;
}

API_AVAILABLE(macos(26.0))
static bool m4_ts_room(PMDevice *d, int entries, int pairs) {
    if (!m4_ts_ready(d)) return false;
    int slot = (int)(d->m4Seq % PM_M4_FRAMES);
    if (d->m4Ts[slot].used + entries > PM_M4_TS_ENTRIES || d->m4Ts[slot].count + pairs > PM_M4_TS_PAIRS) {
        // The heap holds 128 entries / 64 samples per frame slot. Beyond that the sample is dropped and counted
        // (pm_take_stat 37) instead of being folded into another bin or read from an entry that was never written.
        atomic_fetch_add(&d->m4TsDropped, (uint64_t)pairs);
        return false;
    }
    return true;
}

API_AVAILABLE(macos(26.0))
static id<MTL4CounterHeap> m4_ts_heap(PMDevice *d) {
    return PM_OBJ(id<MTL4CounterHeap>, d->m4TsHeap[(int)(d->m4Seq % PM_M4_FRAMES)]);
}

// A compute or blit encoder opens a span: its first timestamp is written into the encoder itself, its second when the next encoder
// starts (or before the frame is committed), which is the same point on the GPU timeline as this encoder's end.
API_AVAILABLE(macos(26.0))
static void m4_ts_span_open(PMDevice *d, id<MTL4ComputeCommandEncoder> e) {
    d->m4TsOpen = -1;
    if (!m4_ts_room(d, 2, 1)) return;
    int slot = (int)(d->m4Seq % PM_M4_FRAMES);
    int first = d->m4Ts[slot].used;
    [e writeTimestampWithGranularity:MTL4TimestampGranularityRelaxed intoHeap:m4_ts_heap(d) atIndex:(NSUInteger)first];
    d->m4Ts[slot].used = (uint16_t)(first + 2);
    d->m4Ts[slot].pair[d->m4Ts[slot].count].first = (uint16_t)first;
    d->m4Ts[slot].pair[d->m4Ts[slot].count].second = (uint16_t)(first + 1);
    d->m4Ts[slot].pair[d->m4Ts[slot].count++].bin = 2;
    d->m4TsOpen = first;
}

API_AVAILABLE(macos(26.0))
static void m4_ts_span_close(PMDevice *d, id<MTL4CommandBuffer> cb) {
    int first = d->m4TsOpen;
    d->m4TsOpen = -1;
    if (first < 0 || !m4_ts_ready(d)) return;
    [cb writeTimestampIntoHeap:m4_ts_heap(d) atIndex:(NSUInteger)first + 1];
}

// One render pass encoder, whatever number of logical passes were merged into it: the span starts with the encoder and closes when
// it ends, so merged passes can never lose their coverage. The samples are attributed to the encoder, i.e. the first pass's label
// (the pass the trace reports, whose merged counter says how many logical passes it carries) and the encoder's bin. A pair
// reopened at every logical pass end would charge only the first pass of a merged encoder.
API_AVAILABLE(macos(26.0))
static void m4_ts_render_open(PMDevice *d, id<MTL4RenderCommandEncoder> e) {
    d->m4TsPass = -1;
    if (!m4_ts_room(d, 3, 2)) return;
    int slot = (int)(d->m4Seq % PM_M4_FRAMES);
    int first = d->m4Ts[slot].used;
    id<MTL4CounterHeap> heap = m4_ts_heap(d);
    [e writeTimestampWithGranularity:MTL4TimestampGranularityRelaxed afterStage:MTLRenderStageVertex intoHeap:heap atIndex:(NSUInteger)first];
    d->m4Ts[slot].used = (uint16_t)(first + 3);
    // Bin 0 is the encoder's whole span: start to its last fragment work, the primary render number. Bin 1 is only the tail after
    // its last vertex work (completion, not fragment shading time), reported as such.
    d->m4Ts[slot].pair[d->m4Ts[slot].count].first = (uint16_t)first;
    d->m4Ts[slot].pair[d->m4Ts[slot].count].second = (uint16_t)(first + 2);
    d->m4Ts[slot].pair[d->m4Ts[slot].count++].bin = 0;
    d->m4Ts[slot].pair[d->m4Ts[slot].count].first = (uint16_t)(first + 1);
    d->m4Ts[slot].pair[d->m4Ts[slot].count].second = (uint16_t)(first + 2);
    d->m4Ts[slot].pair[d->m4Ts[slot].count++].bin = 1;
    d->m4TsPass = first;
}

// Writes the encoder's last two timestamps. Must run before the encoder is ended; end_parked_actions and the two upscale encoders
// are the only places a render encoder ends.
API_AVAILABLE(macos(26.0))
static void m4_ts_render_close(PMDevice *d, id<MTL4RenderCommandEncoder> e) {
    int first = d->m4TsPass;
    d->m4TsPass = -1;
    if (first < 0 || !e || !m4_ts_ready(d)) return;
    id<MTL4CounterHeap> heap = m4_ts_heap(d);
    [e writeTimestampWithGranularity:MTL4TimestampGranularityRelaxed afterStage:MTLRenderStageVertex intoHeap:heap atIndex:(NSUInteger)first + 1];
    [e writeTimestampWithGranularity:MTL4TimestampGranularityRelaxed afterStage:MTLRenderStageFragment intoHeap:heap atIndex:(NSUInteger)first + 2];
}

// Resolves one finished frame's heap into the Metal 3 profile bins (durations and sample counts) and frees its slot.
API_AVAILABLE(macos(26.0))
static void m4_ts_finish(PMDevice *d, uint64_t frame) {
    int slot = (int)(frame % PM_M4_FRAMES);
    id<MTL4CounterHeap> heap = PM_OBJ(id<MTL4CounterHeap>, d->m4TsHeap[slot]);
    if (d->m4Profile && heap) {
        int used = d->m4Ts[slot].used, count = d->m4Ts[slot].count;
        if (used > 0 && count > 0) {
            NSData *data = [heap resolveCounterRange:NSMakeRange(0, (NSUInteger)used)];
            if (data.length >= used * sizeof(MTL4TimestampHeapEntry)) {
                const MTL4TimestampHeapEntry *entries = data.bytes;
                for (int i = 0; i < count; i++) {
                    uint64_t ticks[2];
                    ticks[0] = entries[d->m4Ts[slot].pair[i].first].timestamp;
                    ticks[1] = entries[d->m4Ts[slot].pair[i].second].timestamp;
                    unsigned bin = d->m4Ts[slot].pair[i].bin;
                    // The entries are raw ticks: convert the difference, never the absolute values. Invalid entries read as 0 and
                    // are skipped rather than counted as a zero-length sample.
                    if (ticks[0] && ticks[1] && ticks[1] >= ticks[0]) {
                        atomic_fetch_add(&d->profileStats[bin], pm_ticks_ns(ticks[1] - ticks[0]));
                        atomic_fetch_add(&d->profileStats[bin + 3], 1);
                    }
                }
            }
        }
        d->m4Ts[slot].used = 0;
        d->m4Ts[slot].count = 0;
    }
    atomic_store(&d->m4TsBusy[slot], 0);
}

// Blit copies and compute dispatches share one encoder type. `tracked`: the frame's command buffer (barriers follow the resources
// the encoder touches, see m4_use); otherwise the encoder waits for everything before it in the queue. `stamp`: the encoder belongs
// to the render thread's frame command buffer and takes part in pass timing; the presenter's encoders pass false, so a second
// thread never touches the frame's timestamp state.
API_AVAILABLE(macos(26.0))
static id<MTL4ComputeCommandEncoder> m4_compute_encoder(PMDevice *d, id<MTL4CommandBuffer> cb, bool tracked, bool stamp,
                                                        const char *label) {
    if (stamp) m4_ts_span_close(d, cb);
    bool frameCb = tracked; // the presenter never takes this path; both of its encoder kinds are recorded below
    tracked = tracked && !d->m4FullBarriers;
    if (tracked) m4_prepare(d, cb);
    id<MTL4ComputeCommandEncoder> e = [cb computeCommandEncoder];
    if (!e) return nil;
    if (d->m4BarrierLog) m4_log_encoder(d, e, frameCb, "compute", label);
    if (stamp) m4_ts_span_open(d, e);
    if (!tracked) m4_full_barrier(d, e, "consumer", "m4_compute_encoder", "none", NULL, MTLStageDispatch | MTLStageBlit);
    return e;
}

// Starts a tracked encoder that does not begin the frame (the early-upload buffer): its own fallback barriers and stage dedup, and a
// new serial so the accesses of the encoders recorded before it (a frame still in flight included) count as previous work it must
// barrier against. The frame-boundary work (prune, fence) stays with m4_prepare, which owns the frame's first encoder.
API_AVAILABLE(macos(26.0))
static void m4_prepare_encoder(PMDevice *d) {
    d->m4Track.fullStages = 0;
    d->m4Track.serial++;
}

// -Dpuremetal.m4PreciseBarriers implies the tracked upload path (-Dpuremetal.m4TrackedUploads): a full-barrier upload encoder
// orders every stage of the queue behind its copies, which also serializes the next pass's vertex work behind the previous
// pass's fragments. With it, the frame's own command buffer is committed together with the uploads (pm_submit), so they still
// execute first without the queue-wide barrier.
static bool m4_uploads_tracked(PMDevice *d) { return d->m4TrackedUploads || d->m4PreciseBarriers; }

// The early-upload buffer's copy encoder. `tracked`: its copies wait only for the earlier accesses to the resources they touch
// (m4_use), so the uploads of one frame start while the previous one is still rendering instead of after it; otherwise the encoder
// waits for everything before it in the queue, as before.
API_AVAILABLE(macos(26.0))
static id<MTL4ComputeCommandEncoder> m4_upload_encoder(PMDevice *d, id<MTL4CommandBuffer> cb, bool tracked) {
    tracked = tracked && !d->m4FullBarriers;
    if (tracked) m4_prepare_encoder(d);
    id<MTL4ComputeCommandEncoder> e = [cb computeCommandEncoder];
    if (e && d->m4BarrierLog) m4_log_encoder(d, e, true, "compute", "upload");
    if (e && !tracked) m4_full_barrier(d, e, "consumer", "m4_upload_encoder", "none", NULL, MTLStageDispatch | MTLStageBlit);
    return e;
}

// The returned encoder is an MTL4RenderCommandEncoder; shared code sends it only selectors MTLRenderCommandEncoder also declares.
// `stamp`: the encoder is a render pass of the render thread's frame command buffer, so it opens a timestamp span that
// m4_ts_render_close closes when the encoder ends. The presenter's encoder passes false (other thread, other command buffer).
// `label` only names the encoder in the -Dpuremetal.m4BarrierLog dump; callers still set the Metal label they want.
API_AVAILABLE(macos(26.0))
static id<MTLRenderCommandEncoder> m4_render_encoder(PMDevice *d, id<MTL4CommandBuffer> cb, MTL4RenderPassDescriptor *rp, void *table,
                                                     bool tracked, bool stamp, const char *label) {
    // A compute/blit span still open ends here, at the render encoder that follows it.
    if (stamp) m4_ts_span_close(d, cb);
    bool frameCb = tracked; // the presenter passes false: its encoder belongs to the present command buffer
    tracked = tracked && !d->m4FullBarriers;
    if (tracked) m4_prepare(d, cb);
    id<MTL4RenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
    if (!e) return nil;
    if (d->m4BarrierLog) m4_log_encoder(d, e, frameCb, "render", label);
    if (tracked) {
        // Attachments are read (when loaded) and written by the whole pass: their barriers go in before any draw.
        for (NSUInteger i = 0; i < PM_MAX_COLOR; i++) m4_attachment(d, e, rp.colorAttachments[i]);
        m4_attachment(d, e, rp.depthAttachment);
        m4_attachment(d, e, rp.stencilAttachment);
    } else {
        m4_full_barrier(d, e, "consumer", "m4_render_encoder", "none", NULL, MTLStageVertex | MTLStageFragment);
    }
    // After the pass barriers: the span measures the pass's own work, not the wait it needed first.
    if (stamp) m4_ts_render_open(d, e);
    [e setArgumentTable:PM_OBJ(id<MTL4ArgumentTable>, table) atStages:MTLRenderStageVertex | MTLRenderStageFragment];
    return (id)e;
}

// Pass descriptors expose the same attachment accessors on both paths, so pass setup is shared.
static MTLRenderPassDescriptor *new_pass_descriptor(PMDevice *d) {
    PM_IF_M4(d) {
        MTL4RenderPassDescriptor *rp = [MTL4RenderPassDescriptor new];
        // Unset, Metal sizes the tiles itself; the Metal 3 path never sets them either.
        if (d->m4TileSize) {
            rp.tileWidth = d->m4TileSize;
            rp.tileHeight = d->m4TileSize;
        }
        return (MTLRenderPassDescriptor *)rp;
    }
    return [MTLRenderPassDescriptor renderPassDescriptor];
}

// Encoder opens by kind, for the bench (pm_take_stat 29..34). Clears are counted apart from the passes they break, and are not
// bracketed by the pass timestamps (only kind 0, a real render pass, opens a span).
static id<MTLRenderCommandEncoder> new_render_encoder_kind(PMDevice *d, id<MTLCommandBuffer> cb, MTLRenderPassDescriptor *rp, int kind,
                                                           const char *label) {
    id<MTLRenderCommandEncoder> e = nil;
    if (d->m4) {
        if (@available(macOS 26.0, *)) {
            e = m4_render_encoder(d, (id<MTL4CommandBuffer>)cb, (MTL4RenderPassDescriptor *)rp, d->m4Table, true, kind == 0, label);
        }
    } else {
        e = [cb renderCommandEncoderWithDescriptor:rp];
    }
    if (e) atomic_fetch_add(&d->encCounts[kind], 1);
    return e;
}

static id<MTLRenderCommandEncoder> new_render_encoder(PMDevice *d, id<MTLCommandBuffer> cb, MTLRenderPassDescriptor *rp, const char *label) {
    return new_render_encoder_kind(d, cb, rp, 0, label);
}

// Copies `length` bytes into this frame's inline-constant chunks (Metal 4 has no setBytes) and returns their GPU address.
API_AVAILABLE(macos(26.0))
static MTLGPUAddress m4_inline(PMDevice *d, const void *data, NSUInteger length) {
    int slot = (int)(d->m4Seq % PM_M4_FRAMES);
    if (d->m4ChunkSeq != d->m4Seq) {
        d->m4ChunkSeq = d->m4Seq;
        d->m4ChunkCur = -1;
        d->m4ChunkUsed = 0;
    }
    uint32_t offset = (d->m4ChunkUsed + 255u) & ~255u;
    if (d->m4ChunkCur < 0 || offset > PM_M4_CHUNK_BYTES || length > PM_M4_CHUNK_BYTES - offset) {
        int next = d->m4ChunkCur + 1;
        if (next >= PM_M4_CHUNKS) { set_error(d, @"too many inline constants in one frame"); return 0; }
        if (next >= d->m4ChunkCount[slot]) {
            id<MTLBuffer> chunk = [DEV_MTL(d) newBufferWithLength:PM_M4_CHUNK_BYTES options:MTLResourceStorageModeShared];
            if (!chunk) { set_error(d, @"could not create an inline constant buffer"); return 0; }
            chunk.label = @"Pure Metal inline constants";
            d->m4Chunks[slot][next] = pm_retain(chunk);
            d->m4ChunkCount[slot] = next + 1;
        }
        d->m4ChunkCur = next;
        offset = 0;
    }
    id<MTLBuffer> chunk = PM_OBJ(id<MTLBuffer>, d->m4Chunks[slot][d->m4ChunkCur]);
    memcpy((char *)chunk.contents + offset, data, length);
    d->m4ChunkUsed = offset + (uint32_t)length;
    return chunk.gpuAddress + offset;
}

// Writes of the render table (d->m4Table), skipped when the slot already holds the value. Counted when they reach Metal.
API_AVAILABLE(macos(26.0))
static void m4_table_address(PMDevice *d, NSUInteger index, uint64_t address) {
    PMTableCache *c = &d->m4Cache;
    uint32_t bit = 1u << index;
    if ((c->bufferSet & bit) && c->buffer[index] == address) return;
    [PM_OBJ(id<MTL4ArgumentTable>, d->m4Table) setAddress:address atIndex:index];
    c->buffer[index] = address;
    c->bufferSet |= bit;
    PM_ENC_COUNT(PM_ENC_BUFFER_BINDS, 1);
}

API_AVAILABLE(macos(26.0))
static void m4_table_texture(PMDevice *d, NSUInteger index, id<MTLTexture> t) {
    PMTableCache *c = &d->m4Cache;
    uint32_t bit = 1u << index;
    MTLResourceID rid = t.gpuResourceID;
    if ((c->textureSet & bit) && c->texture[index] == rid._impl) return;
    [PM_OBJ(id<MTL4ArgumentTable>, d->m4Table) setTexture:rid atIndex:index];
    c->texture[index] = rid._impl;
    c->textureSet |= bit;
    PM_ENC_COUNT(PM_ENC_TEXTURE_BINDS, 1);
}

API_AVAILABLE(macos(26.0))
static void m4_table_sampler(PMDevice *d, NSUInteger index, id<MTLSamplerState> s) {
    PMTableCache *c = &d->m4Cache;
    uint32_t bit = 1u << index;
    MTLResourceID rid = s.gpuResourceID;
    if ((c->samplerSet & bit) && c->sampler[index] == rid._impl) return;
    [PM_OBJ(id<MTL4ArgumentTable>, d->m4Table) setSamplerState:rid atIndex:index];
    c->sampler[index] = rid._impl;
    c->samplerSet |= bit;
    PM_ENC_COUNT(PM_ENC_TEXTURE_BINDS, 1);
}

API_AVAILABLE(macos(26.0))
static void m4_set_buffer(PMDevice *d, NSUInteger index, id<MTLBuffer> buffer, uint64_t offset) {
    m4_table_address(d, index, buffer ? buffer.gpuAddress + offset : 0);
}

// Binds inline bytes at a buffer slot through the frame's constant chunks.
API_AVAILABLE(macos(26.0))
static void m4_set_bytes(PMDevice *d, NSUInteger index, const void *data, NSUInteger length) {
    m4_table_address(d, index, m4_inline(d, data, length));
}

// Before a copy in a blit encoder: a barrier if it reads something an earlier copy in the encoder wrote, or writes something it used;
// for the frame's command buffer (`tracked`) also the barrier against earlier encoders, see m4_use. src / dst are buffers or
// texture keys (tex_key; either may be NULL).
API_AVAILABLE(macos(26.0))
static void m4_blit_deps(PMDevice *d, PMBlitHazard *h, id<MTL4ComputeCommandEncoder> e, void *src, void *dst, bool tracked) {
    if (tracked && !d->m4FullBarriers) {
        if (src) m4_use(d, e, src, MTLStageBlit, true, false, "copy source");
        if (dst) m4_use(d, e, dst, MTLStageBlit, false, true, "copy destination");
    }
    bool conflict = h->count + 2 > PM_M4_HAZARDS;
    for (int i = 0; i < h->count && !conflict; i++) {
        conflict = (src && h->resource[i] == src && h->written[i]) || (dst && h->resource[i] == dst);
    }
    if (conflict) {
        [e barrierAfterEncoderStages:MTLStageBlit beforeEncoderStages:MTLStageBlit visibilityOptions:MTL4VisibilityOptionDevice];
        if (d->m4BarrierLog) {
            m4_log_barrier(d, e, "intra", "m4_blit_deps", "earlier copy in this encoder", dst ? dst : src, MTLStageBlit,
                           MTLStageBlit, false);
        }
        h->count = 0;
    }
    if (src) { h->resource[h->count] = src; h->written[h->count++] = false; }
    if (dst) { h->resource[h->count] = dst; h->written[h->count++] = true; }
}

// Commits one or two command buffers on the frame queue, in that order: the GPU starts them in that order, so an earlier buffer's copies
// are earlier work on the queue for every encoder of the later one. finished(start, end, failure) runs when the GPU is done with all of
// them (failure is the error text). One commit is one unit of completion: the feedback comes after the last buffer in it.
API_AVAILABLE(macos(26.0))
static void m4_commit_buffers(PMDevice *d, id<MTL4CommandBuffer> first, id<MTL4CommandBuffer> second,
                              void (^finished)(CFTimeInterval, CFTimeInterval, NSString *)) {
    [first endCommandBuffer];
    if (second) [second endCommandBuffer];
    m4_sync(d, false);
    MTL4CommitOptions *options = [MTL4CommitOptions new];
    if (finished) {
        [options addFeedbackHandler:^(id<MTL4CommitFeedback> feedback) {
            @autoreleasepool {
                finished(feedback.GPUStartTime, feedback.GPUEndTime, feedback.error ? feedback.error.localizedDescription : nil);
            }
        }];
    }
    id<MTL4CommandBuffer> list[2] = { first, second ? second : first };
    [PM_OBJ(id<MTL4CommandQueue>, d->m4Queue) commit:list count:second ? 2 : 1 options:options];
}

API_AVAILABLE(macos(26.0))
static void m4_commit(PMDevice *d, id<MTL4CommandBuffer> cb, void (^finished)(CFTimeInterval, CFTimeInterval, NSString *)) {
    m4_commit_buffers(d, cb, nil, finished);
}

// Completion counters move as prefixes, never as high-water marks, because a still-in-flight submit may finish after a newer one:
// the feedback handlers are not ordered against each other. `value` therefore only advances while the next index is marked finished,
// so it keeps meaning "every index <= value has finished", which is what Java's recycling (tag <= completed) and pm_wait assume.
// Both counters are fed under d->lock: done_mark, done_advance and done_mark_through expect it to be held.

// Remembers that `index` finished, ahead of the prefix. An index PM_DONE_RING or more ahead is dropped rather than remembered: its
// ring slot may still hold an older, unfinished index, and clearing that would claim work that is still on the GPU. Dropping only
// leaves the prefix short, so the wait gets longer; nothing is freed early. With Java's 2 (clamped 1..4) submits in flight plus the
// present thread's commits and the completion listener, the window is a handful of indexes wide, far inside 64.
static void done_mark(uint8_t *mark, _Atomic uint64_t *value, uint64_t index) {
    uint64_t done = atomic_load_explicit(value, memory_order_relaxed);
    if (index <= done || index - done >= PM_DONE_RING) return;
    mark[index & (PM_DONE_RING - 1)] = 1;
}

// Advances the prefix over every consecutive finished index.
static void done_advance(uint8_t *mark, _Atomic uint64_t *value) {
    uint64_t done = atomic_load_explicit(value, memory_order_relaxed);
    while (mark[(done + 1) & (PM_DONE_RING - 1)]) {
        mark[(done + 1) & (PM_DONE_RING - 1)] = 0;
        done++;
    }
    atomic_store_explicit(value, done, memory_order_release);
}

// `index` and everything below it finished: a shared event's value is a finished prefix of the queue, so no slot of the ring can be
// left marked and the prefix moves in one step. Only for sources that prove a prefix, never for one commit's feedback.
static void done_mark_through(uint8_t *mark, _Atomic uint64_t *value, uint64_t index) {
    uint64_t done = atomic_load_explicit(value, memory_order_relaxed);
    if (index <= done) return;
    if (index - done >= PM_DONE_RING) {
        memset(mark, 0, PM_DONE_RING);
        atomic_store_explicit(value, index, memory_order_release);
        return;
    }
    for (uint64_t i = done + 1; i <= index; i++) mark[i & (PM_DONE_RING - 1)] = 1;
    done_advance(mark, value);
}

// Records that submit `index` finished and moves `completed` over it. Held under d->lock; the caller broadcasts d->cond.
static void submit_finished(PMDevice *d, uint64_t index) {
    done_mark(d->doneMark, &d->completed, index);
    done_advance(d->doneMark, &d->completed);
}

// Signals the frame on the completion timeline: the queue runs the signal after the frame's command buffer, so the listener below
// learns about completion from the GPU itself instead of from the commit feedback. `index` is the submit Java waits on and advances
// with the frame number, so the difference between the two is fixed and the timeline can carry both.
API_AVAILABLE(macos(26.0))
static void m4_signal_frame(PMDevice *d, uint64_t frame, uint64_t index) {
    id<MTLSharedEvent> event = PM_OBJ(id<MTLSharedEvent>, d->m4FrameEvent);
    if (!event || !d->m4EventCompletion) return;
    if (!d->m4IndexKnown) {
        d->m4IndexBase = (int64_t)index - (int64_t)frame;
        d->m4IndexKnown = true;
    }
    // The notification holds the handlers count, so teardown waits for it like it waits for the feedback handlers. Registering
    // before the signal means the value can never be missed.
    atomic_fetch_add(&d->handlers, 1);
    [event notifyListener:PM_OBJ(MTLSharedEventListener *, d->m4CompletionListener) atValue:frame
                    block:^(id<MTLSharedEvent> signaled, uint64_t value) {
        (void)signaled;
        trace_ev(16, (int64_t)value, 0, 0); // the frame's completion event fired on the listener queue
        pthread_mutex_lock(&d->lock);
        int64_t doneIndex = (int64_t)value + d->m4IndexBase;
        if (doneIndex > 0) done_mark_through(d->doneMark, &d->completed, (uint64_t)doneIndex);
        // The timeline is a completed prefix: every frame before this one has finished. (The feedback handler may have got there first.)
        done_mark_through(d->m4DoneMark, &d->m4Done, value);
        pthread_cond_broadcast(&d->cond);
        pthread_mutex_unlock(&d->lock);
        atomic_fetch_sub(&d->handlers, 1);
    }];
    [PM_OBJ(id<MTL4CommandQueue>, d->m4Queue) signalEvent:event value:frame];
}

// Event signals are queue operations here, not commands inside the buffer: they run after the frame's buffer is committed.
API_AVAILABLE(macos(26.0))
static void m4_flush_signals(PMDevice *d) {
    for (int i = 0; i < d->m4SignalCount; i++) {
        [PM_OBJ(id<MTL4CommandQueue>, d->m4Queue) signalEvent:PM_OBJ(id<MTLEvent>, d->m4Signals[i].event) value:d->m4Signals[i].value];
    }
    d->m4SignalCount = 0;
}

API_AVAILABLE(macos(26.0))
static id<MTL4ArgumentTable> m4_new_table(id<MTLDevice> device, NSString *label, NSString **why) {
    MTL4ArgumentTableDescriptor *td = [MTL4ArgumentTableDescriptor new];
    td.maxBufferBindCount = PM_BUFFER_BINDINGS;
    td.maxTextureBindCount = PM_TEXTURE_BINDINGS;
    td.maxSamplerStateBindCount = PM_SAMPLER_BINDINGS;
    td.initializeBindings = YES;
    td.label = label;
    NSError *error = nil;
    id<MTL4ArgumentTable> table = [device newArgumentTableWithDescriptor:td error:&error];
    if (!table) *why = [NSString stringWithFormat:@"could not create an argument table: %@", error.localizedDescription];
    return table;
}

// Creates the Metal 4 queues, allocators, residency set and tables. Nothing is stored in the device unless all of it exists, so a
// failure leaves the device on Metal 3 for the session; `why` says what failed.
API_AVAILABLE(macos(26.0))
static bool m4_setup(PMDevice *d, id<MTLDevice> device, NSString **why) {
    if (![device supportsFamily:MTLGPUFamilyApple7]) { *why = @"this GPU is older than Apple7"; return false; }
    NSError *error = nil;
    MTL4CommandQueueDescriptor *qd = [MTL4CommandQueueDescriptor new];
    qd.label = @"Pure Metal";
    id<MTL4CommandQueue> queue = [device newMTL4CommandQueueWithDescriptor:qd error:&error];
    qd.label = @"Pure Metal present";
    id<MTL4CommandQueue> presentQueue = [device newMTL4CommandQueueWithDescriptor:qd error:&error];
    MTLResidencySetDescriptor *rd = [MTLResidencySetDescriptor new];
    rd.label = @"Pure Metal resources";
    rd.initialCapacity = 4096;
    id<MTLResidencySet> residency = [device newResidencySetWithDescriptor:rd error:&error];
    id<MTLSharedEvent> event = [device newSharedEvent];
    id<MTLSharedEvent> frameEvent = [device newSharedEvent];
    dispatch_queue_t completionQueue = dispatch_queue_create("puremetal.m4.completion",
        dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INITIATED, 0));
    MTLSharedEventListener *completionListener = completionQueue ? [[MTLSharedEventListener alloc] initWithDispatchQueue:completionQueue] : nil;
    if (!queue || !presentQueue || !residency || !event || !frameEvent || !completionListener) {
        *why = [NSString stringWithFormat:@"could not create the queue or residency set: %@", error.localizedDescription];
        return false;
    }

    id<MTL4ArgumentTable> table = m4_new_table(device, @"Pure Metal bindings", why);
    id<MTL4ArgumentTable> presentTable = table ? m4_new_table(device, @"Pure Metal present bindings", why) : nil;
    if (!presentTable) return false;
    if (!pm_mtl4_compiler(d, why)) return false;
    id allocs[PM_M4_FRAMES], uploadAllocs[PM_M4_FRAMES], cbs[PM_M4_FRAMES], uploadCbs[PM_M4_FRAMES];
    id presentAllocs[PM_PRESENT_SLOTS], presentCbs[PM_PRESENT_SLOTS];
    id<MTLBuffer> presentConst[PM_PRESENT_SLOTS];
    for (int i = 0; i < PM_M4_FRAMES; i++) {
        allocs[i] = [device newCommandAllocator];
        uploadAllocs[i] = [device newCommandAllocator];
        cbs[i] = [device newCommandBuffer];
        uploadCbs[i] = [device newCommandBuffer];
        if (!allocs[i] || !uploadAllocs[i] || !cbs[i] || !uploadCbs[i]) { *why = @"could not create a command allocator or buffer"; return false; }
    }
    for (int i = 0; i < PM_PRESENT_SLOTS; i++) {
        presentAllocs[i] = [device newCommandAllocator];
        presentCbs[i] = [device newCommandBuffer];
        presentConst[i] = [device newBufferWithLength:256 options:MTLResourceStorageModeShared];
        if (!presentAllocs[i] || !presentCbs[i] || !presentConst[i]) { *why = @"could not create a present command allocator or buffer"; return false; }
    }
    PMM4Use *uses = calloc(PM_M4_TRACK_SLOTS, sizeof(PMM4Use));
    if (!uses) { *why = @"could not allocate the barrier table"; return false; }
    [queue addResidencySet:residency];
    [presentQueue addResidencySet:residency];
    d->m4Track = (PMM4Track){ .uses = uses, .gen = 1 };
    pthread_mutex_init(&d->m4Lock, NULL);
    d->m4Tracked = CFDictionaryCreateMutable(NULL, 0, NULL, NULL);
    d->m4Queue = pm_retain(queue);
    d->m4PresentQueue = pm_retain(presentQueue);
    d->m4Residency = pm_retain(residency);
    d->m4Event = pm_retain(event);
    d->m4FrameEvent = pm_retain(frameEvent);
    d->m4CompletionQueue = (__bridge_retained void *)completionQueue;
    d->m4CompletionListener = pm_retain(completionListener);
    d->m4EventCompletion = true;
    d->m4EarlyUploads = true;
    d->m4TrackedUploads = false; // -Dpuremetal.m4TrackedUploads: the early-upload encoder tracks its copies instead of waiting for the queue
    d->m4TileSize = 0;           // -Dpuremetal.m4TileSize: 0 lets Metal pick the tile size, as the Metal 3 path does
    d->m4Table = pm_retain(table);
    d->m4PresentTable = pm_retain(presentTable);
    for (int i = 0; i < PM_M4_FRAMES; i++) {
        d->m4Alloc[i] = pm_retain(allocs[i]);
        d->m4UploadAlloc[i] = pm_retain(uploadAllocs[i]);
        d->m4Cb[i] = pm_retain(cbs[i]);
        d->m4UploadCb[i] = pm_retain(uploadCbs[i]);
    }
    for (int i = 0; i < PM_PRESENT_SLOTS; i++) {
        d->m4PresentAlloc[i] = pm_retain(presentAllocs[i]);
        d->m4PresentCb[i] = pm_retain(presentCbs[i]);
    }
    d->m4Seq = 1;
    // With profiling off nothing writes these, but they must already mean "no span open": a close (which every frame runs) must
    // find nothing to write into a heap that does not exist.
    d->m4TsOpen = -1;
    d->m4TsPass = -1;
    d->m4 = 1;
    g_m4Device = d;
    // From here on pm_retain tracks buffers: these join the residency set.
    for (int i = 0; i < PM_PRESENT_SLOTS; i++) d->m4PresentConst[i] = pm_retain(presentConst[i]);
    return true;
}

// 1 when the Metal 4 command model is active; reason says why it is not (empty when it was not asked for).
PM_EXPORT int pm_metal4_encoding(PMDevice *d, char *reason, int cap) {
    pm_copy_string([NSString stringWithUTF8String:d->m4Reason], reason, cap);
    return d->m4;
}

// Frees what m4_setup created, after the GPU has drained.
API_AVAILABLE(macos(26.0))
static void m4_teardown(PMDevice *d) {
    m4_sync(d, true);
    g_m4Device = NULL;
    for (int i = 0; i < PM_M4_FRAMES; i++) {
        for (int c = 0; c < d->m4ChunkCount[i]; c++) pm_release(d->m4Chunks[i][c]);
        pm_release(d->m4Alloc[i]);
        pm_release(d->m4UploadAlloc[i]);
        pm_release(d->m4Cb[i]);
        pm_release(d->m4UploadCb[i]);
        pm_release(d->m4TsHeap[i]);
    }
    for (int i = 0; i < PM_PRESENT_SLOTS; i++) {
        pm_release(d->m4PresentAlloc[i]);
        pm_release(d->m4PresentCb[i]);
        pm_release(d->m4PresentConst[i]);
    }
    pm_release(d->m4Scratch);
    pm_release(d->m4Table);
    pm_release(d->m4PresentTable);
    pm_release(d->m4Event);
    pm_release(d->m4FrameEvent);
    pm_release(d->m4CompletionListener);
    CFRelease(d->m4CompletionQueue);
    pm_release(d->m4Residency);
    pm_release(d->m4Queue);
    pm_release(d->m4PresentQueue);
    free(d->m4Grave);
    free(d->m4Track.uses);
    d->m4Track.uses = NULL;
    CFRelease(d->m4Tracked);
    pthread_mutex_destroy(&d->m4Lock);
}

// Developer-only stage-boundary timestamps. Do not split or serialize encoders to collect samples.
PM_EXPORT int pm_profile_enable(PMDevice *d) {
    @autoreleasepool {
        if (d->m4) {
            // Metal 4: per-pass timestamps come from a counter heap per frame slot (see m4_ts_*). Clears and MetalFX's own
            // passes are not bracketed, so the bins carry render passes plus compute/blit encoders only.
            if (d->m4Profile) return 1;
            if (@available(macOS 26.0, *)) {
                for (int i = 0; i < PM_M4_FRAMES; i++) {
                    MTL4CounterHeapDescriptor *hd = [MTL4CounterHeapDescriptor new];
                    hd.type = MTL4CounterHeapTypeTimestamp;
                    hd.count = PM_M4_TS_ENTRIES;
                    NSError *error = nil;
                    id<MTL4CounterHeap> heap = [DEV_MTL(d) newCounterHeapWithDescriptor:hd error:&error];
                    if (!heap) {
                        NSLog(@"Pure Metal: Metal 4 pass timestamps unavailable: %@", error.localizedDescription);
                        for (int j = 0; j < i; j++) { pm_release(d->m4TsHeap[j]); d->m4TsHeap[j] = NULL; }
                        return 0;
                    }
                    heap.label = [NSString stringWithFormat:@"Pure Metal passes %d", i];
                    d->m4TsHeap[i] = pm_retain(heap);
                }
                d->m4TsOpen = -1;
                d->m4TsPass = -1;
                d->m4Profile = 1;
                return 1;
            }
            return 0;
        }
        if (d->profileCounterSet) return 1;
        // This arm64 backend targets Apple GPUs. Resolved counter timestamps are nanoseconds,
        // unlike the raw device sampleTimestamps values, which can be Mach ticks.
        if (![DEV_MTL(d) supportsFamily:MTLGPUFamilyApple1]) return 0;
        if (![DEV_MTL(d) supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) return 0;
        for (id<MTLCounterSet> set in DEV_MTL(d).counterSets) {
            if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) {
                d->profileCounterSet = pm_retain(set);
                return 1;
            }
        }
        return 0;
    }
}

PM_EXPORT uint64_t pm_take_profile_stat(PMDevice *d, int which) {
    return which >= 0 && which < 6 ? atomic_exchange(&d->profileStats[which], 0) : 0;
}

static id<MTLCounterSampleBuffer> profile_buffer(PMDevice *d, NSUInteger count) {
    if (!d->profileCounterSet || d->profileSamples >= 64) return nil;
    MTLCounterSampleBufferDescriptor *desc = [MTLCounterSampleBufferDescriptor new];
    desc.counterSet = PM_OBJ(id<MTLCounterSet>, d->profileCounterSet);
    desc.storageMode = MTLStorageModeShared;
    desc.sampleCount = count;
    id<MTLCounterSampleBuffer> buffer = [DEV_MTL(d) newCounterSampleBufferWithDescriptor:desc error:nil];
    if (buffer) d->profileSamples++;
    return buffer;
}

static void profile_completion(PMDevice *d, id<MTLCommandBuffer> cb, id<MTLCounterSampleBuffer> buffer, bool render) {
    if (!buffer) return;
    atomic_fetch_add(&d->handlers, 1);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        @autoreleasepool {
            NSUInteger count = render ? 4 : 2;
            if (done.status == MTLCommandBufferStatusCompleted) {
                NSData *data = [buffer resolveCounterRange:NSMakeRange(0, count)];
                if (data.length >= count * sizeof(MTLCounterResultTimestamp)) {
                    const MTLCounterResultTimestamp *samples = data.bytes;
                    for (unsigned pair = 0; pair < (render ? 2U : 1U); pair++) {
                        uint64_t start = samples[pair * 2].timestamp, end = samples[pair * 2 + 1].timestamp;
                        unsigned bin = render ? pair : 2;
                        if (start && start != MTLCounterErrorValue && end != MTLCounterErrorValue && end >= start) {
                            uint64_t ns = end - start;
                            atomic_fetch_add(&d->profileStats[bin], ns);
                            atomic_fetch_add(&d->profileStats[bin + 3], 1);
                        }
                    }
                }
            }
            atomic_fetch_sub(&d->handlers, 1);
        }
    }];
}

static id<MTLCounterSampleBuffer> profile_render(PMDevice *d, MTLRenderPassDescriptor *rp) {
    id<MTLCounterSampleBuffer> buffer = profile_buffer(d, 4);
    if (buffer) {
        MTLRenderPassSampleBufferAttachmentDescriptor *a = rp.sampleBufferAttachments[0];
        a.sampleBuffer = buffer;
        a.startOfVertexSampleIndex = 0; a.endOfVertexSampleIndex = 1;
        a.startOfFragmentSampleIndex = 2; a.endOfFragmentSampleIndex = 3;
    }
    return buffer;
}

PM_EXPORT int pm_probe_enable(PMDevice *d, const char *source) {
    @autoreleasepool {
        if (d->probeQuery) return 1;
        NSError *error = nil;
        id<MTLLibrary> lib = pm_new_library(d, [NSString stringWithUTF8String:source], nil, &error);
        if (!lib) return 0;
        id<MTLComputePipelineState> read = pm_new_compute_pipeline(d, lib, @"readDepth", &error);
        id<MTLComputePipelineState> reduce = pm_new_compute_pipeline(d, lib, @"reduceDepth", &error);
        id<MTLComputePipelineState> query = pm_new_compute_pipeline(d, lib, @"queryDepth", &error);
        if (!read || !reduce || !query) return 0;
        d->probeRead = pm_retain(read); d->probeReduce = pm_retain(reduce); d->probeQuery = pm_retain(query);
        return 1;
    }
}
PM_EXPORT uint64_t pm_take_probe_stat(PMDevice *d, int which) {
    return which >= 0 && which < 6 ? atomic_exchange(&d->probeStats[which], 0) : 0;
}
PM_EXPORT void pm_take_probe_stats(PMDevice *d, uint64_t *out) {
    // A completed probe is one observation; never split its query/hidden/span fields across JFR events.
    pthread_mutex_lock(&d->lock);
    for (unsigned i=0;i<6;i++) out[i]=atomic_exchange(&d->probeStats[i],0);
    pthread_mutex_unlock(&d->lock);
}
PM_EXPORT int pm_pass_probe_queries(PMDevice *d, const PMProbeQuery *queries, int count, int reverse) {
    if (!d->probeQuery || !d->render || !d->passDepth || count <= 0) return 0;
    id<MTLTexture> depth = PM_OBJ(id<MTLTexture>, d->passDepth);
    if (depth.textureType != MTLTextureType2D || depth.sampleCount != 1 || depth.pixelFormat != MTLPixelFormatDepth32Float
            || !(depth.usage & MTLTextureUsageShaderRead) || depth.storageMode == MTLStorageModeMemoryless
            || depth.width > 16384 || depth.height > 16384 || depth.width * depth.height > 16777216
            || depth.width != (NSUInteger)d->passWidth || depth.height != (NSUInteger)d->passHeight) return 0;
    unsigned accepted = d->probeSampledSubmit ? 0 : MIN((unsigned)count, 4096 - d->probeCount);
    if (d->probeCount && d->probeReverse != (uint32_t)(reverse != 0)) accepted=0;
    if (accepted && !d->probeCount) d->probeReverse=reverse != 0;
    memcpy(d->probeQueries + d->probeCount, queries, accepted * sizeof(PMProbeQuery));
    d->probeCount += accepted;
    atomic_fetch_add(&d->probeStats[2], (unsigned)count - accepted);
    return (int)accepted;
}
static id<MTLComputeCommandEncoder> probe_encoder(id<MTLCommandBuffer> cb, id<MTLCounterSampleBuffer> samples, NSUInteger start, NSUInteger end) {
    MTLComputePassDescriptor *pd = [MTLComputePassDescriptor new];
    if (samples) {
        pd.sampleBufferAttachments[0].sampleBuffer = samples;
        pd.sampleBufferAttachments[0].startOfEncoderSampleIndex = start;
        pd.sampleBufferAttachments[0].endOfEncoderSampleIndex = end;
    }
    return [cb computeCommandEncoderWithDescriptor:pd];
}

// Hands out a free pooled slot sized for this depth target, or NULL when every slot still belongs to a submit in flight. A slot is
// only picked while nothing on the GPU can touch it (busy is cleared by probe_finish), so its buffers are rewritten safely. The
// pyramid is recreated when the target size changed; the query and result buffers never change size.
static PMProbeSlot *probe_slot(PMDevice *d, uint32_t width, uint32_t height, uint32_t total) {
    for (int i = 0; i < PM_PROBE_SLOTS; i++) {
        PMProbeSlot *s = &d->probeSlots[i];
        if (atomic_load(&s->busy)) continue;
        if (!s->queries) {
            id<MTLBuffer> q = [DEV_MTL(d) newBufferWithLength:4096 * sizeof(PMProbeQuery) options:MTLResourceStorageModeShared];
            id<MTLBuffer> r = [DEV_MTL(d) newBufferWithLength:4096 * 4 options:MTLResourceStorageModeShared];
            if (!q || !r) return NULL;
            q.label = @"Pure Metal probe queries";
            r.label = @"Pure Metal probe results";
            s->queries = pm_retain(q);
            s->results = pm_retain(r);
        }
        if (!s->pyramid || s->width != width || s->height != height) {
            id<MTLBuffer> p = [DEV_MTL(d) newBufferWithLength:total * 4 options:MTLResourceStorageModePrivate];
            if (!p) return NULL;
            p.label = @"Pure Metal probe pyramid";
            pm_release(s->pyramid);
            s->pyramid = pm_retain(p);
            s->width = width;
            s->height = height;
        }
        if (!s->samples && d->profileCounterSet && !d->m4
                && [DEV_MTL(d) supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
            MTLCounterSampleBufferDescriptor *sd = [MTLCounterSampleBufferDescriptor new];
            sd.counterSet = PM_OBJ(id<MTLCounterSet>, d->profileCounterSet);
            sd.sampleCount = 2;
            sd.storageMode = MTLStorageModeShared;
            id<MTLCounterSampleBuffer> samples = [DEV_MTL(d) newCounterSampleBufferWithDescriptor:sd error:nil];
            if (samples) s->samples = pm_retain(samples);
        }
        atomic_store(&s->busy, 1);
        return s;
    }
    return NULL;
}

// The submit that carried the slot has finished on the GPU (ok) or failed: publish one observation and free the slot.
static void probe_finish(PMDevice *d, PMProbeSlot *s, bool ok) {
    if (!s) return;
    unsigned count = s->count;
    if (ok) {
        uint64_t hidden = 0, ns = 0, valid = 0;
        const uint32_t *values = PM_OBJ(id<MTLBuffer>, s->results).contents;
        for (unsigned i = 0; i < count; i++) hidden += values[i] != 0;
        if (s->samples) {
            NSData *resolved = [PM_OBJ(id<MTLCounterSampleBuffer>, s->samples) resolveCounterRange:NSMakeRange(0, 2)];
            if (resolved.length >= 2 * sizeof(MTLCounterResultTimestamp)) {
                const MTLCounterResultTimestamp *ticks = resolved.bytes;
                if (ticks[0].timestamp != MTLCounterErrorValue && ticks[1].timestamp != MTLCounterErrorValue
                        && ticks[0].timestamp > 0 && ticks[1].timestamp > ticks[0].timestamp) {
                    ns = ticks[1].timestamp - ticks[0].timestamp;
                    valid = 1;
                }
            }
        }
        pthread_mutex_lock(&d->lock);
        atomic_fetch_add(&d->probeStats[0], count);
        atomic_fetch_add(&d->probeStats[1], hidden);
        atomic_fetch_add(&d->probeStats[4], 1);
        atomic_fetch_add(&d->probeStats[3], ns);
        atomic_fetch_add(&d->probeStats[5], valid);
        pthread_mutex_unlock(&d->lock);
    } else {
        atomic_fetch_add(&d->probeStats[2], count);
    }
    atomic_store(&s->busy, 0);
}

static void encode_probe(PMDevice *d, void *depthPtr) {
    unsigned count = d->probeCount;
    if (!count) return;
    d->probeCount = 0;
    id<MTLTexture> depth = PM_OBJ(id<MTLTexture>, depthPtr);
    typedef struct { uint32_t width,height,count,levels,offsets[16],widths[16]; } Config;
    typedef struct { uint32_t source,destination,width,height,sourceWidth,sourceHeight; } Level;
    Config c = {.width=(uint32_t)depth.width,.height=(uint32_t)depth.height,.count=count,.levels=1};
    uint32_t width=c.width,height=c.height,total=width*height;c.widths[0]=width;
    while (width>1 || height>1) {
        width=(width+1)/2;height=(height+1)/2;
        c.offsets[c.levels]=total;c.widths[c.levels++]=width;total+=width*height;
    }
    PMProbeSlot *slot = probe_slot(d, c.width, c.height, total);
    if (!slot) { atomic_fetch_add(&d->probeStats[2],count);return; }
    slot->count = count;
    id<MTLBuffer> pyramid=PM_OBJ(id<MTLBuffer>,slot->pyramid),queries=PM_OBJ(id<MTLBuffer>,slot->queries),results=PM_OBJ(id<MTLBuffer>,slot->results);
    memcpy(queries.contents, d->probeQueries, count * sizeof(PMProbeQuery));
    id<MTLCommandBuffer> cb=PM_OBJ(id<MTLCommandBuffer>,d->cb);
    PM_IF_M4(d) {
        // One tracked compute encoder. The depth attachment was written by the pass that just ended (a write the tracker knows from
        // m4_attachment), so the first dispatch waits for it; the dispatches that follow depend on each other through the pyramid, which
        // only this encoder touches, so those are ordered by barriers inside it.
        id<MTL4ComputeCommandEncoder> e = m4_compute_encoder(d, (id<MTL4CommandBuffer>)cb, true, true, "compute probe");
        if (e) {
            if (!d->m4FullBarriers) m4_use(d, e, tex_key(depth), MTLStageDispatch, true, false, "probe depth");
            [e setArgumentTable:PM_OBJ(id<MTL4ArgumentTable>, d->m4Table)];
            #define PROBE_BARRIER() [e barrierAfterEncoderStages:MTLStageDispatch beforeEncoderStages:MTLStageDispatch visibilityOptions:MTL4VisibilityOptionDevice]
            [e setComputePipelineState:PM_OBJ(id<MTLComputePipelineState>,d->probeRead)];
            m4_table_texture(d, 0, depth);
            m4_set_buffer(d, 0, pyramid, 0);
            m4_set_bytes(d, 1, &d->probeReverse, sizeof(d->probeReverse));
            [e dispatchThreads:MTLSizeMake(c.width,c.height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];
            width=c.width;height=c.height;
            for (uint32_t level=1;level<c.levels;level++) {
                Level l={c.offsets[level-1],c.offsets[level],(width+1)/2,(height+1)/2,width,height};
                PROBE_BARRIER();
                [e setComputePipelineState:PM_OBJ(id<MTLComputePipelineState>,d->probeReduce)];
                m4_set_buffer(d, 0, pyramid, 0);
                m4_set_bytes(d, 1, &l, sizeof(l));
                [e dispatchThreads:MTLSizeMake(l.width,l.height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];
                width=l.width;height=l.height;
            }
            PROBE_BARRIER();
            #undef PROBE_BARRIER
            [e setComputePipelineState:PM_OBJ(id<MTLComputePipelineState>,d->probeQuery)];
            m4_set_buffer(d, 0, pyramid, 0);
            m4_set_buffer(d, 1, queries, 0);
            m4_set_buffer(d, 2, results, 0);
            m4_set_bytes(d, 3, &c, sizeof(c));
            [e dispatchThreads:MTLSizeMake(count,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
            [e endEncoding];
        }
        if (!e) { atomic_store(&slot->busy, 0); atomic_fetch_add(&d->probeStats[2],count);return; }
        d->probeSampledSubmit=true;
        d->probeCur=slot;
        return;
    }
    {
        id<MTLCounterSampleBuffer> samples=slot->samples ? PM_OBJ(id<MTLCounterSampleBuffer>,slot->samples) : nil;
        id<MTLComputeCommandEncoder> e=probe_encoder(cb,samples,0,MTLCounterDontSample);
        [e setComputePipelineState:PM_OBJ(id<MTLComputePipelineState>,d->probeRead)];
        [e setTexture:depth atIndex:0];[e setBuffer:pyramid offset:0 atIndex:0];
        [e setBytes:&d->probeReverse length:sizeof(d->probeReverse) atIndex:1];
        [e dispatchThreads:MTLSizeMake(c.width,c.height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[e endEncoding];
        width=c.width;height=c.height;
        for (uint32_t level=1;level<c.levels;level++) {
            Level l={c.offsets[level-1],c.offsets[level],(width+1)/2,(height+1)/2,width,height};
            e=probe_encoder(cb,nil,0,0);[e setComputePipelineState:PM_OBJ(id<MTLComputePipelineState>,d->probeReduce)];
            [e setBuffer:pyramid offset:0 atIndex:0];[e setBytes:&l length:sizeof(l) atIndex:1];
            [e dispatchThreads:MTLSizeMake(l.width,l.height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[e endEncoding];
            width=l.width;height=l.height;
        }
        e=probe_encoder(cb,samples,MTLCounterDontSample,1);[e setComputePipelineState:PM_OBJ(id<MTLComputePipelineState>,d->probeQuery)];
        [e setBuffer:pyramid offset:0 atIndex:0];[e setBuffer:queries offset:0 atIndex:1];[e setBuffer:results offset:0 atIndex:2];
        [e setBytes:&c length:sizeof(c) atIndex:3];
        [e dispatchThreads:MTLSizeMake(count,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];[e endEncoding];
    }
    d->probeSampledSubmit=true;
    d->probeCur=slot;
}


PM_EXPORT int pm_device_check(char *nameOut, int cap) {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) { pm_copy_string(@"no Metal device", nameOut, cap); return 0; }
        pm_copy_string(device.name, nameOut, cap);
        if (![device supportsFamily:MTLGPUFamilyApple7]) return 0;
        return 1;
    }
}

PM_EXPORT PMDevice *pm_device_create(int debugLabels, int metal4) {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) return NULL;
        PMDevice *d = calloc(1, sizeof(PMDevice));
        if (!d) return NULL;
        const char *fold = getenv("PUREMETAL_DEPTH_CLEAR_FOLD");
        // On by default (exactly equal output, one fewer pass break per depth clear); PUREMETAL_DEPTH_CLEAR_FOLD=0 turns it off.
        d->foldDepthClears = !(fold && strcmp(fold, "0") == 0);
        // On by default; -Dpuremetal.m4Pipelines=false builds pipelines on Metal 3 while keeping Metal 4 command encoding.
        d->m4Pipelines = true;
        // -Dpuremetal.m4BarrierLog starts its window at PM_M4_LOG_SKIP unless -Dpuremetal.m4BarrierLogSkip moves it (option 10).
        d->m4LogSkip = PM_M4_LOG_SKIP;
        id<MTLCommandQueue> queue = d ? [device newCommandQueueWithMaxCommandBufferCount:64] : nil;
        if (!queue) { free(d); return NULL; }
        d->device = pm_retain(device);
        d->queue = pm_retain(queue);
        d->debugLabels = debugLabels;
        pthread_mutex_init(&d->lock, NULL);
        pthread_cond_init(&d->cond, NULL);
        atomic_store(&d->completed, 0);
        memset(d->doneMark, 0, sizeof(d->doneMark));
        atomic_store(&d->m4Done, 0);
        memset(d->m4DoneMark, 0, sizeof(d->m4DoneMark));
        if (metal4) {
            NSString *why = @"needs macOS 26 or newer";
            if (@available(macOS 26.0, *)) {
                why = nil;
                m4_setup(d, device, &why);
            }
            if (!d->m4) snprintf(d->m4Reason, sizeof(d->m4Reason), "%s", why ? why.UTF8String : "setup failed");
        }
        return d;
    }
}

// Runtime switches for the in-client A/B bench: 0 = depth clear fold, 1 = Metal 4 full barriers, 2 = Metal 4 fence at every frame
// start, 3 = Metal 4 completion from the frame's shared-event timeline, 4 = Metal 4 early uploads in their own command buffer,
// 5 = Metal 4 barriers that name only the stages that touch the resource (also enables 6), 6 = Metal 4 early-upload copies tracked like
// the frame's and committed together with the frame, 7 = Metal 4 pipeline compile path (0 = build libraries and pipelines on Metal 3 instead),
// 8 = Metal 4 render pass tile size, 9 = Metal 4 barrier/fence dump for N frames (diagnostics only, 0 = off),
// 10 = first frame that dump covers (diagnostics only, default PM_M4_LOG_SKIP)
// (set once at startup, before any frame).
PM_EXPORT void pm_set_option(PMDevice *d, int which, int value) {
    if (which == 0) d->foldDepthClears = value != 0;
    if (which == 1) d->m4FullBarriers = value != 0;
    if (which == 2) d->m4FrameFence = value != 0;
    if (which == 3) d->m4EventCompletion = value != 0;
    if (which == 4) d->m4EarlyUploads = value != 0;
    if (which == 5) d->m4PreciseBarriers = value != 0;
    if (which == 6) d->m4TrackedUploads = value != 0;
    if (which == 7) d->m4Pipelines = value != 0;
    if (which == 9) d->m4BarrierLog = value > 0 ? value : 0;
    if (which == 10) d->m4LogSkip = value > 0 ? (uint64_t)value : 0;
    if (which == 8) {
        // Metal reports no supported tile sizes, so only the documented ones are taken; anything else is ignored and logged once.
        if (value <= 0) {
            d->m4TileSize = 0;
        } else if (value == 16 || value == 32 || value == 64) {
            d->m4TileSize = (uint32_t)value;
        } else if (!d->m4TileWarned) {
            d->m4TileWarned = true;
            NSLog(@"Pure Metal: ignoring -Dpuremetal.m4TileSize=%d (valid: 16, 32, 64)", value);
        }
    }
}

PM_EXPORT void pm_device_name(PMDevice *d, char *out, int cap) {
    @autoreleasepool {
        pm_copy_string(DEV_MTL(d).name, out, cap);
    }
}
PM_EXPORT uint64_t pm_device_max_buffer_length(PMDevice *d) { return DEV_MTL(d).maxBufferLength; }
PM_EXPORT uint64_t pm_device_working_set(PMDevice *d) { return DEV_MTL(d).recommendedMaxWorkingSetSize; }
PM_EXPORT void pm_device_last_error(PMDevice *d, char *out, int cap) {
    if (!out || cap <= 0) return;
    pthread_mutex_lock(&d->lock);
    strncpy(out, d->error, (size_t)cap - 1);
    out[cap - 1] = 0;
    pthread_mutex_unlock(&d->lock);
}
PM_EXPORT int pm_device_failed(PMDevice *d) { return atomic_load(&d->failed); }

PM_EXPORT void pm_release(void *obj) {
    @autoreleasepool {
        if (!obj) return;
        if (g_m4Device && m4_released(obj)) return;
        CFRelease(obj);
    }
}

PM_EXPORT void pm_set_label(void *obj, const char *label) {
    @autoreleasepool {
        if (!obj || !label) return;
        id o = PM_OBJ(id, obj);
        if ([o respondsToSelector:@selector(setLabel:)]) [o setLabel:[NSString stringWithUTF8String:label]];
    }
}

// Depth/stencil store actions are left Unknown when a pass begins and decided here, when the pass ends.
static void set_depth_store(PMDevice *d, id<MTLRenderCommandEncoder> e, void *depth, MTLStoreAction action) {
    if (!depth) return;
    id<MTLTexture> t = PM_OBJ(id<MTLTexture>, depth);
    [e setDepthStoreAction:action];
    if (t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) [e setStencilStoreAction:action];
    if (action == MTLStoreActionDontCare) d->stats[5] -= t.allocatedSize;
}

static void set_color_store(PMDevice *d, id<MTLRenderCommandEncoder> e, void **colors, uint32_t discard) {
    for (NSUInteger i = 0; i < PM_MAX_COLOR; i++) {
        if (!colors[i]) continue;
        MTLStoreAction action = (discard & (1u << i)) ? MTLStoreActionDontCare : MTLStoreActionStore;
        [e setColorStoreAction:action atIndex:i];
        if (action == MTLStoreActionDontCare) d->stats[5] -= PM_OBJ(id<MTLTexture>, colors[i]).allocatedSize;
    }
}

static const char *trace_action(int a, int load) {
    if (load) return a == MTLLoadActionLoad ? "Load" : a == MTLLoadActionClear ? "Clear" : "DontCare";
    return a == MTLStoreActionStore ? "Store" : a == MTLStoreActionDontCare ? "DontCare" : a == MTLStoreActionUnknown ? "Unknown" : "Other";
}

static const char *trace_format(unsigned f) {
    switch (f) {
        case MTLPixelFormatBGRA8Unorm: return "BGRA8";
        case MTLPixelFormatBGRA8Unorm_sRGB: return "BGRA8s";
        case MTLPixelFormatRGBA8Unorm: return "RGBA8";
        case MTLPixelFormatRGBA8Unorm_sRGB: return "RGBA8s";
        case MTLPixelFormatRGBA16Float: return "RGBA16F";
        case MTLPixelFormatRGB10A2Unorm: return "RGB10A2";
        case MTLPixelFormatR8Unorm: return "R8";
        case MTLPixelFormatDepth32Float: return "D32";
        case MTLPixelFormatDepth32Float_Stencil8: return "D32S8";
        default: return "fmt?";
    }
}

static void trace_attachment(PMPassTrace *t, int kind, int idx, id<MTLTexture> tex, int load) {
    if (t->count >= PM_MAX_COLOR + 1) return;
    PMPassTraceAtt *a = &t->att[t->count++];
    a->kind = kind;
    a->idx = idx;
    a->format = (unsigned)tex.pixelFormat;
    a->width = (unsigned)tex.width;
    a->height = (unsigned)tex.height;
    a->bytes = tex.allocatedSize;
    a->load = load;
    a->name[0] = 0;
    if (tex.label) snprintf(a->name, sizeof(a->name), "%s", tex.label.UTF8String);
}

// Called when the parked encoder ends, with the store actions just applied.
static void trace_finish(PMDevice *d, uint32_t colorDiscard, MTLStoreAction depthStore) {
    if (!d->traceOn) return;
    PMPassTrace *t = &d->traceParked;
    if (!t->label[0]) return;
    char key[768];
    int n = snprintf(key, sizeof(key), "%s %dx%d%s", t->label, t->width, t->height, t->merged ? " (merged)" : "");
    for (int i = 0; i < t->count && n < (int)sizeof(key) - 200; i++) {
        PMPassTraceAtt *a = &t->att[i];
        int store = a->kind == 0 ? ((colorDiscard & (1u << a->idx)) ? MTLStoreActionDontCare : MTLStoreActionStore) : (int)depthStore;
        char id[4] = { a->kind ? 'D' : 'C', a->kind ? 0 : (char)('0' + a->idx), 0, 0 };
        n += snprintf(key + n, sizeof(key) - (size_t)n, " | %s %s %s %ux%u %.1fMB load=%s store=%s",
                      id, a->name[0] ? a->name : "-", trace_format(a->format), a->width, a->height, (double)a->bytes / 1e6,
                      trace_action(a->load, 1), trace_action(store, 0));
    }
    for (int i = 0; i < d->traceCount; i++) {
        if (strcmp(d->trace[i].key, key) == 0) { d->trace[i].count++; return; }
    }
    if (d->traceCount < PM_TRACE_ENTRIES) {
        snprintf(d->trace[d->traceCount].key, sizeof(d->trace[0].key), "%s", key);
        d->trace[d->traceCount++].count = 1;
    }
}

// Drains the per-pass trace as "count<TAB>key" lines. The first call turns tracing on.
PM_EXPORT int pm_take_pass_trace(PMDevice *d, char *out, int cap) {
    d->traceOn = 1;
    int n = 0;
    for (int i = 0; i < d->traceCount; i++) {
        int w = snprintf(out + n, (size_t)(cap - n), "%llu\t%s\n", (unsigned long long)d->trace[i].count, d->trace[i].key);
        if (w < 0 || n + w >= cap) break;
        n += w;
    }
    d->traceCount = 0;
    return n;
}

static void end_parked_actions(PMDevice *d, MTLStoreAction depthStore, uint32_t colorDiscard) {
    if (d->parked) {
        // The physical encoder ends here, so this is where its timestamp span closes: every logical pass merged into it is inside
        // that span. -Dpuremetal.profile only; nothing is written otherwise.
        PM_IF_M4(d) m4_ts_render_close(d, (id<MTL4RenderCommandEncoder>)PM_OBJ(id<MTLRenderCommandEncoder>, d->parked));
        // The opt-in probe reads depth after this encoder. Preserve it even if the next pass would clear it.
        if (d->probeCount) depthStore = MTLStoreActionStore;
        set_color_store(d, PM_OBJ(id<MTLRenderCommandEncoder>, d->parked), d->parkedColors, colorDiscard);
        set_depth_store(d, PM_OBJ(id<MTLRenderCommandEncoder>, d->parked), d->parkedDepth, depthStore);
        trace_finish(d, colorDiscard, d->parkedDepth ? depthStore : MTLStoreActionDontCare);
        // -Dpuremetal.m4PreciseBarriers: the fragment writes this encoder made to resources vertex shaders read are published
        // here, as this encoder ends, so a later pass's vertex work is ordered behind them without waiting for the queue.
        PM_IF_M4(d) m4_publish_fragment_writes(d, (id<MTL4CommandEncoder>)PM_OBJ(id<MTLRenderCommandEncoder>, d->parked), "pass end");
        [PM_OBJ(id<MTLRenderCommandEncoder>, d->parked) endEncoding];
        pm_release(d->parked);
        d->parked = NULL;
        encode_probe(d, d->parkedDepth);
    }
}

static void end_parked_store(PMDevice *d, MTLStoreAction depthStore) { end_parked_actions(d, depthStore, 0); }

static void end_parked(PMDevice *d) { end_parked_store(d, MTLStoreActionStore); }

static id<MTLCommandBuffer> new_cb(PMDevice *d, bool upload) {
    PM_IF_M4(d) return (id)m4_begin_cb(d, upload);
    return [PM_OBJ(id<MTLCommandQueue>, d->queue) commandBuffer];
}

static void set_error(PMDevice *d, NSString *msg);

static id<MTLCommandBuffer> raw_cb(PMDevice *d) {
    if (!d->cb) {
        id<MTLCommandBuffer> cb = new_cb(d, false);
        if (!cb && atomic_exchange(&d->cbWarned, 1) == 0) {
            // Nothing for this submit can be recorded without a command buffer. Metal 4 fails for a known reason (no allocator), but
            // Metal 3's would otherwise be invisible: a nil buffer looks exactly like an empty submit to pm_submit, which then has to
            // settle the index itself. Say so once, and leave an existing (more specific) error in place.
            NSLog(@"Pure Metal: could not create a frame command buffer; this submit records nothing");
            if (!atomic_load(&d->failed)) set_error(d, @"could not create a frame command buffer");
        }
        d->cb = pm_retain(cb);
        PM_ENC_COUNT(PM_ENC_CBS_CREATED, 1);
    }
    return PM_OBJ(id<MTLCommandBuffer>, d->cb);
}

static BOOL is_depth_format(MTLPixelFormat f) {
    return f == MTLPixelFormatDepth32Float || f == MTLPixelFormatDepth16Unorm || f == MTLPixelFormatDepth32Float_Stencil8;
}

// Encodes a clear of one level/slice as its own pass.
static void encode_clear(PMDevice *d, id<MTLTexture> t, NSUInteger level, NSUInteger slice, const float *color, double depth) {
    MTLRenderPassDescriptor *rp = new_pass_descriptor(d);
    if (is_depth_format(t.pixelFormat)) {
        rp.depthAttachment.texture = t;
        rp.depthAttachment.level = level;
        rp.depthAttachment.slice = slice;
        rp.depthAttachment.loadAction = MTLLoadActionClear;
        rp.depthAttachment.storeAction = MTLStoreActionStore;
        rp.depthAttachment.clearDepth = depth;
        if (t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) {
            rp.stencilAttachment.texture = t;
            rp.stencilAttachment.level = level;
            rp.stencilAttachment.slice = slice;
            rp.stencilAttachment.loadAction = MTLLoadActionClear;
            rp.stencilAttachment.storeAction = MTLStoreActionStore;
        }
    } else {
        rp.colorAttachments[0].texture = t;
        rp.colorAttachments[0].level = level;
        rp.colorAttachments[0].slice = slice;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        rp.colorAttachments[0].clearColor = MTLClearColorMake(color[0], color[1], color[2], color[3]);
    }
    id<MTLCommandBuffer> cb = raw_cb(d);
    id<MTLCounterSampleBuffer> samples = profile_render(d, rp);
    id<MTLRenderCommandEncoder> encoder = new_render_encoder_kind(d, cb, rp, 1, "clear");
    if (encoder) {
        PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
        profile_completion(d, cb, samples, true);
        [encoder endEncoding];
    }
}

static void drop_backdrop(PMDevice *d) {
    pm_release(d->bgDst);
    pm_release(d->bgSrc);
    d->bgDst = d->bgSrc = NULL;
}

static void drop_clear(PMDevice *d, int i) {
    pm_release(d->clears[i].texture);
    d->clears[i] = d->clears[--d->clearCount];
}

static void flush_backdrop(PMDevice *d);

// Encodes every pending clear, in the order they were requested.
static void flush_clears(PMDevice *d) {
    flush_backdrop(d);
    for (int i = 0; i < d->clearCount; i++) {
        PMPendingClear *c = &d->clears[i];
        encode_clear(d, PM_OBJ(id<MTLTexture>, c->texture), 0, 0, c->color, c->depth);
        pm_release(c->texture);
    }
    d->clearCount = 0;
}

// Attachments are views; a pending clear is keyed by the texture they view. Only single-level 2D textures are
// deferred, so any view of one covers all of it.
static void *root_texture(void *texture) {
    id<MTLTexture> t = PM_OBJ(id<MTLTexture>, texture);
    return t.parentTexture ? (__bridge void *)t.parentTexture : texture;
}

static int find_clear(PMDevice *d, void *texture) {
    texture = root_texture(texture);
    for (int i = 0; i < d->clearCount; i++) {
        if (d->clears[i].texture == texture) return i;
    }
    return -1;
}

// Everything that records into the command buffer goes through here, which keeps encoder order correct.
static id<MTLCommandBuffer> current_cb(PMDevice *d) {
    end_parked(d);
    flush_clears(d);
    return raw_cb(d);
}

static void end_blit(PMDevice *d) {
    if (d->blit) {
        [PM_OBJ(id<MTLBlitCommandEncoder>, d->blit) endEncoding];
        pm_release(d->blit);
        d->blit = NULL;
        d->blitHaz.count = 0;
    }
}

static id<MTLBlitCommandEncoder> new_blit_encoder(PMDevice *d, id<MTLCommandBuffer> cb, MTLBlitPassDescriptor *bp, bool upload) {
    PM_IF_M4(d) {
        // Metal 4 copies run on the compute encoder. The frame's copies are always tracked; the early-upload ones only with
        // -Dpuremetal.m4TrackedUploads or -Dpuremetal.m4PreciseBarriers (they otherwise wait for the whole queue, which serializes
        // consecutive frames).
        if (upload) return (id)m4_upload_encoder(d, (id<MTL4CommandBuffer>)cb, m4_uploads_tracked(d));
        return (id)m4_compute_encoder(d, (id<MTL4CommandBuffer>)cb, true, true, "blit");
    }
    return bp ? [cb blitCommandEncoderWithDescriptor:bp] : [cb blitCommandEncoder];
}

// Returns nil (and sets the device error) when no encoder can be created; callers must skip their copy.
static id<MTLBlitCommandEncoder> blit_encoder(PMDevice *d, int kind) {
    if (!d->blit) {
        bool wasParked = d->parked != NULL;
        id<MTLCommandBuffer> cb = current_cb(d);
        id<MTLCounterSampleBuffer> samples = profile_buffer(d, 2);
        MTLBlitPassDescriptor *bp = nil;
        if (samples) {
            bp = [MTLBlitPassDescriptor blitPassDescriptor];
            bp.sampleBufferAttachments[0].sampleBuffer = samples;
            bp.sampleBufferAttachments[0].startOfEncoderSampleIndex = 0;
            bp.sampleBufferAttachments[0].endOfEncoderSampleIndex = 1;
        }
        id<MTLBlitCommandEncoder> enc = cb ? new_blit_encoder(d, cb, bp, false) : nil;
        if (!enc) { set_error(d, @"could not create a blit command encoder"); return nil; }
        atomic_fetch_add(&d->encCounts[2], 1);
        profile_completion(d, cb, samples, false);
        d->stats[2]++;
        PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
        if (wasParked) { d->stats[3]++; d->stats[7 + kind]++; }
        d->blit = pm_retain(enc);
    }
    return PM_OBJ(id<MTLBlitCommandEncoder>, d->blit);
}

static void set_error(PMDevice *d, NSString *msg) {
    pthread_mutex_lock(&d->lock);
    pm_copy_string(msg, d->error, sizeof(d->error));
    pthread_mutex_unlock(&d->lock);
    atomic_store(&d->failed, 1);
}

// Commits `cb`; finished(start, end, failure) runs when the GPU is done with it (failure is the error text, nil on success).
static void commit_cb(PMDevice *d, id<MTLCommandBuffer> cb, void (^finished)(CFTimeInterval, CFTimeInterval, NSString *)) {
    PM_IF_M4(d) {
        m4_commit(d, (id<MTL4CommandBuffer>)cb, finished);
        return;
    }
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        @autoreleasepool {
            finished(done.GPUStartTime, done.GPUEndTime,
                     done.status == MTLCommandBufferStatusError ? (done.error ? done.error.localizedDescription : @"unknown GPU error") : nil);
        }
    }];
    [cb commit];
}

static void gpu_failed(PMDevice *d, NSString *failure) {
    pthread_mutex_lock(&d->lock);
    pm_copy_string(failure, d->error, sizeof(d->error));
    pthread_mutex_unlock(&d->lock);
    atomic_store(&d->failed, 1);
}

// Ends the early-upload encoder so its command buffer can be committed, and leaves that buffer in d->uploadCb for the caller. False
// when this frame recorded no early uploads.
static bool end_uploads(PMDevice *d) {
    if (!d->uploadCb) return false;
    PM_IF_M4(d) {
        bool orderedAll = false;
        if (d->m4UploadOrderAll) {
            // The tracker had no room for an upload's written resource, so order every later encoder on the queue behind this
            // buffer (a producer barrier: what the upload copied is visible to the frame's encoders).
            [PM_OBJ(id<MTL4ComputeCommandEncoder>, d->uploadBlit)
                barrierAfterStages:MTLStageBlit beforeQueueStages:MTLStageAll visibilityOptions:MTL4VisibilityOptionDevice];
            PM_ENC_COUNT(PM_ENC_BARRIERS, 1);
            if (d->m4BarrierLog) {
                // Logged in the barrier's own order: the upload's blit work finishes first, then the queue stages it holds back.
                m4_log_barrier(d, PM_OBJ(id<MTL4CommandEncoder>, d->uploadBlit), "producer", "end_uploads", "upload buffer",
                               NULL, MTLStageBlit, MTLStageAll, true);
            }
            d->m4UploadOrderAll = false;
            orderedAll = true;
        }
        // -Dpuremetal.m4PreciseBarriers: what these copies wrote is published to every render and dispatch stage here, at the
        // producer, instead of every later reader of an uploaded resource waiting for all the queue's blit work itself.
        if (d->m4PreciseBarriers) {
            uint32_t published = m4_publish_upload_writes(d, orderedAll);
            if (published && !orderedAll) {
                [PM_OBJ(id<MTL4ComputeCommandEncoder>, d->uploadBlit)
                    barrierAfterStages:(MTLStages)published
                     beforeQueueStages:MTLStageVertex | MTLStageFragment | MTLStageDispatch
                  visibilityOptions:MTL4VisibilityOptionDevice];
                PM_ENC_COUNT(PM_ENC_BARRIERS, 1);
                if (d->m4BarrierLog) {
                    m4_log_barrier(d, PM_OBJ(id<MTL4CommandEncoder>, d->uploadBlit), "producer", "end_uploads",
                                   "uploaded writes", NULL, (MTLStages)published,
                                   MTLStageVertex | MTLStageFragment | MTLStageDispatch, false);
                }
            }
        }
    }
    [PM_OBJ(id<MTLBlitCommandEncoder>, d->uploadBlit) endEncoding];
    pm_release(d->uploadBlit);
    d->uploadBlit = NULL;
    d->uploadHaz.count = 0;
    return true;
}

// Commits the early-upload command buffer on its own and releases it. With -Dpuremetal.m4TrackedUploads the frame commits it together
// with its own command buffer instead (m4_commit_buffers in pm_submit), so this path is then only taken by a frame that recorded nothing.
// `index` is the submit that only carries these uploads, marked finished when they are (0 when a frame's commit covers them).
static void commit_upload_cb(PMDevice *d, uint64_t index) {
    id<MTLCommandBuffer> ucb = PM_OBJ(id<MTLCommandBuffer>, d->uploadCb);
    atomic_fetch_add(&d->handlers, 1);
    commit_cb(d, ucb, ^(CFTimeInterval start, CFTimeInterval end, NSString *failure) {
        CFTimeInterval gpu = end - start;
        if (gpu > 0) {
            atomic_fetch_add(&d->uploadGpuNs, (uint64_t)(gpu * 1e9));
            atomic_fetch_add(&d->uploadGpuCommands, 1);
        }
        if (failure) gpu_failed(d, failure);
        if (index != 0) {
            pthread_mutex_lock(&d->lock);
            submit_finished(d, index);
            pthread_cond_broadcast(&d->cond);
            pthread_mutex_unlock(&d->lock);
        }
        atomic_fetch_sub(&d->handlers, 1);
    });
    PM_ENC_COUNT(PM_ENC_CBS_COMMITTED, 1);
    PM_IF_M4(d) {
        // Without tracked uploads the frame's command buffer may hold no encoder (and so no barrier) to order it behind the uploads;
        // its completion must still prove they finished, because it is what frees their staging memory and allocator. The tracked
        // path does not need the event: both buffers are in one commit, and the frame's completion covers the uploads.
        if (!m4_uploads_tracked(d)) {
            d->m4UploadWait = ++d->m4EventValue;
            [PM_OBJ(id<MTL4CommandQueue>, d->m4Queue) signalEvent:PM_OBJ(id<MTLEvent>, d->m4Event) value:d->m4UploadWait];
        }
    }
    pm_release(d->uploadCb);
    d->uploadCb = NULL;
}

// Ends and commits the early-upload command buffer; the frame's is committed after it.
static void commit_uploads(PMDevice *d) {
    if (!end_uploads(d)) return;
    commit_upload_cb(d, 0);
}

PM_EXPORT void pm_submit(PMDevice *d, uint64_t index) {
    PM_ENC_SCOPE(PM_ENC_SUBMIT);
    @autoreleasepool {
        end_blit(d);
        // -Dpuremetal.m4TrackedUploads: the uploads go into the frame's own commit, ahead of its command buffer, instead of a commit of
        // their own that the frame waits for. A queue signal runs after everything committed before it, so that wait only ended once
        // the previous frame had finished on the GPU and consecutive frames could not overlap.
        bool paired = false;
        PM_IF_M4(d) paired = m4_uploads_tracked(d);
        // The early-upload encoder ends here, but its command buffer is not committed yet: the frame's command buffer is allocated
        // first, because whether it exists decides which commit owns this submit's index. Only the commit carrying the work may report
        // the index finished, or Java could recycle staging and source memory the GPU is still reading.
        bool uploads = end_uploads(d);
        id<MTLCommandBuffer> ucb = paired && uploads ? PM_OBJ(id<MTLCommandBuffer>, d->uploadCb) : NULL;
        id<MTLCommandBuffer> cb = current_cb(d);
        if (!cb) {
            // No frame command buffer to carry this submit's index, so its completion comes from whatever did run. Uploads committed
            // for this index were already sent to the GPU (unpaired: they are their own commit), so their callback has to mark it; only
            // a submit that committed nothing at all is finished right here. Without this the prefix would stall on the gap and every
            // later pm_wait would time out.
            if (uploads) {
                commit_upload_cb(d, index);
            } else {
                pthread_mutex_lock(&d->lock);
                submit_finished(d, index);
                pthread_cond_broadcast(&d->cond);
                pthread_mutex_unlock(&d->lock);
            }
            return;
        }
        // Unpaired uploads are a commit of their own, and commit order is execution order on the queue: they land ahead of the frame, so
        // the frame's completion covers them (index 0) instead of the uploads carrying this index.
        if (!paired && uploads) commit_upload_cb(d, 0);
        atomic_fetch_add(&d->handlers, 1);
        trace_ev(5, (int64_t)index, 0, 0);
        uint64_t frame = d->m4Seq;
        PMProbeSlot *probe = d->probeCur; // this submit's probe buffers are free again once its commit has finished
        d->probeCur = NULL;
        PM_IF_M4(d) {
            // No encoder is open any more: close the last pass timestamp span on the GPU timeline before the buffer ends, and hold
            // this slot until the feedback has resolved its heap (-Dpuremetal.profile only).
            m4_ts_span_close(d, (id<MTL4CommandBuffer>)cb);
            if (d->m4Profile && d->m4Ts[frame % PM_M4_FRAMES].used > 0) {
                atomic_store(&d->m4TsBusy[frame % PM_M4_FRAMES], 1);
            }
        }
        PM_IF_M4(d) {
            if (d->m4UploadWait) {
                [PM_OBJ(id<MTL4CommandQueue>, d->m4Queue) waitForEvent:PM_OBJ(id<MTLEvent>, d->m4Event) value:d->m4UploadWait];
                d->m4UploadWait = 0;
            }
        }
        void (^finished)(CFTimeInterval, CFTimeInterval, NSString *) = ^(CFTimeInterval start, CFTimeInterval end, NSString *failure) {
            trace_ev(4, (int64_t)index, (int64_t)pm_sec_ns(start), (int64_t)pm_sec_ns(end));
            trace_ev(15, (int64_t)index, (int64_t)frame, 0); // commit feedback arrived (Metal 4) / completion handler fired
            PM_IF_M4(d) m4_ts_finish(d, frame);
            CFTimeInterval gpu = end - start;
            if (gpu > 0) {
                atomic_fetch_add(&d->gpuNs, (uint64_t)(gpu * 1e9));
                atomic_fetch_add(&d->gpuFrames, 1);
            }
            probe_finish(d, probe, failure == nil);
            if (failure) gpu_failed(d, failure);
            pthread_mutex_lock(&d->lock);
            // Feedback for one commit says nothing about the commits before it, so it marks only this index and lets the prefix walk
            // up what has really finished.
            submit_finished(d, index);
            // With the completion timeline the listener owns m4Done, because the shared event's value is a finished prefix. Without it
            // the feedback is all there is, and it is a prefix there too (the commit feedbacks are unordered like these).
            if (!d->m4EventCompletion || !d->m4FrameEvent) {
                done_mark(d->m4DoneMark, &d->m4Done, frame);
                done_advance(d->m4DoneMark, &d->m4Done);
            }
            pthread_cond_broadcast(&d->cond);
            pthread_mutex_unlock(&d->lock);
            atomic_fetch_sub(&d->handlers, 1);
        };
        if (ucb) {
            // One commit, the uploads first: the GPU runs them in that order, so every encoder of the frame sees the copies as earlier
            // work on the queue (its tracked barriers wait for Blit) while nothing makes the frame wait for them to finish. The frame's
            // completion is the one that frees their allocator, staging memory and deferred releases, so m4Done still only advances
            // past a frame once its uploads have run. The commit feedback covers both buffers, so the upload's GPU time is part of
            // the frame's span instead of a separate figure.
            PM_IF_M4(d) {
                m4_commit_buffers(d, (id<MTL4CommandBuffer>)ucb, (id<MTL4CommandBuffer>)cb, finished);
            }
            atomic_fetch_add(&d->uploadGpuCommands, 1);
            pm_release(d->uploadCb);
            d->uploadCb = NULL;
            PM_ENC_COUNT(PM_ENC_CBS_COMMITTED, 1);
        } else {
            commit_cb(d, cb, finished);
        }
        PM_IF_M4(d) {
            m4_flush_signals(d);
            m4_signal_frame(d, frame, index);
            atomic_fetch_add(&d->m4Seq, 1);
        }
        PM_ENC_COUNT(PM_ENC_CBS_COMMITTED, 1);
        pm_release(d->cb);
        d->cb = NULL;
        d->profileSamples = 0;
        d->probeSampledSubmit = false;
    }
}

PM_EXPORT uint64_t pm_completed(PMDevice *d) { return atomic_load(&d->completed); }

// GPU time and command buffer count since the last call (for the dev benchmark).
PM_EXPORT uint64_t pm_take_gpu_ns(PMDevice *d) { return atomic_exchange(&d->gpuNs, 0); }
PM_EXPORT uint64_t pm_take_upload_gpu_ns(PMDevice *d) { return atomic_exchange(&d->uploadGpuNs, 0); }
PM_EXPORT uint64_t pm_take_upload_gpu_commands(PMDevice *d) { return atomic_exchange(&d->uploadGpuCommands, 0); }
PM_EXPORT uint64_t pm_take_gpu_frames(PMDevice *d) { return atomic_exchange(&d->gpuFrames, 0); }
PM_EXPORT uint64_t pm_take_stat(PMDevice *d, int which) {
    // 28: fences at a frame boundary since the last call (Metal 4; the bench line that would print it per frame lives in
    // EncodeProfile, which reports the slots below). 29..34: encoders opened by kind (render, clear, frame blit, upload blit,
    // compute, present). 35, 36: nanoseconds spent blocked in the surface's frame-slot semaphore waits and how many of those
    // waits blocked at all. 37: Metal 4 timestamp samples dropped because a frame slot's heap was full.
    if (which == 28) return atomic_exchange(&d->m4FrameFences, 0);
    if (which >= 29 && which <= 34) return atomic_exchange(&d->encCounts[which - 29], 0);
    if (which == 35) return atomic_exchange(&d->surfaceWaitNs, 0);
    if (which == 36) return atomic_exchange(&d->surfaceWaitCount, 0);
    if (which == 37) return atomic_exchange(&d->m4TsDropped, 0);
    if (which == 38) return atomic_exchange(&d->drawableWaitMaxNs, 0);
    if (which == 39) return atomic_exchange(&d->layerConfigureMaxNs, 0);
    if (which >= 12) return atomic_exchange(&d->txDiagStats[which - 12], 0);
    uint64_t v = d->stats[which];
    d->stats[which] = 0;
    return v;
}

// Waits until submit `index` has completed. Returns 1 on completion, 0 on timeout.
PM_EXPORT int pm_wait(PMDevice *d, uint64_t index, int64_t timeoutNs) {
    if (atomic_load(&d->completed) >= index) return 1;
    if (timeoutNs == 0) return 0;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    int64_t ns = timeoutNs < 0 ? (int64_t)3600 * 1000000000LL : timeoutNs;
    deadline.tv_sec += ns / 1000000000LL;
    deadline.tv_nsec += ns % 1000000000LL;
    if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
    pthread_mutex_lock(&d->lock);
    int rc = 0;
    while (atomic_load(&d->completed) < index && rc == 0) rc = pthread_cond_timedwait(&d->cond, &d->lock, &deadline);
    pthread_mutex_unlock(&d->lock);
    return atomic_load(&d->completed) >= index;
}

// Shutdown recovery barrier: all previously submitted work on this queue finishes before Java frees scratch.
PM_EXPORT int pm_wait_idle(PMDevice *d) {
    @autoreleasepool {
        PM_IF_M4(d) {
            // A queue signal runs after everything committed before it.
            id<MTLSharedEvent> event = PM_OBJ(id<MTLSharedEvent>, d->m4Event);
            uint64_t value = ++d->m4EventValue;
            [PM_OBJ(id<MTL4CommandQueue>, d->m4Queue) signalEvent:event value:value];
            return [event waitUntilSignaledValue:value timeoutMS:5000] ? 1 : 0;
        }
        id<MTLCommandBuffer> marker = [PM_OBJ(id<MTLCommandQueue>, d->queue) commandBuffer];
        if (!marker) return 0;
        // Bounded so a stuck GPU cannot hang shutdown. The block captures only the semaphore, so a late signal is safe.
        dispatch_semaphore_t finished = dispatch_semaphore_create(0);
        [marker addCompletedHandler:^(id<MTLCommandBuffer> done) { dispatch_semaphore_signal(finished); }];
        [marker commit];
        if (dispatch_semaphore_wait(finished, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) != 0) return 0;
        return marker.status == MTLCommandBufferStatusCompleted;
    }
}

PM_EXPORT void pm_device_destroy(PMDevice *d) {
    @autoreleasepool {
        end_blit(d);
        end_parked(d);
        commit_uploads(d);
        if (d->clearCount > 0 && !d->render) current_cb(d);
        if (d->render) {
            set_color_store(d, PM_OBJ(id<MTLRenderCommandEncoder>, d->render), d->passColors, 0);
            set_depth_store(d, PM_OBJ(id<MTLRenderCommandEncoder>, d->render), d->passDepth, MTLStoreActionStore);
            PM_IF_M4(d) m4_ts_render_close(d, (id<MTL4RenderCommandEncoder>)PM_OBJ(id<MTLRenderCommandEncoder>, d->render));
            PM_IF_M4(d) m4_publish_fragment_writes(d, (id<MTL4CommandEncoder>)PM_OBJ(id<MTLRenderCommandEncoder>, d->render), "device destroy");
            [PM_OBJ(id<MTLRenderCommandEncoder>, d->render) endEncoding];
            pm_release(d->render);
            d->render = NULL;
        }
        d->m4SignalCount = 0; // the surface that owned these events is already gone
        if (d->cb) {
            id<MTLCommandBuffer> cb = PM_OBJ(id<MTLCommandBuffer>, d->cb);
            if (d->m4) {
                PM_IF_M4(d) {
                    atomic_fetch_add(&d->handlers, 1);
                    m4_commit(d, (id<MTL4CommandBuffer>)cb, ^(CFTimeInterval start, CFTimeInterval end, NSString *failure) {
                        atomic_fetch_sub(&d->handlers, 1);
                    });
                }
            } else {
                [cb commit];
                [cb waitUntilCompleted];
            }
            pm_release(d->cb);
        }
        if (d->m4) {
            if (!pm_wait_idle(d)) {
                NSLog(@"Pure Metal: GPU did not go idle; leaking the device state");
                return;
            }
        } else {
            id<MTLCommandBuffer> last = [PM_OBJ(id<MTLCommandQueue>, d->queue) commandBuffer];
            [last commit];
            [last waitUntilCompleted];
        }
        // Completion handlers of earlier command buffers may still be running and touch this struct; wait for them,
        // and leak the struct rather than free it under one that never finishes.
        int drained = 0;
        for (int i = 0; i < 5000; i++) {
            if (atomic_load(&d->handlers) == 0) { drained = 1; break; }
            usleep(1000);
        }
        if (!drained) {
            NSLog(@"Pure Metal: GPU completion handlers did not finish; leaking the device state");
            return;
        }
        // Clears that were never flushed still retain their texture.
        for (int i = 0; i < d->clearCount; i++) pm_release(d->clears[i].texture);
        d->clearCount = 0;
        pm_release(d->bgDst);
        pm_release(d->bgSrc);
        pm_release(d->mtl4Compiler);
        pm_release(d->upscaleDepthPipeline);
        pm_release(d->presentPipeline);
        pm_release(d->presentLibrary);
        pm_release(d->depthClearLibrary);
        pm_release(d->depthClearPipeline);
        pm_release(d->depthClearState);
        pm_release(d->partialDepthLibrary);
        pm_release(d->partialDepthPipeline);
        pm_release(d->partialDepthState);
        pm_release(d->upscalePipeline);
        pm_release(d->upscaleSampler);
        pm_release(d->fxScaler);
        pm_release(d->fxOut);
        reset_temporal(d);
        pm_release(d->txLibrary);
        pm_release(d->txMotionPipeline);
        pm_release(d->txHandPipeline);
        pm_release(d->profileCounterSet);
        pm_release(d->probeRead);
        pm_release(d->probeReduce);
        pm_release(d->probeQuery);
        for (int i = 0; i < PM_PROBE_SLOTS; i++) {
            pm_release(d->probeSlots[i].pyramid);
            pm_release(d->probeSlots[i].queries);
            pm_release(d->probeSlots[i].results);
            pm_release(d->probeSlots[i].samples);
        }
        PM_IF_M4(d) m4_teardown(d);
        pm_release(d->queue);
        pm_release(d->device);
        pthread_mutex_destroy(&d->lock);
        pthread_cond_destroy(&d->cond);
        free(d);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Resources.

PM_EXPORT void *pm_buffer_create(PMDevice *d, uint64_t size) {
    @autoreleasepool {
        id<MTLBuffer> b = [DEV_MTL(d) newBufferWithLength:(size ? size : 16) options:MTLResourceStorageModeShared];
        return pm_retain(b);
    }
}

PM_EXPORT void *pm_buffer_contents(void *buffer) { return [PM_OBJ(id<MTLBuffer>, buffer) contents]; }

// Bytes the device currently holds for every resource it has allocated (MTLDevice.currentAllocatedSize).
PM_EXPORT uint64_t pm_device_allocated_size(PMDevice *d) { return (uint64_t)DEV_MTL(d).currentAllocatedSize; }

// usage bits: 1 copy dst, 2 copy src, 4 sampled, 8 render target, 16 cubemap.
PM_EXPORT void *pm_texture_create(PMDevice *d, int format, int width, int height, int layers, int mips, int usage) {
    @autoreleasepool {
        MTLPixelFormat pf = pixel_format(format);
        if (pf == MTLPixelFormatInvalid) return NULL;
        MTLTextureDescriptor *desc = [MTLTextureDescriptor new];
        desc.pixelFormat = pf;
        desc.width = (NSUInteger)width;
        desc.height = (NSUInteger)height;
        desc.mipmapLevelCount = (NSUInteger)(mips > 0 ? mips : 1);
        desc.storageMode = MTLStorageModePrivate;
        if ((usage & 16) && layers == 6) {
            desc.textureType = MTLTextureTypeCube;
        } else if (layers > 1) {
            desc.textureType = MTLTextureType2DArray;
            desc.arrayLength = (NSUInteger)layers;
        } else {
            desc.textureType = MTLTextureType2D;
        }
        // No PixelFormatView: views keep the texture's own format, and that flag stops Apple GPUs from losslessly
        // compressing the texture (render targets especially).
        MTLTextureUsage u = MTLTextureUsageShaderRead;
        if (usage & 8) u |= MTLTextureUsageRenderTarget;
        desc.usage = u;
        return pm_retain([DEV_MTL(d) newTextureWithDescriptor:desc]);
    }
}

PM_EXPORT void *pm_texture_view(void *texture, int baseMip, int mips, int cube) {
    @autoreleasepool {
        id<MTLTexture> t = PM_OBJ(id<MTLTexture>, texture);
        MTLTextureType type = cube && t.textureType == MTLTextureTypeCube ? MTLTextureTypeCube : MTLTextureType2D;
        NSUInteger slices = type == MTLTextureTypeCube ? 6 : 1;
        if (baseMip == 0 && (NSUInteger)mips == t.mipmapLevelCount && type == t.textureType) {
            // An identical full-range view needs only its own retain, not another driver view object.
            return pm_retain(t);
        }
        id<MTLTexture> v = [t newTextureViewWithPixelFormat:t.pixelFormat textureType:type
                                                     levels:NSMakeRange((NSUInteger)baseMip, (NSUInteger)mips)
                                                     slices:NSMakeRange(0, slices)];
        return pm_retain(v);
    }
}

// Texel-buffer view of a buffer range, for UniformType.TEXEL_BUFFER.
PM_EXPORT void *pm_buffer_texture(void *buffer, uint64_t offset, uint64_t length, int format, int texelSize) {
    @autoreleasepool {
        MTLPixelFormat pf = pixel_format(format);
        if (pf == MTLPixelFormatInvalid || texelSize <= 0) return NULL;
        id<MTLBuffer> b = PM_OBJ(id<MTLBuffer>, buffer);
        NSUInteger texels = (NSUInteger)(length / (uint64_t)texelSize);
        MTLTextureDescriptor *desc = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:pf width:texels
                                                                                 resourceOptions:b.resourceOptions
                                                                                           usage:MTLTextureUsageShaderRead];
        return pm_retain([b newTextureWithDescriptor:desc offset:(NSUInteger)offset bytesPerRow:texels * (NSUInteger)texelSize]);
    }
}

// address: 0 repeat, 1 clamp. filter: 0 nearest, 1 linear. mipFilter: 0 none, 1 nearest, 2 linear.
PM_EXPORT void *pm_sampler_create(PMDevice *d, int addressU, int addressV, int minFilter, int magFilter, int mipFilter,
                                  int maxAnisotropy, float maxLod) {
    @autoreleasepool {
        MTLSamplerDescriptor *desc = [MTLSamplerDescriptor new];
        desc.sAddressMode = addressU == 0 ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
        desc.tAddressMode = addressV == 0 ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
        desc.rAddressMode = desc.tAddressMode;
        desc.minFilter = minFilter ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        desc.magFilter = magFilter ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        desc.mipFilter = mipFilter == 0 ? MTLSamplerMipFilterNotMipmapped
                       : mipFilter == 1 ? MTLSamplerMipFilterNearest : MTLSamplerMipFilterLinear;
        desc.maxAnisotropy = (NSUInteger)(maxAnisotropy < 1 ? 1 : maxAnisotropy > 16 ? 16 : maxAnisotropy);
        desc.lodMinClamp = 0.0f;
        desc.lodMaxClamp = maxLod;
        desc.supportArgumentBuffers = d->m4 != 0; // argument tables bind samplers by resource ID
        return pm_retain([DEV_MTL(d) newSamplerStateWithDescriptor:desc]);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Shaders and pipelines.

PM_EXPORT void *pm_library_create(PMDevice *d, const char *source, char *err, int errCap, int safeMath) {
    @autoreleasepool {
        MTLCompileOptions *opts = [MTLCompileOptions new];
        opts.languageVersion = MTLLanguageVersion3_0;
        // Keeps [[invariant]] positions bit-exact across pipelines (the frontend asks for this on Apple GPUs).
        opts.preserveInvariance = YES;
        // Relaxed allows FMA contraction and reassociation (like MoltenVK and Apple's GL driver) but keeps NaN and
        // infinity behaviour. Safe is strict IEEE and noticeably slower on Apple GPUs.
        if (@available(macOS 15.0, *)) {
            opts.mathMode = safeMath ? MTLMathModeSafe : MTLMathModeRelaxed;
        } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            opts.fastMathEnabled = safeMath ? NO : YES;
#pragma clang diagnostic pop
        }
        NSError *error = nil;
        id<MTLLibrary> lib = pm_new_library(d, [NSString stringWithUTF8String:source], opts, &error);
        if (!lib) { pm_copy_string(error ? error.localizedDescription : @"unknown error", err, errCap); return NULL; }
        return pm_retain(lib);
    }
}

// Returns a retained MTLFunction; the function keeps its library alive, so callers may release the library.
PM_EXPORT void *pm_library_function(void *library, const char *name) {
    @autoreleasepool {
        id<MTLLibrary> lib = PM_OBJ(id<MTLLibrary>, library);
        NSString *fnName = [NSString stringWithUTF8String:name];
        return pm_retain(pm_track_function([lib newFunctionWithName:fnName], lib, fnName, nil, nil));
    }
}

// Like pm_library_function, specializing one bool function constant (index, value).
PM_EXPORT void *pm_library_function_bool(void *library, const char *name, int index, int value) {
    @autoreleasepool {
        MTLFunctionConstantValues *values = [MTLFunctionConstantValues new];
        bool v = value != 0;
        [values setConstantValue:&v type:MTLDataTypeBool atIndex:(NSUInteger)index];
        NSError *error = nil;
        id<MTLLibrary> lib = PM_OBJ(id<MTLLibrary>, library);
        NSString *fnName = [NSString stringWithUTF8String:name];
        id<MTLFunction> fn = [lib newFunctionWithName:fnName constantValues:values error:&error];
        return fn ? pm_retain(pm_track_function(fn, lib, fnName, @(index), @(value))) : NULL;
    }
}

// desc layout (ints):
//  [0] topology code, [1] depth pixel format code (-1 none), [2] vertex buffer count, [3] attribute count,
//  [4] color target count, then per vertex buffer: slot, stride, stepRate (3 ints),
//  then per attribute: location, slot, offset, format (4 ints),
//  then per color target: format (-1 unused), writeMask, blendEnabled, srcColor, dstColor, colorOp,
//  srcAlpha, dstAlpha, alphaOp (9 ints).
PM_EXPORT void *pm_pipeline_create(PMDevice *d, void *vertexFn, void *fragmentFn, const int *desc, const char *label,
                                   char *err, int errCap) {
    @autoreleasepool {
        MTLRenderPipelineDescriptor *p = [MTLRenderPipelineDescriptor new];
        p.vertexFunction = PM_OBJ(id<MTLFunction>, vertexFn);
        p.fragmentFunction = fragmentFn ? PM_OBJ(id<MTLFunction>, fragmentFn) : nil;
        p.inputPrimitiveTopology = topology_class(desc[0]);
        if (desc[1] >= 0) {
            MTLPixelFormat df = pixel_format(desc[1]);
            p.depthAttachmentPixelFormat = df;
            if (df == MTLPixelFormatDepth32Float_Stencil8) p.stencilAttachmentPixelFormat = df;
        }
        if (label) p.label = [NSString stringWithUTF8String:label];
        int vbCount = desc[2], attrCount = desc[3], colorCount = desc[4];
        const int *cursor = desc + 5;
        MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
        for (int i = 0; i < vbCount; i++, cursor += 3) {
            MTLVertexBufferLayoutDescriptor *l = vd.layouts[(NSUInteger)(PM_VERTEX_BUFFER_BASE + cursor[0])];
            l.stride = (NSUInteger)cursor[1];
            if (cursor[2] > 0) { l.stepFunction = MTLVertexStepFunctionPerInstance; l.stepRate = (NSUInteger)cursor[2]; }
            else { l.stepFunction = MTLVertexStepFunctionPerVertex; l.stepRate = 1; }
        }
        for (int i = 0; i < attrCount; i++, cursor += 4) {
            if (cursor[3] < 0 || cursor[3] >= FORMAT_COUNT || VERTEX_FORMATS[cursor[3]] == MTLVertexFormatInvalid) {
                pm_copy_string([NSString stringWithFormat:@"unsupported vertex format %d at location %d", cursor[3], cursor[0]], err, errCap);
                return NULL;
            }
            MTLVertexAttributeDescriptor *a = vd.attributes[(NSUInteger)cursor[0]];
            a.bufferIndex = (NSUInteger)(PM_VERTEX_BUFFER_BASE + cursor[1]);
            a.offset = (NSUInteger)cursor[2];
            a.format = VERTEX_FORMATS[cursor[3]];
        }
        if (vbCount > 0) p.vertexDescriptor = vd;
        for (int i = 0; i < colorCount && i < PM_MAX_COLOR; i++, cursor += 9) {
            if (cursor[0] < 0) continue;
            MTLRenderPipelineColorAttachmentDescriptor *c = p.colorAttachments[(NSUInteger)i];
            c.pixelFormat = pixel_format(cursor[0]);
            MTLColorWriteMask mask = MTLColorWriteMaskNone;
            if (cursor[1] & 1) mask |= MTLColorWriteMaskRed;
            if (cursor[1] & 2) mask |= MTLColorWriteMaskGreen;
            if (cursor[1] & 4) mask |= MTLColorWriteMaskBlue;
            if (cursor[1] & 8) mask |= MTLColorWriteMaskAlpha;
            c.writeMask = mask;
            if (cursor[2]) {
                c.blendingEnabled = YES;
                c.sourceRGBBlendFactor = blend_factor(cursor[3]);
                c.destinationRGBBlendFactor = blend_factor(cursor[4]);
                c.rgbBlendOperation = blend_op(cursor[5]);
                c.sourceAlphaBlendFactor = blend_factor(cursor[6]);
                c.destinationAlphaBlendFactor = blend_factor(cursor[7]);
                c.alphaBlendOperation = blend_op(cursor[8]);
            }
        }
        NSError *error = nil;
        id<MTLRenderPipelineState> state = pm_new_render_pipeline(d, p, &error);
        if (!state) { pm_copy_string(error ? error.localizedDescription : @"unknown error", err, errCap); return NULL; }
        return pm_retain(state);
    }
}

PM_EXPORT void *pm_depth_state_create(PMDevice *d, int enabled, int compare, int write) {
    @autoreleasepool {
        MTLDepthStencilDescriptor *desc = [MTLDepthStencilDescriptor new];
        desc.depthCompareFunction = enabled ? compare_fn(compare) : MTLCompareFunctionAlways;
        desc.depthWriteEnabled = enabled && write;
        return pm_retain([DEV_MTL(d) newDepthStencilStateWithDescriptor:desc]);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Copies (outside render passes, in call order).

PM_EXPORT void pm_copy_buffer(PMDevice *d, void *src, uint64_t srcOffset, void *dst, uint64_t dstOffset, uint64_t size) {
    PM_ENC_SCOPE(PM_ENC_COPY);
    @autoreleasepool {
        if (size == 0) return;
        id<MTLBlitCommandEncoder> blit = blit_encoder(d, 0);
        if (!blit) return;
        PM_IF_M4(d) m4_blit_deps(d, &d->blitHaz, (id<MTL4ComputeCommandEncoder>)blit, src, dst, true);
        [blit copyFromBuffer:PM_OBJ(id<MTLBuffer>, src) sourceOffset:(NSUInteger)srcOffset
                               toBuffer:PM_OBJ(id<MTLBuffer>, dst) destinationOffset:(NSUInteger)dstOffset size:(NSUInteger)size];
    }
}

// Same as pm_copy_buffer, but ahead of everything recorded for this frame so far. Only valid when nothing recorded
// this frame uses `dst` (the Java side tracks that).
PM_EXPORT void pm_copy_buffer_early(PMDevice *d, void *src, uint64_t srcOffset, void *dst, uint64_t dstOffset, uint64_t size) {
    if (size == 0) return;
    // -Dpuremetal.m4EarlyUploads=false: the separate buffer waits for the whole queue before the frame can be committed. Fall
    // back to the frame's own command buffer, where the copy is ordered by the tracked barriers.
    if (d->m4 && !d->m4EarlyUploads) { pm_copy_buffer(d, src, srcOffset, dst, dstOffset, size); return; }
    PM_ENC_SCOPE(PM_ENC_COPY);
    @autoreleasepool {
        if (!d->uploadCb) {
            id<MTLCommandBuffer> ucb = new_cb(d, true);
            id<MTLBlitCommandEncoder> ub = ucb ? new_blit_encoder(d, ucb, nil, true) : nil;
            if (!ub) { set_error(d, @"could not create an upload command buffer"); return; }
            d->uploadCb = pm_retain(ucb);
            d->uploadBlit = pm_retain(ub);
            atomic_fetch_add(&d->encCounts[3], 1);
            PM_ENC_COUNT(PM_ENC_CBS_CREATED, 1);
            PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
        }
        PM_IF_M4(d) {
            if (m4_uploads_tracked(d)) {
                // A fence recorded in the frame's command buffer (MetalFX, an overflow, -Dpuremetal.m4FrameFence) forgot what the frames
                // still in flight touched. This upload is committed ahead of the whole frame buffer, so it runs before that fence and
                // cannot lean on it: wait for everything already on the queue (including a frame whose read of the destination the
                // fence forgot) before overwriting the destination. One barrier covers every copy after the fence, not just this one.
                if (d->m4UploadNeedsFull) {
                    [PM_OBJ(id<MTL4ComputeCommandEncoder>, d->uploadBlit)
                        barrierAfterQueueStages:MTLStageAll beforeStages:MTLStageBlit visibilityOptions:MTL4VisibilityOptionDevice];
                    PM_ENC_COUNT(PM_ENC_BARRIERS, 1);
                    if (d->m4BarrierLog) {
                        m4_log_barrier(d, PM_OBJ(id<MTL4CommandEncoder>, d->uploadBlit), "consumer", "pm_copy_buffer_early",
                                       "upload destination", dst, MTLStageAll, MTLStageBlit, true);
                    }
                    d->m4UploadNeedsFull = false;
                }
                // The upload encoder goes through the tracker like a frame encoder: destination as a write, source as a read, so it
                // only waits for the earlier accesses to those resources (WAR against their readers, WAW against their writers, in
                // flight frames included) instead of for every stage of the previous frame. The serial advance taken when this
                // encoder was created is what turns these writes into prevW for the frame's encoders, as m4_early_use does.
                m4_blit_deps(d, &d->uploadHaz, PM_OBJ(id<MTL4ComputeCommandEncoder>, d->uploadBlit), src, dst, true);
                // -Dpuremetal.m4PreciseBarriers: end_uploads publishes this write for every later reader (m4_publish_upload_writes).
                m4_upload_written(d, dst);
            } else {
                m4_early_use(d, src, false);
                m4_early_use(d, dst, true);
                m4_blit_deps(d, &d->uploadHaz, PM_OBJ(id<MTL4ComputeCommandEncoder>, d->uploadBlit), src, dst, false);
            }
        }
        [PM_OBJ(id<MTLBlitCommandEncoder>, d->uploadBlit) copyFromBuffer:PM_OBJ(id<MTLBuffer>, src) sourceOffset:(NSUInteger)srcOffset
            toBuffer:PM_OBJ(id<MTLBuffer>, dst) destinationOffset:(NSUInteger)dstOffset size:(NSUInteger)size];
        d->stats[6]++;
    }
}

PM_EXPORT void pm_copy_buffer_to_texture(PMDevice *d, void *src, uint64_t offset, int bytesPerRow, int rows, void *dst,
                                         int mip, int slice, int x, int y, int width, int height) {
    PM_ENC_SCOPE(PM_ENC_COPY);
    @autoreleasepool {
        if (width <= 0 || height <= 0) return;
        id<MTLTexture> t = PM_OBJ(id<MTLTexture>, dst);
        MTLBlitOption opt = t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8 ? MTLBlitOptionDepthFromDepthStencil : MTLBlitOptionNone;
        id<MTLBlitCommandEncoder> blit = blit_encoder(d, 1);
        if (!blit) return;
        PM_IF_M4(d) m4_blit_deps(d, &d->blitHaz, (id<MTL4ComputeCommandEncoder>)blit, src, tex_key_ptr(dst), true);
        [blit copyFromBuffer:PM_OBJ(id<MTLBuffer>, src) sourceOffset:(NSUInteger)offset
                     sourceBytesPerRow:(NSUInteger)bytesPerRow sourceBytesPerImage:(NSUInteger)bytesPerRow * (NSUInteger)rows
                            sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
                             toTexture:t destinationSlice:(NSUInteger)slice destinationLevel:(NSUInteger)mip
                     destinationOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0) options:opt];
    }
}

PM_EXPORT void pm_copy_texture_to_buffer(PMDevice *d, void *src, int mip, int slice, int x, int y, int width, int height,
                                         void *dst, uint64_t offset, int bytesPerRow) {
    PM_ENC_SCOPE(PM_ENC_COPY);
    @autoreleasepool {
        if (width <= 0 || height <= 0) return;
        id<MTLTexture> t = PM_OBJ(id<MTLTexture>, src);
        MTLBlitOption opt = t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8 ? MTLBlitOptionDepthFromDepthStencil : MTLBlitOptionNone;
        id<MTLBlitCommandEncoder> blit = blit_encoder(d, 2);
        if (!blit) return;
        PM_IF_M4(d) m4_blit_deps(d, &d->blitHaz, (id<MTL4ComputeCommandEncoder>)blit, tex_key_ptr(src), dst, true);
        [blit copyFromTexture:t sourceSlice:(NSUInteger)slice sourceLevel:(NSUInteger)mip
                            sourceOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)
                              sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
                                toBuffer:PM_OBJ(id<MTLBuffer>, dst) destinationOffset:(NSUInteger)offset
                  destinationBytesPerRow:(NSUInteger)bytesPerRow
                destinationBytesPerImage:(NSUInteger)bytesPerRow * (NSUInteger)height options:opt];
    }
}

PM_EXPORT void pm_copy_texture_to_texture(PMDevice *d, void *src, void *dst, int mip, int srcX, int srcY, int dstX, int dstY,
                                          int width, int height) {
    PM_ENC_SCOPE(PM_ENC_COPY);
    @autoreleasepool {
        if (width <= 0 || height <= 0) return;
        id<MTLBlitCommandEncoder> blit = blit_encoder(d, 3);
        if (!blit) return;
        PM_IF_M4(d) m4_blit_deps(d, &d->blitHaz, (id<MTL4ComputeCommandEncoder>)blit, tex_key_ptr(src), tex_key_ptr(dst), true);
        [blit copyFromTexture:PM_OBJ(id<MTLTexture>, src) sourceSlice:0 sourceLevel:(NSUInteger)mip
                            sourceOrigin:MTLOriginMake((NSUInteger)srcX, (NSUInteger)srcY, 0)
                              sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
                               toTexture:PM_OBJ(id<MTLTexture>, dst) destinationSlice:0 destinationLevel:(NSUInteger)mip
                       destinationOrigin:MTLOriginMake((NSUInteger)dstX, (NSUInteger)dstY, 0)];
    }
}

// Clears every mip and slice of a texture with load-action passes. depth < 0 means "not a depth clear".
PM_EXPORT void pm_clear_texture(PMDevice *d, void *texture, float r, float g, float b, float a, double depth) {
    PM_ENC_SCOPE(PM_ENC_PASS);
    @autoreleasepool {
        end_blit(d);
        id<MTLTexture> t = PM_OBJ(id<MTLTexture>, texture);
        float color[4] = { r, g, b, a };
        if (t.textureType == MTLTextureType2D && t.mipmapLevelCount == 1) {
            if (d->bgDst && depth < 0.0 && root_texture(texture) == d->bgDst) drop_backdrop(d); // cleared over anyway
            // Deferred: folded into the next pass that attaches it, or encoded before anything else runs.
            int i = find_clear(d, texture);
            if (i < 0) {
                if (d->clearCount == PM_MAX_PENDING_CLEARS) current_cb(d);
                i = d->clearCount++;
                d->clears[i].texture = pm_retain(t);
            }
            memcpy(d->clears[i].color, color, sizeof(color));
            d->clears[i].depth = depth;
            return;
        }
        NSUInteger slices = t.textureType == MTLTextureTypeCube ? 6 : (t.arrayLength > 0 ? t.arrayLength : 1);
        current_cb(d);  // earlier pending clears first
        for (NSUInteger level = 0; level < t.mipmapLevelCount; level++) {
            for (NSUInteger slice = 0; slice < slices; slice++) {
                encode_clear(d, t, level, slice, color, depth);
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Render passes.

#define REN(d) PM_OBJ(id<MTLRenderCommandEncoder>, (d)->render)

// Resource binding for every draw. Metal 3 sets the encoder's slots per stage (PM_STAGE_* mask); Metal 4 writes the argument table,
// which both stages read, so the mask is ignored there. The table is read when a draw is encoded, like encoder state.
// The Metal 4 stages a PM_STAGE_* mask makes a resource reachable to; callers that cannot name a single stage pass both.
API_AVAILABLE(macos(26.0))
static MTLStages pm_m4_stages(int stages) {
    MTLStages m = 0;
    if (stages & PM_STAGE_VERTEX) m |= MTLStageVertex;
    if (stages & PM_STAGE_FRAGMENT) m |= MTLStageFragment;
    return m ? m : (MTLStageVertex | MTLStageFragment);
}

static void bind_bytes(PMDevice *d, id<MTLRenderCommandEncoder> e, int stages, const void *data, NSUInteger length, NSUInteger index) {
    PM_IF_M4(d) { m4_set_bytes(d, index, data, length); return; }
    if (stages & PM_STAGE_VERTEX) [e setVertexBytes:data length:length atIndex:index];
    if (stages & PM_STAGE_FRAGMENT) [e setFragmentBytes:data length:length atIndex:index];
}

// `offsetOnly`: the stage already has this buffer bound, only the offset changes.
// `stages` is the binding's PM_STAGE_* mask, which on Metal 4 is also the set of stages the tracked barrier has to hold back.
static void bind_buffer(PMDevice *d, id<MTLRenderCommandEncoder> e, int stages, id<MTLBuffer> b, uint64_t offset, NSUInteger index, bool offsetOnly) {
    PM_IF_M4(d) { m4_read(d, e, (__bridge void *)b, pm_m4_stages(stages)); m4_set_buffer(d, index, b, offset); return; }
    if ((stages & PM_STAGE_VERTEX) && offsetOnly) [e setVertexBufferOffset:(NSUInteger)offset atIndex:index];
    else if (stages & PM_STAGE_VERTEX) [e setVertexBuffer:b offset:(NSUInteger)offset atIndex:index];
    if ((stages & PM_STAGE_FRAGMENT) && offsetOnly) [e setFragmentBufferOffset:(NSUInteger)offset atIndex:index];
    else if (stages & PM_STAGE_FRAGMENT) [e setFragmentBuffer:b offset:(NSUInteger)offset atIndex:index];
}

static void bind_texture(PMDevice *d, id<MTLRenderCommandEncoder> e, int stages, id<MTLTexture> t, NSUInteger index) {
    PM_IF_M4(d) {
        m4_read(d, e, tex_key(t), pm_m4_stages(stages));
        m4_table_texture(d, index, t);
        return;
    }
    if (stages & PM_STAGE_VERTEX) [e setVertexTexture:t atIndex:index];
    if (stages & PM_STAGE_FRAGMENT) [e setFragmentTexture:t atIndex:index];
}

static void bind_sampler(PMDevice *d, id<MTLRenderCommandEncoder> e, int stages, id<MTLSamplerState> s, NSUInteger index) {
    PM_IF_M4(d) { m4_table_sampler(d, index, s); return; }
    if (stages & PM_STAGE_VERTEX) [e setVertexSamplerState:s atIndex:index];
    if (stages & PM_STAGE_FRAGMENT) [e setFragmentSamplerState:s atIndex:index];
}

// No third-party shader code: a full-target triangle writes only depth, with all color writes disabled.
// Failures leave the parked encoder untouched and fall back to the ordinary load-action clear.
static bool fold_depth_clear(PMDevice *d, void **colors, void *depth, double value, int width, int height) {
    if (!d->foldDepthClears || !depth || !isfinite(value) || value < 0.0 || value > 1.0) return false;
    id<MTLTexture> dt = PM_OBJ(id<MTLTexture>, depth);
    if (dt.pixelFormat != MTLPixelFormatDepth32Float || dt.textureType != MTLTextureType2D
            || dt.sampleCount != 1 || width != (int)dt.width || height != (int)dt.height) return false;
    MTLPixelFormat formats[PM_MAX_COLOR] = {0};
    for (int i = 0; i < PM_MAX_COLOR; i++) {
        if (!colors[i]) continue;
        id<MTLTexture> t = PM_OBJ(id<MTLTexture>, colors[i]);
        if (t.textureType != MTLTextureType2D || t.sampleCount != 1
                || width != (int)t.width || height != (int)t.height) return false;
        formats[i] = t.pixelFormat;
    }
    NSError *error = nil;
    if (!d->depthClearLibrary) {
        NSString *source = @"#include <metal_stdlib>\nusing namespace metal;\n"
            "vertex float4 pm_depth_clear(uint i [[vertex_id]], constant float &z [[buffer(0)]]) {"
            "float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};return float4(p[i],z,1);}";
        id<MTLLibrary> lib = pm_new_library(d, source, nil, &error);
        if (!lib) return false;
        d->depthClearLibrary = pm_retain(lib);
        MTLDepthStencilDescriptor *ds = [MTLDepthStencilDescriptor new];
        ds.depthCompareFunction = MTLCompareFunctionAlways;
        ds.depthWriteEnabled = YES;
        d->depthClearState = pm_retain([DEV_MTL(d) newDepthStencilStateWithDescriptor:ds]);
    }
    if (!d->depthClearState) return false;
    if (!d->depthClearPipeline || memcmp(formats, d->depthClearFormats, sizeof(formats)) != 0) {
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.vertexFunction = [PM_OBJ(id<MTLLibrary>, d->depthClearLibrary) newFunctionWithName:@"pm_depth_clear"];
        pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
        for (int i = 0; i < PM_MAX_COLOR; i++) {
            pd.colorAttachments[i].pixelFormat = formats[i];
            pd.colorAttachments[i].writeMask = MTLColorWriteMaskNone;
        }
        id<MTLRenderPipelineState> pipeline = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error];
        if (!pipeline) return false;
        pm_release(d->depthClearPipeline);
        d->depthClearPipeline = pm_retain(pipeline);
        memcpy(d->depthClearFormats, formats, sizeof(formats));
    }
    id<MTLRenderCommandEncoder> enc = PM_OBJ(id<MTLRenderCommandEncoder>, d->parked);
    [enc setRenderPipelineState:PM_OBJ(id<MTLRenderPipelineState>, d->depthClearPipeline)];
    [enc setDepthStencilState:PM_OBJ(id<MTLDepthStencilState>, d->depthClearState)];
    [enc setViewport:(MTLViewport){0, 0, (double)width, (double)height, 0, 1}];
    [enc setScissorRect:(MTLScissorRect){0, 0, (NSUInteger)width, (NSUInteger)height}];
    [enc setCullMode:MTLCullModeNone];
    [enc setTriangleFillMode:MTLTriangleFillModeFill];
    [enc setDepthBias:0 slopeScale:0 clamp:0];
    float z = (float)value;
    bind_bytes(d, enc, PM_STAGE_VERTEX, &z, sizeof(z), 0);
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    return true;
}

// Clear a depth rectangle with a depth-only triangle. Stencil is loaded and preserved.
static bool partial_depth_clear(PMDevice *d, void *depth, double value, int width, int height,
                                int x, int y, int w, int h) {
    if (!isfinite(value)) return false;
    value = fmin(fmax(value, 0.0), 1.0);
    id<MTLTexture> t = PM_OBJ(id<MTLTexture>, depth);
    if (!is_depth_format(t.pixelFormat)
            || (t.textureType != MTLTextureType2D && t.textureType != MTLTextureTypeCube) || t.sampleCount != 1) return false;
    // The pass size comes from the caller; the rectangle must also fit this attachment.
    if ((NSUInteger)x + (NSUInteger)w > t.width || (NSUInteger)y + (NSUInteger)h > t.height) return false;
    NSError *error = nil;
    if (!d->partialDepthLibrary) {
        NSString *source = @"#include <metal_stdlib>\nusing namespace metal;\n"
            "vertex float4 pm_partial_depth(uint i [[vertex_id]], constant float &z [[buffer(0)]]) {"
            "float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};return float4(p[i],z,1);}";
        id<MTLLibrary> lib = pm_new_library(d, source, nil, &error);
        if (!lib) return false;
        d->partialDepthLibrary = pm_retain(lib);
        MTLDepthStencilDescriptor *ds = [MTLDepthStencilDescriptor new];
        ds.depthCompareFunction = MTLCompareFunctionAlways;
        ds.depthWriteEnabled = YES;
        d->partialDepthState = pm_retain([DEV_MTL(d) newDepthStencilStateWithDescriptor:ds]);
    }
    if (!d->partialDepthState) return false;
    if (!d->partialDepthPipeline || d->partialDepthFormat != t.pixelFormat) {
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.vertexFunction = [PM_OBJ(id<MTLLibrary>, d->partialDepthLibrary) newFunctionWithName:@"pm_partial_depth"];
        pd.depthAttachmentPixelFormat = t.pixelFormat;
        if (t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) pd.stencilAttachmentPixelFormat = t.pixelFormat;
        id<MTLRenderPipelineState> pipeline = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error];
        if (!pipeline) return false;
        pm_release(d->partialDepthPipeline);
        d->partialDepthPipeline = pm_retain(pipeline);
        d->partialDepthFormat = t.pixelFormat;
    }
    id<MTLCommandBuffer> cb = current_cb(d);
    MTLRenderPassDescriptor *rp = new_pass_descriptor(d);
    rp.depthAttachment.texture = t;
    rp.depthAttachment.loadAction = MTLLoadActionLoad;
    rp.depthAttachment.storeAction = MTLStoreActionStore;
    if (t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) {
        rp.stencilAttachment.texture = t;
        rp.stencilAttachment.loadAction = MTLLoadActionLoad;
        rp.stencilAttachment.storeAction = MTLStoreActionStore;
    }
    id<MTLRenderCommandEncoder> enc = new_render_encoder_kind(d, cb, rp, 1, "partial depth clear");
    if (!enc) return false;
    [enc setRenderPipelineState:PM_OBJ(id<MTLRenderPipelineState>, d->partialDepthPipeline)];
    [enc setDepthStencilState:PM_OBJ(id<MTLDepthStencilState>, d->partialDepthState)];
    [enc setViewport:(MTLViewport){0, 0, (double)width, (double)height, 0, 1}];
    [enc setScissorRect:(MTLScissorRect){(NSUInteger)x, (NSUInteger)y, (NSUInteger)w, (NSUInteger)h}];
    float z = (float)value;
    bind_bytes(d, enc, PM_STAGE_VERTEX, &z, sizeof(z), 0);
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [enc endEncoding];
    return true;
}

// Metal attachment clears ignore the render area. Clear a temporary texture in the attachment's own
// format, then copy only the requested rectangle. This preserves Metal's format conversion rules.
static bool partial_color_clear(PMDevice *d, void *destination, const float *color, int x, int y, int w, int h) {
    id<MTLTexture> target = PM_OBJ(id<MTLTexture>, destination);
    if ((target.textureType != MTLTextureType2D && target.textureType != MTLTextureTypeCube) || target.sampleCount != 1) return false;
    if ((NSUInteger)x + (NSUInteger)w > target.width || (NSUInteger)y + (NSUInteger)h > target.height) return false;
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:target.pixelFormat
                                                                                  width:(NSUInteger)w height:(NSUInteger)h mipmapped:NO];
    td.storageMode = MTLStorageModePrivate;
    td.usage = MTLTextureUsageRenderTarget;
    void *temporary = pm_retain([DEV_MTL(d) newTextureWithDescriptor:td]);
    if (!temporary) return false;
    id<MTLCommandBuffer> cb = current_cb(d);
    MTLRenderPassDescriptor *rp = new_pass_descriptor(d);
    rp.colorAttachments[0].texture = PM_OBJ(id<MTLTexture>, temporary);
    rp.colorAttachments[0].loadAction = MTLLoadActionClear;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    rp.colorAttachments[0].clearColor = MTLClearColorMake(color[0], color[1], color[2], color[3]);
    id<MTLRenderCommandEncoder> enc = new_render_encoder_kind(d, cb, rp, 1, "partial color clear");
    if (!enc) { pm_release(temporary); return false; }
    [enc endEncoding];
    id<MTLBlitCommandEncoder> blit = blit_encoder(d, 3);
    if (!blit) { pm_release(temporary); return false; }
    PM_IF_M4(d) m4_blit_deps(d, &d->blitHaz, (id<MTL4ComputeCommandEncoder>)blit,
                             tex_key_ptr(temporary), tex_key_ptr(destination), true);
    [blit copyFromTexture:PM_OBJ(id<MTLTexture>, temporary) sourceSlice:0 sourceLevel:0
             sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
                toTexture:target destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)];
    pm_release(temporary);
    end_blit(d);
    return !atomic_load(&d->failed);
}

static void diag_split_pass(PMDevice *d, const char *label, int width, int height, bool depth);

// colorLoad: 0 load, 1 clear, -1 unused attachment. depthLoad likewise, depthTex may be NULL.
PM_EXPORT int pm_pass_begin(PMDevice *d, int colorCount, void **colors, const int *colorLoad, const float *clearColors,
                            void *depth, int depthLoad, double clearDepth, int width, int height,
                            int sx, int sy, int sw, int sh, const char *label) {
    PM_ENC_SCOPE(PM_ENC_PASS);
    @autoreleasepool {
        end_blit(d);
        bool partial = sx != 0 || sy != 0 || sw != width || sh != height;
        bool hasClear = depth && depthLoad == 1;
        int loads[PM_MAX_COLOR];
        for (int i = 0; i < colorCount && i < PM_MAX_COLOR; i++) hasClear |= colors[i] && colorLoad[i] == 1;
        if (partial && hasClear) {
            if (sx < 0 || sy < 0 || sw < 0 || sh < 0 || sx > width || sy > height
                    || sw > width - sx || sh > height - sy) return 0;
            // Preserve earlier contents, deferred clears and parked passes before touching individual attachments.
            current_cb(d);
            if (colorCount > PM_MAX_COLOR) return 0;
            // An attachment whose partial clear is declined keeps its clear load action (a whole-attachment clear).
            bool fellBack = false;
            for (int i = 0; i < colorCount; i++) {
                loads[i] = colorLoad[i];
                if (colors[i] && loads[i] == 1) {
                    if (sw && sh && !partial_color_clear(d, colors[i], clearColors + i * 4, sx, sy, sw, sh)) { fellBack = true; continue; }
                    loads[i] = 0;
                }
            }
            if (depth && depthLoad == 1) {
                if (sw && sh && !partial_depth_clear(d, depth, clearDepth, width, height, sx, sy, sw, sh)) fellBack = true;
                else depthLoad = 0;
            }
            if (fellBack) {
                static atomic_flag loggedFallback = ATOMIC_FLAG_INIT;
                if (!atomic_flag_test_and_set(&loggedFallback))
                    NSLog(@"Pure Metal: a partial render-area clear was declined; clearing the whole attachment instead (further cases are not logged)");
            }
            colorLoad = loads;
        }
        if (d->txSpan) diag_split_pass(d, label, width, height, depth != NULL);
        void *usedColors[PM_MAX_COLOR] = { 0 };
        int clears = depth && depthLoad == 1;
        // Pending clears of this pass's attachments become its load actions.
        int foldColor[PM_MAX_COLOR] = { 0 };
        float foldColorValue[PM_MAX_COLOR][4];
        int foldDepth = 0;
        double foldDepthValue = 0.0;
        for (int i = 0; i < colorCount && i < PM_MAX_COLOR; i++) {
            if (colorLoad[i] < 0 || !colors[i]) continue;
            usedColors[i] = colors[i];
            clears |= colorLoad[i] == 1;
            int c = find_clear(d, colors[i]);
            if (c >= 0) {
                foldColor[i] = colorLoad[i] != 1;
                memcpy(foldColorValue[i], d->clears[c].color, sizeof(foldColorValue[i]));
                drop_clear(d, c);
                clears = 1;
            }
        }
        if (depth) {
            int c = find_clear(d, depth);
            if (c >= 0) {
                foldDepth = depthLoad != 1;
                foldDepthValue = d->clears[c].depth;
                drop_clear(d, c);
                clears = 1;
            }
        }
        // A pending temporal stretch of the one color target this pass loads becomes the pass's first draw: the target is
        // never stored and reloaded in between. Anything else (or any failure to set that up) flushes it as its own pass.
        void *backdropSrc = NULL;
        int backdropColor = -1;
        if (d->bgDst && !d->parked) {
            int only = -1, used = 0;
            for (int i = 0; i < colorCount && i < PM_MAX_COLOR; i++) {
                if (!usedColors[i]) continue;
                used++;
                only = i;
            }
            id<MTLTexture> bt = used == 1 ? PM_OBJ(id<MTLTexture>, usedColors[only]) : nil;
            bool depthOk = !depth || (PM_OBJ(id<MTLTexture>, depth).pixelFormat == MTLPixelFormatDepth32Float
                    && PM_OBJ(id<MTLTexture>, depth).textureType == MTLTextureType2D && PM_OBJ(id<MTLTexture>, depth).sampleCount == 1);
            if (bt && only == 0 && colorLoad[only] == 0 && !foldColor[only] && root_texture(usedColors[only]) == d->bgDst
                    && bt.textureType == MTLTextureType2D && bt.sampleCount == 1
                    && width == (int)bt.width && height == (int)bt.height && depthOk
                    && (depth ? ensure_upscale_depth_pipeline(d, bt.pixelFormat) : ensure_upscale_pipeline(d, bt.pixelFormat))) {
                backdropSrc = d->bgSrc;
                backdropColor = only;
                d->bgSrc = NULL;
                pm_release(d->bgDst);
                d->bgDst = NULL;
            }
        }
        // Only depth may clear; same full-sized views, no intervening work and no pending observable clears.
        // Depth/stencil formats intentionally fall back: a folded texture clear also clears stencil.
        bool onlyDepthClear = depth && (depthLoad == 1 || foldDepth);
        for (int i = 0; i < colorCount && i < PM_MAX_COLOR; i++)
            if (usedColors[i] && (colorLoad[i] == 1 || foldColor[i])) onlyDepthClear = false;
        if (d->parked && onlyDepthClear && d->clearCount == 0 && depth == d->parkedDepth
                && width == d->parkedWidth && height == d->parkedHeight
                && memcmp(usedColors, d->parkedColors, sizeof(usedColors)) == 0
                && fold_depth_clear(d, usedColors, depth, foldDepth ? foldDepthValue : clearDepth, width, height)) clears = 0;
        // Other pending clears must land before this pass (it may sample those textures), so no merging then.
        if (d->parked && !clears && d->clearCount == 0 && depth == d->parkedDepth && width == d->parkedWidth && height == d->parkedHeight
                && memcmp(usedColors, d->parkedColors, sizeof(usedColors)) == 0) {
            // Same attachments, nothing recorded in between and nothing to clear: keep drawing in the open encoder.
            d->stats[1]++;
            d->render = d->parked;
            d->parked = NULL;
            d->traceRender = d->traceParked;
            d->traceRender.merged++;
            d->stateSet = 0;
            // Also reset on a merged logical pass; its Java bindings start fresh.
            memset(&d->bindings, 0, sizeof(d->bindings));
            d->inlineValid = 0;
            id<MTLRenderCommandEncoder> enc = REN(d);
            [enc setViewport:(MTLViewport){ 0.0, 0.0, (double)width, (double)height, 0.0, 1.0 }];
            [enc setScissorRect:(MTLScissorRect){ (NSUInteger)sx, (NSUInteger)sy, (NSUInteger)sw, (NSUInteger)sh }];
            memcpy(d->passColors, usedColors, sizeof(usedColors));
            d->passDepth = depth;
            d->passWidth = width;
            d->passHeight = height;
            d->indexBuffer = NULL;
            d->indexAddress = d->indexLength = 0;
            return 1;
        }
        // A full clear of the exact same color view makes the parked pass's store unnecessary.
        // Do not infer this from scissor/draw coverage, different views, or partial render dimensions.
        uint32_t colorDiscard = 0;
        if (d->parked) {
            for (int i = 0; i < colorCount && i < PM_MAX_COLOR; i++) {
                if (!usedColors[i] || usedColors[i] != d->parkedColors[i] || !(colorLoad[i] == 1 || foldColor[i])) continue;
                id<MTLTexture> t = PM_OBJ(id<MTLTexture>, usedColors[i]);
                if (t.textureType != MTLTextureType2D || t.sampleCount != 1
                        || width != (int)t.width || height != (int)t.height) continue;
                colorDiscard |= 1u << i;
            }
        }
        // The open pass's depth is about to be cleared by this one with nothing in between, so storing it is wasted.
        // (Stencil is only cleared along with a folded clear; otherwise it is loaded and must be kept.)
        bool depthDiscard = d->parked && depth && depth == d->parkedDepth && (foldDepth
                || (depthLoad == 1 && PM_OBJ(id<MTLTexture>, depth).pixelFormat != MTLPixelFormatDepth32Float_Stencil8));
        if (colorDiscard || depthDiscard) {
            end_parked_actions(d, depthDiscard ? MTLStoreActionDontCare : MTLStoreActionStore, colorDiscard);
        }
        memcpy(d->passColors, usedColors, sizeof(usedColors));
        d->passDepth = depth;
        d->passWidth = width;
        d->passHeight = height;
        MTLRenderPassDescriptor *rp = new_pass_descriptor(d);
        for (int i = 0; i < colorCount && i < PM_MAX_COLOR; i++) {
            if (colorLoad[i] < 0 || !colors[i]) continue;
            MTLRenderPassColorAttachmentDescriptor *c = rp.colorAttachments[(NSUInteger)i];
            c.texture = PM_OBJ(id<MTLTexture>, colors[i]);
            c.loadAction = (colorLoad[i] == 1 || foldColor[i]) ? MTLLoadActionClear : MTLLoadActionLoad;
            if (i == backdropColor) c.loadAction = MTLLoadActionDontCare;
            c.storeAction = MTLStoreActionUnknown;
            c.clearColor = foldColor[i]
                ? MTLClearColorMake(foldColorValue[i][0], foldColorValue[i][1], foldColorValue[i][2], foldColorValue[i][3])
                : MTLClearColorMake(clearColors[i * 4], clearColors[i * 4 + 1], clearColors[i * 4 + 2], clearColors[i * 4 + 3]);
        }
        if (depth) {
            id<MTLTexture> dt = PM_OBJ(id<MTLTexture>, depth);
            rp.depthAttachment.texture = dt;
            rp.depthAttachment.loadAction = (depthLoad == 1 || foldDepth) ? MTLLoadActionClear : MTLLoadActionLoad;
            rp.depthAttachment.storeAction = MTLStoreActionUnknown;
            // Metal requires a finite clear depth in [0, 1], even with a Load action. NaN clears to the far plane.
            double descriptorDepth = foldDepth ? foldDepthValue : clearDepth;
            rp.depthAttachment.clearDepth = isfinite(descriptorDepth) ? fmin(fmax(descriptorDepth, 0.0), 1.0) : 1.0;
            if (dt.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) {
                rp.stencilAttachment.texture = dt;
                rp.stencilAttachment.loadAction = foldDepth ? MTLLoadActionClear : MTLLoadActionLoad;
                rp.stencilAttachment.storeAction = MTLStoreActionUnknown;
            }
        }
        id<MTLCommandBuffer> cb = current_cb(d);
        id<MTLCounterSampleBuffer> samples = profile_render(d, rp);
        id<MTLRenderCommandEncoder> enc = new_render_encoder(d, cb, rp, label);
        if (!enc) {
            if (backdropSrc) { // keep the stretch pending: the next pass or the frame's end lands it
                d->bgSrc = backdropSrc;
                d->bgDst = pm_retain(PM_OBJ(id<MTLTexture>, root_texture(colors[backdropColor])));
            }
            return 0;
        }
        profile_completion(d, cb, samples, true);
        d->stats[0]++;
        PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
        for (int i = 0; i < PM_MAX_COLOR; i++) {
            MTLRenderPassColorAttachmentDescriptor *c = rp.colorAttachments[(NSUInteger)i];
            if (!c.texture) continue;
            if (c.loadAction == MTLLoadActionLoad) d->stats[4] += c.texture.allocatedSize;
            d->stats[5] += c.texture.allocatedSize;
        }
        if (rp.depthAttachment.texture) {
            if (rp.depthAttachment.loadAction == MTLLoadActionLoad) d->stats[4] += rp.depthAttachment.texture.allocatedSize;
            d->stats[5] += rp.depthAttachment.texture.allocatedSize;
        }
        if (d->traceOn) {
            PMPassTrace *t = &d->traceRender;
            memset(t, 0, sizeof(*t));
            snprintf(t->label, sizeof(t->label), "%s", label && label[0] ? label : "pass");
            t->width = width;
            t->height = height;
            for (int i = 0; i < PM_MAX_COLOR; i++) {
                MTLRenderPassColorAttachmentDescriptor *c = rp.colorAttachments[(NSUInteger)i];
                if (c.texture) trace_attachment(t, 0, i, c.texture, (int)c.loadAction);
            }
            if (rp.depthAttachment.texture) trace_attachment(t, 1, 0, rp.depthAttachment.texture, (int)rp.depthAttachment.loadAction);
        }
        if (label && d->debugLabels) enc.label = [NSString stringWithUTF8String:label];
        if (backdropSrc) {
            draw_stretch(d, enc, PM_OBJ(id<MTLTexture>, backdropSrc), width, height, depth != NULL);
            pm_release(backdropSrc);
        }
        MTLViewport vp = { 0.0, 0.0, (double)width, (double)height, 0.0, 1.0 };
        [enc setViewport:vp];
        [enc setScissorRect:(MTLScissorRect){ (NSUInteger)sx, (NSUInteger)sy, (NSUInteger)sw, (NSUInteger)sh }];
        // Vertex Y is flipped in the shaders, so pixels land exactly where Vulkan puts them; front faces are
        // therefore clockwise, matching VulkanRenderPipeline's frontFace(CLOCKWISE).
        [enc setFrontFacingWinding:MTLWindingClockwise];
        d->render = pm_retain(enc);
        d->stateSet = 0;
        memset(&d->bindings, 0, sizeof(d->bindings));
        d->inlineValid = 0;
        d->indexBuffer = NULL;
        d->indexAddress = d->indexLength = 0;
        return 1;
    }
}

PM_EXPORT void pm_pass_end(PMDevice *d) {
    PM_ENC_SCOPE(PM_ENC_PASS);
    @autoreleasepool {
        // The pass timestamp span is not closed here: it belongs to the physical encoder and closes when that encoder ends
        // (end_parked_actions), so a pass merged into an open encoder stays inside the span.
        if (!d->render) return;
        end_parked(d);
        d->parked = d->render;
        memcpy(d->parkedColors, d->passColors, sizeof(d->passColors));
        d->traceParked = d->traceRender;
        d->parkedDepth = d->passDepth;
        d->parkedWidth = d->passWidth;
        d->parkedHeight = d->passHeight;
        d->render = NULL;
        d->indexBuffer = NULL;
        d->indexAddress = d->indexLength = 0;
        // Diagnostic queries belong to this logical pass. Do not merge later HUD/hand/depth-clear work into its snapshot.
        if (d->probeCount) end_parked(d);
    }
}

PM_EXPORT void pm_push_debug_group(PMDevice *d, const char *label) {
    PM_ENC_SCOPE(PM_ENC_PASS);
    @autoreleasepool {
        if (!d->debugLabels || !label) return;
        NSString *s = [NSString stringWithUTF8String:label];
        if (d->render) [REN(d) pushDebugGroup:s]; else [current_cb(d) pushDebugGroup:s];
    }
}

PM_EXPORT void pm_pop_debug_group(PMDevice *d) {
    PM_ENC_SCOPE(PM_ENC_PASS);
    @autoreleasepool {
        if (!d->debugLabels) return;
        if (d->render) [REN(d) popDebugGroup]; else [current_cb(d) popDebugGroup];
    }
}

PM_EXPORT void pm_pass_set_pipeline(PMDevice *d, void *pipeline, void *depthState, int cull, int wireframe,
                                    float depthBiasConstant, float depthBiasSlope) {
    PM_ENC_SCOPE(PM_ENC_PASS);
    @autoreleasepool {
        id<MTLRenderCommandEncoder> e = REN(d);
        if (d->bindings.pipeline != pipeline) {
            [e setRenderPipelineState:PM_OBJ(id<MTLRenderPipelineState>, pipeline)];
            d->bindings.pipeline = pipeline;
            // The argument table keeps what earlier pipelines had bound; the stage masks of those resources may now be different.
            d->bindings.stageMaskStale = true;
            // The reflection is read once per pipeline change here; the bind paths read the cached masks inline.
            PM_IF_M4(d) d->bindings.bindMaskKnown = d->m4PreciseBarriers && pm_pipeline_bind_mask(pipeline, &d->bindings.bindMask);
        }
        if (depthState && d->bindings.depth != depthState) {
            [e setDepthStencilState:PM_OBJ(id<MTLDepthStencilState>, depthState)];
            d->bindings.depth = depthState;
        }
        int c = cull != 0, w = wireframe != 0;
        if (!d->stateSet || d->cullSet != c) { [e setCullMode:c ? MTLCullModeBack : MTLCullModeNone]; d->cullSet = c; }
        if (!d->stateSet || d->wireSet != w) { [e setTriangleFillMode:w ? MTLTriangleFillModeLines : MTLTriangleFillModeFill]; d->wireSet = w; }
        if (!d->stateSet || d->biasSet[0] != depthBiasConstant || d->biasSet[1] != depthBiasSlope) {
            [e setDepthBias:depthBiasConstant slopeScale:depthBiasSlope clamp:0.0f];
            d->biasSet[0] = depthBiasConstant;
            d->biasSet[1] = depthBiasSlope;
        }
        d->stateSet = 1;
    }
}

PM_EXPORT void pm_pass_set_scissor(PMDevice *d, int x, int y, int w, int h) {
    PM_ENC_SCOPE(PM_ENC_PASS);
    @autoreleasepool {
        [REN(d) setScissorRect:(MTLScissorRect){ (NSUInteger)x, (NSUInteger)y, (NSUInteger)w, (NSUInteger)h }];
    }
}

PM_EXPORT void pm_pass_set_vertex_buffer(PMDevice *d, int slot, void *buffer, uint64_t offset) {
    PM_ENC_SCOPE(PM_ENC_BIND);
    @autoreleasepool {
        if (slot < 0 || slot >= 8) { set_error(d, @"invalid vertex buffer slot"); return; }
        NSUInteger idx = (NSUInteger)(PM_VERTEX_BUFFER_BASE + slot);
        uint32_t bit = 1u << idx;
        PMBindingState *b = &d->bindings;
        if (!(b->vertexBufferSet & bit) || b->vertexBuffers[idx] != buffer || b->vertexOffsets[idx] != offset) {
            PM_ENC_COUNT(PM_ENC_BUFFER_BINDS, d->m4 ? 0 : 1);
            bind_buffer(d, REN(d), PM_STAGE_VERTEX, PM_OBJ(id<MTLBuffer>, buffer), offset, idx,
                        (b->vertexBufferSet & bit) && buffer && b->vertexBuffers[idx] == buffer);
            b->vertexBuffers[idx] = buffer;
            b->vertexOffsets[idx] = offset;
            b->vertexBufferSet |= bit;
        }
    }
}

PM_EXPORT void pm_pass_set_index_buffer(PMDevice *d, void *buffer, int is32) {
    PM_ENC_SCOPE(PM_ENC_BIND);
    @autoreleasepool {
        d->indexBuffer = buffer;
        d->indexType = is32 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16;
        PM_IF_M4(d) {
            id<MTLBuffer> ib = PM_OBJ(id<MTLBuffer>, buffer);
            d->indexAddress = ib.gpuAddress;
            d->indexLength = ib.length;
            if (d->render) m4_read(d, REN(d), buffer, MTLStageVertex); // indices are fetched for the vertex stage only
        }
    }
}

// kinds: 0 buffer (obj = MTLBuffer, offset), 1 texture (obj = MTLTexture), 2 sampler (obj = MTLSamplerState).
// Bound to both vertex and fragment stages at `index`.
//
// -Dpuremetal.m4PreciseBarriers: the stages a binding at this slot makes its resource reachable to, from the current pipeline's
// reflection (pm_pipeline_attach_mask). Anything the reflection does not describe - no pipeline set yet, a slot it does not name,
// a pipeline built without it - stays on both stages, so a hazard is never dropped on a guess.
static int m4_bind_stages(PMDevice *d, int kind, NSUInteger index) {
    int both = PM_STAGE_VERTEX | PM_STAGE_FRAGMENT;
    if (!d->m4 || !d->m4PreciseBarriers) return both;
    // Resolved when the pipeline was set, and after the caller's unchanged-slot check, so a bind that writes no table slot
    // never looks the reflection up at all.
    if (!d->bindings.bindMaskKnown) return both;
    int stages = kind == 1 ? d->bindings.bindMask.texture[index] : d->bindings.bindMask.buffer[index];
    return stages ? stages : both;
}

// The binding lines of the -Dpuremetal.m4BarrierLog dump: what the resource was bound as and the stages its barrier is for.
API_AVAILABLE(macos(26.0))
static void m4_log_bind(PMDevice *d, const char *kind, NSUInteger index, void *res, int stages) {
    if (!d->m4BarrierLog) return;
    uint64_t frame = atomic_load(&d->m4Seq);
    if (!m4_log_frame(d, frame)) return;
    char stagesText[16], resText[128];
    m4_log_stages(pm_m4_stages(stages), stagesText, sizeof(stagesText));
    m4_log_resource(d, res, resText, sizeof(resText));
    PMM4LogCtx *c = &pm_m4_log; // this thread's encoder; binds are recorded by the render thread only
    NSLog(@"Pure Metal m4 order: frame %llu encoder %d %s bind %s slot=%u stages=%s res=%s", (unsigned long long)frame,
          c->enc ? (int)c->index : -1, c->enc ? c->kind : "?", kind, (unsigned)index, stagesText, resText);
}

PM_EXPORT void pm_pass_bind(PMDevice *d, int count, const int *kinds, const int *indices, void **objs, const uint64_t *offsets) {
    PM_ENC_SCOPE(PM_ENC_BIND);
    @autoreleasepool {
        id<MTLRenderCommandEncoder> e = REN(d);
        for (int i = 0; i < count; i++) {
            int limit = kinds[i] == 0 ? PM_BUFFER_BINDINGS : kinds[i] == 1 ? PM_TEXTURE_BINDINGS : PM_SAMPLER_BINDINGS;
            if (kinds[i] < 0 || kinds[i] > 2 || indices[i] < 0 || indices[i] >= limit) {
                set_error(d, @"invalid resource binding kind or index"); return;
            }
            NSUInteger idx = (NSUInteger)indices[i];
            uint32_t bit = 1u << idx;
            PMBindingState *state = &d->bindings;
            switch (kinds[i]) {
                case 0: {
                    id<MTLBuffer> b = PM_OBJ(id<MTLBuffer>, objs[i]);
                    if (!(state->vertexBufferSet & bit) || state->vertexBuffers[idx] != objs[i] || state->vertexOffsets[idx] != offsets[i]) {
                        // Metal 4 has one table slot for both stages, so the first write is also what the fragment stage reads:
                        // the tracked barrier has to cover the stages of the pipeline's reflection for this slot.
                        int stages = d->m4 ? m4_bind_stages(d, 0, idx) : PM_STAGE_VERTEX;
                        PM_ENC_COUNT(PM_ENC_BUFFER_BINDS, d->m4 ? 0 : 1);
                        bind_buffer(d, e, stages, b, offsets[i], idx,
                                    (state->vertexBufferSet & bit) && b && state->vertexBuffers[idx] == objs[i]);
                        PM_IF_M4(d) m4_log_bind(d, "buffer", idx, objs[i], stages);
                        state->vertexBuffers[idx] = objs[i];
                        state->vertexOffsets[idx] = offsets[i];
                        state->vertexBufferSet |= bit;
                        // One table slot serves both stages: the write above also bound the fragment stage, which the Metal 3 path
                        // binds separately. The benchmark counts logical slot bindings, so neither side counts the second stage.
                        if (d->m4) {
                            state->fragmentBuffers[idx] = objs[i];
                            state->fragmentOffsets[idx] = offsets[i];
                            state->fragmentBufferSet |= bit;
                        }
                    }
                    if (!(state->fragmentBufferSet & bit) || state->fragmentBuffers[idx] != objs[i] || state->fragmentOffsets[idx] != offsets[i]) {
                        // Not counted: the vertex branch above already counted this logical slot, and Metal 4 covers both stages in
                        // one table write (which its own counter records).
                        bind_buffer(d, e, PM_STAGE_FRAGMENT, b, offsets[i], idx,
                                    (state->fragmentBufferSet & bit) && b && state->fragmentBuffers[idx] == objs[i]);
                        state->fragmentBuffers[idx] = objs[i];
                        state->fragmentOffsets[idx] = offsets[i];
                        state->fragmentBufferSet |= bit;
                    }
                    break;
                }
                case 1: {
                    if ((state->textureSet & bit) && state->textures[idx] == objs[i]) break;
                    int stages = d->m4 ? m4_bind_stages(d, 1, idx) : (PM_STAGE_VERTEX | PM_STAGE_FRAGMENT);
                    id<MTLTexture> t = PM_OBJ(id<MTLTexture>, objs[i]);
                    // One logical texture slot covers both stages, and Metal 4 records its single table write itself. The barrier
                    // it registers only names the stages the current pipeline's reflection reads the slot in (both if unknown).
                    PM_ENC_COUNT(PM_ENC_TEXTURE_BINDS, d->m4 ? 0 : 1);
                    bind_texture(d, e, stages, t, idx);
                    PM_IF_M4(d) m4_log_bind(d, "texture", idx, objs[i], stages);
                    state->textures[idx] = objs[i];
                    state->textureSet |= bit;
                    break;
                }
                default: {
                    if ((state->samplerSet & bit) && state->samplers[idx] == objs[i]) break;
                    id<MTLSamplerState> s = PM_OBJ(id<MTLSamplerState>, objs[i]);
                    PM_ENC_COUNT(PM_ENC_TEXTURE_BINDS, d->m4 ? 0 : 1);
                    bind_sampler(d, e, PM_STAGE_VERTEX | PM_STAGE_FRAGMENT, s, idx);
                    state->samplers[idx] = objs[i];
                    state->samplerSet |= bit;
                    break;
                }
            }
        }
    }
}

// -Dpuremetal.m4PreciseBarriers: re-registers the encoder's currently bound resources with the stage masks of the pipeline that
// is now set. Two cases need it: a bind recorded before any pipeline was set (registered for both stages by m4_bind_stages), and
// a slot whose bind was skipped because the same resource was already there while the pipeline changed (its old registration may
// not cover a stage the new pipeline reads). m4_use only emits barriers for stages the encoder has not waited for yet, so the
// extra registration costs a lookup and never a duplicate barrier; it runs once per pipeline change, at the next draw.
API_AVAILABLE(macos(26.0))
static void m4_resolve_bindings(PMDevice *d) {
    if (!d->m4 || !d->m4PreciseBarriers || !d->render || !d->bindings.stageMaskStale) return;
    PMBindingState *b = &d->bindings;
    b->stageMaskStale = false;
    const PMBindMask *mask = b->bindMaskKnown ? &b->bindMask : NULL;
    id<MTLRenderCommandEncoder> e = REN(d);
    for (NSUInteger i = 0; i < PM_BUFFER_BINDINGS; i++) {
        uint32_t bit = 1u << i;
        void *res;
        bool fragment = (b->fragmentBufferSet & bit) != 0;
        if (fragment) res = b->fragmentBuffers[i];
        else if (b->vertexBufferSet & bit) res = b->vertexBuffers[i];
        else continue;
        // The reflection covers the shader slots; a vertex-only attribute slot (never in fragmentBufferSet) falls back to the
        // vertex stage, which pm_pass_set_vertex_buffer already registers it for.
        int fallback = fragment || i < PM_VERTEX_BUFFER_BASE ? (PM_STAGE_VERTEX | PM_STAGE_FRAGMENT) : PM_STAGE_VERTEX;
        int stages = mask && mask->buffer[i] ? mask->buffer[i] : fallback;
        m4_read(d, e, res, pm_m4_stages(stages));
        if (d->m4BarrierLog) m4_log_bind(d, "bound buffer", i, res, stages);
    }
    for (NSUInteger i = 0; i < PM_TEXTURE_BINDINGS; i++) {
        uint32_t bit = 1u << i;
        if (!(b->textureSet & bit)) continue;
        // The tracker is keyed by the resource an access conflicts on, which bind_texture registers through tex_key: a view is
        // its root texture and a texel buffer its backing buffer. Registering the bound view itself would open a second entry
        // with no history, losing the barrier for this read and hiding it from later writes.
        void *res = tex_key_ptr(b->textures[i]);
        int stages = mask && mask->texture[i] ? mask->texture[i] : (PM_STAGE_VERTEX | PM_STAGE_FRAGMENT);
        m4_read(d, e, res, pm_m4_stages(stages));
        if (d->m4BarrierLog) m4_log_bind(d, "bound texture", i, res, stages);
    }
}

PM_EXPORT void pm_pass_set_bytes(PMDevice *d, const void *data, int length, int index) {
    PM_ENC_SCOPE(PM_ENC_BIND);
    @autoreleasepool {
        if (index < 0 || index >= PM_BUFFER_BINDINGS || length < 0 || length > 4096) {
            set_error(d, @"invalid inline buffer binding"); return;
        }
        uint32_t bit = 1u << index;
        if ((d->inlineValid & bit) && !(d->bindings.vertexBufferSet & bit) && !(d->bindings.fragmentBufferSet & bit)
                && d->inlineLengths[index] == length && (length == 0 || memcmp(d->inlineBytes[index], data, (size_t)length) == 0)) {
            return;
        }
        id<MTLRenderCommandEncoder> e = REN(d);
        PM_ENC_COUNT(PM_ENC_BUFFER_BINDS, d->m4 ? 0 : 1);
        bind_bytes(d, e, PM_STAGE_VERTEX | PM_STAGE_FRAGMENT, data, (NSUInteger)length, (NSUInteger)index);
        if (length > 0) memcpy(d->inlineBytes[index], data, (size_t)length);
        d->inlineLengths[index] = (uint16_t)length;
        d->inlineValid |= bit;
        // Inline bytes replace both buffer bindings. Compare contents so writes through the same address are detected.
        d->bindings.vertexBufferSet &= ~(1u << index);
        d->bindings.fragmentBufferSet &= ~(1u << index);
    }
}

PM_EXPORT void pm_pass_draw(PMDevice *d, int prim, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
    PM_ENC_SCOPE(PM_ENC_DRAW);
    @autoreleasepool {
        if (vertexCount <= 0 || instanceCount <= 0) return;
        PM_IF_M4(d) m4_resolve_bindings(d);
        PM_ENC_COUNT(PM_ENC_DRAWS, 1);
        [REN(d) drawPrimitives:primitive(prim) vertexStart:(NSUInteger)firstVertex vertexCount:(NSUInteger)vertexCount
                 instanceCount:(NSUInteger)instanceCount baseInstance:(NSUInteger)firstInstance];
    }
}

static inline void draw_indexed(PMDevice *d, id<MTLRenderCommandEncoder> e, MTLPrimitiveType p, int count, int instances,
                                uint64_t indexByteOffset, int baseVertex, int baseInstance) {
    if (count <= 0 || instances <= 0) return;
    PM_IF_M4(d) m4_resolve_bindings(d);
    PM_ENC_COUNT(PM_ENC_DRAWS, 1);
    PM_IF_M4(d) {
        [(id<MTL4RenderCommandEncoder>)e drawIndexedPrimitives:p indexCount:(NSUInteger)count indexType:d->indexType
                                                   indexBuffer:d->indexAddress + indexByteOffset
                                             indexBufferLength:d->indexLength - (NSUInteger)indexByteOffset
                                                 instanceCount:(NSUInteger)instances baseVertex:baseVertex
                                                  baseInstance:(NSUInteger)baseInstance];
        return;
    }
    [e drawIndexedPrimitives:p indexCount:(NSUInteger)count indexType:d->indexType
                 indexBuffer:PM_OBJ(id<MTLBuffer>, d->indexBuffer) indexBufferOffset:(NSUInteger)indexByteOffset
               instanceCount:(NSUInteger)instances baseVertex:baseVertex baseInstance:(NSUInteger)baseInstance];
}

static inline uint64_t index_size(PMDevice *d) { return d->indexType == MTLIndexTypeUInt32 ? 4 : 2; }

PM_EXPORT void pm_pass_draw_indexed(PMDevice *d, int prim, int indexCount, int instanceCount, int firstIndex, int vertexOffset,
                                    int firstInstance) {
    PM_ENC_SCOPE(PM_ENC_DRAW);
    @autoreleasepool {
        draw_indexed(d, REN(d), primitive(prim), indexCount, instanceCount, (uint64_t)firstIndex * index_size(d), vertexOffset, firstInstance);
    }
}

// Multi-draws: Metal has no multi-draw call, so each non-empty record is one draw. Each returns the number of draws issued.

// Interleaved {firstIndex, indexCount, vertexOffset} records (VkMultiDrawIndexedInfoEXT layout).
PM_EXPORT int pm_pass_multi_draw_indexed(PMDevice *d, int prim, const int *params, int drawCount, int instanceCount, int firstInstance) {
    PM_ENC_SCOPE(PM_ENC_DRAW);
    @autoreleasepool {
        if (instanceCount <= 0) return 0;
        id<MTLRenderCommandEncoder> e = REN(d);
        MTLPrimitiveType p = primitive(prim);
        uint64_t isz = index_size(d);
        int issued = 0;
        for (int i = 0; i < drawCount; i++) {
            const int *r = params + i * 3;
            if (r[1] <= 0) continue;
            draw_indexed(d, e, p, r[1], instanceCount, (uint64_t)r[0] * isz, r[2], firstInstance);
            issued++;
        }
        return issued;
    }
}

// Separate arrays: byte offsets into the index buffer, index counts, base vertices (GL multiDrawElementsBaseVertex).
PM_EXPORT int pm_pass_multi_draw_indexed_separate(PMDevice *d, int prim, const uint64_t *byteOffsets, const int *counts,
                                                  const int *baseVertices, int drawCount) {
    PM_ENC_SCOPE(PM_ENC_DRAW);
    @autoreleasepool {
        id<MTLRenderCommandEncoder> e = REN(d);
        MTLPrimitiveType p = primitive(prim);
        int issued = 0;
        for (int i = 0; i < drawCount; i++) {
            if (counts[i] <= 0) continue;
            draw_indexed(d, e, p, counts[i], 1, byteOffsets[i], baseVertices[i], 0);
            issued++;
        }
        return issued;
    }
}

// Interleaved {firstVertex, vertexCount} records (VkMultiDrawInfoEXT layout).
PM_EXPORT int pm_pass_multi_draw(PMDevice *d, int prim, const int *params, int drawCount, int instanceCount, int firstInstance) {
    PM_ENC_SCOPE(PM_ENC_DRAW);
    @autoreleasepool {
        if (instanceCount <= 0) return 0;
        id<MTLRenderCommandEncoder> e = REN(d);
        MTLPrimitiveType p = primitive(prim);
        int issued = 0;
        PM_IF_M4(d) m4_resolve_bindings(d);
        for (int i = 0; i < drawCount; i++) {
            const int *r = params + i * 2;
            if (r[1] <= 0) continue;
            [e drawPrimitives:p vertexStart:(NSUInteger)r[0] vertexCount:(NSUInteger)r[1]
                instanceCount:(NSUInteger)instanceCount baseInstance:(NSUInteger)firstInstance];
            issued++;
        }
        PM_ENC_COUNT(PM_ENC_DRAWS, issued);
        return issued;
    }
}

PM_EXPORT int pm_pass_multi_draw_separate(PMDevice *d, int prim, const int *firsts, const int *counts, int drawCount) {
    PM_ENC_SCOPE(PM_ENC_DRAW);
    @autoreleasepool {
        id<MTLRenderCommandEncoder> e = REN(d);
        MTLPrimitiveType p = primitive(prim);
        int issued = 0;
        PM_IF_M4(d) m4_resolve_bindings(d);
        for (int i = 0; i < drawCount; i++) {
            if (counts[i] <= 0) continue;
            [e drawPrimitives:p vertexStart:(NSUInteger)firsts[i] vertexCount:(NSUInteger)counts[i]];
            issued++;
        }
        PM_ENC_COUNT(PM_ENC_DRAWS, issued);
        return issued;
    }
}

// Metal's indirect argument structs share Vulkan's layouts (20 bytes indexed, 16 bytes non-indexed).
PM_EXPORT void pm_pass_draw_indexed_indirect(PMDevice *d, int prim, void *args, uint64_t offset, int drawCount) {
    PM_ENC_SCOPE(PM_ENC_DRAW);
    @autoreleasepool {
        id<MTLRenderCommandEncoder> e = REN(d);
        id<MTLBuffer> b = PM_OBJ(id<MTLBuffer>, args);
        MTLPrimitiveType p = primitive(prim);
        PM_IF_M4(d) m4_resolve_bindings(d);
        PM_ENC_COUNT(PM_ENC_DRAWS, drawCount);
        // The draw arguments are fetched for the draw as a whole, not by a shader stage: keep both stages.
        PM_IF_M4(d) m4_read(d, e, args, MTLStageVertex | MTLStageFragment);
        for (int i = 0; i < drawCount; i++) {
            PM_IF_M4(d) {
                [(id<MTL4RenderCommandEncoder>)e drawIndexedPrimitives:p indexType:d->indexType indexBuffer:d->indexAddress
                                                     indexBufferLength:d->indexLength
                                                        indirectBuffer:b.gpuAddress + offset + (uint64_t)i * 20];
                continue;
            }
            [e drawIndexedPrimitives:p indexType:d->indexType indexBuffer:PM_OBJ(id<MTLBuffer>, d->indexBuffer) indexBufferOffset:0
                      indirectBuffer:b indirectBufferOffset:(NSUInteger)(offset + (uint64_t)i * 20)];
        }
    }
}

PM_EXPORT void pm_pass_draw_indirect(PMDevice *d, int prim, void *args, uint64_t offset, int drawCount) {
    PM_ENC_SCOPE(PM_ENC_DRAW);
    @autoreleasepool {
        id<MTLRenderCommandEncoder> e = REN(d);
        id<MTLBuffer> b = PM_OBJ(id<MTLBuffer>, args);
        MTLPrimitiveType p = primitive(prim);
        PM_IF_M4(d) m4_resolve_bindings(d);
        PM_ENC_COUNT(PM_ENC_DRAWS, drawCount);
        // The draw arguments are fetched for the draw as a whole, not by a shader stage: keep both stages.
        PM_IF_M4(d) m4_read(d, e, args, MTLStageVertex | MTLStageFragment);
        for (int i = 0; i < drawCount; i++) {
            PM_IF_M4(d) {
                [(id<MTL4RenderCommandEncoder>)e drawPrimitives:p indirectBuffer:b.gpuAddress + offset + (uint64_t)i * 16];
                continue;
            }
            [e drawPrimitives:p indirectBuffer:b indirectBufferOffset:(NSUInteger)(offset + (uint64_t)i * 16)];
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Surface (CAMetalLayer from SDL_Metal_GetLayer).

// Frames reach the screen without the render thread ever holding a drawable. The frame's command buffer copies
// the finished image into a staging slot and signals `event`; when the GPU gets there, a listener on `presentQueue`
// (a serial dispatch queue) takes a drawable, draws the newest finished slot into it and presents it from its own
// command queue. Holding a drawable across the frame's GPU work kept all three busy for ~50 ms each and capped the
// frames shown far below the frames rendered; writing it on the frame's queue stalled every later frame.
typedef struct PMSurface {
    void *layer;           // CAMetalLayer, borrowed from SDL's view
    PMDevice *device;
    void *dispatch;        // dispatch_queue_t (serial) the listener runs on, retained
    void *listener;        // MTLSharedEventListener, retained
    void *event;           // id<MTLSharedEvent>, retained
    void *presentQueue;    // id<MTLCommandQueue>, retained
    void *pipeline;        // id<MTLRenderPipelineState>, retained, built for the layer's pixel format
    _Atomic uint64_t targetSize; // packed drawable width/height, read by GPU completion callbacks
    uint64_t eventValue;   // last value signalled (render thread)
    int vsync;             // render thread
    // Layer settings requested by pm_surface_configure, applied on the present queue before the next drawable is taken
    // (the layer must not change while that queue is inside nextDrawable, and the render thread must not wait for it).
    pthread_mutex_t cfgLock;
    int cfgPending, cfgWidth, cfgHeight, cfgDisplaySync;
    void *staging[PM_PRESENT_SLOTS];          // id<MTLTexture>
    // The frame's own render target, retained, when it is presented without a staging copy (see pm_surface_blit).
    _Atomic(void *) direct[PM_PRESENT_SLOTS];
    uint64_t slotValue[PM_PRESENT_SLOTS];     // event value of the frame in each slot
    double slotTime[PM_PRESENT_SLOTS];        // CACurrentMediaTime() when the frame was submitted for presenting
    atomic_int slotBusy[PM_PRESENT_SLOTS];    // set until the slot's frame is presented or skipped
    void *slotsFree;                          // dispatch_semaphore_t counting free slots, retained
    // With vsync every frame is shown in order, so the game must not run ahead by more than this many frames
    // (letting it fill every slot measured ~114 ms of input latency at 60 Hz).
    void *vsyncDepth;                         // dispatch_semaphore_t, retained
    int slotVsync[PM_PRESENT_SLOTS];          // the slot holds a vsync frame (holds a vsyncDepth count)
    atomic_int pending;                       // listener callbacks not yet finished
    atomic_int shutdown;                      // set by pm_surface_destroy: callbacks release their slots without presenting
    // Mailbox pacing (vsync off): at most two presented frames wait to be shown. Until macOS reports one on screen,
    // a newer finished frame replaces the waiting one instead of queueing behind it (that queue added ~75 ms).
    void *shown;              // dispatch_semaphore_t, counts free places (of 2) for presented frames waiting to be shown, retained
    int waitingSlot;          // finished frame waiting for the screen, or -1 (present dispatch queue only)
    double lastPresent;       // CACurrentMediaTime() of the last present (present dispatch queue only)
    atomic_int onScreenPending;  // presented drawables whose presented handler has not run yet
    atomic_int presentsPending;  // present command buffers whose completion handler has not finished
    // Dev stats, read and reset by pm_surface_take_stats: [0] skipped (a newer frame was ready), [1] dropped (no free
    // slot), [2] presented, [3] shown on screen, [4] submit-to-shown sum in us.
    // [5] GPU time of the present command buffers in us, [6] their count (pm_surface_take_present_gpu).
    atomic_uint_fast64_t stats[7];
    atomic_int statsEnabled;
} PMSurface;

static NSString *const PRESENT_SHADER =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct V { float4 pos [[position]]; };\n"
     "vertex V pm_present_vs(uint vid [[vertex_id]]) {\n"
     "  float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "  V o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;\n"
     "}\n"
     "fragment float4 pm_present_fs(V in [[stage_in]], texture2d<float> src [[texture(0)]], constant uint &h [[buffer(0)]]) {\n"
     "  uint2 c = uint2(in.pos.xy);\n"
     "  return float4(src.read(uint2(c.x, h - 1 - c.y)).rgb, 1.0);\n"
     "}\n";

static int ensure_present_pipeline(PMDevice *d, MTLPixelFormat format, char *err, int errCap) {
    if (d->presentPipeline && d->presentFormat == format) return 1;
    NSError *error = nil;
    if (!d->presentLibrary) {
        id<MTLLibrary> lib = pm_new_library(d, PRESENT_SHADER, nil, &error);
        if (!lib) { pm_copy_string(error.localizedDescription, err, errCap); return 0; }
        d->presentLibrary = pm_retain(lib);
    }
    id<MTLLibrary> lib = PM_OBJ(id<MTLLibrary>, d->presentLibrary);
    MTLRenderPipelineDescriptor *p = [MTLRenderPipelineDescriptor new];
    p.label = @"Pure Metal present";
    p.vertexFunction = [lib newFunctionWithName:@"pm_present_vs"];
    p.fragmentFunction = [lib newFunctionWithName:@"pm_present_fs"];
    p.colorAttachments[0].pixelFormat = format;
    id<MTLRenderPipelineState> state = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:p error:&error];
    if (!state) { pm_copy_string(error.localizedDescription, err, errCap); return 0; }
    pm_release(d->presentPipeline);
    d->presentPipeline = pm_retain(state);
    d->presentFormat = format;
    return 1;
}


// Full-screen pass that resamples `src` (bilinear) into the target, with optional contrast-adaptive sharpening:
// each pixel is pushed away from its four neighbours, less where it is already near black or white so edges do not ring.
static NSString *const UPSCALE_SHADER =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct V { float4 pos [[position]]; };\n"
     "vertex V pm_upscale_vs(uint vid [[vertex_id]]) {\n"
     "  float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "  V o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;\n"
     "}\n"
     "fragment float4 pm_upscale_fs(V in [[stage_in]], texture2d<float> src [[texture(0)]], sampler smp [[sampler(0)]],\n"
     "                              constant float4 &p [[buffer(0)]]) {\n"
     "  float2 uv = in.pos.xy * p.xy;\n"
     "  float4 c = src.sample(smp, uv);\n"
     "  if (p.z <= 0.0) return c;\n"
     "  float2 t = 1.0 / float2(src.get_width(), src.get_height());\n"
     "  float3 n = src.sample(smp, uv - float2(0.0, t.y)).rgb, s = src.sample(smp, uv + float2(0.0, t.y)).rgb;\n"
     "  float3 w = src.sample(smp, uv - float2(t.x, 0.0)).rgb, e = src.sample(smp, uv + float2(t.x, 0.0)).rgb;\n"
     "  float3 mn = min(c.rgb, min(min(n, s), min(w, e))), mx = max(c.rgb, max(max(n, s), max(w, e)));\n"
     "  float3 amp = sqrt(saturate(min(mn, 1.0 - mx) / max(mx, 1e-4)));\n"
     "  float3 k = -amp * mix(0.08, 0.2, p.z);\n"
     "  return float4(saturate((c.rgb + (n + s + w + e) * k) / (1.0 + 4.0 * k)), c.a);\n"
     "}\n";

static int ensure_upscale_pipeline(PMDevice *d, MTLPixelFormat format) {
    if (d->upscalePipeline && d->upscaleFormat == format && d->upscaleSampler) return 1;
    NSError *error = nil;
    id<MTLLibrary> lib = pm_new_library(d, UPSCALE_SHADER, nil, &error);
    if (!lib) { set_error(d, error.localizedDescription); return 0; }
    MTLRenderPipelineDescriptor *p = [MTLRenderPipelineDescriptor new];
    p.label = @"Pure Metal upscale";
    p.vertexFunction = [lib newFunctionWithName:@"pm_upscale_vs"];
    p.fragmentFunction = [lib newFunctionWithName:@"pm_upscale_fs"];
    p.colorAttachments[0].pixelFormat = format;
    id<MTLRenderPipelineState> state = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:p error:&error];
    if (!state) { set_error(d, error.localizedDescription); return 0; }
    pm_release(d->upscalePipeline);
    d->upscalePipeline = pm_retain(state);
    d->upscaleFormat = format;
    if (!d->upscaleSampler) {
        MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
        sd.minFilter = MTLSamplerMinMagFilterLinear;
        sd.magFilter = MTLSamplerMinMagFilterLinear;
        sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
        sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
        sd.supportArgumentBuffers = d->m4 != 0;
        id<MTLSamplerState> sampler = [DEV_MTL(d) newSamplerStateWithDescriptor:sd];
        if (!sampler) { set_error(d, @"could not create the upscale sampler"); return 0; }
        d->upscaleSampler = pm_retain(sampler);
    }
    return 1;
}

// The upscale pipeline for a pass that also has a Depth32Float attachment (the pass keeps its depth; this draw ignores it).
static int ensure_upscale_depth_pipeline(PMDevice *d, MTLPixelFormat format) {
    if (!ensure_upscale_pipeline(d, format)) return 0;
    if (d->upscaleDepthPipeline && d->upscaleDepthFormat == format) return 1;
    NSError *error = nil;
    id<MTLLibrary> lib = pm_new_library(d, UPSCALE_SHADER, nil, &error);
    if (!lib) { set_error(d, error.localizedDescription); return 0; }
    MTLRenderPipelineDescriptor *p = [MTLRenderPipelineDescriptor new];
    p.label = @"Pure Metal upscale (depth pass)";
    p.vertexFunction = [lib newFunctionWithName:@"pm_upscale_vs"];
    p.fragmentFunction = [lib newFunctionWithName:@"pm_upscale_fs"];
    p.colorAttachments[0].pixelFormat = format;
    p.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    id<MTLRenderPipelineState> state = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:p error:&error];
    if (!state) { set_error(d, error.localizedDescription); return 0; }
    pm_release(d->upscaleDepthPipeline);
    d->upscaleDepthPipeline = pm_retain(state);
    d->upscaleDepthFormat = format;
    return 1;
}

// Bilinear stretch of `src` over the whole of a `width`x`height` target; the pipeline must already exist.
static void draw_stretch(PMDevice *d, id<MTLRenderCommandEncoder> e, id<MTLTexture> src, int width, int height, bool withDepth) {
    [e setRenderPipelineState:PM_OBJ(id<MTLRenderPipelineState>, withDepth ? d->upscaleDepthPipeline : d->upscalePipeline)];
    [e setViewport:(MTLViewport){ 0.0, 0.0, (double)width, (double)height, 0.0, 1.0 }];
    [e setScissorRect:(MTLScissorRect){ 0, 0, (NSUInteger)width, (NSUInteger)height }];
    [e setCullMode:MTLCullModeNone];
    bind_texture(d, e, PM_STAGE_FRAGMENT, src, 0);
    bind_sampler(d, e, PM_STAGE_FRAGMENT, PM_OBJ(id<MTLSamplerState>, d->upscaleSampler), 0);
    float params[4] = { 1.0f / (float)width, 1.0f / (float)height, 0.0f, 0.0f };
    bind_bytes(d, e, PM_STAGE_FRAGMENT, params, sizeof(params), 0);
    [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
}

// Encodes the pending temporal stretch as a pass of its own (nothing folded it into a pass that loads the target).
static void flush_backdrop(PMDevice *d) {
    if (!d->bgDst) return;
    void *dstHandle = d->bgDst, *srcHandle = d->bgSrc;
    d->bgDst = d->bgSrc = NULL;
    id<MTLTexture> dst = PM_OBJ(id<MTLTexture>, dstHandle);
    if (ensure_upscale_pipeline(d, dst.pixelFormat)) {
        MTLRenderPassDescriptor *rp = new_pass_descriptor(d);
        rp.colorAttachments[0].texture = dst;
        rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> e = new_render_encoder(d, raw_cb(d), rp, "Pure Metal upscale");
        if (e) {
            e.label = @"Pure Metal upscale";
            d->stats[0]++;
            PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
            d->stats[5] += dst.allocatedSize;
            draw_stretch(d, e, PM_OBJ(id<MTLTexture>, srcHandle), (int)dst.width, (int)dst.height, false);
            PM_IF_M4(d) m4_ts_render_close(d, (id<MTL4RenderCommandEncoder>)e);
            [e endEncoding];
        } else {
            set_error(d, @"could not create the upscale encoder");
        }
    }
    pm_release(dstHandle);
    pm_release(srcHandle);
}

// Keep MetalFX-only cleanup separate: bilinear rendering still needs the common upscale pipeline/sampler.
static void reset_metalfx(PMDevice *d) {
    @autoreleasepool {
        pm_release(d->fxScaler);
        pm_release(d->fxOut);
        d->fxScaler = NULL;
        d->fxOut = NULL;
        d->fxAllocFailed = 0;
    }
}

// Drops the temporal scaler and its textures (the compute pipelines are kept for the next use).
static void reset_temporal(PMDevice *d) {
    @autoreleasepool {
        pm_release(d->txScaler);
        pm_release(d->txMotion);
        pm_release(d->txDepth);
        pm_release(d->txOut);
        pm_release(d->txExposure);
        d->txScaler = d->txMotion = d->txDepth = d->txOut = d->txExposure = NULL;
        d->txPrepared = d->txDirect = 0;
        d->txSpan = 0;
    }
}

// Drop all inactive upscale state; pending command buffers retain the resources they encoded.
PM_EXPORT void pm_upscale_reset(PMDevice *d) {
    @autoreleasepool {
        reset_metalfx(d);
        reset_temporal(d);
        d->txAllocFailed = 0;
        if (d->bgDst) current_cb(d); // encodes the pending stretch with the pipeline that is about to go
        pm_release(d->upscalePipeline);
        pm_release(d->upscaleDepthPipeline);
        pm_release(d->upscaleSampler);
        d->upscalePipeline = NULL;
        d->upscaleDepthPipeline = NULL;
        d->upscaleSampler = NULL;
    }
}

// The scalers come from the MTL4Compiler on the Metal 4 path (their encode call takes an MTL4CommandBuffer). The Base protocols carry
// every property both flavors share.
static id<MTLFXSpatialScalerBase> new_spatial_scaler(PMDevice *d, MTLFXSpatialScalerDescriptor *desc) {
    PM_IF_M4(d) return [desc newSpatialScalerWithDevice:DEV_MTL(d) compiler:PM_OBJ(id<MTL4Compiler>, d->mtl4Compiler)];
    return [desc newSpatialScalerWithDevice:DEV_MTL(d)];
}

static id<MTLFXTemporalScalerBase> new_temporal_scaler(PMDevice *d, MTLFXTemporalScalerDescriptor *desc) {
    PM_IF_M4(d) return [desc newTemporalScalerWithDevice:DEV_MTL(d) compiler:PM_OBJ(id<MTL4Compiler>, d->mtl4Compiler)];
    return [desc newTemporalScalerWithDevice:DEV_MTL(d)];
}

// The scaler encodes its own passes, which nothing orders behind the pass that produced its input in Metal 4: a fence encoder does
// (and another one orders what reads its output).
static void encode_scaler(PMDevice *d, id scaler, id<MTLCommandBuffer> cb, bool temporal) {
    PM_IF_M4(d) {
        id<MTL4CommandBuffer> cb4 = (id<MTL4CommandBuffer>)cb;
        m4_fence(d, cb4);
        if (temporal) [(id<MTL4FXTemporalScaler>)scaler encodeToCommandBuffer:cb4];
        else [(id<MTL4FXSpatialScaler>)scaler encodeToCommandBuffer:cb4];
        // What it wrote is not tracked: a second fence holds back everything that reads it, and starts a new epoch.
        if (!d->m4FullBarriers) m4_fence(d, cb4);
        return;
    }
    if (temporal) [(id<MTLFXTemporalScaler>)scaler encodeToCommandBuffer:cb];
    else [(id<MTLFXSpatialScaler>)scaler encodeToCommandBuffer:cb];
}

// MetalFX spatial upscale of `src` into d->fxOut (full size); returns that texture, or nil to fall back to bilinear.
static id<MTLTexture> metalfx_upscale(PMDevice *d, id<MTLCommandBuffer> cb, id<MTLTexture> src, id<MTLTexture> dst) {
    if (d->fxUnsupported) return nil;
    int sameKey = d->fxInW == src.width && d->fxInH == src.height && d->fxOutW == dst.width && d->fxOutH == dst.height
        && d->fxFormat == dst.pixelFormat && d->fxSrcFormat == src.pixelFormat;
    if (!d->fxScaler && d->fxAllocFailed && sameKey) return nil; // allocation failed for these sizes: bilinear until they change
    if (!d->fxScaler || !sameKey) {
        pm_release(d->fxScaler);
        pm_release(d->fxOut);
        d->fxScaler = NULL;
        d->fxOut = NULL;
        d->fxAllocFailed = 0;
        if (![MTLFXSpatialScalerDescriptor supportsDevice:DEV_MTL(d)]) { d->fxUnsupported = 1; return nil; }
        MTLFXSpatialScalerDescriptor *desc = [MTLFXSpatialScalerDescriptor new];
        desc.inputWidth = src.width;
        desc.inputHeight = src.height;
        desc.outputWidth = dst.width;
        desc.outputHeight = dst.height;
        desc.colorTextureFormat = src.pixelFormat;
        desc.outputTextureFormat = dst.pixelFormat;
        desc.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
        id<MTLFXSpatialScalerBase> scaler = new_spatial_scaler(d, desc);
        if (!scaler) { d->fxUnsupported = 1; return nil; }
        if ((src.usage & scaler.colorTextureUsage) != scaler.colorTextureUsage) {
            NSLog(@"Pure Metal: MetalFX needs input usage %lu, the world target has %lu; using bilinear",
                  (unsigned long)scaler.colorTextureUsage, (unsigned long)src.usage);
            d->fxUnsupported = 1;
            return nil;
        }
        // Its own output texture: the usage MetalFX writes with would keep the main target from being compressed.
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:dst.pixelFormat
                                                                                       width:dst.width height:dst.height mipmapped:NO];
        td.storageMode = MTLStorageModePrivate;
        td.usage = scaler.outputTextureUsage | MTLTextureUsageShaderRead;
        id<MTLTexture> out = [DEV_MTL(d) newTextureWithDescriptor:td];
        if (!out) {
            // Out of memory, not missing support: bilinear for these sizes, tried again when they change.
            NSLog(@"Pure Metal: could not allocate the MetalFX output texture; using bilinear");
            d->fxInW = src.width;
            d->fxInH = src.height;
            d->fxOutW = dst.width;
            d->fxOutH = dst.height;
            d->fxFormat = dst.pixelFormat;
            d->fxSrcFormat = src.pixelFormat;
            d->fxAllocFailed = 1;
            return nil;
        }
        out.label = @"Pure Metal MetalFX output";
        d->fxScaler = pm_retain(scaler);
        d->fxOut = pm_retain(out);
        d->fxInW = src.width;
        d->fxInH = src.height;
        d->fxOutW = dst.width;
        d->fxOutH = dst.height;
        d->fxFormat = dst.pixelFormat;
        d->fxSrcFormat = src.pixelFormat;
    }
    id<MTLFXSpatialScalerBase> scaler = PM_OBJ(id<MTLFXSpatialScalerBase>, d->fxScaler);
    id<MTLTexture> out = PM_OBJ(id<MTLTexture>, d->fxOut);
    scaler.colorTexture = src;
    scaler.inputContentWidth = src.width;
    scaler.inputContentHeight = src.height;
    scaler.outputTexture = out;
    encode_scaler(d, scaler, cb, false);
    // Encoding has captured these resources. The cached scaler must not own an old world texture between frames.
    scaler.colorTexture = nil;
    scaler.outputTexture = nil;
    return out;
}

// ---- MetalFX temporal upscaling -------------------------------------------------------------------------------------
// pm_temporal_prepare runs before the hand is drawn: it turns the world's depth into the scaler's motion vectors (camera
// motion only: each pixel is reprojected into the previous frame with the previous view-projection) and a depth copy.
// pm_temporal_upscale runs at the end of the world: it patches the hand's pixels (the hand clears the depth to 0 and
// draws on top, and is fixed on screen, so its motion is zero), then feeds the scaler.

static NSString *const TEMPORAL_SHADER =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct TxParams { float4x4 invCur; float4x4 prevVP; float4 camDelta; };\n"
     "kernel void pm_tx_motion(texture2d<float, access::read> depth [[texture(0)]],\n"
     "                         texture2d<float, access::write> motion [[texture(1)]],\n"
     "                         texture2d<float, access::write> depthOut [[texture(2)]],\n"
     "                         constant TxParams &p [[buffer(0)]], uint2 gid [[thread_position_in_grid]]) {\n"
     "  float2 size = float2(depth.get_width(), depth.get_height());\n"
     "  if (gid.x >= depth.get_width() || gid.y >= depth.get_height()) return;\n"
     "  float d = depth.read(gid).r;\n"
     "  float2 uv = (float2(gid) + 0.5) / size;\n"
     "  float2 ndc = float2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);\n"
     "  float4 rel = p.invCur * float4(ndc, d, 1.0);\n"
     "  float4 prev = float4(0.0);\n"
     "  if (d <= 0.0 || abs(rel.w) < 1e-6) {\n"
     "    // Sky (nothing at infinity to unproject): only the camera's rotation moves it, so reproject the view ray as a direction.\n"
     "    float4 nearPoint = p.invCur * float4(ndc, 1.0, 1.0);\n"
     "    if (abs(nearPoint.w) > 1e-6) prev = p.prevVP * float4(nearPoint.xyz / nearPoint.w, 0.0);\n"
     "  } else {\n"
     "    prev = p.prevVP * float4(rel.xyz / rel.w + p.camDelta.xyz, 1.0);\n"
     "  }\n"
     "  float2 mv = float2(0.0);\n"
     "  if (prev.w > 1e-5) {\n"
     "    float2 n = prev.xy / prev.w;\n"
     "    mv = clamp((float2(n.x * 0.5 + 0.5, 0.5 - n.y * 0.5) - uv) * size, -size, size);\n"
     "  }\n"
     "  motion.write(float4(mv, 0.0, 0.0), gid);\n"
     "  depthOut.write(float4(d, 0.0, 0.0, 0.0), gid);\n"
     "}\n"
     "kernel void pm_tx_hand(texture2d<float, access::read> depth [[texture(0)]],\n"
     "                       texture2d<float, access::write> motion [[texture(1)]],\n"
     "                       texture2d<float, access::write> depthOut [[texture(2)]],\n"
     "                       uint2 gid [[thread_position_in_grid]]) {\n"
     "  if (gid.x >= depth.get_width() || gid.y >= depth.get_height()) return;\n"
     "  float d = depth.read(gid).r;\n"
     "  if (d > 0.0) {\n"
     "    motion.write(float4(0.0), gid);\n"
     "    depthOut.write(float4(d, 0.0, 0.0, 0.0), gid);\n"
     "  }\n"
     "}\n";

PM_EXPORT int pm_temporal_supported(PMDevice *d) {
    @autoreleasepool {
        return !d->txUnsupported && [MTLFXTemporalScalerDescriptor supportsDevice:DEV_MTL(d)] ? 1 : 0;
    }
}

static int ensure_temporal_pipelines(PMDevice *d) {
    if (d->txMotionPipeline && d->txHandPipeline) return 1;
    NSError *error = nil;
    id<MTLLibrary> lib = pm_new_library(d, TEMPORAL_SHADER, nil, &error);
    if (!lib) { NSLog(@"Pure Metal: MetalFX temporal helper shaders failed to compile (%@); using bilinear", error.localizedDescription); return 0; }
    id<MTLComputePipelineState> motion = pm_new_compute_pipeline(d, lib, @"pm_tx_motion", &error);
    id<MTLComputePipelineState> hand = motion ? pm_new_compute_pipeline(d, lib, @"pm_tx_hand", &error) : nil;
    if (!motion || !hand) { NSLog(@"Pure Metal: MetalFX temporal helper pipelines failed to build (%@); using bilinear", error.localizedDescription); return 0; }
    d->txLibrary = pm_retain(lib);
    d->txMotionPipeline = pm_retain(motion);
    d->txHandPipeline = pm_retain(hand);
    return 1;
}

static const char *pf_name(MTLPixelFormat f) {
    switch (f) {
        case MTLPixelFormatBGRA8Unorm: return "BGRA8Unorm";
        case MTLPixelFormatBGRA8Unorm_sRGB: return "BGRA8Unorm_sRGB";
        case MTLPixelFormatRGBA8Unorm: return "RGBA8Unorm";
        case MTLPixelFormatRGBA8Unorm_sRGB: return "RGBA8Unorm_sRGB";
        case MTLPixelFormatRGBA16Float: return "RGBA16Float";
        case MTLPixelFormatRGB10A2Unorm: return "RGB10A2Unorm";
        case MTLPixelFormatRG16Float: return "RG16Float";
        case MTLPixelFormatR32Float: return "R32Float";
        case MTLPixelFormatDepth32Float: return "Depth32Float";
        default: return "other";
    }
}

static id<MTLTexture> temporal_texture(PMDevice *d, MTLPixelFormat format, NSUInteger w, NSUInteger h, MTLTextureUsage required, NSString *label) {
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
    td.storageMode = MTLStorageModePrivate;
    td.usage = required | MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    id<MTLTexture> t = [DEV_MTL(d) newTextureWithDescriptor:td];
    t.label = label;
    return t;
}

// (Re)creates the scaler and its textures for these sizes. Returns 0 (and marks temporal unsupported) when they cannot be had.
static int ensure_temporal(PMDevice *d, id<MTLTexture> src, id<MTLTexture> dst, float outScale) {
    if (d->txScaler && d->txInW == src.width && d->txInH == src.height && d->txDstW == dst.width && d->txDstH == dst.height
            && d->txColorFormat == src.pixelFormat && d->txOutFormat == dst.pixelFormat && d->txOutScale == outScale) return 1;
    if (d->txAllocFailed && d->txFailInW == src.width && d->txFailInH == src.height && d->txFailDstW == dst.width
            && d->txFailDstH == dst.height && d->txFailColorFormat == src.pixelFormat && d->txFailOutFormat == dst.pixelFormat
            && d->txFailOutScale == outScale) return 0;
    reset_temporal(d);
    // A reduced output (outScale < 1) is never smaller than the input; the final pass stretches it to the target.
    NSUInteger outW = dst.width, outH = dst.height;
    if (outScale < 0.999f) {
        outW = MAX((NSUInteger)lroundf((float)dst.width * outScale), src.width);
        outH = MAX((NSUInteger)lroundf((float)dst.height * outScale), src.height);
        if (outW > dst.width) outW = dst.width;
        if (outH > dst.height) outH = dst.height;
    }
    if (![MTLFXTemporalScalerDescriptor supportsDevice:DEV_MTL(d)]) { d->txUnsupported = 1; return 0; }
    if (outW <= src.width || outH <= src.height) return 0; // not an upscale at this size: skipped, not unsupported
    MTLFXTemporalScalerDescriptor *desc = [MTLFXTemporalScalerDescriptor new];
    desc.inputWidth = src.width;
    desc.inputHeight = src.height;
    desc.outputWidth = outW;
    desc.outputHeight = outH;
    desc.colorTextureFormat = src.pixelFormat;
    desc.depthTextureFormat = MTLPixelFormatR32Float;
    desc.motionTextureFormat = MTLPixelFormatRG16Float;
    desc.outputTextureFormat = dst.pixelFormat;
    // The exposure is a constant 1 (the image is already display-referred), so there is nothing to meter.
    desc.autoExposureEnabled = NO;
    id<MTLFXTemporalScalerBase> scaler = new_temporal_scaler(d, desc);
    if (!scaler) { d->txUnsupported = 1; return 0; }
    if ((src.usage & scaler.colorTextureUsage) != scaler.colorTextureUsage) {
        NSLog(@"Pure Metal: MetalFX temporal needs input usage %lu, the world target has %lu; using bilinear",
              (unsigned long)scaler.colorTextureUsage, (unsigned long)src.usage);
        d->txUnsupported = 1;
        return 0;
    }
    id<MTLTexture> depth = temporal_texture(d, MTLPixelFormatR32Float, src.width, src.height, scaler.depthTextureUsage, @"Pure Metal temporal depth");
    id<MTLTexture> motion = temporal_texture(d, MTLPixelFormatRG16Float, src.width, src.height, scaler.motionTextureUsage, @"Pure Metal temporal motion");
    // When the full-size target can take the scaler's output itself, it is written directly (no extra texture, no copy pass).
    int direct = outW == dst.width && outH == dst.height && dst.storageMode == MTLStorageModePrivate && (dst.usage & scaler.outputTextureUsage) == scaler.outputTextureUsage;
    id<MTLTexture> out = direct ? nil : temporal_texture(d, dst.pixelFormat, outW, outH, scaler.outputTextureUsage, @"Pure Metal temporal output");
    MTLTextureDescriptor *ed = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR16Float width:1 height:1 mipmapped:NO];
    ed.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> exposure = [DEV_MTL(d) newTextureWithDescriptor:ed];
    if (!depth || !motion || (!direct && !out) || !exposure) {
        // Out of memory, not missing support: skipped for these sizes, tried again when they change.
        NSLog(@"Pure Metal: could not allocate the MetalFX temporal textures; skipping the temporal upscale");
        d->txFailInW = src.width;
        d->txFailInH = src.height;
        d->txFailDstW = dst.width;
        d->txFailDstH = dst.height;
        d->txFailColorFormat = src.pixelFormat;
        d->txFailOutFormat = dst.pixelFormat;
        d->txFailOutScale = outScale;
        d->txAllocFailed = 1;
        return 0;
    }
    exposure.label = @"Pure Metal temporal exposure";
    uint16_t one = 0x3C00; // half-precision 1.0
    [exposure replaceRegion:MTLRegionMake2D(0, 0, 1, 1) mipmapLevel:0 withBytes:&one bytesPerRow:sizeof(one)];
    d->txScaler = pm_retain(scaler);
    d->txDepth = pm_retain(depth);
    d->txMotion = pm_retain(motion);
    d->txOut = out ? pm_retain(out) : NULL;
    d->txDirect = direct;
    d->txExposure = pm_retain(exposure);
    d->txInW = src.width;
    d->txInH = src.height;
    d->txDstW = dst.width;
    d->txDstH = dst.height;
    d->txOutScale = outScale;
    d->txColorFormat = src.pixelFormat;
    d->txOutFormat = dst.pixelFormat;
    d->txAllocFailed = 0;
    d->txFresh = 1;
    atomic_fetch_add(&d->txDiagStats[0], 1);
    snprintf(d->txInfo, sizeof(d->txInfo),
             "scaler in %lux%lu color=%s depth=%s motion=%s -> out %lux%lu %s (%s, outScale=%.2f); world target %s %lux%lu storage=%lu; "
             "main target %s %lux%lu storage=%lu usage=%lu",
             (unsigned long)src.width, (unsigned long)src.height, pf_name(src.pixelFormat), pf_name(MTLPixelFormatR32Float),
             pf_name(MTLPixelFormatRG16Float), (unsigned long)outW, (unsigned long)outH, pf_name(dst.pixelFormat),
             direct ? "written straight into the main target" : "own texture + copy pass", outScale, pf_name(src.pixelFormat),
             (unsigned long)src.width, (unsigned long)src.height, (unsigned long)src.storageMode, pf_name(dst.pixelFormat),
             (unsigned long)dst.width, (unsigned long)dst.height, (unsigned long)dst.storageMode, (unsigned long)dst.usage);
    d->txInfoPending = 1;
    return 1;
}

// Copies the description of the last scaler creation into `out` once; returns 1 when there was a new one.
PM_EXPORT int pm_temporal_info(PMDevice *d, char *out, int cap) {
    if (!out || cap <= 0 || !d->txInfoPending) return 0;
    d->txInfoPending = 0;
    strncpy(out, d->txInfo, (size_t)cap - 1);
    out[cap - 1] = 0;
    return 1;
}

// A compute encoder for the temporal helper passes with their three texture bindings (and the 36 float parameters, if given).
static id<MTLComputeCommandEncoder> temporal_encoder(PMDevice *d, id<MTLCommandBuffer> cb, id<MTLTexture> t0, id<MTLTexture> t1,
                                                     id<MTLTexture> t2, const float *params) {
    PM_IF_M4(d) {
        id<MTL4ComputeCommandEncoder> e = m4_compute_encoder(d, (id<MTL4CommandBuffer>)cb, true, true, "compute temporal");
        if (!e) return nil;
        if (!d->m4FullBarriers) {
            // t0 is read; t1 and t2 (the scaler's motion and depth) are written by the motion pass and patched by the hand pass.
            m4_use(d, e, tex_key(t0), MTLStageDispatch, true, false, "temporal read");
            m4_use(d, e, tex_key(t1), MTLStageDispatch, true, true, "temporal motion/depth");
            m4_use(d, e, tex_key(t2), MTLStageDispatch, true, true, "temporal motion/depth");
        }
        [e setArgumentTable:PM_OBJ(id<MTL4ArgumentTable>, d->m4Table)];
        m4_table_texture(d, 0, t0);
        m4_table_texture(d, 1, t1);
        m4_table_texture(d, 2, t2);
        if (params) m4_set_bytes(d, 0, params, 36 * sizeof(float));
        atomic_fetch_add(&d->encCounts[4], 1);
        return (id)e;
    }
    id<MTLComputeCommandEncoder> e = [cb computeCommandEncoder];
    if (!e) return nil;
    atomic_fetch_add(&d->encCounts[4], 1);
    [e setTexture:t0 atIndex:0];
    [e setTexture:t1 atIndex:1];
    [e setTexture:t2 atIndex:2];
    if (params) [e setBytes:params length:36 * sizeof(float) atIndex:0];
    return e;
}

static void dispatch_temporal(id<MTLComputeCommandEncoder> e, id<MTLComputePipelineState> pipeline, NSUInteger w, NSUInteger h) {
    [e setComputePipelineState:pipeline];
    [e dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(16, 8, 1)];
}

// Once a second, from a completion handler: logs the hand-pass breakdown (ms per span, command buffers per span) and resets it.
static void diag_report_pieces(PMDevice *d) {
    uint64_t nowMs = (uint64_t)(CACurrentMediaTime() * 1000.0);
    uint64_t was = atomic_load(&d->txPieceReportedMs);
    if (nowMs - was < 1000 || !atomic_compare_exchange_strong(&d->txPieceReportedMs, &was, nowMs)) return;
    uint64_t spans = atomic_exchange(&d->txPieceSpans, 0);
    if (!spans) return;
    NSMutableString *text = [NSMutableString string];
    int count = atomic_load(&d->txPieceCount);
    for (int i = 0; i < count; i++) {
        uint64_t ns = atomic_exchange(&d->txPieces[i].ns, 0), n = atomic_exchange(&d->txPieces[i].count, 0);
        if (!n) continue;
        [text appendFormat:@" [%s %.2f ms x%.1f]", d->txPieces[i].name, ns / 1e6 / (double)spans, (double)n / (double)spans];
    }
    NSLog(@"Pure Metal temporal hand-pass passes (per span, %llu spans):%@", (unsigned long long)spans, text);
}

// Diagnostics only: commits the frame's command buffer so far as one timed stage (a command buffer's GPU time is the only
// per-stage timing Metal offers for work like MetalFX that is encoded without an encoder we own). The next encoder starts a
// new one; queue order keeps the frame's order. Stages: 0 world flush, 1 motion, 2 hand pass, 3 hand patch, 4 scaler.
static void diag_stage(PMDevice *d, int stage, bool last, int piece) {
    end_blit(d);
    commit_uploads(d);
    id<MTLCommandBuffer> cb = current_cb(d);
    if (!cb) return;
    atomic_fetch_add(&d->handlers, 1);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        double start = done.GPUStartTime, end = done.GPUEndTime;
        if (end > start && start > 0) {
            uint64_t ns = (uint64_t)((end - start) * 1e9);
            atomic_fetch_add(&d->txDiagStats[8 + stage], ns);
            if (piece >= 0) {
                atomic_fetch_add(&d->txPieces[piece].ns, ns);
                atomic_fetch_add(&d->txPieces[piece].count, 1);
            }
            if (stage == 0) atomic_store(&d->txChainStart, (uint64_t)(start * 1e9));
            if (stage > 0) atomic_fetch_add(&d->txDiagStats[4], ns);
            if (last) {
                uint64_t first = atomic_load(&d->txChainStart);
                uint64_t endNs = (uint64_t)(end * 1e9);
                if (first && endNs > first) atomic_fetch_add(&d->txDiagStats[7], endNs - first);
                atomic_fetch_add(&d->txDiagStats[5], 1);
                atomic_fetch_add(&d->txPieceSpans, 1);
                diag_report_pieces(d);
            }
        }
        atomic_fetch_sub(&d->handlers, 1);
    }];
    [cb commit];
    PM_ENC_COUNT(PM_ENC_CBS_COMMITTED, 1);
    pm_release(d->cb);
    d->cb = NULL;
    atomic_fetch_add(&d->txDiagStats[3], 1);
}

// Inside the hand-pass span (puremetal.profile): a new render pass closes the command buffer of the previous one, so each
// pass gets its own GPU time, named by label (when the device has labels), size and whether it has a depth attachment.
static void diag_split_pass(PMDevice *d, const char *label, int width, int height, bool depth) {
    char name[64];
    snprintf(name, sizeof(name), "%s %dx%d%s", label && label[0] ? label : "pass", width, height, depth ? "+d" : "");
    if (d->txPiece >= 0) diag_stage(d, 2, false, d->txPiece);
    int count = atomic_load(&d->txPieceCount), index = -1;
    for (int i = 0; i < count; i++) if (strcmp(d->txPieces[i].name, name) == 0) { index = i; break; }
    if (index < 0) {
        if (count < PM_DIAG_PIECES) {
            strncpy(d->txPieces[count].name, name, sizeof(d->txPieces[count].name) - 1);
            atomic_store(&d->txPieceCount, count + 1);
            index = count;
        } else {
            index = PM_DIAG_PIECES - 1;
        }
    }
    d->txPiece = index;
}

// Before the hand: `params` is 38 floats (36 used by the shader), column-major: inverse of the current unjittered view-projection (16), the
// previous frame's view-projection (16), the current minus previous camera position (xyz, w unused), the scaler's output
// size as a fraction of the target (1 = full) and the diagnostics flag. `srcView` is the
// world color and `dstView` the full-size target (their sizes and formats shape the scaler). Returns 1 when motion and
// depth are ready, 0 when temporal is unavailable, -1 on failure (device error set).
PM_EXPORT int pm_temporal_prepare(PMDevice *d, void *depthView, void *srcView, void *dstView, const float *params) {
    PM_ENC_SCOPE(PM_ENC_UPSCALE);
    @autoreleasepool {
        id<MTLTexture> depth = PM_OBJ(id<MTLTexture>, depthView);
        id<MTLTexture> src = PM_OBJ(id<MTLTexture>, srcView);
        id<MTLTexture> dst = PM_OBJ(id<MTLTexture>, dstView);
        d->txPrepared = 0;
        d->txSpan = 0;
        if (d->txUnsupported || depth.pixelFormat != MTLPixelFormatDepth32Float || !(depth.usage & MTLTextureUsageShaderRead)
                || depth.storageMode == MTLStorageModeMemoryless || depth.width != src.width || depth.height != src.height) return 0;
        end_blit(d);
        id<MTLCommandBuffer> cb = current_cb(d);
        if (!cb) return -1;
        if (!ensure_temporal_pipelines(d)) {
            d->txUnsupported = 1;
            return 0;
        }
        float outScale = params[36] > 0.0f ? params[36] : 1.0f;
        if (!ensure_temporal(d, src, dst, outScale)) return 0;
        d->txDiag = params[37] != 0.0f && !d->m4; // the stage timing commits command buffers mid-frame: Metal 3 only
        if (d->txDiag) {
            diag_stage(d, 0, false, -1);
            cb = current_cb(d);
            if (!cb) return -1;
        }
        id<MTLComputeCommandEncoder> e = temporal_encoder(d, cb, depth, PM_OBJ(id<MTLTexture>, d->txMotion), PM_OBJ(id<MTLTexture>, d->txDepth), params);
        if (!e) { NSLog(@"Pure Metal: could not create the temporal motion encoder; using bilinear this frame"); return -1; }
        e.label = @"Pure Metal temporal motion";
        d->stats[0]++;
        PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
        if (d->txDiag) atomic_fetch_add(&d->txDiagStats[2], 1);
        dispatch_temporal(e, PM_OBJ(id<MTLComputePipelineState>, d->txMotionPipeline), depth.width, depth.height);
        [e endEncoding];
        if (d->txDiag) { diag_stage(d, 1, false, -1); d->txSpan = 1; d->txPiece = -1; }
        d->txPrepared = 1;
        return 1;
    }
}

// At the end of the world: scales `srcView` into `dstView` with the motion and depth from pm_temporal_prepare.
// `handDepthView` (may be NULL) is the world depth after the hand was drawn: where it is above 0 the hand drew, which
// is fixed on screen. flags: 1 = discard history (camera cut, resize, first frame). Jitter is in pixels of the world
// image. Returns 1 when scaled with MetalFX, 0 when nothing was done (caller falls back to bilinear), -1 on failure.
PM_EXPORT int pm_temporal_upscale(PMDevice *d, void *srcView, void *handDepthView, void *dstView, float jitterX, float jitterY, int flags) {
    PM_ENC_SCOPE(PM_ENC_UPSCALE);
    @autoreleasepool {
        if (!d->txPrepared || !d->txScaler) return 0;
        d->txPrepared = 0;
        d->txSpan = 0; // the hand-pass breakdown ends here (diagnostics)
        id<MTLTexture> src = PM_OBJ(id<MTLTexture>, srcView);
        id<MTLTexture> dst = PM_OBJ(id<MTLTexture>, dstView);
        if (d->txInW != src.width || d->txInH != src.height || d->txDstW != dst.width || d->txDstH != dst.height) return 0;
        end_blit(d);
        if (d->txDiag) { diag_stage(d, 2, false, d->txPiece); d->txPiece = -1; }
        id<MTLCommandBuffer> cb = current_cb(d);
        if (!cb) return -1;
        id<MTLTexture> handTex = handDepthView ? PM_OBJ(id<MTLTexture>, handDepthView) : nil;
        if (handTex && (handTex.pixelFormat != MTLPixelFormatDepth32Float || !(handTex.usage & MTLTextureUsageShaderRead)
                || handTex.width != src.width || handTex.height != src.height)) handTex = nil; // unreadable: the hand is left unpatched
        if (handTex) {
            id<MTLTexture> hand = handTex;
            id<MTLComputeCommandEncoder> e = temporal_encoder(d, cb, hand, PM_OBJ(id<MTLTexture>, d->txMotion), PM_OBJ(id<MTLTexture>, d->txDepth), NULL);
            if (!e) { NSLog(@"Pure Metal: could not create the temporal hand encoder; using bilinear this frame"); return -1; }
            e.label = @"Pure Metal temporal hand";
            d->stats[0]++;
            PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
            if (d->txDiag) atomic_fetch_add(&d->txDiagStats[2], 1);
            dispatch_temporal(e, PM_OBJ(id<MTLComputePipelineState>, d->txHandPipeline), hand.width, hand.height);
            [e endEncoding];
            if (d->txDiag) {
                diag_stage(d, 3, false, -1);
                cb = current_cb(d);
                if (!cb) return -1;
            }
        }
        id<MTLFXTemporalScalerBase> scaler = PM_OBJ(id<MTLFXTemporalScalerBase>, d->txScaler);
        scaler.colorTexture = src;
        scaler.depthTexture = PM_OBJ(id<MTLTexture>, d->txDepth);
        scaler.motionTexture = PM_OBJ(id<MTLTexture>, d->txMotion);
        scaler.exposureTexture = PM_OBJ(id<MTLTexture>, d->txExposure);
        scaler.outputTexture = d->txDirect ? dst : PM_OBJ(id<MTLTexture>, d->txOut);
        scaler.inputContentWidth = src.width;
        scaler.inputContentHeight = src.height;
        scaler.jitterOffsetX = jitterX;
        scaler.jitterOffsetY = jitterY;
        scaler.motionVectorScaleX = 1.0f;
        scaler.motionVectorScaleY = 1.0f;
        scaler.preExposure = 1.0f;
        scaler.depthReversed = YES;
        scaler.reset = (flags & 1) != 0 || d->txFresh;
        if (d->txDiag && scaler.reset) atomic_fetch_add(&d->txDiagStats[1], 1);
        d->txFresh = 0;
        encode_scaler(d, scaler, cb, true);
        // Encoding has captured these resources; the cached scaler must not own an old world texture between frames.
        scaler.colorTexture = nil;
        scaler.depthTexture = nil;
        scaler.motionTexture = nil;
        scaler.exposureTexture = nil;
        scaler.outputTexture = nil;
        if (d->txDiag) {
            diag_stage(d, 4, true, -1); // the stretch to the full-size target is folded into the HUD pass, so the scaler ends the span
            if (d->txDirect) atomic_fetch_add(&d->txDiagStats[6], 1);
        }
        if (d->txDirect) return 1;
        // The stretch to the full-size target is folded into the next pass that loads it (the HUD), see pm_pass_begin.
        if (!ensure_upscale_pipeline(d, dst.pixelFormat)) return -1;
        if (d->bgDst) current_cb(d); // an earlier stretch nothing consumed: land it first
        d->bgDst = pm_retain(PM_OBJ(id<MTLTexture>, root_texture(dstView)));
        d->bgSrc = pm_retain(PM_OBJ(id<MTLTexture>, d->txOut));
        return 1;
    }
}

// Upscales `src` (the world drawn at render scale) to fill `dst`. `metalfx` asks for MetalFX spatial upscaling (falls
// back to bilinear when unavailable); `sharpness` 0 disables sharpening, up to 1. Returns 1 when MetalFX was used,
// 0 for bilinear, -1 on failure (device error set).
PM_EXPORT int pm_upscale(PMDevice *d, void *srcView, void *dstView, int metalfx, float sharpness) {
    PM_ENC_SCOPE(PM_ENC_UPSCALE);
    @autoreleasepool {
        if (!metalfx) reset_metalfx(d);
        reset_temporal(d);
        end_blit(d);
        id<MTLCommandBuffer> cb = current_cb(d);
        if (!cb) return -1;
        id<MTLTexture> src = PM_OBJ(id<MTLTexture>, srcView);
        id<MTLTexture> dst = PM_OBJ(id<MTLTexture>, dstView);
        id<MTLTexture> fx = metalfx ? metalfx_upscale(d, cb, src, dst) : nil;
        if (!ensure_upscale_pipeline(d, dst.pixelFormat)) return -1;
        MTLRenderPassDescriptor *rp = new_pass_descriptor(d);
        rp.colorAttachments[0].texture = dst;
        rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> e = new_render_encoder(d, cb, rp, "Pure Metal upscale");
        if (!e) { set_error(d, @"could not create the upscale encoder"); return -1; }
        e.label = @"Pure Metal upscale";
        d->stats[0]++;
        PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
        d->stats[5] += dst.allocatedSize;
        [e setViewport:(MTLViewport){ 0.0, 0.0, (double)dst.width, (double)dst.height, 0.0, 1.0 }];
        [e setRenderPipelineState:PM_OBJ(id<MTLRenderPipelineState>, d->upscalePipeline)];
        bind_texture(d, e, PM_STAGE_FRAGMENT, fx ? fx : src, 0);
        bind_sampler(d, e, PM_STAGE_FRAGMENT, PM_OBJ(id<MTLSamplerState>, d->upscaleSampler), 0);
        float params[4] = { 1.0f / (float)dst.width, 1.0f / (float)dst.height, sharpness, 0.0f };
        bind_bytes(d, e, PM_STAGE_FRAGMENT, params, sizeof(params), 0);
        [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        PM_IF_M4(d) m4_ts_render_close(d, (id<MTL4RenderCommandEncoder>)e);
        [e endEncoding];
        return fx ? 1 : 0;
    }
}

PM_EXPORT PMSurface *pm_surface_create(PMDevice *d, void *layer) {
    @autoreleasepool {
        CAMetalLayer *l = PM_OBJ(CAMetalLayer *, layer);
        l.device = DEV_MTL(d);
        l.pixelFormat = MTLPixelFormatBGRA8Unorm;
        l.framebufferOnly = YES;
        l.maximumDrawableCount = 3;
        // Nothing behind the game should ever show through (it showed as a white border).
        l.opaque = YES;
        l.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
        char err[256];
        if (!ensure_present_pipeline(d, l.pixelFormat, err, sizeof(err))) {
            NSLog(@"Pure Metal: present pipeline failed: %s", err);
            return NULL;
        }
        dispatch_queue_t q = dispatch_queue_create("puremetal.present", dispatch_queue_attr_make_with_qos_class(
            DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INTERACTIVE, 0));
        MTLSharedEventListener *listener = q ? [[MTLSharedEventListener alloc] initWithDispatchQueue:q] : nil;
        id<MTLSharedEvent> event = [DEV_MTL(d) newSharedEvent];
        id<MTLCommandQueue> pq = [DEV_MTL(d) newCommandQueue];
        if (!listener || !event || !pq) {
            // Nothing is retained manually yet, so ARC releases whatever was created.
            NSLog(@"Pure Metal: could not create the present queue, event or listener");
            return NULL;
        }
        PMSurface *s = calloc(1, sizeof(PMSurface));
        if (!s) return NULL;
        s->layer = layer;
        s->device = d;
        s->vsync = 1;
        pthread_mutex_init(&s->cfgLock, NULL);
        s->pipeline = pm_retain(PM_OBJ(id<MTLRenderPipelineState>, d->presentPipeline));
        s->dispatch = (__bridge_retained void *)q;
        s->listener = pm_retain(listener);
        s->event = pm_retain(event);
        pq.label = @"Pure Metal present";
        s->presentQueue = pm_retain(pq);
        PM_IF_M4(d) [PM_OBJ(id<MTL4CommandQueue>, d->m4PresentQueue) addResidencySet:l.residencySet]; // keeps the drawables resident
        // Starts at 0 and is raised to the slot count: libdispatch aborts if a semaphore is freed below its start value.
        dispatch_semaphore_t slots = dispatch_semaphore_create(0);
        for (int i = 0; i < PM_PRESENT_SLOTS; i++) dispatch_semaphore_signal(slots);
        s->slotsFree = (__bridge_retained void *)slots;
        dispatch_semaphore_t depth = dispatch_semaphore_create(0);
        for (int i = 0; i < 2; i++) dispatch_semaphore_signal(depth);
        s->vsyncDepth = (__bridge_retained void *)depth;
        // Two presents may wait for the screen at once. With one, a present that missed a refresh made the next one
        // start just after a late refresh and miss too, locking the screen at half the refresh rate under GPU load.
        dispatch_semaphore_t shown = dispatch_semaphore_create(0);
        for (int i = 0; i < 2; i++) dispatch_semaphore_signal(shown);
        s->shown = (__bridge_retained void *)shown;
        s->waitingSlot = -1;
        return s;
    }
}

// Caller owns the slot (busy=1), so the render thread cannot reuse it while its old allocation is released.
static void drop_stale_staging(PMSurface *s, int slot) {
    uint64_t size = atomic_load(&s->targetSize);
    if (!size || !s->staging[slot]) return;
    id<MTLTexture> texture = PM_OBJ(id<MTLTexture>, s->staging[slot]);
    if (texture.width != (NSUInteger)(size >> 32) || texture.height != (NSUInteger)(uint32_t)size) {
        pm_release(s->staging[slot]);
        s->staging[slot] = NULL;
    }
}

// On the present queue, before it takes the next drawable, so the layer never changes while that thread is inside nextDrawable.
static void apply_layer_config(PMSurface *s) {
    pthread_mutex_lock(&s->cfgLock);
    int pending = s->cfgPending;
    int width = s->cfgWidth, height = s->cfgHeight, displaySync = s->cfgDisplaySync;
    s->cfgPending = 0;
    pthread_mutex_unlock(&s->cfgLock);
    if (!pending) return;
    CAMetalLayer *l = PM_OBJ(CAMetalLayer *, s->layer);
    bool timing = atomic_load_explicit(&g_traceOn, memory_order_relaxed);
    uint64_t start = timing ? pm_now_ns() : 0;
    l.drawableSize = CGSizeMake(width, height);
    l.displaySyncEnabled = displaySync ? YES : NO;
    if (timing) pm_atomic_max(&s->device->layerConfigureMaxNs, pm_now_ns() - start);
}

PM_EXPORT void pm_surface_configure(PMSurface *s, int width, int height, int vsync, int allowTearing) {
    // Only records the layer settings: the present queue can be blocked in a semaphore wait or nextDrawable, and the
    // render thread (the caller) must not wait for it. The present queue applies them before it next takes a drawable.
    s->vsync = vsync;
    atomic_store(&s->targetSize, ((uint64_t)(uint32_t)width << 32) | (uint32_t)height);
    pthread_mutex_lock(&s->cfgLock);
    s->cfgWidth = width;
    s->cfgHeight = height;
    // Display sync stays on even with vsync off: frames that miss a refresh are skipped (like Vulkan's
    // MAILBOX) instead of tearing. Vsync only decides whether the render thread waits for a free slot.
    s->cfgDisplaySync = (vsync || !allowTearing) ? 1 : 0;
    s->cfgPending = 1;
    pthread_mutex_unlock(&s->cfgLock);
    // A free slot's stale staging texture is dropped now; busy ones are dropped when they are released.
    for (int slot = 0; slot < PM_PRESENT_SLOTS; slot++) {
        int expected = 0;
        if (atomic_compare_exchange_strong(&s->slotBusy[slot], &expected, 1)) {
            drop_stale_staging(s, slot);
            atomic_store(&s->slotBusy[slot], 0);
        }
    }
}

static void release_slot(PMSurface *s, int slot) {
    // Busy slots are reclaimed after their present/copy completes, not while a queued frame still needs them.
    drop_stale_staging(s, slot);
    void *direct = atomic_exchange(&s->direct[slot], NULL);
    if (direct) pm_release(direct);
    if (s->slotVsync[slot]) {
        s->slotVsync[slot] = 0;
        dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->vsyncDepth);
    }
    atomic_store(&s->slotBusy[slot], 0);
    dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->slotsFree);
}

static void draw_and_present(PMSurface *s, int slot, int holdsShown);
static void drop_waiting(PMSurface *s);

// Runs on s->dispatch after the frame presented last is on screen (or was dropped by the compositor); `shown` has
// already been signalled directly by the presented handler, since with vsync this queue may be blocked waiting on it.
static void frame_shown(PMSurface *s) {
    if (atomic_load(&s->shutdown)) return;
    if (s->waitingSlot >= 0 && PM_OBJ(id<MTLSharedEvent>, s->event).signaledValue > s->slotValue[s->waitingSlot]) {
        // A newer frame is already finished; its own callback (queued behind this one) presents it instead.
        drop_waiting(s);
        return;
    }
    if (s->waitingSlot >= 0 && dispatch_semaphore_wait((__bridge dispatch_semaphore_t)s->shown, DISPATCH_TIME_NOW) == 0) {
        int slot = s->waitingSlot;
        s->waitingSlot = -1;
        draw_and_present(s, slot, 1);
    }
}

// Runs on s->dispatch once the GPU has finished the frame in `slot`.
// The frame waiting for the screen is always older than the one being handled, so it is dropped whenever a newer
// frame presents directly; otherwise it would be shown after it, jumping back in time.
static void drop_waiting(PMSurface *s) {
    if (s->waitingSlot >= 0) {
        atomic_fetch_add(&s->stats[0], 1);
        release_slot(s, s->waitingSlot);
        s->waitingSlot = -1;
    }
}

static void present_slot(PMSurface *s, int slot, uint64_t value) {
    dispatch_semaphore_t shown = (__bridge dispatch_semaphore_t)s->shown;
    if (atomic_load(&s->shutdown)) {
        release_slot(s, slot);
        return;
    }
    // The mode the frame was queued with (the render thread may have changed s->vsync since).
    if (s->slotVsync[slot]) {
        drop_waiting(s);
        // FIFO: wait for the screen (bounded, in case the window is hidden and nothing is ever shown).
        draw_and_present(s, slot, dispatch_semaphore_wait(shown, dispatch_time(DISPATCH_TIME_NOW, 100000000LL)) == 0);
        return;
    }
    if (PM_OBJ(id<MTLSharedEvent>, s->event).signaledValue > value) {
        // A newer frame is already finished; its own callback (queued behind this one) handles it.
        atomic_fetch_add(&s->stats[0], 1);
        release_slot(s, slot);
        return;
    }
    int holdsShown = dispatch_semaphore_wait(shown, DISPATCH_TIME_NOW) == 0;
    drop_waiting(s);
    if (!holdsShown && CACurrentMediaTime() - s->lastPresent < 0.1) {
        // The previous frame is not on screen yet: this one waits (replacing any older waiting frame, above).
        s->waitingSlot = slot;
        return;
    }
    draw_and_present(s, slot, holdsShown);
}

// The present pass runs on the present thread, so on Metal 4 it has its own queue, argument table and per-slot allocator
// (reset here: the slot is free again once its previous present completed).
static id<MTLCommandBuffer> present_cb(PMSurface *s, int slot) {
    PMDevice *d = s->device;
    PM_IF_M4(d) {
        id<MTL4CommandAllocator> alloc = PM_OBJ(id<MTL4CommandAllocator>, d->m4PresentAlloc[slot]);
        id<MTL4CommandBuffer> cb = PM_OBJ(id<MTL4CommandBuffer>, d->m4PresentCb[slot]);
        [alloc reset];
        [cb beginCommandBufferWithAllocator:alloc];
        return (id)cb;
    }
    return [PM_OBJ(id<MTLCommandQueue>, s->presentQueue) commandBuffer];
}

static id<MTLRenderCommandEncoder> present_encoder(PMSurface *s, id<MTLCommandBuffer> pcb, MTLRenderPassDescriptor *rp) {
    PM_IF_M4(s->device) return m4_render_encoder(s->device, (id<MTL4CommandBuffer>)pcb, (MTL4RenderPassDescriptor *)rp, s->device->m4PresentTable, false, false, "Pure Metal present");
    return [pcb renderCommandEncoderWithDescriptor:rp];
}

static void present_bind(PMSurface *s, int slot, id<MTLRenderCommandEncoder> e, id<MTLTexture> src, uint32_t height) {
    PMDevice *d = s->device;
    PM_IF_M4(d) {
        id<MTL4ArgumentTable> table = PM_OBJ(id<MTL4ArgumentTable>, d->m4PresentTable);
        id<MTLBuffer> constant = PM_OBJ(id<MTLBuffer>, d->m4PresentConst[slot]);
        memcpy(constant.contents, &height, sizeof(height));
        [table setTexture:src.gpuResourceID atIndex:0];
        [table setAddress:constant.gpuAddress atIndex:0];
        return;
    }
    [e setFragmentTexture:src atIndex:0];
    [e setFragmentBytes:&height length:sizeof(height) atIndex:0];
}

// Presents `drawable` after `pcb`; finished(start, end, failure) runs when the GPU is done with it. Metal 4 orders the drawable with
// queue waits and signals around the commit instead of a present call on the command buffer.
static void present_commit(PMSurface *s, id<MTLCommandBuffer> pcb, id<CAMetalDrawable> drawable,
                           void (^finished)(CFTimeInterval, CFTimeInterval, NSString *)) {
    PM_IF_M4(s->device) {
        id<MTL4CommandQueue> queue = PM_OBJ(id<MTL4CommandQueue>, s->device->m4PresentQueue);
        id<MTL4CommandBuffer> cb = (id<MTL4CommandBuffer>)pcb;
        [cb endCommandBuffer];
        [queue waitForDrawable:drawable];
        MTL4CommitOptions *options = [MTL4CommitOptions new];
        [options addFeedbackHandler:^(id<MTL4CommitFeedback> feedback) {
            @autoreleasepool {
                (void)drawable; // kept alive until the GPU is done with its texture
                finished(feedback.GPUStartTime, feedback.GPUEndTime, feedback.error ? feedback.error.localizedDescription : nil);
            }
        }];
        id<MTL4CommandBuffer> list[1] = { cb };
        [queue commit:list count:1 options:options];
        [queue signalDrawable:drawable];
        [drawable present];
        return;
    }
    [pcb presentDrawable:drawable];
    [pcb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        @autoreleasepool {
            finished(done.GPUStartTime, done.GPUEndTime,
                     done.status == MTLCommandBufferStatusError ? (done.error ? done.error.localizedDescription : @"unknown GPU error") : nil);
        }
    }];
    [pcb commit];
}

// `holdsShown`: this present took the `shown` count, so its presented handler gives it back. A present forced by a
// timeout did not, and must not add a count (that would let two frames wait for the screen from then on).
static void draw_and_present(PMSurface *s, int slot, int holdsShown) {
    PM_ENC_SCOPE(PM_ENC_PRESENT_THREAD);
    @autoreleasepool {
        CAMetalLayer *l = PM_OBJ(CAMetalLayer *, s->layer);
        trace_ev(7, slot, 0, 0);
        if (!atomic_load(&s->shutdown)) apply_layer_config(s);
        bool timing = atomic_load_explicit(&g_traceOn, memory_order_relaxed);
        uint64_t drawableStart = timing ? pm_now_ns() : 0;
        id<CAMetalDrawable> drawable = atomic_load(&s->shutdown) ? nil : [l nextDrawable];
        if (timing) pm_atomic_max(&s->device->drawableWaitMaxNs, pm_now_ns() - drawableStart);
        trace_ev(8, slot, 0, 0);
        if (!drawable) {
            release_slot(s, slot);
            if (holdsShown) dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->shown);
            return;
        }
        id<MTLTexture> dst = drawable.texture;
        void *directTexture = atomic_load(&s->direct[slot]);
        id<MTLTexture> src = PM_OBJ(id<MTLTexture>, directTexture ? directTexture : s->staging[slot]);
        NSUInteger w = MIN(src.width, dst.width), h = MIN(src.height, dst.height);
        id<MTLCommandBuffer> pcb = present_cb(s, slot);
        PM_ENC_COUNT(PM_ENC_CBS_CREATED, 1);
        if (!pcb) {
            release_slot(s, slot);
            if (holdsShown) dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->shown);
            set_error(s->device, @"could not create the present command buffer");
            return;
        }
        MTLRenderPassDescriptor *rp = new_pass_descriptor(s->device);
        rp.colorAttachments[0].texture = dst;
        // The draw covers the whole drawable unless the sizes differ (mid-resize); only then must the rest be cleared.
        rp.colorAttachments[0].loadAction = (w == dst.width && h == dst.height) ? MTLLoadActionDontCare : MTLLoadActionClear;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
        id<MTLRenderCommandEncoder> e = present_encoder(s, pcb, rp);
        PM_ENC_COUNT(PM_ENC_ENCODERS, 1);
        if (e) atomic_fetch_add(&s->device->encCounts[5], 1);
        if (!e) {
            release_slot(s, slot);
            if (holdsShown) dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->shown);
            set_error(s->device, @"could not create the present encoder");
            return;
        }
        e.label = @"Pure Metal present";
        [e setViewport:(MTLViewport){ 0.0, 0.0, (double)w, (double)h, 0.0, 1.0 }];
        [e setRenderPipelineState:PM_OBJ(id<MTLRenderPipelineState>, s->pipeline)];
        uint32_t height = (uint32_t)h;
        present_bind(s, slot, e, src, height);
        [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [e endEncoding];
        double submitted = s->slotTime[slot];
        atomic_fetch_add(&s->onScreenPending, 1);
        [drawable addPresentedHandler:^(id<MTLDrawable> done) {
            @autoreleasepool {
                trace_ev(9, (int64_t)pm_sec_ns(done.presentedTime), 0, 0);
                if (done.presentedTime > 0 && atomic_load(&s->statsEnabled)) {
                    atomic_fetch_add(&s->stats[3], 1);
                    atomic_fetch_add(&s->stats[4], (uint64_t)((done.presentedTime - submitted) * 1e6));
                }
                if (holdsShown) dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->shown);
                dispatch_async((__bridge dispatch_queue_t)s->dispatch, ^{
                    frame_shown(s);
                    atomic_fetch_sub(&s->onScreenPending, 1);
                });
            }
        }];
        atomic_fetch_add(&s->stats[2], 1);
        s->lastPresent = CACurrentMediaTime();
        atomic_fetch_add(&s->presentsPending, 1);
        atomic_fetch_add(&s->device->handlers, 1);
        present_commit(s, pcb, drawable, ^(CFTimeInterval start, CFTimeInterval end, NSString *failure) {
            // Read before s can be freed: the surface's destroy waits on presentsPending, which drops below.
            PMDevice *dev = s->device;
            trace_ev(6, slot, (int64_t)pm_sec_ns(start), (int64_t)pm_sec_ns(end));
            if (failure) set_error(dev, failure);
            if (!failure && atomic_load(&s->statsEnabled)) {
                double gpu = end - start;
                if (gpu > 0) {
                    atomic_fetch_add(&s->stats[5], (uint64_t)(gpu * 1e6));
                    atomic_fetch_add(&s->stats[6], 1);
                }
            }
            release_slot(s, slot);
            atomic_fetch_sub(&s->presentsPending, 1);
            atomic_fetch_sub(&dev->handlers, 1);
        });
        PM_ENC_COUNT(PM_ENC_CBS_COMMITTED, 1);
    }
}

// Signals `event` once the frame's work so far is done: a command in the buffer on Metal 3; on Metal 4 a queue operation, so it is
// held until pm_submit has committed the frame's buffer.
static void signal_event(PMDevice *d, id<MTLCommandBuffer> cb, id<MTLSharedEvent> event, uint64_t value) {
    PM_IF_M4(d) {
        if (d->m4SignalCount == PM_M4_SIGNALS) { set_error(d, @"too many present signals in one frame"); return; }
        d->m4Signals[d->m4SignalCount].event = (__bridge void *)event;
        d->m4Signals[d->m4SignalCount++].value = value;
        return;
    }
    [cb encodeSignalEvent:event value:value];
}

// One surface semaphore wait, with contention detected explicitly: a non-blocking attempt first, and only when that fails the wait
// is counted as blocked (pm_take_stat 36) and the blocked time is timed (pm_take_stat 35). An uncontended slot costs a single
// attempt and reports nothing. `blockNs` 0 means "do not wait"; returns 0 when the semaphore was acquired.
static int surface_wait_stat(PMDevice *d, void *sem, uint64_t blockNs) {
    dispatch_semaphore_t s = (__bridge dispatch_semaphore_t)sem;
    if (dispatch_semaphore_wait(s, DISPATCH_TIME_NOW) == 0) return 0;
    if (blockNs == 0) return 1;
    uint64_t start = pm_now_ns();
    atomic_fetch_add(&d->surfaceWaitCount, 1);
    int rc = dispatch_semaphore_wait(s, dispatch_time(DISPATCH_TIME_NOW, (int64_t)blockNs));
    atomic_fetch_add(&d->surfaceWaitNs, pm_now_ns() - start);
    return rc == 0 ? 0 : 1;
}

// Copies `texture` into a free staging slot on the frame's command buffer and queues it for presenting (flipped
// vertically like VulkanGpuSurface). With vsync on, waits for a free slot, which paces the game to the display;
// without it, a frame that finds no free slot is simply not shown. Returns 0 without an error when not presented.
PM_EXPORT int pm_surface_blit(PMDevice *d, PMSurface *s, void *texture, char *err, int errCap, int allowDirect) {
    @autoreleasepool {
        id<MTLTexture> src = PM_OBJ(id<MTLTexture>, texture);
        int vsync = s->vsync;
        // The two waits below pace the render thread to the display; the bench reports their time and how often they blocked.
        if (vsync) {
            if (surface_wait_stat(d, s->vsyncDepth, 1000000000LL) != 0) {
                atomic_fetch_add(&s->stats[1], 1);
                return 0;
            }
        }
        if (surface_wait_stat(d, s->slotsFree, vsync ? 1000000000LL : 0) != 0) {
            if (vsync) dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->vsyncDepth);
            atomic_fetch_add(&s->stats[1], 1);
            return 0;
        }
        PM_ENC_SCOPE(PM_ENC_PRESENT); // after the semaphore waits above: those block, they are not encode work
        int slot = -1;
        for (int i = 0; i < PM_PRESENT_SLOTS; i++) {
            int expected = 0;
            if (atomic_compare_exchange_strong(&s->slotBusy[i], &expected, 1)) { slot = i; break; }
        }
        if (slot < 0) {
            dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->slotsFree);
            if (vsync) dispatch_semaphore_signal((__bridge dispatch_semaphore_t)s->vsyncDepth);
            return 0;
        }
        s->slotVsync[slot] = vsync;
        // Zero-copy: the present pass samples the frame's own render target. The caller must not write that texture
        // again until pm_surface_texture_busy says the slot is done with it (it rotates to a spare texture instead).
        if (allowDirect && (src.usage & MTLTextureUsageShaderRead) && src.textureType == MTLTextureType2D
            && src.mipmapLevelCount == 1 && src.sampleCount == 1) {
            if (d->parked && d->parkedColors[0] == texture) end_parked_store(d, MTLStoreActionDontCare);
            end_blit(d);
            id<MTLCommandBuffer> cb = current_cb(d);
            if (!cb) {
                release_slot(s, slot);
                pm_copy_string(@"could not create the present command buffer", err, errCap);
                return 0;
            }
            atomic_store(&s->direct[slot], pm_retain(src));
            uint64_t value = ++s->eventValue;
            s->slotValue[slot] = value;
            s->slotTime[slot] = CACurrentMediaTime();
            id<MTLSharedEvent> event = PM_OBJ(id<MTLSharedEvent>, s->event);
            signal_event(d, cb, event, value);
            atomic_fetch_add(&s->pending, 1);
            [event notifyListener:PM_OBJ(MTLSharedEventListener *, s->listener) atValue:value block:^(id<MTLSharedEvent> ev, uint64_t v) {
                present_slot(s, slot, value);
                atomic_fetch_sub(&s->pending, 1);
            }];
            return 2;
        }
        id<MTLTexture> staging = PM_OBJ(id<MTLTexture>, s->staging[slot]);
        if (!staging || staging.width != src.width || staging.height != src.height || staging.pixelFormat != src.pixelFormat) {
            MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:src.pixelFormat
                                                                                            width:src.width height:src.height mipmapped:NO];
            desc.storageMode = MTLStorageModePrivate;
            desc.usage = MTLTextureUsageShaderRead;
            staging = [DEV_MTL(d) newTextureWithDescriptor:desc];
            if (!staging) {
                release_slot(s, slot);
                pm_copy_string(@"could not create the present staging texture", err, errCap);
                return 0;
            }
            staging.label = @"Pure Metal present staging";
            pm_release(s->staging[slot]);
            s->staging[slot] = pm_retain(staging);
        }
        // The pass drawing the presented image is the frame's last use of its depth: the next frame clears it
        // before drawing, so it is never written back to memory.
        if (d->parked && d->parkedColors[0] == texture) end_parked_store(d, MTLStoreActionDontCare);
        id<MTLBlitCommandEncoder> blit = blit_encoder(d, 4);
        if (!blit) {
            release_slot(s, slot);
            pm_copy_string(@"could not create the present blit encoder", err, errCap);
            return 0;
        }
        PM_IF_M4(d) m4_blit_deps(d, &d->blitHaz, (id<MTL4ComputeCommandEncoder>)blit, tex_key_ptr(texture), tex_key_ptr(s->staging[slot]), true);
        [blit copyFromTexture:src sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                   sourceSize:MTLSizeMake(src.width, src.height, 1) toTexture:staging destinationSlice:0
             destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
        end_blit(d);
        uint64_t value = ++s->eventValue;
        s->slotValue[slot] = value;
        s->slotTime[slot] = CACurrentMediaTime();
        id<MTLSharedEvent> event = PM_OBJ(id<MTLSharedEvent>, s->event);
        signal_event(d, current_cb(d), event, value);
        atomic_fetch_add(&s->pending, 1);
        [event notifyListener:PM_OBJ(MTLSharedEventListener *, s->listener) atValue:value block:^(id<MTLSharedEvent> ev, uint64_t v) {
            present_slot(s, slot, value);
            atomic_fetch_sub(&s->pending, 1);
        }];
        return 1;
    }
}

// Copies and resets the five counters described on PMSurface.stats.
PM_EXPORT void pm_surface_take_stats(PMSurface *s, uint64_t *out) {
    atomic_store(&s->statsEnabled, 1);
    for (int i = 0; i < 5; i++) out[i] = atomic_exchange(&s->stats[i], 0);
}

// True while a present slot still references this texture (presented without a copy and not yet done with it).
PM_EXPORT int pm_surface_texture_busy(PMSurface *s, void *texture) {
    for (int i = 0; i < PM_PRESENT_SLOTS; i++) {
        if (atomic_load(&s->slotBusy[i]) && atomic_load(&s->direct[i]) == texture) return 1;
    }
    return 0;
}

// Present command buffer GPU time since the last call: out[0] microseconds, out[1] count.
PM_EXPORT void pm_surface_take_present_gpu(PMSurface *s, uint64_t *out) {
    atomic_store(&s->statsEnabled, 1);
    out[0] = atomic_exchange(&s->stats[5], 0);
    out[1] = atomic_exchange(&s->stats[6], 0);
}

PM_EXPORT void pm_surface_destroy(PMSurface *s) {
    // From here on callbacks release their slots without presenting, so no new listener or presented handler work starts.
    atomic_store(&s->shutdown, 1);
    // Wait for callbacks already running or queued. Frames never submitted never fire, so after a generous wait
    // leak the struct rather than free memory those callbacks may still touch.
    int drained = 0;
    for (int i = 0; i < 5000; i++) {
        if (atomic_load(&s->pending) == 0 && atomic_load(&s->onScreenPending) == 0 && atomic_load(&s->presentsPending) == 0) {
            drained = 1;
            break;
        }
        usleep(1000);
    }
    if (!drained) {
        NSLog(@"Pure Metal: surface frames did not drain; leaking the surface state");
        return;
    }
    @autoreleasepool {
        dispatch_sync((__bridge dispatch_queue_t)s->dispatch, ^{
            if (s->waitingSlot >= 0) { release_slot(s, s->waitingSlot); s->waitingSlot = -1; }
        });
        id<MTLCommandBuffer> last = [PM_OBJ(id<MTLCommandQueue>, s->presentQueue) commandBuffer];
        [last commit];
        [last waitUntilCompleted];
        dispatch_sync((__bridge dispatch_queue_t)s->dispatch, ^{});
        PM_IF_M4(s->device) [PM_OBJ(id<MTL4CommandQueue>, s->device->m4PresentQueue) removeResidencySet:PM_OBJ(CAMetalLayer *, s->layer).residencySet];
    }
    for (int i = 0; i < PM_PRESENT_SLOTS; i++) {
        pm_release(s->staging[i]);
        void *direct = atomic_exchange(&s->direct[i], NULL);
        if (direct) pm_release(direct);
    }
    pm_release(s->pipeline);
    pm_release(s->presentQueue);
    pm_release(s->event);
    pm_release(s->listener);
    CFRelease(s->dispatch);
    CFRelease(s->slotsFree);
    CFRelease(s->shown);
    CFRelease(s->vsyncDepth);
    pthread_mutex_destroy(&s->cfgLock);
    free(s);
}
