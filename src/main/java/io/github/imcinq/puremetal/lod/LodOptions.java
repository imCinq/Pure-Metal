package io.github.imcinq.puremetal.lod;

import io.github.imcinq.puremetal.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Names, tooltips and the vanilla Video Settings entries for terrain LOD (Sodium's page reuses the text). */
public final class LodOptions {
    private static OptionInstance<?> @Nullable [] vanilla;

    private LodOptions() {
    }

    public static Component enabledName() {
        return Lang.text("puremetal.options.terrainLod");
    }

    public static Component ratioName() {
        return Lang.text("puremetal.options.terrainLodRatio");
    }

    public static Component enabledTooltip() {
        return Lang.text("puremetal.options.terrainLod.tooltip");
    }

    public static Component ratioTooltip() {
        return Lang.text("puremetal.options.terrainLodRatio.tooltip");
    }

    public static Component ratioLabel(final int percent) {
        return Component.literal(percent + "%");
    }

    /** Rebuilds all loaded chunks with the new setting (vanilla; Sodium's page reloads its renderer itself). */
    private static void reloadChunks() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelExtractor != null) {
            mc.levelExtractor.allChanged();
        }
    }

    /** Entries appended to Video Settings' quality section. */
    public static OptionInstance<?>[] vanilla() {
        if (vanilla == null) {
            vanilla = new OptionInstance<?>[]{
                Lang.withCaption(OptionInstance.createBoolean(
                    "puremetal.options.terrainLod",
                    value -> Tooltip.create(enabledTooltip()),
                    (caption, value) -> value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF,
                    TerrainLodConfig.enabled(),
                    value -> {
                        if (TerrainLodConfig.setEnabled(value)) {
                            reloadChunks();
                        }
                    }
                ), enabledName()),
                new OptionInstance<>(
                    "puremetal.options.terrainLodRatio",
                    value -> Tooltip.create(ratioTooltip()),
                    (caption, value) -> Options.genericValueLabel(ratioName(), ratioLabel(value)),
                    new OptionInstance.IntRange(TerrainLodConfig.MIN_RATIO, TerrainLodConfig.MAX_RATIO, false),
                    TerrainLodConfig.ratioPercent(),
                    value -> {
                        if (TerrainLodConfig.setRatioPercent(value) && TerrainLodConfig.enabled()) {
                            reloadChunks();
                        }
                    }
                )
            };
        }
        return vanilla;
    }
}
