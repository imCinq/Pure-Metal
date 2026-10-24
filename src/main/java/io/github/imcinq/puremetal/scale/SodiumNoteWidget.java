package io.github.imcinq.puremetal.scale;

import io.github.imcinq.puremetal.Lang;
import java.util.List;
import net.caffeinemc.mods.sodium.client.gui.options.control.AbstractOptionList;
import net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.util.FormattedCharSequence;

/** Plain, non-focusable text row under Pure Metal's title in Sodium's option list. Only loaded by the Sodium list mixin. */
public final class SodiumNoteWidget extends AbstractWidget {
    private static final int PADDING_X = 6;
    private static final int PADDING_Y = 3;
    private static final int COLOR = 0xFFE0E0E0;
    private static final int BACKGROUND = 0x90000000;

    private final AbstractOptionList list;
    private final List<FormattedCharSequence> lines;

    private SodiumNoteWidget(final AbstractOptionList list, final Dim2i dim, final List<FormattedCharSequence> lines) {
        super(dim);
        this.list = list;
        this.lines = lines;
    }

    /** Wraps the note to the row width; the row height grows with the wrapped lines. */
    public static SodiumNoteWidget create(final AbstractOptionList list, final int x, final int y, final int width) {
        final var font = Minecraft.getInstance().font;
        final List<FormattedCharSequence> lines = font.split(Lang.text("puremetal.options.note"), Math.max(20, width - PADDING_X * 2));
        return new SodiumNoteWidget(list, new Dim2i(x, y, width, lines.size() * font.lineHeight + PADDING_Y * 2), lines);
    }

    public int height() {
        return getDimensions().height();
    }

    @Override
    public int getY() {
        return super.getY() - list.getScrollAmount();
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), BACKGROUND);
        int y = getY() + PADDING_Y;
        for (final FormattedCharSequence line : lines) {
            graphics.text(font, line, getX() + PADDING_X, y, COLOR);
            y += font.lineHeight;
        }
    }

    @Override
    public ComponentPath nextFocusPath(final FocusNavigationEvent event) {
        return null;
    }
}
