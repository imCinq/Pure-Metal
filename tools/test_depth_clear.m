// Same framework flags as test_color_discard.m; run with Metal API validation.
#include "../src/main/native/puremetal.m"
#include <assert.h>
#include <stdio.h>

int main(void) { @autoreleasepool {
    PMDevice *d = pm_device_create(0,0); assert(d);
    NSError *error = nil;
    NSString *source = @"#include <metal_stdlib>\nusing namespace metal;\n"
        "vertex float4 v(uint i [[vertex_id]],constant float &z [[buffer(0)]]){"
        "float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};return float4(p[i],z,1);}\n"
        "fragment float4 f(constant float4 &c [[buffer(1)]]){return c;}";
    id<MTLLibrary> lib = [DEV_MTL(d) newLibraryWithSource:source options:nil error:&error]; assert(lib);
    MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
    pd.vertexFunction = [lib newFunctionWithName:@"v"]; pd.fragmentFunction = [lib newFunctionWithName:@"f"];
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
    pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    id<MTLRenderPipelineState> pipeline = [DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error]; assert(pipeline);
    MTLDepthStencilDescriptor *ds = [MTLDepthStencilDescriptor new];
    ds.depthCompareFunction = MTLCompareFunctionLess; ds.depthWriteEnabled = YES;
    id<MTLDepthStencilState> state = [DEV_MTL(d) newDepthStencilStateWithDescriptor:ds]; assert(state);
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4 height:4 mipmapped:NO];
    td.storageMode = MTLStorageModePrivate; td.usage = MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    id<MTLTexture> color = [DEV_MTL(d) newTextureWithDescriptor:td], other = [DEV_MTL(d) newTextureWithDescriptor:td];
    td.pixelFormat = MTLPixelFormatDepth32Float;
    id<MTLTexture> depth = [DEV_MTL(d) newTextureWithDescriptor:td]; assert(color && depth && other);
    void *buffer = pm_buffer_create(d,128); assert(buffer);
    uint8_t reference[128]; uint64_t submit = 0;
    // Explicit/folded clears; endpoints; color clear; pending unrelated clear; partial extent; intervening readback.
    for (int mode=0; mode<8; mode++) for (int enabled=0; enabled<2; enabled++) {
        d->foldDepthClears = enabled;
        for (int i=0;i<6;i++) pm_take_stat(d,i);
        void *colors[] = {(__bridge void *)color}; int load=1;
        float red[]={1,0,0,1}, green[]={0,1,0,1}; float z=.25f;
        assert(pm_pass_begin(d,1,colors,&load,red,(__bridge void *)depth,1,1,4,4,0,0,4,4,NULL));
        pm_pass_set_pipeline(d,(__bridge void *)pipeline,(__bridge void *)state,0,0,0,0);
        pm_pass_set_bytes(d,&z,4,0); pm_pass_set_bytes(d,red,16,1); pm_pass_draw(d,0,3,1,0,0);
        // Seed cached raster state that the internal clear must override and later invalidate.
        pm_pass_set_pipeline(d,(__bridge void *)pipeline,(__bridge void *)state,1,1,2,2);
        pm_pass_end(d);
        double clear = mode==2?0:mode==3?1:.3;
        if (mode==1) pm_clear_texture(d,(__bridge void *)depth,0,0,0,0,clear);
        if (mode==5) pm_clear_texture(d,(__bridge void *)other,0,0,0,1,-1);
        if (mode==7) pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,4,buffer,0,16);
        load=mode==4?1:0; int width=mode==6?2:4;
        assert(pm_pass_begin(d,1,colors,&load,red,(__bridge void *)depth,mode==1?0:1,clear,width,4,1,1,1,1,NULL));
        uint64_t passes=pm_take_stat(d,0);
        // Separate encode_clear passes are not counted by the ordinary-pass statistic.
        assert(passes==((enabled && mode<4)?1:2));
        pm_pass_set_pipeline(d,(__bridge void *)pipeline,(__bridge void *)state,0,0,0,0);
        z=.2f; pm_pass_set_bytes(d,&z,4,0); pm_pass_set_bytes(d,green,16,1); pm_pass_draw(d,0,3,1,0,0);
        pm_pass_end(d);
        pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,4,buffer,0,16);
        pm_copy_texture_to_buffer(d,(__bridge void *)depth,0,0,0,0,4,4,buffer,64,16);
        pm_submit(d,++submit); assert(pm_wait(d,submit,5000000000LL) && !pm_device_failed(d));
        const uint8_t *pixels = pm_buffer_contents(buffer);
        if (!enabled) memcpy(reference,pixels,128);
        else { assert(memcmp(reference,pixels,128)==0); printf("PASS mode%d: identical color/depth bits, expected encoder count\n",mode); }
    }
    // Guard rejects depth/stencil, nonfinite values and partial extents before encoding any draw.
    td.pixelFormat=MTLPixelFormatDepth32Float_Stencil8;
    id<MTLTexture> stencil=[DEV_MTL(d) newTextureWithDescriptor:td]; assert(stencil);
    void *colors[]={(__bridge void *)color}; d->foldDepthClears=true;
    assert(!fold_depth_clear(d,colors,(__bridge void *)stencil,.3,4,4));
    assert(!fold_depth_clear(d,colors,(__bridge void *)depth,NAN,4,4));
    assert(!fold_depth_clear(d,colors,(__bridge void *)depth,.3,2,4));
    puts("PASS conservative fallback guards");
    pm_release(buffer); pm_device_destroy(d); return 0;
} }
