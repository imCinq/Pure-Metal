// Offscreen regression for color, pending clears, multiple targets, and depth.
// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework MetalFX -framework QuartzCore -framework Foundation -framework AppKit tools/test_partial_area_clear.m -o /tmp/pm-partial-area
// MTL_DEBUG_LAYER=1 MTL_DEBUG_LAYER_ERROR_MODE=nslog /tmp/pm-partial-area
#include "../src/main/native/puremetal.m"
#include <assert.h>
#include <stdio.h>
static id<MTLTexture> texture(PMDevice *d, MTLPixelFormat f) {
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:4 height:2 mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    id<MTLTexture> t=[DEV_MTL(d) newTextureWithDescriptor:td];assert(t);return t;
}
static void done(PMDevice *d,uint64_t *n) {pm_submit(d,++*n);assert(pm_wait(d,*n,5000000000LL)&&!pm_device_failed(d));}
static void test(PMDevice *d) {
    uint64_t seq=0;void *buf=pm_buffer_create(d,256);assert(buf);
    id<MTLTexture> a=texture(d,MTLPixelFormatRGBA8Unorm),b=texture(d,MTLPixelFormatRGBA8Unorm);
    void *colors[]={(__bridge void *)a,(__bridge void *)b};int loads[]={1,1};
    float redblue[]={1,0,0,1,0,0,1,1},green[]={0,1,0,1,1,1,0,1};
    assert(pm_pass_begin(d,2,colors,loads,redblue,NULL,0,0,4,2,0,0,4,2,NULL));pm_pass_end(d);
    loads[1]=0;assert(pm_pass_begin(d,2,colors,loads,green,NULL,0,0,4,2,1,0,2,1,NULL));pm_pass_end(d);
    pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,2,buf,0,16);pm_copy_texture_to_buffer(d,colors[1],0,0,0,0,4,2,buf,32,16);done(d,&seq);
    const uint8_t *p=pm_buffer_contents(buf);
    for(int i=0;i<8;i++){int j=i*4;bool in=i==1||i==2;assert(p[j]==(in?0:255)&&p[j+1]==(in?255:0)&&p[j+2]==0&&p[j+3]==255);assert(p[32+j]==0&&p[33+j]==0&&p[34+j]==255&&p[35+j]==255);}
    puts("PASS color and untouched MRT");
    pm_clear_texture(d,colors[0],1,0,0,1,-1);loads[0]=1;
    assert(pm_pass_begin(d,1,colors,loads,green,NULL,0,0,4,2,1,0,1,1,NULL));pm_pass_end(d);
    pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,2,buf,0,16);done(d,&seq);
    for(int i=0;i<8;i++){int j=i*4;assert(p[j]==(i==1?0:255)&&p[j+1]==(i==1?255:0));}
    puts("PASS pending full clear before partial clear");
    assert(pm_pass_begin(d,1,colors,loads,green,NULL,0,0,4,2,0,0,0,1,NULL));pm_pass_end(d);
    pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,2,buf,0,16);done(d,&seq);
    for(int i=0;i<8;i++){int j=i*4;assert(p[j]==(i==1?0:255)&&p[j+1]==(i==1?255:0));}
    puts("PASS zero-width clear preserves pixels");
    float blue[]={0,0,1,1};
    assert(pm_pass_begin(d,1,colors,loads,green,NULL,0,0,4,2,1,0,1,1,NULL));pm_pass_end(d);pm_submit(d,++seq);
    assert(pm_pass_begin(d,1,colors,loads,blue,NULL,0,0,4,2,2,0,1,1,NULL));pm_pass_end(d);
    pm_copy_texture_to_buffer(d,colors[0],0,0,0,0,4,2,buf,0,16);done(d,&seq);
    assert(p[4]==0&&p[5]==255&&p[6]==0&&p[8]==0&&p[9]==0&&p[10]==255);
    puts("PASS temporary clear textures across submissions in flight");
    MTLTextureDescriptor *mtd=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:8 height:4 mipmapped:YES];
    mtd.mipmapLevelCount=2;mtd.storageMode=MTLStorageModePrivate;mtd.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    id<MTLTexture> mip=[DEV_MTL(d) newTextureWithDescriptor:mtd];assert(mip);
    void *mipview=pm_texture_view((__bridge void *)mip,1,1,0);assert(mipview);
    pm_clear_texture(d,(__bridge void *)mip,1,0,0,1,-1);
    void *mipcolors[]={mipview};int ml=1;
    assert(pm_pass_begin(d,1,mipcolors,&ml,green,NULL,0,0,4,2,1,0,1,1,NULL));pm_pass_end(d);
    pm_copy_texture_to_buffer(d,mipview,0,0,0,0,4,2,buf,0,16);
    pm_copy_texture_to_buffer(d,(__bridge void *)mip,0,0,0,0,8,4,buf,64,32);done(d,&seq);
    for(int i=0;i<8;i++){int j=i*4;assert(p[j]==(i==1?0:255)&&p[j+1]==(i==1?255:0));}
    for(int i=0;i<32;i++){int j=64+i*4;assert(p[j]==255&&p[j+1]==0&&p[j+2]==0&&p[j+3]==255);}
    pm_release(mipview);puts("PASS mip view preserves other mip and outside pixels");
    MTLTextureDescriptor *ctd=[MTLTextureDescriptor textureCubeDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm size:4 mipmapped:NO];
    ctd.storageMode=MTLStorageModePrivate;ctd.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    id<MTLTexture> cube=[DEV_MTL(d) newTextureWithDescriptor:ctd];assert(cube);
    void *cubeview=pm_retain(cube);void *cc[]={cubeview};
    pm_clear_texture(d,cubeview,1,0,0,1,-1);
    assert(pm_pass_begin(d,1,cc,&ml,green,NULL,0,0,4,4,1,0,1,1,NULL));pm_pass_end(d);
    pm_copy_texture_to_buffer(d,cubeview,0,0,0,0,4,4,buf,0,16);
    pm_copy_texture_to_buffer(d,cubeview,0,1,0,0,4,4,buf,64,16);done(d,&seq);
    for(int i=0;i<16;i++){int j=i*4;assert(p[j]==(i==1?0:255)&&p[j+1]==(i==1?255:0));assert(p[64+j]==255&&p[65+j]==0);}
    pm_release(cubeview);puts("PASS cube face and untouched faces");
    ctd.pixelFormat=MTLPixelFormatDepth32Float;
    id<MTLTexture> cubeDepth=[DEV_MTL(d) newTextureWithDescriptor:ctd];assert(cubeDepth);
    void *cd=pm_retain(cubeDepth);pm_clear_texture(d,cd,0,0,0,0,.75);
    assert(pm_pass_begin(d,0,NULL,NULL,NULL,cd,1,.25,4,4,1,0,1,1,NULL));pm_pass_end(d);
    pm_copy_texture_to_buffer(d,cd,0,0,0,0,4,4,buf,0,16);
    pm_copy_texture_to_buffer(d,cd,0,1,0,0,4,4,buf,64,16);done(d,&seq);
    for(int i=0;i<16;i++){float x,y;memcpy(&x,p+i*4,4);memcpy(&y,p+64+i*4,4);assert(fabsf(x-(i==1?.25f:.75f))<.00001f&&fabsf(y-.75f)<.00001f);}
    pm_release(cd);puts("PASS cube depth face and untouched faces");
    MTLPixelFormat cformats[]={MTLPixelFormatRGBA8Unorm,MTLPixelFormatRGBA16Float,MTLPixelFormatR32Float,MTLPixelFormatR32Uint,MTLPixelFormatR32Sint};
    for(int f=0;f<5;f++) {
        id<MTLTexture> actual=texture(d,cformats[f]),reference=texture(d,cformats[f]);
        void *out[]={(__bridge void *)actual},*ref[]={(__bridge void *)reference};
        int one=1;float old[]={1,0,0,1},next[]={3,1,0,1};int size=f==1?8:4;
        assert(pm_pass_begin(d,1,out,&one,old,NULL,0,0,4,2,0,0,4,2,NULL));pm_pass_end(d);
        pm_copy_texture_to_buffer(d,out[0],0,0,0,0,4,2,buf,0,4*size);
        assert(pm_pass_begin(d,1,out,&one,next,NULL,0,0,4,2,1,0,2,1,NULL));pm_pass_end(d);
        assert(pm_pass_begin(d,1,ref,&one,next,NULL,0,0,4,2,0,0,4,2,NULL));pm_pass_end(d);
        pm_copy_texture_to_buffer(d,out[0],0,0,0,0,4,2,buf,64,4*size);
        pm_copy_texture_to_buffer(d,ref[0],0,0,0,0,4,2,buf,128,4*size);done(d,&seq);
        for(int i=0;i<8;i++)assert(memcmp(p+64+i*size,p+(i==1||i==2?128:0)+i*size,size)==0);
        printf("PASS color format=%lu matches full-clear conversion\n",(unsigned long)cformats[f]);
    }
    MTLPixelFormat formats[]={MTLPixelFormatDepth32Float,MTLPixelFormatDepth32Float_Stencil8,MTLPixelFormatDepth16Unorm};
    for(int f=0;f<3;f++){
        id<MTLTexture> depth=texture(d,formats[f]);void *dp=(__bridge void *)depth;
        id<MTLCommandBuffer> cb=current_cb(d);MTLRenderPassDescriptor *rp=new_pass_descriptor(d);
        rp.depthAttachment.texture=depth;rp.depthAttachment.loadAction=MTLLoadActionClear;rp.depthAttachment.clearDepth=.75;rp.depthAttachment.storeAction=MTLStoreActionStore;
        if(f==1){rp.stencilAttachment.texture=depth;rp.stencilAttachment.loadAction=MTLLoadActionClear;rp.stencilAttachment.clearStencil=73;rp.stencilAttachment.storeAction=MTLStoreActionStore;}
        id<MTLRenderCommandEncoder> enc=new_render_encoder_kind(d,cb,rp,1,"test seed");assert(enc);[enc endEncoding];
        assert(pm_pass_begin(d,0,NULL,NULL,NULL,dp,1,.25,4,2,1,0,2,1,NULL));pm_pass_end(d);
        int bpp=f==2?2:4;pm_copy_texture_to_buffer(d,dp,0,0,0,0,4,2,buf,0,4*bpp);
        if(f==1){id<MTLBlitCommandEncoder> blit=blit_encoder(d,2);assert(blit);PM_IF_M4(d) m4_blit_deps(d,&d->blitHaz,(id<MTL4ComputeCommandEncoder>)blit,tex_key_ptr(dp),buf,true);
            [blit copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(4,2,1) toBuffer:PM_OBJ(id<MTLBuffer>,buf) destinationOffset:64 destinationBytesPerRow:4 destinationBytesPerImage:8 options:MTLBlitOptionStencilFromDepthStencil];}
        done(d,&seq);
        for(int i=0;i<8;i++){bool in=i==1||i==2;if(f==2){uint16_t v;memcpy(&v,p+i*2,2);assert(abs((int)v-(in?16384:49151))<=1);}else{float v;memcpy(&v,p+i*4,4);assert(fabsf(v-(in?.25f:.75f))<.00001f);}if(f==1)assert(p[64+i]==73);}
        printf("PASS depth format=%lu and untouched stencil\n",(unsigned long)formats[f]);
    }
    {
        MTLTextureDescriptor *msd=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4 height:2 mipmapped:NO];
        msd.textureType=MTLTextureType2DMultisample;msd.sampleCount=4;msd.storageMode=MTLStorageModePrivate;msd.usage=MTLTextureUsageRenderTarget;
        id<MTLTexture> ms=[DEV_MTL(d) newTextureWithDescriptor:msd];
        if(ms){void *mc[]={(__bridge void *)ms};int one=1;
            assert(pm_pass_begin(d,1,mc,&one,green,NULL,0,0,4,2,1,0,2,1,NULL));pm_pass_end(d);done(d,&seq);
            puts("PASS declined color clear (MSAA) falls back instead of failing");}
        else puts("SKIP MSAA texture unavailable");
        double bad[]={2.0,-0.5,NAN};
        for(int k=0;k<3;k++){
            id<MTLTexture> depth=texture(d,MTLPixelFormatDepth32Float);void *dp=(__bridge void *)depth;
            assert(pm_pass_begin(d,0,NULL,NULL,NULL,dp,1,.5,4,2,0,0,4,2,NULL));pm_pass_end(d);
            assert(pm_pass_begin(d,0,NULL,NULL,NULL,dp,1,bad[k],4,2,1,0,2,1,NULL));pm_pass_end(d);
            pm_copy_texture_to_buffer(d,dp,0,0,0,0,4,2,buf,0,16);done(d,&seq);
            if(k<2)for(int i=0;i<8;i++){float v;memcpy(&v,p+i*4,4);bool in=i==1||i==2;assert(fabsf(v-(in?(k==0?1.f:0.f):.5f))<.00001f);}
            printf("PASS out-of-range depth case %d\n",k);
        }
    }
    pm_release(buf);
}
int main(void){@autoreleasepool{for(int m4=0;m4<2;m4++){PMDevice *d=pm_device_create(0,m4);if(!d){printf("SKIP Metal %d unavailable\n",m4?4:3);continue;}if(m4&&!d->m4){puts("SKIP Metal 4 unavailable");pm_device_destroy(d);continue;}printf("Metal %d\n",m4?4:3);test(d);pm_device_destroy(d);}}}
