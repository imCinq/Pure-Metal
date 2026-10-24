package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * A shared-storage MTLBuffer. Apple Silicon memory is unified, so every buffer is CPU-visible and always mapped.
 * Metal command buffers retain what they reference, so releasing on close never frees memory the GPU still reads.
 */
final class MetalBuffer extends BaseGpuBuffer {
    private final MetalDevice device;
    private final long handle;
    private final long contents;
    private boolean closed;
    private int mappings;
    /** Set once the GPU has written into this buffer (a copy or texture readback); its CPU view may then be stale. */
    boolean gpuWritten;
    /** Minecraft's label, kept only while the bench runs (set by MetalDevice.createBuffer). */
    String benchLabel;
    /** Submit currently being recorded (render thread only); advanced by MetalCommandEncoder.submit. */
    static volatile long recordingSubmit;
    /** The last submit whose commands reference this buffer, or -1. */
    private long usedInSubmit = -1L;
    /** CPU copy of the logical contents (small uniform buffers only), or 0 once the CPU no longer knows every byte. */
    private long shadow;
    /** Allocated size of the shadow: size rounded up to 16 like the MTLBuffer. */
    private long shadowBytes;

    /** Marks this buffer as referenced by a command in the submit being recorded. */
    MetalBuffer use() {
        this.usedInSubmit = recordingSubmit;
        return this;
    }

    /** True if no command recorded so far in the current submit references this buffer. */
    boolean unusedThisSubmit() {
        return this.usedInSubmit != recordingSubmit;
    }

    MetalBuffer(final MetalDevice device, final @GpuBuffer.Usage int usage, final long size, final String label) {
        super(usage, size);
        // MSL pads uniform structs to 16 bytes (a std140 vec3 block is 12 bytes in Vulkan but 16 in Metal), and
        // Metal rejects a draw whose bound buffer is shorter than the struct. Over-allocate the tail; size() is unchanged.
        this.device = device;
        this.handle = device.acquireBuffer((size + 15L) & ~15L);
        if (this.handle == 0L) {
            throw new com.mojang.renderpearl.api.device.GpuOutOfMemoryException("Could not allocate a " + size + " byte Metal buffer");
        }
        if (Debug.ENABLED) {
            Debug.bufferBytes.addAndGet((size + 15L) & ~15L);
        }
        this.contents = MetalNative.bufferContents(this.handle);
        device.label(this.handle, label);
    }

    long handle() {
        return this.handle;
    }

    long contents() {
        return this.contents;
    }

    /** Gives a small uniform buffer a zeroed CPU shadow. Only MetalDevice calls this, never transient blocks. */
    void enableShadow() {
        if ((this.usage() & GpuBuffer.USAGE_UNIFORM) == 0 || this.size() <= 0L || this.size() > 65536L) {
            return;
        }
        this.shadowBytes = (this.size() + 15L) & ~15L;
        this.shadow = MemoryUtil.nmemCalloc(1L, this.shadowBytes);
    }

    /** Address of the CPU shadow while the CPU knows every byte, else 0. */
    long shadow() {
        return this.shadow;
    }

    /** Bytes of shadow allocated; valid only while shadow() is non-zero. */
    long shadowBytes() {
        return this.shadowBytes;
    }

    /** Mirrors a CPU write into the shadow, if there is one. */
    void shadowWrite(final long offset, final long src, final long length) {
        if (this.shadow != 0L) {
            MemoryUtil.memCopy(src, this.shadow + offset, length);
        }
    }

    /** Drops the shadow for good, once the GPU or a mapped view may have changed bytes the CPU cannot track. */
    void dropShadow() {
        if (this.shadow != 0L) {
            MemoryUtil.nmemFree(this.shadow);
            this.shadow = 0L;
        }
    }

    /** Records a GPU-side write that the CPU did not perform (a copy or readback into this buffer). */
    void markGpuWritten() {
        this.gpuWritten = true;
        this.dropShadow();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            if (this.mappings != 0) {
                throw new IllegalStateException("Attempt to close a mapped buffer");
            }
            this.closed = true;
            this.dropShadow();
            if (Debug.ENABLED) {
                Debug.bufferBytes.addAndGet(-((this.size() + 15L) & ~15L));
            }
            this.device.recycleBuffer(this.handle, (this.size() + 15L) & ~15L);
        }
    }

    @Override
    public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.closed) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        if (offset < 0L || length < 0L || offset > this.size() || length > this.size() - offset) {
            throw new IllegalArgumentException("Cannot map " + length + " bytes at offset " + offset + " of a " + this.size() + " byte buffer");
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported");
        }
        if (write) {
            // The mapped view can change any byte, so the CPU shadow is no longer trustworthy.
            this.dropShadow();
        }
        this.mappings++;
        return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.contents + offset, (int)length), new Runnable() {
            private boolean done;

            @Override
            public void run() {
                if (!this.done) {
                    this.done = true;
                    MetalBuffer.this.mappings--;
                }
            }
        });
    }
}
