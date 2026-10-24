// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework MetalFX -framework QuartzCore -framework Foundation -framework AppKit tools/test_color_discard.m -o /tmp/pm-color-discard
#include "../src/main/native/puremetal.m"
#include <assert.h>
#include <stdio.h>

int main(void) { @autoreleasepool {
    PMDevice *d=pm_device_create(0,0); assert(d);
    NSError *error=nil;
    NSString *source=@"#include <metal_stdlib>\nusing namespace metal;\nvertex float4 v(uint i [[vertex_id]]){float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};return float4(p[i],0,1);}\nfragment float4 f(){return float4(0,1,0,1);}";
    id<MTLLibrary> lib=[DEV_MTL(d) newLibraryWithSource:source options:nil error:&error]; assert(lib&&!error);
    MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
    pd.vertexFunction=[lib newFunctionWithName:@"v"];pd.fragmentFunction=[lib newFunctionWithName:@"f"];
    pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
    id<MTLRenderPipelineState> pipeline=[DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error]; assert(pipeline&&!error);
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4 height:1 mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    id<MTLTexture> first=[DEV_MTL(d) newTextureWithDescriptor:td],second=[DEV_MTL(d) newTextureWithDescriptor:td];
    assert(first&&second); void *readback=pm_buffer_create(d,32);assert(readback);
    for(int mode=0;mode<6;mode++) {
        for(int i=0;i<6;i++) pm_take_stat(d,i);
        void *colors[]={(__bridge void *)first}; int load=1;float red[]={1,0,0,1},black[]={0,0,0,1};
        assert(pm_pass_begin(d,1,colors,&load,red,NULL,0,0,4,1,0,0,4,1,NULL));pm_pass_end(d);
        if(mode==4) pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,1,readback,16,16);
        if(mode==1) {pm_clear_texture(d,colors[0],0,0,0,1,-1);load=0;}
        else load=mode==2?0:1;
        if(mode==5) colors[0]=(__bridge void *)second;
        int width=mode==3?2:4;
        assert(pm_pass_begin(d,1,colors,&load,black,NULL,0,0,width,1,0,0,width,1,NULL));
        // Load-only mode resumes the same physical encoder, so it also has only one store.
        uint64_t stored=pm_take_stat(d,5),expected=(mode<=2?0:first.allocatedSize)+(mode==5?second.allocatedSize:first.allocatedSize);
        assert(stored==expected);
        pm_pass_set_pipeline(d,(__bridge void *)pipeline,NULL,0,0,0,0);pm_pass_set_scissor(d,1,0,1,1);
        pm_pass_draw(d,0,3,1,0,0);pm_pass_end(d);
        pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,1,readback,0,16);
        if(mode==5) pm_copy_texture_to_buffer(d,(__bridge void *)first,0,0,0,0,4,1,readback,16,16);
        pm_submit(d,mode+1);assert(pm_wait(d,mode+1,5000000000LL)&&!pm_device_failed(d));
        const uint8_t *p=pm_buffer_contents(readback);
        for(int x=0;x<4;x++) {
            assert(p[x*4]==(mode==2&&x!=1?255:0));assert(p[x*4+1]==(x==1?255:0));assert(p[x*4+2]==0&&p[x*4+3]==255);
        }
        if(mode>=4) for(int x=0;x<4;x++) assert(p[16+x*4]==255&&p[17+x*4]==0&&p[18+x*4]==0&&p[19+x*4]==255);
        printf("PASS color-store mode%d: exact pixels, expected store estimate=%llu\n",mode,(unsigned long long)expected);
    }
    // MRT: discard only the cleared attachment; the other attachment must retain its blue contents.
    for(int i=0;i<6;i++) pm_take_stat(d,i);
    pd.colorAttachments[1].pixelFormat=MTLPixelFormatRGBA8Unorm;pd.colorAttachments[1].writeMask=MTLColorWriteMaskNone;
    id<MTLRenderPipelineState> mrt=[DEV_MTL(d) newRenderPipelineStateWithDescriptor:pd error:&error];assert(mrt&&!error);
    void *colors[]={(__bridge void *)first,(__bridge void *)second};int loads[]={1,1};
    float initial[]={1,0,0,1, 0,0,1,1},next[]={0,0,0,1, 0,0,0,1};
    assert(pm_pass_begin(d,2,colors,loads,initial,NULL,0,0,4,1,0,0,4,1,NULL));pm_pass_end(d);
    loads[1]=0;
    assert(pm_pass_begin(d,2,colors,loads,next,NULL,0,0,4,1,0,0,4,1,NULL));
    assert(pm_take_stat(d,5)==first.allocatedSize+second.allocatedSize*2);
    pm_pass_set_pipeline(d,(__bridge void *)mrt,NULL,0,0,0,0);pm_pass_set_scissor(d,1,0,1,1);pm_pass_draw(d,0,3,1,0,0);pm_pass_end(d);
    pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,1,readback,0,16);
    pm_copy_texture_to_buffer(d,colors[1],0,0,0,0,4,1,readback,16,16);
    pm_submit(d,7);assert(pm_wait(d,7,5000000000LL)&&!pm_device_failed(d));
    const uint8_t *p=pm_buffer_contents(readback);
    for(int x=0;x<4;x++) {
        assert(p[x*4]==0&&p[x*4+1]==(x==1?255:0)&&p[x*4+2]==0&&p[x*4+3]==255);
        assert(p[16+x*4]==0&&p[17+x*4]==0&&p[18+x*4]==255&&p[19+x*4]==255);
    }
    puts("PASS MRT: cleared color discarded, uncleared color preserved, exact pixels");
    pm_release(readback);pm_device_destroy(d);return 0;
} }
