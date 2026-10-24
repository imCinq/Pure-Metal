package io.github.imcinq.puremetal.mixin.lod.sodium;

import io.github.imcinq.puremetal.lod.LodState;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** LOD level of the section's current (uploaded) mesh. Main thread only. */
@Mixin(value = RenderSection.class, remap = false)
public abstract class RenderSectionMixin implements LodState {
    @Unique private byte puremetal$lod;
    @Unique private boolean puremetal$requested;

    @Override
    public byte puremetal$lodState() {
        return this.puremetal$lod;
    }

    @Override
    public void puremetal$setLodState(final byte state) {
        this.puremetal$lod = state;
    }

    @Override
    public boolean puremetal$lodRequested() {
        return this.puremetal$requested;
    }

    @Override
    public void puremetal$setLodRequested(final boolean requested) {
        this.puremetal$requested = requested;
    }
}
