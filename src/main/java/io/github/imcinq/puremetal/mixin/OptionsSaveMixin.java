package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.bench.Bench;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -PbenchGraphics changes options in memory (a graphics preset rewrites many of them). Once it has, Options.save() writes nothing for
 * the rest of the process, so no way the run ends can leave the changed values in options.txt. Required (not optional) on purpose: if
 * this cannot apply, the game fails loudly instead of the protection silently missing.
 */
@Mixin(Options.class)
public class OptionsSaveMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void puremetal$holdSaveDuringBench(final CallbackInfo ci) {
        if (Bench.optionsSaveHeld()) {
            ci.cancel();
        }
    }
}
