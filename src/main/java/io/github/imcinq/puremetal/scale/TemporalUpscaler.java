package io.github.imcinq.puremetal.scale;

import io.github.imcinq.puremetal.Redact;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.imcinq.puremetal.backend.MetalUpscaler;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Per-frame state of MetalFX temporal upscaling: the sub-pixel projection jitter, and the camera matrices that turn the
 * world's depth into motion vectors (the camera is the only thing that moves in them; see pm_temporal_prepare).
 *
 * The world is drawn jittered with a Halton sequence. {@link #jitter} shifts clip space by (2jx/w, 2jy/h) for a jitter
 * (jx, jy) in input pixels, so MetalFX is told the sample offset (-jx, +jy) (y points down on screen). If the image
 * crawls or smears in game, the signs are the first suspect: -Dpuremetal.jitterFlipX / -Dpuremetal.jitterFlipY flip them.
 */
public final class TemporalUpscaler {
    /** A camera move longer than this in one frame is a teleport: history is dropped. */
    private static final double CUT_DISTANCE = 8.0;
    private static final int MAX_PHASES = 32;
    private static final float FLIP_X = Boolean.getBoolean("puremetal.jitterFlipX") ? -1.0F : 1.0F;
    private static final float FLIP_Y = Boolean.getBoolean("puremetal.jitterFlipY") ? -1.0F : 1.0F;

    private static final Matrix4f currentVp = new Matrix4f();
    private static final Matrix4f previousVp = new Matrix4f();
    private static final Matrix4f levelScratch = new Matrix4f();
    private static final JitteredProjection handJittered = new JitteredProjection();
    private static final Matrix4f inverseScratch = new Matrix4f();
    private static final Matrix4d inverseDouble = new Matrix4d();
    private static final float[] params = new float[38];
    private static boolean active;
    private static int frame;
    private static float jitterX;
    private static float jitterY;
    private static int inputWidth;
    private static int inputHeight;
    private static @Nullable Vec3 currentCamera;
    private static @Nullable Vec3 previousCamera;
    private static boolean havePrevious;
    /** Weak so a level that was left is not kept alive by the last temporal frame. */
    private static @Nullable WeakReference<Object> previousLevel;
    private static boolean reset;
    private static boolean prepared;
    private static boolean sawCurrent;
    /** Which of the three GameRendererMixin injections ran this temporal frame (another mod may have changed their sites). */
    private static boolean hookLevel;
    private static boolean hookPrepare;
    private static boolean hookHand;
    private static boolean hooksBroken;

    private static @Nullable Method irisInUse;
    private static @Nullable Object irisApi;
    private static boolean irisLookedUp;
    private static boolean irisLoaded;
    /** The shader-pack answer is reused for this long (a pack can only change through a screen, never mid-frame). */
    private static final long SHADER_PACK_CACHE_NANOS = 500_000_000L;
    private static long shaderPackCheckedAt;
    private static boolean shaderPackCached;

    /** -Dpuremetal.profile=true: once a second, log scaler creations/resets, temporal-path encoders and the span's GPU time. */
    private static final boolean DIAG = Boolean.getBoolean("puremetal.profile");
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Pure Metal");
    private static long diagSince;
    private static int diagFrames;

    private TemporalUpscaler() {
    }

    private static void diagFrame() {
        diagFrames++;
        long now = System.nanoTime();
        if (diagSince == 0L) {
            diagSince = now;
        } else if (now - diagSince >= 1_000_000_000L) {
            long frames = Math.max(1, diagFrames);
            long spans = MetalUpscaler.takeTemporalStat(5);
            String info = MetalUpscaler.takeTemporalInfo();
            if (info != null) {
                LOGGER.info("Temporal scaler: {}", Redact.home(info));
            }
            LOGGER.info(
                "Temporal/s: frames={} scalerCreations={} scalerResets={} encoders/frame={} cbSplits/frame={} directFrames={} spanGpu={} ms (avg of {}) | stages ms: world={} motion={} handPass={} handPatch={} scaler={} chain={}",
                diagFrames, MetalUpscaler.takeTemporalStat(0), MetalUpscaler.takeTemporalStat(1),
                String.format("%.1f", MetalUpscaler.takeTemporalStat(2) / (double)frames),
                String.format("%.1f", MetalUpscaler.takeTemporalStat(3) / (double)frames), MetalUpscaler.takeTemporalStat(6),
                ms(MetalUpscaler.takeTemporalStat(4), spans), spans, ms(MetalUpscaler.takeTemporalStat(8), spans),
                ms(MetalUpscaler.takeTemporalStat(9), spans), ms(MetalUpscaler.takeTemporalStat(10), spans),
                ms(MetalUpscaler.takeTemporalStat(11), spans), ms(MetalUpscaler.takeTemporalStat(12), spans),
                ms(MetalUpscaler.takeTemporalStat(7), spans)
            );
            diagFrames = 0;
            diagSince = now;
        }
    }

    private static String ms(final long ns, final long samples) {
        return String.format("%.2f", samples == 0 ? 0.0 : ns / 1e6 / samples);
    }

    /** Why temporal upscaling cannot run right now (a translation key), or null when it can. */
    public static @Nullable String unavailableReason() {
        if (shaderPackActive()) {
            return "puremetal.options.upscaler.temporal.shaderPack";
        }
        if (hooksBroken) {
            return "puremetal.options.upscaler.temporal.hooks";
        }
        if (!MetalUpscaler.active()) {
            return null; // Pure Metal itself is not running; the generic note covers that.
        }
        return MetalUpscaler.temporalSupported() ? null : "puremetal.options.upscaler.temporal.unsupported";
    }

    /** An Iris shader pack renders the world with its own projection and buffers, so jittering it is not possible from here. */
    public static boolean shaderPackActive() {
        if (!irisLookedUp) {
            irisLookedUp = true;
            irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
            if (irisLoaded) {
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = api.getMethod("getInstance").invoke(null);
                    irisInUse = api.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException | LinkageError e) {
                    irisInUse = null;
                }
            }
        }
        if (!irisLoaded) {
            return false;
        }
        long now = System.nanoTime();
        if (now - shaderPackCheckedAt < SHADER_PACK_CACHE_NANOS && shaderPackCheckedAt != 0L) {
            return shaderPackCached;
        }
        shaderPackCheckedAt = now;
        if (irisInUse == null) {
            shaderPackCached = true; // Iris is installed but not queryable: assume the worst.
        } else {
            try {
                shaderPackCached = (boolean)irisInUse.invoke(irisApi);
            } catch (ReflectiveOperationException e) {
                shaderPackCached = true;
            }
        }
        return shaderPackCached;
    }

    /** Start of a scaled world frame that will be upscaled temporally; `width`x`height` is the size it is drawn at. */
    public static void beginFrame(final int width, final int height) {
        if (inputWidth != width || inputHeight != height) {
            inputWidth = width;
            inputHeight = height;
            havePrevious = false;
        }
        int phases = Math.clamp((int)Math.ceil(8.0 / Math.pow(ScaleConfig.scale() / 100.0, 2.0)), 8, MAX_PHASES);
        frame = (frame + 1) % phases;
        jitterX = halton(frame + 1, 2) - 0.5F;
        jitterY = halton(frame + 1, 3) - 0.5F;
        active = true;
        prepared = false;
        sawCurrent = false;
        hookLevel = hookPrepare = hookHand = false;
    }

    /** The frame is not drawn temporally (scale 100%, another upscaler, menus): the next temporal frame starts fresh. */
    public static void cancel() {
        active = false;
        prepared = false;
        havePrevious = false;
        previousLevel = null;
    }

    public static final int HOOK_LEVEL = 0;
    public static final int HOOK_PREPARE = 1;
    public static final int HOOK_HAND = 2;

    /** A GameRendererMixin injection ran during a temporal frame. */
    public static void hookRan(final int hook) {
        if (active) {
            switch (hook) {
                case HOOK_LEVEL -> hookLevel = true;
                case HOOK_PREPARE -> hookPrepare = true;
                default -> hookHand = true;
            }
        }
    }

    /**
     * At the end of a temporal frame: all three injections must have run, or another mod changed rendering around them and
     * jitter, motion vectors and hand would disagree. Then Temporal stays off (the menu says why).
     */
    public static boolean hooksIntact() {
        if (hookLevel && hookPrepare && hookHand) {
            return true;
        }
        if (!hooksBroken) {
            hooksBroken = true;
            LOGGER.warn("Pure Metal: MetalFX Temporal turned off, its hooks did not all run (level jitter {}, motion {}, hand jitter {}); another mod changed rendering", hookLevel, hookPrepare, hookHand);
        }
        return false;
    }

    /** The world's projection with this frame's jitter applied. Also records the unjittered view-projection. */
    public static Matrix4f levelProjection(final Matrix4f projection, final CameraRenderState camera) {
        currentVp.set(projection).mul(camera.viewRotationMatrix);
        currentCamera = camera.pos;
        sawCurrent = true;
        return jitter(projection, levelScratch);
    }

    /** The hand's projection with this frame's jitter, drawn into the same image (a wrapper, so the original is left alone). */
    public static Projection handProjection(final Projection projection) {
        handJittered.set(projection);
        return handJittered;
    }

    /** A projection whose matrix is another one's with this frame's jitter. */
    private static final class JitteredProjection extends Projection {
        private Projection source = new Projection();
        private long version;

        void set(final Projection projection) {
            source = projection;
            version++;
        }

        @Override
        public Matrix4f getMatrix(final Matrix4f dest) {
            return jitter(source.getMatrix(dest), dest);
        }

        @Override
        public long getMatrixVersion() {
            return version;
        }

        @Override
        public float zNear() {
            return source.zNear();
        }

        @Override
        public float zFar() {
            return source.zFar();
        }

        @Override
        public float width() {
            return source.width();
        }

        @Override
        public float height() {
            return source.height();
        }

        @Override
        public float fov() {
            return source.fov();
        }

        @Override
        public boolean invertY() {
            return source.invertY();
        }
    }

    /** {@code projection} with a clip-space shift, written to {@code into} (which may be {@code projection} itself). */
    private static Matrix4f jitter(final Matrix4f projection, final Matrix4f into) {
        return into.set(projection).translateLocal(2.0F * jitterX / inputWidth, 2.0F * jitterY / inputHeight, 0.0F);
    }

    /** Before the hand: has MetalFX derive motion vectors and a depth copy from the world's depth. */
    public static boolean prepare(final GpuTextureView depth, final GpuTextureView source, final GpuTextureView destination) {
        Vec3 camera = currentCamera;
        if (!active || !sawCurrent || camera == null) {
            return false;
        }
        Object level = Minecraft.getInstance().level;
        reset = !havePrevious || previousLevel == null || previousLevel.get() != level || previousCamera == null || camera.distanceToSqr(previousCamera) > CUT_DISTANCE * CUT_DISTANCE;
        if (reset) {
            previousVp.set(currentVp);
            previousCamera = camera;
        }
        inverseScratch.set(inverseDouble.set(currentVp).invert()).get(params, 0);
        previousVp.get(params, 16);
        params[32] = (float)(camera.x - previousCamera.x);
        params[33] = (float)(camera.y - previousCamera.y);
        params[34] = (float)(camera.z - previousCamera.z);
        params[35] = 0.0F;
        params[36] = ScaleConfig.temporalOutputScale();
        params[37] = DIAG ? 1.0F : 0.0F;
        if (previousLevel == null || previousLevel.get() != level) {
            previousLevel = new WeakReference<>(level);
        }
        prepared = MetalUpscaler.temporalPrepare(depth, source, destination, params);
        return prepared;
    }

    /** At the end of the world. Returns false when MetalFX did not draw (the caller upscales another way). */
    public static boolean upscale(final GpuTextureView source, final @Nullable GpuTextureView handDepth, final GpuTextureView destination) {
        boolean ok = prepared && MetalUpscaler.temporalUpscale(source, handDepth, destination, -jitterX * FLIP_X, jitterY * FLIP_Y, reset);
        prepared = false;
        active = false;
        havePrevious = ok;
        if (DIAG && ok) {
            diagFrame();
        }
        if (ok) {
            previousVp.set(currentVp);
            previousCamera = currentCamera;
        }
        return ok;
    }

    private static float halton(final int index, final int base) {
        float fraction = 1.0F;
        float result = 0.0F;
        int i = index;
        while (i > 0) {
            fraction /= base;
            result += fraction * (i % base);
            i /= base;
        }
        return result;
    }
}
