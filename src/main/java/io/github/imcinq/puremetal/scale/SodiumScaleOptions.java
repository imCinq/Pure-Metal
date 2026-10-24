package io.github.imcinq.puremetal.scale;

import io.github.imcinq.puremetal.Redact;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.lod.LodOptions;
import io.github.imcinq.puremetal.backend.Metal4Config;
import io.github.imcinq.puremetal.lod.TerrainLodConfig;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.resources.Identifier;

/** Render-scale and terrain LOD pages in Sodium's settings screen. Only loaded by Sodium (the sodium:config_api_user entrypoint). */
public final class SodiumScaleOptions implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        if (Boolean.getBoolean("puremetal.mixinAudit")) preloadForAudit();
        builder.registerOwnModOptions()
            .setName("Pure Metal")
            .addPage(builder.createOptionPage()
                .setName(Lang.text("puremetal.options.page"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createIntegerOption(id("render_scale"))
                        .setName(ScaleOptions.scaleName())
                        .setTooltip(ScaleOptions::scaleTooltip)
                        .setImpact(OptionImpact.HIGH)
                        .setRange(ScaleConfig.MIN_SCALE, ScaleConfig.MAX_SCALE, 1)
                        .setValueFormatter(ScaleOptions::scaleValue)
                        .setDefaultValue(ScaleConfig.MAX_SCALE)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(ScaleConfig::setScale, ScaleConfig::scale))
                    .addOption(builder.createEnumOption(id("upscaler"), ScaleConfig.Upscaler.class)
                        .setName(ScaleOptions.upscalerName())
                        .setTooltip(ScaleOptions::upscalerTooltip)
                        .setImpact(OptionImpact.LOW)
                        .setElementNameProvider(ScaleOptions::upscalerLabel)
                        .setDefaultValue(ScaleConfig.Upscaler.BILINEAR)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(ScaleConfig::setUpscaler, ScaleConfig::upscaler))
                    .addOption(metal4Option(builder))))
            .addPage(builder.createOptionPage()
                .setName(Lang.text("puremetal.options.lodPage"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createBooleanOption(id("terrain_lod"))
                        .setName(LodOptions.enabledName())
                        .setTooltip(LodOptions.enabledTooltip())
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(true)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(TerrainLodConfig::setEnabled, TerrainLodConfig::enabled))
                    .addOption(builder.createIntegerOption(id("terrain_lod_ratio"))
                        .setName(LodOptions.ratioName())
                        .setTooltip(LodOptions.ratioTooltip())
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setRange(TerrainLodConfig.MIN_RATIO, TerrainLodConfig.MAX_RATIO, 5)
                        .setValueFormatter(LodOptions::ratioLabel)
                        .setDefaultValue(TerrainLodConfig.DEFAULT_RATIO)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(TerrainLodConfig::setRatioPercent, TerrainLodConfig::ratioPercent))));
    }

    private static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath("puremetal", path);
    }

    /** The Metal 4 toggle: still bound to its saved value, but greyed out where Metal 4 cannot run. */
    private static BooleanOptionBuilder metal4Option(final ConfigBuilder builder) {
        BooleanOptionBuilder option = builder.createBooleanOption(id("metal4"))
            .setName(ScaleOptions.metal4Name())
            .setTooltip(ScaleOptions.metal4Tooltip())
            .setImpact(OptionImpact.LOW)
            .setDefaultValue(false)
            .setStorageHandler(() -> {
            })
            .setBinding(Metal4Config::setEnabled, Metal4Config::enabled);
        return Metal4Config.supported() ? option : option.setEnabled(false);
    }

    /** Debug only: loads the mixin targets through the game classloader so a broken mixin fails at startup, not when the screen opens. */
    private static void preloadForAudit() {
        for (final String name : new String[] {
            "net.caffeinemc.mods.sodium.client.gui.widgets.AbstractParentWidget",
            "net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget",
            "net.minecraft.client.gui.screens.options.OptionsSubScreen",
            "net.minecraft.client.gui.screens.options.VideoSettingsScreen"
        }) {
            try {
                Class.forName(name, false, SodiumScaleOptions.class.getClassLoader());
                System.out.println("[PureMetal audit] loaded " + name);
            } catch (final Throwable t) {
                System.out.println("[PureMetal audit] FAILED " + name + ": " + Redact.error(t));
            }
        }
    }
}
