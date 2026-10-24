package io.github.imcinq.puremetal;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Pure Metal's text. The mod does not depend on Fabric API, so its assets are only loaded as a resource pack when Fabric API happens to be installed;
 * without it a plain translatable would show its raw key. Every key therefore carries the English text from the mod's own jar as a fallback,
 * and the real translation still wins whenever the assets are loaded.
 */
public final class Lang {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE = "/assets/puremetal/lang/en_us.json";
    private static Map<String, String> english;

    private Lang() {
    }

    public static Component text(final String key, final Object... args) {
        return Component.translatableWithFallback(key, english().getOrDefault(key, key), args);
    }

    /**
     * Sets the name a vanilla button shows before its value ("Name: Value"). OptionInstance builds that name as a plain translatable of its key,
     * so without Fabric API the raw key would show; this replaces it with {@link #text} (the field is opened by the access widener).
     */
    public static <T> OptionInstance<T> withCaption(final OptionInstance<T> option, final Component caption) {
        option.caption = caption;
        return option;
    }

    private static synchronized Map<String, String> english() {
        if (english == null) {
            Map<String, String> loaded = new HashMap<>();
            try (InputStream in = Lang.class.getResourceAsStream(RESOURCE)) {
                if (in != null) {
                    for (Map.Entry<String, JsonElement> entry : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
                        loaded.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read the bundled English text: {}", Redact.error(e));
            }
            english = loaded;
        }
        return english;
    }
}
