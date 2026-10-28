package io.github.imcinq.puremetal.perf;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Optional singleplayer frame cap while the integrated server generates new terrain: at most twice the display
 * refresh rate, so world generation keeps more CPU time. Off by default; toggled in Pure Metal's settings (stored as
 * generationFpsCap in config/puremetal.properties), or forced at startup with -Dpuremetal.generationFpsCap=true|false.
 */
public final class GenerationFpsCap {
    /** How long after the last generated chunk the cap stays on. */
    private static final long HOLD_NS = 2_000_000_000L;
    private static final long REFRESH_RECHECK_NS = 1_000_000_000L;
    private static final int UNLIMITED = 260;

    private static volatile boolean enabled = readEnabled();

    private static volatile long lastGenerationNs;
    private static volatile boolean generationSeen;
    private static int refreshCap;
    private static long refreshCheckedNs;

    private GenerationFpsCap() {
    }

    private static boolean readEnabled() {
        String property = System.getProperty("puremetal.generationFpsCap");
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        Properties p = ScaleConfig.readProperties();
        return p != null && Boolean.parseBoolean(p.getProperty("generationFpsCap", "false").trim());
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        ScaleConfig.writeProperties(p -> p.setProperty("generationFpsCap", Boolean.toString(value)));
    }

    /** Worldgen thread: a chunk started generating terrain. */
    public static void markGeneration() {
        if (enabled) {
            lastGenerationNs = System.nanoTime();
            generationSeen = true;
        }
    }

    /** Render thread: the frame limit vanilla is about to use. */
    public static int apply(final int limit) {
        if (!enabled || !generationSeen) {
            return limit;
        }
        long now = System.nanoTime();
        if (now - lastGenerationNs > HOLD_NS) {
            return limit;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.hasSingleplayerServer()) {
            return limit;
        }
        if (refreshCap == 0 || now - refreshCheckedNs > REFRESH_RECHECK_NS) {
            refreshCheckedNs = now;
            Monitor monitor = mc.getWindow().findBestMonitor();
            VideoMode mode = monitor == null ? null : monitor.currentMode();
            float hz = mode == null ? 0.0F : mode.getRefreshRate();
            // Vanilla treats 260 and above as unlimited, so a 130 Hz+ display caps just below that.
            refreshCap = hz > 0.0F ? Math.min(Math.round(hz) * 2, UNLIMITED - 1) : -1;
        }
        return refreshCap > 0 ? Math.min(limit, refreshCap) : limit;
    }
}
