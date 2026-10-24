// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework Foundation tools/test_lod_texture_render.m -o /tmp/pm-lod-textures
// /tmp/pm-lod-textures build/lod-texture-shaders
#import <Metal/Metal.h>
#import <Foundation/Foundation.h>
#include <assert.h>
#include <stdio.h>

static id<MTLLibrary> library(id<MTLDevice> device, NSString *path) {
    NSError *error=nil;
    NSString *source=[NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:&error];
    assert(source && !error);
    MTLCompileOptions *options=[MTLCompileOptions new]; options.languageVersion=MTLLanguageVersion3_0;
    id<MTLLibrary> lib=[device newLibraryWithSource:source options:options error:&error];
    if(!lib) fprintf(stderr,"%s: %s\n",path.UTF8String,error.description.UTF8String);
    assert(lib); return lib;
}
static NSData *render(id<MTLDevice> d,id<MTLCommandQueue> queue,id<MTLRenderPipelineState> pipeline,id<MTLTexture> atlas,id<MTLSamplerState> sampler,int size) {
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:size height:size mipmapped:NO];
    td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageRenderTarget;
    id<MTLTexture> target=[d newTextureWithDescriptor:td];
    MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture=target; pass.colorAttachments[0].loadAction=MTLLoadActionClear;
    pass.colorAttachments[0].storeAction=MTLStoreActionStore;
    id<MTLCommandBuffer> cb=[queue commandBuffer];
    id<MTLRenderCommandEncoder> enc=[cb renderCommandEncoderWithDescriptor:pass];
    [enc setRenderPipelineState:pipeline]; [enc setFragmentTexture:atlas atIndex:0];
    [enc setFragmentSamplerState:sampler atIndex:0];
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    assert(cb.status==MTLCommandBufferStatusCompleted);
    NSMutableData *data=[NSMutableData dataWithLength:size*size*4];
    [target getBytes:data.mutableBytes bytesPerRow:size*4 fromRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0];
    return data;
}
int main(int argc,char **argv) { @autoreleasepool {
    assert(argc==2); NSString *directory=[NSString stringWithUTF8String:argv[1]];
    id<MTLDevice> d=MTLCreateSystemDefaultDevice(); assert(d);
    NSUInteger compiled=0;
    for(NSString *file in [[NSFileManager defaultManager] contentsOfDirectoryAtPath:directory error:nil]) {
        if([file.pathExtension isEqualToString:@"metal"]) { library(d,[directory stringByAppendingPathComponent:file]); compiled++; }
    }
    printf("PASS: %lu translated Metal shader libraries compile\n",(unsigned long)compiled);
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:64 height:64 mipmapped:YES];
    td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> atlas=[d newTextureWithDescriptor:td];
    // Magenta adjacent sprites make any atlas leakage conspicuous in readback.
    for(NSUInteger mip=0;mip<4;mip++) {
        int side=64>>mip, min=side/4,max=side/2;
        NSMutableData *pixels=[NSMutableData dataWithLength:side*side*4]; uint8_t *p=pixels.mutableBytes;
        for(int y=0;y<side;y++) for(int x=0;x<side;x++) {
            int i=(y*side+x)*4; p[i]=255;p[i+1]=0;p[i+2]=255;p[i+3]=255;
            if(x>=min&&x<max&&y>=min&&y<max) {
                int shade=40+((x-min)/(side/8))*40+((y-min)/(side/8))*80;
                p[i]=shade;p[i+1]=shade;p[i+2]=shade;
            }
        }
        [atlas replaceRegion:MTLRegionMake2D(0,0,side,side) mipmapLevel:mip withBytes:p bytesPerRow:side*4];
    }
    MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];
    sd.minFilter=MTLSamplerMinMagFilterNearest;sd.magFilter=MTLSamplerMinMagFilterNearest;
    sd.mipFilter=MTLSamplerMipFilterNearest;sd.lodMaxClamp=3;
    id<MTLSamplerState> sampler=[d newSamplerStateWithDescriptor:sd]; id<MTLCommandQueue> queue=[d newCommandQueue];
    NSMutableDictionary<NSString *,id<MTLRenderPipelineState>> *pipelines=[NSMutableDictionary new];
    for(NSString *mode in @[@"repeat",@"stretch",@"reference",@"rgss"]) {
        id<MTLLibrary> vs=library(d,[directory stringByAppendingPathComponent:[NSString stringWithFormat:@"fixture-%@-vertex.metal",mode]]);
        id<MTLLibrary> fs=library(d,[directory stringByAppendingPathComponent:[NSString stringWithFormat:@"fixture-%@-fragment.metal",mode]]);
        MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
        pd.vertexFunction=[vs newFunctionWithName:@"main0"];pd.fragmentFunction=[fs newFunctionWithName:@"main0"];
        pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
        NSError *error=nil; id<MTLRenderPipelineState> pipeline=[d newRenderPipelineStateWithDescriptor:pd error:&error];
        if(!pipeline) fprintf(stderr,"%s\n",error.description.UTF8String); assert(pipeline); pipelines[mode]=pipeline;
    }
    for(int filtering=0;filtering<2;filtering++) {
    sd.minFilter=filtering?MTLSamplerMinMagFilterLinear:MTLSamplerMinMagFilterNearest;
    sd.magFilter=sd.minFilter;
    sampler=[d newSamplerStateWithDescriptor:sd];
    for(int size=64;size>=8;size/=2) {
        NSData *repeat=render(d,queue,pipelines[@"repeat"],atlas,sampler,size);
        NSData *reference=render(d,queue,pipelines[@"reference"],atlas,sampler,size);
        NSData *stretch=render(d,queue,pipelines[@"stretch"],atlas,sampler,size);
        if(!filtering) assert([repeat isEqualToData:reference]);
        assert(![repeat isEqualToData:stretch]);
        const uint8_t *pixels=repeat.bytes;
        for(int i=0;i<size*size;i++) assert(pixels[i*4]==pixels[i*4+1]&&pixels[i*4+1]==pixels[i*4+2]&&pixels[i*4+3]==255);
        NSData *rgss=render(d,queue,pipelines[@"rgss"],atlas,sampler,size);
        pixels=rgss.bytes;
        for(int i=0;i<size*size;i++) assert(pixels[i*4]==pixels[i*4+1]&&pixels[i*4+1]==pixels[i*4+2]&&pixels[i*4+3]==255);
        printf("PASS: %dx%d %s filter, differs from stretch; nearest/RGSS no adjacent-sprite bleed%s\n",size,size,filtering?"linear":"nearest",filtering?"":"; exact tiled reference match");
    }
    }
    return 0;
} }
