package io.github.imcinq.puremetal.bench;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Opt-in per-frame roll-up of the terrain submissions {@link TerrainDrawProfile} saw since the previous frame started. Render thread
 * only. Vanilla draws SOLID terrain through DrawSeparate (one command per section) or DrawIndirect (one call per command group), told
 * apart by the separate and indirect counters. Sodium submits one batch per region and layer; its ranges count the local and the
 * shared index runs of a section separately, so they are an upper bound on the sections. Bounded commands are the ones whose mesh
 * has measured bounds. These are CPU submission counts, not hidden geometry.
 */
@Name("puremetal.TerrainFrame")
@Label("Terrain submissions per frame")
@Category({"Pure Metal", "Performance"})
@StackTrace(false)
public final class TerrainFrameProfile extends Event {
    public int separateCalls, separateCommands, indirectCalls, indirectCommands;
    public int solidCalls, solidCommands, solidMatched, solidBounded, solidSections;
    public int sodiumOpaqueBatches, sodiumOpaqueCommands, sodiumOpaqueMatched, sodiumOpaqueBounded, sodiumOpaqueRanges;
    public int sodiumCutoutBatches, sodiumCutoutCommands, sodiumCutoutRanges;
    public int sodiumTranslucentBatches, sodiumTranslucentCommands, sodiumTranslucentRanges;

    private static TerrainFrameProfile current = new TerrainFrameProfile();
    private static boolean any;

    /** One finished (submitted) draw call; the same fields {@link TerrainDrawProfile} commits. */
    public static void add(String renderer, String layer, int commands, int matched, int bounded, int ranges) {
        TerrainFrameProfile f = current;
        any = true;
        switch (renderer) {
            case "vanilla-direct", "vanilla-indirect" -> {
                boolean direct = renderer.equals("vanilla-direct");
                if (direct) { f.separateCalls++; f.separateCommands += commands; } else { f.indirectCalls++; f.indirectCommands += commands; }
                if (layer.equals("SOLID")) {
                    f.solidCalls++; f.solidCommands += commands; f.solidMatched += matched; f.solidBounded += bounded; f.solidSections += ranges;
                }
            }
            case "sodium" -> {
                switch (layer) {
                    case "opaque" -> { f.sodiumOpaqueBatches++; f.sodiumOpaqueCommands += commands; f.sodiumOpaqueMatched += matched;
                        f.sodiumOpaqueBounded += bounded; f.sodiumOpaqueRanges += ranges; }
                    case "cutout" -> { f.sodiumCutoutBatches++; f.sodiumCutoutCommands += commands; f.sodiumCutoutRanges += ranges; }
                    default -> { f.sodiumTranslucentBatches++; f.sodiumTranslucentCommands += commands; f.sodiumTranslucentRanges += ranges; }
                }
            }
            default -> { }
        }
    }

    /** At the start of a frame: commits what the previous one submitted (nothing when it drew no terrain) and starts over. */
    public static void flush() {
        if (!any) {
            return;
        }
        TerrainFrameProfile f = current;
        current = new TerrainFrameProfile();
        any = false;
        f.commit();
    }
}
