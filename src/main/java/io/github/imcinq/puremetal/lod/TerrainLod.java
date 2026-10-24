package io.github.imcinq.puremetal.lod;

import net.minecraft.client.Minecraft;
import io.github.imcinq.puremetal.backend.MetalUpscaler;

/**
 * Terrain LOD policy. The total view distance stays the player's Render Distance; from 16 chunks up, sections within
 * ceil(ratio * RD) chunks of the camera keep full detail and only the outer band is meshed coarsely (see CoarseMesher).
 *
 * <p>The camera chunk and radius are published by the main thread once per frame and read by mesher threads.
 */
public final class TerrainLod {
    public static final int MIN_RENDER_DISTANCE = 16;

    /** Built-state of a section mesh. NONE: coarse and full meshes would be identical, so it never needs a rebuild. */
    public static final byte NONE = 0;
    public static final byte FULL = 1;
    public static final byte COARSE = 2;

    /** One coherent publication for worker threads; radius -1 disables the outer band. */
    private record Policy(int cameraChunkX, int cameraChunkZ, int radius) {}
    private static volatile Policy policy = new Policy(0, 0, -1);
    public static final int MAX_REBUILDS_PER_FRAME = 32;

    private TerrainLod() {
    }

    /** Full-detail radius for a render distance, or -1 for no LOD. RD12 -> -1, RD16 -> 13, RD24 -> 20, RD32 -> 26 at 80%. */
    public static int fullRadius(final int renderDistance, final boolean enabled, final int ratioPercent) {
        if (!enabled || renderDistance < MIN_RENDER_DISTANCE) {
            return -1;
        }
        int r = (renderDistance * ratioPercent + 99) / 100;
        return r >= renderDistance ? -1 : r;
    }

    /** Main thread, once per frame. Returns true when the camera chunk or radius changed. */
    public static boolean update(final double cameraX, final double cameraZ) {
        int cx = (int) Math.floor(cameraX) >> 4;
        int cz = (int) Math.floor(cameraZ) >> 4;
        Minecraft mc = Minecraft.getInstance();
        int r = fullRadius(mc.options.getEffectiveRenderDistance(), TerrainLodConfig.enabled() && MetalUpscaler.active(), TerrainLodConfig.ratioPercent());
        Policy old = policy;
        boolean changed = cx != old.cameraChunkX || cz != old.cameraChunkZ || r != old.radius;
        if (changed) policy = new Policy(cx, cz, r);
        return changed;
    }

    /** Offline tests: sets the published camera chunk and radius directly. */
    public static void setForTest(final int chunkX, final int chunkZ, final int fullRadius) {
        policy = new Policy(chunkX, chunkZ, fullRadius);
    }

    public static int radius() {
        return policy.radius;
    }

    /** Mesher threads: should a section in chunk column (x, z) be built coarse now? */
    public static boolean coarseAt(final int chunkX, final int chunkZ) {
        Policy p = policy;
        return wantsCoarse(p.radius, chunkX - p.cameraChunkX, chunkZ - p.cameraChunkZ);
    }

    /** Main thread: does a mesh built in {@code built} state need a rebuild at this distance? */
    public static boolean needsRebuild(final byte built, final int chunkX, final int chunkZ) {
        Policy p = policy;
        return needsRebuild(built, p.radius, chunkX - p.cameraChunkX, chunkZ - p.cameraChunkZ);
    }

    public static boolean wantsCoarse(final int r, final int dx, final int dz) {
        return r >= 0 && (long) dx * dx + (long) dz * dz > (long) r * r;
    }

    /**
     * Coarse meshes come back to full detail as soon as they are inside the radius. Full meshes only go coarse one chunk
     * beyond it, so walking back and forth over a chunk border does not rebuild the same ring every time.
     */
    public static boolean needsRebuild(final byte built, final int r, final int dx, final int dz) {
        long d2 = (long) dx * dx + (long) dz * dz;
        return switch (built) {
            case COARSE -> r < 0 || d2 <= (long) r * r;
            case FULL -> r >= 0 && d2 > (long) (r + 1) * (r + 1);
            default -> false;
        };
    }
}
