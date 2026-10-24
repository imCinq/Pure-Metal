// Java 25: java tools/AnalyzeGameplayProfile.java capture.jfr [bucketSeconds [stageDivisor [startSeconds endSeconds]]]
// Frame cadence is rendered-loop timing; GPU counters are completion-window aggregates.
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import jdk.jfr.consumer.*;

public class AnalyzeGameplayProfile {
    static double stageDivisor = 1;
    static class Window {
        ArrayList<Long> frames = new ArrayList<>();
        long wait, gpu, uploads, completed, uploadCount, vertex, fragment, blit, vs, fs, bs;
        long gcNs, gcMax, gcCount;
        long paletteSamples, paletteWeight;
        HashMap<String, Long> allocation = new HashMap<>();
        void accept(RecordedEvent e, boolean skipAllocation) {
            switch (e.getEventType().getName()) {
                case "puremetal.Frame" -> frames.add(e.getLong("intervalNs"));
                case "puremetal.Submission" -> {
                    wait += e.getLong("waitNs"); gpu += e.getLong("frameGpuNs");
                    uploads += e.getLong("earlyUploadGpuNs"); completed += e.getLong("completedFrames");
                    uploadCount += e.getLong("completedUploads");
                    if (e.hasField("renderVertexNs")) {
                        vertex += e.getLong("renderVertexNs"); fragment += e.getLong("renderFragmentNs");
                        blit += e.getLong("inFrameBlitNs"); vs += e.getLong("vertexSamples");
                        fs += e.getLong("fragmentSamples"); bs += e.getLong("blitSamples");
                    }
                }
                case "jdk.GCPhasePause" -> {
                    long ns = e.getDuration().toNanos(); gcNs += ns; gcMax = Math.max(gcMax, ns); gcCount++;
                }
                case "jdk.ObjectAllocationSample" -> {
                    if (!skipAllocation) {
                        String name = e.getClass("objectClass").getName();
                        var stack = e.getStackTrace();
                        if (stack != null) {
                            for (var frame : stack.getFrames()) {
                                String owner = frame.getMethod().getType().getName();
                                if (!owner.startsWith("java.") && !owner.startsWith("jdk.") && !owner.startsWith("it.unimi.")) {
                                    name += " @ " + owner + "." + frame.getMethod().getName(); break;
                                }
                            }
                        }
                        allocation.merge(name, e.getLong("weight"), Long::sum);
                        if (name.contains("PaletteScratch.reencode")) {
                            paletteSamples++; paletteWeight += e.getLong("weight");
                        }
                    }
                }
            }
        }
        void print(String label, boolean allocations) {
            Collections.sort(frames);
            long sum = frames.stream().mapToLong(Long::longValue).sum();
            System.out.printf(Locale.ROOT, "%s frames=%d activeSeconds=%.2f fps=%.1f p99.5Ms=%.2f maxMs=%.2f | completed=%d gpuMs/buf=%.2f waitMs/completed=%.2f vertexMs/buf=%.2f fragmentMs/buf=%.2f blitMs/buf=%.2f uploadMs/buf=%.2f samples=%d/%d/%d | GC=%d totalMs=%.2f maxMs=%.2f%n",
                label, frames.size(), sum/1e9, sum == 0 ? 0 : frames.size()*1e9/sum,
                frames.isEmpty() ? 0 : frames.get(Math.min(frames.size()-1,(int)Math.ceil(frames.size()*.995)-1))/1e6,
                frames.isEmpty() ? 0 : frames.getLast()/1e6, completed,
                avg(gpu,completed),avg(wait,completed),avg(vertex,completed)/stageDivisor,avg(fragment,completed)/stageDivisor,avg(blit,completed)/stageDivisor,avg(uploads,uploadCount),vs,fs,bs,gcCount,gcNs/1e6,gcMax/1e6);
            if (allocations) {
                System.out.printf(Locale.ROOT,"PALETTE-SCRATCH samples=%d sampledMiB=%.2f%n",paletteSamples,paletteWeight/1048576.0);
                allocation.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(10)
                    .forEach(e -> System.out.printf(Locale.ROOT,"  sampledMiB=%.1f %s%n",e.getValue()/1048576.0,e.getKey()));
            }
        }
    }
    static double avg(long ns, long count) { return count == 0 ? 0 : ns/1e6/count; }
    public static void main(String[] args) throws Exception {
        var events = RecordingFile.readAllEvents(Path.of(args[0]));
        events.sort(Comparator.comparing(RecordedEvent::getStartTime));
        Instant start = events.getFirst().getStartTime();
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        stageDivisor = args.length > 2 ? Double.parseDouble(args[2]) : 1;
        double from = args.length > 3 ? Double.parseDouble(args[3]) : 0;
        double to = args.length > 4 ? Double.parseDouble(args[4]) : Double.POSITIVE_INFINITY;
        TreeMap<Long, Window> windows = new TreeMap<>();
        HashSet<Long> sampledThreads = new HashSet<>();
        Window all = new Window();
        System.out.println("Origin UTC="+start+"; GPU stage sums overlap; buckets include menu submissions, frame series excludes menus. Allocation weights are sampled estimates; first sample per thread excluded.");
        System.out.println("Selected seconds=["+from+","+to+"); stage divisor="+stageDivisor+" (1 for current captures; 125/3 for Oct11 M2 capture with old double conversion).");
        for (var e : events) {
            boolean skip = false;
            if (e.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
                var thread = e.getThread(); skip = thread == null || sampledThreads.add(thread.getJavaThreadId());
            }
            double elapsed = java.time.Duration.between(start,e.getStartTime()).toNanos()/1e9;
            if (elapsed < from || elapsed >= to) continue;
            long bucket = (long)elapsed/seconds*seconds;
            windows.computeIfAbsent(bucket,k -> new Window()).accept(e,skip);
            all.accept(e,skip);
        }
        for (var e : windows.entrySet()) e.getValue().print("+"+e.getKey()+"s",false);
        all.print("ALL",true);
    }
}
