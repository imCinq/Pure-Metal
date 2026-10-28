package io.github.imcinq.puremetal.mixin.perf;

import io.github.imcinq.puremetal.perf.GenerationFpsCap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks new terrain generation. The generator step is shared by vanilla's chunk system and C2ME's replacement. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class TerrainGenerationMixin {
    // Optional: a mod that overwrites this method only stops the cap from turning on.
    @Inject(method = "buildTerrain", at = @At("HEAD"), require = 0)
    private void puremetal$markGeneration(final CallbackInfoReturnable<?> cir) {
        GenerationFpsCap.markGeneration();
    }
}
