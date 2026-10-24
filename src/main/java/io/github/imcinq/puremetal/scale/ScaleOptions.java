package io.github.imcinq.puremetal.scale;

import com.mojang.blaze3d.platform.Window;
import com.mojang.serialization.Codec;
import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.backend.Metal4Config;
import io.github.imcinq.puremetal.backend.MetalUpscaler;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Names, tooltips and the vanilla Video Settings entries for render scale and the upscaler (Sodium's page reuses the text). */
public final class ScaleOptions {
    private static OptionInstance<?> @Nullable [] vanilla;

    private ScaleOptions() {
    }

    public static Component scaleName() {
        return Lang.text("puremetal.options.renderScale");
    }

    public static Component metal4Name() {
        return Lang.text("puremetal.options.metal4");
    }

    public static Component metal4Tooltip() {
        Component text = Lang.text("puremetal.options.metal4.tooltip");
        return Metal4Config.supported() ? text : Component.empty().append(text).append("\n").append(Lang.text("puremetal.options.metal4.unsupported"));
    }

    public static Component upscalerName() {
        return Lang.text("puremetal.options.upscaler");
    }

    /** Value label: "Native" or the percentage. */
    public static Component scaleLabel(final int percent) {
        return percent >= ScaleConfig.MAX_SCALE ? Lang.text("puremetal.options.renderScale.native") : Component.literal(percent + "%");
    }

    /** Where the percentage lands on this display, e.g. "Renders at 1707×1067 → 2560×1600" (live while the slider moves). */
    public static Component scaleReadout(final int percent) {
        Window window = Minecraft.getInstance().getWindow();
        int width = window.getWidth();
        int height = window.getHeight();
        if (percent >= ScaleConfig.MAX_SCALE) {
            return Lang.text("puremetal.options.renderScale.readout.native", width, height);
        }
        return Lang.text("puremetal.options.renderScale.readout", scaledSize(width, percent), scaledSize(height, percent), width, height);
    }

    /** The slider's value text: the percentage plus the size the world is drawn at. */
    public static Component scaleValue(final int percent) {
        if (percent >= ScaleConfig.MAX_SCALE) {
            return scaleLabel(percent);
        }
        Window window = Minecraft.getInstance().getWindow();
        return Component.empty().append(scaleLabel(percent)).append(Component.literal(
            " (" + scaledSize(window.getWidth(), percent) + "×" + scaledSize(window.getHeight(), percent) + ")"
        ));
    }

    private static int scaledSize(final int size, final int percent) {
        return Math.max(1, Math.round(size * ScaleConfig.worldFraction(percent)));
    }

    public static Component upscalerLabel(final ScaleConfig.Upscaler value) {
        Component name = Lang.text("puremetal.options.upscaler." + value.name().toLowerCase(Locale.ROOT));
        return value == ScaleConfig.Upscaler.TEMPORAL && TemporalUpscaler.unavailableReason() != null
            ? Component.empty().append(name).append(Lang.text("puremetal.options.upscaler.unavailable"))
            : name;
    }

    public static Component scaleTooltip(final int percent) {
        return withInactiveNote(Component.empty()
            .append(scaleReadout(percent)).append("\n")
            .append(Lang.text("puremetal.options.renderScale.tooltip")));
    }

    /** The upscaler's description; when it is Temporal and unavailable, also why Bilinear is used instead. */
    public static Component upscalerTooltip(final ScaleConfig.Upscaler value) {
        Component text = Lang.text("puremetal.options.upscaler.tooltip");
        String reason = value == ScaleConfig.Upscaler.TEMPORAL ? TemporalUpscaler.unavailableReason() : null;
        if (reason != null) {
            text = Component.empty().append(Lang.text(reason)).append("\n").append(text);
        }
        if (ScaleConfig.scale() >= ScaleConfig.MAX_SCALE) {
            text = Component.empty().append(text).append("\n").append(Lang.text("puremetal.options.upscaler.note100"));
        }
        return withInactiveNote(text);
    }

    private static Component withInactiveNote(final Component text) {
        return MetalUpscaler.active() ? text : Component.empty().append(text).append("\n").append(Lang.text("puremetal.options.inactive"));
    }

    /** Entries appended to Video Settings' quality section. */
    public static OptionInstance<?>[] vanilla() {
        if (vanilla == null) {
            vanilla = new OptionInstance<?>[]{
                new OptionInstance<>(
                    "puremetal.options.renderScale",
                    value -> Tooltip.create(scaleTooltip(value)),
                    (caption, value) -> Options.genericValueLabel(scaleName(), scaleLabel(value)),
                    new OptionInstance.IntRange(ScaleConfig.MIN_SCALE, ScaleConfig.MAX_SCALE, false),
                    ScaleConfig.scale(),
                    ScaleConfig::setScale
                ),
                Lang.withCaption(new OptionInstance<>(
                    "puremetal.options.upscaler",
                    value -> Tooltip.create(upscalerTooltip(value)),
                    (caption, value) -> upscalerLabel(value),
                    new OptionInstance.Enum<>(
                        List.of(ScaleConfig.Upscaler.BILINEAR, ScaleConfig.Upscaler.METALFX, ScaleConfig.Upscaler.TEMPORAL),
                        enumCodec(ScaleConfig.Upscaler.class)
                    ),
                    ScaleConfig.upscaler(),
                    ScaleConfig::setUpscaler
                ), upscalerName()),
                Lang.withCaption(new OptionInstance<>(
                    "puremetal.options.metal4",
                    value -> Tooltip.create(metal4Tooltip()),
                    (caption, value) -> value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF,
                    Metal4Config.supported() ? OptionInstance.BOOLEAN_VALUES : new LockedBooleanValues(),
                    Metal4Config.enabled(),
                    Metal4Config::setEnabled
                ), metal4Name())
            };
        }
        return vanilla;
    }

    /**
     * The vanilla boolean value set with its button left inactive: the option stays visible and keeps reading and writing its saved value,
     * but cannot be clicked on a Mac without Metal 4.
     */
    private record LockedBooleanValues(OptionInstance.ValueSet<Boolean> values) implements OptionInstance.ValueSet<Boolean> {
        LockedBooleanValues() {
            this(OptionInstance.BOOLEAN_VALUES);
        }

        @Override
        public Function<OptionInstance<Boolean>, AbstractWidget> createButton(
            final OptionInstance.TooltipSupplier<Boolean> tooltip, final Options options, final int x, final int y, final int width,
            final OptionInstance.ValueUpdateListener<? super Boolean> onValueUpdate
        ) {
            final Function<OptionInstance<Boolean>, AbstractWidget> create = values.createButton(tooltip, options, x, y, width, onValueUpdate);
            return option -> {
                AbstractWidget widget = create.apply(option);
                widget.active = false;
                return widget;
            };
        }

        @Override
        public Optional<Boolean> validateValue(final Boolean value) {
            return values.validateValue(value);
        }

        @Override
        public Codec<Boolean> codec() {
            return values.codec();
        }
    }

    private static <E extends Enum<E>> Codec<E> enumCodec(final Class<E> type) {
        return Codec.STRING.xmap(name -> Enum.valueOf(type, name), Enum::name);
    }
}
