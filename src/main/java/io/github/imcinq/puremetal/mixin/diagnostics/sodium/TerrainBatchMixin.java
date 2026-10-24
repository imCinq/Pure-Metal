package io.github.imcinq.puremetal.mixin.diagnostics.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import io.github.imcinq.puremetal.perf.LayerBounds;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import java.util.List;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class TerrainBatchMixin {
    @Inject(method = "fillCommandBuffer", at = @At("HEAD"))
    private static void puremetal$resetBatch(CallbackInfo ci, @Local(argsOnly = true) MultiDrawBatch batch) {
        TerrainDrawProfile.attach(batch, List.of());
    }

    @WrapOperation(method = "fillCommandBuffer", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;addLocalIndexedDrawCommands(Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;JI)V"))
    private static void puremetal$localRanges(MultiDrawBatch batch, long data, int mask, Operation<Void> original,
        @Local(index = 15) int x, @Local(index = 16) int y, @Local(index = 17) int z,
        @Local(argsOnly = true) SectionRenderDataStorage storage, @Local(index = 12) int sectionIndex) {
        int first = batch.size;
        original.call(batch, data, mask);
        var bounds = storage instanceof LayerBounds measured ? measured.puremetal$bounds(sectionIndex) : null;
        TerrainDrawProfile.append(batch, new TerrainDrawProfile.Range(x * 16, y * 16, z * 16, first, batch.size - first, bounds));
    }

    @WrapOperation(method = "fillCommandBuffer", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;addSharedIndexedDrawCommands(Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;JI)V"))
    private static void puremetal$sharedRanges(MultiDrawBatch batch, long data, int mask, Operation<Void> original,
        @Local(index = 15) int x, @Local(index = 16) int y, @Local(index = 17) int z,
        @Local(argsOnly = true) SectionRenderDataStorage storage, @Local(index = 12) int sectionIndex) {
        int first = batch.size;
        original.call(batch, data, mask);
        var bounds = storage instanceof LayerBounds measured ? measured.puremetal$bounds(sectionIndex) : null;
        TerrainDrawProfile.append(batch, new TerrainDrawProfile.Range(x * 16, y * 16, z * 16, first, batch.size - first, bounds));
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;draw(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V"))
    private void puremetal$batchSubmission(MultiDrawBatch batch, DrawContext context, Operation<Void> original,
        @Local(argsOnly = true) TerrainRenderPass layer,
        @Local(index = 15) net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion region) {
        var event = TerrainDrawProfile.start("sodium", layer.isTranslucent() ? "translucent" : layer.supportsFragmentDiscard() ? "cutout" : "opaque", batch.size);
        event.match(batch, 0, batch.size);
        try (var scope=io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED && !layer.isTranslucent() && !layer.supportsFragmentDiscard()
            ? io.github.imcinq.puremetal.perf.TerrainProbeScope.batch(batch,0,batch.size,true,region.getOriginX(),region.getOriginY(),region.getOriginZ()) : null) {
            original.call(batch, context); event.submitted = true;
        }
        finally { event.finish(); }
    }
}
