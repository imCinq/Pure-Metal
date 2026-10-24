package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.Redact;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import io.github.imcinq.puremetal.bench.Bench;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

final class MetalDevice implements GpuDeviceBackend {
    /**
     * Strict IEEE shader math (GLSL semantics). -Dpuremetal.safeMath=false compiles with relaxed math instead; measured
     * no faster on an M2, so safe stays the default.
     */
    private static final boolean SAFE_MATH = !"false".equals(System.getProperty("puremetal.safeMath"));
    private static final Logger LOGGER = LogUtils.getLogger();
    private final long handle;
    private final boolean debugLabels;
    private final DeviceInfo deviceInfo;
    private final MetalCommandEncoder commandEncoder;
    private final Object compilationLock = new Object();
    private int activeCompilations;
    private boolean shuttingDown;
    private final MetalResourceCache<SamplerKey> samplers = new MetalResourceCache<>();
    private record ShaderKey(String sourceHash, String entryPoint, boolean safeMath, int constantId, int constantValue) {}
    private record DepthKey(int enabled, int compare, int write) {}
    private final MetalResourceCache<ShaderKey> functions = new MetalResourceCache<>();
    private final MetalResourceCache<DepthKey> depthStates = new MetalResourceCache<>();
    /** A closed native buffer, reusable once the submit that last referenced it (tag) has completed. */
    private record PooledBuffer(long handle, long tag) {}
    private static final long POOL_CAP_BYTES = 512L << 20;
    private static final long POOL_MAX_BUFFER_BYTES = 64L << 20;
    /** Guarded by itself. Keyed by padded size; FIFO, so tags are non-decreasing and the head is the oldest. */
    private final Map<Long, ArrayDeque<PooledBuffer>> bufferPool = new HashMap<>();
    private long bufferPoolBytes;
    private boolean bufferPoolClosed;
    /** The running Metal device, or null when Minecraft uses another backend. */
    static volatile @Nullable MetalDevice current;
    private volatile String gpuName = "";
    private volatile boolean metal4Encoding;

    /** Selects the terrain RGSS early-out shader variant (bit-identical either way); off only for the A/B tool. */
    volatile boolean rgssEarlyOut = true;
    /** Present the main target straight from its texture (it rotates to a spare) instead of through a staging copy. */
    volatile boolean directPresent = !"false".equals(System.getProperty("puremetal.directPresent"));
    /** Set once direct presenting proved unsafe or slow in this session; it stays off (the A/B switch can't revive it). */
    volatile boolean directPresentDisabled;
    private long fallbackWindowStart;
    private int fallbackCount;

    /** A non-clear write had to copy the target into a spare because a present still read it; repeated hits turn direct present off. */
    void directPresentFallback() {
        long now = System.nanoTime();
        if (now - this.fallbackWindowStart > 10_000_000_000L) {
            this.fallbackWindowStart = now;
            this.fallbackCount = 0;
        }
        this.fallbackCount++;
        io.github.imcinq.puremetal.bench.Bench.directFallbacks++;
        if (this.fallbackCount >= 5) {
            this.disableDirectPresent("repeated write-while-presenting fallbacks");
        }
    }

    void disableDirectPresent(final String reason) {
        if (!this.directPresentDisabled) {
            this.directPresentDisabled = true;
            org.slf4j.LoggerFactory.getLogger(MetalDevice.class).info("Pure Metal: direct present disabled for this session ({}), using the staging copy", reason);
        }
    }

    /** The window's native surface, 0 until created; lets textures ask whether a present still reads them. */
    volatile long presentSurface;
    /** The window's surface while open; the device closes it before destroying itself, since the native surface borrows the device. */
    volatile MetalSurface liveSurface;

