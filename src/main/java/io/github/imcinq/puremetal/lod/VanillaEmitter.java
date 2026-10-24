package io.github.imcinq.puremetal.lod;

import com.mojang.blaze3d.vertex.QuadInstance;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Writes merged faces through vanilla's section quad output (same layers and vertex format as normal blocks). */
public final class VanillaEmitter implements CoarseMesher.Emitter {
    private static final ThreadLocal<VanillaEmitter> LOCAL = ThreadLocal.withInitial(VanillaEmitter::new);
    private final QuadInstance instance = new QuadInstance();
    private final IdentityHashMap<BakedQuad, BakedQuad> scaledQuads = new IdentityHashMap<>();
    private BlockQuadOutput output;

    public static VanillaEmitter of(final BlockQuadOutput output) {
        VanillaEmitter e = LOCAL.get();
        if (e.output != output) {
            e.scaledQuads.clear();
        }
        e.output = output;
        return e;
    }

    /** Release section outputs and geometry references after a worker finishes the section. */
    public static void end() {
        VanillaEmitter e = LOCAL.get();
        e.output = null;
        e.scaledQuads.clear();
    }

    @Override
    public void quad(final BakedQuad q, final int cellX, final int cellY, final int cellZ, final int[] colors, final int[] lights) {
        for (int v = 0; v < 4; v++) {
            this.instance.setColor(v, colors[v]);
            this.instance.setLightCoords(v, lights[v]);
        }
        BakedQuad scaled = this.scaledQuads.get(q);
        if (scaled == null) {
            boolean repeat = LodTextureRepeat.ready(false) && LodTextureRepeat.eligible(q);
            scaled = new BakedQuad(
                doubled(q.position0()), doubled(q.position1()), doubled(q.position2()), doubled(q.position3()),
                repeat ? LodTextureRepeat.mark(q.packedUV0()) : q.packedUV0(),
                repeat ? LodTextureRepeat.mark(q.packedUV1()) : q.packedUV1(),
                repeat ? LodTextureRepeat.mark(q.packedUV2()) : q.packedUV2(),
                repeat ? LodTextureRepeat.mark(q.packedUV3()) : q.packedUV3(), q.direction(), q.materialInfo()
            );
            // Resource packs may supply many distinct faces: keep worker memory bounded.
            if (this.scaledQuads.size() >= 128) {
                this.scaledQuads.clear();
            }
            this.scaledQuads.put(q, scaled);
        }
        this.output.put(cellX, cellY, cellZ, scaled, this.instance);
    }

    private static Vector3f doubled(final Vector3fc p) {
        return new Vector3f(p).mul(2.0F);
    }
}
