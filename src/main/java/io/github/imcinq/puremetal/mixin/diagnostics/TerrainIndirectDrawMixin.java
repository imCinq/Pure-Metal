package io.github.imcinq.puremetal.mixin.diagnostics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawIndirect")
public abstract class TerrainIndirectDrawMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;drawIndexedIndirect(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)V"))
    private void puremetal$indirectSubmission(RenderPass pass, GpuBufferSlice commands, int count, Operation<Void> original,
        @Local(argsOnly = true) ChunkSectionLayer layer,
        @Local(index = 11) ChunkSectionsToRender.GpuMultiDrawIndexedIndirect group, @Local(index = 16) int remaining) {
        var event = TerrainDrawProfile.start("vanilla-indirect", layer.name(), count);
        event.match(group, group.drawCount() - remaining, count);
        try (var scope = io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED && layer.name().equals("SOLID")
            ? io.github.imcinq.puremetal.perf.TerrainProbeScope.batch(group,group.drawCount()-remaining,count,false,0,0,0) : null) {
            original.call(pass, commands, count); event.submitted = true;
        }
        finally { event.finish(); }
    }
}