    MetalDevice(final long handle, final GpuDebugOptions debugOptions) {
        this.handle = handle;
        io.github.imcinq.puremetal.bench.Bench.registerAB("directPresent", on -> this.directPresent = on);
        io.github.imcinq.puremetal.bench.Bench.registerAB("rgssEarlyOut", on -> this.rgssEarlyOut = on);
        io.github.imcinq.puremetal.bench.Bench.registerAB("depthClearFold", on -> MetalNative.setOption(handle, 0, on ? 1 : 0));
        if (Boolean.getBoolean("puremetal.m4FullBarriers")) {
            MetalNative.setOption(handle, 1, 1); // Metal 4 only: every encoder waits for the whole queue (A/B against tracked barriers)
        }
        if (Boolean.getBoolean("puremetal.m4FrameFence")) {
            MetalNative.setOption(handle, 2, 1); // Metal 4 only: the frame's first encoder waits for the whole queue (A/B against cross-frame history)
        }
        if (!Boolean.parseBoolean(System.getProperty("puremetal.m4EventCompletion", "true"))) {
            MetalNative.setOption(handle, 3, 0); // Metal 4 only: learn about completion from the commit feedback alone (A/B against the event timeline)
        }
        if (!Boolean.parseBoolean(System.getProperty("puremetal.m4EarlyUploads", "true"))) {
            MetalNative.setOption(handle, 4, 0); // Metal 4 only: early copies go into the frame's command buffer instead of their own (A/B against the separate upload buffer)
        }
        this.debugLabels = debugOptions.useLabels();
        String name;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer out = stack.calloc(256);
            MetalNative.deviceName(handle, MemoryUtil.memAddress(out), out.capacity());
            name = MemoryUtil.memUTF8(MemoryUtil.memAddress(out));
        }
        this.gpuName = name;
        long maxBuffer = MetalNative.deviceMaxBufferLength(handle);
        this.deviceInfo = new DeviceInfo(
            name,
            "Apple",
            "Metal (Pure Metal)",
            true,
            "Metal",
            1.0F,
            new DeviceLimits(16, 256, 16384, maxBuffer, Integer.MAX_VALUE, 8, Integer.MAX_VALUE),
            // wireframe, shaderDrawParameters, multiDrawDirectInterleaved, multiDrawDirectSeparate,
            // multiDrawIndirect, drawIndirect, nonZeroFirstInstance, persistentMapping
            new DeviceFeatures(true, true, true, true, true, true, true, true),
            Set.of(),
            // writeToBufferIsSlow, anisotropyHasKnownIssues, isExplicitDepthRequired, multiDrawIndirectHasKnownIssues
            new HintsAndWorkarounds(false, false, true, false),
            DeviceType.INTEGRATED
        );
        this.commandEncoder = new MetalCommandEncoder(this);
        LOGGER.info("Pure Metal: running on {} (max buffer {} MB, working set {} MB)", name, maxBuffer >> 20,
            MetalNative.deviceWorkingSet(handle) >> 20);
        current = this;
        this.configureMetal4();
        if (io.github.imcinq.puremetal.bench.SubmissionProfile.ENABLED) {
            LOGGER.info("Pure Metal: GPU stage diagnostics {}", MetalNative.profileEnable(handle) != 0 ? "enabled" : "unsupported");
        }
        if (io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED) {
            try (var source=MetalDevice.class.getResourceAsStream("/assets/puremetal/shaders/terrain_probe.metal");
                 MemoryStack stack=MemoryStack.stackPush()) {
                if(source==null) throw new java.io.IOException("Missing terrain probe shader");
                int enabled=MetalNative.probeEnable(handle,MemoryUtil.memAddress(stack.UTF8(new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))));
                LOGGER.info("Pure Metal: no-cull terrain depth probe {}",enabled!=0?"enabled":"unsupported");
            } catch(java.io.IOException error) {LOGGER.warn("Pure Metal: terrain probe unavailable: {}",Redact.error(error));}
        }
    }

    /**
     * Applies the Metal 4 compile setting and logs which compile path and which command encoding path are active and why.
     * The encoding path was fixed when the device was created, so a change made later only takes effect after a restart.
     */
    void configureMetal4() {
        boolean want = Metal4Config.effective();
        boolean on;
        String reason;
        boolean encoding;
        String encodingReason;
        if (Boolean.getBoolean("puremetal.m4PreciseBarriers")) {
            MetalNative.setOption(this.handle, 5, 1); // Metal 4 only: barriers name only the stages that touch the resource (A/B against vertex+fragment everywhere)
        }
        if (Boolean.getBoolean("puremetal.m4TrackedUploads")) {
            MetalNative.setOption(this.handle, 6, 1); // Metal 4 only: the early-upload encoder waits only for the copies' own resources (A/B against its queue-wide barrier)
        }
        if (!Boolean.parseBoolean(System.getProperty("puremetal.m4Pipelines", "true"))) {
            MetalNative.setOption(this.handle, 7, 0); // Metal 4 only: build libraries and pipelines on the Metal 3 device while keeping Metal 4 command encoding (A/B against MTL4Compiler output)
        }
        int tileSize = Integer.getInteger("puremetal.m4TileSize", 0);
        if (tileSize != 0) {
            MetalNative.setOption(this.handle, 8, tileSize); // Metal 4 only: tile width and height of the render pass descriptors (0, the default, lets Metal choose; invalid sizes are ignored and logged once)
        }
        int barrierLog = Integer.getInteger("puremetal.m4BarrierLog", 0);
        if (barrierLog > 0) {
            MetalNative.setOption(this.handle, 9, barrierLog); // Metal 4 only: dump every barrier and fence of N frames after the skip below, one NSLog line each (diagnostics; 0, the default, logs nothing)
            MetalNative.setOption(this.handle, 10, Integer.getInteger("puremetal.m4BarrierLogSkip", 600)); // Metal 4 only: first frame the dump covers, so the window can start at the bench position (default 600 skips startup and the window's first frames)
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer out = stack.calloc(256);
            on = MetalNative.metal4Enable(this.handle, want ? 1 : 0, MemoryUtil.memAddress(out), out.capacity()) != 0;
            reason = MemoryUtil.memUTF8(MemoryUtil.memAddress(out));
            encoding = MetalNative.metal4Encoding(this.handle, MemoryUtil.memAddress(out), out.capacity()) != 0;
            encodingReason = MemoryUtil.memUTF8(MemoryUtil.memAddress(out));
        }
        this.metal4Encoding = encoding;
        String source = Metal4Config.override() != null ? "-Dpuremetal.metal4" : "Video Settings";
        // The device asked for Metal 4 at startup when it uses it or recorded why it could not.
        boolean askedAtStartup = encoding || !encodingReason.isEmpty();
        String encodingText = encoding ? "Metal 4 (set by " + source + ")"
            : askedAtStartup ? "Metal 3 (Metal 4 unavailable: " + encodingReason + ")"
            : "Metal 3";
        if (want != askedAtStartup) {
            encodingText += " until the game is restarted";
        }
        LOGGER.info("Pure Metal: shader compiler: {}; command encoding: {}", on ? "Metal 4 (set by " + source + ")"
            : want ? "Metal 3 (Metal 4 unavailable: " + reason + ")"
            : "Metal 3 (Metal 4 is off; turn it on in Video Settings or with -Dpuremetal.metal4=true)", encodingText);
        LOGGER.info("Pure Metal: Metal 4 options {}",
            "fullBarriers=" + Boolean.getBoolean("puremetal.m4FullBarriers")
                + " frameFence=" + Boolean.getBoolean("puremetal.m4FrameFence")
                + " eventCompletion=" + Boolean.parseBoolean(System.getProperty("puremetal.m4EventCompletion", "true"))
                + " earlyUploads=" + Boolean.parseBoolean(System.getProperty("puremetal.m4EarlyUploads", "true"))
                + " preciseBarriers=" + Boolean.getBoolean("puremetal.m4PreciseBarriers")
                + " trackedUploads=" + Boolean.getBoolean("puremetal.m4TrackedUploads")
                + " pipelines=" + Boolean.parseBoolean(System.getProperty("puremetal.m4Pipelines", "true"))
                + " tileSize=" + Integer.getInteger("puremetal.m4TileSize", 0)
                + " barrierLog=" + Integer.getInteger("puremetal.m4BarrierLog", 0)
                + " barrierLogSkip=" + Integer.getInteger("puremetal.m4BarrierLogSkip", 600));
    }

    long handle() {
        return this.handle;
    }

    String gpuName() {
        return this.gpuName;
    }

    /** True when this session encodes commands through Metal 4 (decided when the device was created). */
    boolean metal4Encoding() {
        return this.metal4Encoding;
    }

    boolean debugLabels() {
        return this.debugLabels;
    }

    void label(final long object, final @Nullable String label) {
        if (this.debugLabels && label != null && !label.isEmpty() && object != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                MetalNative.setLabel(object, MemoryUtil.memAddress(stack.UTF8Safe(label.length() > 1000 ? label.substring(0, 1000) : label)));
            }
        }
    }

    /** Compiles one MSL entry point. The MSL source can be large, so it goes through the heap, not the stack. */
    long compileFunction(final MetalShaderTranslator.Result msl, final String label) {
        return this.compileFunction(msl, label, -1, 0);
    }

    /** Same, specializing one bool function constant (constantId >= 0). */
    long compileFunction(final MetalShaderTranslator.Result msl, final String label, final int constantId, final int constantValue) {
        if (Debug.DUMP_SHADERS) {
            Debug.dumpShader(label, msl.source());
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(msl.source().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        return this.functions.acquire(new ShaderKey(hash, msl.entryPoint(), SAFE_MATH, constantId, constantValue), () -> this.compileFunctionUncached(msl, label, constantId, constantValue));
    }

    void releaseFunction(final long function) {
        this.functions.release(function);
    }

    long acquireDepthState(final int enabled, final int compare, final int write) {
        return this.depthStates.acquire(new DepthKey(enabled, compare, write),
            () -> MetalNative.depthStateCreate(this.handle, enabled, compare, write));
    }

    void releaseDepthState(final long state) {
        this.depthStates.release(state);
    }

    private long compileFunctionUncached(final MetalShaderTranslator.Result msl, final String label, final int constantId, final int constantValue) {
        ByteBuffer source = MemoryUtil.memUTF8(msl.source());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer err = stack.calloc(2048);
            long library = MetalNative.libraryCreate(this.handle, MemoryUtil.memAddress(source), MemoryUtil.memAddress(err), err.capacity(), SAFE_MATH ? 1 : 0);
            if (library == 0L) {
                throw new IllegalStateException("Metal rejected the translated shader " + label + ": " + MemoryUtil.memUTF8(MemoryUtil.memAddress(err)));
            }
            try {
                long entry = MemoryUtil.memAddress(stack.UTF8(msl.entryPoint()));
                long function = constantId >= 0 ? MetalNative.libraryFunctionBool(library, entry, constantId, constantValue) : MetalNative.libraryFunction(library, entry);
                if (function == 0L) {
                    throw new IllegalStateException("Entry point " + msl.entryPoint() + " missing in " + label);
                }
                return function;
            } finally {
                MetalNative.release(library);
            }
        } finally {
            MemoryUtil.memFree(source);
        }
    }

    @Override
    public GpuSurfaceBackend createSurface(final long windowHandle, final BooleanSupplier isIconified) {
        return new MetalSurface(this, windowHandle);
    }

    @Override
    public MetalCommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public GpuSampler createSampler(
        final AddressMode addressModeU,
        final AddressMode addressModeV,
        final FilterMode minFilter,
        final FilterMode magFilter,
        final int maxAnisotropy,
        final OptionalDouble maxLod
    ) {
        return new MetalSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    private record SamplerKey(int addressU, int addressV, int minFilter, int magFilter, int mipFilter, int anisotropy, float maxLod) {}

    long acquireSamplerState(final int addressU, final int addressV, final int minFilter, final int magFilter,
                             final int mipFilter, final int anisotropy, final float maxLod) {
        int clampedAnisotropy = Math.clamp(anisotropy, 1, 16);
        SamplerKey key = new SamplerKey(addressU, addressV, minFilter, magFilter, mipFilter, clampedAnisotropy, maxLod);
        return this.samplers.acquire(key, () -> MetalNative.samplerCreate(
            this.handle, addressU, addressV, minFilter, magFilter, mipFilter, clampedAnisotropy, maxLod));
    }

    void releaseSamplerState(final long sampler) {
        this.samplers.release(sampler);
    }

    @Override
    public GpuTexture createTexture(
        final @Nullable String label,
        final @GpuTexture.Usage int usage,
        final GpuFormat format,
        final int width,
        final int height,
        final int depthOrLayers,
        final int mipLevels
    ) {
        return new MetalTexture(this, usage, label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalTextureView((MetalTexture)texture, baseMipLevel, mipLevels);
    }

    /**
     * Returns a zeroed MTLBuffer of exactly {@code paddedSize} bytes: a pooled one the GPU has finished with, else a new one.
     * Apple's driver keeps a released MTLBuffer's memory alive, so reuse avoids a leak per create/close cycle.
     */
    long acquireBuffer(final long paddedSize) {
        long reused = 0L;
        synchronized (this.bufferPool) {
            ArrayDeque<PooledBuffer> deque = this.bufferPool.get(paddedSize);
            if (deque != null && !deque.isEmpty() && deque.peekFirst().tag() <= MetalNative.completed(this.handle)) {
                reused = deque.pollFirst().handle();
                if (deque.isEmpty()) this.bufferPool.remove(paddedSize);
                this.bufferPoolBytes -= paddedSize;
            }
        }
        if (reused == 0L) {
            Debug.poolMisses.incrementAndGet();
            return MetalNative.bufferCreate(this.handle, paddedSize);
        }
        Debug.poolHits.incrementAndGet();
        Debug.pooledBytes.addAndGet(-paddedSize);
        // Fresh buffers are zeroed (enableShadow assumes it), so a reused one is cleared too. The GPU is done with it.
        MemoryUtil.memSet(MetalNative.bufferContents(reused), 0, paddedSize);
        return reused;
    }

    /** Takes back a closed buffer. It is parked for reuse, or released now if the pool is full, too big, or closed. */
    void recycleBuffer(final long handle, final long paddedSize) {
        // The GPU may still read it in the submit being recorded, so it is reusable only once that submit completes.
        long tag = MetalBuffer.recordingSubmit;
        synchronized (this.bufferPool) {
            if (!this.bufferPoolClosed && paddedSize <= POOL_MAX_BUFFER_BYTES && this.bufferPoolBytes + paddedSize <= POOL_CAP_BYTES) {
                this.bufferPool.computeIfAbsent(paddedSize, k -> new ArrayDeque<>()).addLast(new PooledBuffer(handle, tag));
                this.bufferPoolBytes += paddedSize;
                Debug.pooledBytes.addAndGet(paddedSize);
                return;
            }
        }
        MetalNative.release(handle);
    }

    /** Releases every parked buffer and refuses new ones. Call only after the GPU has drained. */
    private void closeBufferPool() {
        ArrayDeque<PooledBuffer> all = new ArrayDeque<>();
        synchronized (this.bufferPool) {
            this.bufferPoolClosed = true;
            for (ArrayDeque<PooledBuffer> deque : this.bufferPool.values()) all.addAll(deque);
            this.bufferPool.clear();
            this.bufferPoolBytes = 0L;
            Debug.pooledBytes.set(0L);
        }
        for (PooledBuffer pooled : all) MetalNative.release(pooled.handle());
    }

    /** Releases parked buffers the GPU has finished with, so leaving a world does not keep its memory in the pool. */
    void trimBufferPool() {
        ArrayDeque<PooledBuffer> released = new ArrayDeque<>();
        synchronized (this.bufferPool) {
            if (this.bufferPoolClosed) return;
            long completed = MetalNative.completed(this.handle);
            Iterator<Map.Entry<Long, ArrayDeque<PooledBuffer>>> sizes = this.bufferPool.entrySet().iterator();
            while (sizes.hasNext()) {
                Map.Entry<Long, ArrayDeque<PooledBuffer>> entry = sizes.next();
                Iterator<PooledBuffer> buffers = entry.getValue().iterator();
                while (buffers.hasNext()) {
                    PooledBuffer pooled = buffers.next();
                    if (pooled.tag() > completed) continue;
                    buffers.remove();
                    released.add(pooled);
                    this.bufferPoolBytes -= entry.getKey();
                    Debug.pooledBytes.addAndGet(-entry.getKey());
                }
                if (entry.getValue().isEmpty()) sizes.remove();
            }
        }
        for (PooledBuffer pooled : released) MetalNative.release(pooled.handle());
    }

    @Override
    public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
        MetalBuffer buffer = new MetalBuffer(this, usage, size, this.debugLabels && label != null ? label.get() : null);
        if (Bench.SECONDS > 0 && label != null) buffer.benchLabel = label.get();
        buffer.enableShadow();
        return buffer;
    }

    @Override
    public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
        MetalBuffer buffer = new MetalBuffer(this, usage, data.remaining(), this.debugLabels && label != null ? label.get() : null);
        if (Bench.SECONDS > 0 && label != null) buffer.benchLabel = label.get();
        // A brand-new buffer isn't referenced by any GPU work yet, so a direct copy is safe and ordered.
        buffer.enableShadow();
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.contents(), data.remaining());
        buffer.shadowWrite(0L, MemoryUtil.memAddress(data), data.remaining());
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugLabels;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(final BackendRenderPipeline.CreateInfo createInfo) {
        synchronized (this.compilationLock) {
            if (this.shuttingDown) throw new IllegalStateException("Metal device is closing");
            this.activeCompilations++;
        }
        try {
            MetalPipeline pipeline = MetalPipeline.compile(this, createInfo);
            return () -> pipeline;
        } finally {
            synchronized (this.compilationLock) {
                this.activeCompilations--;
                this.compilationLock.notifyAll();
            }
        }
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (this.compilationLock) {
            if (this.shuttingDown) return;
            this.shuttingDown = true;
            // Track the whole pipeline build, including native state creation after shader acquisition.
            while (this.activeCompilations != 0) {
                try {
                    this.compilationLock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        current = null;
        try {
            this.commandEncoder.destroy();
        } finally {
            try {
                this.samplers.close();
                this.functions.close();
                this.depthStates.close();
            } finally {
                this.closeBufferPool();
                try {
                    MetalSurface surface = this.liveSurface;
                    if (surface != null) {
                        surface.close();
                    }
                } finally {
                    MetalNative.deviceDestroy(this.handle);
                }
            }
        }
    }
}
