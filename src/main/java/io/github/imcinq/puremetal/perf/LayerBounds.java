package io.github.imcinq.puremetal.perf;

import org.jspecify.annotations.Nullable;

/** Implemented only by opt-in diagnostics mixins; layer/index lookups follow mesh ownership. */
public interface LayerBounds {
    @Nullable MeshBounds puremetal$bounds(Object layer);
    default void puremetal$setBounds(Object layer, @Nullable MeshBounds bounds) { throw new UnsupportedOperationException(); }
}
