// Java25, after a build, with build/classes/java/main on the class path and -Dpuremetal.terrainDiagnostics=true.
// Records fixture TerrainDraw submissions, rolls them up into TerrainFrame events and runs the analyzer on the recording.
import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import io.github.imcinq.puremetal.bench.TerrainFrameProfile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

public final class TestTerrainFrameProfile {
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    static void submit(String renderer, String layer, int commands, int first, Object owner) {
        var event = TerrainDrawProfile.start(renderer, layer, commands);
        event.match(owner, first, commands);
        event.submitted = true;
        event.finish();
    }
    public static void main(String[] args) throws Exception {
        require(TerrainDrawProfile.ENABLED);
        Object solid = new Object(), cutout = new Object(), plain = new Object();
        TerrainDrawProfile.attach(solid, List.of(
            new TerrainDrawProfile.Range(0, 0, 0, 0, 3, new io.github.imcinq.puremetal.perf.MeshBounds(-1, -1, -1, 17, 17, 17)),
            new TerrainDrawProfile.Range(16, 0, 0, 3, 1)));
        TerrainDrawProfile.attach(cutout, List.of(new TerrainDrawProfile.Range(0, 0, 0, 0, 2)));
        var file = Files.createTempFile("pm-terrain-frame-", ".jfr");
        try (var recording = new Recording()) {
            recording.enable("puremetal.TerrainDraw");
            recording.enable("puremetal.TerrainFrame");
            recording.start();
            TerrainFrameProfile.flush(); // nothing drawn yet: no event
            // Frame 1: vanilla indirect (SOLID 4 commands over 2 sections, 3 bounded) and a cutout group, plus one direct draw.
            submit("vanilla-indirect", "SOLID", 4, 0, solid);
            submit("vanilla-indirect", "CUTOUT", 2, 0, cutout);
            submit("vanilla-direct", "SOLID", 1, 0, plain);
            TerrainFrameProfile.flush();
            // Frame 2: Sodium opaque, cutout and translucent batches.
            submit("sodium", "opaque", 4, 0, solid);
            submit("sodium", "cutout", 2, 0, cutout);
            submit("sodium", "translucent", 1, 0, plain);
            TerrainFrameProfile.flush();
            TerrainFrameProfile.flush(); // nothing since the last flush: no event
            recording.stop();
            recording.dump(file);
        }
        var frames = RecordingFile.readAllEvents(file).stream().filter(e -> e.getEventType().getName().equals("puremetal.TerrainFrame")).toList();
        require(frames.size() == 2);
        var a = frames.get(0);
        require(a.getInt("indirectCalls") == 2 && a.getInt("indirectCommands") == 6 && a.getInt("separateCalls") == 1 && a.getInt("separateCommands") == 1);
        require(a.getInt("solidCalls") == 2 && a.getInt("solidCommands") == 5 && a.getInt("solidMatched") == 4 && a.getInt("solidBounded") == 3 && a.getInt("solidSections") == 2);
        var b = frames.get(1);
        require(b.getInt("sodiumOpaqueBatches") == 1 && b.getInt("sodiumOpaqueCommands") == 4 && b.getInt("sodiumOpaqueMatched") == 4
            && b.getInt("sodiumOpaqueBounded") == 3 && b.getInt("sodiumOpaqueRanges") == 2);
        require(b.getInt("sodiumCutoutBatches") == 1 && b.getInt("sodiumCutoutCommands") == 2 && b.getInt("sodiumCutoutRanges") == 1);
        require(b.getInt("sodiumTranslucentBatches") == 1 && b.getInt("sodiumTranslucentCommands") == 1 && b.getInt("solidCalls") == 0);
        var captured = new ByteArrayOutputStream();
        var original = System.out;
        System.setOut(new PrintStream(captured, true));
        try { AnalyzeTerrainDiagnostics.main(new String[]{file.toString()}); } finally { System.setOut(original); Files.delete(file); }
        String report = captured.toString();
        require(report.contains("Per-frame terrain submissions over 2 frames"));
        require(report.contains("DrawSeparate: calls=0.5(max 1) commands=0.5(max 1) | DrawIndirect: calls=1.0(max 2) commands=3.0(max 6)"));
        require(report.contains("vanilla SOLID candidates: calls=1.0(max 2) commands=2.5(max 5) sections=1.0(max 2)"));
        require(report.contains("Sodium opaque (SOLID): batches=0.5(max 1) commands=2.0(max 4) ranges=1.0(max 2)"));
        System.out.print(report);
        System.out.println("PASS terrain frame roll-up: counters, empty frames, JFR round-trip, analyzer report");
    }
}
