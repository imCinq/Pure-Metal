import io.github.imcinq.puremetal.perf.PaletteScratch;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import net.minecraft.core.IdMapper;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

public final class TestPaletteScratch {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        boolean baseline = args.length > 0 && args[0].equals("baseline");
        check(Arrays.stream(PalettedContainer.class.getDeclaredMethods()).anyMatch(m -> m.getName().contains("puremetal$")) != baseline,
            "Incorrect palette mixin mode");
        String[] values = new String[512];
        var ids = new IdMapper<String>();
        for (int i = 0; i < values.length; i++) { values[i] = "state" + i; ids.add(values[i]); }
        for (var strategy : List.of(Strategy.createForBlockStates(ids), Strategy.createForBiomes(ids))) {
            int axis = strategy.entryCount() == 4096 ? 16 : 4;
            for (int distinct : new int[]{1, 2, 17, 300}) {
                var container = new PalettedContainer<>(values[0], strategy);
                for (int x = 0; x < axis; x++) for (int y = 0; y < axis; y++) for (int z = 0; z < axis; z++)
                    container.set(x, y, z, values[(x + y * axis + z * axis * axis) % distinct]);
                var first = container.pack(strategy);
                // Leave this lazy stream unconsumed while other pack/unpack calls overwrite all scratch ints.
                for (int repeat = 0; repeat < 8; repeat++) {
                    var copy = PalettedContainer.unpack(strategy, container.pack(strategy)).result().orElseThrow();
                    for (int x = 0; x < axis; x++) for (int y = 0; y < axis; y++) for (int z = 0; z < axis; z++)
                        check(copy.get(x, y, z) == container.get(x, y, z), "Palette round trip differs");
                }
                var retained = PalettedContainer.unpack(strategy, first).result().orElseThrow();
                for (int x = 0; x < axis; x++) for (int y = 0; y < axis; y++) for (int z = 0; z < axis; z++)
                    check(retained.get(x, y, z) == container.get(x, y, z), "Earlier serialized output was corrupted");
            }
        }
        var palette = new HashMapPalette<String>(1, List.of(values[0]));
        var storage = new ZeroBitStorage(4096);
        PaletteScratch.enter();
        int[] outer;
        try {
            outer = PaletteScratch.reencode(storage, palette, palette);
            outer[0] = 99;
            PaletteScratch.enter();
            try {
                check(PaletteScratch.reencode(storage, palette, palette) != outer, "Nested call shared scratch");
                check(outer[0] == 99, "Nested call modified outer buffer");
            } finally { PaletteScratch.exit(); }
            try (var workers = Executors.newFixedThreadPool(2)) {
                check(workers.submit(() -> {
                    PaletteScratch.enter();
                    try { return PaletteScratch.reencode(storage, palette, palette); }
                    finally { PaletteScratch.exit(); }
                }).get() != outer, "Different thread shared scratch");
            }
        } finally { PaletteScratch.exit(); }
        PaletteScratch.enter();
        try {
            check(PaletteScratch.reencode(storage, palette, palette) == outer, "Scratch was not reused");
            check(outer[0] == 0, "Scratch contains stale data");
            var smallStorage = new ZeroBitStorage(64);
            int[] small = PaletteScratch.reencode(smallStorage, palette, palette);
            check(PaletteScratch.reencode(storage, palette, palette) == outer, "Biome size evicted block scratch");
            check(PaletteScratch.reencode(smallStorage, palette, palette) == small, "Block size evicted biome scratch");
            int[] oversized = PaletteScratch.reencode(new ZeroBitStorage(8192), palette, palette);
            check(PaletteScratch.reencode(new ZeroBitStorage(8192), palette, palette) != oversized,
                "Oversized scratch must not be retained");
        } finally { PaletteScratch.exit(); }
        // Failure/early-return paths must also release the transformed unpack scope.
        var bad = new net.minecraft.world.level.chunk.PalettedContainerRO.PackedData<String>(
            List.of(values[0]), java.util.Optional.empty(), 9);
        check(PalettedContainer.unpack(Strategy.createForBlockStates(ids), bad).error().isPresent(),
            "Malformed packed data should fail normally");
        PaletteScratch.enter();
        try { check(PaletteScratch.reencode(storage, palette, palette) == outer, "Failed unpack leaked its scratch scope"); }
        finally { PaletteScratch.exit(); }
        var strategy = Strategy.createForBlockStates(ids);
        var measured = new PalettedContainer<>(values[0], strategy);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
            measured.set(x, y, z, values[(x + y * 16 + z * 256) % 300]);
        for (int i = 0; i < 200; i++) PalettedContainer.unpack(strategy, measured.pack(strategy)).result().orElseThrow();
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500; i++) {
            var copy = PalettedContainer.unpack(strategy, measured.pack(strategy)).result().orElseThrow();
            check(copy.get(0, 0, 0) == values[0], "Benchmark round trip differs");
        }
        long allocated = threads.getThreadAllocatedBytes(tid) - before;
        System.out.println("PALETTE-ALLOCATION mode=" + (baseline ? "baseline" : "scratch") + " bytes/pack-unpack=" + allocated / 500);
        var biomeStrategy = Strategy.createForBiomes(ids);
        var biome = new PalettedContainer<>(values[0], biomeStrategy);
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++)
            biome.set(x, y, z, values[(x + y * 4 + z * 16) % 17]);
        for (int i = 0; i < 200; i++) {
            PalettedContainer.unpack(strategy, measured.pack(strategy)).result().orElseThrow();
            PalettedContainer.unpack(biomeStrategy, biome.pack(biomeStrategy)).result().orElseThrow();
        }
        before = threads.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500; i++) {
            var blocks = PalettedContainer.unpack(strategy, measured.pack(strategy)).result().orElseThrow();
            var biomes = PalettedContainer.unpack(biomeStrategy, biome.pack(biomeStrategy)).result().orElseThrow();
            check(blocks.get(0, 0, 0) == values[0] && biomes.get(1, 0, 0) == values[1], "Alternating round trip differs");
        }
        allocated = threads.getThreadAllocatedBytes(tid) - before;
        System.out.println("PALETTE-ALTERNATING mode=" + (baseline ? "baseline" : "scratch") + " bytes/block-biome-pair=" + allocated / 500);
        System.out.println("PASS: transformed palette round trips, retained outputs, nesting, thread isolation and scratch reuse");
    }
}
