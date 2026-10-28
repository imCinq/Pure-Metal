package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import io.github.imcinq.puremetal.bench.PresentationProfile;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** The window's CAMetalLayer (via SDL's Metal view). Present is scheduled on the frame's command buffer. */
final class MetalSurface implements GpuSurfaceBackend {
    private final MetalDevice device;
    private final long view;
    private final long surface;
    private boolean closed;
    /** Dev aid: -Dpuremetal.capture=<frame> saves that presented frame to puremetal-capture.png in the game dir. */
    private static final int CAPTURE_FRAME = Integer.getInteger("puremetal.capture", -1);
    /** -Dpuremetal.allowTearing=true: with vsync off, present immediately (tears) instead of at the next refresh. */
    private static final boolean ALLOW_TEARING = Boolean.getBoolean("puremetal.allowTearing");
    /** Dev aid: -Dpuremetal.noPresent=true renders frames without showing them, to isolate presentation costs. */
    private static final boolean NO_PRESENT = Boolean.getBoolean("puremetal.noPresent");
    private int frame;
    private static final boolean PRESENT_STATS = Debug.ENABLED || io.github.imcinq.puremetal.bench.Bench.SECONDS > 0 || PresentationProfile.ENABLED;
    private long statsStart;
    private int statsFrames;
    private int configuredWidth, configuredHeight;
    private boolean configuredVsync;

    MetalSurface(final MetalDevice device, final long windowHandle) {
        this.device = device;
        FrameTrace.init();
        this.view = SDLMetal.SDL_Metal_CreateView(windowHandle);
        if (this.view == 0L) {
            throw new IllegalStateException("SDL could not create a Metal view: " + SDLError.SDL_GetError());
        }
        long layer = SDLMetal.SDL_Metal_GetLayer(this.view);
        this.surface = MetalNative.surfaceCreate(device.handle(), layer);
        if (this.surface == 0L) {
            SDLMetal.SDL_Metal_DestroyView(this.view);
            throw new IllegalStateException("Pure Metal could not set up presenting (see the log)");
        }
        device.presentSurface = this.surface;
        device.liveSurface = this;
    }

