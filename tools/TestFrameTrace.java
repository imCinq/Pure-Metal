package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.bench.PresentationProfile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/** Headless regression checks for cross-frame trace spans and persistent presentation JFR fields. */
public final class TestFrameTrace {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < 1e-6;
    }

    public static void main(String[] args) throws Exception {
        FrameTrace.Interval interval = new FrameTrace.Interval();
        interval.window(0, 10);
        interval.exit(5);
        require(interval.total == 0 && interval.max == 0, "orphan exit must not subtract zero");
        interval.enter(0);
        interval.finish();
        require(interval.total == 10 && interval.max == 10, "unfinished interval is clipped, including an entry at zero");
        interval.window(10, 20);
        interval.exit(15);
        interval.finish();
        require(interval.total == 5 && interval.max == 5, "entry carries across a frame boundary");
        interval.window(20, 30);
        interval.exit(25);
        interval.finish();
        require(interval.total == 0, "completed interval must not survive into another window");
        interval.enter(21);
        interval.exit(23);
        interval.enter(25);
        interval.exit(29);
        require(interval.total == 6 && interval.max == 4, "sum and longest span remain distinct");
        interval.window(30, 40);
        interval.enter(35);
        interval.exit(34);
        require(interval.total == 0, "malformed negative duration must not create a spike");

        // A drawable acquisition starts before BENCH_START, spans a fast window, then ends in a slow window.
        // The next exit is unmatched. The shown timestamps straddle the fast/slow frame boundary too, and a
        // hidden drawable with no shown timestamp (a=0) must not reset the carried timestamp.
        Class<?> eventClass = Class.forName(FrameTrace.class.getName() + "$Ev");
        var constructor = eventClass.getDeclaredConstructor(long.class, long.class, long.class, long.class, int.class);
        constructor.setAccessible(true);
        List<Object> events = new ArrayList<>();
        long[][] fixture = {
            {990, 7, 0}, {995, 14, 0}, {1000, 1, 0}, {1006, 9, 1005},
            {1010, 1, 0}, {1015, 9, 0}, {1025, 8, 0}, {1036, 9, 1035}, {1040, 1, 0},
            {1060, 8, 0}, {1070, 1, 0}
        };
        for (long[] e : fixture) events.add(constructor.newInstance(e[0] * 1_000_000L, e[2] * 1_000_000L, 0L, 0L, (int)e[1]));
        var analyse = FrameTrace.class.getDeclaredMethod("analyse", List.class, int.class, int.class);
        analyse.setAccessible(true);
        FrameTrace.Summary summary = (FrameTrace.Summary) analyse.invoke(null, events, 1, events.size());
        require(summary.events == fixture.length - 1 && summary.ringCount == fixture.length, "analysis event counts");
        require(summary.frames == 4 && summary.windows == 3, "four frame marks must make three measured windows");
        require(summary.configureCalls == 0, "no configure events in fixture");
        require(summary.slow == 2, "only the two 30ms windows are slow, got " + summary.slow);
        require(summary.slowWindows.size() == 2, "slow window details");
        FrameTrace.SlowWindow first = summary.slowWindows.get(0);
        require(near(first.frameMs, 30.0) && near(first.nextDrawableMaxMs, 15.0),
            "drawable entered before BENCH_START is clipped into the slow window, got " + first.nextDrawableMaxMs);
        require(near(first.maxPresentedGapMs, 30.0) && first.presents == 1,
            "shown gap must carry from the fast window, got " + first.maxPresentedGapMs);
        FrameTrace.SlowWindow second = summary.slowWindows.get(1);
        require(near(second.nextDrawableMaxMs, 0.0), "orphan ND_EXIT must not become a spike, got " + second.nextDrawableMaxMs);
        require(near(second.maxPresentedGapMs, 0.0) && second.presents == 0, "no presents in the final window");
        require(summary.causes.equals(java.util.Map.of("other CPU", 2, "(nextDrawable >5ms in window, indirect)", 1)),
            "cause counts " + summary.causes);
        System.out.printf("analysis windows=%d slow=%d firstND=%.1f firstGap=%.1f secondND=%.1f causes=%s%n",
            summary.windows, summary.slow, first.nextDrawableMaxMs, first.maxPresentedGapMs, second.nextDrawableMaxMs, summary.causes);

        var file = Files.createTempFile("pm-presentation-check-", ".jfr");
        try {
            try (Recording recording = new Recording()) {
                recording.enable("puremetal.Presentation");
                recording.enable("puremetal.SurfaceConfiguration");
                recording.start();
                PresentationProfile.Configuration config = new PresentationProfile.Configuration();
                config.width = 1280;
                config.height = 720;
                config.vsync = false;
                config.begin();
                config.commit();
                PresentationProfile sample = new PresentationProfile();
                sample.width = 1280;
                sample.height = 720;
                sample.focused = true;
                sample.nativeTimingEnabled = true;
                sample.intervalNs = 1_000_000_000L;
                sample.rendered = 2500;
                sample.shown = 60;
                sample.drawableWaitMaxNs = 8_000_000L;
                sample.layerConfigureMaxNs = 2_000_000L;
                sample.commit();
                recording.stop();
                recording.dump(file);
            }
            var recorded = RecordingFile.readAllEvents(file);
            var config = recorded.stream().filter(e -> e.getEventType().getName().equals("puremetal.SurfaceConfiguration")).findFirst().orElseThrow();
            require(config.getInt("width") == 1280 && config.getInt("height") == 720 && !config.getBoolean("vsync"), "configuration JFR round trip");
            var sample = recorded.stream().filter(e -> e.getEventType().getName().equals("puremetal.Presentation")).findFirst().orElseThrow();
            require(sample.getLong("rendered") == 2500 && sample.getLong("shown") == 60 && sample.getBoolean("focused"), "presentation counts and focus");
            require(sample.getLong("drawableWaitMaxNs") == 8_000_000L && sample.getLong("layerConfigureMaxNs") == 2_000_000L
                && sample.getBoolean("nativeTimingEnabled"), "native interval maximum fields");
        } finally {
            Files.deleteIfExists(file);
        }
        System.out.println("PASS trace interval boundaries and presentation JFR round trip");
    }
}
