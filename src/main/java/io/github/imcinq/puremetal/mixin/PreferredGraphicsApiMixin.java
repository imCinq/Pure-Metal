package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.Redact;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuBackend;
import io.github.imcinq.puremetal.backend.PureMetalBackend;
import io.github.imcinq.puremetal.backend.StartupGuard;
import net.minecraft.client.PreferredGraphicsApi;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pure Metal's only hook. With Graphics API set to "Default", Metal is tried first and the vanilla backends stay as
 * fallbacks. Choosing OpenGL or Vulkan in Video Settings skips Metal entirely. Minecraft's own startup-crash guard
 * forces OpenGL after an unfinished start; {@link StartupGuard} undoes that when Metal was not involved.
 */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {
    @Unique
    private static final Logger PUREMETAL_LOGGER = LogUtils.getLogger();

    @Unique
    private static final AtomicBoolean PUREMETAL_NOT_DEFAULT_LOGGED = new AtomicBoolean();

    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void puremetal$preferMetal(final CallbackInfoReturnable<GpuBackend[]> cir) {
        if (StartupGuard.begin((PreferredGraphicsApi)(Object)this)) {
            cir.setReturnValue(PreferredGraphicsApi.DEFAULT.getBackendsToTry());
            return;
        }
        if ((Object)this != PreferredGraphicsApi.DEFAULT) {
            if (PUREMETAL_NOT_DEFAULT_LOGGED.compareAndSet(false, true)) {
                PUREMETAL_LOGGER.info("Pure Metal is not used because Graphics API is set to {} in Video Settings (Minecraft also sets this itself after an unclean previous startup); set it to Default to use Metal", this);
            }
            return;
        }
        String reason = PureMetalBackend.unsupportedReason();
        if (reason != null) {
            PUREMETAL_LOGGER.info("Pure Metal is off because {}; using Minecraft's own renderer", Redact.home(reason));
            return;
        }
        GpuBackend[] vanilla = cir.getReturnValue();
        GpuBackend[] backends = new GpuBackend[vanilla.length + 1];
        backends[0] = new PureMetalBackend();
        System.arraycopy(vanilla, 0, backends, 1, vanilla.length);
        cir.setReturnValue(backends);
    }
}
