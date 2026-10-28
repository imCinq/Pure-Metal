import io.github.imcinq.puremetal.perf.FabricFluidCallbacks;
import io.github.imcinq.puremetal.perf.ScopedFluidCallbacks;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.ArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** Headless integration: immutable bindings, unchanged factories, actual scoped calls and nested failures. */
public final class TestFluidCallbacks {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static java.util.List<String> factories(ClassNode node) {
        var result = new ArrayList<String>();
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode dynamic) result.add(method.name + dynamic.name + dynamic.desc
                + dynamic.bsm + Arrays.toString(dynamic.bsmArgs));
        }
        return result;
    }

    static void ownerChecks(ClassLoader loader, ScopedValue<Object> key, boolean disabled) throws Exception {
        Class<?> type;
        try { type = Class.forName("net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl$DefaultRenderContext", true, loader); }
        catch (ClassNotFoundException absent) { return; }
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object first = constructor.newInstance(), second = constructor.newInstance();
        check((first instanceof ScopedFluidCallbacks.CarrierOwner) == !disabled, "Sodium owner hook enablement incorrect");
        ScopedValue.Carrier a = ScopedFluidCallbacks.where(key, first), b = ScopedFluidCallbacks.where(key, second);
        check(a.get(key) == first && b.get(key) == second, "Binding changed renderer identity");
        if (!disabled) {
            check(a == ScopedFluidCallbacks.where(key, first), "Owner binding not reused");
            check(a != b, "Different renderers shared a binding");
        }
        ScopedValue<Object> other = ScopedValue.newInstance();
        check(ScopedFluidCallbacks.where(other, first).get(other) == first, "Other key silently reused wrong scope");
        if (!disabled) check(a == ScopedFluidCallbacks.where(key, first), "Other key displaced normal binding");
        RuntimeException failure = new RuntimeException("nested failure");
        a.run(() -> {
            check(key.get() == first, "Outer cached scope incorrect");
            try { b.run(() -> { check(key.get() == second, "Inner cached scope incorrect"); throw failure; }); }
            catch (RuntimeException actual) { check(actual == failure, "Nested failure identity changed"); }
            check(key.get() == first, "Nested cached scope did not restore parent");
        });
        check(!key.isBound(), "Cached scope escaped invocation");
        Throwable[] errors = new Throwable[2];
        Thread[] workers = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int index = i;
            workers[i] = new Thread(() -> {
                Object token = new Object();
                try {
                    ScopedValue.where(other, token).run(() -> {
                        for (int j = 0; j < 1000; j++) a.run(() -> {
                            check(key.get() == first && other.get() == token, "Cached carrier captured another thread's parent scope");
                        });
                        check(!key.isBound() && other.get() == token, "Parent scope damaged");
                    });
                } catch (Throwable error) { errors[index] = error; }
            });
            workers[i].start();
        }
        for (Thread worker : workers) worker.join();
        for (Throwable error : errors) if (error != null) throw new AssertionError("Shared immutable carrier failed", error);
        check(Arrays.stream(ScopedFluidCallbacks.class.getDeclaredFields()).noneMatch(f -> f.getType() == ThreadLocal.class),
            "ThreadLocal argument pool reintroduced");
        System.out.println("PASS: renderer ownership, reuse, other-key fallback, nested failure and per-thread parent scopes");
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = new Knot(EnvType.CLIENT).init(new String[]{"--gameDir", args[0]});
        Class.forName("TestFluidCallbacks$Checks", true, loader).getMethod("run").invoke(null);
    }

    public static final class Checks {
        public static void run() throws Exception {
            ClassLoader loader = Checks.class.getClassLoader();
            String name = "net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderingImpl";
            ClassNode node = new ClassNode();
            try (var input = loader.getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (input == null) { System.out.println("PASS: absent fluid API requires no callback integration"); return; }
                new ClassReader(input).accept(node, 0);
            }
            check(FabricFluidCallbacks.supported(node), "Real Fabric factory shape unsupported");
            // A changed consumer must reject both sites before making any mutation.
            ClassNode changed = new ClassNode();
            try (var input = loader.getResourceAsStream(name.replace('.', '/') + ".class")) { new ClassReader(input).accept(changed, 0); }
            for (MethodNode method : changed.methods) if (method.name.equals("render")) {
                for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call
                    && call.owner.equals("java/lang/ScopedValue$Carrier") && call.name.equals("run")) call.name = "changed";
            }
            int count = changed.methods.size();
            check(!FabricFluidCallbacks.apply(changed) && changed.methods.size() == count, "Changed consumer partly transformed");
            var factories = factories(node);
            check(FabricFluidCallbacks.apply(node), "Real Fabric transformation rejected");
            check(factories.equals(factories(node)), "Original Runnable factories changed");
            check(!FabricFluidCallbacks.apply(node), "Duplicate transformation accepted");
            check(FabricFluidCallbacks.supportsVersion("6.0.6+3434d6d902")
                && !FabricFluidCallbacks.supportsVersion("6.0.60+other") && !FabricFluidCallbacks.supportsVersion("6.0.7"), "Version gate incorrect");
            Class<?> impl = Class.forName(name, true, loader);
            boolean disabled = Boolean.getBoolean("puremetal.disableFluidCallbacks");
            long bridges = Arrays.stream(impl.getDeclaredFields()).filter(m -> m.getName().startsWith("puremetal$fluidCallback")).count();
            check(bridges == (disabled ? 0 : 1), "Knot-loaded class enablement incorrect: " + bridges);
            Method render = Arrays.stream(impl.getDeclaredMethods()).filter(m -> m.getName().equals("render")).findFirst().orElseThrow();
            Field current = impl.getDeclaredField("CURRENT_DEFAULT_RENDERER"); current.setAccessible(true);
            ScopedValue<?> scope = (ScopedValue<?>) current.get(null);
            Class<?> handlerType = render.getParameterTypes()[1], defaultType = render.getParameterTypes()[7];
            Object defaultRenderer = Proxy.newProxyInstance(loader, new Class[]{defaultType}, (p,m,a) -> null);
            Object innerDefault = Proxy.newProxyInstance(loader, new Class[]{defaultType}, (p,m,a) -> null);
            Object pos = render.getParameterTypes()[3].getConstructor(int.class, int.class, int.class).newInstance(1,2,3);
            Object world = Proxy.newProxyInstance(loader, new Class[]{render.getParameterTypes()[2]}, (p,m,a) -> null);
            Object output = Proxy.newProxyInstance(loader, new Class[]{render.getParameterTypes()[4]}, (p,m,a) -> null);
            Object inner = Proxy.newProxyInstance(loader, new Class[]{handlerType}, (p,m,a) -> {
                if (m.getName().equals("renderFluid")) {
                    check(scope.get() == innerDefault, "Actual nested scope lost");
                    check(a[1] == pos && a[2] == world && a[3] == output, "Nested current arguments changed");
                }
                return null;
            });
            int[] calls = {0};
            Object handler = Proxy.newProxyInstance(loader, new Class[]{handlerType}, (p,m,a) -> {
                if (m.getName().equals("renderFluid")) {
                    check(scope.get() == defaultRenderer, "Actual default renderer scope lost");
                    check(a.length == 6 && a[0] == null && a[1] == pos && a[2] == world && a[3] == output
                        && a[4] == null && a[5] == null, "Actual handler arguments changed");
                    render.invoke(null, null,inner,world,pos,output,null,null,innerDefault);
                    check(scope.get() == defaultRenderer, "Nested render did not restore outer scope");
                    calls[0]++;
                }
                return null;
            });
            render.invoke(null, null,handler,world,pos,output,null,null,defaultRenderer);
            check(calls[0] == 1 && !scope.isBound(), "Actual handler call/scope cleanup failed");
            RuntimeException failure = new RuntimeException("expected handler failure");
            Object throwing = Proxy.newProxyInstance(loader, new Class[]{handlerType}, (p,m,a) -> { throw failure; });
            try { render.invoke(null, null,throwing,null,null,null,null,null,defaultRenderer); throw new AssertionError("Actual exception swallowed"); }
            catch (InvocationTargetException actual) { check(actual.getCause() == failure, "Actual exception identity changed"); }
            check(!scope.isBound(), "Actual failed handler leaked scope");
            render.invoke(null, null,handler,world,pos,output,null,null,defaultRenderer);
            check(calls[0] == 2, "Actual invocation not reusable after failure");
            // The six-capture site must preserve its original null-receiver failure and scope cleanup.
            Method vanilla = Arrays.stream(impl.getDeclaredMethods()).filter(m -> m.getName().equals("renderVanillaDefault")).findFirst().orElseThrow();
            try { vanilla.invoke(null, new Object[6]); throw new AssertionError("Null renderer did not fail"); }
            catch (InvocationTargetException actual) { check(actual.getCause() instanceof NullPointerException, "Vanilla failure changed"); }
            check(!((ScopedValue<?>) impl.getField("IS_RENDERING_VANILLA_DEFAULT").get(null)).isBound(), "Vanilla scope leaked");
            ownerChecks(loader, (ScopedValue<Object>) scope, disabled);
            System.out.println("PASS: real Fabric class through Knot, " + (disabled ? "opt-out" : "scope bindings cached, original factories intact")
                + ", shape/version rejection, original dispatch and failure cleanup");
        }
    }
}
