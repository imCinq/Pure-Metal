package io.github.imcinq.puremetal.mixin.diagnostics.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import io.github.imcinq.puremetal.perf.LayerBounds;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class UploadedMeshBoundsMixin {
    @WrapOperation(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionMeshParts;getVertexSegments()[I"))
    private int[] puremetal$segmentBounds(BuiltSectionMeshParts mesh, Operation<int[]> original) {
        int[] segments = original.call(mesh);
        TerrainDrawProfile.attach(segments, TerrainDrawProfile.metadata(mesh));
        return segments;
    }
    @WrapOperation(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;setVertexData(ILnet/caffeinemc/mods/sodium/client/gpu/arena/BufferSegment;[I)V"))
    private void puremetal$uploadedBounds(SectionRenderDataStorage storage, int index, BufferSegment allocation, int[] segments, Operation<Void> original) {
        original.call(storage, index, allocation, segments);
        var metadata = TerrainDrawProfile.metadata(segments);
        ((LayerBounds) storage).puremetal$setBounds(index, metadata.isEmpty() ? null : metadata.getFirst().bounds());
    }
}
