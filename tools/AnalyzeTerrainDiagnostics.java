// Java25: java tools/AnalyzeTerrainDiagnostics.java capture.jfr
import java.nio.file.Path;
import java.util.Locale;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordingFile;

public final class AnalyzeTerrainDiagnostics {
    static final String[] FRAME_FIELDS={"separateCalls","separateCommands","indirectCalls","indirectCommands",
        "solidCalls","solidCommands","solidMatched","solidBounded","solidSections",
        "sodiumOpaqueBatches","sodiumOpaqueCommands","sodiumOpaqueMatched","sodiumOpaqueBounded","sodiumOpaqueRanges",
        "sodiumCutoutBatches","sodiumCutoutCommands","sodiumCutoutRanges",
        "sodiumTranslucentBatches","sodiumTranslucentCommands","sodiumTranslucentRanges"};
    static final class Totals {
        long batches,commands,matched,bounded,ranges,failed,cpuNs;
    }
    public static void main(String[] args) throws Exception {
        var rows=new TreeMap<String,Totals>();
        long queries=0,hidden=0,dropped=0,probeNs=0,probes=0,valid=0;
        var frame=new TreeMap<String,long[]>(); // field -> {sum, max}
        long frames=0;
        try(var recording=new RecordingFile(Path.of(args[0]))) {
            while(recording.hasMoreEvents()) {
                var event=recording.readEvent();
                if(event.getEventType().getName().equals("puremetal.TerrainProbe")) {
                    long q=event.getLong("queries"),h=event.getLong("hidden"),p=event.getLong("completedProbes"),v=event.getLong("validSpans");
                    if(q<0 || h<0 || h>q || p<0 || v<0 || v>p) throw new IllegalStateException("Invalid completed probe statistics");
                    queries+=q;hidden+=h;probes+=p;valid+=v;dropped+=event.getLong("dropped");probeNs+=event.getLong("computeSpanNs");
                    continue;
                }
                if(event.getEventType().getName().equals("puremetal.TerrainFrame")) {
                    frames++;
                    for(String field:FRAME_FIELDS) {
                        int value=event.getInt(field);
                        if(value<0) throw new IllegalStateException("Invalid frame count "+field);
                        var row=frame.computeIfAbsent(field,k->new long[2]);row[0]+=value;row[1]=Math.max(row[1],value);
                    }
                    if(frame.get("solidBounded")[0]>frame.get("solidMatched")[0] || frame.get("solidMatched")[0]>frame.get("solidCommands")[0]
                        || frame.get("sodiumOpaqueBounded")[0]>frame.get("sodiumOpaqueMatched")[0] || frame.get("sodiumOpaqueMatched")[0]>frame.get("sodiumOpaqueCommands")[0])
                        throw new IllegalStateException("Invalid frame coverage");
                    continue;
                }
                if(!event.getEventType().getName().equals("puremetal.TerrainDraw")) continue;
                String key=event.getString("renderer")+"/"+event.getString("layer");
                var totals=rows.computeIfAbsent(key,k->new Totals());
                if(!event.getBoolean("submitted")) {totals.failed++;continue;}
                int commands=event.getInt("commands"),matched=event.getInt("matchedCommands");
                if(commands<0 || matched<0 || matched>commands) throw new IllegalStateException("Invalid diagnostic coverage");
                totals.batches++;totals.commands+=commands;totals.matched+=matched;totals.ranges+=event.getInt("sectionRanges");
                if(event.hasField("boundedCommands")) {
                    int bounded=event.getInt("boundedCommands");
                    if(bounded<0 || bounded>matched) throw new IllegalStateException("Invalid mesh-bound coverage");
                    totals.bounded+=bounded;
                }
                totals.cpuNs+=event.getDuration().toNanos();
                if(matched>0 && (event.getInt("minX")>event.getInt("maxX") || event.getInt("minY")>event.getInt("maxY") || event.getInt("minZ")>event.getInt("maxZ")))
                    throw new IllegalStateException("Invalid diagnostic section extent");
            }
        }
        System.out.println("CPU submission diagnostics; durations include hook overhead. Section origins are NOT validated mesh bounds. No hidden-terrain or FPS savings measured.");
        rows.forEach((key,t)->System.out.printf(Locale.ROOT,"%s batches=%d commands=%d matched=%d coverage=%.2f%% bounded=%d sectionRanges=%d failed=%d cpuMs=%.2f%n",
            key,t.batches,t.commands,t.matched,t.commands==0?0:100.0*t.matched/t.commands,t.bounded,t.ranges,t.failed,t.cpuNs/1e6));
        if(probes>0 || dropped>0) System.out.printf(Locale.ROOT,
            "No-cull probe: queries=%d hiddenObservations=%d (%.2f%%) dropped=%d completedProbes=%d validSpans=%d meanComputeSpanMs=%.3f. Repeated observations, not unique sections/draw savings. Extra stores/loads and CPU work excluded.%n",
            queries,hidden,queries==0?0:100.0*hidden/queries,dropped,probes,valid,valid==0?0:probeNs/1e6/valid);
        if(frames>0) {
            final long n=frames;
            java.util.function.Function<String,String> per=k->{var r=frame.get(k);return String.format(Locale.ROOT,"%.1f(max %d)",(double)r[0]/n,r[1]);};
            System.out.printf(Locale.ROOT,"Per-frame terrain submissions over %d frames that drew terrain (mean per frame, max in brackets):%n",n);
            System.out.printf(Locale.ROOT,"  vanilla DrawSeparate: calls=%s commands=%s | DrawIndirect: calls=%s commands=%s (all layers)%n",
                per.apply("separateCalls"),per.apply("separateCommands"),per.apply("indirectCalls"),per.apply("indirectCommands"));
            var solidCommands=frame.get("solidCommands")[0];
            System.out.printf(Locale.ROOT,"  vanilla SOLID candidates: calls=%s commands=%s sections=%s; metadata matched %.2f%%, measured bounds on %.2f%% of matched commands%n",
                per.apply("solidCalls"),per.apply("solidCommands"),per.apply("solidSections"),
                solidCommands==0?0:100.0*frame.get("solidMatched")[0]/solidCommands,
                frame.get("solidMatched")[0]==0?0:100.0*frame.get("solidBounded")[0]/frame.get("solidMatched")[0]);
            var sodiumCommands=frame.get("sodiumOpaqueCommands")[0];
            System.out.printf(Locale.ROOT,"  Sodium opaque (SOLID): batches=%s commands=%s ranges=%s; metadata matched %.2f%%, measured bounds on %.2f%% of matched commands%n",
                per.apply("sodiumOpaqueBatches"),per.apply("sodiumOpaqueCommands"),per.apply("sodiumOpaqueRanges"),
                sodiumCommands==0?0:100.0*frame.get("sodiumOpaqueMatched")[0]/sodiumCommands,
                frame.get("sodiumOpaqueMatched")[0]==0?0:100.0*frame.get("sodiumOpaqueBounded")[0]/frame.get("sodiumOpaqueMatched")[0]);
            System.out.printf(Locale.ROOT,"  Sodium cutout: batches=%s commands=%s ranges=%s | translucent: batches=%s commands=%s ranges=%s (ranges count local and shared runs of a section separately)%n",
                per.apply("sodiumCutoutBatches"),per.apply("sodiumCutoutCommands"),per.apply("sodiumCutoutRanges"),
                per.apply("sodiumTranslucentBatches"),per.apply("sodiumTranslucentCommands"),per.apply("sodiumTranslucentRanges"));
        }
        if(rows.isEmpty()) throw new IllegalStateException("No terrain diagnostic events");
    }
}
