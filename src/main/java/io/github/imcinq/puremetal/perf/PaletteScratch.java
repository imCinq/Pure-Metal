package io.github.imcinq.puremetal.perf;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;

/** Scratch used only inside pack/unpack; SimpleBitStorage consumes the ints before that scope ends. */
public final class PaletteScratch {
    private static final ThreadLocal<PaletteScratch> LOCAL = ThreadLocal.withInitial(PaletteScratch::new);
    // Biomes (64 entries) and blocks (4096 entries) alternate on chunk save/load.
    // Keep them separate so one does not evict the other's warmed buffer.
    private final int[][] buffers = new int[4][];
    private final int[][] smallBuffers = new int[4][];
    private int depth;

    public static void enter() { LOCAL.get().depth++; }
    public static void exit() { LOCAL.get().depth--; }

    public static <T> int[] reencode(BitStorage storage, Palette<T> source, Palette<T> target) {
        PaletteScratch scratch = LOCAL.get();
        int size = storage.getSize();
        int slot = scratch.depth - 1;
        int[] values;
        if (slot >= 0 && slot < scratch.buffers.length && size <= 4096) {
            int[][] buffers = size <= 64 ? scratch.smallBuffers : scratch.buffers;
            values = buffers[slot];
            if (values == null || values.length != size) {
                values = buffers[slot] = new int[size];
            }
        } else {
            values = new int[size];
        }
        storage.unpack(values);
        PaletteResize<T> resize = PaletteResize.noResizeExpected();
        int previous = -1;
        int mapped = -1;
        for (int i = 0; i < values.length; i++) {
            int id = values[i];
            if (id != previous) {
                previous = id;
                mapped = target.idFor(source.valueFor(id), resize);
            }
            values[i] = mapped;
        }
        return values;
    }
}
