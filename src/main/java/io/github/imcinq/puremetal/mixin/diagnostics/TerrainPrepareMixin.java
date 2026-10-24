package io.github.imcinq.puremetal.mixin.diagnostics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import io.github.imcinq.puremetal.perf.LayerBounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class TerrainPrepareMixin {
    @WrapOperation(method = "extractSectionDrawGroups", at = @At(value = "NEW", target = "net/minecraft/client/renderer/DynamicGpuData$IndexedDraw"))
    private DynamicGpuData.IndexedDraw puremetal$meshMetadata(int count, int instances, int first, int baseVertex, int sectionIndex,
        Operation<DynamicGpuData.IndexedDraw> original, @Local(index = 13) SectionMesh mesh, @Local(index = 19) ChunkSectionLayer layer) {
        var draw = original.call(count, instances, first, baseVertex, sectionIndex);
        var bounds = mesh instanceof net.minecraft.client.renderer.chunk.CompiledSectionMesh compiled
            && mesh instanceof LayerBounds measured && compiled.isVertexBufferUploaded(layer) ? measured.puremetal$bounds(layer) : null;
        TerrainDrawProfile.attach(draw, List.of(new TerrainDrawProfile.Range(0,0,0,0,1,bounds)));
        return draw;
    }
    @WrapOperation(method = "prepareChunkRenders", at = @At(value = "NEW", target = "com/mojang/renderpearl/api/commands/RenderPass$Draw"))
    private RenderPass.Draw<GpuBufferSlice[]> puremetal$directMetadata(int slot, GpuBuffer vertex, GpuBuffer index,
        IndexType type, int firstIndex, int count, int baseVertex, BiConsumer<String, GpuBufferSlice[]> uploader,
        Operation<RenderPass.Draw<GpuBufferSlice[]>> original,
        @Local(index = 4) List<DynamicGpuData.ChunkSectionInfo> infos, @Local(index = 21) int sectionIndex,
        @Local(index = 20) DynamicGpuData.IndexedDraw source) {
        var draw = original.call(slot, vertex, index, type, firstIndex, count, baseVertex, uploader);
        if (sectionIndex >= 0 && sectionIndex < infos.size()) {
            var info = infos.get(sectionIndex);
            var metadata = TerrainDrawProfile.metadata(source);
            var bounds = metadata.isEmpty() ? null : metadata.getFirst().bounds();
            TerrainDrawProfile.attach(draw, List.of(new TerrainDrawProfile.Range(info.x(), info.y(), info.z(), 0, 1, bounds)));
        }
        return draw;
    }

    @WrapOperation(method = "prepareChunkRendersIndirect", at = @At(value = "NEW", target = "net/minecraft/client/renderer/chunk/ChunkSectionsToRender$GpuMultiDrawIndexedIndirect"))
    private ChunkSectionsToRender.GpuMultiDrawIndexedIndirect puremetal$indirectMetadata(
        GpuBufferSlice vertex, GpuBufferSlice index, IndexType type, GpuBufferSlice commands, int count,
        Operation<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> original,
        @Local(index = 4) List<DynamicGpuData.ChunkSectionInfo> infos,
        @Local(index = 10) List<List<DynamicGpuData.IndexedDraw>> commandLists, @Local(index = 12) int nextCommandList) {
        var group = original.call(vertex, index, type, commands, count);
        if (count > 4096) return group;
        // Match the actual ordered list written to this command buffer, including reversed translucent order.
        if (nextCommandList < 1 || nextCommandList > commandLists.size()) return group;
        var draws = commandLists.get(nextCommandList - 1);
        var ranges = new ArrayList<TerrainDrawProfile.Range>();
        for (int i = 0; i < draws.size(); i++) {
            int sectionIndex = draws.get(i).baseInstance();
            if (sectionIndex < 0 || sectionIndex >= infos.size()) continue;
            var info = infos.get(sectionIndex);
            var metadata = TerrainDrawProfile.metadata(draws.get(i));
            var bounds = metadata.isEmpty() ? null : metadata.getFirst().bounds();
            ranges.add(new TerrainDrawProfile.Range(info.x(), info.y(), info.z(), i, 1, bounds));
        }
        TerrainDrawProfile.attach(group, ranges);
        return group;
    }
}
