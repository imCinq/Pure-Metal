package io.github.imcinq.puremetal.mixin.perf.sodium;

import io.github.imcinq.puremetal.perf.ScopedFluidCallbacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/** The binding lives with Sodium's existing default renderer, not with a worker ThreadLocal. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl$DefaultRenderContext", remap = false)
public abstract class FluidDefaultContextMixin implements ScopedFluidCallbacks.CarrierOwner {
    @Unique private volatile ScopedFluidCallbacks.Binding puremetal$fluidBinding;

    @Override
    public ScopedValue.Carrier puremetal$fluidCarrier(ScopedValue<Object> key) {
        ScopedFluidCallbacks.Binding binding = this.puremetal$fluidBinding;
        if (binding != null && binding.key() == key) return binding.carrier();
        ScopedValue.Carrier carrier = ScopedValue.where(key, this);
        // Concurrent first calls may create equivalent immutable bindings; either publication is safe.
        // An unexpected other key uses a fresh binding without displacing the normal cached one.
        if (binding == null) this.puremetal$fluidBinding = new ScopedFluidCallbacks.Binding(key, carrier);
        return carrier;
    }
}
