package io.github.imcinq.puremetal.mixin.diagnostics.sodium;

import io.github.imcinq.puremetal.perf.LayerBounds;
import io.github.imcinq.puremetal.perf.MeshBounds;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SectionRenderDataStorage.class, remap = false)
public abstract class StoredMeshBoundsMixin implements LayerBounds {
    @Unique private final MeshBounds[] puremetal$meshBounds = new MeshBounds[256];
    @Override public @Nullable MeshBounds puremetal$bounds(Object key) {
        int index = (Integer) key;
        return index >= 0 && index < 256 ? this.puremetal$meshBounds[index] : null;
    }
    @Override public void puremetal$setBounds(Object key, @Nullable MeshBounds bounds) {
        int index = (Integer) key;
        if (index >= 0 && index < 256) this.puremetal$meshBounds[index] = bounds;
    }
    @Inject(method = {"setVertexData", "removeVertexData", "removeData(I)V", "removeData(IZZ)V"}, at = @At("HEAD"))
    private void puremetal$invalidateBounds(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) int index) {
        this.puremetal$setBounds(index, null);
    }
    @Inject(method = "delete", at = @At("HEAD"))
    private void puremetal$deleteBounds(CallbackInfo ci) { java.util.Arrays.fill(this.puremetal$meshBounds, null); }
}
