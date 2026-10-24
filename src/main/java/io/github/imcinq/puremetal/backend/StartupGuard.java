package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.Redact;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft's startup-crash guard switches Graphics API from Default to OpenGL after any unfinished start, which would
 * skip Metal for good. A small marker in config/puremetal-startup.properties tells whether Metal took part in the
 * unfinished start: if it did not, Default is restored; if it did, OpenGL is left alone. Default is restored only
 * when this launch's vanilla guard is verifiably the one that flipped it (see {@link #vanillaStart}), and only when
 * the marker is complete and its owner is gone. Every failure or ambiguity is swallowed and means "do not restore".
 * Each read-check-change of the marker runs under a short-lived lock on a sibling lock file, so concurrent launches
 * cannot replace or delete each other's marker between the check and the change; without the lock nothing is touched.
 *
 * Known limitation: a crash after the vanilla guard flips the setting but before the first {@link #begin} leaves no
 * marker, so that start is not recovered.
 */
public final class StartupGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("Pure Metal");
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("puremetal-startup.properties");
    private static final Path LOCK_FILE = FILE.resolveSibling("puremetal-startup.lock");
    private static final String FORCED = "FORCED";
    private static final String VERSION = "2";
    private static final int MAX_BYTES = 4096;
    private static final long PID = ProcessHandle.current().pid();
    private static final String ID = UUID.randomUUID().toString();

    private static boolean begun;
    private static boolean disabled;
    private static boolean finished;
    private static String setting = FORCED;
    private static boolean metal;
    private static boolean poisoned;
    private static boolean ambiguous;
    private static boolean sawUncleanDefault;

    private StartupGuard() {
    }

    /**
     * Called from the Minecraft constructor right before vanilla clears {@code startedCleanly}, with the values as
     * loaded from options.txt and the launch-argument override. Vanilla's guard then flips Default to OpenGL exactly
     * when the start was unclean, nothing was forced and the loaded value was Default.
     */
    public static synchronized void vanillaStart(final boolean startedCleanly, final @Nullable PreferredGraphicsApi loaded, final @Nullable PreferredGraphicsApi forcedByLaunch) {
        sawUncleanDefault = !startedCleanly && loaded == PreferredGraphicsApi.DEFAULT && forcedByLaunch == null;
    }

    /** Called once per launch with the Graphics API about to be used. True means Default was restored for this launch. */
    public static synchronized boolean begin(final PreferredGraphicsApi current) {
        if (begun) {
            return false;
        }
        begun = true;
        Lock lock = Lock.acquire();
        if (lock == null) {
            disabled = true;
            ambiguous = true;
            return false;
        }
        boolean restore = false;
        try {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                boolean forced = minecraft == null || launchArgumentForced() || minecraft.options.preferredGraphicsBackend().get() != current;
                Marker previous = read();
                if (previous != null && previous.pid != PID && ProcessHandle.of(previous.pid).isPresent()) {
                    disabled = true;
                    LOGGER.debug("Pure Metal startup recovery is off for this launch because another instance owns the startup marker");
                    return false;
                }
                setting = forced ? FORCED : current.name();
                if (previous != null && sawUncleanDefault && !forced && current == PreferredGraphicsApi.OPENGL
                        && PreferredGraphicsApi.DEFAULT.name().equals(previous.setting)) {
                    if (previous.metal) {
                        LOGGER.warn("The last start with Pure Metal did not finish, so Graphics API was left on {}; set it to Default in Video Settings to try Metal again", current);
                    } else if (PureMetalBackend.unsupportedReason() == null) {
                        minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.DEFAULT);
                        restore = true;
                        setting = PreferredGraphicsApi.DEFAULT.name();
                        LOGGER.info("Minecraft's crash guard had switched Graphics API to OpenGL after a start that did not finish and did not involve Metal; Default is restored");
                        try {
                            minecraft.options.save();
                        } catch (RuntimeException e) {
                            LOGGER.debug("Pure Metal could not save options after restoring Default: {}", Redact.error(e));
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("Pure Metal startup check failed: {}", Redact.error(t));
            }
            writeLocked();
            return restore;
        } finally {
            lock.close();
        }
    }

    /**
     * Called before the Metal device is created. Records "Metal may start" first, so a crash inside Metal is never
     * mistaken for one that did not involve it. False means that could neither be recorded nor made harmless, and
     * Metal must not start: that is only the case while a marker that could still authorize a restore remains.
     */
    public static synchronized boolean metalStarting() {
        if (!begun || finished || metal) {
            return true;
        }
        metal = true;
        if (disabled && !ambiguous) {
            return true;
        }
        if (!disabled && write()) {
            return true;
        }
        if (disabled && !ambiguous) {
            return true;
        }
        poisoned = true;
        setting = FORCED;
        if (recoveryImpossible()) {
            LOGGER.debug("Pure Metal startup recovery is off for this launch because the startup marker could not be written");
            return true;
        }
        LOGGER.warn("Pure Metal is not started because its startup marker could be neither written nor removed");
        return false;
    }

    /** Called when the Metal device could not be created and nothing of it is left running. */
    public static synchronized void metalFailed() {
        if (!begun || finished || disabled || poisoned || !metal) {
            return;
        }
        metal = false;
        if (!write()) {
            metal = true;
        }
    }

    /** Called when the game finished loading: this start was clean. */
    public static synchronized void finished() {
        finished = true;
        if (!begun || disabled) {
            return;
        }
        Lock lock = Lock.acquire();
        if (lock == null) {
            return;
        }
        try {
            Marker marker = read();
            if (marker != null && ID.equals(marker.id)) {
                Files.deleteIfExists(FILE);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Pure Metal could not remove the startup marker: {}", Redact.error(e));
        } finally {
            lock.close();
        }
    }

    private static boolean launchArgumentForced() {
        for (String argument : FabricLoader.getInstance().getLaunchArguments(true)) {
            if (argument.startsWith("-") && argument.replaceFirst("^-+", "").startsWith("graphicsBackend")) {
                return true;
            }
        }
        return false;
    }

    private record Marker(String id, long pid, String setting, boolean metal) {
    }

    private static @Nullable Marker read() {
        if (!Files.isRegularFile(FILE)) {
            return null;
        }
        Properties p = new Properties();
        try {
            byte[] bytes;
            try (InputStream in = Files.newInputStream(FILE)) {
                bytes = in.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                return null;
            }
            try (Reader in = new StringReader(new String(bytes, StandardCharsets.UTF_8))) {
                p.load(in);
            }
            String s = p.getProperty("setting");
            String m = p.getProperty("metal");
            String id = p.getProperty("id");
            if (!VERSION.equals(p.getProperty("version")) || id == null || id.isEmpty() || id.length() > 64 || s == null || !(s.equals(FORCED) || isApi(s))
                    || !("true".equals(m) || "false".equals(m))) {
                return null;
            }
            return new Marker(id, Long.parseLong(p.getProperty("pid", "")), s, Boolean.parseBoolean(m));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Pure Metal could not read the startup marker: {}", Redact.error(e));
            return null;
        }
    }

    private static boolean isApi(final String name) {
        for (PreferredGraphicsApi api : PreferredGraphicsApi.values()) {
            if (api.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** True when the marker is absent, unreadable, or ours; false when another live launch has taken it over. */
    private static boolean ownsFile() {
        Marker marker = read();
        return marker == null || marker.id.equals(ID) || marker.pid == PID || ProcessHandle.of(marker.pid).isEmpty();
    }

    /** True for a marker that a later launch could use to restore Default. */
    private static boolean authorizesRestore(final @Nullable Marker marker) {
        return marker != null && PreferredGraphicsApi.DEFAULT.name().equals(marker.setting) && !marker.metal;
    }

    /**
     * True when no marker that could authorize a restore remains or can remain: it is absent, cannot authorize one, is
     * a live launch's, or was just deleted or replaced by one that never matches. Without the lock nothing is touched.
     */
    private static boolean recoveryImpossible() {
        Lock lock = Lock.acquire();
        if (lock == null) {
            return !authorizesRestore(read());
        }
        try {
            Marker marker = read();
            if (!authorizesRestore(marker) || !ownsFile()) {
                return true;
            }
            try {
                Files.deleteIfExists(FILE);
                return true;
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Pure Metal could not remove the startup marker: {}", Redact.error(e));
            }
            return writeLocked();
        } finally {
            lock.close();
        }
    }

    private static boolean write() {
        if (disabled) {
            return false;
        }
        Lock lock = Lock.acquire();
        if (lock == null) {
            disabled = true;
            ambiguous = true;
            return false;
        }
        try {
            return writeLocked();
        } finally {
            lock.close();
        }
    }

    /** Replaces the marker; the caller holds the lock. */
    private static boolean writeLocked() {
        Path tmp = null;
        try {
            if (!ownsFile()) {
                disabled = true;
                return false;
            }
            Properties p = new Properties();
            p.setProperty("version", VERSION);
            p.setProperty("id", ID);
            p.setProperty("pid", Long.toString(PID));
            p.setProperty("setting", setting);
            p.setProperty("metal", Boolean.toString(metal));
            tmp = Files.createTempFile(FILE.toAbsolutePath().getParent(), "puremetal", ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp)) {
                p.store(out, "Pure Metal startup marker; removed when the game finishes loading");
            }
            try {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Pure Metal could not write the startup marker: {}", Redact.error(e));
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
            return false;
        }
    }

    /** Non-blocking cross-process lock on a sibling file, held only across one read-check-change of the marker. */
    private static final class Lock {
        private final FileChannel channel;
        private final FileLock lock;

        private Lock(final FileChannel channel, final FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /** Null when the lock could not be obtained; callers then treat ownership as ambiguous and change nothing. */
        static @Nullable Lock acquire() {
            FileChannel channel = null;
            try {
                channel = FileChannel.open(LOCK_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return new Lock(channel, lock);
                }
                LOGGER.debug("Pure Metal startup recovery is off because the startup lock is held by another instance");
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Pure Metal could not lock the startup marker: {}", Redact.error(e));
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            return null;
        }

        void close() {
            try {
                lock.release();
            } catch (IOException | RuntimeException ignored) {
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
