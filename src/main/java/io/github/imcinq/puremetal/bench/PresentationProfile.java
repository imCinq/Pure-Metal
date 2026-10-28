package io.github.imcinq.puremetal.bench;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * Once-per-interval presentation counters; these do not represent individual frame timings. The width/height/vsync fields
 * are the configuration last requested through the surface, not proof that CAMetalLayer already applied it. The native
 * maxima cover the whole reporting interval and are only captured while -Dpuremetal.frameTrace is on.
 */
@Name("puremetal.Presentation")
@Label("Presentation interval")
@Category({"Pure Metal", "Performance"})
@StackTrace(false)
public final class PresentationProfile extends Event {
    public static final boolean ENABLED = Boolean.getBoolean("puremetal.profile");
    @Timespan(Timespan.NANOSECONDS) public long intervalNs;
    public int width, height;
    public boolean focused, vsync, nativeTimingEnabled;
    public long rendered, presented, shown, skipped, slotsBusy;
    @Timespan(Timespan.NANOSECONDS) public long submitToShownSumNs;
    @Timespan(Timespan.NANOSECONDS) public long drawableWaitMaxNs, layerConfigureMaxNs;

    @Name("puremetal.SurfaceConfiguration")
    @Label("Requested surface configuration")
    @Category({"Pure Metal", "Performance"})
    @StackTrace(false)
    public static final class Configuration extends Event {
        public int width, height;
        public boolean vsync;
    }
}
