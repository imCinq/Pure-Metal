package io.github.imcinq.puremetal.lod.sodium;

import io.github.imcinq.puremetal.lod.CoarseMesher;
import io.github.imcinq.puremetal.lod.LodTextureRepeat;
import java.util.IdentityHashMap;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3fc;

/** Writes merged faces into Sodium's section buffers, the way Sodium's BlockRenderer buffers an opaque quad. */
public final class SodiumEmitter implements CoarseMesher.Emitter {
    private static final ThreadLocal<SodiumEmitter> LOCAL = ThreadLocal.withInitial(SodiumEmitter::new);
    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
    private ChunkBuildBuffers buffers;
    private final IdentityHashMap<Material, Material> repeatMaterials = new IdentityHashMap<>();

    public static SodiumEmitter of(final ChunkBuildBuffers buffers) {
        SodiumEmitter e = LOCAL.get();
        e.buffers = buffers;
        return e;
    }

    @Override
    public void quad(final BakedQuad q, final int cellX, final int cellY, final int cellZ, final int[] colors, final int[] lights) {
        int emission = q.materialInfo().lightEmission();
        for (int v = 0; v < 4; v++) {
            Vector3fc p = q.position(v);
            ChunkVertexEncoder.Vertex out = this.vertices[v];
            out.x = cellX + p.x() * 2.0F;
            out.y = cellY + p.y() * 2.0F;
            out.z = cellZ + p.z() * 2.0F;
            // Brightness (shade and AO) is already multiplied into the color, as vanilla does.
            out.color = ColorARGB.toABGR(colors[v]);
            out.ao = 1.0F;
            long uv = q.packedUV(v);
            out.u = UVPair.unpackU(uv);
            out.v = UVPair.unpackV(uv);
            out.light = LightCoordsUtil.lightCoordsWithEmission(lights[v], emission);
        }
        Material material = DefaultMaterials.forChunkLayer(q.materialInfo().layer());
        if (LodTextureRepeat.ready(true) && LodTextureRepeat.eligible(q)) {
            material = this.repeatMaterials.computeIfAbsent(material, original -> new Material(original.pass, original.alphaCutoff, original.mipped) {
                @Override public int bits() { return original.bits() | LodTextureRepeat.SODIUM_FLAG; }
            });
        }
        ChunkModelBuilder builder = this.buffers.get(material);
        builder.getVertexBuffer(ModelQuadFacing.fromDirection(q.direction())).push(this.vertices, material);
        builder.addSprite(q.materialInfo().sprite());
    }
}
