package io.github.imcinq.puremetal.lod;

import com.mojang.blaze3d.vertex.QuadInstance;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Coarse meshing for the outer LOD band, shared by the vanilla and Sodium section meshers.
 *
 * <p>A section is split into 2x2x2 cells. A cell is merged only when all eight blocks are opaque full cubes with plain
 * vanilla cube models (no block entity, fluid, random offset, translucency or extra geometry). Each exposed side of a
 * merged cell becomes one quad, twice the block size, using the most common block's face (texture stretched 2x). The
 * merged cell has exactly the outline of its eight blocks, so caves, overhangs and section borders keep their shape.
 * Corner light and ambient occlusion are copied from what vanilla computes for the block at that corner. Every other
 * block (partial cells, foliage, fluids, glass, custom or modded models) is meshed by the normal path at full detail.
 *
 * <p>One instance per mesher thread; begin/end bracket one section build.
 */
public final class CoarseMesher {
    /** Receives a merged face: the source quad, the cell origin (section-relative) and per-vertex color/light. */
    public interface Emitter {
        void quad(BakedQuad quad, int cellX, int cellY, int cellZ, int[] colors, int[] lights);
    }

    private static final ThreadLocal<CoarseMesher> LOCAL = ThreadLocal.withInitial(CoarseMesher::new);
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float EPS = 1.0E-4F;

    private final BlockState[] states = new BlockState[4096];
    private final long[] skip = new long[64];
    private final byte[] cellFaces = new byte[512];
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos scratch2 = new BlockPos.MutableBlockPos();
    // Kept separate from neighbor-query scratch: lighting needs the corner position throughout the call.
    private final BlockPos.MutableBlockPos cornerPos = new BlockPos.MutableBlockPos();
    private final RandomSource random = RandomSource.create(0L);
    private final List<BlockStateModelPart> parts = new ObjectArrayList<>();
    private final BlockModelLighter lighter = new BlockModelLighter();
    private final QuadInstance lightOut = new QuadInstance();
    private final Reference2BooleanOpenHashMap<BlockState> stateEligible = new Reference2BooleanOpenHashMap<>();
    private final Reference2BooleanOpenHashMap<BlockState> modelEligible = new Reference2BooleanOpenHashMap<>();
    private final int[] cornerColor = new int[4];
    private final int[] cornerLight = new int[4];
    private final boolean[] cornerSet = new boolean[4];
    private final int[] vertexColor = new int[4];
    private final int[] vertexLight = new int[4];
    private final int[] subIndex = new int[4];
    private final boolean[] subExposed = new boolean[4];

    private @Nullable BlockStateModelSet cachedModels;
    private BlockAndTintGetter level;
    private BlockStateModelSet models;
    private BlockColors colors;
    private boolean ambientOcclusion;
    private int ox;
    private int oy;
    private int oz;
    private boolean active;
    private byte result;
    private int mergedCells;

    private CoarseMesher() {
    }

    /** Starts a section build on this thread. Returns the mesher if any cell is merged, else null. */
    public static @Nullable CoarseMesher begin(
        final BlockAndTintGetter level, final int sectionX, final int sectionY, final int sectionZ,
        final boolean ambientOcclusion, final BlockStateModelSet models, final BlockColors colors
    ) {
        CoarseMesher m = LOCAL.get();
        m.active = false;
        m.result = TerrainLod.NONE;
        m.mergedCells = 0;
        if (TerrainLod.radius() < 0) {
            return null;
        }
        boolean coarse = TerrainLod.coarseAt(sectionX, sectionZ);
        m.prepare(level, sectionX << 4, sectionY << 4, sectionZ << 4, coarse, ambientOcclusion, models, colors);
        return m.active ? m : null;
    }

    /** The mesher of the section being built on this thread, or null if nothing is merged. */
    public static @Nullable CoarseMesher current() {
        CoarseMesher m = LOCAL.get();
        return m.active ? m : null;
    }

    /** Ends the section build; returns its built state (TerrainLod.NONE/FULL/COARSE). */
    public static byte end() {
        CoarseMesher m = LOCAL.get();
        m.active = false;
        m.level = null;
        return m.result;
    }

