package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.backend.StartupGuard;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Both hooks are optional: if either does not apply, {@link StartupGuard} never restores anything (no verified guard
 * flip) or keeps its marker, which cannot cause a restore on its own.
 */
@Mixin(Minecraft.class)
public class MinecraftStartupMixin {
    /** Marks the start as clean at the same point Minecraft does, so the marker can be dropped. */
    @Inject(method = "onGameLoadFinished", at = @At("RETURN"), require = 0)
    private void puremetal$startupFinished(final GameLoadCookie cookie, final CallbackInfo ci) {
        StartupGuard.finished();
    }

    /** Reads what Minecraft's startup-crash guard is about to act on, right before it clears startedCleanly. */
    @Inject(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;startedCleanly:Z", opcode = 181), require = 0)
    private void puremetal$vanillaStart(final GameConfig config, final CallbackInfo ci) {
        try {
            Minecraft minecraft = (Minecraft)(Object)this;
            StartupGuard.vanillaStart(minecraft.options.startedCleanly, minecraft.options.preferredGraphicsBackend().get(), config.game.forcedGraphicsApi);
        } catch (Throwable ignored) {
        }
    }
}
