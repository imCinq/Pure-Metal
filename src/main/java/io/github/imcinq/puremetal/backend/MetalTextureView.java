package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

final class MetalTextureView extends BaseGpuTextureView {
    private long handle;
    private int generation;
    private final int baseMip, mips;
    private final boolean cube;
    private boolean closed;

    MetalTextureView(final MetalTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        this.cube = (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        this.baseMip = baseMipLevel;
        this.mips = mipLevels;
        this.generation = texture.generation;
        this.handle = MetalNative.textureView(texture.handle(), baseMipLevel, mipLevels, cube ? 1 : 0);
        if (this.handle == 0L) {
            throw new IllegalStateException("Could not create a view of texture \"" + texture.getLabel() + "\"");
        }
    }

    long handle() {
        if (this.closed) {
            throw new IllegalStateException("Texture view already closed");
        }
        MetalTexture texture = this.metalTexture();
        if (texture.generation != this.generation) {
            // The texture was rotated to a spare native texture (presented target): follow it. If the new view can't
            // be created, keep the old one (its texture is still retained as a spare) and retry on the next call.
            long next = MetalNative.textureView(texture.handle(), this.baseMip, this.mips, this.cube ? 1 : 0);
            if (next != 0L) {
                MetalNative.release(this.handle);
                this.handle = next;
                this.generation = texture.generation;
            }
        }
        return this.handle;
    }

    MetalTexture metalTexture() {
        return (MetalTexture)this.texture();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            MetalNative.release(this.handle);
        }
    }
}
