package io.github.imcinq.puremetal.lod;

/** Main-thread transition scans keep their original cadence, but unchanged stationary meshes need no scan. */
public final class LodScanSchedule {
    private int frames;
    private boolean pending;

    public void meshArrived() { this.pending = true; }

    public boolean shouldScan(boolean moved, boolean hasSections) {
        if (!hasSections || (!moved && ++this.frames < 20)) return false;
        this.frames = 0;
        if (!moved && !this.pending) return false;
        this.pending = false;
        return true;
    }

    public void continueNextFrame() {
        this.frames = 20;
        this.pending = true;
    }
}
