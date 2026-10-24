// Offscreen submission experiment. No game, window, display settings or presenter changes.
// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework MetalFX -framework QuartzCore
//   -framework Foundation -framework AppKit tools/bench_terrain_submission.m -o /tmp/pm-terrain-bench
// /tmp/pm-terrain-bench (requires GPU access; run without Metal API validation for timings)
// This deliberately tiny shader measures submission overhead, not Minecraft FPS or terrain GPU cost.
#include "../src/main/native/puremetal.m"
#include <assert.h>
#include <stdio.h>

static double seconds(void) { return (double)clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1e9; }

int main(void) {
    @autoreleasepool {
        enum { draws = 1000, samples = 24 };
        PMDevice *d = pm_device_create(0);
        if (!d) { fputs("No Metal device\n", stderr); return 2; }
        NSError *error = nil;
        NSString *source = @"#include <metal_stdlib>\nusing namespace metal;\n"
            "vertex float4 v(uint i [[vertex_id]]) { float2 p[3] = {float2(-1,-1),float2(3,-1),float2(-1,3)}; return float4(p[i%3]*0.1,0,1); }\n"
            "fragment float4 f() { return float4(1,0,0,1); }\n";
        id<MTLLibrary> lib = [DEV_MTL(d) newLibraryWithSource:source options:nil error:&error];
        assert(lib && !error);
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.vertexFunction = [lib newFunctionWithName:@"v"];
        pd.fragmentFunction = [lib newFunctionWithName:@"f"];
        pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        pd.supportIndirectCommandBuffers = YES;
        id<MTLRenderPipelineState> pipeline = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error];
        assert(pipeline && !error);
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:64 height:64 mipmapped:NO];
        td.usage = MTLTextureUsageRenderTarget;
        td.storageMode = MTLStorageModePrivate;
        id<MTLTexture> target = [DEV_MTL(d) newTextureWithDescriptor:td];
        uint16_t indices[] = {0,1,2};
        id<MTLBuffer> indexBuffer = [DEV_MTL(d) newBufferWithBytes:indices length:sizeof(indices) options:MTLResourceStorageModeShared];
        MTLIndirectCommandBufferDescriptor *desc = [MTLIndirectCommandBufferDescriptor new];
        desc.commandTypes = MTLIndirectCommandTypeDrawIndexed;
        desc.inheritPipelineState = YES;
        desc.inheritBuffers = YES;
        desc.maxVertexBufferBindCount = 0;
        desc.maxFragmentBufferBindCount = 0;
        id<MTLIndirectCommandBuffer> icb = [DEV_MTL(d) newIndirectCommandBufferWithDescriptor:desc maxCommandCount:draws options:MTLResourceStorageModeShared];
        assert(target && indexBuffer && icb);
        uint64_t offsets[draws]; int counts[draws], bases[draws];
        for (int i=0; i<draws; i++) { offsets[i]=0; counts[i]=3; bases[i]=i*3; }
        id<MTLBuffer> readback = [DEV_MTL(d) newBufferWithLength:64*64*4 options:MTLResourceStorageModeShared];
        assert(readback);
        uint8_t reference[64*64*4];
        double cpu[3]={0}, gpu[3]={0};
        // Interleaved, reversing mode order each round; first four rounds warm up.
        // Modes: current native loop; freshly CPU-encoded ICB; reused ICB upper bound.
        for (int round=0; round<samples+4; round++) {
            for (int order=0; order<3; order++) {
                @autoreleasepool {
                    int mode = round%2 ? 2-order : order;
                    id<MTLCommandBuffer> cb = [PM_OBJ(id<MTLCommandQueue>, d->queue) commandBuffer];
                    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
                    rp.colorAttachments[0].texture = target;
                    rp.colorAttachments[0].loadAction = MTLLoadActionClear;
                    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
                    id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
                    [e setRenderPipelineState:pipeline];
                    double start = seconds();
                    if (mode == 0) {
                        d->render = (__bridge void *)e;
                        pm_pass_set_index_buffer(d, (__bridge void *)indexBuffer, 0);
                        assert(pm_pass_multi_draw_indexed_separate(d, 0, offsets, counts, bases, draws)==draws);
                        d->render = NULL;
                    } else {
                        if (mode == 1 || round == 0) {
                            [icb resetWithRange:NSMakeRange(0, draws)];
                            for (int i=0; i<draws; i++) {
                                [[icb indirectRenderCommandAtIndex:i] drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:3 indexType:MTLIndexTypeUInt16 indexBuffer:indexBuffer indexBufferOffset:0 instanceCount:1 baseVertex:bases[i] baseInstance:0];
                            }
                        }
                        [e useResource:indexBuffer usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
                        [e executeCommandsInBuffer:icb withRange:NSMakeRange(0, draws)];
                    }
                    double elapsed = seconds()-start;
                    [e endEncoding];
                    [cb commit];
                    [cb waitUntilCompleted];
                    assert(cb.status == MTLCommandBufferStatusCompleted);
                    if (round>=4) { cpu[mode]+=elapsed; gpu[mode]+=cb.GPUEndTime-cb.GPUStartTime; }
                    if (round == samples+3) {
                        // Separate CB keeps verification out of the measured GPU duration.
                        id<MTLCommandBuffer> copy = [PM_OBJ(id<MTLCommandQueue>, d->queue) commandBuffer];
                        id<MTLBlitCommandEncoder> blit = [copy blitCommandEncoder];
                        [blit copyFromTexture:target sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(64,64,1) toBuffer:readback destinationOffset:0 destinationBytesPerRow:256 destinationBytesPerImage:64*256];
                        [blit endEncoding]; [copy commit]; [copy waitUntilCompleted];
                        assert(copy.status == MTLCommandBufferStatusCompleted);
                        if (order == 0) {
                            memcpy(reference, readback.contents, sizeof(reference));
                            int redPixels = 0;
                            for (int i=0;i<64*64;i++) if (reference[i*4]==255) redPixels++;
                            assert(redPixels>0 && redPixels<64*64);
                        } else assert(memcmp(reference, readback.contents, sizeof(reference))==0);
                    }
                }
            }
        }
        printf("Device: %s; %d indexed draws; %d interleaved samples/mode\n", DEV_MTL(d).name.UTF8String, draws, samples);
        const char *names[]={"native loop", "fresh CPU ICB", "reused ICB (upper bound)"};
        for (int i=0;i<3;i++) printf("%-25s CPU encoding %.3f ms; GPU %.3f ms\n", names[i],cpu[i]*1000/samples,gpu[i]*1000/samples);
        puts("PASS: all three paths produced identical nonempty pixels");
        pm_device_destroy(d);
    }
}
