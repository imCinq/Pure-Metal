package io.github.imcinq.puremetal.bench;
import jdk.jfr.*;
/** Aggregate completed-query observations. Hidden counts never suppress actual draws. */
@Name("puremetal.TerrainProbe") @Label("No-cull terrain depth probe")
@Category({"Pure Metal","Performance"}) @StackTrace(false)
public final class TerrainProbeProfile extends Event {
    public long queries,hidden,dropped,completedProbes,validSpans;
    @Timespan(Timespan.NANOSECONDS) public long computeSpanNs;
    public static void record(long queries,long hidden,long dropped,long ns,long probes,long valid) {
        if(queries==0 && dropped==0 && probes==0) return;
        var event=new TerrainProbeProfile();event.queries=queries;event.hidden=hidden;event.dropped=dropped;
        event.computeSpanNs=ns;event.completedProbes=probes;event.validSpans=valid;event.commit();
    }
}
