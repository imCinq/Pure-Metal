package io.github.imcinq.puremetal.mixin.perf.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Marker for the plugin's shape-checked method-reference cache. */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererCallbacksMixin {}
