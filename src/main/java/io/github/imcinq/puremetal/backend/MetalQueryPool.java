package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.Arrays;
import java.util.OptionalLong;

/**
 * Timestamp queries. GPU timestamps aren't implemented yet, so every query reports "no value"; callers such as the
 * F3 profiler already handle that, which keeps them from crashing.
 */
final class MetalQueryPool implements GpuQueryPool {
    private final int size;

    MetalQueryPool(final int size) {
        this.size = size;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public OptionalLong getValue(final int index) {
        return OptionalLong.empty();
    }

    @Override
    public OptionalLong[] getValues(final int index, final int count) {
        OptionalLong[] values = new OptionalLong[count];
        Arrays.fill(values, OptionalLong.empty());
        return values;
    }

    @Override
    public void close() {
    }
}
