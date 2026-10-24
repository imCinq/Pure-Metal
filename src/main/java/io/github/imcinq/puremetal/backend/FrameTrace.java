package io.github.imcinq.puremetal.backend;

import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        if (ENABLED) {
            MetalNative.traceMark(type, a, 0L, 0L);
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
            analyse(all.subList(start, all.size()), n);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static void analyse(final List<Ev> evs, final int ringCount) {
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
        int slow = 0, detail = 0;
        for (int f = 0; f + 1 < frames.size(); f++) {
            int lo = frames.get(f), hi = frames.get(f + 1);
            long t0 = evs.get(lo).t, t1 = evs.get(hi).t;
            double frameMs = ms(t1 - t0);
            if (frameMs <= SLOW_MS) {
                continue;
            }
            slow++;
            long wait = 0, cfg = 0, rot = 0, nd = 0, waitIdx = -1, enter = 0, ndEnter = 0, cfgEnter = 0, rotEnter = 0;
            int presents = 0;
            long lastPresented = 0, maxPresentGap = 0, pcbMax = 0;
            for (int i = lo; i < hi; i++) {
                Ev e = evs.get(i);
                switch (e.type) {
                    case WAIT_ENTER -> { enter = e.t; waitIdx = e.a; }
                    case WAIT_EXIT -> wait += e.t - enter;
                    case CFG_ENTER -> cfgEnter = e.t;
                    case CFG_EXIT -> cfg += e.t - cfgEnter;
                    case ROT_ENTER -> rotEnter = e.t;
                    case ROT_EXIT -> rot += e.t - rotEnter;
                    case ND_ENTER -> ndEnter = e.t;
                    case ND_EXIT -> nd = Math.max(nd, e.t - ndEnter);
                    case PCB -> pcbMax = Math.max(pcbMax, e.c - e.b);
                    case PRESENTED -> {
                        presents++;
                        if (lastPresented != 0) {
                            maxPresentGap = Math.max(maxPresentGap, e.a - lastPresented);
                        }
                        lastPresented = e.a;
                    }
                    default -> { }
                }
            }
            String cause;
            String sub = "";
            double w = ms(wait), c = ms(cfg), r = ms(rot);
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
            if (detail++ < 25) {
                LOGGER.info("FRAMETRACE slow frame {} ms: wait={} cfg={} rot={} nextDrawableMax={} presentGpuMax={} presents={} maxPresentedGap={} cause={}{}",
                    String.format("%.1f", frameMs), String.format("%.1f", w), String.format("%.1f", c), String.format("%.1f", r),
                    String.format("%.1f", ms(nd)), String.format("%.1f", ms(pcbMax)), presents, String.format("%.1f", ms(maxPresentGap)), cause, sub);
            }
        }
        LOGGER.info("FRAMETRACE slow(>{}ms)={} of {} frames; causes={} | commitFeedback={} submitToFeedbackAvg={} max={} | eventListener={} matched={} feedbackToEventAvg={} max={}",
            SLOW_MS, slow, Math.max(frames.size() - 1, 0), causes,
            feedbacks, feedbacks == 0 ? "n/a" : String.format("%.2f", ms(feedbackLag) / feedbacks),
            String.format("%.2f", ms(feedbackLagMax)),
            signals, signalMatched,
            signalMatched == 0 ? "n/a" : String.format("%.2f", ms(signalLag) / signalMatched),
            String.format("%.2f", ms(signalLagMax)));
    }
}
