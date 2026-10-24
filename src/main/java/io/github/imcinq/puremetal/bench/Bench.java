package io.github.imcinq.puremetal.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Dev-only benchmark, identical for every backend: -Dpuremetal.bench=<seconds>. Once in a world with no screen open it
 * waits {@code puremetal.benchWarmup} seconds (default 10, fractions allowed), records frame times, logs one summary line and quits.
 */
public final class Bench {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int SECONDS = Integer.getInteger("puremetal.bench", 0);
    private static final long WARMUP_NS = (long)(Double.parseDouble(System.getProperty("puremetal.benchWarmup", "10")) * 1e9);
    /** Optional commands run once on joining, separated by ';' (e.g. "gamemode creative;tp @s 0 140 0 0 0"); the world needs cheats on. */
    private static final String COMMAND = System.getProperty("puremetal.benchCommand");
    private static boolean commandSent;
    /**
     * -Dpuremetal.benchGraphics=fast|fancy|fabulous: sets the graphics preset in memory for the run. A preset rewrites about seventeen
     * individual options (render distance, simulation distance, entity distance, ...), so every one of them is saved first and put back
     * before the quit, not just the preset. Options.save() is also disabled from the moment the preset is applied until the process
     * ends (OptionsSaveMixin), so nothing the preset changed can reach options.txt, whatever way the run ends.
     */
    private static final String GRAPHICS = System.getProperty("puremetal.benchGraphics");
    private static boolean graphicsSet;
    private static net.minecraft.client.GraphicsPreset graphicsBefore;
    private static List<SavedOption<?>> graphicsSaved;
    private static volatile boolean optionsSaveHeld;
    /** -Dpuremetal.benchWalk=true: fly forward, sprinting, the whole time, so every run takes the same route. */
    private static final boolean WALK = Boolean.getBoolean("puremetal.benchWalk");
    /** -Dpuremetal.benchShot=name: hide the HUD and save screenshots/name.png 3 s into the recording (gallery images). */
    private static final String SHOT = System.getProperty("puremetal.benchShot");
    private static boolean shotTaken;
    /**
     * -Dpuremetal.benchScales=100,75,50: cycle the render scale every {@link #SLICE_NS} during the one recording and report
     * each separately. Interleaving keeps heat build-up (the fanless Air slows down over minutes) out of the comparison.
     */
    private static final int[] SCALES = parseScales(System.getProperty("puremetal.benchScales"));
    private static final long SLICE_NS = 2_000_000_000L;
    /** Frames this soon after a scale change are left out of its stats (resize, frames still in flight). */
    private static final long SETTLE_NS = 400_000_000L;
    /**
     * -Dpuremetal.benchAB=name: toggle the named runtime switch (see {@link #registerAB}) every slice, A (off) then B (on),
     * during the one recording, and report both variants separately. Same camera, same heat, same scene for A and B.
     * Not combined with benchScales. Saves screenshots/ab-A1, ab-B, ab-A2.png (three consecutive frames) for a pixel diff.
     */
    private static final String AB = System.getProperty("puremetal.benchAB");
    private static final Map<String, java.util.function.Consumer<Boolean>> AB_HOOKS = new HashMap<>();
    private static java.util.function.Consumer<Boolean> abHook;
    private static int abSlot = -1;
    private static final long[][] abFrames = new long[2][65536];
    private static final int[] abCount = new int[2];
    private static final long[] abNs = new long[2];
    private static final double[] abLoad = new double[2], abStore = new double[2];
    private static int abShotPhase;
    private static long prevLoad, prevStore;
    private static final long[] abGpuNs = new long[2], abGpuFrames = new long[2], abPresentNs = new long[2], abPresentCount = new long[2];
    private static long prevGpuNs, prevGpuFrames, prevPresentNs, prevPresentCount;
    private static int slot = -1;
    private static long sliceStart;
    private static long[][] scaleFrames;
    private static int[] scaleCount;
    private static long[] scaleNs;
    private static long[] frames = new long[65536];
    private static int count;
    private static long last;
    private static long inWorldSince;
    private static long recordStart;
    private static boolean done;
    /** Backend-side counters (Pure Metal only), summed over the recording. */
    public static long submitWaitNs;
    public static long gpuNs;
    public static long gpuFrames;
    /** GPU time and count of the present command buffers (separate queue, completes asynchronously). */
    public static long presentGpuNs, presentGpuCount;
    public static long uploadGpuNs, uploadGpuCommands;
    public static long submits;
    public static long passes;
    public static long draws;
    public static long metalDraws;
    /** Times a write had to copy the presented target into a spare texture (direct present fallback). */
    public static long directFallbacks;
    /** Uniform bindings sent as setBytes from the CPU shadow instead of an MTLBuffer reference. */
    public static long bytesBinds;
    public static long frameFences;
    /** CPU time for the separate-array indexed multi-draw path used by Sodium terrain; excludes GPU execution. */
    public static long terrainPrepareNs, terrainEncodeNs, terrainBatches, terrainDraws;
    /** Native counters: new render encoders, merged passes, blit encoders, blits closing a parked encoder, bytes loaded, bytes stored. */
    public static final long[] stats = new long[12];
    /** Encoder opens by kind (render, clear, frame blit, upload blit, compute, present), summed over the recording. */
    public static final long[] encoders = new long[6];
    /** Time actually blocked in the surface's frame-slot semaphore waits (vsyncDepth, slotsFree) and how many waits blocked. */
    public static long surfaceBlockedNs, surfaceBlockedWaits;
    /** Opt-in native pass timings (-Dpuremetal.profile, Metal 4): render encoder span (start to its last fragment work), the
     * fragment tail after its last vertex work, compute/blit, their sample counts, and samples dropped by the heap's capacity. */
    public static long profileSpanNs, profileFragTailNs, profileBlitNs;
    public static long profileSpanSamples, profileFragTailSamples, profileBlitSamples, profileDroppedSamples;
    /** Native per-physical-pass attachment trace ("label WxH | C0 name fmt WxH MB load=.. store=.."), by line, with pass counts. */
    private static final Map<String, Long> passTrace = new LinkedHashMap<>();
    private static final Pattern TRACE_ATT = Pattern.compile("([0-9.]+)MB load=(\\w+) store=(\\w+)");

