package io.github.imcinq.puremetal.lod;

/** Built LOD state carried by compile results and section meshes (added by mixins). */
public interface LodState {
    byte puremetal$lodState();

    void puremetal$setLodState(byte state);

    /** A rebuild for this mesh's LOD level was already requested; don't ask again until a new mesh arrives. */
    boolean puremetal$lodRequested();

    void puremetal$setLodRequested(boolean requested);
}
