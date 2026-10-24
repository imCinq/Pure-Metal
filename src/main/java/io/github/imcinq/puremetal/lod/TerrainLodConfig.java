package io.github.imcinq.puremetal.lod;

import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;

/** Terrain LOD settings, stored next to the render-scale settings in config/puremetal.properties. */
public final class TerrainLodConfig {
    public static final int MIN_RATIO = 50;
    public static final int MAX_RATIO = 95;
    public static final int DEFAULT_RATIO = 80;

    private static boolean enabled = true;
    private static int ratioPercent = DEFAULT_RATIO;

    static {
        Properties p = ScaleConfig.readProperties();
        if (p != null) {
            enabled = Boolean.parseBoolean(p.getProperty("terrainLod", "true").trim());
            try {
                ratioPercent = Math.clamp(Integer.parseInt(p.getProperty("terrainLodRatio", "80").trim()), MIN_RATIO, MAX_RATIO);
            } catch (NumberFormatException e) {
                ratioPercent = DEFAULT_RATIO;
            }
        }
    }

    private TerrainLodConfig() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static int ratioPercent() {
        return ratioPercent;
    }

    /** Returns true if the value changed (the caller reloads chunks). */
    public static boolean setEnabled(final boolean value) {
        if (value == enabled) {
            return false;
        }
        enabled = value;
        save();
        return true;
    }

    public static boolean setRatioPercent(final int value) {
        int clamped = Math.clamp(value, MIN_RATIO, MAX_RATIO);
        if (clamped == ratioPercent) {
            return false;
        }
        ratioPercent = clamped;
        save();
        return true;
    }

    private static void save() {
        ScaleConfig.writeProperties(p -> {
            p.setProperty("terrainLod", Boolean.toString(enabled));
            p.setProperty("terrainLodRatio", Integer.toString(ratioPercent));
        });
    }
}
