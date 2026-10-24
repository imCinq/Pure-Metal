// Headless Fabric transformation check. Calls Knot.init, never GameProvider.launch or Minecraft.main.
// Run separately with and without Sodium on the development runtime classpath; use a temporary game directory.
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

public final class TestLodMixins {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = new Knot(EnvType.CLIENT).init(new String[]{"--gameDir", args[0]});
        String[] paletteArgs = Boolean.getBoolean("puremetal.disablePaletteScratch") ? new String[]{"baseline"} : new String[0];
        Class.forName("TestPaletteScratch", true, loader).getMethod("main", String[].class).invoke(null, (Object) paletteArgs);
        Class<?> state = Class.forName("io.github.imcinq.puremetal.lod.LodState", false, loader);
        Class<?> config = Class.forName("io.github.imcinq.puremetal.lod.TerrainLodConfig", true, loader);
        boolean expectedEnabled = args.length < 3 || !args[2].equals("off");
        if ((boolean) config.getMethod("enabled").invoke(null) != expectedEnabled) {
            throw new AssertionError("LOD default or saved override was not respected");
        }
        System.out.println("PASS: LOD " + (expectedEnabled ? "defaults on" : "respects saved off"));
        boolean sodium = args.length > 1 && args[1].equals("sodium");
        String[] targets = sodium ? new String[]{
            "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask",
            "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
            "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection",
            "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput"
        } : new String[]{
            "net.minecraft.client.renderer.chunk.SectionCompiler",
            "net.minecraft.client.renderer.chunk.SectionCompiler$Results",
            "net.minecraft.client.renderer.chunk.CompiledSectionMesh",
            "net.minecraft.client.renderer.extract.LevelExtractor"
        };
        for (String name : targets) {
            Class<?> target = Class.forName(name, false, loader);
            // Force verification/resolution of transformed methods without running class initializers.
            target.getDeclaredMethods();
            boolean hook = java.util.Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("puremetal$"));
            if (!hook) throw new AssertionError("LOD hook missing: " + name);
            if (name.endsWith("Results") || name.endsWith("CompiledSectionMesh") || name.endsWith("RenderSection") || name.endsWith("ChunkBuildOutput")) {
                if (!state.isAssignableFrom(target)) throw new AssertionError("LOD state missing: " + name);
            }
            System.out.println("PASS transformed " + name);
        }
        if (sodium) checkSodiumCondition(loader, args[0]);
        Class.forName("TestTerrainDiagnostics", true, loader).getMethod("main", String[].class)
            .invoke(null, (Object) new String[]{sodium ? "sodium" : "vanilla"});
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
        boolean frameHook = java.util.Arrays.stream(minecraft.getDeclaredMethods())
            .anyMatch(m -> m.getName().contains("puremetal$benchFrame"));
        if (frameHook != Boolean.getBoolean("puremetal.profile")) {
            throw new AssertionError("Gameplay frame profiling hook enablement incorrect");
        }
        System.out.println("PASS: gameplay frame profiling hook " + (frameHook ? "enabled" : "disabled") + " without launching the game");
        Class<?> frontend = Class.forName("com.mojang.renderpearl.frontend.FrontendGpuDevice", false, loader);
        boolean textureHook = java.util.Arrays.stream(frontend.getDeclaredMethods())
            .anyMatch(m -> m.getName().contains("puremetal$repeatSource"));
        boolean textureExpected = Boolean.parseBoolean(System.getProperty("puremetal.experimentalLodTextures", "true"))
            || Boolean.parseBoolean(System.getProperty("puremetal.rgssEarlyOut", "true"));
        if (textureHook != textureExpected) {
            throw new AssertionError("LOD/RGSS shader hook enablement incorrect");
        }
        System.out.println("PASS: LOD/RGSS source hook " + (textureHook ? "enabled" : "disabled"));
        System.out.println("PASS: " + (sodium ? "Sodium" : "vanilla") + " LOD hooks transformed without launching the game");
    }

    static void checkSodiumCondition(ClassLoader loader, String gameDir) throws Exception {
        String name="net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask";
        var node=new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(gameDir,
            ".mixin.out/class/"+name.replace('.','/')+".class"))).accept(node,0);
        var hook=node.methods.stream().filter(m->m.name.contains("puremetal$coarseBlock")).findFirst().orElseThrow();
        if(!hook.desc.endsWith(")Z") || hook.desc.contains("wrapoperation/Operation")) throw new AssertionError("Allocating operation hook retained");
        for(var method:node.methods) for(var instruction:method.instructions) {
            if(instruction instanceof org.objectweb.asm.tree.TypeInsnNode t && t.getOpcode()==org.objectweb.asm.Opcodes.ANEWARRAY
                && t.desc.equals("java/lang/Object") && method.name.contains("coarseBlock")) throw new AssertionError("LOD hook allocates Object[]");
        }
        // Execute the actual transformed condition for inactive, uncovered and covered non-origin blocks.
        Class<?> target=Class.forName(name,false,loader);
        var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        Object task=unsafe.allocateInstance(target);
        var method=java.util.Arrays.stream(target.getDeclaredMethods()).filter(m->m.getName().contains("puremetal$coarseBlock")).findFirst().orElseThrow();
        method.setAccessible(true);
        Class<?> posClass=Class.forName("net.minecraft.core.BlockPos",true,loader);
        Object pos=posClass.getConstructor(int.class,int.class,int.class).newInstance(1,0,0);
        Class<?> mesherClass=Class.forName("io.github.imcinq.puremetal.lod.CoarseMesher",true,loader);
        mesherClass.getMethod("end").invoke(null);
        if(!(boolean)method.invoke(task,null,null,null,pos,null,null)) throw new AssertionError("Inactive mesher skipped detailed block");
        var localField=mesherClass.getDeclaredField("LOCAL"); localField.setAccessible(true);
        Object mesher=((ThreadLocal<?>)localField.get(null)).get();
        var active=mesherClass.getDeclaredField("active"); active.setAccessible(true);
        var skip=mesherClass.getDeclaredField("skip"); skip.setAccessible(true);
        long[] covered=(long[])skip.get(mesher); java.util.Arrays.fill(covered,0);
        Class<?> contextClass=Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext",true,loader);
        Object context=unsafe.allocateInstance(contextClass);
        try {
            active.setBoolean(mesher,true);
            if(!(boolean)method.invoke(task,null,null,null,pos,null,context)) throw new AssertionError("Uncovered block skipped");
            covered[0]=2L;
            if((boolean)method.invoke(task,null,null,null,pos,null,context)) throw new AssertionError("Covered block duplicated");
        } finally { java.util.Arrays.fill(covered,0); mesherClass.getMethod("end").invoke(null); }
        var handle=java.lang.invoke.MethodHandles.lookup().unreflect(method).asType(java.lang.invoke.MethodType.methodType(
            boolean.class,Object.class,Object.class,Object.class,Object.class,Object.class,Object.class,Object.class));
        for(int i=0;i<30000;i++) invokeCondition(handle,task,pos);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(thread);
        for(int i=0;i<100000;i++) if(!invokeCondition(handle,task,pos)) throw new AssertionError("Detailed call skipped during allocation fixture");
        double bytes=(bean.getThreadAllocatedBytes(thread)-before)/100000.0;
        if(bytes>1.0) throw new AssertionError("Conditional hook allocation: "+bytes+" bytes/call");
        System.out.println("SODIUM-CONDITION-ALLOCATION actual transformed inactive hook bytes/call="+bytes);
        System.out.println("PASS: actual Sodium conditional hook retains detailed calls, skips covered blocks, no Object[] operation allocation");
    }
    static boolean invokeCondition(java.lang.invoke.MethodHandle handle,Object task,Object pos) {
        try { return (boolean)handle.invokeExact(task,(Object)null,(Object)null,(Object)null,pos,(Object)null,(Object)null); }
        catch(Throwable error) { throw new AssertionError(error); }
    }
}
