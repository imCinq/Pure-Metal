import com.sun.management.ThreadMXBean;
import io.github.imcinq.puremetal.perf.SodiumBlockCallbacks;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.services.PlatformModelEmitter;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.Direction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

/** Headless: checks the actual Sodium class through Knot; never starts Minecraft or opens a window. */
public final class TestSodiumBlockCallbacks {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        for (String version : new String[]{"0.9.2", "0.9.2+mc26.3", "0.9.3-beta.1", "0.9.3-beta.1+mc26.3"}) {
            check(SodiumBlockCallbacks.supportsVersion(version), "Verified version rejected: " + version);
        }
        for (String version : new String[]{"0.9.3", "0.9.3-alpha.1", "0.9.3-beta.10", "0.9.30", "0.10.0"}) {
            check(!SodiumBlockCallbacks.supportsVersion(version), "Unverified version accepted: " + version);
        }
        String path = "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer.class";
        ClassNode node = new ClassNode();
        try (var input = TestSodiumBlockCallbacks.class.getClassLoader().getResourceAsStream(path)) {
            new ClassReader(input).accept(node, 0);
        }
        check(SodiumBlockCallbacks.supported(node), "Actual Sodium shape unsupported");
        check(SodiumBlockCallbacks.apply(node), "Actual Sodium transformation failed");
        check(!SodiumBlockCallbacks.apply(node), "Transformation applied twice");
        // An extra captured value changes the factory shape: neither site may be rewritten.
        ClassNode changed = new ClassNode();
        try (var input = TestSodiumBlockCallbacks.class.getClassLoader().getResourceAsStream(path)) {
            new ClassReader(input).accept(changed, 0);
        }
        for (MethodNode method : changed.methods) {
            if (!method.name.equals("renderModel")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode dynamic && dynamic.bsmArgs[1] instanceof Handle handle
                    && handle.getName().equals("isFaceCulled")) dynamic.desc = dynamic.desc.replace(";)", ";I)");
            }
        }
        int fields = changed.fields.size(), methods = changed.methods.size();
        check(!SodiumBlockCallbacks.apply(changed), "Changed capture shape was accepted");
        check(changed.fields.size() == fields && changed.methods.size() == methods, "Rejected shape partly modified");
        ClassLoader loader = new Knot(EnvType.CLIENT).init(new String[]{"--gameDir", args[0]});
        Class.forName("TestSodiumBlockCallbacks$Checks", true, loader).getMethod("run").invoke(null);
    }

    public static final class Renderer extends BlockRenderer {
        public int calls;
        public boolean answer;
        public Object part, predicate, consumer;
        public Renderer() { super(null, null); }
        @Override public boolean isFaceCulled(Direction direction) { calls++; return answer; }
        @Override public void bufferDefaultModel(BlockStateModelPart part, Predicate<Direction> predicate,
                                                 Consumer<MutableQuadViewImpl> consumer) {
            calls++; this.part = part; this.predicate = predicate; this.consumer = consumer;
        }
    }

    public static final class Checks {
        private static volatile Object sink;
        private static MethodHandle getter(String suffix) throws Exception {
            var method = BlockRenderer.class.getDeclaredMethod("puremetal$blockCallback" + suffix);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).asType(MethodType.methodType(Object.class, BlockRenderer.class));
        }
        @SuppressWarnings("unchecked")
        public static void run() throws Throwable {
            boolean disabled = Boolean.getBoolean("puremetal.disableSodiumBlockCallbacks");
            long added = java.util.Arrays.stream(BlockRenderer.class.getDeclaredFields())
                .filter(f -> f.getName().startsWith("puremetal$blockCallback")).count();
            if (disabled) { check(added == 0, "Disabled cache still transformed Sodium"); return; }
            check(added == 2, "Actual loaded Sodium callback fields missing");
            // Bypass Minecraft-dependent construction: only the inherited callback dispatch is under test.
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) field.get(null);
            Renderer a = (Renderer) unsafe.allocateInstance(Renderer.class);
            Renderer b = (Renderer) unsafe.allocateInstance(Renderer.class);
            MethodHandle cull = getter("Cull"), buffer = getter("Buffer");
            Predicate<Direction> predicate = (Predicate<Direction>) (Object) cull.invokeExact((BlockRenderer) a);
            PlatformModelEmitter.Bufferer bufferer = (PlatformModelEmitter.Bufferer) (Object) buffer.invokeExact((BlockRenderer) a);
            check(predicate == (Object) cull.invokeExact((BlockRenderer) a), "Cull callback was not reused");
            check(bufferer == (Object) buffer.invokeExact((BlockRenderer) a), "Buffer callback was not reused");
            check(predicate != (Object) cull.invokeExact((BlockRenderer) b), "Different renderers shared callback");
            a.answer = true;
            check(predicate.test(Direction.UP), "Virtual override not dispatched");
            a.answer = false;
            check(!predicate.test(Direction.DOWN) && a.calls == 2, "Callback captured stale renderer state");
            Consumer<MutableQuadViewImpl> consumer = quad -> {};
            bufferer.emit(null, predicate, consumer);
            check(a.calls == 3 && a.predicate == predicate && a.consumer == consumer && b.calls == 0,
                "Buffer callback changed receiver or arguments");
            for (int i = 0; i < 100_000; i++) {
                sink = (Object) cull.invokeExact((BlockRenderer) a);
                sink = (Object) buffer.invokeExact((BlockRenderer) a);
            }
            ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long id = Thread.currentThread().threadId(), before = threads.getThreadAllocatedBytes(id);
            for (int i = 0; i < 100_000; i++) {
                sink = (Object) cull.invokeExact((BlockRenderer) a);
                sink = (Object) buffer.invokeExact((BlockRenderer) a);
            }
            long cached = threads.getThreadAllocatedBytes(id) - before;
            before = threads.getThreadAllocatedBytes(id);
            for (int i = 0; i < 100_000; i++) {
                sink = (Predicate<Direction>) a::isFaceCulled;
                sink = (PlatformModelEmitter.Bufferer) a::bufferDefaultModel;
            }
            long baseline = threads.getThreadAllocatedBytes(id) - before;
            check(cached < baseline / 10, "Cache did not substantially reduce callback allocations");
            System.out.println("CALLBACK-ALLOCATION 100000 pairs: cached=" + cached + " baseline=" + baseline + " bytes");
            System.out.println("PASS: actual Knot transformation, changed-shape fallback, receiver isolation, live state, dispatch, arguments, allocation");
        }
    }
}
