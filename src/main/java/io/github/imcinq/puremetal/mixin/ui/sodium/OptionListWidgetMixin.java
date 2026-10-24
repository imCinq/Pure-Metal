package io.github.imcinq.puremetal.mixin.ui.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import io.github.imcinq.puremetal.scale.SodiumNoteWidget;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds the optional-extras note right under Pure Metal's title row in Sodium's option list (not the search view). */
@Pseudo
@Mixin(value = OptionListWidget.class, remap = false)
public abstract class OptionListWidgetMixin {
    @Shadow private int entryHeight;

    @Inject(
        method = "renderAllPages",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gui/widgets/OptionListWidget;addRenderableChild(Lnet/minecraft/client/gui/components/events/GuiEventListener;)Lnet/minecraft/client/gui/components/events/GuiEventListener;",
            ordinal = 0,
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void puremetal$addNote(
        final net.minecraft.client.gui.screens.Screen screen, final int x, final int y, final int width,
        final CallbackInfoReturnable<Integer> cir,
        @Local(index = 7) final ModOptions mod,
        @Local(index = 5) final LocalIntRef offset
    ) {
        if (!"puremetal".equals(mod.configId())) return;
        final SodiumNoteWidget note = SodiumNoteWidget.create((OptionListWidget) (Object) this, x, y + offset.get() + entryHeight, width);
        ((AbstractParentWidgetInvoker) this).puremetal$addRenderableChild(note);
        offset.set(offset.get() + note.height());
    }
}
