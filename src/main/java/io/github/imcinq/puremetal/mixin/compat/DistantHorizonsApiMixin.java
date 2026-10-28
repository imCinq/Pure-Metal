package io.github.imcinq.puremetal.mixin.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.imcinq.puremetal.backend.DistantHorizonsExperiment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DH 3.3.4 has no Metal/unknown enum; use its non-OpenGL branch only on our Metal device. */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper", remap = false)
abstract class DistantHorizonsApiMixin {
    @Inject(method = "getMcRenderingApi", at = @At("HEAD"), cancellable = true, remap = false)
    private void puremetal$dhMetalApi(CallbackInfoReturnable<Object> result) {
        if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) return;
        result.setReturnValue(DistantHorizonsExperiment.nonGlApi());
    }
}
