package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import io.github.imcinq.puremetal.lod.LodTextureRepeat;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

public class TestLodTextureShaders {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static String resource(String path) {
        try (var in = TestLodTextureShaders.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static class Source implements ShaderSource {
        Map<Identifier,CachedIncludeSource> includes = new HashMap<>();
        boolean custom;
        String customInclude;
        public String getShader(Identifier id, ShaderType type) {
            String text = resource("/assets/"+id.getNamespace()+"/shaders/"+id.getPath()+"."+(type == ShaderType.VERTEX ? "vsh" : "fsh"));
            return text != null && custom ? text + "\n// custom shader" : text;
        }
        public CachedIncludeSource getInclude(Identifier id) {
            return includes.computeIfAbsent(id, key -> {
                String text = resource("/assets/"+key.getNamespace()+"/shaders/include/"+key.getPath()+(key.getPath().endsWith(".glsl")?"":".glsl"));
                if(text!=null && key.toString().equals(customInclude)) text += "\n// changed include";
                return text == null ? CachedIncludeSource.createError("Missing "+key) : CachedIncludeSource.create(key,text);
            });
        }
        public void close() { includes.values().forEach(CachedIncludeSource::close); }
    }
    static void rectangles() {
        for (Direction d : Direction.values()) for (int rotation=0;rotation<4;rotation++) for (int mirror=0;mirror<2;mirror++) {
            Vector3f[] p = new Vector3f[4]; long[] uv = new long[4];
            int[][] corners={{0,0},{0,1},{1,1},{1,0}};
            for(int i=0;i<4;i++) {
                float a=corners[i][0],b=corners[i][1], plane=d.getAxisDirection()==Direction.AxisDirection.POSITIVE?1:0;
                p[i]=switch(d.getAxis()) {case X->new Vector3f(plane,a,b);case Y->new Vector3f(a,plane,b);case Z->new Vector3f(a,b,plane);};
                if(mirror!=0) a=1-a;
                for(int r=0;r<rotation;r++) {float old=a;a=1-b;b=old;}
                float u=.25f+a*.25f,v=.5f+b*.25f;
                uv[i]=((long)Float.floatToRawIntBits(u)<<32)|(Float.floatToRawIntBits(v)&0xffffffffL);
                long marked=LodTextureRepeat.mark(uv[i]);
                check(Float.intBitsToFloat((int)(marked>>>32))-2==u,"Marker must preserve UV");
                check((int)marked==(int)uv[i],"Marker must preserve V");
            }
            var q=new BakedQuad(p[0],p[1],p[2],p[3],uv[0],uv[1],uv[2],uv[3],d,null);
            check(LodTextureRepeat.rectangle(q,.25f,.5f,.5f,.75f),"Rotated/mirrored full sprite rejected");
            check(!LodTextureRepeat.rectangle(q,0,0,1,1),"Sub-sprite UV must not repeat");
        }
        System.out.println("PASS: all face axes, UV rotations/mirrors, marker preservation and sub-sprite rejection");
    }
    public static void main(String[] args) throws Exception {
        rectangles();
        check(io.github.imcinq.puremetal.perf.TerrainShaderProof.eligibleShape(DepthStencilState.DEFAULT,PrimitiveTopology.QUADS,PolygonMode.FILL),"Actual stock reverse-depth quad pipeline must be eligible");
        check(!io.github.imcinq.puremetal.perf.TerrainShaderProof.eligibleShape(DepthStencilState.DEFAULT,PrimitiveTopology.QUADS,PolygonMode.WIREFRAME),"Wireframe must fall back");
        check(!io.github.imcinq.puremetal.perf.TerrainShaderProof.eligibleShape(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true,1,0),PrimitiveTopology.QUADS,PolygonMode.FILL),"Depth bias must fall back");
        for(String id:List.of("minecraft:core/terrain","sodium:blocks/block_layer_opaque")) {
            var builder=RenderPipeline.builder().withLocation(Identifier.parse("puremetal:probe-fixture")).withVertexShader(Identifier.parse(id)).withFragmentShader(Identifier.parse(id))
                .withBindGroupLayout(new BindGroupLayout(List.of())).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
            if(id.startsWith("sodium:")) builder.withShaderDefine("USE_VERTEX_COMPRESSION");
            var pipeline=builder.build();
            int expected=id.startsWith("sodium:")?2:1;
            try(var source=new Source();var proof=io.github.imcinq.puremetal.perf.TerrainShaderProof.enter(pipeline,LodTextureRepeat.wrap(source))) {
                if(io.github.imcinq.puremetal.perf.TerrainShaderProof.current()!=expected) {
                    var field=io.github.imcinq.puremetal.perf.TerrainShaderProof.class.getDeclaredField(expected==2?"SODIUM":"VANILLA");field.setAccessible(true);
                    @SuppressWarnings("unchecked") var pins=(Map<String,String>)field.get(null);
                    for(var entry:pins.entrySet()) {
                        var include=source.getInclude(Identifier.parse(entry.getKey()));
                        var result=org.lwjgl.util.shaderc.ShadercIncludeResult.create(include.includeResultPtr());
                        long address=org.lwjgl.system.MemoryUtil.memGetAddress(result.address()+org.lwjgl.util.shaderc.ShadercIncludeResult.CONTENT);
                        String content=StandardCharsets.UTF_8.decode(org.lwjgl.system.MemoryUtil.memByteBuffer(address,(int)result.content_length())).toString();
                        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
                        check(digest.equals(entry.getValue()),"Include fingerprint mismatch: "+entry.getKey()+" actual="+digest);
                    }
                }
                check(io.github.imcinq.puremetal.perf.TerrainShaderProof.current()==expected,"Stock transform/includes must validate: "+id);
            }
            check(io.github.imcinq.puremetal.perf.TerrainShaderProof.current()==0,"Proof scope must restore");
            try(var source=new Source()) {
                source.custom=true;
                try(var proof=io.github.imcinq.puremetal.perf.TerrainShaderProof.enter(pipeline,source)) {
                    check(io.github.imcinq.puremetal.perf.TerrainShaderProof.current()==0,"Changed shader must fall back");
                }
            }
            try(var source=new Source()) {
                source.customInclude=id.startsWith("sodium:")?"sodium:chunk_vertex.glsl":"minecraft:projection.glsl";
                try(var proof=io.github.imcinq.puremetal.perf.TerrainShaderProof.enter(pipeline,source)) {
                    check(io.github.imcinq.puremetal.perf.TerrainShaderProof.current()==0,"Changed vertex include must fall back");
                }
            }
        }
        System.out.println("PASS per-compilation terrain transform proof: vanilla/Sodium stock and own LOD patch, custom top-level/include fallback, scope restore");
        // GlslCompiler asks for backend workarounds; a stub avoids initializing a window/device.
        var deviceField=com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        var info=new com.mojang.renderpearl.api.device.DeviceInfo("headless","Apple","test",true,"Metal",1,null,null,Set.of(),
            new com.mojang.renderpearl.api.device.HintsAndWorkarounds(false,false,true,false),null);
        var stub=java.lang.reflect.Proxy.newProxyInstance(TestLodTextureShaders.class.getClassLoader(),
            new Class<?>[]{com.mojang.renderpearl.api.device.GpuDevice.class},(proxy,method,arguments)-> {
                if(method.getName().equals("getDeviceInfo")) return info;
                throw new AssertionError("Unexpected headless device call "+method);
            });
        deviceField.set(null,stub);
        Path out=Path.of(args[0]); Files.createDirectories(out);
        var emptyBindings=new MetalPipeline.Bindings(new UniformType[0],new int[0],new int[0],new int[0]);
        try(var original=new Source(); var compiler=new GlslCompiler(true,true)) {
            ShaderSource source=LodTextureRepeat.wrap(original);
            check(LodTextureRepeat.ready(false)&&LodTextureRepeat.ready(true),"Stock sources must enable both prototypes");
            int variants=0;
            for(String name:List.of("minecraft:core/terrain","sodium:blocks/block_layer_opaque")) {
                boolean sodium=name.startsWith("sodium:"); Identifier id=Identifier.parse(name);
                for(String variant:List.of("solid","cutout","oit","alpha")) for(ShaderType type:ShaderType.values()) {
                    var defines=ShaderDefines.builder();
                    if(sodium) defines.define("USE_VERTEX_COMPRESSION").define("USE_FOG");
                    else defines.define("MULTIDRAW_TERRAIN");
                    if(variant.equals("cutout")) defines.define("ALPHA_CUTOUT",0.1f);
                    if(variant.equals("oit")) defines.define("OIT").define("OIT_ACCUMULATE").define("OIT_COEFF_COUNT",4).define("OIT_WAVELET_RANK",2);
                    if(variant.equals("alpha")) defines.define("OIT_ALPHA_ONLY");
                    String text=source.getShader(id,type);
                    check(text.contains("pmRepeatFace"),"Actual shader source was not patched");
                    try(var module=compiler.compileToSpv(name,text,type,defines.build(),source)) {
                        var msl=MetalShaderTranslator.translate(new BackendRenderPipeline.CreateInfo.Shader(name,"main",module),emptyBindings,false);
                        Files.writeString(out.resolve((sodium?"sodium":"vanilla")+"-"+variant+"-"+type.getName()+".metal"),msl.source());
                    }
                    variants++;
                }
            }
            System.out.println("PASS: "+variants+" actual vanilla/Sodium GLSL -> SPIR-V -> MSL variants");
            // Export the exact helper in a small offscreen shader, not a separate reimplementation.
            String vertex="""
                #version 460
                layout(location=0) out vec3 localPos;
                layout(location=1) out vec2 uv;
                void main() {
                    vec2 corners[6]=vec2[](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));
                    vec2 p=corners[gl_VertexIndex]; localPos=vec3(p*2.0,0.0); uv=vec2(.25,.25)+p*.25;
                    gl_Position=vec4(p*2.0-1.0,0.0,1.0);
                }
                """;
            // Reference uses explicit unwrapped one-block UV gradients, wrapping only the sample coordinates.
            String functions="""
                vec4 sampleNearest(sampler2D t, vec2 uv, vec2 size) { return texture(t,uv); }
                vec4 sampleRGSS(sampler2D t, vec2 uv, vec2 size) { return texture(t,uv); }
                const bool PM_RGSS_EARLY_OUT = true;
                vec4 pmSampleRGSS(sampler2D t, vec2 uv, vec2 size) { return sampleRGSS(t,uv,size); }
                """;
            for(String mode:List.of("repeat","stretch","reference","rgss")) {
                String call=mode.equals("reference") ? "textureGrad(Atlas,vec2(.25)+fract(localPos.xy)*.25,dFdx(uv)*2.0,dFdy(uv)*2.0)"
                    : "pmSampleLod(Atlas,uv,vec2(1.0/64.0),"+mode.equals("rgss")+",localPos,"+(mode.equals("stretch")?"0u":"1u")+")";
                String fragment="#version 460\nlayout(binding=0) uniform sampler2D Atlas;\nlayout(location=0) in vec3 localPos;\nlayout(location=1) in vec2 uv;\nlayout(location=0) out vec4 color;\n"+functions+LodTextureRepeat.helper()+"\nvoid main(){color="+call+";}";
                for(ShaderType type:ShaderType.values()) try(var module=compiler.compileToSpv("fixture",type==ShaderType.VERTEX?vertex:fragment,type,ShaderDefines.EMPTY,original)) {
                    var msl=MetalShaderTranslator.translate(new BackendRenderPipeline.CreateInfo.Shader("fixture","main",module),emptyBindings,false);
                    Files.writeString(out.resolve("fixture-"+mode+"-"+type.getName()+".metal"),msl.source());
                }
            }
            // Optional before/after experiment uses the actual Sodium sampling functions, including RGSS.
            String referencePath=System.getProperty("puremetal.lodShaderReference");
            if(referencePath!=null) {
                String stock=original.getShader(Identifier.parse("sodium:blocks/block_layer_opaque"),ShaderType.FRAGMENT);
                String sampling=stock.substring(stock.indexOf("vec4 sampleNearest("),stock.indexOf("void main()"));
                String before=Files.readString(Path.of(referencePath));
                for(String scene:List.of("ordinary","repeat","mixed","degenerate")) {
                    String vs=vertex.replace("layout(location=1) out vec2 uv;", "layout(location=1) out vec2 uv;\nlayout(location=2) flat out uint repeatFace;");
                    String marker=scene.equals("ordinary")?"0u":scene.equals("mixed")?"uint(gl_VertexIndex/3)":"1u";
                    vs=vs.replace("gl_Position=", "repeatFace="+marker+"; gl_Position=");
                    if(scene.equals("degenerate")) vs=vs.replace("localPos=vec3(p*2.0,0.0)", "localPos=vec3(0.0)");
                    for(boolean rgss:List.of(false,true)) for(boolean old:List.of(true,false)) {
                        String name="perf-"+(old?"before":"after")+"-"+scene+"-"+(rgss?"rgss":"nearest");
                        String fs="#version 460\nlayout(binding=0) uniform sampler2D Atlas;\nlayout(location=0) in vec3 localPos;\nlayout(location=1) in vec2 uv;\nlayout(location=2) flat in uint repeatFace;\nlayout(location=0) out vec4 color;\n"
                            +sampling+(old?before:LodTextureRepeat.helper())+"\nvoid main(){color=pmSampleLod(Atlas,uv,vec2(1.0/64.0),"+rgss+",localPos,repeatFace);}";
                        for(ShaderType type:ShaderType.values()) try(var module=compiler.compileToSpv(name,type==ShaderType.VERTEX?vs:fs,type,ShaderDefines.EMPTY,original)) {
                            var msl=MetalShaderTranslator.translate(new BackendRenderPipeline.CreateInfo.Shader(name,"main",module),emptyBindings,false);
                            Files.writeString(out.resolve(name+"-"+type.getName()+".metal"),msl.source());
                        }
                    }
                }
                System.out.println("PASS: before/after GPU fixtures exported with actual Sodium nearest/RGSS functions");
            }
            original.custom=true;
            var custom=new Source(); custom.custom=true;
            var fallback=LodTextureRepeat.wrap(custom);
            check(!LodTextureRepeat.ready(false)&&!LodTextureRepeat.ready(true),"Custom shader override must disable markers");
            var id=Identifier.parse("minecraft:core/terrain");
            check(fallback.getShader(id,ShaderType.VERTEX).equals(custom.getShader(id,ShaderType.VERTEX)),"Custom source changed");
            custom.close();
            System.out.println("PASS: custom shader override falls back unchanged");
        }
    }
}
