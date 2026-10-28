package io.github.imcinq.puremetal.mixin.perf;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import io.github.imcinq.puremetal.perf.GenerationFpsCap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
    private int puremetal$generationCap(final int limit) {
        return GenerationFpsCap.apply(limit);
    }
}
