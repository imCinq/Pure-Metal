// Uses fixtures exported with PM_LOD_SHADER_REFERENCE by test_lod_textures.py.
// clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework Foundation tools/bench_lod_shader.m -o /tmp/pm-lod-shader-bench
#define main lod_pixel_test_main
#include "test_lod_texture_render.m"
#undef main

static id<MTLRenderPipelineState> pipeline(id<MTLDevice> d,NSString *directory,NSString *name) {
    id<MTLLibrary> vs=library(d,[directory stringByAppendingPathComponent:[name stringByAppendingString:@"-vertex.metal"]]);
    id<MTLLibrary> fs=library(d,[directory stringByAppendingPathComponent:[name stringByAppendingString:@"-fragment.metal"]]);
    MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
    pd.vertexFunction=[vs newFunctionWithName:@"main0"]; pd.fragmentFunction=[fs newFunctionWithName:@"main0"];
    pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
    NSError *error=nil; id<MTLRenderPipelineState> result=[d newRenderPipelineStateWithDescriptor:pd error:&error];
    if(!result) fprintf(stderr,"%s\n",error.description.UTF8String); assert(result); return result;
}
static int compareDouble(const void *a,const void *b) {
    double x=*(const double *)a,y=*(const double *)b; return (x>y)-(x<y);
}
static double measure(id<MTLCommandQueue> queue,id<MTLRenderPipelineState> pipeline,id<MTLTexture> atlas,id<MTLSamplerState> sampler,id<MTLTexture> target) {
    id<MTLCommandBuffer> cb=[queue commandBuffer];
    // Separate stored passes prevent opaque overdraw elimination from reducing this to one shaded draw.
    for(int passIndex=0;passIndex<32;passIndex++) {
        MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture=target; pass.colorAttachments[0].loadAction=MTLLoadActionClear;
        pass.colorAttachments[0].storeAction=MTLStoreActionStore;
        id<MTLRenderCommandEncoder> enc=[cb renderCommandEncoderWithDescriptor:pass];
        [enc setRenderPipelineState:pipeline]; [enc setFragmentTexture:atlas atIndex:0]; [enc setFragmentSamplerState:sampler atIndex:0];
        [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6]; [enc endEncoding];
    }
    [cb commit]; [cb waitUntilCompleted]; assert(cb.status==MTLCommandBufferStatusCompleted);
    return (cb.GPUEndTime-cb.GPUStartTime)*1000.0/32.0;
}
int main(int argc,char **argv) { @autoreleasepool {
    assert(argc==2||argc==3); BOOL pixelsOnly=argc==3;
    NSString *directory=[NSString stringWithUTF8String:argv[1]];
    id<MTLDevice> d=MTLCreateSystemDefaultDevice(); assert(d);
    id<MTLCommandQueue> queue=[d newCommandQueue];
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:64 height:64 mipmapped:YES];
    td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> atlas=[d newTextureWithDescriptor:td];
    for(int mip=0;mip<=6;mip++) {
        int size=64>>mip; NSMutableData *data=[NSMutableData dataWithLength:size*size*4]; uint8_t *p=data.mutableBytes;
        for(int i=0;i<size*size;i++) {
            p[i*4]=(i*37+mip*19)%256; p[i*4+1]=(i*13+mip*29)%256;
            p[i*4+2]=(i*71+mip*7)%256; p[i*4+3]=255;
        }
        [atlas replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:mip withBytes:p bytesPerRow:size*4];
    }
    for(NSString *scene in @[@"ordinary",@"repeat",@"mixed",@"degenerate"]) for(NSString *sampling in @[@"nearest",@"rgss"]) {
        NSString *suffix=[NSString stringWithFormat:@"%@-%@",scene,sampling];
        id<MTLRenderPipelineState> before=pipeline(d,directory,[@"perf-before-" stringByAppendingString:suffix]);
        id<MTLRenderPipelineState> after=pipeline(d,directory,[@"perf-after-" stringByAppendingString:suffix]);
        MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new]; sd.mipFilter=MTLSamplerMipFilterLinear; sd.lodMaxClamp=3;
        for(int filtering=0;filtering<2;filtering++) {
            sd.minFilter=filtering?MTLSamplerMinMagFilterLinear:MTLSamplerMinMagFilterNearest; sd.magFilter=sd.minFilter;
            id<MTLSamplerState> sampler=[d newSamplerStateWithDescriptor:sd];
            for(NSNumber *dimension in @[@8,@16,@32,@64,@257]) {
                int size=dimension.intValue;
                NSData *old=render(d,queue,before,atlas,sampler,size),*new=render(d,queue,after,atlas,sampler,size);
                if(![old isEqualToData:new]) fprintf(stderr,"Pixel mismatch %s size%d filter%d\n",suffix.UTF8String,size,filtering);
                assert([old isEqualToData:new]);
            }
        }
        printf("PASS exact before/after pixels: %s, 5 sizes, nearest/linear filters\n",suffix.UTF8String);
        if(pixelsOnly) continue;
        sd.minFilter=MTLSamplerMinMagFilterNearest; sd.magFilter=sd.minFilter;
        id<MTLSamplerState> sampler=[d newSamplerStateWithDescriptor:sd];
        MTLTextureDescriptor *targetDesc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:2048 height:2048 mipmapped:NO];
        targetDesc.storageMode=MTLStorageModePrivate; targetDesc.usage=MTLTextureUsageRenderTarget;
        id<MTLTexture> target=[d newTextureWithDescriptor:targetDesc];
        // Alternate ABBA to limit order drift; omit initial warmup and report medians/ranges.
        double oldTimes[24],newTimes[24]; int oldN=0,newN=0;
        for(int block=-2;block<12;block++) for(int step=0;step<4;step++) {
            @autoreleasepool {
                BOOL old=(step==0||step==3); double ms=measure(queue,old?before:after,atlas,sampler,target);
                if(block>=0) { if(old) oldTimes[oldN++]=ms; else newTimes[newN++]=ms; }
            }
        }
        qsort(oldTimes,24,sizeof(double),compareDouble); qsort(newTimes,24,sizeof(double),compareDouble);
        double oldMedian=(oldTimes[11]+oldTimes[12])*.5,newMedian=(newTimes[11]+newTimes[12])*.5;
        printf("GPU fixture %s 2048sq, 32 stored passes/buffer, ms/pass before %.4f [%.4f,%.4f] after %.4f [%.4f,%.4f], time reduction %.2f%%\n",
            suffix.UTF8String,oldMedian,oldTimes[0],oldTimes[23],newMedian,newTimes[0],newTimes[23],100.0*(oldMedian-newMedian)/oldMedian);
    }
    printf("Fixture timings are whole command-buffer GPU durations, not gameplay FPS or terrain stage attribution.\n");
    return 0;
} }
