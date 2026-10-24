package io.github.imcinq.puremetal.scale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import io.github.imcinq.puremetal.backend.MetalUpscaler;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Draws the world at a fraction of the window size and upscales it before the HUD is drawn at full size.
 *
 * While the world renders, Minecraft's main target temporarily holds the smaller textures (its contents are swapped with
 * {@link #world}), so everything that draws the world, including objects that kept a reference to the main target such
 * as the sky renderer, lands in them. Afterwards the textures are swapped back and the world is upscaled into the main
 * target.
 */
public final class RenderScale {
    private static @Nullable TextureTarget world;
    private static boolean swapped;
    /** Starts set so the first frame sizes the world targets for the saved scale. */
    private static boolean dirty = true;
    private static boolean hadLevel;
    /** This frame's world is drawn jittered and upscaled by MetalFX temporal. */
    private static boolean temporal;
    /** The frame began temporal (the world was jittered), whatever became of it since. */
    private static boolean temporalBegun;
    private static volatile ScaleConfig.@Nullable Upscaler lastUsed;
    /** Where the hand's depth went this frame (a post effect gives it its own target), or null. */
    private static @Nullable RenderTarget handTarget;

    private RenderScale() {
    }

    /** Render scale applies: below 100% and Minecraft is rendering through Pure Metal. */
    public static boolean enabled() {
        return ScaleConfig.scale() < ScaleConfig.MAX_SCALE && MetalUpscaler.active();
    }

    public static int scaled(final int size) {
        return enabled() ? Math.max(1, Math.round(size * ScaleConfig.worldFraction(ScaleConfig.scale()))) : size;
    }

    static void markDirty() {
        dirty = true;
    }

    /** The scale changed since the last call: the world-sized targets need resizing. */
    public static boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    /** After GameRenderer.resize: size the world's own targets (3D HUD depth, entity outlines) to the render scale. */
    public static void resized(final RenderTarget main, final RenderTarget hud3dTarget, final LevelRenderer levelRenderer,
                               final int width, final int height, final boolean hasLevel) {
        if (!enabled() || !hasLevel) {
            releaseWorld(main);
            return;
        }
        int w = scaled(width);
        int h = scaled(height);
        hud3dTarget.resize(w, h);
        levelRenderer.resize(w, h);
        if (world == null) {
            world = new TextureTarget("Pure Metal world", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else {
            world.resize(w, h);
        }
    }

    /** Render-thread lifecycle: menus do not need scaled world targets or an upscale pipeline. */
    public static void prepareFrame(final RenderTarget main, final boolean hasLevel) {
        if (ScaleConfig.refreshEffective()) {
            dirty = true; // the upscaler that really runs (or the Temporal output size) changed under the same settings
        }
        if (!hasLevel) {
            if (world != null || hadLevel) {
                releaseWorld(main);
                MetalUpscaler.trimPoolIfActive();
            }
        } else if (!hadLevel && enabled()) {
            // Recreate the targets through the ordinary resize path on entering a world.
            dirty = true;
        } else if (enabled() ? !ready(main) : world != null) {
            dirty = true; // the targets no longer match the size this frame wants
        }
        hadLevel = hasLevel;
    }

    private static void releaseWorld(final RenderTarget main) {
        temporal = false;
        temporalBegun = false;
        handTarget = null;
        TemporalUpscaler.cancel();
        if (world != null) {
            if (swapped) {
                ((TargetSwap)main).puremetal$swapWith(world);
                swapped = false;
            }
            world.destroyBuffers();
            world = null;
        }
        MetalUpscaler.releaseResources();
    }

    /** The scaler that produced the last upscaled frame (for the F3 line), or null before the first one. */
    public static ScaleConfig.@Nullable Upscaler lastUsedUpscaler() {
        return lastUsed;
    }

    /** The world targets match the main target at the current scale, so this frame can be scaled. */
    public static boolean ready(final RenderTarget main) {
        return enabled() && world != null && world.width == scaled(main.width) && world.height == scaled(main.height);
    }

    /** Before the world renders: point the main target at the smaller textures and clear them. */
    public static void begin(final RenderTarget main, final Vector4fc clearColor) {
        if (swapped) {
            return;
        }
        if (!ready(main)) {
            TemporalUpscaler.cancel();
            return;
        }
        // Decide while main is still full-size and world is input-size; the swap exchanges their sizes.
        temporal = ScaleConfig.effectiveUpscaler() == ScaleConfig.Upscaler.TEMPORAL && temporalUpscales(main);
        ((TargetSwap)main).puremetal$swapWith(world);
        swapped = true;
        temporalBegun = temporal;
        handTarget = null;
        if (temporal) {
            TemporalUpscaler.beginFrame(main.width, main.height);
        } else {
            TemporalUpscaler.cancel();
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(main.getColorTexture(), clearColor, main.getDepthTexture(), 0.0);
    }

    /** Call before the swap (main full-size, world input-size). MetalFX Temporal needs an output larger than the world's image; at 1:1 (rounding at very small sizes) it is skipped. */
    private static boolean temporalUpscales(final RenderTarget main) {
        float output = ScaleConfig.temporalOutputScale();
        int outW = output < 0.999F ? Math.round(main.width * output) : main.width;
        int outH = output < 0.999F ? Math.round(main.height * output) : main.height;
        return outW > world.width && outH > world.height;
    }

    /** The world is being drawn with temporal jitter. */
    public static boolean temporalFrame() {
        return swapped && temporal;
    }

    /**
     * Just before the hand is drawn (it clears the depth): captures what MetalFX temporal needs from the world's depth.
     * {@code postEffects}: a post effect is active, so the hand's depth goes to {@code hud3d} and the world's stays intact.
     */
    public static void prepareTemporal(final RenderTarget main, final RenderTarget hud3d, final boolean postEffects) {
        if (swapped && temporal) {
            handTarget = postEffects ? hud3d : null;
            if (!TemporalUpscaler.prepare(main.getDepthTextureView(), main.getColorTextureView(), world.getColorTextureView())) {
                temporal = false;
            }
        }
    }

    /** After the world and its post effects: restore the full-size textures and upscale the world into them. */
    public static boolean end(final RenderTarget main) {
        if (!swapped) {
            return false;
        }
        swapped = false;
        ((TargetSwap)main).puremetal$swapWith(world);
        boolean hooks = !temporalBegun || TemporalUpscaler.hooksIntact();
        RenderTarget handDepth = handTarget != null ? handTarget : world;
        boolean done = temporal && hooks && TemporalUpscaler.upscale(
            world.getColorTextureView(),
            handDepth.width == world.width && handDepth.height == world.height ? handDepth.getDepthTextureView() : null,
            main.getColorTextureView()
        );
        temporal = false;
        temporalBegun = false;
        handTarget = null;
        if (done) {
            lastUsed = ScaleConfig.Upscaler.TEMPORAL;
        } else {
            TemporalUpscaler.cancel();
            boolean spatial = MetalUpscaler.upscale(
                world.getColorTextureView(), main.getColorTextureView(), ScaleConfig.effectiveUpscaler() == ScaleConfig.Upscaler.METALFX,
                ScaleConfig.sharpening().amount
            );
            lastUsed = spatial ? ScaleConfig.Upscaler.METALFX : ScaleConfig.Upscaler.BILINEAR;
        }
        return true;
    }
}
