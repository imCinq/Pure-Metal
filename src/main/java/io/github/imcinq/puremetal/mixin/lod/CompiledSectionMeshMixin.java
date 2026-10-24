package io.github.imcinq.puremetal.mixin.lod;

import io.github.imcinq.puremetal.lod.LodState;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The LOD level lives on the mesh, so it changes exactly when the new mesh replaces the old one. */
@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements LodState {
    @Unique private byte puremetal$lod;
    @Unique private volatile boolean puremetal$requested;

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/chunk/TranslucencyPointOfView;Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;J)V", at = @At("TAIL"))
    private void puremetal$copyLod(final TranslucencyPointOfView pov, final SectionCompiler.Results results, final long start, final CallbackInfo ci) {
        this.puremetal$lod = ((LodState) (Object) results).puremetal$lodState();
    }

    @Override
    public byte puremetal$lodState() {
        return this.puremetal$lod;
    }

    @Override
    public void puremetal$setLodState(final byte state) {
        this.puremetal$lod = state;
    }

    @Override
    public boolean puremetal$lodRequested() {
        return this.puremetal$requested;
    }

    @Override
    public void puremetal$setLodRequested(final boolean requested) {
        this.puremetal$requested = requested;
    }
}
