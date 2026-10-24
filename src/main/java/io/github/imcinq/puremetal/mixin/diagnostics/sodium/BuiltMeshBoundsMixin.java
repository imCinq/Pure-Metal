package io.github.imcinq.puremetal.mixin.diagnostics.sodium;

import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import io.github.imcinq.puremetal.perf.MeshBounds;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkBuildBuffers.class, remap = false)
public abstract class BuiltMeshBoundsMixin {
    @Shadow @Final private ChunkVertexType vertexType;
    @Inject(method = "createMesh", at = @At("RETURN"))
    private void puremetal$measureBounds(CallbackInfoReturnable<BuiltSectionMeshParts> cir) {
        var mesh = cir.getReturnValue();
        if (mesh == null || this.vertexType.getClass() != CompactChunkVertex.class) return;
        var data = mesh.getVertexData().getDirectBuffer();
        int stride = CompactChunkVertex.STRIDE;
        if (stride != 20 || data.remaining() % stride != 0) return;
        var bounds = MeshBounds.scan(data, data.remaining() / stride, stride, 0, true);
        TerrainDrawProfile.attach(mesh, List.of(new TerrainDrawProfile.Range(0,0,0,0,1,bounds)));
    }
}
