import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

public final class TestTerrainDiagnostics {
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    static Method hook(Class<?> type, String name) {
        // MixinExtras also generates sugar bridge methods with a different argument list.
        var method=java.util.Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().endsWith(name)).findFirst().orElseThrow();
        method.setAccessible(true);return method;
    }
    static Object allocate(Class<?> type) throws Exception {
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        return ((sun.misc.Unsafe)field.get(null)).allocateInstance(type);
    }
    public static void main(String[] args) throws Exception {
        boolean sodium=args[0].equals("sodium"), enabled=TerrainDrawProfile.ENABLED;
        String[] targets=sodium?new String[]{"net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer",
            "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers",
            "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager",
            "net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage"}:
            new String[]{"net.minecraft.client.renderer.LevelRenderer","net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawSeparate",
                         "net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawIndirect","net.minecraft.client.renderer.chunk.CompiledSectionMesh"};
        for(String name:targets) {
            Class<?> type=Class.forName(name,false,TestTerrainDiagnostics.class.getClassLoader());
            String expected=sodium?(name.endsWith("DefaultChunkRenderer")?"puremetal$batchSubmission":name.endsWith("ChunkBuildBuffers")?"puremetal$measureBounds":name.endsWith("RenderRegionManager")?"puremetal$uploadedBounds":"puremetal$invalidateBounds"):
                name.endsWith("LevelRenderer")?"puremetal$directMetadata":name.endsWith("DrawSeparate")?"puremetal$directSubmission":name.endsWith("DrawIndirect")?"puremetal$indirectSubmission":"puremetal$measureBounds";
            boolean present=java.util.Arrays.stream(type.getDeclaredMethods()).anyMatch(m->m.getName().contains(expected));
            require(present==enabled);
        }
        if(!enabled) {System.out.println("PASS terrain diagnostics absent when disabled");return;}
        if(io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED) {
            var builder=Class.forName("com.mojang.renderpearl.frontend.shaders.PipelineBuilder",false,TestTerrainDiagnostics.class.getClassLoader());
            hook(builder,"puremetal$shaderProof");
        }
        // Identity matching must not combine distinct record-like owners that compare equal.
        Object a=new String("equal"),b=new String("equal");
        TerrainDrawProfile.attach(a,List.of(new TerrainDrawProfile.Range(16,32,48,0,4)));
        require(TerrainDrawProfile.metadata(b).isEmpty());
        var event=TerrainDrawProfile.start("fixture","solid",2);event.match(a,1,2);
        require(event.matchedCommands==2 && event.sectionRanges==1 && event.minX==16 && event.maxZ==48);
        TerrainDrawProfile.attach(a,List.of());require(TerrainDrawProfile.metadata(a).isEmpty());
        var measuredBounds=new io.github.imcinq.puremetal.perf.MeshBounds(-1,-1,-1,17,17,17);
        TerrainDrawProfile.attach(a,List.of(new TerrainDrawProfile.Range(16,32,48,0,4,measuredBounds)));
        try(var outer=io.github.imcinq.puremetal.perf.TerrainProbeScope.batch(a,1,3,true,0,0,0)) {
            require(outer.consume(1).getFirst().bounds()==measuredBounds);
            try(var inner=io.github.imcinq.puremetal.perf.TerrainProbeScope.batch(b,0,1,false,0,0,0)) {
                require(io.github.imcinq.puremetal.perf.TerrainProbeScope.current()==inner && inner.consume(1).isEmpty());
            }
            require(io.github.imcinq.puremetal.perf.TerrainProbeScope.current()==outer && outer.consume(2).size()==1);
        }
        require(io.github.imcinq.puremetal.perf.TerrainProbeScope.current()==null);
        if(!sodium) checkVanilla();else checkSodium();
        // JFR fields describe CPU submission/metadata, not measured hidden geometry or GPU time.
        var file=Files.createTempFile("pm-terrain-diagnostics-",".jfr");
        try(var recording=new Recording()) {
            recording.enable("puremetal.TerrainDraw");recording.enable("puremetal.TerrainProbe");recording.start();
            var recorded=TerrainDrawProfile.start("fixture","solid",2);recorded.submitted=true;recorded.match(b,0,2);recorded.finish();
            io.github.imcinq.puremetal.bench.TerrainProbeProfile.record(100,25,3,12000,1,1);
            recording.stop();recording.dump(file);
        }
        var events=RecordingFile.readAllEvents(file);Files.delete(file);
        var drawEvent=events.stream().filter(e->e.getEventType().getName().equals("puremetal.TerrainDraw")).findFirst().orElseThrow();
        var probeEvent=events.stream().filter(e->e.getEventType().getName().equals("puremetal.TerrainProbe")).findFirst().orElseThrow();
        require(events.size()==2 && drawEvent.getInt("commands")==2 && drawEvent.getInt("matchedCommands")==0
            && drawEvent.getBoolean("submitted") && drawEvent.getString("renderer").equals("fixture"));
        require(probeEvent.getLong("queries")==100 && probeEvent.getLong("hidden")==25 && probeEvent.getLong("dropped")==3
            && probeEvent.getLong("computeSpanNs")==12000 && probeEvent.getLong("completedProbes")==1 && probeEvent.getLong("validSpans")==1);
        System.out.println("PASS terrain diagnostics: identity, replacement, command subranges, actual transformed handlers, JFR round-trip");
    }
    static void checkVanilla() throws Exception {
        var renderer=Class.forName("net.minecraft.client.renderer.LevelRenderer",false,TestTerrainDiagnostics.class.getClassLoader());
        Object target=allocate(renderer);
        var infos=List.of(new DynamicGpuData.ChunkSectionInfo(16,0,32,1),new DynamicGpuData.ChunkSectionInfo(-48,16,0,1));
        int[] calls={0};
        Operation<RenderPass.Draw<?>> direct=args->{calls[0]++;require((int)args[4]==3 && (int)args[5]==6);return new RenderPass.Draw<>(0,null,null,null,3,6,9,null);};
        var source=new DynamicGpuData.IndexedDraw(6,1,3,9,1);
        Object draw=hook(renderer,"puremetal$directMetadata").invoke(target,0,null,null,null,3,6,9,null,direct,infos,1,source);
        require(calls[0]==1 && TerrainDrawProfile.metadata(draw).equals(List.of(new TerrainDrawProfile.Range(-48,16,0,0,1))));
        var commands=List.of(new DynamicGpuData.IndexedDraw(6,1,0,0,1),new DynamicGpuData.IndexedDraw(6,1,6,4,0));
        Operation<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> indirect=args->{calls[0]++;return new ChunkSectionsToRender.GpuMultiDrawIndexedIndirect(null,null,null,null,(int)args[4]);};
        Object group=hook(renderer,"puremetal$indirectMetadata").invoke(target,null,null,null,null,2,indirect,infos,List.of(commands),1);
        require(calls[0]==2 && TerrainDrawProfile.metadata(group).get(0).x()==-48 && TerrainDrawProfile.metadata(group).get(1).x()==16);
        var event=TerrainDrawProfile.start("fixture","solid",1);event.match(group,1,1);
        require(event.matchedCommands==1 && event.sectionRanges==1 && event.minX==16);
        // Execute actual submission hook and propagate an original failure unchanged, without mutating arguments.
        var type=Class.forName("net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawIndirect",false,TestTerrainDiagnostics.class.getClassLoader());
        Operation<Void> submit=args->{require(args[0]==null && args[1]==null && (int)args[2]==1);calls[0]++;return null;};
        hook(type,"puremetal$indirectSubmission").invoke(allocate(type),null,null,1,submit,net.minecraft.client.renderer.chunk.ChunkSectionLayer.values()[0],group,1);
        require(calls[0]==3);
    }
    static void checkSodium() throws Exception {
        var storage=new net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage(false);
        var measured=(io.github.imcinq.puremetal.perf.LayerBounds)(Object)storage;
        var bounds=new io.github.imcinq.puremetal.perf.MeshBounds(-2,-3,-4,18,19,20);
        measured.puremetal$setBounds(7,bounds);require(measured.puremetal$bounds(7)==bounds);
        storage.removeVertexData(7);require(measured.puremetal$bounds(7)==null);
        measured.puremetal$setBounds(7,bounds);storage.removeData(7);require(measured.puremetal$bounds(7)==null);
        measured.puremetal$setBounds(8,bounds);storage.delete();require(measured.puremetal$bounds(8)==null);
        // Execute the actual upload wrapper: metadata belongs to the uploaded segments, and absence replaces old bounds.
        var manager=Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager",false,TestTerrainDiagnostics.class.getClassLoader());
        var uploaded=new net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage(false);
        var uploadBounds=(io.github.imcinq.puremetal.perf.LayerBounds)(Object)uploaded;
        int[] segments={1,2,3,4,5,6,7};int[] forwarded={0};
        TerrainDrawProfile.attach(segments,List.of(new TerrainDrawProfile.Range(0,0,0,0,1,bounds)));
        Operation<Void> upload=args->{require(args[0]==uploaded && (int)args[1]==9 && args[2]==null && args[3]==segments);forwarded[0]++;return null;};
        hook(manager,"puremetal$uploadedBounds").invoke(allocate(manager),uploaded,9,null,segments,upload);
        require(forwarded[0]==1 && uploadBounds.puremetal$bounds(9)==bounds);
        TerrainDrawProfile.attach(segments,List.of());
        hook(manager,"puremetal$uploadedBounds").invoke(allocate(manager),uploaded,9,null,segments,upload);
        require(forwarded[0]==2 && uploadBounds.puremetal$bounds(9)==null);uploaded.delete();
        var type=Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer",false,TestTerrainDiagnostics.class.getClassLoader());
        var batchType=Class.forName("net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch",false,type.getClassLoader());
        Object batch=new RecordingBatch();
        var size=batchType.getField("size");
        try {
            int[] calls={0};
            Operation<Void> operation=args->{require(args[0]==batch && (long)args[1]==123L && (int)args[2]==7);calls[0]++;try{size.setInt(batch,size.getInt(batch)+2);}catch(Exception e){throw new RuntimeException(e);}return null;};
            hook(type,"puremetal$sharedRanges").invoke(null,batch,123L,7,operation,2,-1,3,null,0);
            hook(type,"puremetal$localRanges").invoke(null,batch,123L,7,operation,4,5,6,null,0);
            require(calls[0]==2 && TerrainDrawProfile.metadata(batch).equals(List.of(
                new TerrainDrawProfile.Range(32,-16,48,0,2),new TerrainDrawProfile.Range(64,80,96,2,2))));
            var layer=new net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass(
                net.minecraft.client.renderer.chunk.ChunkSectionLayer.values()[0],false,false);
            Operation<Void> submit=args->{require(args[0]==batch && args[1]==null);calls[0]++;return null;};
            var region=allocate(net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion.class);
            hook(type,"puremetal$batchSubmission").invoke(allocate(type),batch,null,submit,layer,region);
            require(calls[0]==3);
            Object callback=new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("fixture",false);
            hook(type,"puremetal$resetBatch").invoke(null,callback,batch);
            require(TerrainDrawProfile.metadata(batch).isEmpty());
        } finally {batchType.getMethod("delete").invoke(batch);}
    }
    public static final class RecordingBatch extends net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch {
        @Override public void put(int a,int b,int c,long d) { throw new AssertionError("unexpected native batch use"); }
        @Override public void draw(net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext context) { throw new AssertionError("unexpected GPU draw"); }
        @Override public void delete() {}
    }
}
