package io.github.imcinq.puremetal.backend;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Records into one Metal command buffer per submit, in call order. Copies use blit encoders between render passes,
 * which gives the same ordering the Vulkan backend gets from its barriers. Up to {@link #SUBMITS_IN_FLIGHT} submits are in flight.
 */
final class MetalCommandEncoder implements CommandEncoderBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Like vanilla Vulkan. Each extra submit adds a full GPU frame of input latency when GPU-bound (3 measured
     * ~70 ms present-to-screen at 45 FPS); presenting no longer stalls the queue, so 2 keeps the GPU fed.
     */
    static final long SUBMITS_IN_FLIGHT = Math.clamp(Long.getLong("puremetal.submitsInFlight", 2L), 1L, 4L);
    private static final long SUBMIT_TIMEOUT_NS = 5_000_000_000L;

    private record Deferred(long submitIndex, Runnable action) {
    }

    private final MetalDevice device;
    private final MetalTransientMemory transientMemory;
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    private long currentSubmitIndex = 1L;
    private @Nullable MetalRenderPass currentRenderPass;
    private boolean warnedUnalignedWrite;

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        // Pooled buffers closed while recording submit 1 must wait for it, so the tag starts at this encoder's index.
        MetalBuffer.recordingSubmit = this.currentSubmitIndex;
        this.transientMemory = new MetalTransientMemory(device, this);
    }

    long handle() {
        return this.device.handle();
    }

    MetalDevice device() {
        return this.device;
    }

    /** Runs {@code action} once the GPU has finished every command recorded so far. */
    void runWhenSubmitDone(final Runnable action) {
        this.deferred.add(new Deferred(this.currentSubmitIndex, action));
    }

    private void runCompleted() {
        this.runCompleted(MetalNative.completed(this.handle()));
    }

    private void runCompleted(final long completed) {
        Throwable failure = null;
        while (!this.deferred.isEmpty() && this.deferred.peekFirst().submitIndex() <= completed) {
            try {
                this.deferred.pollFirst().action().run();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private void checkNotInRenderPass(final String what) {
        if (this.currentRenderPass != null) {
            throw new IllegalStateException("Cannot " + what + " while inside a render pass");
        }
    }

    void destroy() {
        if (this.currentRenderPass != null) {
            this.currentRenderPass.end();
            MetalNative.passEnd(this.handle());
            this.currentRenderPass = null;
        }
        long last = this.currentSubmitIndex;
        MetalNative.submit(this.handle(), last);
        this.transientMemory.endSubmit();
        if (MetalNative.waitSubmit(this.handle(), last, SUBMIT_TIMEOUT_NS) == 0 && MetalNative.waitIdle(this.handle()) == 0) {
            // Retain unfinished resources rather than running readback callbacks or freeing GPU-owned scratch.
            throw new IllegalStateException("Pure Metal: GPU shutdown could not drain; scratch retained");
        }
        try {
            // A successful queue barrier proves completion even if native submit callbacks are still catching up.
            this.runCompleted(last);
        } finally {
            this.transientMemory.destroy();
        }
    }

    @Override
    public void submit() {
        this.checkNotInRenderPass("submit");
        this.transientMemory.endSubmit();
        MetalNative.submit(this.handle(), this.currentSubmitIndex);
        Debug.endSubmit(this.currentSubmitIndex, Debug.ENABLED ? MetalNative.deviceAllocatedSize(this.device.handle()) : 0L);
        this.currentSubmitIndex++;
        MetalBuffer.recordingSubmit = this.currentSubmitIndex;
        if (MetalNative.deviceFailed(this.handle()) != 0) {
            throw new GpuDeviceLossException("Metal command buffer failed: " + this.lastError());
        }
        if (io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED) {
            try(MemoryStack stack=MemoryStack.stackPush()) {
                LongBuffer stats=stack.mallocLong(6);
                MetalNative.takeProbeStats(this.handle(),org.lwjgl.system.MemoryUtil.memAddress(stats));
            io.github.imcinq.puremetal.bench.TerrainProbeProfile.record(
                stats.get(0),stats.get(1),stats.get(2),stats.get(3),stats.get(4),stats.get(5));
            }
        }
        long waitFor = this.currentSubmitIndex - SUBMITS_IN_FLIGHT;
        long waitStart = System.nanoTime();
        FrameTrace.mark(FrameTrace.WAIT_ENTER, waitFor);
        if (waitFor > 0L && MetalNative.waitSubmit(this.handle(), waitFor, SUBMIT_TIMEOUT_NS) == 0) {
            throw new IllegalStateException("Pure Metal: the GPU did not finish submit " + waitFor + " within 5s");
        }
        FrameTrace.mark(FrameTrace.WAIT_EXIT, waitFor);
        if (io.github.imcinq.puremetal.bench.Bench.SECONDS > 0 || io.github.imcinq.puremetal.bench.SubmissionProfile.ENABLED) {
            long wait = System.nanoTime() - waitStart;
            long gpu = MetalNative.takeGpuNs(this.handle());
            long gpuFrames = MetalNative.takeGpuFrames(this.handle());
            long uploadGpu = MetalNative.takeUploadGpuNs(this.handle());
            long uploads = MetalNative.takeUploadGpuCommands(this.handle());
            // The pass-timing bins are taken once and used by both consumers: the JFR event and the bench line.
            long p0 = MetalNative.takeProfileStat(this.handle(), 0), p1 = MetalNative.takeProfileStat(this.handle(), 1);
            long p2 = MetalNative.takeProfileStat(this.handle(), 2), p3 = MetalNative.takeProfileStat(this.handle(), 3);
            long p4 = MetalNative.takeProfileStat(this.handle(), 4), p5 = MetalNative.takeProfileStat(this.handle(), 5);
            if (io.github.imcinq.puremetal.bench.SubmissionProfile.ENABLED) {
                io.github.imcinq.puremetal.bench.SubmissionProfile.record(wait, gpu, uploadGpu, gpuFrames, uploads, p0, p1, p2, p3, p4, p5);
            }
            if (io.github.imcinq.puremetal.bench.Bench.SECONDS > 0) {
                io.github.imcinq.puremetal.bench.Bench.submitWaitNs += wait;
                io.github.imcinq.puremetal.bench.Bench.gpuNs += gpu;
                io.github.imcinq.puremetal.bench.Bench.gpuFrames += gpuFrames;
                io.github.imcinq.puremetal.bench.Bench.uploadGpuNs += uploadGpu;
                io.github.imcinq.puremetal.bench.Bench.uploadGpuCommands += uploads;
                io.github.imcinq.puremetal.bench.Bench.profileSpanNs += p0;
                io.github.imcinq.puremetal.bench.Bench.profileFragTailNs += p1;
                io.github.imcinq.puremetal.bench.Bench.profileBlitNs += p2;
                io.github.imcinq.puremetal.bench.Bench.profileSpanSamples += p3;
                io.github.imcinq.puremetal.bench.Bench.profileFragTailSamples += p4;
                io.github.imcinq.puremetal.bench.Bench.profileBlitSamples += p5;
                for (int i = 0; i < io.github.imcinq.puremetal.bench.Bench.stats.length; i++) {
                    io.github.imcinq.puremetal.bench.Bench.stats[i] += MetalNative.takeStat(this.handle(), i);
                }
                io.github.imcinq.puremetal.bench.Bench.frameFences += MetalNative.takeStat(this.handle(), 28);
                for (int i = 0; i < io.github.imcinq.puremetal.bench.Bench.encoders.length; i++) {
                    io.github.imcinq.puremetal.bench.Bench.encoders[i] += MetalNative.takeStat(this.handle(), 29 + i);
                }
                io.github.imcinq.puremetal.bench.Bench.surfaceBlockedNs += MetalNative.takeStat(this.handle(), 35);
                io.github.imcinq.puremetal.bench.Bench.surfaceBlockedWaits += MetalNative.takeStat(this.handle(), 36);
                io.github.imcinq.puremetal.bench.Bench.profileDroppedSamples += MetalNative.takeStat(this.handle(), 37);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer trace = stack.calloc(32768);
                    int length = MetalNative.takePassTrace(this.handle(), MemoryUtil.memAddress(trace), trace.capacity());
                    if (length > 0) {
                        byte[] bytes = new byte[length];
                        trace.get(0, bytes);
                        io.github.imcinq.puremetal.bench.Bench.addPassTrace(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
        }
        this.runCompleted();
    }

    private String lastError() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer out = stack.calloc(512);
            MetalNative.deviceLastError(this.handle(), MemoryUtil.memAddress(out), out.capacity());
            return MemoryUtil.memUTF8(MemoryUtil.memAddress(out));
        }
    }

    @Override
    public TransientMemory transientMemory() {
        return this.transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        this.checkNotInRenderPass("start a render pass");
        List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        int width = 0;
        int height = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int n = colors.size();
            LongBuffer textures = stack.callocLong(Math.max(n, 1));
            IntBuffer loads = stack.mallocInt(Math.max(n, 1));
            FloatBuffer clears = stack.callocFloat(Math.max(n, 1) * 4);
            for (int i = 0; i < n; i++) {
                RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
                if (attachment == null) {
                    loads.put(i, -1);
                    continue;
                }
                MetalTextureView view = (MetalTextureView)attachment.textureView();
                width = view.getWidth(0);
                height = view.getHeight(0);
                view.metalTexture().beforeWrite(attachment.clearValue().isPresent());
                textures.put(i, view.handle());
                if (attachment.clearValue().isPresent()) {
                    Vector4fc c = attachment.clearValue().get();
                    loads.put(i, 1);
                    clears.put(i * 4, c.x()).put(i * 4 + 1, c.y()).put(i * 4 + 2, c.z()).put(i * 4 + 3, c.w());
                } else {
                    loads.put(i, 0);
                }
            }
            long depthHandle = 0L;
            int depthLoad = 0;
            double clearDepth = 0.0;
            int depthFormat = -1;
            if (depth != null) {
                MetalTextureView view = (MetalTextureView)depth.textureView();
                depthHandle = view.handle();
                depthFormat = MetalConst.format(view.texture().getFormat());
                if (n == 0) {
                    width = view.getWidth(0);
                    height = view.getHeight(0);
                }
                if (depth.clearValue().isPresent()) {
                    depthLoad = 1;
                    clearDepth = depth.clearValue().getAsDouble();
                }
            }
            RenderPass.RenderArea area = descriptor.renderArea();
            long label = this.device.debugLabels() || io.github.imcinq.puremetal.bench.SubmissionProfile.ENABLED || io.github.imcinq.puremetal.bench.Bench.SECONDS > 0 ? MemoryUtil.memAddress(stack.UTF8Safe(descriptor.label().get())) : 0L;
            int ok = MetalNative.passBegin(
                this.handle(), n, MemoryUtil.memAddress(textures), MemoryUtil.memAddress(loads), MemoryUtil.memAddress(clears),
                depthHandle, depthLoad, clearDepth, width, height, area.x(), area.y(), area.width(), area.height(), label
            );
            if (ok == 0) {
                throw new IllegalStateException("Metal could not start render pass " + descriptor.label().get());
            }
            this.currentRenderPass = new MetalRenderPass(this, area, width, height, depthFormat);
            Debug.passes++;
        }
        return this.currentRenderPass;
    }

    @Override
    public void submitRenderPass() {
        if (this.currentRenderPass == null) {
            throw new IllegalStateException("Cannot submit a renderpass if one hasn't been started!");
        }
        this.currentRenderPass.end();
        MetalNative.passEnd(this.handle());
        this.currentRenderPass = null;
    }

    @Override
    public void clearColorTexture(final GpuTexture texture, final Vector4fc color) {
        this.checkNotInRenderPass("clear a texture");
        ((MetalTexture)texture).beforeWrite(true);
        MetalNative.clearTexture(this.handle(), ((MetalTexture)texture).handle(), color.x(), color.y(), color.z(), color.w(), 0.0);
    }

    @Override
    public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
        this.clearColorTexture(colorTexture, clearColor);
        this.clearDepthTexture(depthTexture, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
        final GpuTexture colorTexture,
        final Vector4fc clearColor,
        final GpuTexture depthTexture,
        final double clearDepth,
        final int regionX,
        final int regionY,
        final int regionWidth,
        final int regionHeight,
        final int mipLevel
    ) {
        this.checkNotInRenderPass("clear a texture region");
        // Metal has no "clear rectangle" outside a draw, so fill the region from a staging buffer instead.
        if (!this.fillRegion(colorTexture, clearColor, 0.0, regionX, regionY, regionWidth, regionHeight, mipLevel)
            | !this.fillRegion(depthTexture, clearColor, clearDepth, regionX, regionY, regionWidth, regionHeight, mipLevel)) {
            LOGGER.warn("Pure Metal: region clear of {} / {} not supported for these formats, clearing whole textures",
                colorTexture.getFormat(), depthTexture.getFormat());
            this.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
        }
    }

    private boolean fillRegion(
        final GpuTexture texture, final Vector4fc color, final double depth, final int x, final int y, final int width, final int height, final int mip
    ) {
        GpuFormat format = texture.getFormat();
        int texel = format.blockSize();
        ByteBuffer pixel = MemoryUtil.memAlloc(16);
        try {
            switch (format) {
                case RGBA8_UNORM -> pixel.put(unorm8(color.x())).put(unorm8(color.y())).put(unorm8(color.z())).put(unorm8(color.w()));
                case R8_UNORM -> pixel.put(unorm8(color.x()));
                case RGBA16_FLOAT -> pixel.putShort(half(color.x())).putShort(half(color.y())).putShort(half(color.z())).putShort(half(color.w()));
                case R32_FLOAT -> pixel.putFloat(color.x());
                case RGBA32_FLOAT -> pixel.putFloat(color.x()).putFloat(color.y()).putFloat(color.z()).putFloat(color.w());
                case D32_FLOAT -> pixel.putFloat((float)depth);
                case D16_UNORM -> pixel.putShort((short)Math.round(Math.clamp(depth, 0.0, 1.0) * 65535.0));
                default -> {
                    return false;
                }
            }
            long size = (long)texel * width * height;
            GpuBufferSlice.MappedView staging = this.transientMemory.allocateGpuMapped(size, Math.max(4, texel), GpuBuffer.USAGE_COPY_SRC);
            long base = MemoryUtil.memAddress(staging.data());
            for (long offset = 0; offset < size; offset += texel) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(pixel, 0), base + offset, texel);
            }
            ((MetalTexture)texture).beforeWrite(false);
            MetalNative.copyBufferToTexture(
                this.handle(), ((MetalBuffer)staging.slice().buffer()).use().handle(), staging.slice().offset(), texel * width, height,
                ((MetalTexture)texture).handle(), mip, 0, x, y, width, height
            );
            return true;
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }

    private static byte unorm8(final float v) {
        return (byte)Math.round(Math.clamp(v, 0.0F, 1.0F) * 255.0F);
    }

    private static short half(final float v) {
        return Float.floatToFloat16(v);
    }

    @Override
    public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
        this.checkNotInRenderPass("clear a texture");
        MetalNative.clearTexture(this.handle(), ((MetalTexture)depthTexture).handle(), 0.0F, 0.0F, 0.0F, 0.0F, clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        this.checkNotInRenderPass("write to a buffer");
        MetalBuffer target = (MetalBuffer)destination.buffer();
        long dstOffset = destination.offset();
        int size = data.remaining();
        // The CPU knows these bytes, so keep the shadow current; this does not drop it (the copy below is CPU-sourced).
        target.shadowWrite(dstOffset, MemoryUtil.memAddress(data), size);
        if ((dstOffset & 3L) == 0L && (size & 3) == 0) {
            if (target.unusedThisSubmit()) {
                // Nothing recorded this submit touches the target, so the copy can run ahead of the whole frame and
                // the open render pass stays open.
                GpuBufferSlice staging = this.transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
                MetalNative.copyBufferEarly(this.handle(), ((MetalBuffer)staging.buffer()).handle(), staging.offset(), target.handle(), dstOffset, size);
                target.gpuWritten = true;
                return;
            }
            if (io.github.imcinq.puremetal.bench.Bench.SECONDS > 0) {
                io.github.imcinq.puremetal.bench.Bench.countBlit(target.benchLabel, target.usage(), size);
            }
            GpuBufferSlice staging = this.transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
            MetalNative.copyBuffer(this.handle(), ((MetalBuffer)staging.buffer()).use().handle(), staging.offset(), target.handle(), dstOffset, size);
        } else {
            // Metal blits need 4-byte aligned offsets and sizes on macOS. Widen the copy and keep the bytes around
            // the write from the buffer's current contents.
            if (!this.warnedUnalignedWrite) {
                this.warnedUnalignedWrite = true;
                LOGGER.info("Pure Metal: widening an unaligned buffer write ({} bytes at offset {})", size, dstOffset);
            }
            if (io.github.imcinq.puremetal.bench.Bench.SECONDS > 0) {
                io.github.imcinq.puremetal.bench.Bench.countBlit(target.benchLabel, target.usage(), size);
            }
            long start = dstOffset & ~3L;
            if (dstOffset < 0L || size < 0L || dstOffset > target.size() || size > target.size() - dstOffset) {
                throw new IllegalArgumentException("Cannot write " + size + " bytes at offset " + dstOffset + " of a " + target.size() + " byte buffer");
            }
            // The allocation is padded to 16 bytes (see MetalBuffer), so the widened end can always be 4-aligned.
            // Limitation: a buffer without a shadow that an earlier command in this submit already wrote on the GPU
            // has stale neighbour bytes in contents(); the widened copy would write those back.
            long end = (dstOffset + size + 3L) & ~3L;
            int widened = (int)(end - start);
            GpuBufferSlice.MappedView staging = this.transientMemory.allocateGpuMapped(widened, 4L, GpuBuffer.USAGE_COPY_SRC);
            long base = MemoryUtil.memAddress(staging.data());
            // Read the bytes around the write from the shadow when it is current: the MTLBuffer may lag pending copies.
            MemoryUtil.memCopy((target.shadow() != 0L ? target.shadow() : target.contents()) + start, base, widened);
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), base + (dstOffset - start), size);
            MetalNative.copyBuffer(this.handle(), ((MetalBuffer)staging.slice().buffer()).use().handle(), staging.slice().offset(), target.handle(), start, widened);
        }
        target.use().gpuWritten = true;
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        this.checkNotInRenderPass("copy a buffer");
        if (io.github.imcinq.puremetal.bench.Bench.SECONDS > 0) {
            MetalBuffer t = (MetalBuffer)target.buffer();
            io.github.imcinq.puremetal.bench.Bench.countBlit("copy " + ((MetalBuffer)source.buffer()).benchLabel + " -> " + t.benchLabel, t.usage(), source.length());
        }
        MetalBuffer src = (MetalBuffer)source.buffer();
        MetalBuffer dst = (MetalBuffer)target.buffer();
        if (src.unusedThisSubmit() && dst.unusedThisSubmit()) {
            // Nothing recorded this submit touches either buffer, so the copy can run ahead of the whole frame (like
            // writeToBuffer's early path) and the open render pass stays open.
            MetalNative.copyBufferEarly(this.handle(), src.handle(), source.offset(), dst.handle(), target.offset(), source.length());
            dst.markGpuWritten();
            return;
        }
        MetalNative.copyBuffer(
            this.handle(), ((MetalBuffer)source.buffer()).use().handle(), source.offset(), ((MetalBuffer)target.buffer()).use().handle(), target.offset(), source.length()
        );
        ((MetalBuffer)target.buffer()).markGpuWritten();
    }

    @Override
    public void writeToTexture(
        final GpuTexture destination, final ByteBuffer source, final int mipLevel, final int depthOrLayer, final int destX, final int destY,
        final int width, final int height
    ) {
        this.checkNotInRenderPass("write to a texture");
        int texel = destination.getFormat().blockSize();
        GpuBufferSlice staging = this.transientMemory.uploadStaging(source, Math.max(4, texel), GpuBuffer.USAGE_COPY_SRC);
        ((MetalTexture)destination).beforeWrite(false);
        MetalNative.copyBufferToTexture(
            this.handle(), ((MetalBuffer)staging.buffer()).use().handle(), staging.offset(), width * texel, height,
            ((MetalTexture)destination).handle(), mipLevel, depthOrLayer, destX, destY, width, height
        );
    }

    @Override
    public void copyBufferToTexture(
        final GpuBufferSlice source,
        final int sourceX,
        final int sourceY,
        final int sourceWidth,
        final int sourceHeight,
        final GpuTexture destination,
        final int destinationX,
        final int destinationY,
        final int copyWidth,
        final int copyHeight,
        final int mipLevel,
        final int arrayLayer
    ) {
        this.checkNotInRenderPass("copy to a texture");
        int texel = destination.getFormat().blockSize();
        long skip = ((long)sourceX + (long)sourceY * sourceWidth) * texel;
        ((MetalTexture)destination).beforeWrite(false);
        MetalNative.copyBufferToTexture(
            this.handle(), ((MetalBuffer)source.buffer()).use().handle(), source.offset() + skip, sourceWidth * texel, copyHeight,
            ((MetalTexture)destination).handle(), mipLevel, arrayLayer, destinationX, destinationY, copyWidth, copyHeight
        );
    }

    @Override
    public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
        final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel,
        final int x, final int y, final int width, final int height
    ) {
        this.checkNotInRenderPass("copy from a texture");
        MetalBuffer target = ((MetalBuffer)destination).use();
        MetalNative.copyTextureToBuffer(
            this.handle(), ((MetalTexture)source).handle(), mipLevel, 0, x, y, width, height, target.handle(), offset, width * source.getFormat().blockSize()
        );
        target.markGpuWritten();
        this.runWhenSubmitDone(callback);
    }

    @Override
    public void copyTextureToTexture(
        final GpuTexture source, final GpuTexture destination, final int mipLevel, final int destX, final int destY,
        final int sourceX, final int sourceY, final int width, final int height
    ) {
        this.checkNotInRenderPass("copy a texture");
        ((MetalTexture)destination).beforeWrite(false);
        MetalNative.copyTextureToTexture(
            this.handle(), ((MetalTexture)source).handle(), ((MetalTexture)destination).handle(), mipLevel, sourceX, sourceY, destX, destY, width, height
        );
    }

    @Override
    public GpuFence createFence() {
        final long submitIndex = this.currentSubmitIndex;
        return new GpuFence() {
            private boolean completed;

            @Override
            public boolean awaitCompletion(final long timeoutNs) {
                if (this.completed) {
                    return true;
                }
                if (submitIndex >= MetalCommandEncoder.this.currentSubmitIndex) {
                    if (timeoutNs == 0L) {
                        return false;
                    }
                    throw new IllegalStateException("Cannot wait on a fence for the current submit");
                }
                this.completed = MetalNative.waitSubmit(MetalCommandEncoder.this.handle(), submitIndex, timeoutNs) != 0;
                return this.completed;
            }

            @Override
            public void close() {
                this.completed = true;
            }
        };
    }

    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
    }

    MetalTransientMemory transientMemoryImpl() {
        return this.transientMemory;
    }
}
