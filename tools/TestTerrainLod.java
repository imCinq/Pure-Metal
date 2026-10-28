// Offline terrain LOD checks: bootstraps Minecraft's registries headlessly (no window, no GPU) and runs the real
// CoarseMesher + VanillaEmitter on synthetic sections. Checks the policy table, hysteresis, exact coverage of the
// visible surface (no holes, only hidden extras), cell geometry, corner lighting, texture choice, ineligible
// categories, section borders and per-thread state reset.
//
// Reproduce geometry and vanilla/Sodium Fabric transformation checks:
//   python3 -I tools/test_terrain_lod.py
import com.mojang.blaze3d.vertex.QuadInstance;
import io.github.imcinq.puremetal.lod.CoarseMesher;
import io.github.imcinq.puremetal.lod.TerrainLod;
import io.github.imcinq.puremetal.lod.VanillaEmitter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class TestTerrainLod {
    static int failures;
    static int quadReads;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    // ---- synthetic models: unit cubes whose UV u encodes the block id, v the face ----
    record Part(Map<Direction, List<BakedQuad>> quads, List<BakedQuad> unculled) implements BlockStateModelPart {
        public List<BakedQuad> getQuads(Direction d) {
            quadReads++;
            return d == null ? unculled : quads.getOrDefault(d, List.of());
        }

        public boolean useAmbientOcclusion() {
            return true;
        }

        public Material.Baked particleMaterial() {
            return null;
        }

        public int materialFlags() {
            return 0;
        }
    }

    /** A model class outside net.minecraft, like a modded/custom model. */
    record CustomModel(BlockStateModelPart part) implements BlockStateModel {
        public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
            output.add(part);
        }

        public Material.Baked particleMaterial() {
            return null;
        }

        public int materialFlags() {
            return 0;
        }
    }

    static long uv(float u, float v) {
        return ((long) Float.floatToRawIntBits(u) << 32) | (Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
    }

    static BakedQuad face(Direction d, int id, float inset, ChunkSectionLayer layer, int tint) {
        float lo = inset;
        float hi = 1.0F - inset;
        float p = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        Vector3f[] v = new Vector3f[4];
        float[][] t = {{lo, lo}, {lo, hi}, {hi, hi}, {hi, lo}};
        for (int i = 0; i < 4; i++) {
            float a = t[i][0];
            float b = t[i][1];
            v[i] = switch (d.getAxis()) {
                case X -> new Vector3f(p, a, b);
                case Y -> new Vector3f(a, p, b);
                case Z -> new Vector3f(a, b, p);
            };
        }
        BakedQuad.MaterialInfo info = new BakedQuad.MaterialInfo(null, layer, null, null, null, tint, null, 0);
        float u = id;
        float vv = d.ordinal();
        return new BakedQuad(v[0], v[1], v[2], v[3], uv(u, vv), uv(u + 0.25F, vv), uv(u + 0.5F, vv), uv(u + 0.75F, vv), d, info);
    }

    static Part cube(int id, float inset, ChunkSectionLayer layer, boolean grass) {
        Map<Direction, List<BakedQuad>> q = new HashMap<>();
        for (Direction d : Direction.values()) {
            List<BakedQuad> list = new ArrayList<>();
            list.add(face(d, id, inset, layer, grass && d == Direction.UP ? 0 : -1));
            if (grass && d.getAxis() != Direction.Axis.Y) {
                list.add(face(d, id + 100, inset, ChunkSectionLayer.CUTOUT, 0)); // grass side overlay
            }
            q.put(d, list);
        }
        return new Part(q, List.of());
    }

    // ---- synthetic world ----
    static final class World implements BlockAndTintGetter {
        final Map<Long, BlockState> blocks = new HashMap<>();
        int stateReads;

        BlockState at(int x, int y, int z) {
            return blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        public BlockState getBlockState(BlockPos pos) {
            stateReads++;
            return at(pos.getX(), pos.getY(), pos.getZ());
        }

        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        public int getHeight() {
            return 384;
        }

        public int getMinY() {
            return -64;
        }

        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return 0xFF30A040;
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            int h = pos.getX() * 7 + pos.getY() * 3 + pos.getZ() * 5;
            return layer == LightLayer.SKY ? Math.floorMod(h, 16) : Math.floorMod(h >> 2, 16);
        }
    }

    record Face(int x, int y, int z, Direction d) {
    }

    static final Direction[] DIRS = Direction.values();

    /** Faces vanilla would draw for the blocks of section (0,0,0) at full detail (cube models only). */
    static Set<Face> fullFaces(World w, Map<BlockState, BlockStateModel> models) {
        Set<Face> out = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState s = w.at(x, y, z);
                    if (s.isAir() || !models.containsKey(s)) {
                        continue;
                    }
                    for (Direction d : DIRS) {
                        if (Block.shouldRenderFace(s, w.at(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()), d)) {
                            out.add(new Face(x, y, z, d));
                        }
                    }
                }
            }
        }
        return out;
    }

    record Emitted(int cx, int cy, int cz, BakedQuad quad, int[] colors, int[] lights) {
    }

    static final class Result {
        final Set<Face> faces = new HashSet<>();
        final List<Emitted> coarse = new ArrayList<>();
        int fineQuads;
        int coarseQuads;
        byte state;
        int merged;
        final Set<Long> covered = new HashSet<>();
    }

    /** Runs the coarse path the way the vanilla mixin does: covered blocks emit at their cell origin, others normally. */
    static Result build(World w, Map<BlockState, BlockStateModel> models, BlockStateModelSet set, BlockColors colors, boolean ao) {
        Result r = new Result();
        CoarseMesher m = CoarseMesher.begin(w, 0, 0, 0, ao, set, colors);
        VanillaEmitter emitter = VanillaEmitter.of((x, y, z, quad, inst) -> {
            int[] c = new int[4];
            int[] l = new int[4];
            for (int v = 0; v < 4; v++) {
                c[v] = inst.getColor(v);
                l[v] = inst.getLightCoords(v);
            }
            r.coarse.add(new Emitted((int) x, (int) y, (int) z, quad, c, l));
        });
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState s = w.at(x, y, z);
                    if (s.isAir() || !models.containsKey(s)) {
                        continue;
                    }
                    if (m != null && m.covers(x, y, z)) {
                        r.covered.add(BlockPos.asLong(x, y, z));
                        m.emitIfOrigin(x, y, z, emitter);
                        continue;
                    }
                    for (Direction d : DIRS) {
                        if (Block.shouldRenderFace(s, w.at(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()), d)) {
                            r.faces.add(new Face(x, y, z, d));
                            r.fineQuads += s.is(Blocks.GRASS_BLOCK) && d.getAxis() != Direction.Axis.Y ? 2 : 1;
                        }
                    }
                }
            }
        }
        r.state = CoarseMesher.end();
        VanillaEmitter.end();
        r.merged = CoarseMesher.lastMergedCells();
        for (Emitted e : r.coarse) {
            r.coarseQuads++;
            BakedQuad q = e.quad;
            Direction d = q.direction();
            float minX = 9, minY = 9, minZ = 9, maxX = -9, maxY = -9, maxZ = -9;
            for (int v = 0; v < 4; v++) {
                Vector3fc p = q.position(v);
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
            float plane = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 2 : 0;
            float[] mn = {minX, minY, minZ};
            float[] mx = {maxX, maxY, maxZ};
            int axis = d.getAxis().ordinal();
            boolean shapeOk = true;
            for (int i = 0; i < 3; i++) {
                if (i == axis) {
                    shapeOk &= mn[i] == plane && mx[i] == plane;
                } else {
                    shapeOk &= mn[i] == 0 && mx[i] == 2;
                }
            }
            check(shapeOk, "coarse quad spans exactly one 2x2 cell face " + e);
            check((e.cx | e.cy | e.cz) % 2 == 0, "cell origin even");
            int layer = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    int[] o = new int[3];
                    o[axis] = layer;
                    o[axis == 0 ? 1 : 0] = a;
                    o[axis == 2 ? 1 : 2] = b;
                    r.faces.add(new Face(e.cx + o[0], e.cy + o[1], e.cz + o[2], d));
                }
            }
        }
        return r;
    }

    public static void main(String[] args) throws Exception {
        checkLodSchedule();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        policyChecks();

        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState custom = Blocks.DIAMOND_ORE.defaultBlockState();
        BlockState inset = Blocks.COBBLESTONE.defaultBlockState();
        Map<BlockState, BlockStateModel> models = new HashMap<>();
        models.put(stone, new SingleVariant(cube(1, 0, ChunkSectionLayer.SOLID, false)));
        models.put(dirt, new SingleVariant(cube(2, 0, ChunkSectionLayer.SOLID, false)));
        models.put(grass, new SingleVariant(cube(3, 0, ChunkSectionLayer.SOLID, true)));
        models.put(glass, new SingleVariant(cube(4, 0, ChunkSectionLayer.CUTOUT, false)));
        models.put(leaves, new SingleVariant(cube(5, 0, ChunkSectionLayer.CUTOUT, false)));
        models.put(custom, new CustomModel(cube(6, 0, ChunkSectionLayer.SOLID, false)));
        models.put(inset, new SingleVariant(cube(7, 0.0625F, ChunkSectionLayer.SOLID, false)));
        BlockStateModelSet set = new BlockStateModelSet(models, new SingleVariant(cube(9, 0, ChunkSectionLayer.SOLID, false)));
        BlockColors colors = BlockColors.createDefault();
        check(stone.isSolidRender() && grass.isSolidRender() && custom.isSolidRender() && inset.isSolidRender(), "test blocks are solid");
        check(!glass.isSolidRender() && !leaves.isSolidRender(), "glass/leaves are not solid");

        // 1) Off / inside the full-detail radius: nothing merged.
        World flat = new World();
        for (int x = -16; x < 32; x++) {
            for (int z = -16; z < 32; z++) {
                for (int y = -16; y <= 7; y++) {
                    flat.set(x, y, z, y == 7 ? grass : y >= 5 ? dirt : stone);
                }
            }
        }
        TerrainLod.setForTest(0, 0, -1);
        Result off = build(flat, models, set, colors, true);
        check(off.coarse.isEmpty() && off.state == TerrainLod.NONE, "LOD off: no merging, state NONE");
        TerrainLod.setForTest(0, 0, 13);
        flat.stateReads = 0;
        Result near = build(flat, models, set, colors, true);
        check(flat.stateReads < 4096, "near eligible terrain avoids full section prefetch: " + flat.stateReads);
        System.out.println("near eligibility block reads: " + flat.stateReads + " (previous prefetch alone: 4096)");
        check(near.coarse.isEmpty() && near.state == TerrainLod.FULL, "inside radius: full detail, state FULL (candidates)");
        check(near.faces.equals(fullFaces(flat, models)), "inside radius: same faces as full detail");

        // 2) Flat terrain in the outer band (camera 14 chunks away).
        TerrainLod.setForTest(-14, 0, 13);
        Result fr = build(flat, models, set, colors, true);
        check(fr.state == TerrainLod.COARSE && fr.merged > 0, "outer band: merged, state COARSE");
        coverage("flat", flat, models, fr);
        cacheChecks(flat, models, set, colors, stone);
        // Top surface (y=7 grass) is in cell y 6..7 whose top layer is grass: top faces must use the grass texture (id 3).
        boolean grassTop = false;
        for (Emitted e : fr.coarse) {
            if (e.quad.direction() == Direction.UP) {
                float u = Float.intBitsToFloat((int) (e.quad.packedUV0() >>> 32));
                check(u == 3.0F, "flat top merged face uses grass texture, got u=" + u);
                grassTop = true;
                // tinted: green channel dominates after multiplying by the 0x30A040 biome tint
                int c = e.colors[0];
                check(((c >> 8) & 0xFF) > ((c >> 16) & 0xFF), "grass top is tinted");
            }
        }
        check(grassTop, "flat terrain produced merged grass tops");
        System.out.printf("flat surface: full %d quads -> lod %d fine + %d merged (%d cells)%n",
            fullQuadCount(flat, models), fr.fineQuads, fr.coarseQuads, fr.merged);

        // 3) Noisy terrain with caves, overhangs, glass, leaves, custom and inset models and random section borders.
        Random rnd = new Random(42);
        World noisy = new World();
        BlockState[] solids = {stone, stone, stone, dirt, custom, inset};
        for (int x = -16; x < 32; x++) {
            for (int z = -16; z < 32; z++) {
                int h = 8 + (int) Math.round(4 * Math.sin(x * 0.4) * Math.cos(z * 0.3)) + rnd.nextInt(2);
                for (int y = -16; y < 32; y++) {
                    BlockState s;
                    if (y > h) {
                        s = y == h + 1 && rnd.nextInt(9) == 0 ? leaves : Blocks.AIR.defaultBlockState();
                    } else if (y == h) {
                        s = grass;
                    } else if (rnd.nextInt(14) == 0) {
                        s = Blocks.AIR.defaultBlockState(); // cave voids
                    } else if (rnd.nextInt(30) == 0) {
                        s = glass;
                    } else {
                        s = solids[rnd.nextInt(solids.length)];
                    }
                    noisy.set(x, y, z, s);
                }
            }
        }
        // An overhang: a solid slab of stone over air.
        for (int x = 2; x < 10; x++) {
            for (int z = 2; z < 10; z++) {
                for (int y = 0; y < 4; y++) {
                    noisy.set(x, y, z, Blocks.AIR.defaultBlockState());
                }
                for (int y = 4; y < 6; y++) {
                    noisy.set(x, y, z, stone);
                }
            }
        }
        Result nr = build(noisy, models, set, colors, true);
        coverage("noisy", noisy, models, nr);
        for (long p : nr.covered) {
            BlockState s = noisy.at(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
            check(s == stone || s == dirt || s == grass, "only plain cube blocks are merged, got " + s);
        }
        System.out.printf("noisy terrain: full %d quads -> lod %d fine + %d merged (%d cells)%n",
            fullQuadCount(noisy, models), nr.fineQuads, nr.coarseQuads, nr.merged);

        // 4) Corner lighting, flat shading (no AO): each corner takes the light outside its own block.
        Result lit = build(flat, models, set, colors, false);
        BlockModelLighter lighter = new BlockModelLighter();
        int checkedCorners = 0;
        for (Emitted e : lit.coarse) {
            Direction d = e.quad.direction();
            int axis = d.getAxis().ordinal();
            int layer = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            for (int v = 0; v < 4; v++) {
                Vector3fc p = e.quad.position(v);
                int[] o = new int[3];
                o[axis] = layer;
                for (int i = 0; i < 3; i++) {
                    if (i != axis) {
                        o[i] = p.get(i) > 1.0F ? 1 : 0;
                    }
                }
                BlockPos sub = new BlockPos(e.cx + o[0], e.cy + o[1], e.cz + o[2]);
                BlockState s = flat.getBlockState(sub);
                if (!Block.shouldRenderFace(s, flat.getBlockState(sub.relative(d)), d)) {
                    continue;
                }
                int expected = lighter.getLightCoords(s, flat, sub.relative(d));
                check(e.lights[v] == expected, "corner light matches the block outside that corner");
                checkedCorners++;
            }
        }
        check(checkedCorners > 0, "corner light checked");

        // 5) Per-thread state resets: a coarse build followed by an all-air section and an inside-radius build.
        World empty = new World();
        Result er = build(empty, models, set, colors, true);
        check(er.coarse.isEmpty() && er.covered.isEmpty() && er.state == TerrainLod.NONE, "empty section after a coarse build");
        TerrainLod.setForTest(0, 0, 13);
        Result again = build(noisy, models, set, colors, true);
        check(again.covered.isEmpty() && again.faces.equals(fullFaces(noisy, models)), "full detail again after coarse");

        // Resource packs may have a diamond or crossed quad with the same bounding box as a full face.
        // Those are not cube faces and must not be stretched into a coarse cell.
        for (boolean crossed : new boolean[]{false, true}) {
            Map<Direction, List<BakedQuad>> faces = new HashMap<>();
            for (Direction d : DIRS) {
                BakedQuad q = face(d, 12, 0, ChunkSectionLayer.SOLID, -1);
                Vector3fc[] v = {q.position0(), q.position1(), q.position2(), q.position3()};
                if (crossed) {
                    Vector3fc swap = v[1]; v[1] = v[2]; v[2] = swap;
                } else {
                    int a = d.getAxis() == Direction.Axis.X ? 1 : 0;
                    int b = d.getAxis() == Direction.Axis.Z ? 1 : 2;
                    float[][] corners = {{0,0.5F},{0.5F,1},{1,0.5F},{0.5F,0}};
                    for (int i=0;i<4;i++) {
                        Vector3f p = new Vector3f(v[i]);
                        p.setComponent(a, corners[i][0]); p.setComponent(b, corners[i][1]); v[i]=p;
                    }
                }
                faces.put(d, List.of(new BakedQuad(v[0],v[1],v[2],v[3],q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),d,q.materialInfo())));
            }
            Map<BlockState, BlockStateModel> malformed = new HashMap<>(models);
            malformed.put(stone, new SingleVariant(new Part(faces, List.of())));
            World cubeWorld = new World();
            for (int x=0;x<2;x++) for (int y=0;y<2;y++) for (int z=0;z<2;z++) cubeWorld.set(x,y,z,stone);
            TerrainLod.setForTest(-14,0,13);
            Result rejected = build(cubeWorld, malformed, new BlockStateModelSet(malformed, malformed.get(stone)), colors, true);
            check(rejected.coarse.isEmpty(), (crossed ? "crossed" : "diamond") + " resource-pack faces stay full detail");
        }

        // 6) Section of only custom/inset models in the band: nothing merged, state NONE (never rebuilt for LOD again).
        World mod = new World();
        for (int x = -1; x < 17; x++) {
            for (int z = -1; z < 17; z++) {
                for (int y = -1; y < 8; y++) {
                    mod.set(x, y, z, (x + z) % 2 == 0 ? custom : inset);
                }
            }
        }
        TerrainLod.setForTest(-14, 0, 13);
        Result mr = build(mod, models, set, colors, true);
        check(mr.coarse.isEmpty() && mr.state == TerrainLod.NONE, "custom/inset models stay full detail, state NONE");
        TerrainLod.setForTest(0, 0, 13);
        Result nearRejected = build(mod, models, set, colors, true);
        check(nearRejected.coarse.isEmpty() && nearRejected.state == TerrainLod.NONE,
            "near ineligible models do not request an unnecessary coarse rebuild");
        check(nearRejected.faces.equals(fullFaces(mod, models)), "near model rejection preserves detailed faces");
        World vines = new World();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            vines.set(x, y, z, Blocks.VINE.defaultBlockState());
        }
        for (int camera : new int[]{0, -14}) {
            TerrainLod.setForTest(camera, 0, 13);
            Result vineResult = build(vines, models, set, colors, true);
            check(vineResult.covered.isEmpty() && vineResult.state == TerrainLod.NONE,
                "vines retain detailed geometry and do not enter solid-cell LOD");
        }

        System.out.println(failures == 0 ? "ALL TERRAIN LOD CHECKS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void checkLodSchedule() {
        var schedule = new io.github.imcinq.puremetal.lod.LodScanSchedule();
        schedule.meshArrived();
        check(schedule.shouldScan(true, true), "movement scans immediately");
        int stationary = 0;
        for (int i = 0; i < 1000; i++) if (schedule.shouldScan(false, true)) stationary++;
        check(stationary == 0, "unchanged stationary meshes avoid 50 periodic scans");
        for (int i = 0; i < 19; i++) check(!schedule.shouldScan(false, true), "idle periodic phase retained");
        schedule.meshArrived();
        check(schedule.shouldScan(false, true), "newly completed mesh uses existing periodic phase");
        schedule.continueNextFrame();
        check(schedule.shouldScan(false, true), "rebuild budget exhaustion continues next frame");
        check(!schedule.shouldScan(false, true), "completed scan does not keep scanning");
        check(schedule.shouldScan(true, true), "new camera policy rescans unchanged meshes immediately");
        check(!schedule.shouldScan(true, false), "empty manager skips scans");
        schedule.meshArrived();
        check(schedule.shouldScan(true, true), "world/section arrival resumes scanning");
    }

    static int fullQuadCount(World w, Map<BlockState, BlockStateModel> models) {
        int n = 0;
        for (Face f : fullFaces(w, models)) {
            n += w.at(f.x, f.y, f.z).is(Blocks.GRASS_BLOCK) && f.d.getAxis() != Direction.Axis.Y ? 2 : 1;
        }
        return n;
    }

    /** Every visible face is covered; extra merged faces lie against an opaque full block (never visible). */
    static void coverage(String name, World w, Map<BlockState, BlockStateModel> models, Result r) {
        Set<Face> full = fullFaces(w, models);
        int missing = 0;
        for (Face f : full) {
            if (!r.faces.contains(f)) {
                missing++;
            }
        }
        check(missing == 0, name + ": " + missing + " visible faces missing (holes)");
        int visibleExtra = 0;
        for (Face f : r.faces) {
            if (!full.contains(f)) {
                BlockState n = w.at(f.x + f.d.getStepX(), f.y + f.d.getStepY(), f.z + f.d.getStepZ());
                if (!n.isSolidRender()) {
                    visibleExtra++;
                }
            }
        }
        check(visibleExtra == 0, name + ": " + visibleExtra + " extra faces would be visible");
    }

    static void cacheChecks(World world, Map<BlockState, BlockStateModel> models, BlockStateModelSet set,
                            BlockColors colors, BlockState stone) throws Exception {
        var local = CoarseMesher.class.getDeclaredField("LOCAL");
        local.setAccessible(true);
        Object mesher = ((ThreadLocal<?>) local.get(null)).get();
        var validate = CoarseMesher.class.getDeclaredMethod("modelEligible", BlockState.class, BlockPos.class);
        validate.setAccessible(true);
        CoarseMesher.begin(world, 0, 0, 0, true, set, colors);
        check((boolean) validate.invoke(mesher, stone, BlockPos.ZERO), "fixed cube is eligible");
        int reads = quadReads;
        check((boolean) validate.invoke(mesher, stone, new BlockPos(1, 2, 3)), "cached cube remains eligible");
        check(quadReads == reads, "fixed cube validation reuses cached result");
        CoarseMesher.end();

        BlockStateModelPart cube = cube(1, 0, ChunkSectionLayer.SOLID, false);
        BlockStateModelPart inset = cube(1, 0.0625F, ChunkSectionLayer.SOLID, false);
        BlockStateModel alternating = new SingleVariant(cube) {
            int calls;
            @Override public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
                output.add((calls++ & 1) == 0 ? cube : inset);
            }
        };
        Map<BlockState, BlockStateModel> changed = new HashMap<>(models);
        changed.put(stone, alternating);
        var changedSet = new BlockStateModelSet(changed, alternating);
        CoarseMesher.begin(world, 0, 0, 0, true, changedSet, colors);
        boolean first = (boolean) validate.invoke(mesher, stone, BlockPos.ZERO);
        boolean second = (boolean) validate.invoke(mesher, stone, new BlockPos(1, 2, 3));
        check(first != second, "variant subclasses are validated every time");
        CoarseMesher.end();
        changed.put(stone, new SingleVariant(inset));
        changedSet = new BlockStateModelSet(changed, changed.get(stone));
        CoarseMesher.begin(world, 0, 0, 0, true, changedSet, colors);
        check(!(boolean) validate.invoke(mesher, stone, BlockPos.ZERO), "model reload discards cube eligibility");
        reads = quadReads;
        validate.invoke(mesher, stone, BlockPos.ZERO);
        check(quadReads == reads, "ineligible fixed models are also cached");
        CoarseMesher.end();

        BakedQuad source = cube.getQuads(Direction.UP).getFirst();
        List<BakedQuad> emitted = new ArrayList<>();
        var emitter = VanillaEmitter.of((x, y, z, q, inst) -> emitted.add(q));
        int[] values = {1, 2, 3, 4};
        emitter.quad(source, 0, 0, 0, values, values);
        emitter.quad(source, 2, 0, 0, values, values);
        check(emitted.get(0) == emitted.get(1), "same section reuses scaled quad");
        VanillaEmitter.end();
        emitter = VanillaEmitter.of((x, y, z, q, inst) -> emitted.add(q));
        emitter.quad(source, 0, 0, 0, values, values);
        check(emitted.get(0) != emitted.get(2), "scaled cache is released between sections");
        VanillaEmitter.end();
    }

    static void policyChecks() {
        int[][] table = {{8, -1}, {12, -1}, {15, -1}, {16, 13}, {20, 16}, {24, 20}, {32, 26}};
        for (int[] row : table) {
            check(TerrainLod.fullRadius(row[0], true, 80) == row[1], "fullRadius RD" + row[0] + " -> " + row[1]);
        }
        check(TerrainLod.fullRadius(32, false, 80) == -1, "disabled -> no LOD");
        check(TerrainLod.fullRadius(16, true, 95) == -1, "95% of 16 rounds up to RD: no band");
        check(TerrainLod.wantsCoarse(13, 100_000, 100_000), "large teleport distances do not overflow");
        check(TerrainLod.needsRebuild(TerrainLod.COARSE, -1, 14, 0), "coarse mesh repairs after disabling LOD");
        check(TerrainLod.needsRebuild(TerrainLod.COARSE, 20, 14, 0), "increasing full radius restores detailed mesh");
        // Walk the camera back and forth across chunk borders around a section 14 chunks away (RD16, R=13).
        int r = 13;
        byte built = TerrainLod.wantsCoarse(r, 14, 0) ? TerrainLod.COARSE : TerrainLod.FULL;
        int rebuilds = 0;
        int[] path = {0, 1, 0, 1, 0, 1, 0, -1, 0, -1, 0, -1, -2, -1, 0, 1};
        for (int cam : path) {
            int dx = 14 - cam;
            if (TerrainLod.needsRebuild(built, r, dx, 0)) {
                built = TerrainLod.wantsCoarse(r, dx, 0) ? TerrainLod.COARSE : TerrainLod.FULL;
                rebuilds++;
            }
            check(!(built == TerrainLod.COARSE && dx * dx <= r * r), "never coarse inside the full-detail radius");
        }
        check(rebuilds <= 4, "hysteresis bounds level switching, rebuilds=" + rebuilds);
        System.out.println("policy: RD12->" + TerrainLod.fullRadius(12, true, 80) + " RD16->" + TerrainLod.fullRadius(16, true, 80)
            + " RD24->" + TerrainLod.fullRadius(24, true, 80) + " RD32->" + TerrainLod.fullRadius(32, true, 80)
            + "; back-and-forth walk rebuilds=" + rebuilds);
    }
}
