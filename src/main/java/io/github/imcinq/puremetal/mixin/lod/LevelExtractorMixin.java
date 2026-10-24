package io.github.imcinq.puremetal.mixin.lod;

import io.github.imcinq.puremetal.lod.LodState;
import io.github.imcinq.puremetal.lod.TerrainLod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla LOD transitions: just before dirty visible sections are queued for compiling, marks visible sections whose
 * mesh is at the wrong LOD level as dirty (not player-changed, so they rebuild asynchronously on vanilla's normal
 * schedule). The old mesh stays on screen until its replacement is uploaded, so a switch never leaves a hole.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow private @Nullable SectionUpdateTracker sectionUpdateTracker;
    @Unique private boolean puremetal$lodWasActive;

    @Inject(
        method = "extract",
        at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=sectionUpdates")
    )
    private void puremetal$lodTransitions(final DeltaTracker deltaTracker, final Camera camera, final float partialTicks, final CallbackInfo ci) {
        Vec3 pos = camera.position();
        TerrainLod.update(pos.x, pos.z);
        SectionUpdateTracker tracker = this.sectionUpdateTracker;
        // Keep repairing existing coarse meshes after disabling LOD. A worker may finish a coarse build
        // after the settings reload; assuming that reload made every mesh full can leave it coarse forever.
        this.puremetal$lodWasActive |= TerrainLod.radius() >= 0;
        if (tracker == null || !this.puremetal$lodWasActive) {
            return;
        }
        int requested = 0;
        for (SectionRenderDispatcher.RenderSection section : this.levelRenderer.visibleSections()) {
            SectionMesh mesh = section.getSectionMesh();
            if (!(mesh instanceof LodState lod) || lod.puremetal$lodRequested()) {
                continue;
            }
            long node = section.getSectionNode();
            int x = SectionPos.x(node);
            int z = SectionPos.z(node);
            if (TerrainLod.needsRebuild(lod.puremetal$lodState(), x, z)) {
                lod.puremetal$setLodRequested(true);
                tracker.setDirty(x, SectionPos.y(node), z, false);
                if (++requested >= TerrainLod.MAX_REBUILDS_PER_FRAME) break;
            }
        }
    }
}
