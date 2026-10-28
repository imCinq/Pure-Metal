package io.github.imcinq.puremetal.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in compatibility with DH 3.3.4 for Minecraft 26.3. */
public final class DistantHorizonsExperiment {
    public static final String PROPERTY = "puremetal.experimentalDistantHorizons";
    // Fingerprints of the official 26.3 release's classes that govern this adapter.
    // Check the class contents, not the ZIP bytes or the mod's filesystem origin.
    private static final Map<String, String> DH_CLASSES = Map.of(
            "com/seibel/distanthorizons/api/enums/config/EDhApiRenderingApi.class", "282d54510acb37c249437b5fea65a8929e92d911c666691f99a0a7fd8209b303",
            "com/seibel/distanthorizons/common/render/blaze/BlazeDhRenderApiDefinition.class", "5ab0f3a9a0de82e27c181676e93c85f4b998d7fe07d8acb39be4c44640260c06",
            "com/seibel/distanthorizons/common/wrappers/DependencySetup.class", "66e5ee35784e7c802015a9f0949fb585387d4a4d046f0de2787ef9cc4da0199d",
            "com/seibel/distanthorizons/common/wrappers/VersionConstants.class", "6131c47d6f17250e99c9d9e354312ab9f20257660279e516639c366d4fe5b0c6",
            "com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper.class", "9c10b82bcf5d939e9b67692097ba5ef9813e62ed23a259cbf1d468a9b30b98c4",
            "com/seibel/distanthorizons/fabric/mixins/client/MixinLightTexture.class", "5717a3c5cc3a5cbffa5ef76cedd36c3c532801f4852db3021dc78b1b1e6dc76e");
    private static final Logger LOGGER = LoggerFactory.getLogger("Pure Metal");
    private static final long MAX_CLASS_BYTES = 2L * 1024 * 1024;
    private static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024;
    private static Boolean enabled;
    private static Object nonGlApi;

    private DistantHorizonsExperiment() { }

    /** Only called after DH's renderer is loaded and the active device is Metal. */
    public static synchronized Object nonGlApi() {
        if (nonGlApi == null) {
            try {
                Class<?> type = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi");
                nonGlApi = type.getField("VULKAN").get(null);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Pure Metal: DH experiment could not obtain the non-OpenGL API value", error);
            }
        }
        return nonGlApi;
    }

    /** Why the experiment is on or off, computed once by the preflight. The note is the short F3 text; null means nothing to show. */
    public enum Status {
        OFF(null),
        NOT_INSTALLED(null),
        ACTIVE("DH: experimental on"),
        IRIS("DH: off (Iris)"),
        VERSION("DH: off (version)"),
        MODIFIED("DH: off (modified build)"),
        ENGINE("DH: off (engine setting)"),
        CONFIG_UNREADABLE("DH: off (config unreadable)"),
        ERROR("DH: off (preflight error)");

        private final String note;

        Status(String note) {
            this.note = note;
        }

        public String note() {
            return note;
        }
    }

    enum EngineState { OK, WRONG_ENGINE, UNREADABLE }

    /** The outcome of reading DH's renderingEngine; value is the configured engine when WRONG_ENGINE, else null. */
    record EngineCheck(EngineState state, String value) { }

    private static Status status;

    /** Short F3 text for the current state, or null when DH is not installed or the flag is off. */
    public static synchronized String debugNote() {
        enabled();
        return status.note();
    }

    /** Shared by the mixin plugin and backend; never loads DH classes during backend selection. */
    public static synchronized boolean enabled() {
        if (enabled != null) return enabled;
        status = preflight();
        return enabled = status == Status.ACTIVE;
    }

