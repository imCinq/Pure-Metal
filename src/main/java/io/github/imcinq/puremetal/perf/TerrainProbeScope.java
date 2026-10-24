package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Scoped association with actual submission calls. Never suppresses commands; off by default. */
public final class TerrainProbeScope implements AutoCloseable {
    public static final boolean ENABLED=Boolean.getBoolean("puremetal.terrainOcclusionProbe");
    private static final ThreadLocal<TerrainProbeScope> ACTIVE=new ThreadLocal<>();
    public final boolean sodium;
    public final int regionX,regionY,regionZ;
    private final List<TerrainDrawProfile.Range> ranges;
    private final TerrainProbeScope previous;
    private int command;
    private TerrainProbeScope(List<TerrainDrawProfile.Range> ranges,boolean sodium,int x,int y,int z) {
        this.ranges=ranges;this.sodium=sodium;regionX=x;regionY=y;regionZ=z;previous=ACTIVE.get();ACTIVE.set(this);
    }
    public static TerrainProbeScope current() {return ACTIVE.get();}
    public static TerrainProbeScope batch(Object owner,int first,int count,boolean sodium,int x,int y,int z) {
        var selected=new ArrayList<TerrainDrawProfile.Range>();
        for(var range:TerrainDrawProfile.metadata(owner)) {
            int start=Math.max(first,range.first()),end=Math.min(first+count,range.first()+range.count());
            if(end>start) selected.add(new TerrainDrawProfile.Range(range.x(),range.y(),range.z(),start-first,end-start,range.bounds()));
        }
        return new TerrainProbeScope(List.copyOf(selected),sodium,x,y,z);
    }
    public static TerrainProbeScope direct(Collection<?> draws) {
        var selected=new ArrayList<TerrainDrawProfile.Range>();int index=0;
        for(Object draw:draws) {
            var metadata=TerrainDrawProfile.metadata(draw);
            if(!metadata.isEmpty()) {var range=metadata.getFirst();selected.add(new TerrainDrawProfile.Range(range.x(),range.y(),range.z(),index,1,range.bounds()));}
            index++;
        }
        return new TerrainProbeScope(List.copyOf(selected),false,0,0,0);
    }
    public List<TerrainDrawProfile.Range> consume(int count) {
        int first=command;command+=count;
        return ranges.stream().filter(r->r.bounds()!=null && (long)r.first()+r.count()>first && r.first()<command).toList();
    }
    @Override public void close() {ACTIVE.set(previous);}
}
