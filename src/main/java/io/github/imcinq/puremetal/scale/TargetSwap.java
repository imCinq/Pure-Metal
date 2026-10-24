package io.github.imcinq.puremetal.scale;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Implemented on RenderTarget by a mixin: exchanges size and textures with another target. */
public interface TargetSwap {
    void puremetal$swapWith(RenderTarget other);
}
