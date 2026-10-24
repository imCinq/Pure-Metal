// Production native API, offscreen only. Run on Metal 3, then on Metal 4 when the OS has it.
// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework MetalFX -framework QuartzCore -framework Foundation -framework AppKit tools/test_terrain_probe.m -o /tmp/pm-terrain-probe
#include "../src/main/native/puremetal.m"
#include <assert.h>
#include <stdio.h>
static int run(int metal4) { @autoreleasepool {
    PMDevice *d=pm_device_create(0,metal4);assert(d);
    char reason[128];assert(pm_metal4_encoding(d,reason,sizeof reason)==metal4);
    NSError *error=nil;
    NSString *probe=[NSString stringWithContentsOfFile:@"src/main/resources/assets/puremetal/shaders/terrain_probe.metal" encoding:NSUTF8StringEncoding error:&error];assert(probe);
    assert(pm_probe_enable(d,probe.UTF8String));assert(pm_probe_enable(d,probe.UTF8String));
    int counters=pm_profile_enable(d);
    NSString *source=@"#include <metal_stdlib>\nusing namespace metal;\n"
        "vertex float4 v(uint i [[vertex_id]],constant float &z [[buffer(0)]]){float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};return float4(p[i],z,1);}\n"
        "fragment float4 f(){return float4(.2,.4,.6,1);}";
    id<MTLLibrary> lib=[DEV_MTL(d) newLibraryWithSource:source options:nil error:&error];assert(lib);
    MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"v"];pd.fragmentFunction=[lib newFunctionWithName:@"f"];
    pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
    id<MTLRenderPipelineState> pipeline=[DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error];assert(pipeline);
    MTLDepthStencilDescriptor *ds=[MTLDepthStencilDescriptor new];ds.depthCompareFunction=MTLCompareFunctionLess;ds.depthWriteEnabled=YES;
    id<MTLDepthStencilState> state=[DEV_MTL(d) newDepthStencilStateWithDescriptor:ds];assert(state);
    enum {width=13,height=7,bytes=width*height*4};
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    id<MTLTexture> color=[DEV_MTL(d) newTextureWithDescriptor:td];td.pixelFormat=MTLPixelFormatDepth32Float;
    id<MTLTexture> depth=[DEV_MTL(d) newTextureWithDescriptor:td];assert(color && depth);
    // Metal 4 reads only resident memory: the production code retains its textures through pm_retain, so the test does too.
    void *keepColor=pm_retain(color),*keepDepth=pm_retain(depth);
    void *readback=pm_buffer_create(d,bytes*2);assert(readback);uint8_t reference[bytes*2];
    PMProbeQuery queries[4100];
    for(int i=0;i<4100;i++) queries[i]=(PMProbeQuery){0,0,width,height,.75f,0};
    queries[0].nearest=.1f;queries[1].uncertain=1;queries[2].nearest=NAN;queries[3].x0=-1;
    uint64_t submit=0;
    for(int mode=0;mode<8;mode++) {
        bool enabled=mode%4!=0,reverse=mode>=4;ds.depthCompareFunction=reverse?MTLCompareFunctionGreater:MTLCompareFunctionLess;state=[DEV_MTL(d) newDepthStencilStateWithDescriptor:ds];
        void *colors[]={(__bridge void *)color};int load=1;float clear[]={0,0,0,1};
        assert(pm_pass_begin(d,1,colors,&load,clear,(__bridge void *)depth,1,reverse?0:1,width,height,0,0,width,height,NULL));
        pm_pass_set_pipeline(d,(__bridge void *)pipeline,(__bridge void *)state,0,0,0,0);
        float z=reverse?.75f:.25f;pm_pass_set_bytes(d,&z,4,0);pm_pass_draw(d,0,3,1,0,0);
        if(enabled) assert(pm_pass_probe_queries(d,queries,4100,reverse)==4096);
        pm_pass_end(d);
        // A later compatible pass writes nearer depth, like a HUD/hand. It must not contaminate query0.
        load=0;assert(pm_pass_begin(d,1,colors,&load,clear,(__bridge void *)depth,0,1,width,height,0,0,width,height,NULL));
        if(enabled) assert(pm_pass_probe_queries(d,queries,1,reverse)==0);
        pm_pass_set_pipeline(d,(__bridge void *)pipeline,(__bridge void *)state,0,0,0,0);
        z=reverse?.95f:.05f;pm_pass_set_bytes(d,&z,4,0);pm_pass_draw(d,0,3,1,0,0);pm_pass_end(d);
        pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,width,height,readback,0,width*4);
        pm_copy_texture_to_buffer(d,(__bridge void *)depth,0,0,0,0,width,height,readback,bytes,width*4);
        pm_submit(d,++submit);assert(pm_wait(d,submit,5000000000LL) && !pm_device_failed(d));
        // Completion statistics become available after their handler finishes.
        while(atomic_load(&d->handlers)) sched_yield();
        if(!enabled) memcpy(reference,pm_buffer_contents(readback),bytes*2);
        else assert(!memcmp(reference,pm_buffer_contents(readback),bytes*2));
        uint64_t stats[6];pm_take_probe_stats(d,stats);
        assert(stats[0]==(enabled?4096:0));assert(stats[1]==(enabled?4092:0));
        assert(stats[2]==(enabled?5:0));assert(stats[4]==(enabled?1:0));
        uint64_t ns=stats[3],valid=stats[5];
        assert(valid<=1 && (!valid || ns>0));
        if(enabled && counters && !metal4) assert(valid==1);
        for(int i=0;i<6;i++) assert(pm_take_probe_stat(d,i)==0);
        printf("PASS %s probe mode%d: exact color/depth, bounded queries, asynchronous stats/reset; span=%llu ns\n",metal4?"Metal4":"Metal3",mode,(unsigned long long)ns);
    }
    // Pooled buffers: a new target size recreates the pyramid, and a burst of unwaited submits only borrows free slots.
    // Query 0 of every submit covers the whole target at .75 over depth .25, so it is hidden whatever the size.
    {
        const unsigned sizes[][2]={{21,9},{13,7},{64,33},{1,1},{13,7}};
        for(unsigned i=0;i<5;i++) {
            unsigned w=sizes[i][0],h=sizes[i][1];
            MTLTextureDescriptor *sd=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:w height:h mipmapped:NO];
            sd.storageMode=MTLStorageModePrivate;sd.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
            id<MTLTexture> c2=[DEV_MTL(d) newTextureWithDescriptor:sd];sd.pixelFormat=MTLPixelFormatDepth32Float;
            id<MTLTexture> d2=[DEV_MTL(d) newTextureWithDescriptor:sd];assert(c2 && d2);
            void *keepC2=pm_retain(c2),*keepD2=pm_retain(d2);
            ds.depthCompareFunction=MTLCompareFunctionLess;state=[DEV_MTL(d) newDepthStencilStateWithDescriptor:ds];
            void *cs[]={(__bridge void *)c2};int ld=1;float cl[]={0,0,0,1};
            assert(pm_pass_begin(d,1,cs,&ld,cl,(__bridge void *)d2,1,1,w,h,0,0,w,h,NULL));
            pm_pass_set_pipeline(d,(__bridge void *)pipeline,(__bridge void *)state,0,0,0,0);
            // The last column and row stay at the cleared depth 1: only queries that stay inside the drawn area are hidden.
            float z=.25f;pm_pass_set_bytes(d,&z,4,0);pm_pass_set_scissor(d,0,0,w>1?w-1:w,h>1?h-1:h);pm_pass_draw(d,0,3,1,0,0);
            PMProbeQuery q[64];for(int k=0;k<64;k++) q[k]=(PMProbeQuery){0,0,w>1?4:1,h>1?4:1,.75f,0}; // a 4x4 tile inside the drawn area
            q[1]=(PMProbeQuery){0,0,(int)w,(int)h,.75f,0};q[2]=(PMProbeQuery){(int)w-1,0,(int)w,(int)h,.75f,0};
            q[3]=(PMProbeQuery){0,(int)h-1,(int)w,(int)h,.75f,0};
            assert(pm_pass_probe_queries(d,q,64,0)==64);
            pm_pass_end(d);pm_submit(d,++submit);assert(pm_wait(d,submit,5000000000LL) && !pm_device_failed(d));
            while(atomic_load(&d->handlers)) sched_yield();
            uint64_t st[6];pm_take_probe_stats(d,st);
            uint64_t expected=w>1&&h>1?61:64; // 1x1 has no undrawn column or row, so every query is hidden
            // (the pyramid may cover more than a query asks for, so only the tile inside the drawn area is guaranteed hidden)
            assert(st[0]==64 && st[1]==expected && st[2]==0 && st[4]==1);
            pm_release(keepC2);pm_release(keepD2);
        }
        printf("PASS %s pooled probe buffers across target sizes\n",metal4?"Metal4":"Metal3");
        uint64_t first=submit+1;const unsigned burst=8;
        for(unsigned i=0;i<burst;i++) {
            void *cs[]={(__bridge void *)color};int ld=1;float cl[]={0,0,0,1};
            assert(pm_pass_begin(d,1,cs,&ld,cl,(__bridge void *)depth,1,1,width,height,0,0,width,height,NULL));
            pm_pass_set_pipeline(d,(__bridge void *)pipeline,(__bridge void *)state,0,0,0,0);
            float z=.25f;pm_pass_set_bytes(d,&z,4,0);pm_pass_draw(d,0,3,1,0,0);
            PMProbeQuery q[64];for(int k=0;k<64;k++) q[k]=(PMProbeQuery){0,0,width,height,.75f,0};
            assert(pm_pass_probe_queries(d,q,64,0)==64);
            pm_pass_end(d);pm_submit(d,++submit);
        }
        assert(pm_wait(d,first+burst-1,5000000000LL) && !pm_device_failed(d));
        while(atomic_load(&d->handlers)) sched_yield();
        uint64_t st[6];pm_take_probe_stats(d,st);
        // Every query is either measured or counted as dropped, and a measured batch is fully hidden.
        assert(st[0]+st[2]==64u*burst && st[0]==64*st[4] && st[1]==st[0] && st[4]>=1);
        printf("PASS %s burst of %u unwaited submits: %llu probes measured, %llu queries dropped\n",metal4?"Metal4":"Metal3",burst,
               (unsigned long long)st[4],(unsigned long long)st[2]);
    }
    assert(!pm_pass_probe_queries(d,queries,1,0));
    pm_release(readback);pm_release(keepColor);pm_release(keepDepth);pm_device_destroy(d);
    printf("PASS %s production depth probe, no draw rejection\n",metal4?"Metal4":"Metal3");return 0;
} }
int main(void) {
    setvbuf(stdout,NULL,_IOLBF,0);
    int status=run(0);
    if(status) return status;
    if(@available(macOS 26.0,*)) { char name[128];if(pm_device_check(name,sizeof name)) return run(1); }
    puts("SKIP Metal 4 probe: needs macOS 26 or newer");return 0;
}
