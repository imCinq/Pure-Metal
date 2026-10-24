package io.github.imcinq.puremetal.backend;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.Locale;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Bench-only (-Dpuremetal.bench): CPU time the render thread spends inside native Metal encoding per frame, and the Metal calls
 * issued. The native side times only the outermost entry point of each nested call and excludes the FFM transition itself.
 */
public final class EncodeProfile {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Native slot order: ns per category (pass, bind, draw, copy, upscale, submit, present, present thread), then counts (the last one: Metal 4 queue barriers encoded). */
    private static final String[] CATEGORIES = {"pass", "bind", "draw", "copy", "upscale", "submit", "present"};
    private static final int PRESENT_THREAD = 7, DRAWS = 8, BUFFER_BINDS = 9, TEXTURE_BINDS = 10, ENCODERS = 11, CBS_CREATED = 12,
        CBS_COMMITTED = 13, ENTRIES = 14, BARRIERS = 15, SLOTS = 16;
    private static long[] encodeNs = new long[65536], gpuNs = new long[65536];
    private static final long[] totals = new long[SLOTS];
    private static int frames;
    private static boolean active;
    private static long prevGpuNs;

    private EncodeProfile() {
    }

    /** Starts counting (and drops anything counted so far); the bench calls it when recording begins. */
    public static void start(final long gpuNsTotal) {
        if (!MetalNative.loaded()) {
            return; // another backend is being benchmarked
        }
        active = true;
        MetalNative.encEnable();
        take(new long[SLOTS]);
        prevGpuNs = gpuNsTotal;
    }

    /** Closes one frame: everything counted since the previous call, and the GPU time that completed in the same span. */
    public static void frame(final long gpuNsTotal) {
        if (!active) {
            return;
        }
        long[] v = new long[SLOTS];
        take(v);
        long encode = 0;
        for (int i = 0; i < CATEGORIES.length; i++) {
            encode += v[i];
        }
        if (frames == encodeNs.length) {
            encodeNs = Arrays.copyOf(encodeNs, frames * 2);
            gpuNs = Arrays.copyOf(gpuNs, frames * 2);
        }
        encodeNs[frames] = encode;
        gpuNs[frames++] = gpuNsTotal - prevGpuNs;
        prevGpuNs = gpuNsTotal;
        for (int i = 0; i < SLOTS; i++) {
            totals[i] += v[i];
        }
    }

    private static void take(final long[] into) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer out = stack.callocLong(SLOTS);
            MetalNative.encTake(MemoryUtil.memAddress(out));
            out.get(0, into);
        }
    }

    public static void report(final long frameNsSum, final long medianFrameNs) {
        if (!active || frames == 0) {
            return;
        }
        long[] encode = Arrays.copyOf(encodeNs, frames), gpu = Arrays.copyOf(gpuNs, frames);
        Arrays.sort(encode);
        Arrays.sort(gpu);
        long encodeSum = 0;
        StringBuilder byCategory = new StringBuilder();
        for (int i = 0; i < CATEGORIES.length; i++) {
            encodeSum += totals[i];
            byCategory.append(' ').append(CATEGORIES[i]).append('=').append(fmt(totals[i] / 1e6 / frames));
        }
        LOGGER.info(
            "BENCH-ENCODE frames={} encodeMs/f median={} p99={} mean={} | pctOfFrame mean={} median={} | gpuMs/f median={} p99={} | byCategoryMs/f{} | presentThreadMs/f={}",
            frames, ms(encode[frames / 2]), ms(pct(encode)), fmt(encodeSum / 1e6 / frames),
            fmt(100.0 * encodeSum / frameNsSum), fmt(100.0 * encode[frames / 2] / medianFrameNs),
            ms(gpu[frames / 2]), ms(pct(gpu)), byCategory, fmt(totals[PRESENT_THREAD] / 1e6 / frames)
        );
        LOGGER.info(
            "BENCH-ENCODE-COUNTS draws/f={} bufferBinds/f={} textureBinds/f={} encoders/f={} cbCreated/f={} cbCommitted/f={} timedEntries/f={} barriers/f={} (each adds ~50ns of timer overhead to encodeMs)",
            fmt((double)totals[DRAWS] / frames), fmt((double)totals[BUFFER_BINDS] / frames), fmt((double)totals[TEXTURE_BINDS] / frames),
            fmt((double)totals[ENCODERS] / frames), fmt((double)totals[CBS_CREATED] / frames), fmt((double)totals[CBS_COMMITTED] / frames),
            fmt((double)totals[ENTRIES] / frames), fmt((double)totals[BARRIERS] / frames)
        );
    }

    private static long pct(final long[] sorted) {
        return sorted[Math.min(sorted.length - 1, (int)Math.ceil(0.99 * sorted.length) - 1)];
    }

    private static String ms(final long ns) {
        return fmt(ns / 1e6);
    }

    private static String fmt(final double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