    /** True when this session is an in-client A/B run, so backends should prebuild both variants of their switches. */
    public static boolean abRequested() {
        return AB != null;
    }

    /** Backends register a runtime switch here; -Dpuremetal.benchAB=name then A/B tests it inside one client. */
    public static void registerAB(final String name, final java.util.function.Consumer<Boolean> setter) {
        AB_HOOKS.put(name, setter);
    }

    private static void switchAB(final int next, final long now) {
        abSlot = next;
        sliceStart = now;
        abHook.accept(next == 1);
    }

    private static void abGrab(final Minecraft minecraft, final String name) {
        net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1, message -> LOGGER.info("BENCH-SHOT {}", message.getString()));
    }

    private static void reportAB() {
        String[] names = {"A(off)", "B(on)"};
        for (int i = 0; i < 2; i++) {
            int n = abCount[i];
            if (n == 0) {
                continue;
            }
            long[] sorted = Arrays.copyOf(abFrames[i], n);
            Arrays.sort(sorted);
            LOGGER.info(
                "BENCH-AB {} {} frames={} avgFps={} medianMs={} p98Ms={} p99.5Ms={} attachLoadMB/f={} attachStoreMB/f={} gpuMs/f={} presentGpuMs/cb={}",
                AB, names[i], n, fmt(n / (abNs[i] / 1e9)), ms(sorted[n / 2]), ms(pct(sorted, 0.98)), ms(pct(sorted, 0.995)),
                fmt(abLoad[i] / n), fmt(abStore[i] / n),
                abGpuFrames[i] == 0 ? "n/a" : fmt(abGpuNs[i] / 1e6 / abGpuFrames[i]),
                abPresentCount[i] == 0 ? "n/a" : fmt(abPresentNs[i] / 1e6 / abPresentCount[i])
            );
        }
    }

    public static void addPassTrace(final String lines) {
        for (String line : lines.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                passTrace.merge(line.substring(tab + 1), Long.parseLong(line.substring(0, tab)), Long::sum);
            }
        }
    }

    /** True once -PbenchGraphics changed options in memory: Options.save() must then write nothing (OptionsSaveMixin). */
    public static boolean optionsSaveHeld() {
        return optionsSaveHeld;
    }

    private record SavedOption<T>(String name, net.minecraft.client.OptionInstance<T> option, T value) {
        void restore() {
            this.option.set(this.value);
        }

        boolean intact() {
            return java.util.Objects.equals(this.option.get(), this.value);
        }
    }

    private static <T> void save(final List<SavedOption<?>> saved, final String name, final net.minecraft.client.OptionInstance<T> option) {
        saved.add(new SavedOption<>(name, option, option.get()));
    }

    /** Every option GraphicsPreset.apply can change. */
    private static List<SavedOption<?>> saveGraphicsOptions(final net.minecraft.client.Options o) {
        List<SavedOption<?>> saved = new ArrayList<>();
        save(saved, "biomeBlendRadius", o.biomeBlendRadius());
        save(saved, "renderDistance", o.renderDistance());
        save(saved, "prioritizeChunkUpdates", o.prioritizeChunkUpdates());
        save(saved, "simulationDistance", o.simulationDistance());
        save(saved, "ambientOcclusion", o.ambientOcclusion());
        save(saved, "cloudStatus", o.cloudStatus());
        save(saved, "particles", o.particles());
        save(saved, "mipmapLevels", o.mipmapLevels());
        save(saved, "entityShadows", o.entityShadows());
        save(saved, "entityDistanceScaling", o.entityDistanceScaling());
        save(saved, "menuBackgroundBlurriness", o.menuBackgroundBlurriness());
        save(saved, "cloudRange", o.cloudRange());
        save(saved, "cutoutLeaves", o.cutoutLeaves());
        save(saved, "improvedTransparency", o.improvedTransparency());
        save(saved, "weatherRadius", o.weatherRadius());
        save(saved, "maxAnisotropyBit", o.maxAnisotropyBit());
        save(saved, "textureFiltering", o.textureFiltering());
        return saved;
    }

    /** Puts the preset and every option it changed back; safe to call more than once. Saves stay disabled afterwards. */
    private static void restoreGraphics(final Minecraft minecraft) {
        List<SavedOption<?>> saved = graphicsSaved;
        if (saved == null) {
            return;
        }
        graphicsSaved = null;
        net.minecraft.client.Options options = minecraft.options;
        try {
            // Re-applies the original preset's values (a no-op for CUSTOM); the saved values then replace them, with the
            // "applying a preset" flag set so that each restored option does not flip the preset to CUSTOM again.
            options.graphicsPreset().set(graphicsBefore);
            options.isApplyingGraphicsPreset = true;
            for (SavedOption<?> option : saved) {
                try {
                    option.restore();
                } catch (RuntimeException e) {
                    LOGGER.warn("BENCH could not restore option {}", option.name(), e);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("BENCH graphics restore failed", e);
        } finally {
            options.isApplyingGraphicsPreset = false;
        }
        List<String> wrong = new ArrayList<>();
        for (SavedOption<?> option : saved) {
            if (!option.intact()) {
                wrong.add(option.name());
            }
        }
        if (graphicsBefore != options.graphicsPreset().get()) {
            wrong.add("graphicsPreset");
        }
        if (wrong.isEmpty()) {
            LOGGER.info("BENCH graphics preset and {} options restored to {}", saved.size(), graphicsBefore);
        } else {
            LOGGER.warn("BENCH graphics options NOT restored in memory: {} (options.txt is untouched: saving is disabled for this run)", wrong);
        }
    }

    private static void reportPassTrace(final int frames) {
        double loadTotal = 0, storeTotal = 0;
        for (Map.Entry<String, Long> e : passTrace.entrySet()) {
            double load = 0, store = 0;
            Matcher m = TRACE_ATT.matcher(e.getKey());
            while (m.find()) {
                double mb = Double.parseDouble(m.group(1));
                if (m.group(2).equals("Load")) {
                    load += mb;
                }
                if (m.group(3).equals("Store")) {
                    store += mb;
                }
            }
            double n = e.getValue() / (double)frames;
            loadTotal += load * n;
            storeTotal += store * n;
            LOGGER.info("BENCH-PASS n/f={} loadMB={} storeMB={} {}", fmt(n), fmt(load), fmt(store), e.getKey());
        }
        LOGGER.info("BENCH-PASS-TOTAL loadMB/f={} storeMB/f={}", fmt(loadTotal), fmt(storeTotal));
    }

    /** Non-early buffer writes by "label usage=N": {count, bytes}. Render thread only. */
    private static final Map<String, long[]> blits = new HashMap<>();

    private Bench() {
    }

    public static void countBlit(final String label, final int usage, final long bytes) {
        long[] c = blits.computeIfAbsent(label + " usage=" + usage, k -> new long[2]);
        c[0]++;
        c[1] += bytes;
    }

    public static void frame(final Minecraft minecraft) {
        if (SECONDS <= 0 || done) {
            return;
        }
        long now = System.nanoTime();
        boolean playing = minecraft.level != null && minecraft.gui.screen() == null;
        if (!playing) {
            inWorldSince = 0;
            last = now;
            return;
        }
        if (SHOT != null) {
            if (!minecraft.gui.hud.isHidden()) {
                minecraft.gui.hud.toggle();
            }
        }
        if (WALK && minecraft.player != null) {
            minecraft.player.getAbilities().flying = true;
            minecraft.options.keyUp.setDown(true);
            minecraft.options.keySprint.setDown(true);
        }
        if (GRAPHICS != null && !graphicsSet) {
            graphicsSet = true;
            optionsSaveHeld = true; // before anything changes: from here to the end of the process options.txt is never rewritten
            graphicsBefore = minecraft.options.graphicsPreset().get();
            graphicsSaved = saveGraphicsOptions(minecraft.options);
            net.minecraft.client.GraphicsPreset wanted = net.minecraft.client.GraphicsPreset.valueOf(GRAPHICS.toUpperCase(java.util.Locale.ROOT));
            minecraft.options.graphicsPreset().set(wanted);
            LOGGER.info("BENCH graphics preset {} -> {} (restored before the quit)", graphicsBefore, wanted);
            inWorldSince = 0; // the preset change reloads the renderer: the warmup starts again
            last = now;
            return;
        }
        if (inWorldSince == 0) {
            inWorldSince = now;
            if (COMMAND != null && !commandSent && minecraft.player != null) {
                commandSent = true;
                for (String command : COMMAND.split(";")) {
                    minecraft.player.connection.sendCommand(command.trim());
                }
            }
        }
        if (recordStart == 0) {
            if (now - inWorldSince < WARMUP_NS) {
                last = now;
                return;
            }
            recordStart = now;
            io.github.imcinq.puremetal.backend.FrameTrace.mark(io.github.imcinq.puremetal.backend.FrameTrace.BENCH_START, 0);
            submitWaitNs = gpuNs = gpuFrames = submits = passes = draws = metalDraws = bytesBinds = frameFences = 0;
            terrainPrepareNs = terrainEncodeNs = terrainBatches = terrainDraws = 0;
            uploadGpuNs = uploadGpuCommands = 0;
            presentGpuNs = presentGpuCount = 0;
            surfaceBlockedNs = surfaceBlockedWaits = 0;
            profileSpanNs = profileFragTailNs = profileBlitNs = 0;
            profileSpanSamples = profileFragTailSamples = profileBlitSamples = profileDroppedSamples = 0;
            Arrays.fill(stats, 0);
            Arrays.fill(encoders, 0);
            io.github.imcinq.puremetal.backend.EncodeProfile.start(gpuNs);
            blits.clear();
            passTrace.clear();
            if (AB != null) {
                abHook = AB_HOOKS.get(AB);
                if (abHook == null) {
                    LOGGER.warn("BENCH-AB no switch named {} (have {})", AB, AB_HOOKS.keySet());
                } else {
                    prevLoad = prevStore = prevGpuNs = prevGpuFrames = prevPresentNs = prevPresentCount = 0;
                    switchAB(0, now);
                }
            }
            if (SCALES != null) {
                scaleFrames = new long[SCALES.length][65536];
                scaleCount = new int[SCALES.length];
                scaleNs = new long[SCALES.length];
                switchScale(0, now);
            }
            last = now;
            return;
        }
        if (count == frames.length) {
            frames = Arrays.copyOf(frames, count * 2);
        }
        frames[count++] = now - last;
        io.github.imcinq.puremetal.backend.EncodeProfile.frame(gpuNs);
        if (SHOT != null && !shotTaken && now - recordStart >= 3_000_000_000L) {
            shotTaken = true;
            net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, SHOT + ".png", minecraft.gameRenderer.mainRenderTarget(), 1, message -> LOGGER.info("BENCH-SHOT {}", message.getString()));
        }
        if (abHook != null) {
            double load = stats[4] - prevLoad, store = stats[5] - prevStore;
            prevLoad = stats[4];
            prevStore = stats[5];
            if (now - sliceStart >= SETTLE_NS && abCount[abSlot] < abFrames[abSlot].length) {
                abFrames[abSlot][abCount[abSlot]++] = now - last;
                abNs[abSlot] += now - last;
                abLoad[abSlot] += load / 1e6;
                abStore[abSlot] += store / 1e6;
                abGpuNs[abSlot] += gpuNs - prevGpuNs;
                abGpuFrames[abSlot] += gpuFrames - prevGpuFrames;
                abPresentNs[abSlot] += presentGpuNs - prevPresentNs;
                abPresentCount[abSlot] += presentGpuCount - prevPresentCount;
            }
            prevGpuNs = gpuNs;
            prevGpuFrames = gpuFrames;
            prevPresentNs = presentGpuNs;
            prevPresentCount = presentGpuCount;
            if (abShotPhase == 0 && abSlot == 0 && now - recordStart >= 6_000_000_000L && now - sliceStart >= SETTLE_NS) {
                // Three consecutive frames A, B, A of the same still scene: A1 vs A2 is the noise floor, A1 vs B the effect.
                abShotPhase = 1;
                abGrab(minecraft, "ab-A1.png");
                abHook.accept(true);
                last = now;
                return;
            } else if (abShotPhase == 1) {
                abShotPhase = 2;
                abGrab(minecraft, "ab-B.png");
                abHook.accept(false);
                last = now;
                return;
            } else if (abShotPhase == 2) {
                abShotPhase = 3;
                abGrab(minecraft, "ab-A2.png");
                sliceStart = now;
                last = now;
                return;
            }
            int next = (int)((now - recordStart) / SLICE_NS % 2);
            if (next != abSlot) {
                switchAB(next, now);
            }
        }
        if (SCALES != null) {
            if (now - sliceStart >= SETTLE_NS && scaleCount[slot] < scaleFrames[slot].length) {
                scaleFrames[slot][scaleCount[slot]++] = now - last;
                scaleNs[slot] += now - last;
            }
            int next = (int)((now - recordStart) / SLICE_NS % SCALES.length);
            if (next != slot) {
                switchScale(next, now);
            }
        }
        last = now;
        if (now - recordStart >= SECONDS * 1_000_000_000L) {
            done = true;
            try {
                report(minecraft, now - recordStart);
            } catch (RuntimeException e) {
                LOGGER.error("BENCH report failed", e);
            } finally {
                restoreGraphics(minecraft);
                minecraft.stop();
            }
        }
    }

    private static int @org.jspecify.annotations.Nullable [] parseScales(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split(",")).mapToInt(v -> Integer.parseInt(v.trim())).toArray();
    }

    private static void switchScale(final int next, final long now) {
        slot = next;
        sliceStart = now;
        io.github.imcinq.puremetal.scale.ScaleConfig.setScaleTransient(SCALES[next]);
    }

    private static void reportScales() {
        for (int i = 0; i < SCALES.length; i++) {
            int n = scaleCount[i];
            if (n == 0) {
                continue;
            }
            long[] sorted = Arrays.copyOf(scaleFrames[i], n);
            Arrays.sort(sorted);
            LOGGER.info(
                "BENCH-SCALE scale={} frames={} avgFps={} medianMs={} p98Ms={} p99.5Ms={}",
                SCALES[i], n, fmt(n / (scaleNs[i] / 1e9)), ms(sorted[n / 2]), ms(pct(sorted, 0.98)), ms(pct(sorted, 0.995))
            );
        }
    }

    private static void report(final Minecraft minecraft, final long elapsed) {
        if (abHook != null) {
            reportAB();
        }
        if (SCALES != null) {
            reportScales();
        }
        long[] sorted = Arrays.copyOf(frames, count);
        Arrays.sort(sorted);
        double avgFps = count / (elapsed / 1e9);
        String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
        LOGGER.info(
            "BENCH backend={} res={}x{} frames={} avgFps={} medianMs={} p98Ms={} p99.5Ms={} maxMs={} | submitWaitMs/f={} gpuMsPerFrame={} | submits/f={} passes/f={} draws/f={} metalDraws/f={} directFallbacks={}",
            backend, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(), count, fmt(avgFps), ms(sorted[count / 2]), ms(pct(sorted, 0.98)), ms(pct(sorted, 0.995)), ms(sorted[count - 1]),
            fmt(submitWaitNs / 1e6 / count), gpuFrames == 0 ? "n/a" : fmt(gpuNs / 1e6 / count),
            fmt((double)submits / count), fmt((double)passes / count), fmt((double)draws / count), fmt((double)metalDraws / count), directFallbacks
        );
        if (terrainBatches > 0) {
            LOGGER.info("BENCH-TERRAIN prepareMs/f={} encodeMs/f={} batches/f={} draws/f={}",
                fmt(terrainPrepareNs / 1e6 / count), fmt(terrainEncodeNs / 1e6 / count),
                fmt((double)terrainBatches / count), fmt((double)terrainDraws / count));
        }
        LOGGER.info("BENCH-UPLOAD earlyUploadGpuMs/f={} completedUploadBuffers/f={} (frame GPU time includes in-frame blits)",
            fmt(uploadGpuNs / 1e6 / count), fmt((double)uploadGpuCommands / count));
        LOGGER.info("BENCH-ENCODERS render={} clear={} blit={} uploadBlit={} compute={} present={} surfaceBlockedMs/f={} blockedWaits/f={} (blocked only: an uncontended slot is not counted)",
            fmt((double)encoders[0] / count), fmt((double)encoders[1] / count), fmt((double)encoders[2] / count),
            fmt((double)encoders[3] / count), fmt((double)encoders[4] / count), fmt((double)encoders[5] / count),
            fmt(surfaceBlockedNs / 1e6 / count), fmt((double)surfaceBlockedWaits / count));
        if (profileSpanSamples > 0 || profileBlitSamples > 0 || profileDroppedSamples > 0) {
            // Metal 4 pass timings: "span" is a render encoder from its start to its last fragment work (merged logical passes are
            // inside it); "fragTail" is only the tail after that encoder's last vertex work, not fragment shading time.
            LOGGER.info("BENCH-PROFILE spanMs/f={} spanSamples/f={} fragTailMs/f={} fragTailSamples/f={} blitMs/f={} blitSamples/f={} droppedSamples/f={}",
                fmt(profileSpanNs / 1e6 / count), fmt((double)profileSpanSamples / count),
                fmt(profileFragTailNs / 1e6 / count), fmt((double)profileFragTailSamples / count),
                fmt(profileBlitNs / 1e6 / count), fmt((double)profileBlitSamples / count),
                fmt((double)profileDroppedSamples / count));
        }
        if (!blits.isEmpty()) {
            List<Map.Entry<String, long[]>> top = new ArrayList<>(blits.entrySet());
            top.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            StringBuilder line = new StringBuilder("BENCH-BLITS");
            for (Map.Entry<String, long[]> e : top.subList(0, Math.min(8, top.size()))) {
                line.append(" | ").append(e.getKey()).append(" count/f=").append(fmt((double)e.getValue()[0] / count))
                    .append(" bytes/f=").append(fmt(e.getValue()[1] / (double)count));
            }
            LOGGER.info(line.toString());
        }
        long frameNsSum = 0;
        for (int i = 0; i < count; i++) {
            frameNsSum += frames[i];
        }
        io.github.imcinq.puremetal.backend.EncodeProfile.report(frameNsSum, sorted[count / 2]);
        io.github.imcinq.puremetal.backend.FrameTrace.report();
        if (!passTrace.isEmpty()) {
            reportPassTrace(count);
        }
        if (gpuFrames > 0) {
            LOGGER.info(
                "BENCH encoders/f={} mergedPasses/f={} blitEncoders/f={} blitsClosingPass/f={} attachLoadMB/f={} attachStoreMB/f={} earlyUploads/f={} closingByKind(buf,buf2tex,tex2buf,tex2tex,present)/f={},{},{},{},{} bytesBinds/f={} m4FrameFences/f={}",
                fmt((double)stats[0] / count), fmt((double)stats[1] / count), fmt((double)stats[2] / count), fmt((double)stats[3] / count),
                fmt(stats[4] / 1e6 / count), fmt(stats[5] / 1e6 / count), fmt((double)stats[6] / count),
                fmt((double)stats[7] / count), fmt((double)stats[8] / count), fmt((double)stats[9] / count), fmt((double)stats[10] / count),
                fmt((double)stats[11] / count), fmt((double)bytesBinds / count), fmt((double)frameFences / count)
            );
        }
    }

    private static long pct(final long[] sorted, final double p) {
        return sorted[Math.min(sorted.length - 1, (int)Math.ceil(p * sorted.length) - 1)];
    }

    private static String ms(final long ns) {
        return fmt(ns / 1e6);
    }

    private static String fmt(final double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
