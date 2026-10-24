package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/** Whether shaders compile through Metal 4 (macOS 26+): config/puremetal.properties, overridden by -Dpuremetal.metal4=true|false. */
public final class Metal4Config {
    private static boolean enabled;

    static {
        Properties p = ScaleConfig.readProperties();
        if (p != null) {
            enabled = Boolean.parseBoolean(p.getProperty("metal4", "false").trim());
        }
    }

    private Metal4Config() {
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Whether this Mac can run Metal 4 at all: macOS 26 or newer on Apple silicon. Cheap enough for the settings screens. */
    public static boolean supported() {
        if (!System.getProperty("os.name", "").startsWith("Mac") || !System.getProperty("os.arch", "").equals("aarch64")) {
            return false;
        }
        return macOsMajor() >= 26;
    }

    private static int macOsMajor() {
        String version = System.getProperty("os.version", "");
        int dot = version.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The system property's value, or null when unset or not a boolean. */
    static @Nullable Boolean override() {
        String value = System.getProperty("puremetal.metal4");
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    static boolean effective() {
        Boolean override = override();
        return override != null ? override : enabled;
    }

    public static void setEnabled(final boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        ScaleConfig.writeProperties(p -> p.setProperty("metal4", Boolean.toString(enabled)));
        MetalDevice device = MetalDevice.current;
        if (device != null) {
            device.configureMetal4();
        }
    }
}
