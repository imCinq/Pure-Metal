package io.github.imcinq.puremetal.mixin.lod.sodium;

import io.github.imcinq.puremetal.lod.LodState;
import io.github.imcinq.puremetal.lod.TerrainLod;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Iterator;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium LOD transitions. Tracks sections whose mesh could differ between levels (bounded by the loaded sections, and
 * owned by this manager, so a world switch or reload drops it) and schedules non-urgent rebuilds through Sodium's own
 * budgeted task queue when one is at the wrong level. The old mesh is drawn until the new one is uploaded.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
    @Unique private final ReferenceOpenHashSet<RenderSection> puremetal$lodSections = new ReferenceOpenHashSet<>();
    @Unique private int puremetal$lodFrame;

    @Shadow
    public abstract void scheduleRebuild(int x, int y, int z, boolean important);

    @Inject(method = "updateWithResult", at = @At("HEAD"), require = 1)
    private void puremetal$trackLod(
        final Viewport viewport, final RenderSection section, final ChunkBuildOutput output, final List<RenderSection> touched,
        final CallbackInfoReturnable<Integer> cir
    ) {
        byte state = ((LodState) output).puremetal$lodState();
        LodState lod = (LodState) section;
        lod.puremetal$setLodState(state);
        lod.puremetal$setLodRequested(false);
        if (state != TerrainLod.NONE) {
            this.puremetal$lodSections.add(section);
        } else {
            this.puremetal$lodSections.remove(section);
        }
    }

    @Inject(method = "prepareFrame", at = @At("TAIL"), require = 1)
    private void puremetal$lodTransitions(final Vector3dc camera, final CallbackInfo ci) {
        boolean moved = TerrainLod.update(camera.x(), camera.z());
        // Checked when the camera chunk changes and every 20 frames (sections that finished building in between).
        if (this.puremetal$lodSections.isEmpty() || (!moved && ++this.puremetal$lodFrame < 20)) {
            return;
        }
        this.puremetal$lodFrame = 0;
        int requested = 0;
        Iterator<RenderSection> it = this.puremetal$lodSections.iterator();
        while (it.hasNext()) {
            RenderSection section = it.next();
            if (section.isDisposed()) {
                it.remove();
                continue;
            }
            LodState lod = (LodState) section;
            if (!lod.puremetal$lodRequested() && TerrainLod.needsRebuild(lod.puremetal$lodState(), section.getChunkX(), section.getChunkZ())) {
                lod.puremetal$setLodRequested(true);
                this.scheduleRebuild(section.getChunkX(), section.getChunkY(), section.getChunkZ(), false);
                if (++requested >= TerrainLod.MAX_REBUILDS_PER_FRAME) {
                    // Continue the remaining work next frame rather than waiting another 20 frames.
                    this.puremetal$lodFrame = 20;
                    break;
                }
            }
        }
    }
}
