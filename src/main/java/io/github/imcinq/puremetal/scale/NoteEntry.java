package io.github.imcinq.puremetal.scale;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

/** A non-interactive Video Settings row whose height follows the wrapped note text. */
public final class NoteEntry extends OptionsList.AbstractEntry {
    private final FocusableTextWidget note;

    public NoteEntry(final FocusableTextWidget note) {
        this.note = note;
    }

    public int rowHeight() {
        return note.getHeight() + 4;
    }

    @Override
    public void extractContent(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final boolean hovered, final float partialTick) {
        note.setPosition(getContentX() + (getContentWidth() - note.getWidth()) / 2, getContentY() + 2);
        note.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return List.of();
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return List.of();
    }
}
