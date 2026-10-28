package io.github.imcinq.puremetal.perf;

/** Reuses immutable bindings only; temporary callbacks and render arguments keep their original lifetimes. */
public final class ScopedFluidCallbacks {
    private ScopedFluidCallbacks() {}

    public interface CarrierOwner {
        ScopedValue.Carrier puremetal$fluidCarrier(ScopedValue<Object> key);
    }

    /** Safely published with its owner; contains no per-call world, output, block or position arguments. */
    public record Binding(ScopedValue<Object> key, ScopedValue.Carrier carrier) {}

    public static ScopedValue.Carrier where(ScopedValue<Object> key, Object value) {
        return value instanceof CarrierOwner owner ? owner.puremetal$fluidCarrier(key) : ScopedValue.where(key, value);
    }
}
