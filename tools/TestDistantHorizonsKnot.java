// Fabric initialization and class transformation only. Does not start Minecraft.
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

public final class TestDistantHorizonsKnot {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = new Knot(EnvType.CLIENT).init(new String[]{"--gameDir", args[0]});
        Class<?> experiment = Class.forName("io.github.imcinq.puremetal.backend.DistantHorizonsExperiment", true, loader);
        boolean enabled = (boolean) experiment.getMethod("enabled").invoke(null);
        boolean expected = Boolean.parseBoolean(args[1]);
        if (enabled != expected) throw new AssertionError("enabled=" + enabled + " expected=" + expected);
        Class<?> backend = Class.forName("io.github.imcinq.puremetal.backend.PureMetalBackend", true, loader);
        var incompatible = backend.getDeclaredMethod("incompatibleMod");
        incompatible.setAccessible(true);
        Object conflict = incompatible.invoke(null);
        if ((conflict == null) != expected) throw new AssertionError("backend conflict=" + conflict + " expected experiment=" + expected);
        Class<?> target = Class.forName("com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper", false, loader);
        boolean hooked = java.util.Arrays.stream(target.getDeclaredMethods())
            .anyMatch(method -> method.getName().contains("puremetal$dhMetalApi"));
        if (hooked != expected) throw new AssertionError("hooked=" + hooked + " expected=" + expected);
        System.out.println("PASS DH Knot gate=" + enabled + " mixin=" + hooked);
    }
}
