package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.Redact;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Minecraft's third backend. Any problem while starting is reported as a {@link BackendCreationException}, which
 * makes Minecraft quietly move on to its own backends.
 */
public final class PureMetalBackend implements GpuBackend {
    /** Identify our backend without exposing its implementation class to mixins. */
    public static boolean isMetalDevice(Object device) {
        return device instanceof MetalDevice;
    }
    private static final Logger LOGGER = LogUtils.getLogger();
    private static @Nullable String unsupportedReason;
    private static boolean checked;

    /** Why Pure Metal can't run on this machine, or null if it can. Cheap after the first call. */
    public static synchronized @Nullable String unsupportedReason() {
        if (!checked) {
            checked = true;
            unsupportedReason = check();
        }
        return unsupportedReason;
    }

    /**
     * Mods that render through OpenGL themselves rather than Minecraft's graphics API; under Metal there is no GL
     * context, so they would crash. Extra ids can be added with -Dpuremetal.incompatibleMods=id1,id2.
     */
    private static final List<String> INCOMPATIBLE_MODS = List.of("iris", "distanthorizons", "voxy", "nvidium");

    private static @Nullable String incompatibleMod() {
        if (Boolean.getBoolean("puremetal.ignoreIncompatibleMods")) {
            return null;
        }
        List<String> ids = new ArrayList<>(INCOMPATIBLE_MODS);
        for (String extra : System.getProperty("puremetal.incompatibleMods", "").split(",")) {
            if (!extra.isBlank()) {
                ids.add(extra.strip());
            }
        }
        FabricLoader loader = FabricLoader.getInstance();
        for (String id : ids) {
            var mod = loader.getModContainer(id);
            if (mod.isPresent()) {
                if ("distanthorizons".equals(id) && DistantHorizonsExperiment.enabled()) continue;
                return mod.get().getMetadata().getName() + " (" + id + ")";
            }
        }
        return null;
    }

    private static @Nullable String check() {
        if (Boolean.getBoolean("puremetal.disable")) {
            return "it was turned off with -Dpuremetal.disable=true";
        }
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        if (!os.startsWith("Mac")) {
            return "this isn't macOS";
        }
        if (!arch.equals("aarch64")) {
            return "this Mac doesn't have Apple Silicon";
        }
        String conflict = incompatibleMod();
        if (conflict != null) {
            return conflict + " is installed and draws with OpenGL directly (add -Dpuremetal.ignoreIncompatibleMods=true to try anyway)";
        }
        try {
            MetalNative.load();
            MetalConst.verifyNativeTables();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer name = stack.calloc(256);
                if (MetalNative.deviceCheck(MemoryUtil.memAddress(name), name.capacity()) == 0) {
                    return "the GPU (" + MemoryUtil.memUTF8(MemoryUtil.memAddress(name)) + ") isn't Apple7 (M1) or newer";
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Pure Metal failed to load its native library: {}", Redact.error(t));
            // The message can name the library's absolute path, which holds the macOS user name.
            return "its native library could not be loaded (" + Redact.home(t.getMessage()) + ")";
        }
        return null;
    }

    @Override
    public String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        String reason = unsupportedReason();
        if (reason != null) {
            LOGGER.warn("Pure Metal was rejected: {}", Redact.home(reason));
            throw new BackendCreationException("Pure Metal is off because " + reason, BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    @Override
    public void unloadLibrary() {
    }

    @Override
    public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
    }

    @Override
    public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
        if (!StartupGuard.metalStarting()) {
            throw new BackendCreationException("Pure Metal is off because its startup marker could not be written", BackendCreationException.Reason.OTHER);
        }
        long handle;
        try {
            handle = MetalNative.deviceCreate(debugOptions.useLabels() ? 1 : 0, Metal4Config.effective() ? 1 : 0);
        } catch (Throwable t) {
            LOGGER.warn("Pure Metal could not create a Metal device: {}", Redact.error(t));
            StartupGuard.metalFailed();
            throw new BackendCreationException("Pure Metal could not create a Metal device: " + Redact.error(t), BackendCreationException.Reason.OTHER);
        }
        if (handle == 0L) {
            LOGGER.warn("Pure Metal could not create a Metal device: the native layer returned no device");
            StartupGuard.metalFailed();
            throw new BackendCreationException("Pure Metal could not create a Metal device", BackendCreationException.Reason.OTHER);
        }
        try {
            return new FrontendGpuDevice(new MetalDevice(handle, debugOptions));
        } catch (RuntimeException e) {
            MetalDevice.current = null;
            MetalNative.deviceDestroy(handle);
            LOGGER.warn("Pure Metal could not start: {}", Redact.error(e));
            StartupGuard.metalFailed();
            throw new BackendCreationException("Pure Metal could not start: " + Redact.error(e), BackendCreationException.Reason.OTHER);
        }
    }
}
