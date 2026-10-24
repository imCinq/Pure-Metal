package io.github.imcinq.puremetal.bench;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** Render-loop cadence, not display presentation timing. Called only with profiling enabled. */
@Name("puremetal.Frame")
@Label("Gameplay frame interval")
@Category({"Pure Metal", "Performance"})
@StackTrace(false)
public final class FrameProfile extends Event {
    @Timespan(Timespan.NANOSECONDS) public long intervalNs;
    private static long previous;
    private static boolean previousPlaying;

    /** Render thread only; skip intervals touching a menu or world transition. */
    public static void frame(long now, boolean playing) {
        if (playing && previousPlaying && now > previous) {
            FrameProfile event = new FrameProfile();
            if (event.isEnabled()) {
                event.intervalNs = now - previous;
                event.commit();
            }
        }
        previous = now;
        previousPlaying = playing;
    }
}
