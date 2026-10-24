package io.github.imcinq.puremetal.mixin.lod.sodium;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.imcinq.puremetal.lod.CoarseMesher;
import io.github.imcinq.puremetal.lod.LodState;
import io.github.imcinq.puremetal.lod.sodium.SodiumEmitter;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium section mesher: merged cells replace their blocks' normal geometry in the outer LOD band. */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
    private static final String EXECUTE = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;";

    @Shadow @Final private ChunkRenderContext renderContext;

    @Inject(
        method = EXECUTE,
        at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;prepare(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;)V", shift = At.Shift.AFTER),
        require = 1
    )
    private void puremetal$beginLod(final ChunkBuildContext context, final CancellationToken token, final CallbackInfoReturnable<ChunkBuildOutput> cir) {
        SectionPos origin = this.renderContext.getOrigin();
        Minecraft mc = Minecraft.getInstance();
        CoarseMesher.begin(
            context.cache.getWorldSlice(), origin.x(), origin.y(), origin.z(),
            mc.options.ambientOcclusion().get(), context.cache.getBlockModels(), mc.getBlockColors()
        );
    }

    @WrapWithCondition(
        method = EXECUTE,
        at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V"),
        require = 1
    )
    private boolean puremetal$coarseBlock(
        final BlockRenderer renderer, final BlockStateModel model, final BlockState state, final BlockPos pos, final BlockPos modelOffset,
        @Local(argsOnly = true) final ChunkBuildContext context
    ) {
        CoarseMesher mesher = CoarseMesher.current();
        if (mesher != null) {
            int lx = pos.getX() & 15;
            int ly = pos.getY() & 15;
            int lz = pos.getZ() & 15;
            if (mesher.covers(lx, ly, lz)) {
                mesher.emitIfOrigin(lx, ly, lz, SodiumEmitter.of(context.buffers));
                return false;
            }
        }
        // Keep the original invocation and other mods' wrappers intact; no Operation.call varargs array.
        return true;
    }

    @Inject(method = EXECUTE, at = @At("RETURN"), require = 1)
    private void puremetal$endLod(final ChunkBuildContext context, final CancellationToken token, final CallbackInfoReturnable<ChunkBuildOutput> cir) {
        byte state = CoarseMesher.end();
        if (cir.getReturnValue() instanceof LodState lod) {
            lod.puremetal$setLodState(state);
        }
    }
}
