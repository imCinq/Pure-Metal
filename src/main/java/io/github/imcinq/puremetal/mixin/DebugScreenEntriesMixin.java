package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.DebugLine;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers the Pure Metal line as a normal F3 debug entry (no Fabric API needed). */
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
    @Shadow
    private static Identifier register(final Identifier id, final DebugScreenEntry entry) {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void puremetal$register(final CallbackInfo ci) {
        register(DebugLine.ID, DebugLine.INSTANCE);
    }
}
