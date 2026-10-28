package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.Lang;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Name, tooltip and the vanilla Video Settings entry for the world-generation frame cap (Sodium's page reuses the text). */
public final class GenerationCapOptions {
    private static @Nullable OptionInstance<Boolean> vanilla;

    private GenerationCapOptions() {
    }

    public static Component name() {
        return Lang.text("puremetal.options.generationFpsCap");
    }

    public static Component tooltip() {
        return Lang.text("puremetal.options.generationFpsCap.tooltip");
    }

    public static OptionInstance<Boolean> vanilla() {
        if (vanilla == null) {
            vanilla = Lang.withCaption(OptionInstance.createBoolean(
                "puremetal.options.generationFpsCap",
                value -> Tooltip.create(tooltip()),
                (caption, value) -> value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF,
                GenerationFpsCap.enabled(),
                GenerationFpsCap::setEnabled
            ), name());
        }
        return vanilla;
    }
}
