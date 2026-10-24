package io.github.imcinq.puremetal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.imcinq.puremetal.scale.TargetSwap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Lets render scale point the main target at smaller textures while the world renders (see RenderScale). */
@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin implements TargetSwap {
    @Shadow
    public int width;
    @Shadow
    public int height;
    @Shadow
    protected @Nullable GpuTexture colorTexture;
    @Shadow
    protected @Nullable GpuTextureView colorTextureView;
    @Shadow
    protected @Nullable GpuTexture depthTexture;
    @Shadow
    protected @Nullable GpuTextureView depthTextureView;

    @Override
    public void puremetal$swapWith(final RenderTarget other) {
        RenderTargetMixin o = (RenderTargetMixin)(Object)other;
        int w = this.width;
        this.width = o.width;
        o.width = w;
        int h = this.height;
        this.height = o.height;
        o.height = h;
        GpuTexture ct = this.colorTexture;
        this.colorTexture = o.colorTexture;
        o.colorTexture = ct;
        GpuTextureView cv = this.colorTextureView;
        this.colorTextureView = o.colorTextureView;
        o.colorTextureView = cv;
        GpuTexture dt = this.depthTexture;
        this.depthTexture = o.depthTexture;
        o.depthTexture = dt;
        GpuTextureView dv = this.depthTextureView;
        this.depthTextureView = o.depthTextureView;
        o.depthTextureView = dv;
    }
}
