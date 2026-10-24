package io.github.imcinq.puremetal.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.DataResult;
import io.github.imcinq.puremetal.perf.PaletteScratch;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> {
    @WrapMethod(method = "pack")
    private PalettedContainerRO.PackedData<T> puremetal$pack(Strategy<T> strategy,
        Operation<PalettedContainerRO.PackedData<T>> original) {
        PaletteScratch.enter();
        try { return original.call(strategy); } finally { PaletteScratch.exit(); }
    }

    @WrapMethod(method = "unpack")
    private static <T> DataResult<PalettedContainer<T>> puremetal$unpack(Strategy<T> strategy,
        PalettedContainerRO.PackedData<T> data, Operation<DataResult<PalettedContainer<T>>> original) {
        PaletteScratch.enter();
        try { return original.call(strategy, data); } finally { PaletteScratch.exit(); }
    }

    // Optional: other mods (Lithium's chunk.serialization) overwrite pack/unpack and drop this call. Missing targets
    // just lose the scratch reuse there; they must never stop the game from loading.
    @WrapOperation(method = {"pack", "unpack"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/PalettedContainer;reencodeContents(Lnet/minecraft/util/BitStorage;Lnet/minecraft/world/level/chunk/Palette;Lnet/minecraft/world/level/chunk/Palette;)[I"), require = 0)
    private static <T> int[] puremetal$reencode(BitStorage storage, Palette<T> source, Palette<T> target,
        Operation<int[]> original) {
        return PaletteScratch.reencode(storage, source, target);
    }
}
