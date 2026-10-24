package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

final class MetalTexture extends BaseGpuTexture {
    /** Spare textures kept for a presented target: enough for every present slot plus the one being drawn. */
    private static final int MAX_SPARES = 6;
    private final MetalDevice device;
    private final int formatCode;
    private final @GpuTexture.Usage int usageBits;
    private long handle;
    /** Bumped whenever {@link #handle} changes; views rebuild their native view when they see a new value. */
    int generation;
    private boolean closed;
    /** The native texture is being (or was) read by a present slot without a copy; see {@link #beforeWrite}. */
    private boolean presented;
    private final java.util.ArrayList<Long> spares = new java.util.ArrayList<Long>();

    MetalTexture(
        final MetalDevice device,
        final @GpuTexture.Usage int usage,
        final String label,
        final GpuFormat format,
        final int width,
        final int height,
        final int depthOrLayers,
        final int mipLevels
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.usageBits = usage;
        int code = MetalConst.format(format);
        if (code < 0 || !MetalConst.isPixelFormatSupported(format)) {
            throw new IllegalArgumentException("Pure Metal can't create a " + format + " texture (\"" + label + "\"): Metal has no matching pixel format");
        }
        this.formatCode = code;
        this.handle = MetalNative.textureCreate(device.handle(), code, width, height, depthOrLayers, mipLevels, usage);
        if (this.handle == 0L) {
            throw new com.mojang.renderpearl.api.device.GpuOutOfMemoryException("Could not create Metal texture \"" + label + "\" " + width + "x" + height);
        }
        device.label(this.handle, label);
        if (Debug.ENABLED) Debug.textureCount(label + " " + width + "x" + height, 1);
    }

    long handle() {
        return this.handle;
    }

    boolean canPresentDirect() {
        return !this.closed && this.getDepthOrLayers() == 1 && this.getMipLevels() == 1;
    }

    /** The present path now reads this texture in place; the next write must not touch it while that lasts. */
    void presentedDirect() {
        this.presented = true;
    }

    /**
     * Call before anything writes this texture. If a present slot may still be reading its current contents, the
     * texture switches to a free spare so the frame in flight is never modified: a full overwrite (a clear) just
     * switches, anything else first copies the old contents into the spare on the GPU, in the frame's own command
     * stream (reads of the old texture may overlap the present's reads). Never throws. Only when no spare can be
     * allocated (out of memory, or all spares busy) direct presenting is switched off and this waits for the present
     * to finish reading, however long that takes up to a safety cap of 10 s; if the cap is hit the texture stays
     * marked presented so the next write waits again. Reads (screenshots) need no call: the finished frame stays until then.
     */
    void beforeWrite(final boolean fullOverwrite) {
        if (!this.presented) {
            return;
        }
        long surface = this.device.presentSurface;
        if (surface == 0L || !MetalNative.surfaceTextureBusy(surface, this.handle)) {
            this.presented = false;
            return;
        }
        FrameTrace.mark(FrameTrace.ROT_ENTER, 0);
        try {
            if (this.rotateOrWait(surface, fullOverwrite)) {
                this.presented = false;
            }
        } finally {
            FrameTrace.mark(FrameTrace.ROT_EXIT, 0);
        }
    }

    /** Returns false only if the texture is still being read by a present after the safety cap. */
    private boolean rotateOrWait(final long surface, final boolean fullOverwrite) {
        long old = this.handle;
        if (this.rotate(surface)) {
            if (!fullOverwrite) {
                MetalNative.copyTextureToTexture(
                    this.device.handle(), old, this.handle, 0, 0, 0, 0, 0, this.getWidth(0), this.getHeight(0)
                );
                this.device.directPresentFallback();
            }
            return true;
        }
        this.device.disableDirectPresent("no spare texture available");
        long start = System.nanoTime();
        boolean logged = false;
        while (MetalNative.surfaceTextureBusy(surface, old)) {
            long waited = System.nanoTime() - start;
            if (waited > 10_000_000_000L) {
                org.slf4j.LoggerFactory.getLogger(MetalTexture.class).error("Pure Metal: texture \"{}\" is still being presented after 10 s, writing anyway", this.getLabel());
                return false;
            }
            if (!logged && waited > 250_000_000L) {
                logged = true;
                org.slf4j.LoggerFactory.getLogger(MetalTexture.class).warn("Pure Metal: waiting for a present to finish reading texture \"{}\"", this.getLabel());
            }
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
        return true;
    }

    private boolean rotate(final long surface) {
        long next = 0L;
        for (int i = 0; i < this.spares.size(); i++) {
            if (!MetalNative.surfaceTextureBusy(surface, this.spares.get(i))) {
                next = this.spares.remove(i);
                break;
            }
        }
        if (next == 0L) {
            if (this.spares.size() >= MAX_SPARES) {
                return false;
            }
            next = MetalNative.textureCreate(this.device.handle(), this.formatCode, this.getWidth(0), this.getHeight(0),
                this.getDepthOrLayers(), this.getMipLevels(), this.usageBits);
            if (next == 0L) {
                return false;
            }
            this.device.label(next, this.getLabel());
        }
        this.spares.add(this.handle);
        this.handle = next;
        this.generation++;
        return true;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            if (Debug.ENABLED) Debug.textureCount(this.getLabel() + " " + this.getWidth(0) + "x" + this.getHeight(0), -1);
            MetalNative.release(this.handle);
            for (long spare : this.spares) {
                MetalNative.release(spare);
            }
            this.spares.clear();
        }
    }
}
