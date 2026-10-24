package io.github.imcinq.puremetal.bench;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import io.github.imcinq.puremetal.perf.MeshBounds;
import org.jspecify.annotations.Nullable;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Opt-in CPU submission diagnostics. Coordinates identify section grids, NOT safe custom-mesh culling bounds. */
@Name("puremetal.TerrainDraw")
@Label("Terrain draw submission")
@Category({"Pure Metal", "Performance"})
@StackTrace(false)
public final class TerrainDrawProfile extends Event {
    public static final boolean ENABLED = Boolean.getBoolean("puremetal.terrainDiagnostics");
    public String renderer, layer;
    public int commands, matchedCommands, sectionRanges;
    public int boundedCommands;
    public boolean submitted;
    public int minX, minY, minZ, maxX, maxY, maxZ;

    public record Range(int x, int y, int z, int first, int count, @Nullable MeshBounds bounds) {
        public Range(int x, int y, int z, int first, int count) { this(x,y,z,first,count,null); }
    }
    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    private static final HashMap<Key, List<Range>> METADATA = new HashMap<>();
    private static final int MAX_KEYS = 8192, MAX_RANGES = 4096;
    private static final class Key extends WeakReference<Object> {
        final int hash;
        Key(Object owner) { super(owner, QUEUE); hash = System.identityHashCode(owner); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key key && get() != null && get() == key.get();
        }
    }
    private static void drain() {
        Key key;
        while ((key = (Key) QUEUE.poll()) != null) METADATA.remove(key);
    }
    public static synchronized void attach(Object owner, List<Range> ranges) {
        drain();
        if (METADATA.size() >= MAX_KEYS) METADATA.clear();
        Key key = new Key(owner);
        METADATA.remove(key);
        if (ranges.size() <= MAX_RANGES) METADATA.put(key, List.copyOf(ranges));
    }
    public static synchronized List<Range> metadata(Object owner) {
        drain();
        return METADATA.getOrDefault(new Key(owner), List.of());
    }
    public static synchronized void append(Object owner, Range range) {
        if (range.count <= 0) return;
        var ranges = new java.util.ArrayList<>(metadata(owner));
        if (ranges.size() >= MAX_RANGES) { attach(owner, List.of()); return; }
        ranges.add(range);
        attach(owner, ranges);
    }
    public static TerrainDrawProfile start(String renderer, String layer, int commands) {
        TerrainDrawProfile event = new TerrainDrawProfile();
        event.renderer = renderer; event.layer = layer; event.commands = commands;
        event.minX = event.minY = event.minZ = Integer.MAX_VALUE;
        event.maxX = event.maxY = event.maxZ = Integer.MIN_VALUE;
        event.begin();
        return event;
    }
    public void match(Object owner, int first, int count) {
        long end = (long) first + count;
        for (Range range : metadata(owner)) {
            long overlap = Math.min(end, (long) range.first + range.count) - Math.max(first, range.first);
            if (overlap <= 0) continue;
            matchedCommands += (int) overlap;
            if (range.bounds != null) boundedCommands += (int) overlap;
            sectionRanges++;
            minX = Math.min(minX, range.x); minY = Math.min(minY, range.y); minZ = Math.min(minZ, range.z);
            maxX = Math.max(maxX, range.x); maxY = Math.max(maxY, range.y); maxZ = Math.max(maxZ, range.z);
        }
    }
    public void finish() {
        end();
        commit();
        if (submitted) TerrainFrameProfile.add(renderer, layer, commands, matchedCommands, boundedCommands, sectionRanges);
    }
}
