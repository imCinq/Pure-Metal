package io.github.imcinq.puremetal.mixin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Keeps benchmark/profile hooks out of normal play and picks the terrain LOD hooks. */
public final class PureMetalMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Pure Metal");
    private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
    private static Boolean sodiumMemoryTrimSupported;
    private static Boolean sodiumOptionListSupported;
    /**
     * Sodium releases whose OptionListWidget local-variable slots were verified (0.9.3-alpha.1 and 0.9.3-beta.1 have
     * byte-identical OptionListWidget, AbstractParentWidget and ModOptions classes to 0.9.2); other versions skip the note.
     * An entry matches itself and "entry+build", never another prerelease tag or a longer number such as 0.9.30.
     */
    private static final List<String> VERIFIED_SODIUM_VERSIONS = List.of("0.9.2", "0.9.3-alpha.1", "0.9.3-beta.1", "0.9.3");

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.contains(".memory.sodium.")) {
            return SODIUM && isSodiumMemoryTrimSupported(targetClassName);
        }
        if (mixinClassName.contains(".ui.sodium.")) {
            return SODIUM && isSodiumOptionListSupported();
        }
        if (mixinClassName.contains(".diagnostics.")) {
            return (Boolean.getBoolean("puremetal.terrainDiagnostics") || Boolean.getBoolean("puremetal.terrainOcclusionProbe"))
                && (mixinClassName.contains(".diagnostics.sodium.") == SODIUM);
        }
        if (mixinClassName.endsWith(".perf.TerrainShaderProofMixin")) return Boolean.getBoolean("puremetal.terrainOcclusionProbe");
        if (mixinClassName.endsWith(".perf.PalettedContainerMixin")) {
            return !Boolean.getBoolean("puremetal.disablePaletteScratch");
        }
        if (mixinClassName.endsWith(".MinecraftBenchMixin")) {
            return Integer.getInteger("puremetal.bench", 0) > 0 || Boolean.getBoolean("puremetal.profile")
                || Boolean.getBoolean("puremetal.terrainDiagnostics") || Boolean.getBoolean("puremetal.terrainOcclusionProbe");
        }
        // Terrain LOD hooks the mesher that is actually in use: Sodium's when it is installed, vanilla's otherwise.
        if (mixinClassName.endsWith(".lod.LodTextureShaderMixin")) {
            return Boolean.parseBoolean(System.getProperty("puremetal.experimentalLodTextures", "true"))
                || Boolean.parseBoolean(System.getProperty("puremetal.rgssEarlyOut", "true"));
        }
        if (mixinClassName.contains(".lod.sodium.")) {
            return SODIUM;
        }
        if (mixinClassName.contains(".lod.")) {
            return !SODIUM;
        }
        return true;
    }

    /** The memory trim is optional, so a Sodium version whose ArenaAggregator internals changed skips it instead of crashing. */
    private static synchronized boolean isSodiumMemoryTrimSupported(final String targetClassName) {
        if (sodiumMemoryTrimSupported == null) {
            sodiumMemoryTrimSupported = checkSodiumMemoryTrimTarget(targetClassName);
            if (!sodiumMemoryTrimSupported) {
                LOGGER.warn("Pure Metal: Sodium memory trim skipped, this Sodium version is not supported");
            }
        }
        return sodiumMemoryTrimSupported;
    }

    /**
     * The options-screen note is cosmetic, so a Sodium whose option list changed shows no note instead of crashing.
     * Both the list mixin and its invoker accessor are gated on the same result, and every member must be declared
     * on the exact class Mixin targets (inherited members do not count).
     */
    private static synchronized boolean isSodiumOptionListSupported() {
        if (sodiumOptionListSupported == null) {
            sodiumOptionListSupported = isVerifiedSodiumVersion() && checkSodiumOptionList();
            if (!sodiumOptionListSupported) {
                LOGGER.debug("Pure Metal: Sodium options note skipped, this Sodium version is not verified");
            }
        }
        return sodiumOptionListSupported;
    }

    /** The list mixin captures @Local slots, which the member check cannot see, so only allow-listed versions apply it. */
    private static boolean isVerifiedSodiumVersion() {
        return FabricLoader.getInstance().getModContainer("sodium")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .filter(v -> VERIFIED_SODIUM_VERSIONS.stream().anyMatch(ok -> v.equals(ok) || v.startsWith(ok + "+")))
            .isPresent();
    }

    private static boolean checkSodiumOptionList() {
        try {
            final ClassNode list = readClass("net/caffeinemc/mods/sodium/client/gui/widgets/OptionListWidget", org.objectweb.asm.ClassReader.SKIP_FRAMES);
            final ClassNode parent = readClass("net/caffeinemc/mods/sodium/client/gui/widgets/AbstractParentWidget");
            final ClassNode mod = readClass("net/caffeinemc/mods/sodium/client/config/structure/ModOptions");
            return hasField(list, "entryHeight", "I")
                && hasRenderAllPagesLocals(list)
                && hasMethod(parent, "addRenderableChild", "(Lnet/minecraft/client/gui/components/events/GuiEventListener;)Lnet/minecraft/client/gui/components/events/GuiEventListener;")
                && hasMethod(mod, "configId", "()Ljava/lang/String;");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The mixin captures renderAllPages locals by slot (5 = int offset, 7 = ModOptions) after the first
     * addRenderableChild call; confirm the debug local table still says so, so a mismatch skips the note instead of failing the inject.
     */
    private static boolean hasRenderAllPagesLocals(final ClassNode list) {
        final String listName = "net/caffeinemc/mods/sodium/client/gui/widgets/OptionListWidget";
        final String modName = "Lnet/caffeinemc/mods/sodium/client/config/structure/ModOptions;";
        for (final org.objectweb.asm.tree.MethodNode m : list.methods) {
            if (!"renderAllPages".equals(m.name) || !"(Lnet/minecraft/client/gui/screens/Screen;III)I".equals(m.desc)) continue;
            if (m.localVariables == null || m.instructions == null) return false;
            final boolean offset = m.localVariables.stream().anyMatch(v -> v.index == 5 && "I".equals(v.desc));
            final boolean mod = m.localVariables.stream().anyMatch(v -> v.index == 7 && modName.equals(v.desc));
            boolean add = false;
            for (final org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && listName.equals(call.owner) && "addRenderableChild".equals(call.name)) {
                    add = true;
                    break;
                }
            }
            return offset && mod && add;
        }
        return false;
    }

    private static boolean hasField(final ClassNode node, final String name, final String desc) {
        return node.fields.stream().anyMatch(f -> name.equals(f.name) && desc.equals(f.desc));
    }

    private static boolean hasMethod(final ClassNode node, final String name, final String desc) {
        return node.methods.stream().anyMatch(m -> name.equals(m.name) && desc.equals(m.desc));
    }

    /** Reads class bytes from the resource without loading or initialising the class. */
    private static ClassNode readClass(final String internalName) throws java.io.IOException {
        return readClass(internalName, org.objectweb.asm.ClassReader.SKIP_CODE);
    }

    private static ClassNode readClass(final String internalName, final int flags) throws java.io.IOException {
        try (java.io.InputStream in = PureMetalMixinPlugin.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new java.io.FileNotFoundException(internalName);
            final ClassNode node = new ClassNode();
            new org.objectweb.asm.ClassReader(in).accept(node, flags);
            return node;
        }
    }

    private static boolean checkSodiumMemoryTrimTarget(final String targetClassName) {
        try {
            final ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false);
            return node.fields.stream().anyMatch(f -> "freeBuffers".equals(f.name) && "[Lcom/mojang/renderpearl/api/buffers/GpuBuffer;".equals(f.desc))
                && node.fields.stream().anyMatch(f -> "freeBufferCount".equals(f.name) && "I".equals(f.desc))
                && node.methods.stream().anyMatch(m -> "releaseBufferForReuse".equals(m.name) && "(Lcom/mojang/renderpearl/api/buffers/GpuBuffer;)V".equals(m.desc))
                && node.methods.stream().anyMatch(m -> "update".equals(m.name) && "()V".equals(m.desc));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