    @Override
    public void configure(final GpuSurface.Configuration config) {
        boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO || config.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
        this.configuredWidth = config.width();
        this.configuredHeight = config.height();
        this.configuredVsync = vsync;
        PresentationProfile.Configuration event = PresentationProfile.ENABLED ? new PresentationProfile.Configuration() : null;
        if (event != null) {
            event.width = config.width();
            event.height = config.height();
            event.vsync = vsync;
            event.begin();
        }
        FrameTrace.mark(FrameTrace.CFG_ENTER, config.width(), config.height(), vsync ? 1L : 0L);
        MetalNative.surfaceConfigure(this.surface, config.width(), config.height(), vsync ? 1 : 0, ALLOW_TEARING ? 1 : 0);
        FrameTrace.mark(FrameTrace.CFG_EXIT, 0);
        if (event != null) event.commit();
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
        if (NO_PRESENT) {
            return;
        }
        if (PRESENT_STATS) {
            this.logPresentStats();
        }
        if (io.github.imcinq.puremetal.bench.Bench.SECONDS > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.LongBuffer gpu = stack.mallocLong(2);
                MetalNative.surfaceTakePresentGpu(this.surface, MemoryUtil.memAddress(gpu));
                io.github.imcinq.puremetal.bench.Bench.presentGpuNs += gpu.get(0) * 1000L;
                io.github.imcinq.puremetal.bench.Bench.presentGpuCount += gpu.get(1);
            }
        }
        FrameTrace.mark(FrameTrace.FRAME, 0);
    }

    /** Once a second: frames rendered vs presented vs actually shown, and why the rest were not presented. */
    private void logPresentStats() {
        long now = System.nanoTime();
        this.statsFrames++;
        if (this.statsStart == 0L) {
            this.statsStart = now;
            return;
        }
        if (now - this.statsStart < 1_000_000_000L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer st = stack.mallocLong(5);
            MetalNative.surfaceTakeStats(this.surface, MemoryUtil.memAddress(st));
            double secs = (now - this.statsStart) / 1e9;
            long shown = st.get(3);
            long drawableWaitMax = FrameTrace.ENABLED ? MetalNative.takeStat(this.device.handle(), 38) : 0L;
            long layerConfigureMax = FrameTrace.ENABLED ? MetalNative.takeStat(this.device.handle(), 39) : 0L;
            if (PresentationProfile.ENABLED) {
                PresentationProfile event = new PresentationProfile();
                if (event.isEnabled()) {
                    event.intervalNs = now - this.statsStart;
                    event.width = this.configuredWidth;
                    event.height = this.configuredHeight;
                    event.vsync = this.configuredVsync;
                    event.focused = net.minecraft.client.Minecraft.getInstance().isWindowActive();
                    event.nativeTimingEnabled = FrameTrace.ENABLED;
                    event.rendered = this.statsFrames;
                    event.skipped = st.get(0);
                    event.slotsBusy = st.get(1);
                    event.presented = st.get(2);
                    event.shown = shown;
                    event.submitToShownSumNs = st.get(4) * 1000L;
                    event.drawableWaitMaxNs = drawableWaitMax;
                    event.layerConfigureMaxNs = layerConfigureMax;
                    event.commit();
                }
            }
            org.slf4j.LoggerFactory.getLogger(MetalSurface.class).info(
                "PRESENT rendered/s={} presented/s={} shown/s={} noDrawable/s={} slotsBusy/s={} presentToShownMs={}",
                Math.round(this.statsFrames / secs), Math.round(st.get(2) / secs), Math.round(shown / secs),
                Math.round(st.get(0) / secs), Math.round(st.get(1) / secs), shown == 0 ? "n/a" : String.format("%.1f", st.get(4) / 1000.0 / shown)
            );
            if (FrameTrace.ENABLED) {
                org.slf4j.LoggerFactory.getLogger(MetalSurface.class).info(
                    "PRESENT-TIMING drawableWaitMaxMs={} layerConfigureMaxMs={} size={}x{} vsync={}",
                    String.format("%.2f", drawableWaitMax / 1e6), String.format("%.2f", layerConfigureMax / 1e6),
                    this.configuredWidth, this.configuredHeight, this.configuredVsync
                );
            }
        }
        this.statsStart = now;
        this.statsFrames = 0;
    }

    @Override
    public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
        if (++this.frame == CAPTURE_FRAME) {
            this.capture((MetalCommandEncoder)commandEncoder, (MetalTextureView)textureView);
        }
        if (NO_PRESENT) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer err = stack.calloc(512);
            MetalTextureView view = (MetalTextureView)textureView;
            int ok = MetalNative.surfaceBlit(
                this.device.handle(), this.surface, view.handle(), MemoryUtil.memAddress(err), err.capacity(),
                this.directPresentAllowed(view.metalTexture()) ? 1 : 0
            );
            if (ok == 2) {
                view.metalTexture().presentedDirect();
            }
            if (ok == 0 && err.get(0) != 0) {
                throw new IllegalStateException("Pure Metal could not present: " + MemoryUtil.memUTF8(MemoryUtil.memAddress(err)));
            }
        }
    }

    private boolean directPresentAllowed(final MetalTexture texture) {
        return this.device.directPresent && !this.device.directPresentDisabled && texture.canPresentDirect();
    }

    private void capture(final MetalCommandEncoder encoder, final MetalTextureView view) {
        MetalTexture texture = view.metalTexture();
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        int texel = texture.getFormat().blockSize();
        if (texel != 4) {
            org.slf4j.LoggerFactory.getLogger(MetalSurface.class).warn("Pure Metal capture: unsupported format {}", texture.getFormat());
            return;
        }
        MetalBuffer buffer = new MetalBuffer(this.device, com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_MAP_READ, (long)width * height * 4, null);
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            try {
                java.nio.file.Path out = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("puremetal-capture.png");
                // Render targets use Vulkan's row order, so flip like the present pass.
                org.lwjgl.stb.STBImageWrite.stbi_flip_vertically_on_write(true);
                org.lwjgl.stb.STBImageWrite.stbi_write_png(out.toString(), width, height, 4, MemoryUtil.memByteBuffer(buffer.contents(), width * height * 4), width * 4);
                org.slf4j.LoggerFactory.getLogger(MetalSurface.class).info("Pure Metal capture: frame {} saved to {}", CAPTURE_FRAME, out.getFileName());
            } finally {
                org.lwjgl.stb.STBImageWrite.stbi_flip_vertically_on_write(false);
                buffer.close();
            }
        }, 0);
    }

    @Override
    public void present() {
        // Already scheduled with presentDrawable when the frame was blitted; it shows once that work completes.
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
    }

    @Override
    public synchronized void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.device.presentSurface == this.surface) {
                this.device.presentSurface = 0L;
            }
            MetalNative.surfaceDestroy(this.surface);
            SDLMetal.SDL_Metal_DestroyView(this.view);
            // Only after teardown: a concurrent MetalDevice.close() then still finds this surface and waits on its monitor.
            if (this.device.liveSurface == this) {
                this.device.liveSurface = null;
            }
        }
    }
}
