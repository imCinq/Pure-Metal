package io.github.imcinq.puremetal.mixin.ui.sodium;

import net.caffeinemc.mods.sodium.client.gui.widgets.AbstractParentWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reaches the protected addRenderableChild, which Sodium declares on AbstractParentWidget rather than on the option list. */
@Pseudo
@Mixin(value = AbstractParentWidget.class, remap = false)
public interface AbstractParentWidgetInvoker {
    @Invoker("addRenderableChild")
    <T extends GuiEventListener & Renderable> T puremetal$addRenderableChild(T widget);
}
