package io.github.imcinq.puremetal.mixin.perf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Optional marker for immutable scope bindings; Fabric's fluid API is not a required dependency. */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderingImpl", remap = false)
public abstract class FabricFluidCallbacksMixin {}
