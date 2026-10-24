package io.github.imcinq.puremetal.mixin.diagnostics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import java.util.Collection;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawSeparate")
public abstract class TerrainDirectDrawMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V"))
    private void puremetal$directSubmission(RenderPass pass, Collection<?> draws, GpuBuffer index, IndexType type,
        Collection<String> uniforms, Object userData, Operation<Void> original, @Local(argsOnly = true) ChunkSectionLayer layer) {
        var event = TerrainDrawProfile.start("vanilla-direct", layer.name(), draws.size());
        for (Object draw : draws) event.match(draw, 0, 1);
        try (var scope = io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED && layer.name().equals("SOLID")
            ? io.github.imcinq.puremetal.perf.TerrainProbeScope.direct(draws) : null) {
            original.call(pass, draws, index, type, uniforms, userData); event.submitted = true;
        }
        finally { event.finish(); }
    }
}
