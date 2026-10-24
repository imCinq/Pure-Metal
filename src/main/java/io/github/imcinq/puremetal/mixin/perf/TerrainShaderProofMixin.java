package io.github.imcinq.puremetal.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import io.github.imcinq.puremetal.perf.TerrainShaderProof;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PipelineBuilder.class)
public abstract class TerrainShaderProofMixin {
    @WrapOperation(method="lambda$compilePipeline$0",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/backend/api/GpuDeviceBackend;compilePipeline(Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline$CreateInfo;)Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline$Pending;"))
    private BackendRenderPipeline.Pending puremetal$shaderProof(GpuDeviceBackend backend,BackendRenderPipeline.CreateInfo info,
        Operation<BackendRenderPipeline.Pending> original,@Local(argsOnly=true) RenderPipeline pipeline,@Local(argsOnly=true) ShaderSource source) {
        try(var proof=TerrainShaderProof.enter(pipeline,source)) {return original.call(backend,info);}
    }
}
