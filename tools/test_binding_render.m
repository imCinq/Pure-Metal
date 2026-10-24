// Real Metal readback regression, without launching Minecraft or opening a window.
// Build with the same clang/framework flags as tools/test_bindings.m.
#include "../src/main/native/puremetal.m"
#include <assert.h>
#include <stdio.h>

int main(int argc, char **argv) {
    (void)argv;
    @autoreleasepool {
        PMDevice *d = pm_device_create(0,0);
        if (!d) { fputs("No Metal device available\n", stderr); return 2; }
        bool profiling = argc > 1;
        if (profiling) assert(pm_profile_enable(d));
        NSError *error = nil;
        NSString *source = @"#include <metal_stdlib>\nusing namespace metal;\n"
            "vertex float4 v(uint i [[vertex_id]]) { float2 p[3] = {float2(-1,-1),float2(3,-1),float2(-1,3)}; return float4(p[i],0,1); }\n"
            "fragment float4 f(constant float4 &c [[buffer(0)]]) { return c; }\n";
        id<MTLLibrary> library = [DEV_MTL(d) newLibraryWithSource:source options:nil error:&error];
        assert(library && !error);
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.vertexFunction = [library newFunctionWithName:@"v"];
        pd.fragmentFunction = [library newFunctionWithName:@"f"];
        pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        id<MTLRenderPipelineState> pipeline = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error];
        assert(pipeline && !error);
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
            width:4 height:1 mipmapped:NO];
        td.storageMode = MTLStorageModePrivate;
        td.usage = MTLTextureUsageRenderTarget;
        id<MTLTexture> target = [DEV_MTL(d) newTextureWithDescriptor:td];
        assert(target);
        void *uniform = pm_buffer_create(d, 32), *readback = pm_buffer_create(d, 32);
        void *uploadSrc = pm_buffer_create(d, 16), *uploadDst = pm_buffer_create(d, 16);
        memset(pm_buffer_contents(uploadSrc), 0x5A, 16);
        pm_copy_buffer_early(d, uploadSrc, 0, uploadDst, 0, 16);
        assert(uniform && readback);
        float values[] = {1,0,0,1, 0,1,0,1};
        memcpy(pm_buffer_contents(uniform), values, sizeof(values));
        void *colors[] = {(__bridge void *)target};
        int load = 1, kind = 0, index = 0;
        float clear[4] = {0,0,0,1};
        uint64_t offset = 0;
        assert(pm_pass_begin(d, 1, colors, &load, clear, NULL, 0, 0, 4, 1, 0, 0, 4, 1, NULL));
        pm_pass_set_pipeline(d, (__bridge void *)pipeline, NULL, 0, 0, 0, 0);
        for (int x = 0; x < 4; x++) {
            pm_pass_set_scissor(d, x, 0, 1, 1);
            if (x == 2) {
                float snapshot[] = {0,0,1,1};
                pm_pass_set_bytes(d, snapshot, sizeof(snapshot), 0);
                snapshot[0] = 1; // same address, new contents must be captured
                pm_pass_set_bytes(d, snapshot, sizeof(snapshot), 0);
            } else {
                offset = x == 0 ? 0 : 16;
                pm_pass_bind(d, 1, &kind, &index, &uniform, &offset);
                pm_pass_bind(d, 1, &kind, &index, &uniform, &offset);
            }
            pm_pass_draw(d, 0, 3, 1, 0, 0); // triangles
            if (x == 0) {
                // Same attachments resume the parked encoder. The logical-pass cache must reset too.
                pm_pass_end(d);
                load = 0;
                assert(pm_pass_begin(d, 1, colors, &load, clear, NULL, 0, 0, 4, 1, 0, 0, 4, 1, NULL));
                pm_pass_set_pipeline(d, (__bridge void *)pipeline, NULL, 0, 0, 0, 0);
            }
        }
        pm_pass_end(d);
        pm_copy_texture_to_buffer(d, colors[0], 0, 0, 0, 0, 4, 1, readback, 0, 16);
        // A blit closes the old encoder; identical pipeline/buffer state must be emitted on the next encoder.
        assert(pm_pass_begin(d, 1, colors, &load, clear, NULL, 0, 0, 4, 1, 0, 0, 4, 1, NULL));
        pm_pass_set_pipeline(d, (__bridge void *)pipeline, NULL, 0, 0, 0, 0);
        pm_pass_bind(d, 1, &kind, &index, &uniform, &offset);
        pm_pass_draw(d, 0, 3, 1, 0, 0);
        pm_pass_end(d);
        pm_copy_texture_to_buffer(d, colors[0], 0, 0, 0, 0, 4, 1, readback, 16, 16);
        pm_submit(d, 1);
        assert(pm_wait(d, 1, 5000000000LL) && !pm_device_failed(d));
        assert(memcmp(pm_buffer_contents(uploadSrc), pm_buffer_contents(uploadDst), 16) == 0);
        assert(pm_take_upload_gpu_commands(d) == 1);
        assert(pm_take_upload_gpu_commands(d) == 0);
        assert(pm_take_upload_gpu_ns(d) > 0);
        assert(pm_take_upload_gpu_ns(d) == 0);
        for (int i = 0; i < 1000 && atomic_load(&d->handlers); i++) usleep(1000);
        assert(atomic_load(&d->handlers) == 0);
        uint64_t vertexNs = pm_take_profile_stat(d, 0), fragmentNs = pm_take_profile_stat(d, 1);
        uint64_t blitNs = pm_take_profile_stat(d, 2);
        uint64_t vertices = pm_take_profile_stat(d, 3), fragments = pm_take_profile_stat(d, 4);
        uint64_t blits = pm_take_profile_stat(d, 5);
        if (profiling) {
            assert(vertexNs > 0 && fragmentNs > 0 && blitNs > 0);
            assert(vertices >= 2 && fragments >= 2 && blits >= 1);
            printf("PROFILE: vertex=%llu ns fragment=%llu ns in-frame-blit=%llu ns, samples=%llu/%llu/%llu\n",
                vertexNs, fragmentNs, blitNs, vertices, fragments, blits);
        } else {
            assert(vertexNs == 0 && fragmentNs == 0 && blitNs == 0 && vertices == 0 && fragments == 0 && blits == 0);
        }
        for (int i = 0; i < 6; i++) assert(pm_take_profile_stat(d, i) == 0);
        const uint8_t expected[] = {255,0,0,255, 0,255,0,255, 255,0,255,255, 0,255,0,255};
        const uint8_t *pixels = pm_buffer_contents(readback);
        assert(memcmp(pixels, expected, sizeof(expected)) == 0);
        for (int x = 0; x < 4; x++) assert(memcmp(pixels + 16 + x * 4, expected + 4, 4) == 0);
        if (profiling) {
            // A measurable isolated blit checks timestamp units, not just nonzero counters.
            // Resolved counter durations must fit inside the whole command-buffer duration.
            pm_take_gpu_ns(d);
            pm_take_gpu_frames(d);
            void *a = pm_buffer_create(d, 1024 * 1024);
            void *b = pm_buffer_create(d, 1024 * 1024);
            assert(a && b);
            for (int i = 0; i < 64; i++) pm_copy_buffer(d, a, 0, b, 0, 1024 * 1024);
            pm_submit(d, 2);
            assert(pm_wait(d, 2, 5000000000LL) && !pm_device_failed(d));
            for (int i = 0; i < 1000 && atomic_load(&d->handlers); i++) usleep(1000);
            assert(atomic_load(&d->handlers) == 0);
            uint64_t bufferNs = pm_take_gpu_ns(d), copyNs = pm_take_profile_stat(d, 2);
            printf("PROFILE-UNITS: isolated blit=%llu ns whole buffer=%llu ns\n", copyNs, bufferNs);
            fflush(stdout);
            assert(copyNs > 0 && bufferNs > 0 && copyNs <= bufferNs * 2);
            assert(pm_take_profile_stat(d, 5) == 1);
            pm_release(a);
            pm_release(b);
        }
        pm_device_destroy(d);
        pm_release(uniform);
        pm_release(readback);
        pm_release(uploadSrc);
        pm_release(uploadDst);
        puts("PASS: Metal pixels correct after offset updates, inline snapshots, merged passes and new encoders");
    }
}
