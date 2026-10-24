// Exercises the real native binding functions with a recording encoder; no window or GPU required.
// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework MetalFX -framework QuartzCore
//   -framework Foundation -framework AppKit tools/test_bindings.m -o /tmp/puremetal-test-bindings
#include "../src/main/native/puremetal.m"
#include <assert.h>
#include <stdio.h>

@interface PMRecordingEncoder : NSObject
@property NSUInteger pipelines, depths, vertexBuffers, fragmentBuffers, textures, samplers, bytes;
@property NSUInteger offsetChanges;
@end

@implementation PMRecordingEncoder
- (void)setRenderPipelineState:(id)value { self.pipelines++; }
- (void)setDepthStencilState:(id)value { self.depths++; }
- (void)setCullMode:(MTLCullMode)value {}
- (void)setTriangleFillMode:(MTLTriangleFillMode)value {}
- (void)setDepthBias:(float)value slopeScale:(float)slope clamp:(float)clamp {}
- (void)setVertexBuffer:(id)value offset:(NSUInteger)offset atIndex:(NSUInteger)index { self.vertexBuffers++; }
- (void)setFragmentBuffer:(id)value offset:(NSUInteger)offset atIndex:(NSUInteger)index { self.fragmentBuffers++; }
- (void)setVertexBufferOffset:(NSUInteger)offset atIndex:(NSUInteger)index { self.vertexBuffers++; self.offsetChanges++; }
- (void)setFragmentBufferOffset:(NSUInteger)offset atIndex:(NSUInteger)index { self.fragmentBuffers++; self.offsetChanges++; }
- (void)setVertexTexture:(id)value atIndex:(NSUInteger)index { self.textures++; }
- (void)setFragmentTexture:(id)value atIndex:(NSUInteger)index { self.textures++; }
- (void)setVertexSamplerState:(id)value atIndex:(NSUInteger)index { self.samplers++; }
- (void)setFragmentSamplerState:(id)value atIndex:(NSUInteger)index { self.samplers++; }
- (void)setVertexBytes:(const void *)data length:(NSUInteger)length atIndex:(NSUInteger)index { self.bytes++; }
- (void)setFragmentBytes:(const void *)data length:(NSUInteger)length atIndex:(NSUInteger)index { self.bytes++; }
@end

int main(void) {
    @autoreleasepool {
        PMDevice d = {0};
        pthread_mutex_init(&d.lock, NULL);
        PMRecordingEncoder *e = [PMRecordingEncoder new];
        d.render = (__bridge void *)e;
        NSObject *a = [NSObject new], *b = [NSObject new];
        void *pa = (__bridge void *)a, *pb = (__bridge void *)b;
        int kinds[] = {0, 1, 2}, indices[] = {0, 30, 15};
        void *objects[] = {pa, pa, pa};
        uint64_t offsets[] = {0, 0, 0};
        for (int i = 0; i < 100; i++) {
            pm_pass_set_pipeline(&d, pa, pb, 1, 0, 0, 0);
            pm_pass_set_vertex_buffer(&d, 0, pa, 0);
            pm_pass_bind(&d, 3, kinds, indices, objects, offsets);
        }
        assert(e.pipelines == 1 && e.depths == 1);
        assert(e.vertexBuffers == 2 && e.fragmentBuffers == 1);
        assert(e.textures == 2 && e.samplers == 2);

        // Changing offsets or buffer identity must bind again, even when the pipeline is unchanged.
        offsets[0] = 16;
        pm_pass_bind(&d, 3, kinds, indices, objects, offsets);
        objects[0] = pb;
        pm_pass_bind(&d, 3, kinds, indices, objects, offsets);
        assert(e.vertexBuffers == 4 && e.fragmentBuffers == 3);
        assert(e.offsetChanges == 2);

        // Inline bytes are a fresh snapshot each time and replace both stages' previous buffer binding.
        uint32_t data = 1;
        pm_pass_set_bytes(&d, &data, sizeof(data), 0);
        data = 2;
        pm_pass_set_bytes(&d, &data, sizeof(data), 0);
        pm_pass_bind(&d, 3, kinds, indices, objects, offsets);
        assert(e.bytes == 4 && e.vertexBuffers == 5 && e.fragmentBuffers == 4);

        objects[1] = pb;
        objects[2] = pb;
        pm_pass_bind(&d, 3, kinds, indices, objects, offsets);
        assert(e.textures == 4 && e.samplers == 4);
        pm_pass_set_vertex_buffer(&d, 0, pa, 32);
        assert(e.vertexBuffers == 6);
        assert(e.offsetChanges == 3);

        // A fresh encoder/logical pass must receive every binding again.
        memset(&d.bindings, 0, sizeof(d.bindings));
        pm_pass_set_pipeline(&d, pa, pb, 1, 0, 0, 0);
        pm_pass_set_vertex_buffer(&d, 0, pa, 32);
        pm_pass_bind(&d, 3, kinds, indices, objects, offsets);
        assert(e.pipelines == 2 && e.depths == 2);
        assert(e.vertexBuffers == 8 && e.fragmentBuffers == 5);
        assert(e.textures == 6 && e.samplers == 6);
        // Unbinding must never use an offset-only setter on a nil buffer.
        objects[0] = NULL;
        pm_pass_bind(&d, 1, kinds, indices, objects, offsets);
        NSUInteger offsetChanges = e.offsetChanges;
        offsets[0] = 32;
        pm_pass_bind(&d, 1, kinds, indices, objects, offsets);
        assert(e.offsetChanges == offsetChanges);

        // Reject bad indices before shifts or cache accesses, including sampler's smaller limit.
        for (int kind = 0; kind < 3; kind++) {
            int limit = kind == 2 ? 16 : 31;
            int bad[] = {-1, limit, 32};
            for (int i = 0; i < 3; i++) {
                atomic_store(&d.failed, 0);
                pm_pass_bind(&d, 1, &kind, &bad[i], objects, offsets);
                assert(atomic_load(&d.failed));
            }
        }
        atomic_store(&d.failed, 0);
        pm_pass_set_vertex_buffer(&d, 8, pa, 0);
        assert(atomic_load(&d.failed));
        atomic_store(&d.failed, 0);
        pm_pass_set_bytes(&d, &data, sizeof(data), 31);
        assert(atomic_load(&d.failed));
        pthread_mutex_destroy(&d.lock);
        puts("PASS: redundant bindings skipped; changed resources, offsets, inline bytes and reset rebind correctly");
    }
}
