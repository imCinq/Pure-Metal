package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.DebugLine;
import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows the Pure Metal line while F3 is open unless a saved debug profile already chose a status for it. */
@Mixin(DebugScreenEntryList.class)
public abstract class DebugScreenEntryListMixin {
    @Shadow
    @Final
    private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "resetStatuses", at = @At("TAIL"))
    private void puremetal$defaultOn(final Map<Identifier, DebugScreenEntryStatus> statuses, final CallbackInfo ci) {
        this.allStatuses.putIfAbsent(DebugLine.ID, DebugScreenEntryStatus.IN_OVERLAY);
    }
}
