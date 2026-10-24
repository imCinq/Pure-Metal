package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-submit scratch memory. Staging, GPU and GPU-mapped allocations are all shared MTLBuffers (unified memory), so
 * one block allocator serves them. Blocks used in a submit are recycled only after that submit has completed.
 */
final class MetalTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 1L << 20;
    private static final long MAX_CPU_ALIGNMENT = 16L;
    private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
    /** Scratch blocks serve every purpose (staging, uniforms, vertices, indices, indirect args), like Vulkan's. */
    private static final int ALL_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_HINT_CLIENT_STORAGE
        | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM
        | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_INDIRECT_PARAMETERS;

    record Block(MetalBuffer buffer) implements TransientBlockAllocator.Allocator.Block {
        @Override
        public boolean suboptimal() {
            return false;
        }
    }

    private final MetalCommandEncoder encoder;
    private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpu = new TransientBlockAllocator<>(
        BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.CpuBlock.memalloc()
    );
    private final TransientBlockAllocator<Block> gpu;

    MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
        this.encoder = encoder;
        this.gpu = new TransientBlockAllocator<>(
            BLOCK_SIZE,
            MAX_GPU_ALIGNMENT,
            TransientBlockAllocator.Allocator.create(size -> new Block(new MetalBuffer(device, ALL_USAGE, size, "Pure Metal transient")), block -> block.buffer().close())
        );
    }

    /** Called when a submit ends: CPU scratch is free at once, GPU blocks once the GPU has finished with them. */
    void endSubmit() {
        this.cpu.rotate().run();
        this.encoder.runWhenSubmitDone(this.gpu.rotate());
    }

    void destroy() {
        this.cpu.close();
        this.gpu.close();
    }

    private GpuBufferSlice slice(final TransientBlockAllocator.Allocation<Block> allocation) {
        return allocation.block().buffer().slice(allocation.offset(), allocation.size());
    }

    @Override
    public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> a = this.cpu.allocate(size, alignment, minimumAllocation, elementSize);
        return MemoryUtil.memByteBuffer(a.block().address() + a.offset(), (int)a.size());
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(
        final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.allocateGpuMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice allocateGpu(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
        return this.slice(this.gpu.allocate(size, alignment, minimumAllocation, elementSize));
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(
        final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        TransientBlockAllocator.Allocation<Block> a = this.gpu.allocate(size, alignment, minimumAllocation, elementSize);
        MetalBuffer buffer = a.block().buffer();
        ByteBuffer data = MemoryUtil.memByteBuffer(buffer.contents() + a.offset(), (int)a.size());
        return new GpuBufferSlice.MappedView(buffer.slice(a.offset(), a.size()), data, () -> {});
    }

    @Override
    public GpuBufferSlice uploadStaging(
        final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.uploadGpu(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(
        final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        // Same layout rules as VulkanTransientMemory.upload: each piece starts aligned, and a partial allocation
        // (minimumAllocation) receives as much as fits.
        long total = 0L;
        for (ByteBuffer buffer : data) {
            total = Mth.roundToward(total + buffer.remaining(), alignment);
        }
        try (GpuBufferSlice.MappedView mapped = this.allocateGpuMapped(total, alignment, usage, minimumAllocation, elementSize)) {
            long base = MemoryUtil.memAddress(mapped.data());
            long length = mapped.slice().length();
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), base + offset, Math.min(length - offset, buffer.remaining()));
                offset = Mth.roundToward(offset + buffer.remaining(), alignment);
                if (offset >= length) {
                    break;
                }
            }
            return mapped.slice();
        }
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        return this.multiUploadGpu(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer buffer : data) {
            try (GpuBufferSlice.MappedView view = this.allocateGpuMapped(buffer.remaining(), alignment, usage)) {
                MemoryUtil.memCopy(buffer, view.data());
                slices.add(view.slice());
            }
        }
        return slices;
    }
}