    /** Number of merged cells in the last build on this thread (tests and debugging). */
    public static int lastMergedCells() {
        return LOCAL.get().mergedCells;
    }

    /** Is this block (section-relative) part of a merged cell, i.e. must the normal mesher skip it? */
    public boolean covers(final int x, final int y, final int z) {
        int i = index(x, y, z);
        return (this.skip[i >>> 6] & (1L << i)) != 0;
    }

    /** Called for every covered block; emits the merged faces once, at the cell's lowest corner. */
    public void emitIfOrigin(final int x, final int y, final int z, final Emitter out) {
        if (((x | y | z) & 1) == 0) {
            this.emitCell(x, y, z, out);
        }
    }

    private static int index(final int x, final int y, final int z) {
        return (y << 8) | (z << 4) | x;
    }

    private void prepare(
        final BlockAndTintGetter level, final int ox, final int oy, final int oz, final boolean coarse,
        final boolean ambientOcclusion, final BlockStateModelSet models, final BlockColors colors
    ) {
        this.level = level;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.models = models;
        this.colors = colors;
        this.ambientOcclusion = ambientOcclusion;
        if (this.cachedModels != models) {
            this.stateEligible.clear();
            this.modelEligible.clear();
            this.cachedModels = models;
        }
        java.util.Arrays.fill(this.skip, 0L);
        if (coarse) {
            boolean anySolid = false;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState s = level.getBlockState(this.scratch.set(ox + x, oy + y, oz + z));
                        this.states[index(x, y, z)] = s;
                        anySolid |= s.isSolidRender();
                    }
                }
            }
            if (!anySolid) {
                return;
            }
        } else {
            // Near sections only need the first eligible exposed cell. Fetch states lazily, retaining
            // a section-local snapshot for repeated neighbor checks without pre-reading all 4096 blocks.
            java.util.Arrays.fill(this.states, null);
        }
        boolean candidate = false;
        for (int cy = 0; cy < 16; cy += 2) {
            for (int cz = 0; cz < 16; cz += 2) {
                for (int cx = 0; cx < 16; cx += 2) {
                    if (!this.cellStatesEligible(cx, cy, cz)) {
                        continue;
                    }
                    int faces = this.exposedFaces(cx, cy, cz);
                    if (faces == 0) {
                        continue;
                    }
                    candidate = true;
                    if (!this.cellModelsEligible(cx, cy, cz)) {
                        continue;
                    }
                    if (!coarse) {
                        // A full-detail build only needs to know a coarse build would differ.
                        this.result = TerrainLod.FULL;
                        return;
                    }
                    this.cellFaces[(cy << 5) | (cz << 2) | (cx >> 1)] = (byte) faces;
                    for (int i = 0; i < 8; i++) {
                        int b = index(cx + (i & 1), cy + (i >> 2), cz + ((i >> 1) & 1));
                        this.skip[b >>> 6] |= 1L << b;
                    }
                    this.mergedCells++;
                }
            }
        }
        // A coarse attempt that merged nothing equals the full mesh: NONE, so it is never rebuilt for LOD again.
        this.result = this.mergedCells > 0 ? TerrainLod.COARSE : TerrainLod.NONE;
        this.active = this.mergedCells > 0;
        if (!candidate) {
            this.result = TerrainLod.NONE;
        }
    }

    private BlockState stateAt(final int x, final int y, final int z) {
        if ((x | y | z) >= 0 && x < 16 && y < 16 && z < 16) {
            int i = index(x, y, z);
            BlockState state = this.states[i];
            if (state == null) {
                state = this.level.getBlockState(this.scratch2.set(this.ox + x, this.oy + y, this.oz + z));
                this.states[i] = state;
            }
            return state;
        }
        return this.level.getBlockState(this.scratch2.set(this.ox + x, this.oy + y, this.oz + z));
    }

    private boolean cellStatesEligible(final int cx, final int cy, final int cz) {
        for (int i = 0; i < 8; i++) {
            BlockState s = this.stateAt(cx + (i & 1), cy + (i >> 2), cz + ((i >> 1) & 1));
            if (!s.isSolidRender() || !this.isStateEligible(s)) {
                return false;
            }
        }
        return true;
    }

    private boolean isStateEligible(final BlockState s) {
        if (this.stateEligible.containsKey(s)) {
            return this.stateEligible.getBoolean(s);
        }
        boolean ok = s.getRenderShape() == RenderShape.MODEL
            && !s.hasBlockEntity()
            && s.getFluidState().isEmpty()
            && !s.hasOffsetFunction()
            && isVanillaModel(this.models.get(s));
        // A fixed vanilla model cannot become mergeable at a different position. Reject it once
        // per model set rather than testing eight copies in every exposed near/far cell.
        if (ok && this.models.get(s).getClass() == SingleVariant.class) {
            ok = this.modelEligible(s, BlockPos.ZERO);
        }
        this.stateEligible.put(s, ok);
        return ok;
    }

    /** Modded model classes may emit custom geometry (connected textures, emissive layers); keep those at full detail. */
    private static boolean isVanillaModel(final BlockStateModel model) {
        return model.getClass().getName().startsWith("net.minecraft.");
    }

    /** Bit per direction with at least one of the cell's four blocks on that side visible. */
    private int exposedFaces(final int cx, final int cy, final int cz) {
        int faces = 0;
        for (Direction d : DIRECTIONS) {
            for (int k = 0; k < 4; k++) {
                if (this.subExposed(cx, cy, cz, d, k)) {
                    faces |= 1 << d.ordinal();
                    break;
                }
            }
        }
        return faces;
    }

    /** Section-relative position of sub-block k (0..3) on side d of the cell, packed as index(). */
    private static int subBlock(final int cx, final int cy, final int cz, final Direction d, final int k) {
        int layer = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        int a = k >> 1;
        int b = k & 1;
        return switch (d.getAxis()) {
            case X -> index(cx + layer, cy + a, cz + b);
            case Y -> index(cx + a, cy + layer, cz + b);
            case Z -> index(cx + a, cy + b, cz + layer);
        };
    }

    private boolean subExposed(final int cx, final int cy, final int cz, final Direction d, final int k) {
        int i = subBlock(cx, cy, cz, d, k);
        int x = i & 15;
        int y = i >> 8;
        int z = (i >> 4) & 15;
        BlockState neighbor = this.stateAt(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
        return Block.shouldRenderFace(this.states[i], neighbor, d);
    }

    private boolean cellModelsEligible(final int cx, final int cy, final int cz) {
        for (int i = 0; i < 8; i++) {
            int x = cx + (i & 1);
            int y = cy + (i >> 2);
            int z = cz + ((i >> 1) & 1);
            if (!this.modelEligible(this.states[index(x, y, z)], this.scratch.set(this.ox + x, this.oy + y, this.oz + z))) {
                return false;
            }
        }
        return true;
    }

    /** Every part has only culled, non-translucent quads that each cover one whole face of the unit cube. */
    private boolean modelEligible(final BlockState state, final BlockPos pos) {
        // Only this exact vanilla model class has position-independent parts. Weighted/multipart/custom
        // models must still be checked at each position, including subclasses with changed behavior.
        boolean cacheable = this.models.get(state).getClass() == SingleVariant.class;
        if (cacheable && this.modelEligible.containsKey(state)) {
            return this.modelEligible.getBoolean(state);
        }
        boolean eligible = this.validateModel(state, pos);
        if (cacheable) {
            this.modelEligible.put(state, eligible);
        }
        return eligible;
    }

    private boolean validateModel(final BlockState state, final BlockPos pos) {
        this.collectParts(state, pos);
        try {
            if (this.parts.isEmpty()) {
                return false;
            }
            for (Direction d : DIRECTIONS) {
                boolean any = false;
                for (int partIndex = 0; partIndex < this.parts.size(); partIndex++) {
                    List<BakedQuad> quads = this.parts.get(partIndex).getQuads(d);
                    for (int quadIndex = 0; quadIndex < quads.size(); quadIndex++) {
                        BakedQuad q = quads.get(quadIndex);
                        if (q.direction() != d || q.materialInfo().layer().translucent() || !isFullFace(q, d)) {
                            return false;
                        }
                        any = true;
                    }
                }
                if (!any) {
                    return false;
                }
            }
            for (int partIndex = 0; partIndex < this.parts.size(); partIndex++) {
                if (!this.parts.get(partIndex).getQuads(null).isEmpty()) {
                    return false;
                }
            }
            return true;
        } finally {
            this.parts.clear();
        }
    }

    private void collectParts(final BlockState state, final BlockPos pos) {
        this.parts.clear();
        this.random.setSeed(state.getSeed(pos));
        this.models.get(state).collectParts(this.random, this.parts);
    }

    static boolean isFullFace(final BakedQuad q, final Direction d) {
        int axis = d.getAxis().ordinal();
        int t1 = axis == 0 ? 1 : 0;
        int t2 = axis == 2 ? 1 : 2;
        float plane = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        float min1 = Float.MAX_VALUE;
        float max1 = -Float.MAX_VALUE;
        float min2 = Float.MAX_VALUE;
        float max2 = -Float.MAX_VALUE;
        int corners = 0, firstCorner = -1, previousCorner = -1;
        for (int v = 0; v < 4; v++) {
            Vector3fc p = q.position(v);
            if (!(Math.abs(p.get(axis) - plane) < EPS)) {
                return false;
            }
            float a = p.get(t1), b = p.get(t2);
            if ((!nearEndpoint(a)) || (!nearEndpoint(b))) return false;
            int corner = (a > 0.5F ? 2 : 0) | (b > 0.5F ? 1 : 0);
            if (previousCorner != -1 && (previousCorner ^ corner) == 3) return false;
            if (firstCorner == -1) firstCorner = corner;
            previousCorner = corner;
            int bit = 1 << corner;
            if ((corners & bit) != 0) return false;
            corners |= bit;
            min1 = Math.min(min1, p.get(t1));
            max1 = Math.max(max1, p.get(t1));
            min2 = Math.min(min2, p.get(t2));
            max2 = Math.max(max2, p.get(t2));
        }
        return corners == 15 && (firstCorner ^ previousCorner) != 3 && Math.abs(min1) < EPS && Math.abs(max1 - 1.0F) < EPS && Math.abs(min2) < EPS && Math.abs(max2 - 1.0F) < EPS;
    }

    private static boolean nearEndpoint(float value) {
        return Math.abs(value) < EPS || Math.abs(value - 1.0F) < EPS;
    }

    private void emitCell(final int cx, final int cy, final int cz, final Emitter out) {
        int faces = this.cellFaces[(cy << 5) | (cz << 2) | (cx >> 1)];
        for (Direction d : DIRECTIONS) {
            if ((faces & (1 << d.ordinal())) != 0) {
                this.emitFace(cx, cy, cz, d, out);
            }
        }
    }

    private void emitFace(final int cx, final int cy, final int cz, final Direction d, final Emitter out) {
        int axis = d.getAxis().ordinal();
        int t1 = axis == 0 ? 1 : 0;
        int t2 = axis == 2 ? 1 : 2;
        for (int k = 0; k < 4; k++) {
            this.subIndex[k] = subBlock(cx, cy, cz, d, k);
            this.subExposed[k] = this.subExposed(cx, cy, cz, d, k);
            this.cornerSet[k] = false;
        }
        // Corner k = (a, b) of the merged face is corner (a, b) of sub-block k's own face: light it exactly like vanilla.
        for (int k = 0; k < 4; k++) {
            if (this.subExposed[k]) {
                this.lightCorner(k, d, t1, t2);
            }
        }
        for (int k = 0; k < 4; k++) {
            if (!this.cornerSet[k]) {
                int from = this.cornerSet[k ^ 2] ? k ^ 2 : this.cornerSet[k ^ 1] ? k ^ 1 : k ^ 3;
                this.cornerColor[k] = this.cornerColor[from];
                this.cornerLight[k] = this.cornerLight[from];
            }
        }

        // The face shows the most common block on that side; ties go to the upper block (grass over dirt).
        int rep = 0;
        int repCount = 0;
        for (int k = 0; k < 4; k++) {
            BlockState s = this.states[this.subIndex[k]];
            int count = 0;
            for (int j = 0; j < 4; j++) {
                if (this.states[this.subIndex[j]] == s) {
                    count++;
                }
            }
            if (count > repCount || (count == repCount && (this.subIndex[k] >> 8) > (this.subIndex[rep] >> 8))) {
                rep = k;
                repCount = count;
            }
        }
        int ri = this.subIndex[rep];
        BlockState state = this.states[ri];
        BlockPos pos = new BlockPos(this.ox + (ri & 15), this.oy + (ri >> 8), this.oz + ((ri >> 4) & 15));
        this.collectParts(state, pos);
        try {
            List<BlockTintSource> tints = null;
            int tintIndexCached = -1;
            int tintCached = -1;
            for (int partIndex = 0; partIndex < this.parts.size(); partIndex++) {
                List<BakedQuad> quads = this.parts.get(partIndex).getQuads(d);
                for (int quadIndex = 0; quadIndex < quads.size(); quadIndex++) {
                    BakedQuad q = quads.get(quadIndex);
                    int tintIndex = q.materialInfo().tintIndex();
                    int tint = -1;
                    if (tintIndex != -1) {
                        if (tintIndex != tintIndexCached) {
                            if (tints == null) {
                                tints = this.colors.getTintSources(state);
                            }
                            BlockTintSource source = tintIndex < tints.size() ? tints.get(tintIndex) : null;
                            tintCached = source != null ? source.colorInWorld(state, this.level, pos) : -1;
                            tintIndexCached = tintIndex;
                        }
                        tint = tintCached;
                    }
                    for (int v = 0; v < 4; v++) {
                        Vector3fc p = q.position(v);
                        int corner = (p.get(t1) > 0.5F ? 2 : 0) | (p.get(t2) > 0.5F ? 1 : 0);
                        this.vertexColor[v] = tint != -1 ? ARGB.multiply(this.cornerColor[corner], tint) : this.cornerColor[corner];
                        this.vertexLight[v] = this.cornerLight[corner];
                    }
                    out.quad(q, cx, cy, cz, this.vertexColor, this.vertexLight);
                }
            }
        } finally {
            this.parts.clear();
        }
    }

    private void lightCorner(final int k, final Direction d, final int t1, final int t2) {
        int i = this.subIndex[k];
        BlockState state = this.states[i];
        BlockPos pos = this.cornerPos.set(this.ox + (i & 15), this.oy + (i >> 8), this.oz + ((i >> 4) & 15));
        this.collectParts(state, pos);
        try {
            BakedQuad q = null;
            for (int partIndex = 0; partIndex < this.parts.size(); partIndex++) {
                List<BakedQuad> quads = this.parts.get(partIndex).getQuads(d);
                if (!quads.isEmpty()) {
                    q = quads.getFirst();
                    break;
                }
            }
            if (q == null) {
                return;
            }
            if (this.ambientOcclusion && state.getLightEmission() == 0 && this.parts.getFirst().useAmbientOcclusion()) {
                this.lighter.prepareQuadAmbientOcclusion(this.level, state, pos, q, this.lightOut);
            } else {
                int light = this.lighter.getLightCoords(state, this.level, this.scratch2.setWithOffset(pos, d));
                this.lighter.prepareQuadFlat(this.level, state, pos, light, q, this.lightOut);
            }
            float a = k >> 1;
            float b = k & 1;
            int vertex = 0;
            for (int v = 0; v < 4; v++) {
                Vector3fc p = q.position(v);
                if (Math.abs(p.get(t1) - a) < EPS && Math.abs(p.get(t2) - b) < EPS) {
                    vertex = v;
                    break;
                }
            }
            this.cornerColor[k] = this.lightOut.getColor(vertex);
            this.cornerLight[k] = this.lightOut.getLightCoords(vertex);
            this.cornerSet[k] = true;
        } finally {
            this.parts.clear();
        }
    }
}
