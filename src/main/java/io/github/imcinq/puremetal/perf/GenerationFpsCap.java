package io.github.imcinq.puremetal.perf;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * Optional singleplayer frame cap while new terrain is generating and chunks near the player are still missing: at most
 * twice the display refresh rate, so world generation keeps more CPU time. Once the area around the player has caught
 * up (normal walking), the cap lifts. Off by default; toggled in Pure Metal's settings (stored as
 * generationFpsCap in config/puremetal.properties), or forced at startup with -Dpuremetal.generationFpsCap=true|false.
 */
public final class GenerationFpsCap {
    /** How long after the last generated chunk the cap stays on. */
    private static final long HOLD_NS = 2_000_000_000L;
    private static final long REFRESH_RECHECK_NS = 1_000_000_000L;
    private static final int UNLIMITED = 260;
    private static final long TERRAIN_RECHECK_NS = 250_000_000L;
    /** Missing share of nearby chunks (in percent) that turns the cap on, and the share below which it turns off again. */
    private static final int MISSING_ON_PERCENT = 5;
    private static final int MISSING_OFF_PERCENT = 2;

    private static volatile boolean enabled = readEnabled();

    private static volatile long lastGenerationNs;
    private static volatile boolean generationSeen;
    private static int refreshCap;
    private static long refreshCheckedNs;
    private static boolean terrainMissing;
    private static long terrainCheckedNs;

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
        if (now - terrainCheckedNs > TERRAIN_RECHECK_NS) {
            terrainCheckedNs = now;
            terrainMissing = nearbyTerrainMissing(mc);
        }
        if (!terrainMissing) {
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

    /** Whether enough chunks within three quarters of the render distance are still missing on the client. */
    private static boolean nearbyTerrainMissing(final Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return false;
        }
        int radius = Math.max(2, mc.options.getEffectiveRenderDistance() * 3 / 4);
        int cx = player.chunkPosition().x();
        int cz = player.chunkPosition().z();
        int total = 0;
        int missing = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                total++;
                if (!level.getChunkSource().hasChunk(cx + dx, cz + dz)) {
                    missing++;
                }
            }
        }
        int threshold = terrainMissing ? MISSING_OFF_PERCENT : MISSING_ON_PERCENT;
        return missing * 100 > total * threshold;
    }
}
