package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.lod.LodOptions;
import io.github.imcinq.puremetal.perf.GenerationCapOptions;
import io.github.imcinq.puremetal.scale.NoteEntry;
import io.github.imcinq.puremetal.scale.ScaleOptions;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Pure Metal" section (title, optional-extras note, render-scale and terrain LOD settings) to Video Settings; Sodium's screen gets its own page instead. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void puremetal$addSection(final CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreenAccessor) this).puremetal$list();
        list.addHeader(Component.literal("Pure Metal"));
        FocusableTextWidget note = FocusableTextWidget.builder(Lang.text("puremetal.options.note"), ((VideoSettingsScreen) (Object) this).getFont(), 4)
            .maxWidth(list.getRowWidth())
            .alwaysShowBorder(false)
            .backgroundFill(FocusableTextWidget.BackgroundFill.NEVER)
            .build();
        note.setCentered(true);
        final NoteEntry entry = new NoteEntry(note);
        list.addEntry(entry, entry.rowHeight());
        list.addSmall(ScaleOptions.vanilla());
        list.addSmall(LodOptions.vanilla());
        list.addSmall(GenerationCapOptions.vanilla());
    }
}
