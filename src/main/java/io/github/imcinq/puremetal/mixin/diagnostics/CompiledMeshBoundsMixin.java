package io.github.imcinq.puremetal.mixin.diagnostics;

import com.mojang.renderpearl.api.GpuFormat;
import io.github.imcinq.puremetal.perf.LayerBounds;
import io.github.imcinq.puremetal.perf.MeshBounds;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledMeshBoundsMixin implements LayerBounds {
    @Unique private Map<Object, MeshBounds> puremetal$meshBounds;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void puremetal$measureBounds(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) SectionCompiler.Results results) {
        var measured = new HashMap<Object, MeshBounds>();
        results.renderedLayers.forEach((layer, mesh) -> {
            var state = mesh.drawState(); var format = state.format();
            var position = format.getElement("Position");
            if (position == null || position.format() != GpuFormat.RGB32_FLOAT || format.getStepRate() != 0) return;
            var bounds = MeshBounds.scan(mesh.vertexBuffer(), state.vertexCount(), format.getVertexSize(), position.offset(), false);
            if (bounds != null) measured.put(layer, bounds);
        });
        this.puremetal$meshBounds = Map.copyOf(measured);
    }
    @Override public @Nullable MeshBounds puremetal$bounds(Object layer) {
        return this.puremetal$meshBounds == null ? null : this.puremetal$meshBounds.get(layer);
    }
}
