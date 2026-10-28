package io.github.imcinq.puremetal.backend;

import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Dev aid, off by default (-Dpuremetal.frameTrace=true): native timestamps (mach time) for the render thread, the GPU
 * and the present thread, analysed at the end of the bench to say which interval made each slow frame slow.
 */
public final class FrameTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = Boolean.getBoolean("puremetal.frameTrace");
    static final int FRAME = 1, WAIT_ENTER = 2, WAIT_EXIT = 3, RCB = 4, SUBMIT = 5, PCB = 6, ND_ENTER = 7, ND_EXIT = 8,
        PRESENTED = 9, CFG_ENTER = 10, CFG_EXIT = 11, ROT_ENTER = 12, ROT_EXIT = 13;
    public static final int BENCH_START = 14;
    /** The GPU finished submit (Metal 4 commit feedback; the completion handler on Metal 3). a = submit index. */
    static final int FEEDBACK = 15;
    /** Metal 4: the frame's shared-event listener ran, which is what advances the submit Java waits on. a = frame. */
    static final int EVENT_SIGNAL = 16;
    static final int WALL_CLOCK = 17;
    private static final int REC = 40;
    private static final double SLOW_MS = Double.parseDouble(System.getProperty("puremetal.frameTraceSlowMs", "20.0"));

    private FrameTrace() {
    }

    static void init() {
        if (ENABLED) {
            MetalNative.traceEnable();
        }
    }

    public static void mark(final int type, final long a) {
        mark(type, a, 0L, 0L);
    }

    static void mark(final int type, final long a, final long b, final long c) {
        if (ENABLED) {
            MetalNative.traceMark(type, a, b, c);
        }
    }

    private record Ev(long t, long a, long b, long c, int type) {
    }

    private static double ms(final long ns) {
        return ns / 1e6;
    }

    public static void report() {
        if (!ENABLED) {
            return;
        }
        int cap = 262144;
        ByteBuffer buf = MemoryUtil.memAlloc(cap * REC).order(ByteOrder.nativeOrder());
        List<Ev> all = new ArrayList<>();
        try {
            // Included at the end even if a high-FPS run wrapped the ring: aligns the raw trace with JFR/wall time.
            mark(WALL_CLOCK, System.currentTimeMillis());
            int n = MetalNative.traceRead(MemoryUtil.memAddress(buf), cap);
            int start = 0;
            for (int i = 0; i < n; i++) {
                int o = i * REC;
                Ev e = new Ev(buf.getLong(o), buf.getLong(o + 8), buf.getLong(o + 16), buf.getLong(o + 24), buf.getInt(o + 32));
                if (e.type == BENCH_START) {
                    start = all.size();
                }
                all.add(e);
            }
            dump(all, n == cap);
            analyse(all, start, n);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    /**
     * Opt-in raw export (-Dpuremetal.frameTraceOutput=path), one CREATE_NEW CSV. The native ring holds 262144 events, so a
     * run at thousands of frames per second overwrites its oldest events: the file is a recent tail, not whole-run history.
     * The final row is the type-17 WALL_CLOCK anchor (a = System.currentTimeMillis) that lines native time up with JFR.
     */
    private static void dump(final List<Ev> evs, final boolean wrapped) {
        String output = System.getProperty("puremetal.frameTraceOutput");
        if (output == null || output.isBlank()) return;
        try {
            Path path = Path.of(output);
            try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write("nativeNs,type,a,b,c\n");
                for (Ev e : evs) {
                    writer.write(e.t + "," + e.type + "," + e.a + "," + e.b + "," + e.c + "\n");
                }
            }
            LOGGER.info("FRAMETRACE raw events saved to {} ({} rows, wall-clock anchor type {})", path, evs.size(), WALL_CLOCK);
            if (wrapped) {
                LOGGER.warn("FRAMETRACE raw export wrapped the {}-event ring: it contains only the most recent events, not the whole run",
                    evs.size());
            }
        } catch (java.io.IOException | RuntimeException error) {
            LOGGER.warn("FRAMETRACE raw export failed: {}", error.getClass().getSimpleName());
        }
    }

    /** One serial operation's spans, clipped to each render-frame window without losing an open entry. */
    static final class Interval {
        private long entered = -1L;
        private long start, end;
        long total, max;

        void window(final long start, final long end) {
            this.start = start;
            this.end = end;
            this.total = this.max = 0L;
        }

        void enter(final long time) {
            this.entered = time;
        }

        void exit(final long time) {
            if (this.entered >= 0L) this.add(this.entered, time);
            this.entered = -1L;
        }

        void finish() {
            if (this.entered >= 0L) this.add(this.entered, this.end);
        }

        private void add(final long from, final long to) {
            long duration = Math.max(0L, Math.min(this.end, to) - Math.max(this.start, from));
            this.total += duration;
            this.max = Math.max(this.max, duration);
        }
    }

    /** One measured slow render window, as reported in the slow-frame log line. */
    static final class SlowWindow {
        final double frameMs, waitMs, cfgMs, rotMs, nextDrawableMaxMs, presentGpuMaxMs, maxPresentedGapMs;
        final int presents;
        final String cause;

        SlowWindow(final double frameMs, final double waitMs, final double cfgMs, final double rotMs, final double nextDrawableMaxMs,
            final double presentGpuMaxMs, final int presents, final double maxPresentedGapMs, final String cause) {
            this.frameMs = frameMs;
            this.waitMs = waitMs;
            this.cfgMs = cfgMs;
            this.rotMs = rotMs;
            this.nextDrawableMaxMs = nextDrawableMaxMs;
            this.presentGpuMaxMs = presentGpuMaxMs;
            this.presents = presents;
            this.maxPresentedGapMs = maxPresentedGapMs;
            this.cause = cause;
        }
    }

    /** Headless-visible result of {@link #analyse}; the log lines are rendered from the same numbers. */
    static final class Summary {
        final int events, ringCount, frames, windows, slow;
        final long configureCalls;
        final java.util.Map<String, Integer> causes;
        final java.util.List<SlowWindow> slowWindows;

        Summary(final int events, final int ringCount, final int frames, final int windows, final int slow, final long configureCalls,
            final java.util.Map<String, Integer> causes, final java.util.List<SlowWindow> slowWindows) {
            this.events = events;
            this.ringCount = ringCount;
            this.frames = frames;
            this.windows = windows;
            this.slow = slow;
            this.configureCalls = configureCalls;
            this.causes = causes;
            this.slowWindows = slowWindows;
        }
    }

    private static Summary analyse(final List<Ev> all, final int start, final int ringCount) {
        List<Ev> evs = all.subList(start, all.size());
        List<Integer> frames = new ArrayList<>();
        long cfgCalls = 0;
        for (int i = 0; i < evs.size(); i++) {
            if (evs.get(i).type == FRAME) {
                frames.add(i);
            } else if (evs.get(i).type == CFG_ENTER) {
                cfgCalls++;
            }
        }
        LOGGER.info("FRAMETRACE events={} (ring read {}) frames={} configureCallsInBench={}", evs.size(), ringCount, frames.size(), cfgCalls);
        if (ringCount == 262144) LOGGER.warn("FRAMETRACE ring full: this run wrapped the 262144-event buffer, so only the most recent events remain; unmatched exits are ignored");
        // GPU completion marks: how long after submit the commit feedback arrived, and (Metal 4) how long after that the shared-event
        // listener that advances the completion timeline Java waits on ran. Feedback and listener are matched by frame, not by
        // order: the listener runs on its own queue, so the two can interleave arbitrarily across frames.
        java.util.Map<Long, Long> submits = new java.util.HashMap<>();
        for (Ev e : evs) {
            if (e.type == SUBMIT) {
                submits.put(e.a, e.t);
            }
        }
        long feedbacks = 0, feedbackLag = 0, feedbackLagMax = 0;
        java.util.Map<Long, Long> feedbackByFrame = new java.util.HashMap<>();
        for (Ev e : evs) {
            if (e.type != FEEDBACK) {
                continue;
            }
            feedbacks++;
            Long submit = submits.get(e.a);
            if (submit != null && e.t > submit) {
                long lag = e.t - submit;
                feedbackLag += lag;
                feedbackLagMax = Math.max(feedbackLagMax, lag);
            }
            feedbackByFrame.putIfAbsent(e.b, e.t);
        }
        // Only a listener event whose frame has a feedback mark of its own is a sample: the delta is signed (the listener can beat
        // the feedback handler) and unmatched events are left out of the average instead of being charged to another frame.
        long signals = 0, signalMatched = 0, signalLag = 0, signalLagMax = 0;
        for (Ev e : evs) {
            if (e.type != EVENT_SIGNAL) {
                continue;
            }
            signals++;
            Long feedback = feedbackByFrame.get(e.a);
            if (feedback == null) {
                continue;
            }
            signalMatched++;
            long lag = e.t - feedback;
            signalLag += lag;
            signalLagMax = Math.max(signalLagMax, lag);
        }
        java.util.Map<String, Integer> causes = new java.util.TreeMap<>();
        java.util.List<SlowWindow> slowWindows = new ArrayList<>();
        Interval waits = new Interval(), configs = new Interval(), rotations = new Interval(), drawables = new Interval();
        long activeWaitIdx = -1L, lastPresented = 0L;
        // Entries before BENCH_START/the first frame can still have exits inside a measured window.
        int beforeFirstFrame = frames.isEmpty() ? all.size() : start + frames.getFirst();
        for (int i = 0; i < beforeFirstFrame; i++) {
            Ev e = all.get(i);
            switch (e.type) {
                case WAIT_ENTER -> { waits.enter(e.t); activeWaitIdx = e.a; }
                case WAIT_EXIT -> waits.exit(e.t);
                case CFG_ENTER -> configs.enter(e.t);
                case CFG_EXIT -> configs.exit(e.t);
                case ROT_ENTER -> rotations.enter(e.t);
                case ROT_EXIT -> rotations.exit(e.t);
                case ND_ENTER -> drawables.enter(e.t);
                case ND_EXIT -> drawables.exit(e.t);
                case PRESENTED -> { if (e.a > 0L) lastPresented = e.a; }
                default -> { }
            }
        }
        int slow = 0, detail = 0;
        for (int f = 0; f + 1 < frames.size(); f++) {
            int lo = frames.get(f), hi = frames.get(f + 1);
            long t0 = evs.get(lo).t, t1 = evs.get(hi).t;
            double frameMs = ms(t1 - t0);
            waits.window(t0, t1);
            configs.window(t0, t1);
            rotations.window(t0, t1);
            drawables.window(t0, t1);
            long waitIdx = activeWaitIdx;
            int presents = 0;
            long maxPresentGap = 0, pcbMax = 0;
            for (int i = lo; i < hi; i++) {
                Ev e = evs.get(i);
                switch (e.type) {
                    case WAIT_ENTER -> { waits.enter(e.t); activeWaitIdx = waitIdx = e.a; }
                    case WAIT_EXIT -> waits.exit(e.t);
                    case CFG_ENTER -> configs.enter(e.t);
                    case CFG_EXIT -> configs.exit(e.t);
                    case ROT_ENTER -> rotations.enter(e.t);
                    case ROT_EXIT -> rotations.exit(e.t);
                    case ND_ENTER -> drawables.enter(e.t);
                    case ND_EXIT -> drawables.exit(e.t);
                    case PCB -> pcbMax = Math.max(pcbMax, e.c - e.b);
                    case PRESENTED -> {
                        if (e.a <= 0L) break; // A drawable discarded while hidden has no shown timestamp.
                        presents++;
                        if (lastPresented != 0) {
                            maxPresentGap = Math.max(maxPresentGap, e.a - lastPresented);
                        }
                        lastPresented = e.a;
                    }
                    default -> { }
                }
            }
            waits.finish();
            configs.finish();
            rotations.finish();
            drawables.finish();
            // Fast windows must update the trackers too: an operation may end in a later slow window.
            if (frameMs <= SLOW_MS) continue;
            slow++;
            long nd = drawables.max;
            String cause;
            String sub = "";
            double w = ms(waits.total), c = ms(configs.total), r = ms(rotations.total);
            double dom = Math.max(w, Math.max(c, r));
            if (dom < 0.3 * frameMs) {
                cause = "other CPU";
            } else if (dom == c) {
                cause = "configure blocked";
            } else if (dom == r) {
                cause = "rotation wait";
            } else {
                cause = "wait: ?";
                for (Ev e : evs) {
                    if (e.type == RCB && e.a == waitIdx) {
                        long submit = 0;
                        for (Ev s : evs) {
                            if (s.type == SUBMIT && s.a == waitIdx) {
                                submit = s.t;
                            }
                        }
                        double lag = ms(e.t - e.c), startDelay = submit == 0 ? -1 : ms(e.b - submit), exec = ms(e.c - e.b);
                        sub = String.format(" submit#%d commit->gpuStart=%.1f gpuExec=%.1f gpuEnd->callback=%.1f", waitIdx, startDelay, exec, lag);
                        if (lag > 5.0) {
                            cause = "wait: callback late";
                        } else if (startDelay > 5.0) {
                            cause = "wait: GPU start late (queue)";
                        } else {
                            cause = "wait: GPU exec long";
                        }
                        break;
                    }
                }
            }
            causes.merge(cause, 1, Integer::sum);
            if (nd > 5_000_000L) {
                causes.merge("(nextDrawable >5ms in window, indirect)", 1, Integer::sum);
            }
            slowWindows.add(new SlowWindow(frameMs, w, c, r, ms(nd), ms(pcbMax), presents, ms(maxPresentGap), cause));
            if (detail++ < 25) {
                LOGGER.info("FRAMETRACE slow frame {} ms: wait={} cfg={} rot={} nextDrawableMax={} presentGpuMax={} presents={} maxPresentedGap={} cause={}{}",
                    String.format("%.1f", frameMs), String.format("%.1f", w), String.format("%.1f", c), String.format("%.1f", r),
                    String.format("%.1f", ms(nd)), String.format("%.1f", ms(pcbMax)), presents, String.format("%.1f", ms(maxPresentGap)), cause, sub);
            }
        }
        int windows = Math.max(frames.size() - 1, 0);
        LOGGER.info("FRAMETRACE slow(>{}ms)={} of {} frames; causes={} | commitFeedback={} submitToFeedbackAvg={} max={} | eventListener={} matched={} feedbackToEventAvg={} max={}",
            SLOW_MS, slow, windows, causes,
            feedbacks, feedbacks == 0 ? "n/a" : String.format("%.2f", ms(feedbackLag) / feedbacks),
            String.format("%.2f", ms(feedbackLagMax)),
            signals, signalMatched,
            signalMatched == 0 ? "n/a" : String.format("%.2f", ms(signalLag) / signalMatched),
            String.format("%.2f", ms(signalLagMax)));
        return new Summary(evs.size(), ringCount, frames.size(), windows, slow, cfgCalls, causes, slowWindows);
    }
}
