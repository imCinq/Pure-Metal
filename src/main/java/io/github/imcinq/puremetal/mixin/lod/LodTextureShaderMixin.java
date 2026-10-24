package io.github.imcinq.puremetal.mixin.lod;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import io.github.imcinq.puremetal.backend.PureMetalBackend;
import io.github.imcinq.puremetal.lod.LodTextureRepeat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FrontendGpuDevice.class)
public abstract class LodTextureShaderMixin {
    @Shadow @Final private GpuDeviceBackend backend;
    @ModifyVariable(method = "compilePipeline", at = @At("HEAD"), argsOnly = true, require = 1)
    private ShaderSource puremetal$repeatSource(ShaderSource source) {
        return PureMetalBackend.isMetalDevice(this.backend) ? LodTextureRepeat.wrap(source) : source;
    }
}
