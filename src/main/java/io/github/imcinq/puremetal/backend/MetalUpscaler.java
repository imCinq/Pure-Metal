package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Render-scale upscaling on the running Metal device, recorded in order with the frame's other commands. */
public final class MetalUpscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Pure Metal");
    private static MetalDevice temporalDevice;
    private static boolean temporalSupported;
    private static boolean temporalFailureLogged;

    private MetalUpscaler() {
    }

    /** True while Minecraft renders through Pure Metal (render scale does nothing on other backends). */
    public static boolean active() {
        return MetalDevice.current != null;
    }

    /** Called on the render thread when render scale becomes inactive. */
    public static void releaseResources() {
        MetalDevice device = MetalDevice.current;
        if (device != null) MetalNative.upscaleReset(device.handle());
    }

    /** Called on the render thread when the player returns to menus: parked buffers the GPU has finished are released. */
    public static void trimPoolIfActive() {
        MetalDevice device = MetalDevice.current;
        if (device != null) device.trimBufferPool();
    }

    /**
     * Diagnostics counter since the last call (see TemporalUpscaler): 0 scaler creations, 1 resets, 2 encoders added by the
     * temporal path, 3 command buffer splits, 4 span GPU ns, 5 spans timed, 6 frames the scaler wrote the target directly,
     * 7 chain ns (first stage start to last stage end), 8-13 GPU ns per stage (world flush, motion, hand pass, hand patch,
     * scaler, copy).
     */
    public static long takeTemporalStat(final int which) {
        MetalDevice device = MetalDevice.current;
        return device == null ? 0L : MetalNative.takeStat(device.handle(), 12 + which);
    }

    /** What the temporal scaler was last created with (sizes, formats), once per creation; null when nothing new. */
    public static String takeTemporalInfo() {
        MetalDevice device = MetalDevice.current;
        if (device == null) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer out = stack.calloc(768);
            return MetalNative.temporalInfo(device.handle(), MemoryUtil.memAddress(out), out.capacity()) == 1
                ? MemoryUtil.memUTF8(MemoryUtil.memAddress(out)) : null;
        }
    }

    /** The device can run MetalFX temporal upscaling (it may still turn itself off if the scaler cannot be built). */
    public static boolean temporalSupported() {
        MetalDevice device = MetalDevice.current;
        if (device == null) {
            return false;
        }
        if (temporalDevice != device) {
            temporalDevice = device;
            temporalSupported = MetalNative.temporalSupported(device.handle());
        }
        return temporalSupported;
    }

    /**
     * Before the hand is drawn: derives motion vectors and a depth copy from {@code depth} (the world depth) for the
     * temporal scaler. {@code params}: inverse of this frame's unjittered view-projection, the previous frame's
     * view-projection (both column-major) and the camera movement since then (xyz + padding), 36 floats.
     * Returns false when temporal upscaling is unavailable.
     */
    public static boolean temporalPrepare(final GpuTextureView depth, final GpuTextureView source, final GpuTextureView destination, final float[] params) {
        MetalDevice device = MetalDevice.current;
        if (device == null) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer buffer = stack.mallocFloat(params.length);
            buffer.put(params).flip();
            int result = MetalNative.temporalPrepare(
                device.handle(), ((MetalTextureView)depth).handle(), ((MetalTextureView)source).handle(),
                ((MetalTextureView)destination).handle(), MemoryUtil.memAddress(buffer)
            );
            return temporalResult(result, "prepare the temporal upscale");
        }
    }

    /**
     * At the end of the world: scales {@code source} into {@code destination} with MetalFX temporal upscaling.
     * {@code handDepth} is the world depth after the hand was drawn (or null). Returns false if nothing was drawn
     * and the caller must upscale another way.
     */
    public static boolean temporalUpscale(
        final GpuTextureView source, final GpuTextureView handDepth, final GpuTextureView destination, final float jitterX, final float jitterY, final boolean reset
    ) {
        MetalDevice device = MetalDevice.current;
        if (device == null) {
            return false;
        }
        ((MetalTextureView)destination).metalTexture().beforeWrite(true);
        int result = MetalNative.temporalUpscale(
            device.handle(), ((MetalTextureView)source).handle(), handDepth == null ? 0L : ((MetalTextureView)handDepth).handle(),
            ((MetalTextureView)destination).handle(), jitterX, jitterY, reset ? 1 : 0
        );
        return temporalResult(result, "temporally upscale the world");
    }

    /**
     * Temporal results never throw: -1 means this frame falls back to bilinear (logged once); 0 means nothing was done, and
     * the device may have just marked temporal unsupported, so the cached answer is asked for again.
     */
    private static boolean temporalResult(final int result, final String action) {
        if (result < 0) {
            if (!temporalFailureLogged) {
                temporalFailureLogged = true;
                LOGGER.warn("Pure Metal could not {} (see the native log); using Bilinear for the frames it fails", action);
            }
            return false;
        }
        if (result == 0) {
            temporalDevice = null;
        }
        return result == 1;
    }

    /**
     * Fills {@code destination} with {@code source} scaled up, using MetalFX spatial upscaling when {@code metalFx} is set
     * and supported (bilinear otherwise); {@code sharpness} 0 disables sharpening, up to 1. Must be called outside a
     * render pass. Returns whether MetalFX was used.
     */
    public static boolean upscale(final GpuTextureView source, final GpuTextureView destination, final boolean metalFx, final float sharpness) {
        MetalDevice device = MetalDevice.current;
        if (device == null) {
            return false;
        }
        ((MetalTextureView)destination).metalTexture().beforeWrite(true);
        int result = MetalNative.upscale(
            device.handle(), ((MetalTextureView)source).handle(), ((MetalTextureView)destination).handle(), metalFx ? 1 : 0, sharpness
        );
        if (result < 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer out = stack.calloc(512);
                MetalNative.deviceLastError(device.handle(), MemoryUtil.memAddress(out), out.capacity());
                throw new IllegalStateException("Pure Metal could not upscale the world: " + MemoryUtil.memUTF8(MemoryUtil.memAddress(out)));
            }
        }
        return result == 1;
    }
}