    private static Status preflight() {
        if (!Boolean.getBoolean(PROPERTY)) return Status.OFF;
        try {
            FabricLoader loader = FabricLoader.getInstance();
            var mod = loader.getModContainer("distanthorizons");
            if (mod.isEmpty()) return Status.NOT_INSTALLED;
            if (loader.isModLoaded("iris")) {
                LOGGER.warn("Pure Metal: Distant Horizons is installed together with Iris, which the experimental path does not support; DH stays on Pure Metal's normal fallback.");
                return Status.IRIS;
            }
            String version = mod.get().getMetadata().getVersion().getFriendlyString();
            if (!"3.3.4".equals(version)) {
                LOGGER.warn("Pure Metal: Distant Horizons {} found, the experimental path needs 3.3.4; DH stays on Pure Metal's normal fallback.", version);
                return Status.VERSION;
            }
            if (!hasVerifiedClasses(mod.get()::findPath)) {
                LOGGER.warn("Pure Metal: this Distant Horizons 3.3.4 build is modified or unofficial (its key classes differ from the official release); DH stays on Pure Metal's normal fallback.");
                return Status.MODIFIED;
            }
            EngineCheck engine = checkEngine(loader.getConfigDir().resolve("DistantHorizons.toml"));
            if (engine.state() == EngineState.WRONG_ENGINE) {
                LOGGER.warn("Pure Metal: Distant Horizons is set to {} rendering engine, which the experimental path cannot use. Set DH's rendering engine to AUTO (renderingEngine in DistantHorizons.toml) and restart; until then DH stays on Pure Metal's normal fallback.",
                    engine.value() == null ? "an unsupported" : "the " + engine.value());
                return Status.ENGINE;
            }
            if (engine.state() == EngineState.UNREADABLE) {
                LOGGER.warn("Pure Metal: DistantHorizons.toml could not be read or its renderingEngine entry is not understood. Set DH's rendering engine to AUTO and restart; until then DH stays on Pure Metal's normal fallback.");
                return Status.CONFIG_UNREADABLE;
            }
            LOGGER.warn("Pure Metal: experimental DH 3.3.4 preflight passed. If Metal is selected, DH sees the Vulkan API label while rendering through Metal. This path is experimental and opt-in.");
            return Status.ACTIVE;
        } catch (Exception error) {
            LOGGER.warn("Pure Metal: DH experiment preflight failed; using the normal backend fallback ({})", error.getClass().getSimpleName());
            return Status.ERROR;
        }
    }

    static boolean hasVerifiedClasses(Function<String, Optional<Path>> findPath) throws Exception {
        for (var entry : DH_CLASSES.entrySet()) {
            Optional<Path> path = findPath.apply(entry.getKey());
            if (path.isEmpty() || !Files.isRegularFile(path.get()) || !entry.getValue().equals(sha256(path.get()))) return false;
        }
        return true;
    }

    /** Null for a class file above 2 MiB; the limit is enforced while reading, not by an earlier size check. */
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_CLASS_BYTES) return null;
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** DH 3.3.4 defaults AUTO to Blaze3D on 26.3. Reject unrecognized or conflicting values. */
    static EngineCheck checkEngine(Path config) {
        try {
            return readEngine(config);
        } catch (IOException | UncheckedIOException error) {
            return new EngineCheck(EngineState.UNREADABLE, null);
        }
    }

    private static EngineCheck readEngine(Path config) throws IOException {
        if (Files.notExists(config, LinkOption.NOFOLLOW_LINKS)) return new EngineCheck(EngineState.OK, null);
        // A symlink could point outside the config folder; the limit is enforced while reading.
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) return new EngineCheck(EngineState.UNREADABLE, null);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(config, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MAX_CONFIG_BYTES + 1);
        }
        if (bytes.length > MAX_CONFIG_BYTES) return new EngineCheck(EngineState.UNREADABLE, null);
        String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        String found = null;
        for (String line : content.split("\\R")) {
            String text = line.strip();
            if (text.startsWith("#") || text.startsWith("[")) continue;
            int equals = text.indexOf('=');
            if (equals < 0) continue;
            String key = text.substring(0, equals).strip();
            int dot = key.lastIndexOf('.');
            if (dot >= 0) key = key.substring(dot + 1).strip();
            if (key.length() >= 2 && ((key.startsWith("\"") && key.endsWith("\""))
                    || (key.startsWith("'") && key.endsWith("'")))) key = key.substring(1, key.length() - 1);
            if (!key.equals("renderingEngine")) continue;
            String value = text.substring(equals + 1).strip();
            int comment = value.indexOf('#');
            if (comment >= 0) value = value.substring(0, comment).strip();
            if (value.length() < 2 || !((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) return new EngineCheck(EngineState.UNREADABLE, null);
            value = value.substring(1, value.length() - 1).toUpperCase(Locale.ROOT);
            // Only a plain engine name is kept for the log; anything else is reported without its text.
            if (!value.equals("AUTO") && !value.equals("BLAZE_3D"))
                return new EngineCheck(EngineState.WRONG_ENGINE, value.matches("[A-Z0-9_]{1,32}") ? value : null);
            if (found != null && !found.equals(value)) return new EngineCheck(EngineState.UNREADABLE, null);
            found = value;
        }
        return new EngineCheck(EngineState.OK, null);
    }
}
