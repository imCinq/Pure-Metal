package io.github.imcinq.puremetal.mixin.memory.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import io.github.imcinq.puremetal.backend.MetalUpscaler;
import net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bounds retired terrain buffers without touching arenas or their live allocations. */
@Mixin(value = ArenaAggregator.class, remap = false)
public abstract class ArenaAggregatorMixin {
    @Unique private static final long PUREMETAL_CACHE_BYTES = 64L << 20;
    @Unique private static final long PUREMETAL_IDLE_NS = 10_000_000_000L;

    @Shadow @Final private GpuBuffer[] freeBuffers;
    @Shadow private int freeBufferCount;

    @Unique private GpuBuffer[] puremetal$seenBuffers = new GpuBuffer[0];
    @Unique private long[] puremetal$retiredAt = new long[0];

    @Inject(method = "releaseBufferForReuse", at = @At("RETURN"), require = 1)
    private void puremetal$limitRetiredBuffers(final GpuBuffer buffer, final CallbackInfo ci) {
        this.puremetal$trimRetiredBuffers(buffer);
    }

    @Inject(method = "update", at = @At("TAIL"), require = 1)
    private void puremetal$expireRetiredBuffers(final CallbackInfo ci) {
        this.puremetal$trimRetiredBuffers(null);
    }

    @Unique
    private void puremetal$trimRetiredBuffers(final @Nullable GpuBuffer newlyRetired) {
        // RenderSystem exposes a frontend wrapper, not the underlying MetalDevice.
        if (!MetalUpscaler.active()) {
            return;
        }
        if (this.puremetal$seenBuffers.length != this.freeBuffers.length) {
            this.puremetal$seenBuffers = new GpuBuffer[this.freeBuffers.length];
            this.puremetal$retiredAt = new long[this.freeBuffers.length];
        }
        long now = System.nanoTime();
        long cachedBytes = 0L;
        for (int i = 0; i < this.freeBuffers.length; i++) {
            GpuBuffer buffer = this.freeBuffers[i];
            if (buffer == null) {
                this.puremetal$seenBuffers[i] = null;
                this.puremetal$retiredAt[i] = 0L;
                continue;
            }
            if (buffer == newlyRetired || buffer != this.puremetal$seenBuffers[i]) {
                this.puremetal$seenBuffers[i] = buffer;
                this.puremetal$retiredAt[i] = now;
            }
            if (now - this.puremetal$retiredAt[i] >= PUREMETAL_IDLE_NS || buffer.size() > PUREMETAL_CACHE_BYTES) {
                this.puremetal$evictRetiredBuffer(i);
            } else {
                cachedBytes += buffer.size();
            }
        }
        // Keep small buffers useful for subsequent uploads; discard the largest idle allocations first.
        while (cachedBytes > PUREMETAL_CACHE_BYTES) {
            int largest = -1;
            for (int i = 0; i < this.freeBuffers.length; i++) {
                if (this.freeBuffers[i] != null && (largest < 0 || this.freeBuffers[i].size() > this.freeBuffers[largest].size())) {
                    largest = i;
                }
            }
            if (largest < 0) break;
            cachedBytes -= this.freeBuffers[largest].size();
            this.puremetal$evictRetiredBuffer(largest);
        }
    }

    @Unique
    private void puremetal$evictRetiredBuffer(final int slot) {
        GpuBuffer buffer = this.freeBuffers[slot];
        this.freeBuffers[slot] = null;
        this.freeBufferCount--;
        this.puremetal$seenBuffers[slot] = null;
        this.puremetal$retiredAt[slot] = 0L;
        // Metal command buffers retain resources still used by in-flight copies, as in Sodium's own eviction.
        buffer.close();
    }
}
