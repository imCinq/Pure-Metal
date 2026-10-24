package io.github.imcinq.puremetal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import io.github.imcinq.puremetal.scale.RenderScale;
import io.github.imcinq.puremetal.scale.TemporalUpscaler;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render scale: the world is drawn into smaller textures and upscaled before the HUD (see RenderScale). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;
    @Shadow
    @Final
    private RenderTarget mainRenderTarget;
    @Shadow
    @Final
    private RenderTarget hud3DTarget;
    @Shadow
    @Final
    private GameRenderState gameRenderState;
    @Shadow
    @Final
    private ProjectionMatrixBuffer levelProjectionMatrixBuffer;
    /** Re-sends the frame's global settings with the full window size once the world is done. */
    @Unique
    private @Nullable Runnable puremetal$fullSizeSettings;

    @Shadow
    @Final
    private List<?> appliedPostEffects;

    @Shadow
    public abstract void resize(int width, int height);

    @Inject(method = "resize", at = @At("TAIL"))
    private void puremetal$resizeWorldTargets(final int width, final int height, final CallbackInfo ci) {
        RenderScale.resized(this.mainRenderTarget, this.hud3DTarget, this.minecraft.levelRenderer, width, height,
            this.minecraft.level != null || this.gameRenderState.shouldRenderLevel);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void puremetal$applyScaleChange(final CallbackInfo ci) {
        // Preserve resources for a final extracted world frame even if the live level has already been cleared.
        RenderScale.prepareFrame(this.mainRenderTarget, this.minecraft.level != null || this.gameRenderState.shouldRenderLevel);
        if (RenderScale.takeDirty()) {
            this.resize(this.mainRenderTarget.width, this.mainRenderTarget.height);
        }
    }

    // When the world is scaled, the full-size target's clear is wasted: the upscale overwrites all of its color and the
    // HUD clears its depth again. The smaller world textures are cleared instead (RenderScale.begin).
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
    private void puremetal$skipFullSizeClear(
        final CommandEncoder encoder, final GpuTexture color, final Vector4fc clearColor, final GpuTexture depth, final double clearDepth,
        final Operation<Void> original
    ) {
        if (!this.gameRenderState.shouldRenderLevel || !RenderScale.ready(this.mainRenderTarget)) {
            original.call(encoder, color, clearColor, depth, clearDepth);
        }
    }

    // Shaders that read ScreenSize while drawing the world must see the size it is drawn at.
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"))
    private void puremetal$scaledScreenSize(
        final GlobalSettingsUniform uniform, final int width, final int height, final double glintAlpha, final long gameTime,
        final float worldPartialTicks, final int menuBlurRadius, final Vec3 cameraPos, final boolean useRgss, final Operation<Void> original
    ) {
        if (this.gameRenderState.shouldRenderLevel && RenderScale.ready(this.mainRenderTarget)) {
            original.call(uniform, RenderScale.scaled(width), RenderScale.scaled(height), glintAlpha, gameTime, worldPartialTicks, menuBlurRadius, cameraPos, useRgss);
            this.puremetal$fullSizeSettings = () -> original.call(uniform, width, height, glintAlpha, gameTime, worldPartialTicks, menuBlurRadius, cameraPos, useRgss);
        } else {
            original.call(uniform, width, height, glintAlpha, gameTime, worldPartialTicks, menuBlurRadius, cameraPos, useRgss);
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void puremetal$beginScaledWorld(final CallbackInfo ci) {
        RenderScale.begin(this.mainRenderTarget, this.gameRenderState.guiRenderState.clearColorOverride);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void puremetal$endScaledWorld(final CallbackInfo ci) {
        RenderScale.end(this.mainRenderTarget);
        if (this.puremetal$fullSizeSettings != null) {
            this.puremetal$fullSizeSettings.run();
            this.puremetal$fullSizeSettings = null;
        }
    }

    // MetalFX temporal: the world's projection is jittered by a sub-pixel amount each frame. These three injections are
    // optional (require = 0); if one does not apply or does not run, Temporal turns itself off (TemporalUpscaler.hooksIntact).
    // Only the level's own projection buffer is touched (renderLevel has a single such call in vanilla).
    @WrapOperation(method = "renderLevel", require = 0, at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice puremetal$jitterWorldProjection(final ProjectionMatrixBuffer buffer, final Matrix4f projection, final Operation<GpuBufferSlice> original) {
        if (buffer != this.levelProjectionMatrixBuffer) {
            return original.call(buffer, projection);
        }
        TemporalUpscaler.hookRan(TemporalUpscaler.HOOK_LEVEL);
        if (!RenderScale.temporalFrame()) {
            return original.call(buffer, projection);
        }
        CameraRenderState camera = this.gameRenderState.levelRenderState.cameraRenderState;
        return original.call(buffer, TemporalUpscaler.levelProjection(projection, camera));
    }

    // The hand clears the depth, so the motion vectors are derived from it just before it is drawn.
    @Inject(method = "renderLevel", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void puremetal$prepareTemporal(final CallbackInfo ci) {
        TemporalUpscaler.hookRan(TemporalUpscaler.HOOK_PREPARE);
        if (RenderScale.temporalFrame()) {
            RenderScale.prepareTemporal(this.mainRenderTarget, this.hud3DTarget, !this.appliedPostEffects.isEmpty());
        }
    }

    // The hand is part of the jittered image and fixed on screen, so it gets the same jitter.
    @WrapOperation(method = "render3dHud", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice puremetal$jitterHandProjection(final ProjectionMatrixBuffer buffer, final Projection projection, final Operation<GpuBufferSlice> original) {
        TemporalUpscaler.hookRan(TemporalUpscaler.HOOK_HAND);
        if (!RenderScale.temporalFrame()) {
            return original.call(buffer, projection);
        }
        return original.call(buffer, TemporalUpscaler.handProjection(projection));
    }
}
