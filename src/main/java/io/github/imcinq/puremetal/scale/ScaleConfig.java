package io.github.imcinq.puremetal.scale;

import io.github.imcinq.puremetal.Redact;
import com.mojang.blaze3d.platform.Window;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Render-scale settings, stored in config/puremetal.properties. */
public final class ScaleConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Pure Metal");
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("puremetal.properties");
    public static final int MIN_SCALE = 50;
    public static final int MAX_SCALE = 100;

    /** METALFX is MetalFX Spatial (the name predates the temporal scaler and stays so saved settings keep working). */
    public enum Upscaler {
        METALFX,
        BILINEAR,
        TEMPORAL
    }

    public enum Sharpening {
        OFF(0.0F),
        LOW(0.35F),
        HIGH(1.0F);

        public final float amount;

        Sharpening(final float amount) {
            this.amount = amount;
        }
    }

    private static int scale = MAX_SCALE;
    /** Session-only scale (benchmarks); never saved. 0 = none. */
    private static int transientScale;
    private static Upscaler upscaler = Upscaler.BILINEAR;
    private static Sharpening sharpening = Sharpening.LOW;
    /**
     * Config-only (temporalOutputScale in puremetal.properties, a number from 0.5 to 1 or "auto"): MetalFX Temporal writes
     * this fraction of the screen size and a plain bilinear pass stretches it up, which costs much less on high-resolution
     * displays (the scaler's cost follows its output pixels). Render Scale then applies to that output size, so the world is
     * drawn at scale x this fraction. 0 = auto: the logical-point size on a HiDPI display, full size otherwise.
     */
    private static float temporalOutputScale;

    /** What runs and the Temporal output fraction, fixed per frame by {@link #refreshEffective} so every use in a frame agrees. */
    private static Upscaler effective;
    private static float outputScaleNow;

    static {
        load();
        effective = upscaler;
    }

    private ScaleConfig() {
    }

    public static int scale() {
        return transientScale != 0 ? transientScale : scale;
    }

    /** The fraction of the screen size MetalFX Temporal writes (1 = full). */
    public static float temporalOutputScale() {
        return outputScaleNow > 0.0F ? outputScaleNow : computeOutputScale();
    }

    private static float computeOutputScale() {
        if (temporalOutputScale > 0.0F) {
            return temporalOutputScale;
        }
        Window window = Minecraft.getInstance().getWindow();
        float density = window.getScreenWidth() > 0 ? (float)window.getWidth() / window.getScreenWidth() : 1.0F;
        return density >= 1.5F ? Math.max(1.0F / density, 0.5F) : 1.0F;
    }

    /** The world's size as a fraction of the screen's at this render scale, given what actually runs. */
    public static float worldFraction(final int percent) {
        return percent / 100.0F * (effectiveUpscaler() == Upscaler.TEMPORAL ? temporalOutputScale() : 1.0F);
    }

    public static Upscaler upscaler() {
        return upscaler;
    }

    /** What actually runs: Temporal falls back to Bilinear while it is unavailable (shader pack active, unsupported Mac). */
    public static Upscaler effectiveUpscaler() {
        return effective;
    }

    /** Re-evaluates what runs (shader pack toggles, MetalFX turning itself off, the display's density). True if it changed. */
    static boolean refreshEffective() {
        Upscaler now = upscaler == Upscaler.TEMPORAL && TemporalUpscaler.unavailableReason() != null ? Upscaler.BILINEAR : upscaler;
        float output = computeOutputScale();
        boolean changed = now != effective || output != outputScaleNow;
        effective = now;
        outputScaleNow = output;
        return changed;
    }

    public static Sharpening sharpening() {
        return sharpening;
    }

    public static void setScale(final int value) {
        int clamped = Math.clamp(value, MIN_SCALE, MAX_SCALE);
        transientScale = 0;
        if (clamped != scale) {
            scale = clamped;
            RenderScale.markDirty();
            save();
        }
    }

    /** Changes the scale for this session only (benchmarks), without saving it. */
    public static void setScaleTransient(final int value) {
        int clamped = Math.clamp(value, MIN_SCALE, MAX_SCALE);
        if (clamped != scale()) {
            transientScale = clamped;
            RenderScale.markDirty();
        }
    }

    public static void setUpscaler(final Upscaler value) {
        if (value != upscaler) {
            upscaler = value;
            refreshEffective();
            RenderScale.markDirty();
            save();
        }
    }

    private static void load() {
        Properties p = readProperties();
        if (p == null) {
            return;
        }
        // Each key stands alone: a bad value falls back to its own default and leaves the others as written.
        int newScale = MAX_SCALE;
        String value = p.getProperty("renderScale");
        if (value != null) {
            try {
                newScale = Math.clamp(Integer.parseInt(value.trim()), MIN_SCALE, MAX_SCALE);
            } catch (NumberFormatException e) {
                LOGGER.warn("Pure Metal: invalid renderScale '{}' in {}, using {}", value, FILE.getFileName(), newScale);
            }
        }
        Upscaler newUpscaler = parseEnum(p, "upscaler", Upscaler.class, Upscaler.BILINEAR);
        Sharpening newSharpening = parseEnum(p, "sharpening", Sharpening.class, Sharpening.LOW);
        float newOutputScale = 0.0F;
        value = p.getProperty("temporalOutputScale");
        if (value != null && !value.isBlank() && !value.trim().equalsIgnoreCase("auto")) {
            try {
                float parsed = Float.parseFloat(value.trim());
                if (!Float.isFinite(parsed)) {
                    throw new NumberFormatException(value);
                }
                newOutputScale = parsed == 0.0F ? 0.0F : Math.clamp(parsed, 0.5F, 1.0F);
            } catch (NumberFormatException e) {
                LOGGER.warn("Pure Metal: invalid temporalOutputScale '{}' in {}, using auto", value, FILE.getFileName());
            }
        }
        scale = newScale;
        upscaler = newUpscaler;
        sharpening = newSharpening;
        temporalOutputScale = newOutputScale;
    }

    private static <E extends Enum<E>> E parseEnum(final Properties p, final String key, final Class<E> type, final E fallback) {
        String value = p.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Pure Metal: invalid {} '{}' in {}, using {}", key, value, FILE.getFileName(), fallback);
            return fallback;
        }
    }

    /** The settings file, or null if it is missing or unreadable. Shared with the terrain LOD settings. */
    public static Properties readProperties() {
        if (!Files.isRegularFile(FILE)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(FILE)) {
            p.load(in);
            return p;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Pure Metal: could not read {}: {}", FILE.getFileName(), Redact.error(e));
            return null;
        }
    }

    /** Rewrites the settings file, keeping keys owned by other settings. */
    public static synchronized void writeProperties(final Consumer<Properties> update) {
        Properties p = readProperties();
        if (p == null) {
            if (Files.exists(FILE)) {
                LOGGER.warn("Pure Metal: not saving {} because the existing file could not be read", FILE.getFileName());
                return;
            }
            p = new Properties();
        }
        update.accept(p);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(FILE.toAbsolutePath().getParent(), "puremetal", ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp)) {
                p.store(out, "Pure Metal settings");
            }
            try {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Pure Metal: could not save {}: {}", FILE.getFileName(), Redact.error(e));
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void save() {
        writeProperties(p -> {
            p.setProperty("renderScale", Integer.toString(scale));
            p.setProperty("upscaler", upscaler.name());
            p.setProperty("sharpening", sharpening.name());
        });
    }
}
