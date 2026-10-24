package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.Redact;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/** Development diagnostics, all off by default: -Dpuremetal.debug=true, -Dpuremetal.dumpShaders=true. */
final class Debug {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final boolean ENABLED = Boolean.getBoolean("puremetal.debug");
    static final boolean DUMP_SHADERS = Boolean.getBoolean("puremetal.dumpShaders");
    /** Bytes of live MTLBuffers (all buffers, transient blocks included); reported with the device total. */
    static final java.util.concurrent.atomic.AtomicLong bufferBytes = new java.util.concurrent.atomic.AtomicLong();
    /** MTLBuffer pool: creates served from pooled buffers, creates that needed a new native buffer, and bytes parked in the pool. */
    static final java.util.concurrent.atomic.AtomicLong poolHits = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong poolMisses = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong pooledBytes = new java.util.concurrent.atomic.AtomicLong();
    /** Live textures per label, to find what keeps device memory growing. */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> textures = new java.util.concurrent.ConcurrentHashMap<>();
    static int passes;
    static int draws;
    static int skippedDraws;
    /** Metal draw calls issued, counting each sub-draw of a CPU multi-draw. */
    static int metalDraws;

    private Debug() {
    }

    static void endSubmit(final long submitIndex, final long deviceAllocatedBytes) {
        if (ENABLED && submitIndex % 120 == 0) {
            LOGGER.info("Pure Metal debug: submit {}: {} passes, {} draws, {} skipped", submitIndex, passes, draws, skippedDraws);
            LOGGER.info("Pure Metal memory: device {} MB allocated, live buffers {} MB",
                deviceAllocatedBytes >> 20, bufferBytes.get() >> 20);
            LOGGER.info("Pure Metal buffer pool: {} hits, {} misses, {} MB pooled",
                poolHits.get(), poolMisses.get(), pooledBytes.get() >> 20);
            LOGGER.info("Pure Metal textures: {}", textures.entrySet().stream()
                .sorted((a, b) -> b.getValue().get() - a.getValue().get()).limit(4)
                .map(e -> e.getKey() + "=" + e.getValue().get()).toList());
        }
        io.github.imcinq.puremetal.bench.Bench.submits++;
        io.github.imcinq.puremetal.bench.Bench.passes += passes;
        io.github.imcinq.puremetal.bench.Bench.draws += draws;
        io.github.imcinq.puremetal.bench.Bench.metalDraws += metalDraws;
        passes = draws = skippedDraws = metalDraws = 0;
    }

    static void textureCount(final String label, final int delta) {
        if (ENABLED) textures.computeIfAbsent(label, k -> new java.util.concurrent.atomic.AtomicInteger()).addAndGet(delta);
    }

    static synchronized void dumpShader(final String label, final String source) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("puremetal-shaders");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(label.replaceAll("[^A-Za-z0-9._-]", "_") + ".metal"), source);
        } catch (IOException e) {
            LOGGER.warn("Pure Metal: couldn't dump shader {}: {}", label, Redact.error(e));
        }
    }
}
