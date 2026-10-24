package io.github.imcinq.puremetal.mixin.lod.sodium;

import io.github.imcinq.puremetal.lod.LodState;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ChunkBuildOutput.class, remap = false)
public abstract class ChunkBuildOutputMixin implements LodState {
    @Unique private byte puremetal$lod;

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
        return false;
    }

    @Override
    public void puremetal$setLodRequested(final boolean requested) {
    }
}
